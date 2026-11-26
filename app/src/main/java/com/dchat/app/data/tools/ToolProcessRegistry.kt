package com.dchat.app.data.tools

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * D80：全局进程预算。local.python 池最多 3，registry 非 local 工具共用剩余名额，
 * 合计最多 5 个活进程（含 idle）。所有 acquire 成功后必须由 Worker.close() 释放。
 */
object ProcBudget {
    private const val MAX = 5
    private val semaphore = Semaphore(MAX)

    fun tryAcquire(timeoutMs: Long): Boolean =
        semaphore.tryAcquire(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)

    fun release() = semaphore.release()

    fun available(): Int = semaphore.availablePermits()
}

/** 记录本 App 启动过的 child pid；App 启动时清理上次残留（子进程本身 EOF 也会自杀）。 */
object ChildPidStore {
    private const val NAME = "child_pids"
    private val lock = Any()

    private fun file(root: java.io.File) = java.io.File(root, NAME)

    fun add(root: java.io.File, pid: Long) {
        if (pid <= 0) return
        synchronized(lock) {
            runCatching { file(root).appendText("$pid\n") }
        }
    }

    fun remove(root: java.io.File, pid: Long) {
        if (pid <= 0) return
        synchronized(lock) {
            runCatching {
                val f = file(root)
                if (!f.isFile) return
                val keep = f.readLines().filter { it.trim() != pid.toString() }
                f.writeText(keep.joinToString("\n") + if (keep.isEmpty()) "" else "\n")
            }
        }
    }

    fun cleanupStale(root: java.io.File) {
        synchronized(lock) {
            runCatching {
                val f = file(root)
                if (!f.isFile) return
                f.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach { pid ->
                    if (pid > 0) android.os.Process.killProcess(pid)
                }
                f.delete()
            }
        }
    }
}

data class ToolProcSpec(
    val toolId: String,
    val entry: String?,
    val entryFunction: String,
    val code: String,
    val argsJson: String,
    val conversationId: String,
    val conversationMode: String,
    val timeoutMs: Int,
    val background: Boolean = false
)

/** 诊断页快照 */
data class ProcInfo(
    val kind: String,
    val toolId: String,
    val worker: Int,
    val alive: Boolean,
    val idleMs: Long,
    val crashes: Int = 0,
    val backoffMs: Long = 0L,
    val state: String = "idle",
    val lastError: String = "",
    val crashCount: Int = 0,
    val backoffUntil: Long = 0L,
    val pid: Long = 0L,
    val rssKb: Long = 0L,
    val overbudget: Boolean = false
)

/**
 * 每工具独立子进程（非 local.python）。复用 PyProcessRuntime 的 ChildWorker/JSON-lines。
 * - 懒启动、同工具单并发、60s 空闲回收、崩溃 60s→5min 退避
 * - 不缓存权限；perm 每次随 request 发送
 * - local.python 不走这里，继续由 PyProcessRuntime 旧池处理
 */
object ToolProcessRegistry {
    private const val TAG = "ToolProcRegistry"
    private const val MAX_IDLE_MS = 60_000L
    private const val START_TIMEOUT_MS = 5_000L
    private const val QUEUE_MAX_MS = 10_000L
    private const val BACKOFF_FIRST_MS = 60_000L
    private const val BACKOFF_MAX_MS = 5 * 60_000L

    private data class Entry(
        var worker: PyProcessRuntime.Worker? = null,
        var lastUsedAt: Long = SystemClock.elapsedRealtime(),
        var running: Boolean = false,
        var backoffUntil: Long = 0L,
        var backoffMs: Long = 0L,
        var crashes: Int = 0,
        var lastError: String = "",
        var generation: Int = 0
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)
    private val locks = HashMap<String, Mutex>()
    private val requestIds = AtomicLong(1)

    init {
        Thread({
            while (true) {
                runCatching {
                    Thread.sleep(30_000L)
                    enforceRssBudget()
                }
            }
        }, "pychild-rss-watchdog").apply { isDaemon = true }.start()
    }

    /** 单进程 >60MB 或全局 >120MB：只淘汰 idle；正在跑的标记 overbudget 并记日志。 */
    private fun enforceRssBudget() {
        val now = SystemClock.elapsedRealtime()
        var total = 0L
        val over = ArrayList<Pair<String, Long>>()
        synchronized(lock) {
            pruneIdleLocked(now)
            entries.forEach { (id, e) ->
                val w = e.worker ?: return@forEach
                val rss = w.rssKb
                total += rss
                if (rss > 60L * 1024L) over.add(id to rss)
            }
        }
        PyProcessRuntime.poolSnapshot().forEach { total += (it["rssKb"] as? Long) ?: 0L }
        if (total > 120L * 1024L || over.isNotEmpty()) {
            Log.w(TAG, "rss over budget total=${total}KB per=${over}")
            synchronized(lock) {
                val it = entries.entries.iterator()
                while (it.hasNext()) {
                    val (_, e) = it.next()
                    if (!e.running && e.worker != null) {
                        runCatching { e.worker?.close() }
                        e.worker = null
                        it.remove()
                    } else if (e.running) {
                        e.lastError = "OVERBUDGET"
                    }
                }
            }
            PyProcessRuntime.evictIdleForBudget()
        }
    }

    /** App 启动时清理上次残留 child（子进程 EOF 也会自杀；双保险） */
    fun cleanupStale(context: Context) {
        ChildPidStore.cleanupStale(context.filesDir)
    }

    private fun mutexFor(toolId: String): Mutex = synchronized(lock) {
        locks.getOrPut(toolId) { Mutex() }
    }

    private fun pruneIdleLocked(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (_, e) = it.next()
            if (!e.running && now - e.lastUsedAt >= MAX_IDLE_MS) {
                runCatching { e.worker?.close() }
                e.worker = null
                it.remove()
            }
        }
    }

    /** LRU 淘汰 registry 的 idle 进程，给新进程腾全局预算 */
    private fun evictIdleForBudget(): Boolean {
        synchronized(lock) {
            val it = entries.entries.iterator()
            while (it.hasNext()) {
                val (_, e) = it.next()
                if (!e.running && e.worker != null) {
                    runCatching { e.worker?.close() }
                    e.worker = null
                    it.remove()
                    return true
                }
            }
        }
        return false
    }

    /**
     * 返回 null 表示非 registry 工具（local.python），调用方继续走旧池。
     */
    suspend fun execute(context: Context, spec: ToolProcSpec): PyResult? {
        if (spec.toolId == "local.python") return null
        val queueTimeout = minOf(spec.timeoutMs.toLong() / 2, QUEUE_MAX_MS).coerceAtLeast(1_000L)
        val mutex = mutexFor(spec.toolId)
        val got = withTimeoutOrNull(queueTimeout) {
            mutex.lock()
            true
        } ?: return PyResult(
            false, errorCode = "PY_TIMEOUT",
            errorMessage = "排队超时（>${queueTimeout}ms），请稍后重试"
        )
        try {
            return executeLocked(context, spec)
        } finally {
            mutex.unlock()
        }
    }

    private fun executeLocked(context: Context, spec: ToolProcSpec): PyResult {
        val now = SystemClock.elapsedRealtime()
        val e = synchronized(lock) {
            pruneIdleLocked(now)
            entries.getOrPut(spec.toolId) { Entry() }
        }
        if (e.backoffUntil > now) {
            return PyResult(
                false, errorCode = "PY_UNAVAILABLE",
                errorMessage = "该工具进程崩溃后冷却中，${(e.backoffUntil - now) / 1000}s 后重试"
            )
        }
        val boot = PyProcessRuntime.ensureBoot(context)
            ?: return PyResult(false, errorCode = "PY_UNAVAILABLE", errorMessage = "Python 子进程环境不可用")
        e.running = true
        var newWorker = false
        try {
            var w = e.worker?.takeIf { it.isAlive }
            if (w == null) {
                if (!ProcBudget.tryAcquire(START_TIMEOUT_MS)) {
                    evictIdleForBudget()
                    if (!PyProcessRuntime.evictIdleForBudget()) {
                        // no idle local worker; wait a bit more for a running one to return
                    }
                    if (!ProcBudget.tryAcquire(1_000L)) {
                        return PyResult(false, errorCode = "PY_TIMEOUT", errorMessage = "全局进程预算已满，排队超时")
                    }
                }
                w = try {
                    PyProcessRuntime.Worker(boot) { ProcBudget.release() }
                } catch (t: Throwable) {
                    ProcBudget.release()
                    throw t
                }
                newWorker = true
                e.worker = w
                e.generation++
            }
            UserToolStore.ensureDirs(context, spec.toolId)
            val req = JSONObject()
                .put("id", requestIds.getAndIncrement().toString())
                .put("toolId", spec.toolId)
                .put("entryFunction", spec.entryFunction)
                .put("argsJson", spec.argsJson)
                .put("sandbox", UserToolStore.sandboxDir(context, spec.toolId).absolutePath)
                .put("sitePackages", UserToolStore.sitePackagesDir(context, spec.toolId).absolutePath)
                .put("statePath", UserToolStore.stateFile(context, spec.toolId, spec.conversationId).also {
                    it.parentFile?.mkdirs()
                }.absolutePath)
                .put("perm", spec.conversationMode)
            if (spec.entry != null) req.put("entry", spec.entry) else req.put("code", spec.code)
            val timeout = spec.timeoutMs.toLong().coerceAtLeast(1_000L)
            val resp = w.request(req, timeout)
            e.lastUsedAt = SystemClock.elapsedRealtime()
            return PyResult(
                ok = resp.optBoolean("ok", false),
                output = resp.optString("result"),
                errorCode = resp.optString("code").takeIf { it.isNotBlank() },
                errorMessage = resp.optString("error").takeIf { it.isNotBlank() }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "tool=${spec.toolId} child failed", t)
            synchronized(lock) {
                runCatching { e.worker?.close() }
                e.worker = null
                e.crashes++
                e.lastError = t.message ?: t.javaClass.simpleName
                e.backoffMs = if (e.backoffMs == 0L) BACKOFF_FIRST_MS else BACKOFF_MAX_MS
                e.backoffUntil = SystemClock.elapsedRealtime() + e.backoffMs
            }
            return PyResult(false, errorCode = "PY_UNAVAILABLE", errorMessage = "子进程异常退出，已退避重试")
        } finally {
            e.running = false
            if (newWorker && e.worker == null) {
                // constructor failed before budget release? release handled in catch
            }
        }
    }

    /** 诊断页：registry + local.python 池，全部标 kind */
    fun snapshot(): List<ProcInfo> {
        enforceRssBudget()
        val out = ArrayList<ProcInfo>()
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            pruneIdleLocked(now)
            entries.forEach { (toolId, e) ->
                val w = e.worker
                out.add(
                    ProcInfo(
                        kind = "tool", toolId = toolId,
                        worker = w?.let { System.identityHashCode(it) } ?: 0,
                        alive = w?.isAlive == true,
                        idleMs = now - e.lastUsedAt,
                        crashes = e.crashes,
                        backoffMs = (e.backoffUntil - now).coerceAtLeast(0L),
                        state = when {
                            e.running -> "running"
                            e.backoffUntil > now -> "backoff"
                            w != null -> "idle"
                            else -> "stopped"
                        },
                        lastError = e.lastError,
                        crashCount = e.crashes,
                        backoffUntil = e.backoffUntil,
                        pid = w?.pid ?: 0L,
                        rssKb = w?.rssKb ?: 0L,
                        overbudget = e.lastError == "OVERBUDGET"
                    )
                )
            }
        }
        PyProcessRuntime.poolSnapshot().forEach { m ->
            out.add(
                ProcInfo(
                    kind = "pool", toolId = "local.python",
                    worker = (m["worker"] as? Int) ?: 0,
                    alive = (m["alive"] as? Boolean) == true,
                    idleMs = (m["idleMs"] as? Long) ?: 0L,
                    state = "pool",
                    pid = (m["pid"] as? Long) ?: 0L,
                    rssKb = (m["rssKb"] as? Long) ?: 0L
                )
            )
        }
        return out
    }

    fun restart(toolId: String) {
        synchronized(lock) {
            entries.remove(toolId)?.let { e ->
                runCatching { e.worker?.close() }
            }
        }
    }

    fun restartAll() {
        shutdownAll()
        PyProcessRuntime.closePoolIdle()
    }

    fun shutdownAll() {
        synchronized(lock) {
            entries.values.forEach { e -> runCatching { e.worker?.close() } }
            entries.clear()
            locks.clear()
        }
    }

    /** 预热接口：首版不启用，留给后续 */
    fun warmUp(toolIds: List<String>) {
        // intentionally no-op; 预启需与全局预算/设备内存联动，proc 首版先不启用
    }
}
