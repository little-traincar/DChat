package com.dchat.app.data.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call

/**
 * 会话生成的「屏幕之外」运行时。
 *
 * 背景（用户反馈的 bug）：生成跑到一半时去设置页，ChatScreen 退出组合，挂在
 * rememberCoroutineScope 上的请求协程被取消、消息永远停在 STATUS_WIP，
 * 回来就是一张「正在思考…」的卡死卡片。
 *
 * 所以整轮生成（含工具循环、生图计时）必须挂在**进程级** scope 上，并且把
 * job / OkHttp Call / 本轮 token 放在这里，这样：
 *  - 离开聊天页：生成继续（AppState 里的消息照常更新），回来能看到进度；
 *  - 回到聊天页后点「停止」：新实例也能取到同一个 job/call 把它取消；
 *  - App 被杀：下次启动由 ChatScreen 的自愈逻辑把残留 WIP 标记为已中断。
 */
object ChatRuntime {

    /** 进程级 scope：SupervisorJob 保证单轮失败不会拖垮后续轮次 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前这轮生成的 job（用户停止时取消） */
    var job: Job? = null

    /** 当前这轮流式请求的 OkHttp Call（停止时立即断流） */
    var call: Call? = null

    /** 本轮标识：工具循环、流式回调用它判断是否已被停止/新轮次替换 */
    private var token: Any? = null

    /** 生图占位卡计时起点（elapsedRealtime ms；0 = 当前没有进行中的生图） */
    var imageGenStartedAt: Long = 0L

    /** 开始新一轮生成，返回本轮 token */
    fun begin(): Any {
        val t = Any()
        token = t
        return t
    }

    /** 该 token 是否仍是当前轮次 */
    fun isCurrent(t: Any?): Boolean = t != null && token === t

    /** 停止当前轮次：作废 token、断流、取消协程（清空引用） */
    fun cancelCurrent() {
        token = null
        imageGenStartedAt = 0L
        runCatching { call?.cancel() }
        call = null
        runCatching { job?.cancel() }
        job = null
    }
}
