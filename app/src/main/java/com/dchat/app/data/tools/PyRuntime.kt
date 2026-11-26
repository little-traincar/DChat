package com.dchat.app.data.tools

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.dchat.app.ui.lang.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Python 运行时池级不可用：带产品恢复入口，避免用户只看到一个死错误 */
private fun pyUnavailableMessage(detail: String?): String {
    val base = "Python 运行时不可用；可在设置 → 工具 → 重置 Python 运行环境"
    val d = detail?.trim().orEmpty()
    return if (d.isBlank() || d == "Python 运行时不可用") base else "$base（$d）"
}

/** 工具产出的一个落盘文件（imagegen 一次可产出多张） */
data class PyFile(
    val path: String,
    val mime: String,
    val sizeBytes: Long
)

/** imagegen 多张生成时按序号记录失败项（成功的图仍保留） */
data class PyFailure(
    val index: Int,
    val message: String
)

/** 一次 Python 工具执行结果（4a：只有字符串结果/错误码，4b 再加 render/UI 状态） */
data class PyResult(
    val ok: Boolean,
    val output: String = "",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** local.download：落盘文件绝对路径 */
    val filePath: String? = null,
    val mime: String? = null,
    val sizeBytes: Long = 0L,
    val downloadStatus: String? = null,
    /** local.imagegen：本次成功落盘的全部图片 */
    val files: List<PyFile> = emptyList(),
    /** local.imagegen：部分失败时按张序号列出的原因 */
    val failures: List<PyFailure> = emptyList()
)

/**
 * 第四期 4a：内嵌 CPython（Chaquopy）运行时。
 *
 * - 首次启动在后台线程 `Python.start(AndroidPlatform)`，失败降级为 [State.FAILED]，
 *   工具入口/编辑器显示「Python 运行时不可用」，App 不崩；
 * - 所有用户代码在单线程 Executor 里执行（解释器全局串行），Kotlin 侧 `Future.get(timeout)`；
 *   超时后**只能取消 Future，无法强杀 Python 线程**（报告里注明），返回 TOOL_TIMEOUT；
 * - 输出超过 64KB 截断（保头尾 + 省略字符数）；
 * - import 白名单/沙箱 open 由内置 `src/main/python/dchat_runner.py` 执行。
 */
object PyRuntime {

    enum class State { NOT_STARTED, STARTING, READY, FAILED }

    /** 诊断页接入点：每工具进程 + local.python 池 */
    fun toolProcSnapshot(): List<ProcInfo> = ToolProcessRegistry.snapshot()

    fun restartToolProcess(toolId: String) = ToolProcessRegistry.restart(toolId)

    fun restartAllToolProcesses() = ToolProcessRegistry.restartAll()

    var state by mutableStateOf(State.NOT_STARTED)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private const val OUTPUT_LIMIT = 64 * 1024
    private const val INIT_WAIT_MS = 20_000L

    private val initLock = Any()
    private val runner = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dchat-python-tool").apply { isDaemon = true }
    }
    /**
     * 后台任务（bg.run）专用线程池。
     *
     * 前台工具调用走 [runner]（单线程，解释器全局串行，避免用户代码互相干扰）；
     * 但 bg.run 的名额上限是 5，若和前台共用一个线程，5 个 python 任务会串行跑完，
     * 后面的必然超时（真机实测 3 个 60s TOOL_TIMEOUT）。这里给后台 4 个线程：
     * GIL 仍会串行执行字节码，但 `time.sleep`/IO 会释放 GIL，并发任务才能真的并行等待。
     */
    private val bgRunner = Executors.newFixedThreadPool(5) { r ->
        Thread(r, "dchat-python-bg").apply { isDaemon = true }
    }

    fun initAsync(context: Context) {
        ToolProcessRegistry.cleanupStale(context)
        synchronized(initLock) {
            if (state != State.NOT_STARTED) return
            state = State.STARTING
            val app = context.applicationContext ?: context
            Thread({
                try {
                    if (!Python.isStarted()) Python.start(AndroidPlatform(app))
                    // 提前加载运行器，初始化失败能尽早暴露
                    Python.getInstance().getModule("dchat_runner")
                    state = State.READY
                    Log.i("PyRuntime", "Python runtime ready")
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                    state = State.FAILED
                    Log.w("PyRuntime", "Python runtime init failed", t)
                }
            }, "dchat-python-init").apply { isDaemon = true }.start()
        }
    }

    fun awaitReady(timeoutMs: Long = INIT_WAIT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (state == State.STARTING && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(50) }
        }
        return state == State.READY
    }

    /**
     * 4a-2：local.python 裸代码模式（不是 def run 工具）。
     * 代码在 sandbox 中执行，返回 stdout + 最后一个表达式的值；超时/截断逻辑同 [runCode]。
     */
    suspend fun runPythonCode(
        context: Context,
        toolId: String = "local.python",
        code: String,
        argsJson: String = "{}",
        conversationId: String,
        timeoutMs: Int = 5_000,
        conversationMode: String = "workspace",
        /** true = 后台任务（bg.run）执行，走独立线程池，允许多个任务真并发 */
        background: Boolean = false,
        /** 内部降级标记：false = 强制进程内，避免 PyProcessRuntime 回退时递归 */
        viaChild: Boolean = true
    ): PyResult = withContext(Dispatchers.IO) {
        if (viaChild && PyProcessRuntime.shouldUseChild()) {
            return@withContext PyProcessRuntime.runPythonCode(
                context = context,
                toolId = toolId,
                code = code,
                argsJson = argsJson,
                conversationId = conversationId,
                timeoutMs = timeoutMs,
                conversationMode = conversationMode,
                background = background
            )
        }
        if (state == State.FAILED) {
            return@withContext PyResult(ok = false, errorCode = "PY_UNAVAILABLE", errorMessage = pyUnavailableMessage(lastError))
        }
        if (!awaitReady()) {
            return@withContext PyResult(ok = false, errorCode = "PY_UNAVAILABLE", errorMessage = pyUnavailableMessage(lastError))
        }
        UserToolStore.ensureDirs(context, toolId)
        val sandbox = UserToolStore.sandboxDir(context, toolId)
        val sitePackages = UserToolStore.sitePackagesDir(context, toolId)
        val statePath = UserToolStore.stateFile(context, toolId, conversationId).also { it.parentFile?.mkdirs() }
        try {
            val executor = if (background) bgRunner else runner
            val future = executor.submit(Callable {
                val module = Python.getInstance().getModule("dchat_runner")
                module.callAttr(
                    "execute_code",
                    code,
                    argsJson,
                    sandbox.absolutePath,
                    sitePackages.absolutePath,
                    statePath.absolutePath,
                    conversationMode
                ).toString()
            })
            val raw = try {
                future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                return@withContext PyResult(
                    ok = false,
                    errorCode = "TOOL_TIMEOUT",
                    errorMessage = "Python 代码执行超时（${timeoutMs / 1000}s），已丢弃结果"
                )
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                return@withContext PyResult(
                    ok = false,
                    errorCode = "TOOL_ERROR",
                    errorMessage = cause.message ?: cause.javaClass.simpleName
                )
            }
            parseResult(raw)
        } catch (t: Throwable) {
            PyResult(ok = false, errorCode = "TOOL_ERROR", errorMessage = t.message ?: t.javaClass.simpleName)
        }
    }

    /** 跑已保存到磁盘的用户工具 */
    suspend fun run(
        context: Context,
        tool: UserToolDef,
        argsJson: String,
        conversationId: String,
        conversationMode: String = "workspace",
        background: Boolean = false,
        viaChild: Boolean = true
    ): PyResult {
        if (viaChild && PyProcessRuntime.shouldUseChild()) {
            return PyProcessRuntime.run(context, tool, argsJson, conversationId, conversationMode, background)
        }
        return runCode(
            context = context,
            toolId = tool.id,
            entryFunction = tool.entryFunction,
            code = tool.code,
            argsJson = argsJson,
            conversationId = conversationId,
            timeoutMs = ToolRegistry.byId(tool.id)?.timeoutMs ?: 5_000,
            conversationMode = conversationMode,
            background = background,
            viaChild = false
        )
    }

    /** 编辑器「试运行」：不要求先保存，把当前代码写到临时入口文件再执行 */
    suspend fun runCode(
        context: Context,
        toolId: String,
        entryFunction: String,
        code: String,
        argsJson: String,
        conversationId: String,
        timeoutMs: Int = 5_000,
        conversationMode: String = "workspace",
        /** true = 后台任务（bg.run）执行，走独立线程池，允许多个任务真并发 */
        background: Boolean = false,
        /** 内部降级标记：false = 强制进程内，避免 PyProcessRuntime 回退时递归 */
        viaChild: Boolean = true
    ): PyResult = withContext(Dispatchers.IO) {
        if (viaChild && PyProcessRuntime.shouldUseChild()) {
            return@withContext PyProcessRuntime.runCode(
                context = context,
                toolId = toolId,
                entryFunction = entryFunction,
                code = code,
                argsJson = argsJson,
                conversationId = conversationId,
                timeoutMs = timeoutMs,
                conversationMode = conversationMode,
                background = background
            )
        }
        if (state == State.FAILED) {
            return@withContext PyResult(
                ok = false,
                errorCode = "PY_UNAVAILABLE",
                errorMessage = pyUnavailableMessage(lastError)
            )
        }
        if (!awaitReady()) {
            return@withContext PyResult(
                ok = false,
                errorCode = "PY_UNAVAILABLE",
                errorMessage = pyUnavailableMessage(lastError)
            )
        }
        UserToolStore.ensureDirs(context, toolId)
        val dir = UserToolStore.toolDir(context, toolId)
        val tempEntry = File(dir, "entry_preview.py").apply {
            writeText(code)
        }
        val sandbox = UserToolStore.sandboxDir(context, toolId)
        val sitePackages = UserToolStore.sitePackagesDir(context, toolId)
        val statePath = UserToolStore.stateFile(context, toolId, conversationId).also {
            it.parentFile?.mkdirs()
        }
        try {
            val executor = if (background) bgRunner else runner
            val future = executor.submit(Callable {
                val module = Python.getInstance().getModule("dchat_runner")
                val raw = module.callAttr(
                    "execute",
                    tempEntry.absolutePath,
                    entryFunction,
                    argsJson,
                    sandbox.absolutePath,
                    sitePackages.absolutePath,
                    statePath.absolutePath,
                    conversationMode
                ).toString()
                raw
            })
            val raw = try {
                future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // Python 线程无法强杀：丢弃结果，仅取消 Future
                future.cancel(true)
                return@withContext PyResult(
                    ok = false,
                    errorCode = "TOOL_TIMEOUT",
                    errorMessage = "Python 工具执行超时（${timeoutMs / 1000}s），已丢弃结果"
                )
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                return@withContext PyResult(
                    ok = false,
                    errorCode = "TOOL_ERROR",
                    errorMessage = cause.message ?: cause.javaClass.simpleName
                )
            }
            parseResult(raw)
        } catch (t: Throwable) {
            PyResult(
                ok = false,
                errorCode = "TOOL_ERROR",
                errorMessage = t.message ?: t.javaClass.simpleName
            )
        } finally {
            runCatching { tempEntry.delete() }
        }
    }

    private fun parseResult(raw: String): PyResult {
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return PyResult(ok = true, output = truncate(raw))
        return if (o.optBoolean("ok", false)) {
            PyResult(ok = true, output = truncate(o.optString("result")))
        } else {
            PyResult(
                ok = false,
                errorCode = o.optString("code").ifBlank { "TOOL_ERROR" },
                errorMessage = o.optString("error").ifBlank { "Python 工具执行失败" }
            )
        }
    }

    private fun truncate(raw: String): String {
        if (raw.length <= OUTPUT_LIMIT) return raw
        val half = OUTPUT_LIMIT / 2
        val omitted = raw.length - OUTPUT_LIMIT
        val marker = com.dchat.app.ui.lang.Lang.current.format(
            com.dchat.app.ui.lang.Lang.current.chat.toolOutputTruncated,
            omitted
        )
        return raw.substring(0, half) + "\n" + marker + "\n" + raw.substring(raw.length - half)
    }
}
