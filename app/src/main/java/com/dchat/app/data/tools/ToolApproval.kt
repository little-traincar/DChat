package com.dchat.app.data.tools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次待审批的工具调用（第三期）。
 * 字段都是给弹窗展示的短文案；不含业务执行参数（文件本身在调用方持有）。
 */
data class ToolApprovalRequest(
    val toolId: String,
    val toolName: String,
    /** 一句话说明这次要做什么（如「将把 report.pdf 上传到服务端解析，可能产生费用」） */
    val action: String,
    /** 参数摘要（弹窗正文第二行） */
    val args: String,
    /** 用户不回应时的等待上限，超时按拒绝处理 */
    val timeoutMs: Int = 120_000
)

/**
 * 一次性工具审批（第三期）。
 *
 * 语义照 DSH 的 `allowed-once`：只有 [complete]`(true)` 才放行；拒绝、关闭弹窗、超时、
 * 或被新的请求顶掉，一律按拒绝处理（fail-closed）。Compose 读 [pending] 渲染弹窗。
 *
 * 线程模型：同一时刻只允许一个待审批请求；[request] 是挂起函数，由执行工具的协程调用。
 */
object ToolApproval {

    /** 当前待审批请求；null = 没有弹窗。Compose 可观察。 */
    var pending by mutableStateOf<ToolApprovalRequest?>(null)
        private set

    private val lock = Any()
    private var deferred: CompletableDeferred<Boolean>? = null

    /**
     * 请求一次审批并挂起等待结果。
     * @return true = 用户点了「允许一次」；false = 拒绝/关闭/超时/已有其它审批
     */
    suspend fun request(
        toolId: String,
        toolName: String,
        action: String,
        args: String,
        timeoutMs: Int = 120_000
    ): Boolean {
        val request: ToolApprovalRequest
        val waiter: CompletableDeferred<Boolean>
        synchronized(lock) {
            // 同一时刻只处理一个审批；并发来的直接拒绝（fail-closed）
            if (deferred != null) return false
            request = ToolApprovalRequest(toolId, toolName, action, args, timeoutMs)
            waiter = CompletableDeferred()
            deferred = waiter
            pending = request
        }
        return try {
            val allowed = withTimeoutOrNull(timeoutMs.toLong()) { waiter.await() }
            allowed == true
        } finally {
            synchronized(lock) {
                if (deferred === waiter) {
                    deferred = null
                    pending = null
                } else {
                    // 被 complete() 完成过：状态已经清空，这里只兜底
                    if (pending?.toolId == request.toolId && pending?.action == request.action) {
                        pending = null
                    }
                }
            }
        }
    }

    /** Compose 弹窗回调：允许一次 / 拒绝。可重复调用，第二次开始无效。 */
    fun complete(allow: Boolean) {
        synchronized(lock) {
            val waiter = deferred ?: return
            deferred = null
            pending = null
            waiter.complete(allow)
        }
    }
}
