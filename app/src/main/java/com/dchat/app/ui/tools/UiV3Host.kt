package com.dchat.app.ui.tools

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.tools.PyResult
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.data.tools.ToolRegistry
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.Dispatchers
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
    val loading: Boolean = false
)

/**
 * UI Schema v3 多页宿主（纯原生 Compose，无 WebView）。
 *
 * 由 [ToolUiScreen] 在「存在且校验通过 `ui/manifest.json`」时调用（版本分发在 ToolUiScreen）。
 * 职责：
 * 1. 页面栈（`mutableStateListOf<UiV3PageEntry>`）：navigate push / back pop / 根页返回退出宿主；
 * 2. manifest.tab 3–5 项 → 底部 tabBar（切换 = 清栈到该页的根）；
 * 3. 顶栏标题（页面 title 或 manifest.title）+ 返回箭头；
 * 4. 事件桥：`page_load/page_action` 走 [PyRuntime.runCode]，适配器由 [UiV2Protocol] 追加；
 * 5. 图片 src 统一用 [UiV3Store.resolveSrc] 解析成绝对路径交给渲染器。
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
    /** manifest.title 为空时的兜底标题（工具显示名） */
    fallbackTitle: String,
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

    val adaptedCode = remember(code) { UiV2Protocol.adaptedCodeV3(code) }
    val hasPageLoad = remember(code) { UiV2Protocol.hasPageLoad(code) }
    val hasPageAction = remember(code) { UiV2Protocol.hasPageAction(code) }
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
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit()
    }

    /** 按 index 替换栈项（index 失效时忽略，避免异步结果越界） */
    fun replaceAt(index: Int, next: (UiV3PageEntry) -> UiV3PageEntry) {
        if (index in stack.indices) stack[index] = next(stack[index])
    }

    /** 所有 Python 调用串行：同一工具共用一把 Mutex（entry_preview 临时文件不并发写） */
    suspend fun callPy(entryFunction: String, payload: JSONObject): PyResult = pyMutex.withLock {
        PyRuntime.runCode(
            context = ctx,
            toolId = toolId,
            entryFunction = entryFunction,
            code = adaptedCode,
            argsJson = payload.toString(),
            conversationId = stateId,
            timeoutMs = timeoutMs,
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
        stack.add(
            UiV3PageEntry(
                page = page,
                args = args,
                state = initial,
                base = base,
                loading = hasPageLoad
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
            stack.add(UiV3PageEntry(page = page, base = base, loading = hasPageLoad))
            Log.i(TAG, "switch-tab tool=$toolId page=$page")
            if (hasPageLoad) runLoad(0)
        }
    }

    // 打开宿主即进入 manifest.entry 根页：page_load(entry, {}, {})
    LaunchedEffect(toolId) {
        booting = true
        fatal = null
        stack.clear()
        val entryPage = manifest.entry
        val base = withContext(Dispatchers.IO) { UiV3Store.readPage(ctx, toolId, entryPage) }
        if (base == null) {
            Log.w(TAG, "entry page missing/invalid tool=$toolId page=$entryPage")
            fatal = L.format(L.tools.uiOpenFailed, entryPage)
        } else {
            // 先静态渲染（page_load 未返回前也能看到页面），后台 load 回来再替换 body/state
            stack.add(UiV3PageEntry(page = entryPage, base = base, loading = hasPageLoad))
        }
        booting = false
        if (base != null && hasPageLoad) runLoad(0)
    }

    // 系统返回键：栈深 >1 先 pop；根页交给外层（MainActivity 会 pop Screen.ToolUi → onBack）
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

    val entry = stack.lastOrNull()
    val screenTitle = entry?.title?.takeIf { it.isNotBlank() }
        ?: entry?.base?.title?.takeIf { it.isNotBlank() }
        ?: manifest.title.takeIf { it.isNotBlank() }
        ?: fallbackTitle
    val tabPages = manifest.tab.map { it.page }
    val selectedTab = entry?.page?.takeIf { it in tabPages }
        ?: stack.firstOrNull()?.page?.takeIf { it in tabPages }
        ?: manifest.entry

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶栏：返回（栈深>1 pop，否则退出宿主）+ 页面/工具标题 + 忙碌提示
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Rounded.ArrowBackIosNew, { goBack() }, iconSize = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                screenTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (actionBusy || entry?.loading == true) {
                Text(
                    L.tools.uiWorking,
                    fontSize = 10.5.sp,
                    color = c.text3,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
        }

        // 页面内容：body/背景/滚动全部交给渲染器；宿主只兜底错误页（规范 §4：不崩 + 有返回）
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val cur = entry
            val f = fatal
            when {
                f != null -> Text(
                    f,
                    fontSize = 12.5.sp,
                    color = c.danger,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(16.dp)
                )

                booting || cur == null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(L.tools.uiWorking, fontSize = 12.sp, color = c.text3)
                }

                else -> {
                    val base = cur.base
                    if (base == null) {
                        Text(
                            L.format(L.tools.uiOpenFailed, cur.page),
                            fontSize = 12.5.sp,
                            color = c.danger,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        val dynamicBody = cur.body
                        val page = if (dynamicBody != null) base.copy(body = dynamicBody) else base
                        RenderPage(
                            page = page,
                            state = cur.state,
                            onAction = { action, params -> onAction(action, params) },
                            onStateChange = { key, value -> onStateChangeInternal(stack, key, value) },
                            onResolveImage = imageResolver
                        )
                    }
                }
            }
        }

        // tabBar：manifest.tab 3–5 项时显示在底部（首版只显示文字，icon 由渲染器/后续补）
        if (manifest.tab.size in 3..5 && entry != null) {
            UiV3TabBar(
                tabs = manifest.tab,
                selected = selectedTab,
                onSelect = { switchTab(it) }
            )
        }
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
 */
@Composable
private fun RenderPage(
    page: UiPage,
    state: JSONObject,
    onAction: (String, JSONObject) -> Unit,
    onStateChange: (String, Any?) -> Unit,
    onResolveImage: (String) -> String?
) {
    UiV3Page(
        page = page,
        state = state,
        onAction = onAction,
        onStateChange = onStateChange,
        onResolveImage = onResolveImage,
        modifier = Modifier.fillMaxSize()
    )
}

/** v3 底部 tabBar：3–5 项，等宽；选中项品牌色 + 下方短横线 */
@Composable
private fun UiV3TabBar(
    tabs: List<UiTab>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.line2))
        Row(Modifier.fillMaxWidth().height(54.dp).background(c.bg2)) {
            tabs.forEach { tab ->
                val active = tab.page == selected
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
                        color = if (active) c.brand else c.text3,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        Modifier
                            .padding(top = 3.dp)
                            .width(16.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(if (active) c.brand else Color.Transparent)
                    )
                }
            }
        }
    }
}
