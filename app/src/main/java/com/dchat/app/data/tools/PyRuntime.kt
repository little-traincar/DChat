package com.dchat.app.data.tools

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.dchat.app.ui.lang.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val failures: List<PyFailure> = emptyList(),
    /**
     * local.python / 用户工具：输出超过 64KB 时，完整输出（≤1MB）会落盘到
     * `<会话工作区>/python_out.txt`，这里返回该文件绝对路径（否则 null）。
     * ChatScreen 负责把它拼进给模型的提示里（TOOL_OUTPUT_LIMIT 截断也在 ChatScreen 侧）。
     */
    val outputPath: String? = null,
    /** local.web_search / local.web_fetch：结构化来源，供时间线渲染搜索/打开链接卡片 */
    val sources: List<com.dchat.app.data.Source> = emptyList()
)

/**
 * 第四期 4a：内嵌 CPython（Chaquopy）运行时。
 *
 * - 首次启动在后台线程 `Python.start(AndroidPlatform)`，失败降级为 [State.FAILED]，
 *   工具入口/编辑器显示「Python 运行时不可用」，App 不崩；
 * - 所有用户代码在单线程 Executor 里执行（解释器全局串行），Kotlin 侧 `Future.get(timeout)`；
 *   超时后**只能取消 Future，无法强杀 Python 线程**（报告里注明），返回 TOOL_TIMEOUT；
 * - 本地收集输出上限 **1MB**（[OUTPUT_LIMIT]）；超过 **64KB** 时把完整内容写到会话工作区的
 *   `python_out.txt` 并在 [PyResult.outputPath] 返回路径，回给模型的截断交给 ChatScreen；
 * - 内存：单次执行软限 256MB、解释器总硬限 512MB（IN_PROCESS 共享 App 进程，见 [rssBytes] 注释）；
 * - import 限制已放开（全部标准库）；open 仍由 `src/main/python/dchat_runner.py` 限制在沙箱内。
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

    private const val OUTPUT_LIMIT = 1024 * 1024
    /** 超过该长度就把完整输出落盘 python_out.txt（回给模型时由 ChatScreen 截到 TOOL_OUTPUT_LIMIT） */
    private const val OUTPUT_FILE_HINT_LIMIT = 64 * 1024
    private const val OUTPUT_FILE_NAME = "python_out.txt"
    /** 单次执行内存软限：256MB（IN_PROCESS 测量进程 RSS 增量） */
    private const val SOFT_MEMORY_LIMIT_BYTES = 256L * 1024 * 1024
    /** 解释器总硬限：512MB；IN_PROCESS 只能标记不可用并提示重启（无法强杀 Python 线程） */
    private const val HARD_MEMORY_LIMIT_BYTES = 512L * 1024 * 1024
    private const val MEMORY_POLL_MS = 200L
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
        timeoutMs: Int = 30_000,
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
                    conversationMode,
                    toolId,
                    conversationId
                ).toString()
            })
            Log.i("PyRuntime", "runPythonCode timeout=" + if (timeoutMs <= 0) "unlimited" else "${timeoutMs}ms")
            val outcome = awaitWithMemoryGuard(future, timeoutMs.toLong())
            when (outcome) {
                is AwaitOutcome.Ok -> parseResult(context, conversationId, outcome.raw)
                AwaitOutcome.Timeout -> timeoutResult(timeoutMs, "代码执行")
                AwaitOutcome.MemorySoft -> PyResult(
                    ok = false, errorCode = "MEMORY_LIMIT",
                    errorMessage = "单次执行内存超过 256MB，已丢弃结果；请减少数据量或拆分执行"
                )
                AwaitOutcome.MemoryHard -> PyResult(
                    ok = false, errorCode = "PY_UNAVAILABLE",
                    errorMessage = pyUnavailableMessage("内存超限（512MB），请重启 App 或重置 Python 运行环境")
                )
                is AwaitOutcome.Error -> {
                    val cause = outcome.cause
                    PyResult(ok = false, errorCode = "TOOL_ERROR",
                        errorMessage = cause.message ?: cause.javaClass.simpleName)
                }
            }
        } catch (c: CancellationException) {
            // 用户停止：不要吞掉取消信号（Future 已在 awaitWithMemoryGuard 里 cancel）
            throw c
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
            timeoutMs = ToolRegistry.byId(tool.id)?.timeoutMs ?: 30_000,
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
        timeoutMs: Int = 30_000,
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
                    conversationMode,
                    toolId,
                    conversationId
                ).toString()
                raw
            })
            Log.i("PyRuntime", "runCode timeout=" + if (timeoutMs <= 0) "unlimited" else "${timeoutMs}ms")
            val outcome = awaitWithMemoryGuard(future, timeoutMs.toLong())
            when (outcome) {
                is AwaitOutcome.Ok -> parseResult(context, conversationId, outcome.raw)
                AwaitOutcome.Timeout -> timeoutResult(timeoutMs, "工具执行")
                AwaitOutcome.MemorySoft -> PyResult(
                    ok = false, errorCode = "MEMORY_LIMIT",
                    errorMessage = "单次执行内存超过 256MB，已丢弃结果；请减少数据量或拆分执行"
                )
                AwaitOutcome.MemoryHard -> PyResult(
                    ok = false, errorCode = "PY_UNAVAILABLE",
                    errorMessage = pyUnavailableMessage("内存超限（512MB），请重启 App 或重置 Python 运行环境")
                )
                is AwaitOutcome.Error -> {
                    val cause = outcome.cause
                    PyResult(ok = false, errorCode = "TOOL_ERROR",
                        errorMessage = cause.message ?: cause.javaClass.simpleName)
                }
            }
        } catch (c: CancellationException) {
            // 用户停止：不要吞掉取消信号（Future 已在 awaitWithMemoryGuard 里 cancel）
            throw c
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

    private sealed class AwaitOutcome {
        class Ok(val raw: String) : AwaitOutcome()
        object Timeout : AwaitOutcome()
        object MemorySoft : AwaitOutcome()
        object MemoryHard : AwaitOutcome()
        class Error(val cause: Throwable) : AwaitOutcome()
    }

    /**
     * 进程 RSS（字节）。IN_PROCESS 模式解释器与 App 同进程，无法单独测量解释器内存，
     * 只能按「执行前后的增量」判断用户代码是否吃内存；CHILD 模式由子进程 RSS 单独限制。
     */
    private fun rssBytes(): Long = runCatching {
        val statm = File("/proc/self/statm").readText().trim().split(" ")
        val pages = statm.getOrNull(1)?.toLongOrNull() ?: 0L
        pages * 4096L
    }.getOrDefault(0L)

    /**
     * 等 Future 完成，同时每 200ms 采样一次 RSS：
     * - `timeoutMs <= 0` = **不限时**：不设截止时间，只等完成 / 内存超限 / 用户停止（job 取消）；
     * - 增量 > 256MB（软限）→ 取消 Future，返回 [AwaitOutcome.MemorySoft]（Python 线程无法强杀，结果丢弃）；
     * - 增量 > 512MB（硬限）→ 标记运行时不可用（IN_PROCESS 无法重启解释器），返回 [AwaitOutcome.MemoryHard]；
     * - 用户停止：协程取消后由 [ensureActive] 抛 CancellationException，先 cancel Future 再向上抛，尽快返回。
     */
    private suspend fun awaitWithMemoryGuard(
        future: java.util.concurrent.Future<String>,
        timeoutMs: Long
    ): AwaitOutcome {
        val unlimited = timeoutMs <= 0
        val deadline = if (unlimited) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
        val baseline = rssBytes()
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
            } catch (c: CancellationException) {
                future.cancel(true)
                throw c
            }
            if (!unlimited && System.currentTimeMillis() >= deadline) {
                future.cancel(true)
                return AwaitOutcome.Timeout
            }
            val slice = if (unlimited) {
                MEMORY_POLL_MS
            } else {
                minOf(MEMORY_POLL_MS, (deadline - System.currentTimeMillis()).coerceAtLeast(1L))
            }
            try {
                return AwaitOutcome.Ok(future.get(slice, TimeUnit.MILLISECONDS))
            } catch (_: TimeoutException) {
                // 正常轮询
            } catch (e: ExecutionException) {
                return AwaitOutcome.Error(e.cause ?: e)
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                return AwaitOutcome.Error(e)
            }
            val now = rssBytes()
            if (baseline > 0 && now > baseline) {
                val growth = now - baseline
                if (growth > HARD_MEMORY_LIMIT_BYTES) {
                    future.cancel(true)
                    state = State.FAILED
                    lastError = "Python 解释器内存超过 512MB"
                    return AwaitOutcome.MemoryHard
                }
                if (growth > SOFT_MEMORY_LIMIT_BYTES) {
                    future.cancel(true)
                    return AwaitOutcome.MemorySoft
                }
            }
        }
    }

    private fun timeoutResult(timeoutMs: Int, what: String): PyResult = PyResult(
        ok = false,
        errorCode = "TOOL_TIMEOUT",
        errorMessage = "Python ${what}超时（${timeoutMs / 1000}s），已丢弃结果"
    )

    /**
     * 输出后处理（IN_PROCESS / CHILD 共用）：
     * - 总量截到 1MB；
     * - 超过 64KB 时把完整输出写到 `<会话工作区>/python_out.txt`，路径放进 [PyResult.outputPath]；
     * - 回给模型的 64KB 截断由 ChatScreen 负责（这里保留完整 1MB）。
     */
    internal fun finalizeOutput(context: Context, conversationId: String, result: PyResult): PyResult {
        if (!result.ok) return result
        val full = truncate(result.output)
        var path: String? = null
        if (full.length > OUTPUT_FILE_HINT_LIMIT) {
            path = runCatching {
                val dir = FileTools.workspaceDir(context, conversationId).also { it.mkdirs() }
                val f = File(dir, OUTPUT_FILE_NAME)
                f.writeText(full)
                f.absolutePath
            }.onFailure { Log.w("PyRuntime", "写 python_out.txt 失败", it) }.getOrNull()
        }
        return result.copy(output = full, outputPath = path)
    }

    private fun parseResult(context: Context, conversationId: String, raw: String): PyResult {
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return finalizeOutput(context, conversationId, PyResult(ok = true, output = raw))
        return if (o.optBoolean("ok", false)) {
            finalizeOutput(context, conversationId, PyResult(ok = true, output = o.optString("result")))
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
