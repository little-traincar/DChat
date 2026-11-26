package com.dchat.app.data.tools

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.util.Log
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 第四期 4c：后台任务 `bg.*` + 前台服务 + 通知。
 *
 * - 受控动作白名单（**不接受任意 shell**）：[ACTION_RUN_USER_TOOL] / [ACTION_HTTP] /
 *   [ACTION_NOTIFY] / [ACTION_PYTHON]（python 还要求 local.python 开关打开）；
 * - 并发上限 [MAX_CONCURRENT] = 5：超出进入 QUEUED 队列（不拒绝），有空位自动补位；
 * - 任务状态持久化到 `filesDir/bg_tasks.json`，App 重启后仍可 `bg.list`；
 *   重启前处于 RUNNING / QUEUED 的任务一律标 FAILED（TASK_INTERRUPTED），**不自动续跑**；
 * - App 在前台时任务只在进程内跑协程（不起 FGS）；切后台才补前台服务 + 常驻通知；
 *   任务完成发通知（Android 13+ 若未授权通知则静默跳过）。
 */
object BgTasks {

    private const val TAG = "BgTasks"
    private const val FILE_NAME = "bg_tasks.json"
    private const val OUTPUT_LIMIT = 8 * 1024
    private const val RESULT_LIST_LIMIT = 2 * 1024

    const val MAX_CONCURRENT = 5

    const val ACTION_RUN_USER_TOOL = "run_user_tool"
    const val ACTION_HTTP = "http_request"
    const val ACTION_NOTIFY = "notify"
    const val ACTION_PYTHON = "python"

    /**
     * 受控动作白名单（**不接受任意 shell**）。
     *
     * `python` 是任务的一种：后台跑一段 Python 代码，和「跑用户工具」同类。
     * 权限口径（用户定）：后台任务本身要求「完全权限」；`python` 动作**额外**要求
     * `local.python` 工具在设置里是开启的 —— 否则关掉 python 开关也能借道后台执行代码，
     * 那开关就形同虚设。闸门在 [executeAction] 里按执行时刻实时判定。
     */
    val ALLOWED_ACTIONS = setOf(ACTION_RUN_USER_TOOL, ACTION_HTTP, ACTION_NOTIFY, ACTION_PYTHON)

    const val STATUS_QUEUED = "QUEUED"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_DONE = "DONE"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_CANCELLED = "CANCELLED"

    data class TaskRecord(
        val id: String,
        val action: String,
        val paramsJson: String,
        val conversationId: String,
        val status: String,
        val createdAt: Long,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
        val result: String = "",
        val error: String = ""
    )

    private val lock = Any()
    private val tasks = LinkedHashMap<String, TaskRecord>()
    private val jobs = HashMap<String, Job>()
    private val queue = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val counter = AtomicInteger(0)

    @Volatile
    private var appContext: Context? = null
    private var file: File? = null

    @Volatile
    private var initialized = false

    /**
     * App 是否可见（由 MainActivity onStart/onStop 维护）。
     *
     * 可见时后台任务**只在进程内跑协程，不起前台服务**：前台进程不会被回收，
     * 没必要冒 `startForegroundService` 5 秒看门狗超时的风险（主线程忙时 onCreate 排队
     * 超过 5s 会直接 RemoteServiceException，已在 2026-11-26 17:28 复现 3 次）。
     * 切后台时若仍有运行中的任务，再补起 FGS。
     */
    @Volatile
    private var appVisible = false

    fun isAppVisible(): Boolean = appVisible

    /** MainActivity 生命周期回调：可见时撤 FGS（进程内执行），切后台时为运行中任务补 FGS。 */
    fun setAppVisible(context: Context, visible: Boolean) {
        appVisible = visible
        if (!visible) {
            // 先确保初始化拿得到 appContext；init 不再自动 dispatch，不会在这里起新任务
            init(context)
            val running = counts().first
            if (running > 0) {
                val ctx = appContext ?: context.applicationContext ?: context
                BgNotifications.ensurePostPermission(ctx)
                BgTaskService.start(ctx)
                BgTaskService.update(ctx, running, counts().second)
            }
        } else {
            // 回前台：进程内继续跑，撤掉常驻通知（避免不必要的前台服务）
            val ctx = appContext ?: context.applicationContext ?: context
            if (counts().first > 0) BgTaskService.stop(ctx)
        }
    }

    // ---------------------------------------------------------------- 初始化 / 持久化

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext ?: context
            appContext = app
            val f = File(app.filesDir, FILE_NAME)
            file = f
            loadLocked(f)
            // 进程重启后：RUNNING（无存活 Job）与 QUEUED 都不会自动续跑，统一标为中断失败。
            // 这样 App 启动/收到广播时不会因为「恢复排队任务」去调 startForegroundService，
            // 避免主线程繁忙时的 5 秒看门狗超时（RemoteServiceException）。
            var changed = false
            tasks.forEach { (id, rec) ->
                if (rec.status == STATUS_RUNNING || rec.status == STATUS_QUEUED) {
                    tasks[id] = rec.copy(
                        status = STATUS_FAILED,
                        finishedAt = System.currentTimeMillis(),
                        error = "TASK_INTERRUPTED: " + Lang.current.local.bgTaskInterrupted
                    )
                    changed = true
                }
            }
            if (changed) persistLocked()
            queue.clear()
            initialized = true
        }
    }

    fun isAllowedAction(action: String): Boolean = action in ALLOWED_ACTIONS

    private fun loadLocked(f: File) {
        tasks.clear()
        if (!f.isFile) return
        runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                tasks[id] = TaskRecord(
                    id = id,
                    action = o.optString("action"),
                    paramsJson = o.optString("params"),
                    conversationId = o.optString("conversationId"),
                    status = o.optString("status", STATUS_QUEUED),
                    createdAt = o.optLong("createdAt"),
                    startedAt = o.optLong("startedAt"),
                    finishedAt = o.optLong("finishedAt"),
                    result = o.optString("result"),
                    error = o.optString("error")
                )
            }
        }.onFailure { Log.w(TAG, "读取 bg_tasks.json 失败", it) }
    }

    /** 只留最近 200 条，避免文件无限增长 */
    private fun persistLocked() {
        val f = file ?: return
        runCatching {
            if (tasks.size > 200) {
                val drop = tasks.values.sortedBy { it.createdAt }.take(tasks.size - 200)
                drop.forEach { tasks.remove(it.id) }
            }
            val arr = JSONArray()
            tasks.values.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("action", t.action)
                        .put("params", t.paramsJson)
                        .put("conversationId", t.conversationId)
                        .put("status", t.status)
                        .put("createdAt", t.createdAt)
                        .put("startedAt", t.startedAt)
                        .put("finishedAt", t.finishedAt)
                        .put("result", t.result)
                        .put("error", t.error)
                )
            }
            val text = JSONObject().put("version", 1).put("tasks", arr).toString()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(text)
            if (!part.renameTo(f)) {
                f.delete()
                if (!part.renameTo(f)) {
                    f.writeText(text)
                    part.delete()
                }
            }
        }.onFailure { Log.w(TAG, "写入 bg_tasks.json 失败", it) }
    }

    // ---------------------------------------------------------------- 调度

    private fun dispatch() {
        if (!initialized) return
        val started = ArrayList<Job>()
        val records = ArrayList<TaskRecord>()
        synchronized(lock) {
            while (jobs.size < MAX_CONCURRENT && queue.isNotEmpty()) {
                val id = queue.removeFirst()
                val rec = tasks[id] ?: continue
                if (rec.status != STATUS_QUEUED) continue
                val running = rec.copy(status = STATUS_RUNNING, startedAt = System.currentTimeMillis())
                tasks[id] = running
                val job = scope.launch(start = CoroutineStart.LAZY) { runTask(running) }
                jobs[id] = job
                started.add(job)
                records.add(running)
            }
            if (records.isNotEmpty()) persistLocked()
        }
        started.forEach { it.start() }
        if (records.isNotEmpty()) {
            updateService()
            updateForegroundText()
        }
    }

    private fun updateService() {
        val ctx = appContext ?: return
        val (running, queued) = counts()
        if (running > 0) {
            BgNotifications.ensurePostPermission(ctx)
            if (appVisible) {
                // 可见时不起 FGS：进程内协程足够，避免 startForegroundService 5 秒看门狗超时
                BgTaskService.stop(ctx)
            } else {
                BgTaskService.start(ctx)
            }
            BgTaskService.update(ctx, running, queued)
        } else {
            BgTaskService.stop(ctx)
        }
    }

    private fun updateForegroundText() {
        val ctx = appContext ?: return
        val (running, queued) = counts()
        if (running > 0) BgTaskService.update(ctx, running, queued)
    }

    private fun counts(): Pair<Int, Int> = synchronized(lock) {
        tasks.values.count { it.status == STATUS_RUNNING } to tasks.values.count { it.status == STATUS_QUEUED }
    }

    /** 给前台服务读的公开计数（只读快照） */
    fun serviceCounts(): Pair<Int, Int> = counts()

    private fun runTask(rec: TaskRecord) {
        val (ok, output, error) = try {
            executeAction(rec)
        } catch (t: Throwable) {
            Log.w(TAG, "后台任务失败: ${rec.id}", t)
            Triple(false, "", "TOOL_ERROR: " + (t.message ?: t.javaClass.simpleName))
        }
        finish(rec.id, ok, output, error)
    }

    private fun finish(id: String, ok: Boolean, output: String, error: String) {
        var finalRec: TaskRecord? = null
        synchronized(lock) {
            val rec = tasks[id] ?: return
            tasks[id] = rec.copy(
                status = if (rec.status == STATUS_CANCELLED) STATUS_CANCELLED
                else if (ok) STATUS_DONE else STATUS_FAILED,
                finishedAt = System.currentTimeMillis(),
                result = truncate(output, OUTPUT_LIMIT),
                error = truncate(error, OUTPUT_LIMIT)
            )
            finalRec = tasks[id]
            jobs.remove(id)
            persistLocked()
        }
        val done = finalRec ?: return
        if (done.status == STATUS_DONE || done.status == STATUS_FAILED) {
            BgNotifications.notifyCompletion(
                appContext = appContext ?: return,
                taskId = done.id,
                action = done.action,
                ok = done.status == STATUS_DONE,
                text = if (ok) done.result.take(300) else done.error.take(300)
            )
        }
        dispatch()
        updateService()
    }

    // ---------------------------------------------------------------- 提交 / 查询 / 取消

    /** 提交一个后台任务；action 不在白名单时返回 null（调用方报错） */
    fun submit(context: Context, conversationId: String, action: String, params: JSONObject): TaskRecord? {
        init(context)
        if (!isAllowedAction(action)) return null
        val now = System.currentTimeMillis()
        val rec = TaskRecord(
            id = "bg_" + now.toString(36) + "_" + counter.incrementAndGet(),
            action = action,
            paramsJson = params.toString(),
            conversationId = conversationId,
            status = STATUS_QUEUED,
            createdAt = now
        )
        synchronized(lock) {
            tasks[rec.id] = rec
            queue.addLast(rec.id)
            persistLocked()
        }
        dispatch()
        return synchronized(lock) { tasks[rec.id] } ?: rec
    }

    fun list(limit: Int = 50): List<TaskRecord> = synchronized(lock) {
        tasks.values.sortedByDescending { it.createdAt }.take(limit.coerceIn(1, 200))
    }

    // ---------------------------------------------------------------- 任务管理界面（按会话）

    /** 当前会话创建的后台任务（UI 列表用）：按创建时间倒序 */
    fun listFor(conversationId: String, limit: Int = 100): List<TaskRecord> = synchronized(lock) {
        tasks.values
            .filter { it.conversationId == conversationId }
            .sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, 200))
    }

    /** 当前会话的后台任务数量（会话页「⋮」菜单角标用） */
    fun countFor(conversationId: String): Int = synchronized(lock) {
        tasks.values.count { it.conversationId == conversationId }
    }

    /** 删除会话时：取消并移除该会话的全部后台任务（含排队/运行中） */
    fun removeForConversation(conversationId: String): Int {
        val ids = synchronized(lock) {
            tasks.values.filter { it.conversationId == conversationId }.map { it.id }
        }
        ids.forEach { remove(it) }
        return ids.size
    }

    /** 删除所有数据时：取消并移除全部后台任务 */
    fun removeAll(): Int {
        val ids = synchronized(lock) { tasks.keys.toList() }
        ids.forEach { remove(it) }
        return ids.size
    }

    /** 按 id 取快照（定时任务页展示「上次结果」用） */
    fun byId(id: String): TaskRecord? = synchronized(lock) { tasks[id] }

    /** 删除一条任务记录；运行中/排队中的先取消再删（UI「删除记录」用） */
    fun remove(id: String): Boolean {
        var job: Job? = null
        val removed = synchronized(lock) {
            val rec = tasks[id] ?: return false
            if (rec.status == STATUS_QUEUED || rec.status == STATUS_RUNNING) {
                queue.remove(id)
                job = jobs[id]
            }
            jobs.remove(id)
            tasks.remove(id)
            persistLocked()
            true
        }
        job?.cancel()
        dispatch()
        updateService()
        return removed
    }

    /** 用原 action / params 重新提交一次（新任务，仍归属原会话） */
    fun rerun(context: Context, id: String): TaskRecord? {
        init(context)
        val rec = synchronized(lock) { tasks[id] } ?: return null
        val params = runCatching { JSONObject(rec.paramsJson) }.getOrDefault(JSONObject())
        return submit(context, rec.conversationId, rec.action, params)
    }

    fun cancel(id: String): TaskRecord? {
        var job: Job? = null
        val rec = synchronized(lock) {
            val rec = tasks[id] ?: return null
            if (rec.status != STATUS_QUEUED && rec.status != STATUS_RUNNING) return rec
            queue.remove(id)
            val updated = rec.copy(
                status = STATUS_CANCELLED,
                finishedAt = System.currentTimeMillis(),
                error = "TASK_CANCELLED"
            )
            tasks[id] = updated
            job = jobs[id]
            persistLocked()
            updated
        }
        job?.cancel()
        // 取消后如果执行线程还占着槽位，jobs 会在 finish() 时清掉；这里再补一次调度
        dispatch()
        return rec
    }

    // ---------------------------------------------------------------- 工具入口

    /** bg.run / bg.list / bg.cancel 总入口；不是 bg.* 返回 null */
    fun execute(context: Context, toolId: String, argsJson: String, conversationId: String?): PyResult? {
        if (!toolId.startsWith("bg.")) return null
        val convId = conversationId ?: "preview"
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return PyResult(
                ok = false, errorCode = "INVALID_ARGS",
                errorMessage = Lang.current.local.fsInvalidArgs
            )
        return when (toolId) {
            "bg.run" -> {
                val action = args.optString("action").trim()
                if (!isAllowedAction(action)) {
                    return PyResult(
                        ok = false, errorCode = "INVALID_ARGS",
                        errorMessage = Lang.current.local.bgActionNotAllowed
                    )
                }
                val params = args.optJSONObject("params") ?: JSONObject()
                val rec = submit(context, convId, action, params)
                    ?: return PyResult(
                        ok = false, errorCode = "INVALID_ARGS",
                        errorMessage = Lang.current.local.bgActionNotAllowed
                    )
                val note = if (rec.status == STATUS_QUEUED) {
                    Lang.current.format(Lang.current.local.bgQueued, MAX_CONCURRENT)
                } else {
                    Lang.current.local.bgStarted
                }
                PyResult(
                    ok = true,
                    output = JSONObject()
                        .put("task_id", rec.id)
                        .put("status", rec.status)
                        .put("note", note)
                        .toString()
                )
            }

            "bg.list" -> {
                val statusFilter = args.optString("status", "").trim().lowercase()
                val explicitLimit = args.optInt("limit", 0)
                val all = listFor(convId, 200)
                // 默认只回「运行中/排队 + 最近 5 条已结束」，避免历史测试任务灌满上下文
                val filtered = when (statusFilter) {
                    "running" -> all.filter { it.status == STATUS_RUNNING }
                    "queued" -> all.filter { it.status == STATUS_QUEUED }
                    "done" -> all.filter { it.status == STATUS_DONE }
                    "failed" -> all.filter { it.status == STATUS_FAILED }
                    "all" -> all
                    else -> {
                        val active = all.filter {
                            it.status == STATUS_QUEUED || it.status == STATUS_RUNNING
                        }
                        val recentDone = all
                            .filter {
                                it.status != STATUS_QUEUED && it.status != STATUS_RUNNING
                            }
                            .take(5)
                        (active + recentDone).sortedByDescending { it.createdAt }
                    }
                }.let { list ->
                    if (explicitLimit > 0) list.take(explicitLimit.coerceIn(1, 200)) else list
                }
                val arr = JSONArray()
                // 只回当前会话创建的任务，避免模型跨会话看到/操作别人的任务
                filtered.forEach { t ->
                    arr.put(
                        JSONObject()
                            .put("task_id", t.id)
                            .put("action", t.action)
                            .put("status", t.status)
                            .put("created_at", t.createdAt)
                            .put("started_at", t.startedAt)
                            .put("finished_at", t.finishedAt)
                            .put("params", t.paramsJson.take(300))
                            .put("result", t.result.take(RESULT_LIST_LIMIT))
                            .put("error", t.error.take(500))
                    )
                }
                PyResult(ok = true, output = JSONObject().put("tasks", arr).toString())
            }

            "bg.cancel" -> {
                val id = args.optString("task_id", args.optString("id", "")).trim()
                if (id.isEmpty()) {
                    return PyResult(
                        ok = false, errorCode = "INVALID_ARGS",
                        errorMessage = Lang.current.local.fsInvalidArgs
                    )
                }
                // 归属校验：只能取消当前会话创建的任务（fail-closed）
                val owner = synchronized(lock) { tasks[id]?.conversationId }
                if (owner != null && owner != convId) {
                    return PyResult(
                        ok = false, errorCode = "PERMISSION_DENIED",
                        errorMessage = Lang.current.local.notOwned
                    )
                }
                val rec = cancel(id)
                    ?: return PyResult(
                        ok = false, errorCode = "TASK_NOT_FOUND",
                        errorMessage = Lang.current.format(Lang.current.local.bgTaskNotFound, id)
                    )
                PyResult(
                    ok = true,
                    output = JSONObject().put("task_id", rec.id).put("status", rec.status).toString()
                )
            }

            else -> null
        }
    }

    // ---------------------------------------------------------------- 动作执行

    private fun executeAction(rec: TaskRecord): Triple<Boolean, String, String> {
        val ctx = appContext ?: return Triple(false, "", "TOOL_ERROR: context lost")
        val params = runCatching { JSONObject(rec.paramsJson) }.getOrDefault(JSONObject())
        return when (rec.action) {
            ACTION_HTTP -> httpRequest(params)
            ACTION_NOTIFY -> {
                val title = params.optString("title", "DChat")
                val text = params.optString("text", params.optString("message", ""))
                BgNotifications.notifyResult(ctx, title, text)
                Triple(true, Lang.current.local.bgNotifySent, "")
            }

            // 后台跑 Python 代码：能力保留，但必须过权限闸门。
            // 1）background 任务本身已要求会话「完全权限」（工具 minMode）；
            // 2）这里再校验 `local.python` 开关：关掉 python 就不能借道后台执行代码。
            ACTION_PYTHON -> {
                val pyEnabled = runCatching { ToolRegistry.byId("local.python")?.enabled == true }
                    .getOrDefault(false)
                if (!pyEnabled) {
                    return Triple(
                        false,
                        "",
                        "PERMISSION_DENIED: " + Lang.current.local.bgPythonDisabled
                    )
                }
                val code = params.optString("code")
                if (code.isBlank()) return Triple(false, "", "INVALID_ARGS: code")
                val timeoutSec = params.optInt("timeout_seconds", 30).coerceIn(5, 300)
                val argsJson = params.optString("args").ifBlank { "{}" }
                val r = kotlinx.coroutines.runBlocking {
                    PyRuntime.runPythonCode(
                        context = ctx,
                        code = code,
                        argsJson = argsJson,
                        conversationId = rec.conversationId,
                        timeoutMs = timeoutSec * 1000,
                        conversationMode = ToolRegistry.CONV_FULL,
                        // 后台名额上限 5：必须用独立线程池，否则 5 个任务会串行跑完、后面的超时
                        background = true
                    )
                }
                Triple(r.ok, r.output, if (r.ok) "" else (r.errorCode ?: "TOOL_ERROR") + ": " + (r.errorMessage ?: ""))
            }

            ACTION_RUN_USER_TOOL -> {
                val toolId = params.optString("tool_id", params.optString("id", "")).trim()
                if (toolId.isEmpty()) return Triple(false, "", "INVALID_ARGS: tool_id")
                val def = UserToolStore.find(ctx, toolId)
                    ?: return Triple(false, "", "TOOL_NOT_FOUND: $toolId")
                val argsJson = params.optJSONObject("args")?.toString() ?: params.optString("args").ifBlank { "{}" }
                val r = kotlinx.coroutines.runBlocking {
                    PyRuntime.run(
                        context = ctx,
                        tool = def,
                        argsJson = argsJson,
                        conversationId = rec.conversationId,
                        conversationMode = ToolRegistry.CONV_FULL,
                        background = true
                    )
                }
                Triple(r.ok, r.output, if (r.ok) "" else (r.errorCode ?: "TOOL_ERROR") + ": " + (r.errorMessage ?: ""))
            }

            else -> Triple(false, "", "INVALID_ARGS: " + rec.action)
        }
    }

    private fun httpRequest(params: JSONObject): Triple<Boolean, String, String> {
        val urlText = params.optString("url").trim()
        if (!urlText.startsWith("http://") && !urlText.startsWith("https://")) {
            return Triple(false, "", "INVALID_ARGS: " + Lang.current.local.bgHttpBadUrl)
        }
        val method = params.optString("method", "GET").trim().uppercase()
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            return Triple(false, "", "INVALID_ARGS: method $method")
        }
        val timeoutMs = (params.optInt("timeout_seconds", 30).coerceIn(1, 60)) * 1000
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DChat/1.0 (Android)")
            }
            params.optJSONObject("headers")?.let { headers ->
                headers.keys().forEach { key -> conn.setRequestProperty(key, headers.optString(key)) }
            }
            val body = params.optString("body")
            if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                if (!params.has("content_type")) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val raw = stream?.use { readCap(it, 1024 * 1024) } ?: ByteArray(0)
            val text = String(raw, Charsets.UTF_8)
            val bodyOut = truncate(text, 64 * 1024)
            val out = JSONObject()
                .put("status", status)
                .put("content_type", conn.contentType ?: "")
                .put("body", bodyOut)
            Triple(status in 200..299, out.toString(), if (status in 200..299) "" else "HTTP_$status")
        } catch (t: Throwable) {
            Triple(false, "", "TOOL_ERROR: " + (t.message ?: t.javaClass.simpleName))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readCap(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "…"
}

/**
 * 前台服务：有后台任务运行时显示常驻通知，任务结束（running == 0）由 [BgTasks] 停掉。
 * 后台启动可能被 Android 12+ 拒绝（ForegroundServiceStartNotAllowedException），
 * 此时静默降级为普通进程内执行，任务仍由 [BgTasks] 的协程完成。
 */
class BgTaskService : Service() {

    @Volatile
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // P0：startForegroundService 后 5 秒内必须 startForeground。
        // 第一件事就发不依赖 Lang/Compose/磁盘的最小通知；失败则降级，不冒泡。
        if (!promoteToForeground()) return
        instance = this
        runCatching {
            BgNotifications.ensureChannels(this)
            updateNotification()
        }.onFailure { Log.w(TAG, "前台服务通知初始化失败，保留最小通知", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统可能重复 start；每次幂等确认前台状态。
        // 任务已经跑完（running == 0）时不要留下空服务，否则常驻通知/看门狗都是负担。
        if (BgTasks.serviceCounts().first <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (promoteToForeground()) {
            runCatching { updateNotification() }
                .onFailure { Log.w(TAG, "前台服务通知更新失败", it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foregroundStarted = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 只做升前台：最小渠道 → 最小通知 → startForeground；不碰 Lang/磁盘/Python。 */
    private fun promoteToForeground(): Boolean {
        if (foregroundStarted) return true
        return try {
            BgNotifications.ensureMinimalChannel(this)
            startForegroundCompat(BgNotifications.buildMinimalServiceNotification(this))
            foregroundStarted = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground 失败，降级为进程内执行", t)
            fgsUnavailable = true
            runCatching { stopSelf() }
            false
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        // Android 14（targetSdk 35）：manifest 声明了 foregroundServiceType 后，
        // 必须把 type 显式传给 startForeground；两参数等于 type=0，5 秒看门狗会杀进程。
        // ServiceCompat 会按 API 版本自动分派（<29 退化为两参数）。
        ServiceCompat.startForeground(
            this,
            BgNotifications.SERVICE_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val (running, queued) = BgTasks.serviceCounts()
        startForegroundCompat(BgNotifications.buildServiceNotification(this, running, queued))
    }

    companion object {
        private const val TAG = "BgTaskService"

        @Volatile
        private var instance: BgTaskService? = null

        /** FGS 已证明不可用（后台限制/权限/机型）后，不再反复尝试 */
        @Volatile
        private var fgsUnavailable = false

        fun start(context: Context) {
            if (fgsUnavailable || BgTasks.isAppVisible()) return
            val inst = instance
            if (inst != null && inst.foregroundStarted) return
            val app = context.applicationContext ?: context
            // 关键：必须等主线程空下来再发 startForegroundService。
            // 主线程忙（Compose 重建/JSON 解析/Python）时 onCreate 会排队，超过系统 5 秒
            // 看门狗就会被 RemoteServiceException 杀掉。这里统一 post 到主线程队列尾部，
            // 让「发 FGS」和「onCreate 升前台」紧挨着执行。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                startOnMain(app)
            } else {
                Handler(Looper.getMainLooper()).post { startOnMain(app) }
            }
        }

        private fun startOnMain(app: Context) {
            if (fgsUnavailable || BgTasks.isAppVisible()) return
            if (BgTasks.serviceCounts().first <= 0) return // 已跑完，别起空服务
            val inst = instance
            if (inst != null && inst.foregroundStarted) return
            runCatching {
                ContextCompat.startForegroundService(app, Intent(app, BgTaskService::class.java))
            }.onFailure {
                fgsUnavailable = true
                Log.w(TAG, "前台服务启动失败，降级为进程内执行", it)
            }
        }

        fun stop(context: Context) {
            // 即使 instance 没建立也要停：取消排队中的 FGS 启动，避免迟到服务 5 秒超时。
            runCatching {
                (context.applicationContext ?: context)
                    .stopService(Intent(context.applicationContext ?: context, BgTaskService::class.java))
            }.onFailure { Log.w(TAG, "前台服务停止失败", it) }
        }

        fun update(context: Context, running: Int, queued: Int) {
            val inst = instance ?: return
            if (!inst.foregroundStarted) return
            runCatching {
                inst.startForegroundCompat(
                    BgNotifications.buildServiceNotification(inst, running, queued)
                )
            }.onFailure { Log.w(TAG, "前台服务通知更新失败", it) }
        }
    }
}

/** 通知 / 运行时权限（POST_NOTIFICATIONS）集中处理 */
object BgNotifications {

    private const val TAG = "BgNotifications"
    const val CHANNEL_RUNNING = "bg_running"
    const val CHANNEL_RESULT = "bg_result"
    const val SERVICE_NOTIFICATION_ID = 0x4C01
    private const val PERM_REQUEST_CODE = 0x4C02

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, Lang.current.local.bgChannelRunning, NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, Lang.current.local.bgChannelResult, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * P0 升前台专用：只建「运行中」渠道，文案写死不碰 Lang。
     * 必须足够快，保证 startForegroundService 后 5 秒内能 startForeground。
     */
    fun ensureMinimalChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "后台任务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    /** 升前台用的最小通知：不读语言/不读磁盘，失败也不让主线程崩 */
    fun buildMinimalServiceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DChat")
            .setContentText("后台任务运行中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Android 13+ 通知权限：拿得到 Activity 就当场申请一次（失败也不影响任务执行） */
    fun ensurePostPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        val activity = context as? Activity ?: return
        // 运行时权限申请必须在主线程
        runCatching {
            activity.runOnUiThread {
                runCatching {
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        PERM_REQUEST_CODE
                    )
                }
            }
        }.onFailure { Log.w(TAG, "申请通知权限失败", it) }
    }

    fun buildServiceNotification(context: Context, running: Int, queued: Int): Notification {
        ensureChannels(context)
        val text = if (queued > 0) {
            Lang.current.format(Lang.current.local.bgServiceRunningQueued, running, queued)
        } else {
            Lang.current.format(Lang.current.local.bgServiceRunning, running)
        }
        return NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(Lang.current.local.bgServiceTitle)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notifyCompletion(appContext: Context, taskId: String, action: String, ok: Boolean, text: String) {
        ensureChannels(appContext)
        val title = if (ok) {
            Lang.current.format(Lang.current.local.bgTaskDone, action)
        } else {
            Lang.current.format(Lang.current.local.bgTaskFailed, action)
        }
        post(appContext, title, text.ifBlank { taskId })
    }

    fun notifyResult(context: Context, title: String, text: String) {
        ensureChannels(context)
        post(context, title.ifBlank { "DChat" }, text)
    }

    private fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val n = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(2000)))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify((System.currentTimeMillis() and 0xFFFF).toInt(), n) }
            .onFailure { Log.w(TAG, "发通知失败", it) }
    }
}
