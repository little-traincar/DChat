package com.dchat.app.ui.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.dchat.app.data.droid.DroidHost
import com.dchat.app.data.tools.PyResult
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.data.tools.ToolRegistry
import com.dchat.app.data.tools.UserToolStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * 工具 WebView 的 JS 桥（规范 `docs/spec/tool-web-ui.md` §3）。
 *
 * 页面里用 `window.dchat.xxx(...)` 访问；对象名必须是 [NAME]（`dchat`）：
 *
 * ```js
 * const r = dchat.call("device", "{}");   // {"ok":true,"result":{...}}
 * dchat.saveState(JSON.stringify(state)); // ≤256KB，按 工具 + 会话 落盘
 * const raw = dchat.loadState();          // 无则 "{}"
 * dchat.close();                          // 退出工具页
 * ```
 *
 * 说明：
 * - `@JavascriptInterface` 方法在 WebView 的 JavaBridge 线程执行（不是主线程），
 *   所以 `DroidHost.call` / `PyRuntime.runCode` 的阻塞调用天然满足“不得阻塞主线程”；
 * - `call` 原样透传 DroidHost 的 `{"ok":...}` JSON（权限档位/超时/错误码全部沿用 droid-api）；
 * - `run` 执行**当前工具自己的** `entry.py` 顶层函数（默认 `run`），走现有 Python 执行链，
 *   同一页面内部用 Mutex 串行；页面关闭时取消进行中的调用（返回 CANCELLED）；
 * - `saveState/loadState` 只写/读 `filesDir/tools/<toolId>/state/web_<convId>.json`，单文件 ≤256KB；
 * - `close` 只负责通知宿主退出，真正退出在主线程执行（宿主传入回调）。
 */
class ToolWebBridge(
    private val appContext: Context,
    private val toolId: String,
    private val conversationId: String?,
    /** 会话权限档位：read / workspace / full（沿用 Conversation.PERMISSION_*） */
    private val permission: String,
    /** 宿主提供当前 WebView（异步结果用 evaluateJavascript 回传；页面销毁后返回 null） */
    private val webViewProvider: () -> WebView?,
    /** 退出工具页（宿主保证切主线程） */
    private val onClose: () -> Unit,
    /** 首次 run/runAsync 完成时回调一次（宿主用于 “web open: firstRun=…ms” 计时，默认忽略） */
    private val onFirstRun: () -> Unit = {}
) {

    /** 同一页面内的 Python 调用串行（JS 本身单线程，这里防重入/桥线程并发） */
    private val runMutex = Mutex()

    @Volatile
    private var activeRunJob: Job? = null

    @Volatile
    private var closed = false

    /** 异步 run 的协程作用域：每个页面一个，页面销毁时取消 pending job */
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** cbId → 正在跑的 runAsync job（页面关闭时逐个取消并回 CANCELLED） */
    private val pendingAsync: MutableMap<String, Job> =
        java.util.Collections.synchronizedMap(LinkedHashMap<String, Job>())

    /** 每个 cbId 只回调一次（自然完成 / 关闭取消 二选一） */
    private val asyncDelivered: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val firstRunDone = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 页面关闭时由宿主调用：标记关闭、取消进行中的 run/runAsync 并各回一次 CANCELLED */
    fun cancelInFlight() {
        closed = true
        runCatching { activeRunJob?.cancel() }
        val pending: List<Pair<String, Job>> = synchronized(pendingAsync) {
            val list = pendingAsync.entries.map { it.key to it.value }
            pendingAsync.clear()
            list
        }
        for ((cbId, job) in pending) {
            deliverAsync(cbId, errorJson("CANCELLED", "工具页已关闭"))
            runCatching { job.cancel() }
        }
    }

    /** 同步调用 droid 能力，返回 JSON 字符串；任何异常都转成错误 JSON，不向 JS 抛。 */
    @JavascriptInterface
    fun call(method: String, argsJson: String): String = try {
        DroidHost.call(
            method,
            argsJson,
            toolId,
            conversationId.orEmpty(),
            permission
        )
    } catch (t: Throwable) {
        Log.w(TAG, "droid call failed via web bridge: $method", t)
        errorJson("DROID_ERROR", t.message ?: t.javaClass.simpleName)
    }

    /**
     * 同步版 `run`（兼容旧页面）：在 JS 的 JavaBridge 线程上阻塞，直到结果返回。
     *
     * 返回 `{"ok":true,"result":"<字符串>"}`；失败
     * `{"ok":false,"code":"TOOL_TIMEOUT|TOOL_ERROR|PERMISSION_DENIED|PY_UNAVAILABLE|CANCELLED","error":...}`。
     * 新页面建议用 [runAsync]（不阻塞 JS）。
     */
    @JavascriptInterface
    fun run(func: String, argsJson: String): String {
        val out = try {
            runBlocking {
                activeRunJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                try {
                    when (val prep = prepareRun(func, argsJson)) {
                        is RunSlot.Err -> prep.json
                        is RunSlot.Ok -> executeRun(prep)
                    }
                } finally {
                    activeRunJob = null
                }
            }
        } catch (c: CancellationException) {
            errorJson("CANCELLED", "调用已取消")
        } catch (t: Throwable) {
            Log.w(TAG, "python run failed (tool=$toolId func=$func)", t)
            errorJson("TOOL_ERROR", t.message ?: t.javaClass.simpleName)
        }
        onFirstRunOnce()
        return out
    }

    /**
     * 异步版 `run`：**立即返回**，后台线程（IO 协程）执行本工具 entry.py；
     * 完成后主线程调用页面回调：
     *
     * ```js
     * window.__dchatRunResult && window.__dchatRunResult(cbId, {ok:true,result:"..."});
     * ```
     *
     * 同一页面内与同步 `run` 共用 [runMutex] 串行；页面关闭/取消时对每个未完成 cbId
     * 回调一次 `{"ok":false,"code":"CANCELLED"}`（已在 `deliverAsync` 里按 cbId 去重）。
     */
    @JavascriptInterface
    fun runAsync(func: String, argsJson: String, cbId: String) {
        if (closed) {
            deliverAsync(cbId, errorJson("CANCELLED", "工具页已关闭"))
            return
        }
        val job = asyncScope.launch {
            val result = try {
                when (val prep = prepareRun(func, argsJson)) {
                    is RunSlot.Err -> prep.json
                    is RunSlot.Ok -> executeRun(prep)
                }
            } catch (c: CancellationException) {
                errorJson("CANCELLED", "调用已取消")
            } catch (t: Throwable) {
                Log.w(TAG, "python runAsync failed (tool=$toolId func=$func)", t)
                errorJson("TOOL_ERROR", t.message ?: t.javaClass.simpleName)
            }
            // 页面已关闭：统一回 CANCELLED（自然完成但关闭竞态时也只回调一次）
            deliverAsync(cbId, if (closed) errorJson("CANCELLED", "工具页已关闭") else result)
        }
        pendingAsync[cbId] = job
        job.invokeOnCompletion {
            pendingAsync.remove(cbId)
            onFirstRunOnce()
        }
    }

    /** 参数/权限校验（同步/异步共用）；错误时直接给结构化 JSON，避免后面再判断 */
    private sealed class RunSlot {
        class Err(val json: String) : RunSlot()
        class Ok(
            val name: String,
            val mode: String,
            val convId: String,
            val timeoutMs: Int,
            val code: String,
            val argsJson: String
        ) : RunSlot()
    }

    private fun prepareRun(func: String, argsJson: String): RunSlot {
        val name = func.trim().ifBlank { "run" }
        if (!FUNC_NAME.matches(name)) {
            return RunSlot.Err(errorJson("TOOL_ERROR", "函数名不合法：$name"))
        }
        if (closed) return RunSlot.Err(errorJson("CANCELLED", "工具页已关闭"))
        val def = UserToolStore.find(appContext, toolId)
            ?: return RunSlot.Err(errorJson("TOOL_ERROR", "工具不存在或已被删除：$toolId"))
        val mode = effectivePermission()
        if (!runCatching { ToolRegistry.canExecute(toolId, mode) }.getOrDefault(false)) {
            return RunSlot.Err(errorJson("PERMISSION_DENIED", "当前会话权限不足以执行该工具（$mode）"))
        }
        val convId = conversationId?.takeIf { it.isNotBlank() } ?: "web"
        val timeoutMs = runCatching { ToolRegistry.byId(toolId)?.timeoutMs }.getOrNull() ?: 30_000
        return RunSlot.Ok(name, mode, convId, timeoutMs, def.code, argsJson.ifBlank { "{}" })
    }

    /** 真正执行 Python（挂起，可被取消）；调用方负责串行 Mutex */
    private suspend fun executeRun(prep: RunSlot.Ok): String = runMutex.withLock {
        if (closed) {
            errorJson("CANCELLED", "工具页已关闭")
        } else {
            val r = PyRuntime.runCode(
                context = appContext,
                toolId = toolId,
                entryFunction = prep.name,
                code = prep.code,
                argsJson = prep.argsJson,
                conversationId = prep.convId,
                timeoutMs = prep.timeoutMs,
                conversationMode = prep.mode
            )
            formatRunResult(r)
        }
    }

    /** 主线程把结果回传给 JS（每个 cbId 只调一次；页面已销毁/JS 未定义则静默丢弃） */
    private fun deliverAsync(cbId: String, resultJson: String) {
        if (!asyncDelivered.add(cbId)) return
        onFirstRunOnce()
        val js = "(function(){try{var f=window.__dchatRunResult;if(typeof f==='function'){f(" +
            JSONObject.quote(cbId) + "," + resultJson + ");}}catch(e){}})();"
        val deliver: () -> Unit = {
            val wv = runCatching { webViewProvider() }.getOrNull()
            if (wv != null) {
                runCatching { wv.evaluateJavascript(js, null) }
                    .onFailure { Log.w(TAG, "evaluateJavascript failed cb=$cbId", it) }
            }
        }
        // 已在主线程（页面关闭取消路径）就直接投递，保证 destroy 之前把 CANCELLED 交给页面；
        // 后台完成路径统一 post 到主线程。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver()
        } else {
            mainHandler.post { deliver() }
        }
    }

    private fun onFirstRunOnce() {
        if (firstRunDone.compareAndSet(false, true)) {
            runCatching { onFirstRun() }
        }
    }

    /** 保存页面状态：原子写 `state/web_<convId>.json`；超过 256KB 拒绝并返回结构化错误。 */
    @JavascriptInterface
    fun saveState(json: String): String {
        return try {
            if (json.isBlank()) return okJson()
            val bytes = json.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_STATE_BYTES) {
                Log.w(TAG, "saveState ignored: ${bytes.size} bytes > $MAX_STATE_BYTES (tool=$toolId)")
                return errorJson("DROID_ERROR", "状态超过 256KB，未保存")
            }
            val target = stateFile()
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            }
            okJson()
        } catch (t: Throwable) {
            Log.w(TAG, "saveState failed (tool=$toolId)", t)
            errorJson("DROID_ERROR", "保存状态失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 读取页面状态；无文件/超限按契约返回 "{}"，异常返回结构化错误 JSON（仍是合法 JSON）。 */
    @JavascriptInterface
    fun loadState(): String {
        return try {
            val f = stateFile()
            when {
                !f.isFile -> "{}"
                f.length() > MAX_STATE_BYTES -> {
                    Log.w(TAG, "loadState ignored: ${f.length()} bytes > $MAX_STATE_BYTES (tool=$toolId)")
                    "{}"
                }
                else -> f.readText().ifBlank { "{}" }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadState failed (tool=$toolId)", t)
            errorJson("DROID_ERROR", "读取状态失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 退出工具页（宿主回调里切主线程）；返回结构化 JSON，异常不抛给 JS。 */
    @JavascriptInterface
    fun close(): String {
        return try {
            onClose()
            okJson()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed (tool=$toolId)", t)
            errorJson("DROID_ERROR", "关闭工具页失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun stateFile(): File {
        // conversationId 来自 App 内部（通常是 UUID），这里再兜一层，杜绝路径穿越
        val safeConv = conversationId.orEmpty()
            .replace(UNSAFE_ID, "_")
            .ifBlank { "default" }
        return File(UserToolStore.stateDir(appContext, toolId), "web_$safeConv.json")
    }

    /**
     * 当前会话权限档位：ToolUiScreen 已按 AppState 当前会话传入；非法/空则安全回落到 read（只读），
     * 绝不硬编码 full。
     */
    private fun effectivePermission(): String {
        val p = permission.trim().lowercase()
        if (p == "read" || p == "workspace" || p == "full") return p
        Log.w(TAG, "unknown session permission='$permission' tool=$toolId, fallback read_only")
        return "read"
    }

    /** 把 PyResult 转成 JS 约定的 JSON：「结果 dict/list 已由 runner 序列化成字符串」 */
    private fun formatRunResult(r: PyResult): String {
        if (!r.ok) {
            val code = when (r.errorCode) {
                "TOOL_TIMEOUT" -> "TOOL_TIMEOUT"
                "PY_UNAVAILABLE" -> "PY_UNAVAILABLE"
                "PERMISSION_DENIED" -> "PERMISSION_DENIED"
                else -> "TOOL_ERROR"
            }
            val detail = (r.errorMessage ?: "").ifBlank { r.errorCode ?: "工具函数执行失败" }
            return errorJson(code, detail.take(1_000))
        }
        val out = truncateUtf8(r.output ?: "", MAX_RESULT_BYTES)
        return JSONObject()
            .put("ok", true)
            .put("result", out)
            .toString()
    }

    /** 结果截断到 4MB（按 UTF-8 边界，避免切断多字节字符） */
    private fun truncateUtf8(text: String, limitBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limitBytes) return text
        var end = limitBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + "\n...(结果超过 4MB，已截断)"
    }

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("error", message.take(300))
        .toString()

    private fun okJson(): String = JSONObject().put("ok", true).toString()

    companion object {
        /** JS 侧对象名：`window.dchat` */
        const val NAME = "dchat"
        private const val TAG = "ToolWebBridge"
        private const val MAX_STATE_BYTES = 256 * 1024
        private const val MAX_RESULT_BYTES = 4 * 1024 * 1024
        private val UNSAFE_ID = Regex("[^A-Za-z0-9._-]")
        private val FUNC_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    }
}
