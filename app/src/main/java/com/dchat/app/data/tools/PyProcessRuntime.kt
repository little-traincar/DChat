package com.dchat.app.data.tools

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * 独立 Python 子进程运行时（方案 A）。
 *
 * - 默认 IN_PROCESS：完全不改变现有行为；
 * - CHILD：nativeLibraryDir/libpychild.so + Chaquopy libpython3.11.so，
 *   复用 dchat_runner.py 的沙箱语义，通过 stdin/stdout JSON-lines 协议；
 * - 首次使用把 stdlib imy 解到 filesDir/pyhome（tmp → .ready → rename，原子）；
 * - 每个请求独占一个子进程；最多 5 个并发进程；
 * - 超时 destroyForcibly + waitFor 确认；启动失败/不可用时降级回 PyRuntime 进程内实现；
 * - 启动失败次数有窗口上限，避免「启动即崩」无限拉起。
 */
object PyProcessRuntime {

    enum class Mode { IN_PROCESS, CHILD }

    /** 生产默认必须是 IN_PROCESS；verify 通过、负责人确认后才改成 true/CHILD。 */
    const val CHILD_MODE_DEFAULT = false

    @Volatile
    var mode: Mode = if (CHILD_MODE_DEFAULT) Mode.CHILD else Mode.IN_PROCESS

    private const val TAG = "PyProcessRuntime"
    private const val POOL_SIZE = 3
    private const val POOL_IDLE_MS = 60_000L
    private const val START_WINDOW_MS = 60_000L
    private const val MAX_STARTS_PER_WINDOW = 4
    private const val FAILURE_COOLDOWN_MS = 60_000L
    private const val CHILD_BOOT_TIMEOUT_MS = 20_000L

    /** 单次执行内存软限：256MB（子进程 RSS 增量） */
    private const val CHILD_SOFT_MEMORY_KB = 256L * 1024
    /** 解释器总硬限：512MB（子进程 RSS 绝对值）；超限直接 kill 子进程，下次请求自动重启解释器 */
    private const val CHILD_HARD_MEMORY_KB = 512L * 1024
    private const val MEMORY_POLL_MS = 250L

    private val requestIds = AtomicLong(1)
    private val semaphore = Semaphore(POOL_SIZE)
    private val lock = Any()
    private val idle = ArrayDeque<Worker>()
    private val activePool = HashSet<Worker>()

    private var startsInWindow = 0
    private var windowStart = 0L
    private var failureUntil = 0L
    private var boot: Boot? = null
    private var bootRetryAt = 0L

    init {
        // local.python idle worker 60s 回收，避免池常驻占全局进程预算
        Thread({
            while (true) {
                runCatching {
                    Thread.sleep(15_000L)
                    val now = SystemClock.elapsedRealtime()
                    synchronized(lock) {
                        idle.removeAll { w ->
                            val stale = now - w.lastUsedAt >= POOL_IDLE_MS
                            if (stale) runCatching { w.close() }
                            stale
                        }
                    }
                }
            }
        }, "pychild-pool-reaper").apply { isDaemon = true }.start()
    }

    fun shouldUseChild(): Boolean = mode == Mode.CHILD && System.currentTimeMillis() >= failureUntil

    /** 清掉失败冷却/缓存，下一次调用重新尝试启动（解包失败时可从设置/诊断入口调用） */
    fun retryBootstrap() {
        synchronized(lock) {
            boot = null
            bootRetryAt = 0
            failureUntil = 0
            startsInWindow = 0
            windowStart = 0
        }
    }

    /** 产品入口：清空已解包 Python 环境，下次调用重新解包（也会杀掉空闲子进程） */
    fun resetPyhome(context: Context) {
        ToolProcessRegistry.shutdownAll()
        synchronized(lock) {
            while (idle.isNotEmpty()) runCatching { idle.removeFirst().close() }
            boot = null
            bootRetryAt = 0
            failureUntil = 0
            startsInWindow = 0
            windowStart = 0
            runCatching { File(context.filesDir, "pyhome").deleteRecursively() }
            runCatching { File(context.filesDir, "pyhome.tmp").deleteRecursively() }
        }
    }

    data class Boot(
        val pyhome: File,
        val launcher: File,
        val nativeDir: String
    )

    private class BootException(message: String) : Exception(message)

    private class StartException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private class ChildTimeout(message: String) : Exception(message)

    /** 子进程内存超限：已 close（kill），下一次请求会重新启动解释器 */
    private class ChildMemoryLimit(message: String) : Exception(message)

    suspend fun runPythonCode(
        context: Context,
        toolId: String = "local.python",
        code: String,
        argsJson: String = "{}",
        conversationId: String,
        timeoutMs: Int = 5_000,
        conversationMode: String = "workspace",
        background: Boolean = false
    ): PyResult = withContext(Dispatchers.IO) {
        if (!shouldUseChild()) {
            return@withContext PyRuntime.runPythonCode(
                context, toolId, code, argsJson, conversationId,
                timeoutMs, conversationMode, background, viaChild = false
            )
        }
        try {
            runChild(
                context = context,
                toolId = toolId,
                entry = null,
                entryFunction = "run",
                code = code,
                argsJson = argsJson,
                conversationId = conversationId,
                timeoutMs = timeoutMs,
                conversationMode = conversationMode
            )
        } catch (e: BootException) {
            Log.w(TAG, "child bootstrap failed, fallback in-process", e)
            PyRuntime.runPythonCode(
                context, toolId, code, argsJson, conversationId,
                timeoutMs, conversationMode, background, viaChild = false
            )
        } catch (e: StartException) {
            Log.w(TAG, "child start failed, fallback in-process", e)
            PyRuntime.runPythonCode(
                context, toolId, code, argsJson, conversationId,
                timeoutMs, conversationMode, background, viaChild = false
            )
        }
    }

    suspend fun runCode(
        context: Context,
        toolId: String,
        entryFunction: String,
        code: String,
        argsJson: String,
        conversationId: String,
        timeoutMs: Int = 5_000,
        conversationMode: String = "workspace",
        background: Boolean = false
    ): PyResult = withContext(Dispatchers.IO) {
        if (!shouldUseChild()) {
            return@withContext PyRuntime.runCode(
                context, toolId, entryFunction, code, argsJson, conversationId,
                timeoutMs, conversationMode, background, viaChild = false
            )
        }
        // 用户工具优先走 entry.py；编辑器/预览等没有落盘 entry.py 或代码已改时，
        // 把当前 code 写到 sandbox 的临时 entry，保证仍然按「def run(args)」语义执行。
        val entryFile = UserToolStore.entryFile(context, toolId)
        val def = if (toolId.startsWith("user.")) UserToolStore.find(context, toolId) else null
        val entry = if (toolId.startsWith("user.")) {
            if (def != null && entryFile.isFile && def.code == code) {
                entryFile.absolutePath
            } else {
                UserToolStore.ensureDirs(context, toolId)
                File(UserToolStore.sandboxDir(context, toolId), "entry_preview.py").apply {
                    writeText(code)
                }.absolutePath
            }
        } else {
            null
        }
        try {
            runChild(
                context = context,
                toolId = toolId,
                entry = entry,
                entryFunction = entryFunction,
                code = code,
                argsJson = argsJson,
                conversationId = conversationId,
                timeoutMs = timeoutMs,
                conversationMode = conversationMode
            )
        } catch (e: BootException) {
            Log.w(TAG, "child bootstrap failed, fallback in-process", e)
            PyRuntime.runCode(
                context, toolId, entryFunction, code, argsJson, conversationId,
                timeoutMs, conversationMode, background, viaChild = false
            )
        } catch (e: StartException) {
            Log.w(TAG, "child start failed, fallback in-process", e)
            PyRuntime.runCode(
                context, toolId, entryFunction, code, argsJson, conversationId,
                timeoutMs, conversationMode, background, viaChild = false
            )
        }
    }

    suspend fun run(
        context: Context,
        tool: UserToolDef,
        argsJson: String,
        conversationId: String,
        conversationMode: String = "workspace",
        background: Boolean = false
    ): PyResult = withContext(Dispatchers.IO) {
        if (!shouldUseChild()) {
            return@withContext PyRuntime.run(
                context, tool, argsJson, conversationId, conversationMode, background, viaChild = false
            )
        }
        try {
            runChild(
                context = context,
                toolId = tool.id,
                entry = UserToolStore.entryFile(context, tool.id).absolutePath,
                entryFunction = tool.entryFunction,
                code = tool.code,
                argsJson = argsJson,
                conversationId = conversationId,
                timeoutMs = ToolRegistry.byId(tool.id)?.timeoutMs ?: 5_000,
                conversationMode = conversationMode
            )
        } catch (e: BootException) {
            Log.w(TAG, "child bootstrap failed, fallback in-process", e)
            PyRuntime.run(context, tool, argsJson, conversationId, conversationMode, background, viaChild = false)
        } catch (e: StartException) {
            Log.w(TAG, "child start failed, fallback in-process", e)
            PyRuntime.run(context, tool, argsJson, conversationId, conversationMode, background, viaChild = false)
        }
    }

    private suspend fun runChild(
        context: Context,
        toolId: String,
        entry: String?,
        entryFunction: String,
        code: String,
        argsJson: String,
        conversationId: String,
        timeoutMs: Int,
        conversationMode: String
    ): PyResult {
        if (toolId != "local.python") {
            val r = ToolProcessRegistry.execute(
                context,
                ToolProcSpec(
                    toolId = toolId,
                    entry = entry,
                    entryFunction = entryFunction,
                    code = code,
                    argsJson = argsJson,
                    conversationId = conversationId,
                    conversationMode = conversationMode,
                    timeoutMs = timeoutMs
                )
            )
            if (r != null) return r
        }
        val b = ensureBoot(context) ?: throw BootException("child runtime unavailable")
        UserToolStore.ensureDirs(context, toolId)
        val sandbox = UserToolStore.sandboxDir(context, toolId).absolutePath
        val site = UserToolStore.sitePackagesDir(context, toolId).absolutePath
        val state = UserToolStore.stateFile(context, toolId, conversationId).also { it.parentFile?.mkdirs() }.absolutePath
        val id = requestIds.getAndIncrement().toString()
        val req = JSONObject()
            .put("id", id)
            .put("toolId", toolId)
            .put("entryFunction", entryFunction)
            .put("argsJson", argsJson)
            .put("sandbox", sandbox)
            .put("sitePackages", site)
            .put("statePath", state)
            .put("perm", conversationMode)
        if (entry != null) req.put("entry", entry) else req.put("code", code)

        val timeout = timeoutMs.toLong().coerceAtLeast(1_000L)
        acquire()
        var worker: Worker? = null
        try {
            worker = try {
                obtainWorker(b)
            } catch (e: StartException) {
                throw e
            } catch (e: Throwable) {
                throw StartException("child worker start failed", e)
            }
            val resp = worker.request(req, timeout)
            synchronized(lock) {
                activePool.remove(worker)
                worker.lastUsedAt = SystemClock.elapsedRealtime()
                idle.addLast(worker)
            }
            worker = null
            return PyRuntime.finalizeOutput(
                context, conversationId,
                PyResult(
                    ok = resp.optBoolean("ok", false),
                    output = resp.optString("result"),
                    errorCode = resp.optString("code").takeIf { it.isNotBlank() },
                    errorMessage = resp.optString("error").takeIf { it.isNotBlank() }
                )
            )
        } catch (e: ChildMemoryLimit) {
            Log.w(TAG, "child memory limit exceeded, killed", e)
            return PyResult(false, errorCode = "MEMORY_LIMIT", errorMessage = e.message)
        } catch (e: ChildTimeout) {
            Log.w(TAG, "child timeout, kill -9", e)
            return PyResult(false, errorCode = "TOOL_TIMEOUT", errorMessage = e.message)
        } catch (e: StartException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "child request failed", e)
            return PyResult(false, errorCode = "TOOL_ERROR", errorMessage = e.message ?: e.javaClass.simpleName)
        } finally {
            synchronized(lock) { worker?.let { activePool.remove(it) } }
            worker?.close()
            release()
        }
    }

    private fun acquire() {
        semaphore.acquire()
    }

    private fun release() {
        semaphore.release()
    }

    private fun obtainWorker(b: Boot): Worker {
        synchronized(lock) {
            while (idle.isNotEmpty()) {
                val w = idle.removeFirst()
                if (w.isAlive) {
                    activePool.add(w)
                    return w
                }
                activePool.remove(w)
                w.close()
            }
            if (!canStart()) {
                throw StartException("child restart limit reached; cooling down")
            }
            if (!ProcBudget.tryAcquire(5_000L)) {
                throw StartException("global process budget unavailable")
            }
            startsInWindow++
            return try {
                Worker(b) { ProcBudget.release() }.also {
                    activePool.add(it)
                    it.lastUsedAt = SystemClock.elapsedRealtime()
                }
            } catch (t: Throwable) {
                ProcBudget.release()
                throw t
            }
        }
    }

    /** 全局预算不够时，按 LRU 关掉 local.python 的一个 idle worker 腾名额 */
    internal fun evictIdleForBudget(): Boolean = synchronized(lock) {
        while (idle.isNotEmpty()) {
            val w = idle.removeFirst()
            activePool.remove(w)
            runCatching { w.close() }
            return true
        }
        false
    }

    /** 诊断页「全部重启」：关闭 local.python 池当前 idle */
    internal fun closePoolIdle() {
        synchronized(lock) {
            while (idle.isNotEmpty()) runCatching { idle.removeFirst().close() }
        }
    }

    /** 诊断页用：local.python 池（idle + 正在跑） */
    internal fun poolSnapshot(): List<Map<String, Any?>> = synchronized(lock) {
        (idle + activePool).distinct().map { w ->
            mapOf(
                "kind" to "pool",
                "toolId" to "local.python",
                "worker" to System.identityHashCode(w),
                "alive" to w.isAlive,
                "idleMs" to (SystemClock.elapsedRealtime() - w.lastUsedAt),
                "pid" to w.pid,
                "rssKb" to w.rssKb
            )
        }
    }

    private fun canStart(): Boolean {
        val now = System.currentTimeMillis()
        if (now < failureUntil) return false
        if (now - windowStart > START_WINDOW_MS) {
            windowStart = now
            startsInWindow = 0
        }
        if (startsInWindow >= MAX_STARTS_PER_WINDOW) {
            failureUntil = now + FAILURE_COOLDOWN_MS
            return false
        }
        return true
    }

    internal fun ensureBoot(context: Context): Boot? {
        synchronized(lock) {
            boot?.let { if (it.launcher.isFile && File(it.pyhome, ".ready").isFile) return it }
            val now = System.currentTimeMillis()
            if (now < bootRetryAt) return null
            val app = context.applicationContext ?: context
            val nativeDir = app.applicationInfo.nativeLibraryDir
            val launcher = File(nativeDir, "libpychild.so")
            if (!launcher.isFile) {
                bootRetryAt = now + FAILURE_COOLDOWN_MS
                Log.w(TAG, "libpychild.so missing in $nativeDir")
                return null
            }
            val pyhome = File(app.filesDir, "pyhome")
            val version = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            }.getOrDefault(0L).toString()
            val ready = File(pyhome, ".ready")
            if (ready.isFile && ready.readText().trim() == version &&
                File(pyhome, "lib/python3.11/encodings/__init__.pyc").isFile &&
                File(pyhome, "app/dchat_child.py").isFile
            ) {
                return Boot(pyhome, launcher, nativeDir).also { boot = it }
            }
            return try {
                extractPyhome(app, pyhome, version)
                Log.i(TAG, "pyhome extracted/ready at ${pyhome.absolutePath}")
                Boot(pyhome, launcher, nativeDir).also { boot = it }
            } catch (t: Throwable) {
                bootRetryAt = now + FAILURE_COOLDOWN_MS
                Log.e(TAG, "extract pyhome failed", t)
                null
            }
        }
    }

    private fun extractPyhome(context: Context, target: File, version: String) {
        val tmp = File(context.filesDir, "pyhome.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val libDir = File(tmp, "lib/python3.11")
        libDir.mkdirs()
        extractZipAsset(context, "chaquopy/stdlib-common.imy", libDir)
        extractZipAsset(context, "chaquopy/stdlib-arm64-v8a.imy", libDir)
        // bootstrap-native：math/_datetime/_json 等 C 扩展不在 stdlib imy 里
        val bootstrap = "chaquopy/bootstrap-native/arm64-v8a"
        context.assets.list(bootstrap)?.forEach { name ->
            if (name.endsWith(".so")) copyAsset(context, "$bootstrap/$name", File(libDir, name))
        }
        val appDir = File(tmp, "app")
        appDir.mkdirs()
        extractZipAsset(context, "chaquopy/app.imy", appDir)
        copyAsset(context, "child/dchat_child.py", File(appDir, "dchat_child.py"))
        // 全部成功后才写 ready，再原子切换；半包不启动
        File(tmp, ".ready").writeText(version)
        val old = File(target.path + ".old")
        old.deleteRecursively()
        if (target.exists()) target.renameTo(old)
        if (!tmp.renameTo(target)) {
            old.renameTo(target)
            throw IOException("pyhome rename failed")
        }
        old.deleteRecursively()
    }

    private fun extractZipAsset(context: Context, asset: String, dest: File) {
        context.assets.open(asset).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = e.name
                    if (name.endsWith("/")) continue
                    val out = File(dest, name)
                    val root = dest.canonicalPath
                    val path = out.canonicalPath
                    if (!path.startsWith(root + File.separator)) continue
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
            }
        }
    }

    private fun copyAsset(context: Context, asset: String, out: File) {
        out.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            FileOutputStream(out).use { fos -> input.copyTo(fos) }
        }
    }

    internal class Worker(val boot: Boot, private val onClose: () -> Unit = {}) {
        private val process: Process
        private val stdin: java.io.BufferedWriter
        private val queue = LinkedBlockingQueue<String>()
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile
        private var alive = true
        @Volatile
        var lastUsedAt: Long = SystemClock.elapsedRealtime()
        val pid: Long = runCatching {
            process.javaClass.getMethod("pid").invoke(process) as Long
        }.getOrDefault(0L)
        val rssKb: Long
            get() = runCatching {
                if (pid <= 0) return 0L
                val statm = File("/proc/$pid/statm").readText().trim().split(" ")
                val pages = statm.getOrNull(1)?.toLongOrNull() ?: 0L
                pages * 4L
            }.getOrDefault(0L)

        init {
            val pb = ProcessBuilder(
                boot.launcher.absolutePath,
                boot.pyhome.absolutePath,
                File(boot.pyhome, "app/dchat_child.py").absolutePath
            )
            pb.directory(boot.pyhome)
            pb.environment()["LD_LIBRARY_PATH"] = boot.nativeDir
            pb.environment()["PYTHONHOME"] = boot.pyhome.absolutePath
            pb.environment()["PYTHONPATH"] =
                File(boot.pyhome, "lib/python3.11").absolutePath + ":" + File(boot.pyhome, "app").absolutePath
            pb.environment()["DCHAT_CHILD"] = "1"
            process = pb.start()
            stdin = process.outputStream.bufferedWriter(Charsets.UTF_8)
            Thread({
                try {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { queue.offer(it) }
                    }
                } catch (_: Throwable) {
                } finally {
                    alive = false
                    queue.offer(EOF)
                }
            }, "pychild-reader").apply { isDaemon = true }.start()
            Thread({
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        var logged = 0
                        lines.forEach { line ->
                            if (logged < 40) {
                                Log.w("PyProcessRuntime", "child stderr: ${line.take(300)}")
                                logged++
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }, "pychild-stderr").apply { isDaemon = true }.start()
            if (!process.isAlive) {
                alive = false
                throw StartException("child process died immediately")
            }
            ChildPidStore.add(boot.pyhome.parentFile, pid)
            Log.i(TAG, "child started")
        }

        val isAlive: Boolean get() = alive && process.isAlive

        fun request(req: JSONObject, timeoutMs: Long): JSONObject {
            if (!isAlive) throw IOException("child not alive")
            stdin.write(req.toString())
            stdin.newLine()
            stdin.flush()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val baselineKb = rssKb
            while (System.nanoTime() < deadline) {
                val remainMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                val line = queue.poll(minOf(remainMs, MEMORY_POLL_MS), TimeUnit.MILLISECONDS)
                // 内存看门狗：轮询期间采样子进程 RSS
                val rss = rssKb
                if (rss > 0) {
                    if (rss > CHILD_HARD_MEMORY_KB) {
                        close()
                        throw ChildMemoryLimit("Python 解释器内存超过 512MB，已终止子进程并将在下次请求重启")
                    }
                    if (baselineKb > 0 && rss - baselineKb > CHILD_SOFT_MEMORY_KB) {
                        close()
                        throw ChildMemoryLimit("单次执行内存超过 256MB，已终止子进程并将在下次请求重启")
                    }
                }
                if (line == null) continue
                if (line == EOF) break
                val o = runCatching { JSONObject(line) }.getOrDefault(JSONObject())
                if (o.optString("id").isBlank()) {
                    if (o.optString("code") == "CHILD_BOOT_FAILED") {
                        throw StartException(o.optString("error", "child boot failed"))
                    }
                    continue
                }
                if (o.optString("id") == req.optString("id")) return o
            }
            close()
            throw ChildTimeout("child timeout after ${timeoutMs}ms; killed")
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            alive = false
            runCatching { stdin.close() }
            runCatching { process.destroyForcibly() }
            runCatching {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "child still alive after destroyForcibly")
                }
                Log.i(TAG, "child exited code=${process.exitValue()}")
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            ChildPidStore.remove(boot.pyhome.parentFile, pid)
            onClose()
        }

        companion object {
            const val EOF = "\u0000__EOF__"
        }
    }
}
