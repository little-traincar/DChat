package com.dchat.app.ui.tools

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dchat.app.data.tools.PyResult
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.data.tools.ToolRegistry
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val TAG = "UiV3Host"

/** 工具 timeout 缺省值（规范 §4：动作超时 = 工具 timeout，默认 10s） */
private const val DEFAULT_TIMEOUT_MS = 10_000

/** 没有真实会话 id 时的 Python state 文件名（与 v2 ToolUiScreen 的 `state/page.json` 保持一致） */
private const val FALLBACK_STATE_ID = "page"

/** canvas 单帧 ops 上限（规范 `tool-ui-v3-canvas.md` §1） */
private const val CANVAS_MAX_OPS = 800

/** canvas 单帧 Python 耗时软上限：超过即降到 15fps 并打日志（规范 §5） */
private const val CANVAS_SLOW_MS = 25L

/** 慢帧降级后的目标帧率 */
private const val CANVAS_DEGRADED_FPS = 15

/** canvas_frame 硬超时：坏帧不能长期占住共享的 Python 串行队列（页面动作也在排队） */
private const val CANVAS_FRAME_TIMEOUT_MS = 2_000

/** 手势串行队列上限：discrete 手势最多排 8 个，move/pan 只保留最新一条（不无限堆积） */
private const val CANVAS_GESTURE_QUEUE = 8

/**
 * UI Schema v3 的页面栈项。
 *
 * - [base]：`ui/pages/<page>.json` 解析出的静态页面（打开/切换页时读一次）；
 * - [body]：`page_load` / `page_action` 返回的整页 body 覆盖（服务端驱动渲染），null = 用静态 body；
 * - [state]：**只活在内存里**（规范 §3.2：控件值/动作结果在宿主累积，下次动作回传；关闭即丢弃，
 *   工具数据由工具自己在 workspace 持久化），每次变更都换新 JSONObject 以触发渲染器重组。
 */
private data class UiV3PageEntry(
    val page: String,
    val args: JSONObject = JSONObject(),
    val state: JSONObject = JSONObject(),
    val base: UiPage? = null,
    val body: UiNode? = null,
    val title: String = "",
    val loading: Boolean = false,
    /** 页面 JSON 原文：外观配置（nav / background.cover）只在这里读，模型未建模也能用（规范 canvas §9） */
    val raw: JSONObject? = null
)

/**
 * 工具页 chrome 配置（规范 canvas §9，全部来自页面/ manifest JSON，模型没写就用默认值）。
 * 宿主只负责「系统返回 + 安全兜底」，顶栏/底栏/背景的观感全部由页面 JSON 决定。
 */
private data class UiChromeConfig(
    /** screen（默认，铺满整页）/ content（只铺 body 区域，旧行为） */
    val backgroundCover: String = "screen",
    /** nav.show，默认 true */
    val navShow: Boolean = true,
    /** auto（默认）/ transparent / solid / none */
    val navStyle: String = "auto",
    /** 顶栏标题覆盖，空 = page.title / manifest.title */
    val navTitle: String = "",
    /** 是否显示返回箭头（系统返回始终可用） */
    val navBack: Boolean = true,
    /** 顶栏 tint（#RRGGBB/#AARRGGBB），空 = 自动 */
    val navTint: String = "",
    /** tabBar：glass（默认）/ solid / none */
    val tabStyle: String = "glass",
    /** tabBar tint，空 = 自动 */
    val tabTint: String = "",
    /** tab 图标（首版不实现，仅解析保留） */
    val tabIcons: Boolean = false
) {
    val navHidden: Boolean get() = !navShow || navStyle == "none"
    val coverContent: Boolean get() = backgroundCover == "content"
    val tabsHidden: Boolean get() = tabStyle == "none"
}

/**
 * 单个 canvas（`id`/viewId）的帧循环状态（P3，规范 `tool-ui-v3-canvas.md` §5）。
 *
 * 线程约定：只在**主线程**读写——渲染器 `withFrameNanos` 每帧在主线程调 [UiV3Host] 的
 * `onCanvasFrame`，Python 结果也回到主线程应用；`lastFrame` 是「最近一帧已完成结果」，
 * 回调同步返回它（渲染器下一帧就能看到新 ops），Python 调用永远不阻塞 UI。
 */
private class UiV3CanvasLoop(val page: String, val viewId: String) {
    /** 渲染器最近一次给的 canvas 内 dp 尺寸与时间 */
    var width: Float = 0f
    var height: Float = 0f
    var tMs: Long = 0L
    var dtMs: Long = 0L

    /** 最近一帧（返回给渲染器）；stop=true 后不再请求 Python */
    var lastFrame: CanvasFrame? = null

    /** 上一次回调对应的 canvas 节点：引用变化 = body 被替换/渲染器重建，循环状态需要重置 */
    var lastNode: UiNode? = null

    /** 当前目标帧率（10..30；0 = 尚未初始化，首帧取节点 fps；慢帧后压到 15） */
    var fps: Int = 0

    /** 是否已因慢帧降级（只降不升，避免抖动） */
    var degraded: Boolean = false

    /** 工具返回 stop=true 后置位：本 canvas 循环结束，定时器相当于释放 */
    var stopRequested: Boolean = false

    /** 本 canvas 是否有一次帧调用在飞（配合 runtime 的全局单飞） */
    var inFlight: Boolean = false

    /** 连续失败次数：达到 3 次就停掉本 canvas 的循环，避免每帧刷日志/空转（成功即清零） */
    var errorCount: Int = 0

    /** 上次真正发起 Python 帧调用的时刻（elapsedRealtime，节流用） */
    var lastRequestAt: Long = 0L

    var job: Job? = null

    /** 手势单飞：串行队列；move/pan 高频只保留最新一条，discrete 手势最多排 [CANVAS_GESTURE_QUEUE] 个 */
    var gestureInFlight: Boolean = false
    var gestureJob: Job? = null
    val gestureQueue: java.util.ArrayDeque<Pair<String, JSONObject>> = java.util.ArrayDeque()
}

/**
 * canvas 帧调度的全局状态（每个宿主实例一份）。
 *
 * - [activeCalls]：**全局 canvas 桥调用计数**（帧/手势都算）；帧只在 0 时发起（规范 §5「所有桥调用串行」），
 *   手势在飞时帧不排队（避免把互斥锁等待算成慢帧）；
 * - [paused]：页面不可见 / App 退后台时暂停发起新帧（已完成的 `lastFrame` 保留，回前台直接复用）；
 * - [generation]：切页/暂停/销毁时自增，旧协程的 finally 不会误清新一代的计数。
 */
private class UiV3CanvasRuntime {
    var activeCalls: Int = 0
    var paused: Boolean = false
    var generation: Long = 0L
}

/**
 * UI Schema v3 多页宿主（纯原生 Compose，无 WebView）。
 *
 * 由 [ToolUiScreen] 在「存在且校验通过 `ui/manifest.json`」时调用（版本分发在 ToolUiScreen）。
 * 职责：
 * 1. 页面栈（`mutableStateListOf<UiV3PageEntry>`）：navigate push / back pop / 根页返回退出宿主；
 * 2. 背景：[UiV3Background] 默认铺满整屏（页面 `background.cover=content` 时退回只铺 body）；
 * 3. chrome（规范 canvas §10）：**宿主不画顶栏**，只在左上角浮一个始终显示的 × 关闭按钮
 *    （点击直接 onExit，不管栈深）；标题/返回/导航全部由模型页面 JSON 决定；
 *    `nav{...}` 字段仍解析保留（[UiChromeConfig]），按 §10 默认不渲染；
 * 4. manifest.tab 3–5 项 + `tabStyle`（glass/solid/none）→ 底部浮层 tabBar（切换 = 清栈到该页的根）；
 * 5. 事件桥：`page_load/page_action` 走 [PyRuntime.runCode]，适配器由 [UiV2Protocol] 追加；
 * 6. P3 canvas：`canvas_frame` 逐帧（tick=true）/ `canvas_gesture` 手势，单飞串行、慢帧降级、切页/退后台暂停；
 * 7. 图片 src 统一用 [UiV3Store.resolveSrc] 解析成绝对路径交给渲染器。
 *
 * 并发约定：同一个工具的所有 Python 调用共用一把 Mutex；动作在跑时新的点击直接忽略
 * （不排队堆积，避免频繁点击把队列撑爆），失败/超时只 toast，界面保持原状。
 */
@Composable
fun UiV3Host(
    toolId: String,
    /** 工具入口代码（`entry.py` 原文，适配器由本宿主追加） */
    code: String,
    /** 工具的权限档位（ToolRegistry.CONV_READ / CONV_WORKSPACE / CONV_FULL） */
    conversationMode: String,
    /** 当前会话 id：`workspace:` 图片按此解析；null 时降级（可能落到 preview 工作区） */
    conversationId: String?,
    manifest: UiManifest,
    /** 根页返回 = 退出宿主回聊天 */
    onExit: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val stack = remember(toolId) { mutableStateListOf<UiV3PageEntry>() }
    val pyMutex = remember(toolId) { Mutex() }
    var actionBusy by remember(toolId) { mutableStateOf(false) }
    var booting by remember(toolId) { mutableStateOf(true) }
    var fatal by remember(toolId) { mutableStateOf<String?>(null) }
    /** manifest.json 原文：tabStyle 只在这里读（规范 canvas §9） */
    var manifestRaw by remember(toolId) { mutableStateOf<JSONObject?>(null) }

    val adaptedCode = remember(code) { UiV2Protocol.adaptedCodeV3(code) }
    val hasPageLoad = remember(code) { UiV2Protocol.hasPageLoad(code) }
    val hasPageAction = remember(code) { UiV2Protocol.hasPageAction(code) }
    val hasCanvasFrame = remember(code) { UiV2Protocol.hasCanvasFrame(code) }
    val hasCanvasGesture = remember(code) { UiV2Protocol.hasCanvasGesture(code) }
    val timeoutMs = remember(toolId) {
        (ToolRegistry.byId(toolId)?.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceIn(1_000, 30_000)
    }
    val stateId = remember(conversationId) {
        conversationId?.takeIf { it.isNotBlank() } ?: FALLBACK_STATE_ID
    }
    // 图片路径解析缓存：resolveSrc 内部有 canonicalFile/isFile 磁盘访问，渲染期间避免重复解析
    val imageCache = remember(toolId) { HashMap<String, File>() }
    // 稳定 lambda 身份：渲染器用 onResolveImage 做 remember key，每次重组换 lambda 会让它重复解析文件
    val imageResolver: (String) -> String? = remember(toolId, conversationId, ctx, imageCache) {
        { src: String ->
            if (src.isBlank()) {
                null
            } else {
                val cached = imageCache[src]
                if (cached != null && cached.isFile) {
                    cached.absolutePath
                } else {
                    val f = UiV3Store.resolveSrc(ctx, toolId, conversationId, src)
                    if (f == null) {
                        null
                    } else {
                        imageCache[src] = f
                        f.absolutePath
                    }
                }
            }
        }
    }

    // ---- P3 canvas：帧循环状态（主线程读写；切页/退出/退后台取消或暂停） ----
    val canvasLoops = remember(toolId) { HashMap<String, UiV3CanvasLoop>() }
    val canvasRuntime = remember(toolId) { UiV3CanvasRuntime() }

    /** 当前页真正用于渲染的 body（page_action 返回的动态 body 优先） */
    fun currentPageBody(entry: UiV3PageEntry): UiNode? = entry.body ?: entry.base?.body

    /** 在页面树里找 canvas 节点（viewId 对应节点的 id；深度已被模型限制 ≤10） */
    fun findCanvasNode(root: UiNode?, viewId: String): UiNode? {
        if (root == null) return null
        if (root.type == "canvas" && root.id == viewId) return root
        root.children.forEach { child -> findCanvasNode(child, viewId)?.let { return it } }
        return null
    }

    fun canvasLoop(page: String, viewId: String): UiV3CanvasLoop =
        canvasLoops.getOrPut("$page\u0000$viewId") { UiV3CanvasLoop(page, viewId) }

    /** 规范 §5：慢帧后压到 15fps；工具声明的 fps 已被模型 clamp 到 10..30 */
    fun effectiveCanvasFps(loop: UiV3CanvasLoop, node: UiNode): Int {
        val base = if (loop.fps in 10..30) loop.fps else node.fps.coerceIn(10, 30)
        return if (loop.degraded) minOf(base, CANVAS_DEGRADED_FPS) else base
    }

    /** 暂停帧循环（页面不可见/App 退后台）：取消在飞请求，保留 lastFrame 与降级状态，回前台可续 */
    fun pauseCanvasLoops() {
        canvasRuntime.paused = true
        canvasRuntime.generation++
        canvasRuntime.activeCalls = 0
        canvasLoops.values.forEach { st ->
            st.job?.cancel()
            st.job = null
            st.inFlight = false
            st.gestureQueue.clear()
            st.gestureJob?.cancel()
            st.gestureJob = null
            st.gestureInFlight = false
        }
    }

    fun resumeCanvasLoops() {
        canvasRuntime.paused = false
    }

    /**
     * 切页/关闭宿主：立即取消所有 canvas 的帧循环（规范 §5）。
     * @param cancelGestures false 时保留手势协程——navigate 由 canvas_gesture 触发时，取消会连带取消
     *   正在执行 push 的这个协程，导致新页 page_load 被跳过；手势结果本身已有 page/index 校验兜底。
     */
    fun cancelCanvasLoops(cancelGestures: Boolean = true) {
        canvasRuntime.generation++
        canvasRuntime.activeCalls = 0
        canvasLoops.values.forEach { st ->
            st.job?.cancel()
            st.job = null
            st.inFlight = false
            st.gestureQueue.clear()
            if (cancelGestures) {
                st.gestureJob?.cancel()
                st.gestureJob = null
                st.gestureInFlight = false
            }
            st.stopRequested = false
        }
        canvasLoops.clear()
    }

    fun toast(msg: String) {
        if (msg.isNotBlank()) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun detailOf(r: PyResult): String =
        (r.errorMessage ?: "").ifBlank { r.errorCode ?: "TOOL_ERROR" }.take(300)

    fun failText(r: PyResult, opening: Boolean = false): String =
        if (UiV2Protocol.isPyUnavailable(r)) L.tools.uiPyUnavailable
        else L.format(if (opening) L.tools.uiOpenFailed else L.tools.uiActionFailed, detailOf(r))

    fun mergeState(cur: JSONObject, delta: JSONObject?): JSONObject {
        if (delta == null) return cur
        val out = JSONObject(cur.toString())
        delta.keys().forEach { key ->
            val v = delta.opt(key)
            // 规范：state 合并。JSON null 视为「删除该 key」，方便工具清空状态
            if (v == null || v === JSONObject.NULL) out.remove(key) else out.put(key, v)
        }
        return out
    }

    /**
     * 规范 §3.2：page_action 第 3 个参数是「params + state」（按钮/控件参数覆盖同名 state），
     * 第 4 个参数仍是整份 state；这样工具既可按事件参数取值，也能直接读控件当前值。
     */
    fun mergeParams(state: JSONObject, params: JSONObject): JSONObject {
        val out = JSONObject(state.toString())
        params.keys().forEach { key ->
            val v = params.opt(key)
            // 渲染器 slider 会传 Float 进 params；org.json 统一按 Double 序列化
            out.put(key, if (v is Float) v.toDouble() else v)
        }
        return out
    }

    /**
     * `page_load/page_action` 返回的 body 是裸节点 JSON；UiV3Model 只公开 parsePage，
     * 这里包一层最小页面壳交给它解析（不校验，动态 body 允许只写必要字段）。
     */
    fun parseBody(obj: JSONObject?): UiNode? {
        if (obj == null) return null
        val wrapped = JSONObject().put("version", 3).put("body", obj)
        return UiV3Model.parsePage(wrapped)?.body
    }

    fun goBack() {
        cancelCanvasLoops()
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit()
    }

    /** 左上角 × 按钮：直接退出工具页（不管栈深），先取消 canvas 帧循环 */
    fun exitHost() {
        cancelCanvasLoops()
        onExit()
    }

    /** 按 index 替换栈项（index 失效时忽略，避免异步结果越界） */
    fun replaceAt(index: Int, next: (UiV3PageEntry) -> UiV3PageEntry) {
        if (index in stack.indices) stack[index] = next(stack[index])
    }

    /** 所有 Python 调用串行：同一工具共用一把 Mutex（entry_preview 临时文件不并发写） */
    suspend fun callPy(
        entryFunction: String,
        payload: JSONObject,
        /** canvas 帧用更短的硬超时，避免坏帧长期占住页面动作的队列 */
        overrideTimeoutMs: Int? = null
    ): PyResult = pyMutex.withLock {
        PyRuntime.runCode(
            context = ctx,
            toolId = toolId,
            entryFunction = entryFunction,
            code = adaptedCode,
            argsJson = payload.toString(),
            conversationId = stateId,
            timeoutMs = overrideTimeoutMs ?: timeoutMs,
            conversationMode = conversationMode
        )
    }

    /** page_load：成功合并 state/body/title；失败保留静态页只提示（规范 §3.1/§4） */
    suspend fun runLoad(index: Int) {
        val start = stack.getOrNull(index) ?: return
        if (start.base == null) {
            replaceAt(index) { it.copy(loading = false) }
            return
        }
        val pageName = start.page
        val r = callPy(
            UiV2Protocol.ENTRY_V3_LOAD,
            UiV2Protocol.v3LoadPayload(pageName, start.args, start.state)
        )
        val cur = stack.getOrNull(index) ?: return
        if (cur.page != pageName) return
        if (!r.ok) {
            Log.w(TAG, "page_load failed tool=$toolId page=$pageName code=${r.errorCode} msg=${r.errorMessage}")
            replaceAt(index) { it.copy(loading = false) }
            toast(failText(r, opening = true))
            return
        }
        val result = UiV2Protocol.parseObject(r.output)
        if (result == null) {
            Log.w(TAG, "page_load output is not a JSON object tool=$toolId page=$pageName")
            replaceAt(index) { it.copy(loading = false) }
            return
        }
        val err = UiV2Protocol.errorOf(result)
        if (err.isNotBlank()) {
            Log.w(TAG, "page_load reported error tool=$toolId page=$pageName: $err")
            replaceAt(index) { it.copy(loading = false) }
            toast(err)
            return
        }
        val nextState = mergeState(cur.state, result.optJSONObject("state"))
        val body = parseBody(result.optJSONObject("body"))
        val title = result.optString("title")
        replaceAt(index) {
            it.copy(
                state = nextState,
                body = body ?: it.body,
                title = title.ifBlank { it.title },
                loading = false
            )
        }
    }

    /** navigate：push 新页 → page_load(新page, params, 当前 state)（规范 §3.3） */
    suspend fun push(page: String, args: JSONObject, initial: JSONObject) {
        if (!UiV3Model.isPageName(page)) {
            Log.w(TAG, "navigate to illegal page tool=$toolId page=$page")
            toast(L.format(L.tools.uiOpenFailed, page))
            return
        }
        val base = withContext(Dispatchers.IO) { UiV3Store.readPage(ctx, toolId, page) }
        if (base == null) {
            Log.w(TAG, "navigate to missing page tool=$toolId page=$page")
            toast(L.format(L.tools.uiOpenFailed, page))
            return
        }
        val rawPage = withContext(Dispatchers.IO) {
            readRawUiJson(File(UiV3Store.pagesDir(ctx, toolId), "$page.json"))
        }
        cancelCanvasLoops(cancelGestures = false) // push 新页：取消上一页帧循环（保留触发本次 navigate 的手势协程）
        stack.add(
            UiV3PageEntry(
                page = page,
                args = args,
                state = initial,
                base = base,
                loading = hasPageLoad,
                raw = rawPage
            )
        )
        Log.i(TAG, "navigate tool=$toolId page=$page depth=${stack.size}")
        if (hasPageLoad) runLoad(stack.lastIndex)
    }

    /**
     * page_action 结果应用顺序（规范 §3.2）：state 合并 → body 替换 → navigate/back → toast。
     * 等待期间用户可能返回/切页：只有页面还在原 index 才应用；navigate/back 只在仍是当前页时执行。
     */
    suspend fun applyActionResult(index: Int, pageName: String, result: JSONObject) {
        val cur = stack.getOrNull(index) ?: return
        if (cur.page != pageName) return
        val nextState = mergeState(cur.state, result.optJSONObject("state"))
        val body = parseBody(result.optJSONObject("body"))
        val title = result.optString("title")
        stack[index] = cur.copy(
            state = nextState,
            body = body ?: cur.body,
            title = title.ifBlank { cur.title }
        )
        val toastText = result.optString("toast")
        if (toastText.isNotBlank()) toast(toastText)
        if (index != stack.lastIndex) return // 已不是当前页：只保留 state/body，不执行导航
        val nav: Any? = result.opt("navigate")
        when {
            nav is JSONObject -> {
                val target = nav.optString("page")
                val navArgs = nav.optJSONObject("args") ?: nav.optJSONObject("params") ?: JSONObject()
                if (target.isNotBlank()) push(target, navArgs, nextState)
            }
            nav is String && nav.isNotBlank() -> push(nav, JSONObject(), nextState)
            result.optBoolean("back", false) -> goBack()
        }
    }

    /** 动作入口：串行 + 忙碌时忽略点击（不排队堆积）；失败/超时只 toast，界面不变 */
    fun onAction(action: String, params: JSONObject) {
        if (action.isBlank()) return
        if (!hasPageAction) {
            Log.d(TAG, "page_action not declared, ignore tool=$toolId action=$action")
            return
        }
        if (actionBusy) {
            Log.d(TAG, "action ignored (busy) tool=$toolId action=$action")
            return
        }
        val entry = stack.lastOrNull() ?: return
        if (entry.loading) return
        val index = stack.lastIndex
        val pageName = entry.page
        val clickState = entry.state
        actionBusy = true
        Log.d(TAG, "action tool=$toolId page=$pageName name=$action")
        scope.launch {
            try {
                val r = callPy(
                    UiV2Protocol.ENTRY_V3_ACTION,
                    UiV2Protocol.v3ActionPayload(
                        pageName,
                        action,
                        mergeParams(clickState, params),
                        clickState
                    )
                )
                if (!r.ok) {
                    toast(failText(r))
                    return@launch
                }
                val result = UiV2Protocol.parseObject(r.output)
                if (result == null) {
                    Log.w(TAG, "page_action output is not a JSON object tool=$toolId action=$action")
                    return@launch
                }
                val err = UiV2Protocol.errorOf(result)
                if (err.isNotBlank()) {
                    Log.w(TAG, "page_action reported error tool=$toolId action=$action: $err")
                    toast(err)
                    return@launch
                }
                applyActionResult(index, pageName, result)
            } catch (t: Throwable) {
                // 兜底：任何宿主侧异常都不许崩（规范 §4）
                Log.e(TAG, "action crashed tool=$toolId action=$action", t)
                toast(L.format(L.tools.uiActionFailed, (t.message ?: "TOOL_ERROR").take(300)))
            } finally {
                actionBusy = false
            }
        }
    }

    /** 手势 data 拷贝（Float→Double 供 org.json 序列化）+ 补 gesture 字段，供 page_action 兜底用 */
    fun copyGestureParams(data: JSONObject, gesture: String): JSONObject {
        val out = JSONObject()
        data.keys().forEach { key ->
            val v = data.opt(key)
            out.put(key, if (v is Float) v.toDouble() else v)
        }
        out.put("gesture", gesture)
        return out
    }

    /**
     * 真正发起一次 `canvas_frame` Python 调用：
     * - **全局单飞**（`canvasRuntime.activeCalls == 0` 才发起）：帧不排队，忙就等下一帧；
     * - 结果回主线程写入 [UiV3CanvasLoop.lastFrame]，下一帧回调自然返回给渲染器；
     * - 异常/超时/None/工具报错：只丢该帧（保留上一帧），不崩、不 toast（避免每帧刷屏）。
     */
    fun requestCanvasFrame(
        loop: UiV3CanvasLoop,
        node: UiNode,
        pageName: String,
        stateSnapshot: JSONObject,
        tMs: Long,
        dtMs: Long,
        w: Float,
        h: Float
    ) {
        if (canvasRuntime.activeCalls > 0) return
        val gen = canvasRuntime.generation
        canvasRuntime.activeCalls++
        loop.inFlight = true
        loop.lastRequestAt = SystemClock.elapsedRealtime()
        loop.job = scope.launch {
            val startMs = SystemClock.elapsedRealtime()
            try {
                val r = callPy(
                    UiV2Protocol.ENTRY_V3_CANVAS_FRAME,
                    UiV2Protocol.v3CanvasFramePayload(pageName, loop.viewId, tMs, dtMs, w, h, stateSnapshot),
                    overrideTimeoutMs = minOf(timeoutMs, CANVAS_FRAME_TIMEOUT_MS)
                )
                val costMs = SystemClock.elapsedRealtime() - startMs
                if (!r.ok) {
                    Log.w(
                        TAG,
                        "canvas_frame failed tool=$toolId page=$pageName view=${loop.viewId} " +
                            "code=${r.errorCode} msg=${r.errorMessage} (drop frame)"
                    )
                    // 连续失败 3 次：停掉该 canvas 循环（保留上一帧），避免每帧空转刷日志
                    loop.errorCount++
                    if (loop.errorCount >= 3) {
                        loop.stopRequested = true
                        Log.w(TAG, "canvas_frame keeps failing, stop loop tool=$toolId view=${loop.viewId}")
                    }
                    return@launch
                }
                val obj = UiV2Protocol.parseObject(r.output)
                if (obj == null) {
                    // canvas_frame 返回 None / 空输出：丢弃该帧，界面保留上一帧（规范 §7-5）
                    Log.d(TAG, "canvas_frame returned no frame tool=$toolId view=${loop.viewId}")
                    loop.errorCount++
                    if (loop.errorCount >= 3) {
                        loop.stopRequested = true
                        Log.w(TAG, "canvas_frame keeps returning None, stop loop tool=$toolId view=${loop.viewId}")
                    }
                    return@launch
                }
                val err = UiV2Protocol.errorOf(obj)
                if (err.isNotBlank()) {
                    Log.w(TAG, "canvas_frame reported error tool=$toolId view=${loop.viewId}: $err (drop frame)")
                    loop.errorCount++
                    if (loop.errorCount >= 3) {
                        loop.stopRequested = true
                        Log.w(TAG, "canvas_frame keeps reporting errors, stop loop tool=$toolId view=${loop.viewId}")
                    }
                    return@launch
                }
                // 慢帧：单次 >25ms → 降到 15fps（只降一次 + 日志标注，规范 §5）
                if (costMs > CANVAS_SLOW_MS && !loop.degraded) {
                    loop.degraded = true
                    Log.w(
                        TAG,
                        "canvas frame slow ${costMs}ms (> ${CANVAS_SLOW_MS}ms) tool=$toolId " +
                            "view=${loop.viewId}: degrade to ${CANVAS_DEGRADED_FPS}fps"
                    )
                }
                // ops：单帧最多 800 条，超过截断并在日志标注。
                // 工具只回 {"fps"/"stop"} 不带 ops 时保留上一帧 ops（避免误清空画布）。
                val opsArr = obj.optJSONArray("ops")
                val ops: List<UiOp> = if (opsArr == null) {
                    loop.lastFrame?.ops ?: node.ops.take(CANVAS_MAX_OPS)
                } else {
                    val srcCount = opsArr.length()
                    val take = minOf(srcCount, CANVAS_MAX_OPS)
                    if (srcCount > CANVAS_MAX_OPS) {
                        Log.w(
                            TAG,
                            "canvas ops truncated $srcCount -> $CANVAS_MAX_OPS tool=$toolId view=${loop.viewId}"
                        )
                    }
                    val list = ArrayList<UiOp>(take)
                    for (i in 0 until take) {
                        opsArr.optJSONObject(i)?.let { list.add(UiV3Model.parseOp(it)) }
                    }
                    list
                }
                val fpsOverride = obj.optInt("fps", 0)
                if (fpsOverride in 10..30) loop.fps = fpsOverride
                val stop = obj.optBoolean("stop", false)
                if (stop) loop.stopRequested = true
                loop.errorCount = 0 // 成功出帧才清零（None/报错会累计到 3 次后停循环）
                loop.lastFrame = CanvasFrame(
                    ops = ops,
                    // stop 后 fps=0（渲染器只认 10..30），正常帧给当前有效帧率
                    fps = if (stop) 0 else effectiveCanvasFps(loop, node),
                    stop = stop
                )
            } catch (t: Throwable) {
                // 只丢该帧，保留上一帧
                Log.w(TAG, "canvas_frame crashed tool=$toolId view=${loop.viewId}", t)
                loop.errorCount++
                if (loop.errorCount >= 3) {
                    loop.stopRequested = true
                    Log.w(TAG, "canvas_frame keeps crashing, stop loop tool=$toolId view=${loop.viewId}")
                }
            } finally {
                loop.inFlight = false
                // 暂停/切页会自增 generation：旧协程不要误清新一代的全局计数
                if (canvasRuntime.generation == gen) {
                    canvasRuntime.activeCalls = (canvasRuntime.activeCalls - 1).coerceAtLeast(0)
                }
            }
        }
    }

    /**
     * 渲染器每帧（主线程）回调入口（规范 canvas §1/§5）。
     *
     * 同步返回「最近一帧」；Python 在后台单飞取下一帧。返回 null = 让渲染器保留当前帧
     * （首帧前渲染器用 node.ops 兜底；未声明 canvas_frame / tick=false 也返回 null 走静态）。
     */
    fun onCanvasFrame(viewId: String, tMs: Long, dtMs: Long, w: Float, h: Float): CanvasFrame? {
        if (viewId.isBlank()) return null
        val entry = stack.lastOrNull() ?: return null
        val pageName = entry.page
        val node = findCanvasNode(currentPageBody(entry), viewId) ?: return null
        // tick=false：静态 ops 由渲染器画
        if (!node.tick) return null
        // tick=true 但工具没声明 canvas_frame：静态 ops 画一次就停（规范 §3：缺省时画一次）
        if (!hasCanvasFrame) {
            return CanvasFrame(ops = node.ops.take(CANVAS_MAX_OPS), fps = 0, stop = true)
        }
        val loop = canvasLoop(pageName, viewId)
        // canvas 节点被替换（page_action 返回新 body / 渲染器重建）→ 重置循环，允许重新开始/重新计时
        if (loop.lastNode !== node) {
            loop.lastNode = node
            loop.stopRequested = false
            loop.lastFrame = null
            loop.errorCount = 0
            loop.degraded = false
            loop.lastRequestAt = 0L
            loop.fps = node.fps.coerceIn(10, 30)
        }
        if (loop.fps !in 10..30) loop.fps = node.fps.coerceIn(10, 30)
        loop.tMs = tMs
        loop.dtMs = dtMs
        loop.width = w
        loop.height = h
        if (loop.stopRequested) {
            // 工具要求停止：把 stop 回给渲染器（只回一次有效），并释放后续请求
            return loop.lastFrame?.copy(fps = 0, stop = true) ?: CanvasFrame(fps = 0, stop = true)
        }
        if (canvasRuntime.paused) return loop.lastFrame
        if (entry.loading) return loop.lastFrame // page_load 占着 Python 队列，别把等待算进慢帧
        val now = SystemClock.elapsedRealtime()
        val fps = effectiveCanvasFps(loop, node)
        val intervalMs = (1000L / fps.coerceAtLeast(10)).coerceAtLeast(10L)
        if (!loop.inFlight && canvasRuntime.activeCalls == 0 && !actionBusy && !loop.gestureInFlight &&
            now - loop.lastRequestAt >= intervalMs
        ) {
            requestCanvasFrame(loop, node, pageName, entry.state, tMs, dtMs, w, h)
        }
        return loop.lastFrame
    }

    /** 手势入队：move/pan 只保留最新一条，discrete 手势最多排 [CANVAS_GESTURE_QUEUE] 个（串行消费） */
    fun enqueueCanvasGesture(loop: UiV3CanvasLoop, gesture: String, data: JSONObject) {
        val highFreq = gesture == "move" || gesture == "pan"
        if (highFreq) {
            val last = loop.gestureQueue.peekLast()
            if (last != null && (last.first == "move" || last.first == "pan")) {
                loop.gestureQueue.pollLast()
            }
        }
        if (loop.gestureQueue.size >= CANVAS_GESTURE_QUEUE) loop.gestureQueue.pollFirst()
        loop.gestureQueue.addLast(gesture to data)
    }

    /** 执行一条手势桥调用：canvas_gesture 优先，否则 page_action(node.action, data+gesture) 兜底 */
    suspend fun runCanvasGesture(
        loop: UiV3CanvasLoop,
        pageName: String,
        index: Int,
        gesture: String,
        data: JSONObject,
        stateSnapshot: JSONObject,
        useGestureFn: Boolean,
        fallbackAction: String
    ) {
        val gen = canvasRuntime.generation
        canvasRuntime.activeCalls++
        val r: PyResult = try {
            if (useGestureFn) {
                callPy(
                    UiV2Protocol.ENTRY_V3_CANVAS_GESTURE,
                    UiV2Protocol.v3CanvasGesturePayload(pageName, loop.viewId, gesture, data, stateSnapshot)
                )
            } else {
                val params = copyGestureParams(data, gesture)
                callPy(
                    UiV2Protocol.ENTRY_V3_ACTION,
                    UiV2Protocol.v3ActionPayload(
                        pageName,
                        fallbackAction,
                        mergeParams(stateSnapshot, params),
                        stateSnapshot
                    )
                )
            }
        } finally {
            if (canvasRuntime.generation == gen) {
                canvasRuntime.activeCalls = (canvasRuntime.activeCalls - 1).coerceAtLeast(0)
            }
        }
        if (!r.ok) {
            // 失败只丢弃该手势，不崩界面（规范 §5）
            Log.w(
                TAG,
                "canvas_gesture failed tool=$toolId view=${loop.viewId} gesture=$gesture " +
                    "code=${r.errorCode} msg=${r.errorMessage}"
            )
            return
        }
        val result = UiV2Protocol.parseObject(r.output)
        if (result == null) {
            Log.d(TAG, "canvas_gesture returned no result tool=$toolId view=${loop.viewId} gesture=$gesture")
            return
        }
        val err = UiV2Protocol.errorOf(result)
        if (err.isNotBlank()) {
            Log.w(TAG, "canvas_gesture reported error tool=$toolId view=${loop.viewId}: $err")
            return
        }
        // 与 page_action 完全同语义：state 合并 / body 替换 / navigate / back / toast
        applyActionResult(index, pageName, result)
    }

    /**
     * 手势入口（渲染器已按 node.gestures 过滤，坐标单位 canvas dp）：
     * - 声明了 `canvas_gesture` → 调它，返回值与 page_action 同语义（state/body/navigate/back/toast）；
     * - 否则若 canvas 节点有 `action` → 退化为 `page_action(page, action, data+gesture, state)`（规范 §1）；
     * - 都没有 → 忽略。手势**串行**消费：discrete 手势排队（tap 跟在 up 后面不会丢），move/pan 只留最新。
     */
    fun onCanvasGesture(viewId: String, gesture: String, data: JSONObject) {
        if (viewId.isBlank() || gesture.isBlank()) return
        val entry = stack.lastOrNull() ?: return
        if (entry.loading) return
        val pageName = entry.page
        val node = findCanvasNode(currentPageBody(entry), viewId) ?: return
        if (node.gestures.isNotEmpty() && gesture !in node.gestures) {
            Log.d(TAG, "canvas gesture filtered tool=$toolId view=$viewId gesture=$gesture")
            return
        }
        val useGestureFn = hasCanvasGesture
        val fallbackAction = node.action
        if (!useGestureFn && (fallbackAction.isBlank() || !hasPageAction)) {
            Log.d(TAG, "canvas gesture ignored (no canvas_gesture/action) tool=$toolId view=$viewId gesture=$gesture")
            return
        }
        val loop = canvasLoop(pageName, viewId)
        enqueueCanvasGesture(loop, gesture, data)
        if (loop.gestureInFlight) {
            Log.d(TAG, "canvas gesture queued tool=$toolId view=$viewId gesture=$gesture size=${loop.gestureQueue.size}")
            return
        }
        val index = stack.lastIndex
        loop.gestureInFlight = true
        Log.d(TAG, "canvas gesture tool=$toolId page=$pageName view=$viewId gesture=$gesture")
        loop.gestureJob = scope.launch {
            try {
                while (true) {
                    val item = loop.gestureQueue.pollFirst() ?: break
                    val cur = stack.getOrNull(index) ?: break
                    // 页面被切走/被 pop：剩余手势直接丢弃（不再打扰旧页）
                    if (cur.page != pageName || index != stack.lastIndex) break
                    // body 可能被上一次手势替换：每一条都重新找 canvas 节点拿最新 action
                    val itemNode = findCanvasNode(currentPageBody(cur), viewId) ?: continue
                    runCanvasGesture(
                        loop = loop,
                        pageName = pageName,
                        index = index,
                        gesture = item.first,
                        data = item.second,
                        stateSnapshot = cur.state,
                        useGestureFn = useGestureFn,
                        fallbackAction = itemNode.action
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "canvas_gesture crashed tool=$toolId view=$viewId", t)
            } finally {
                loop.gestureQueue.clear()
                loop.gestureInFlight = false
            }
        }
    }

    /** tabBar 切换 = 清栈到该页的根（规范 §1） */
    fun switchTab(page: String) {
        if (page.isBlank()) return
        if (stack.size == 1 && stack.firstOrNull()?.page == page) return
        scope.launch {
            val base = withContext(Dispatchers.IO) { UiV3Store.readPage(ctx, toolId, page) }
            if (base == null) {
                Log.w(TAG, "tab page missing tool=$toolId page=$page")
                toast(L.format(L.tools.uiOpenFailed, page))
                return@launch
            }
            stack.clear()
            val rawPage = withContext(Dispatchers.IO) {
                readRawUiJson(File(UiV3Store.pagesDir(ctx, toolId), "$page.json"))
            }
            stack.add(UiV3PageEntry(page = page, base = base, loading = hasPageLoad, raw = rawPage))
            Log.i(TAG, "switch-tab tool=$toolId page=$page")
            cancelCanvasLoops() // 切 tab = 换页：上一页 canvas 循环立即取消
            if (hasPageLoad) runLoad(0)
        }
    }

    // 打开宿主即进入 manifest.entry 根页：page_load(entry, {}, {})
    LaunchedEffect(toolId) {
        booting = true
        fatal = null
        stack.clear()
        val entryPage = manifest.entry
        // 原始 JSON：背景 cover / nav / tabStyle 等外观配置（规范 canvas §9，模型未建模也读得到）
        manifestRaw = withContext(Dispatchers.IO) {
            readRawUiJson(File(UiV3Store.uiDir(ctx, toolId), "manifest.json"))
        }
        val base = withContext(Dispatchers.IO) { UiV3Store.readPage(ctx, toolId, entryPage) }
        if (base == null) {
            Log.w(TAG, "entry page missing/invalid tool=$toolId page=$entryPage")
            fatal = L.format(L.tools.uiOpenFailed, entryPage)
        } else {
            // 先静态渲染（page_load 未返回前也能看到页面），后台 load 回来再替换 body/state
            val rawPage = withContext(Dispatchers.IO) {
                readRawUiJson(File(UiV3Store.pagesDir(ctx, toolId), "$entryPage.json"))
            }
            stack.add(
                UiV3PageEntry(page = entryPage, base = base, loading = hasPageLoad, raw = rawPage)
            )
        }
        booting = false
        if (base != null && hasPageLoad) runLoad(0)
    }

    // 系统返回键：栈深 >1 先 pop；根页交给外层（MainActivity 会 pop Screen.ToolUi → onBack）
    BackHandler(enabled = stack.size > 1) { goBack() }

    // ---- P3 canvas 生命周期（规范 canvas §1/§5）：页面不可见 / App 退后台暂停帧循环，回前台恢复；
    // 返回键/切页已由 goBack/cancelCanvasLoops 立即取消。lastFrame 与降级状态保留（回前台不丢）。 ----
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, toolId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumeCanvasLoops()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> pauseCanvasLoops()
                Lifecycle.Event.ON_DESTROY -> {
                    pauseCanvasLoops()
                    cancelCanvasLoops()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 首次组合时按当前 lifecycle 状态决定是否暂停（多数情况已是 RESUMED）
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumeCanvasLoops()
        } else {
            pauseCanvasLoops()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pauseCanvasLoops()
            cancelCanvasLoops()
        }
    }

    val entry = stack.lastOrNull()
    val tabPages = manifest.tab.map { it.page }
    val selectedTab = entry?.page?.takeIf { it in tabPages }
        ?: stack.firstOrNull()?.page?.takeIf { it in tabPages }
        ?: manifest.entry

    // chrome 配置（规范 canvas §9/§10）：宿主**不再渲染任何顶栏**（无标题/无返回箭头），
    // 页面 JSON 的 nav 字段仍解析保留，但按 §10 默认不渲染；标题/返回/导航全部由模型页面决定。
    val chrome = remember(entry?.raw, manifestRaw) { parseChrome(entry?.raw, manifestRaw) }
    val basePage: UiPage? = entry?.base
    // 页面没写 background 时用 theme.bg 兜底，保证背景仍然铺满（UiV3Background 自己处理图片/渐变/动画）
    val pageBackground: UiBackground? = basePage?.let { p ->
        p.background ?: p.theme.bg.takeIf { it.isNotBlank() }?.let { bg ->
            UiBackground(type = "color", value = bg)
        }
    }
    val appDark = c.bg.luminance() < 0.5f
    val darkChrome = isDarkChrome(basePage, appDark)
    val showTabs = manifest.tab.size in 2..5 && entry != null && !chrome.tabsHidden
    // tabBar：glass（默认半透明）/ solid（实心）/ none（不显示，已从 showTabs 排除）
    val tabBarSolid = chrome.tabStyle == "solid"
    val tabTint = parseChromeColor(chrome.tabTint)
    val tabBarBg = when {
        tabBarSolid -> parseChromeColor(basePage?.theme?.card.orEmpty()) ?: c.bg2
        darkChrome -> Color(0xB3101626)
        else -> Color(0xD9FFFFFF)
    }
    val tabBarDivider = if (darkChrome) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val tabActive = tabTint ?: if (darkChrome) Color.White else c.brand
    val tabInactive = (tabTint ?: (if (darkChrome) Color.White else c.text3)).copy(alpha = 0.55f)
    // × 关闭按钮：深色背景 → #66000000 + 白 ×；浅色 → #66FFFFFF + 深色 ×（规范 §10）
    val closeBg = if (darkChrome) Color(0x66000000) else Color(0x66FFFFFF)
    val closeTint = if (darkChrome) Color.White else Color(0xE6000000)
    val errorText = if (darkChrome) Color.White else c.danger
    val busyText = if (darkChrome) Color.White.copy(alpha = 0.72f) else c.text3

    Box(Modifier.fillMaxSize().background(if (chrome.coverContent) c.bg else Color.Transparent)) {
        // 1) 背景：默认 cover=screen 铺满整屏（状态栏下方/屏幕底部/所有浮层下面）
        if (!chrome.coverContent) {
            UiV3Background(
                background = pageBackground,
                modifier = Modifier.fillMaxSize(),
                onResolveImage = imageResolver,
                scrollOffset = { 0f } // parallax 需要滚动偏移，首版传静止 0
            )
        }

        // 2) body：全高、透明底（drawPageBackground=false，背景由宿主统一画；无任何栏位 inset）
        Box(Modifier.fillMaxSize()) {
            if (chrome.coverContent) {
                // cover=content：退回旧行为，背景只画在页面内容区域（不铺到栏位/系统栏下）
                UiV3Background(
                    background = pageBackground,
                    modifier = Modifier.fillMaxSize(),
                    onResolveImage = imageResolver,
                    scrollOffset = { 0f }
                )
            }
            val cur = entry
            val f = fatal
            when {
                f != null -> Text(
                    f,
                    fontSize = 12.5.sp,
                    color = errorText,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 64.dp, end = 16.dp, bottom = 16.dp)
                )

                booting || cur == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(L.tools.uiWorking, fontSize = 12.sp, color = busyText)
                }

                else -> {
                    val base = cur.base
                    if (base == null) {
                        Text(
                            L.format(L.tools.uiOpenFailed, cur.page),
                            fontSize = 12.5.sp,
                            color = errorText,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 64.dp, end = 16.dp, bottom = 16.dp)
                        )
                    } else {
                        val dynamicBody = cur.body
                        val page = if (dynamicBody != null) base.copy(body = dynamicBody) else base
                        // 内容避开状态栏（背景仍铺满全屏）；tabBar 不占位，页面底部自己留白
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                            RenderPage(
                                page = page,
                                state = cur.state,
                                onAction = { action, params -> onAction(action, params) },
                                onStateChange = { key, value ->
                                    onStateChangeInternal(stack, key, value)
                                },
                                onResolveImage = imageResolver,
                                onCanvasFrame = { viewId, tMs, dtMs, w, h ->
                                    onCanvasFrame(viewId, tMs, dtMs, w, h)
                                },
                                onCanvasGesture = { viewId, gesture, data ->
                                    onCanvasGesture(viewId, gesture, data)
                                }
                            )
                        }
                    }
                }
            }
        }

        // 3) tabBar：浮在底部（manifest.tab 3–5 项 + tabStyle 非 none 才显示）
        if (showTabs) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                UiV3TabBar(
                    tabs = manifest.tab,
                    selected = selectedTab,
                    onSelect = { switchTab(it) },
                    background = tabBarBg,
                    divider = tabBarDivider,
                    active = tabActive,
                    inactive = tabInactive
                )
            }
        }

        // 4) × 关闭按钮：右上角（状态栏下 12dp），始终显示、模型不可关；点击直接退出工具页
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(closeBg)
                .clickable { exitHost() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = L.common.close,
                tint = closeTint,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 读 UI v3 原始 JSON（坏文件/不存在返回 null；只在 IO 线程调用） */
private fun readRawUiJson(file: File): JSONObject? =
    runCatching { if (file.isFile) JSONObject(file.readText()) else null }.getOrNull()

/**
 * 解析页面/ manifest 的 chrome 配置（规范 canvas §9）。
 * 页面 JSON：`background.cover`、`nav{show,style,title,back,tint}`；
 * manifest：`tabStyle{style,tint,icons}`。缺失/非法都回落到默认值，宿主不写死观感。
 */
private fun parseChrome(pageRaw: JSONObject?, manifestRaw: JSONObject?): UiChromeConfig {
    val bg = pageRaw?.optJSONObject("background")
    val nav = pageRaw?.optJSONObject("nav")
    val tab = manifestRaw?.optJSONObject("tabStyle")
    val navStyle = nav?.optString("style").orEmpty().trim().lowercase()
        .takeIf { it in setOf("auto", "transparent", "solid", "none") } ?: "auto"
    val tabStyle = tab?.optString("style").orEmpty().trim().lowercase()
        .takeIf { it in setOf("glass", "solid", "none") } ?: "glass"
    return UiChromeConfig(
        backgroundCover = if (bg?.optString("cover").orEmpty().trim().equals("content", true)) {
            "content"
        } else {
            "screen"
        },
        navShow = nav?.optBoolean("show", true) ?: true,
        navStyle = navStyle,
        navTitle = nav?.optString("title").orEmpty().trim(),
        navBack = nav?.optBoolean("back", true) ?: true,
        navTint = nav?.optString("tint").orEmpty().trim(),
        tabStyle = tabStyle,
        tabTint = tab?.optString("tint").orEmpty().trim(),
        tabIcons = tab?.optBoolean("icons", false) ?: false
    )
}

/** `#RRGGBB` / `#AARRGGBB` → Color；非法返回 null（调用方走自动） */
private fun parseChromeColor(raw: String): Color? = runCatching {
    val s = raw.trim()
    if (!s.startsWith("#")) return null
    val hex = s.removePrefix("#")
    val value = hex.toLong(16)
    when (hex.length) {
        6 -> Color(0xFF000000L or value)
        8 -> Color(value)
        else -> null
    }
}.getOrNull()

/** `#RRGGBB` 相对亮度（0 黑 ~ 1 白）；解析失败返回 null */
private fun hexLuminance(raw: String): Float? = runCatching {
    val color = android.graphics.Color.parseColor(raw.trim())
    val r = android.graphics.Color.red(color) / 255f
    val g = android.graphics.Color.green(color) / 255f
    val b = android.graphics.Color.blue(color) / 255f
    0.2126f * r + 0.7152f * g + 0.0722f * b
}.getOrNull()

/**
 * 自动明暗（`nav.style=auto` / 未给 tint 时）：
 * theme.dark 或 图片/渐变/深色纯色 → 白字（深色时顶栏加 scrim）；浅色 → 主题 text1。
 * 图片无法预判，一律按深色处理（scrim 保证可读）。
 */
private fun isDarkChrome(page: UiPage?, fallbackDark: Boolean): Boolean {
    if (page == null) return fallbackDark
    if (page.theme.dark) return true
    val bg = page.background
    if (bg == null) {
        // 页面没写 background：宿主会用 theme.bg 兜底铺底，明暗也按 theme.bg 判断
        return hexLuminance(page.theme.bg)?.let { it < 0.55 } ?: fallbackDark
    }
    return when (bg.type.lowercase()) {
        "image" -> true
        "gradient" -> {
            val lums = bg.stops.mapNotNull { (color, _) -> hexLuminance(color) }
            if (lums.isEmpty()) true else lums.average() < 0.55
        }
        "color" -> hexLuminance(bg.value)?.let { it < 0.55 } ?: fallbackDark
        else -> fallbackDark
    }
}

/** 控件值写回当前页 state：只存 UI 层内存，下一次动作时整份回传给 page_action */
private fun onStateChangeInternal(
    stack: MutableList<UiV3PageEntry>,
    key: String,
    value: Any?
) {
    if (key.isBlank()) return
    val index = stack.lastIndex
    if (index < 0) return
    val cur = stack[index]
    val next = JSONObject(cur.state.toString())
    if (value == null || value === JSONObject.NULL) {
        next.remove(key)
    } else {
        // org.json 对 Float 的序列化不稳，统一转 Double；Boolean/String 原样
        next.put(key, if (value is Float) value.toDouble() else value)
    }
    stack[index] = cur.copy(state = next)
}

/**
 * 渲染器调用点（ui-render-worker 的 `UiV3Renderer.kt`）。
 * 单独包一层：渲染器最终签名若有微调只改这里，不散落到宿主逻辑里。
 * 背景由宿主 [UiV3Background] 统一画，这里 `drawPageBackground=false` 只渲染 body。
 */
@Composable
private fun RenderPage(
    page: UiPage,
    state: JSONObject,
    onAction: (String, JSONObject) -> Unit,
    onStateChange: (String, Any?) -> Unit,
    onResolveImage: (String) -> String?,
    onCanvasFrame: (String, Long, Long, Float, Float) -> CanvasFrame?,
    onCanvasGesture: (String, String, JSONObject) -> Unit
) {
    UiV3Page(
        page = page,
        state = state,
        onAction = onAction,
        onStateChange = onStateChange,
        onResolveImage = onResolveImage,
        onCanvasFrame = onCanvasFrame,
        onCanvasGesture = onCanvasGesture,
        scrollOffset = { 0f }, // 首版宿主没有滚动偏移来源；parallax 预设停在原位
        drawPageBackground = false,
        modifier = Modifier.fillMaxSize()
    )
}

/** v3 底部 tabBar：浮层（glass/solid 由宿主按 tabStyle 决定底色与文字色）；选中项下方短横线 */
@Composable
private fun UiV3TabBar(
    tabs: List<UiTab>,
    selected: String,
    onSelect: (String) -> Unit,
    background: Color,
    divider: Color,
    active: Color,
    inactive: Color
) {
    Column(Modifier.fillMaxWidth().background(background).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            tabs.forEach { tab ->
                val isActive = tab.page == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab.page) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        tab.text.ifBlank { tab.page },
                        fontSize = 11.5.sp,
                        color = if (isActive) active else inactive,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .width(16.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (isActive) active else Color.Transparent)
                    )
                }
            }
        }
    }
}
