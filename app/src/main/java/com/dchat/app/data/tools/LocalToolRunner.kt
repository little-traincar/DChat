package com.dchat.app.data.tools

import android.content.Context
import android.util.Log
import com.dchat.app.ui.lang.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 第四期 4c：内置原生工具（文件 / 后台任务 / 定时任务）的统一执行分派。
 *
 * ChatScreen 的客户端工具循环只认三件事：`handles(id)` 判断是不是本类工具、`run()` 拿到
 * 统一的 [PyResult]、其余仍走 Python / 用户工具。审批与权限判定都在各自执行器内部完成，
 * 避免把 fs 的路径级权限逻辑塞进 UI 层。
 */
object LocalToolRunner {

    const val FS_PREFIX = "fs."
    const val BG_PREFIX = "bg."
    const val SCHEDULE_PREFIX = "schedule."
    const val DOWNLOAD = "local.download"
    const val IMAGEGEN = "local.imagegen"

    fun handles(toolId: String): Boolean =
        toolId == DOWNLOAD ||
            toolId == IMAGEGEN ||
            toolId.startsWith(FS_PREFIX) ||
            toolId.startsWith(BG_PREFIX) ||
            toolId.startsWith(SCHEDULE_PREFIX)

    /** 是否属于「本地原生工具」这一组（注册表 seed 用同一条判定，保证请求侧/执行侧一致） */
    fun isNativeTool(toolId: String): Boolean = handles(toolId)

    /**
     * 执行一次 `fs.* / bg.* / schedule.*` 调用。
     * 权限先过 [ToolRegistry.canExecute]；路径级（工作区内外）判定在各执行器里做。
     */
    suspend fun run(
        context: Context,
        toolId: String,
        argsJson: String,
        conversationId: String?,
        mode: String,
        /** 用户已停止生成：生图等长耗时工具在落盘前用它做最后校验（默认不取消） */
        isCancelled: () -> Boolean = { false }
    ): PyResult {
        val normalized = ToolRegistry.normalizeConversationMode(mode)
        if (!ToolRegistry.canExecute(toolId, normalized)) {
            return PyResult(
                ok = false,
                errorCode = "PERMISSION_DENIED",
                errorMessage = Lang.current.local.fsNeedWorkspace
            )
        }
        // 统一放到 IO：fs 读写、bg/schedule 的 JSON 落盘、审批挂起、生图网络请求都不占主线程
        return withContext(Dispatchers.IO) {
            when (toolId) {
                DOWNLOAD -> DownloadTool.run(context, argsJson, conversationId ?: "preview")
                IMAGEGEN -> ImageGenTool.run(
                    context = context,
                    argsJson = argsJson,
                    conversationId = conversationId ?: "preview",
                    conversationMode = normalized,
                    isCancelled = isCancelled
                )

                else -> FileTools.execute(context, toolId, argsJson, conversationId, normalized)
                    ?: BgTasks.execute(context, toolId, argsJson, conversationId)
                    ?: ScheduleStore.execute(context, toolId, argsJson, conversationId)
                    ?: PyResult(
                        ok = false,
                        errorCode = "TOOL_NOT_FOUND",
                        errorMessage = "Unknown native tool: $toolId"
                    )
            }
        }
    }
}

/**
 * App 启动时把原生工具的持久化状态接上：
 * - [BgTasks]：恢复任务列表；重启前 RUNNING / QUEUED 的都标为中断失败（不自动续跑，
 *   避免 App 启动/收广播时去起前台服务导致 5 秒看门狗超时）；
 * - [ScheduleStore]：加载定时任务并重新挂 AlarmManager（进程重启后闹钟虽然还在，重挂一次更稳）。
 *
 * 由 [ToolRegistry.init] 在 AppState 初始化时调用一次（幂等），避免改 MainActivity。
 */
object LocalToolsBootstrap {

    private const val TAG = "LocalToolsBootstrap"

    @Volatile
    private var done = false

    fun ensure(context: Context) {
        if (done) return
        done = true
        runCatching { BgTasks.init(context) }
            .onFailure { Log.w(TAG, "后台任务初始化失败", it) }
        runCatching { ScheduleStore.restoreOnStartup(context) }
            .onFailure { Log.w(TAG, "定时任务初始化失败", it) }
    }
}
