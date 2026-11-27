package com.dchat.app.ui.tools

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.ui.CompactSwitch
import com.dchat.app.ui.theme.DarkColors
import com.dchat.app.ui.theme.LocalColors
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 工具 UI v3 的 Compose 渲染器（规范见 `docs/spec/tool-ui-v3.md`）。
 *
 * 设计要点：
 * - 只消费 [UiPage]/[UiNode] 数据模型，不解析 JSON 文件；`state` 只用于绑定求值。
 * - 绑定 `{{state.path}}` 在 [remember] 里一次算完（见 [resolvePage]），渲染每帧不再做字符串替换。
 * - 未知节点/渲染异常显示灰色虚线占位，不崩；整个页面构建失败时显示错误页。
 * - 图片由宿主通过 [onResolveImage] 解析成绝对路径，渲染器负责 IO 线程采样解码 + LruCache。
 * - 全部原生 Compose，无 WebView/H5。
 */
@Composable
fun UiV3Page(
    page: UiPage,
    state: JSONObject,
    onAction: (action: String, params: JSONObject) -> Unit,
    onStateChange: (key: String, value: Any?) -> Unit,
    onResolveImage: ((String) -> String?)? = null,
    modifier: Modifier = Modifier
) {
    val appColors = LocalColors.current
    // 规范 2.2：dark 首版只影响页面默认色选择，不切换全局暗色主题
    val baseColors = if (page.theme.dark) DarkColors else appColors
    val colors = remember(page.theme, baseColors) { uiV3Colors(page.theme, baseColors) }
    val fontScale = remember(page.theme) { uiV3FontScale(page.theme) }

    // 绑定一次性求值：state 内容变了才重建渲染模型。
    // 宿主每次动作都会传新的 JSONObject；原地 mutate 也能通过 toString 变化感知。
    val stateKey = state.toString()
    val resolved = remember(page, stateKey) { resolvePage(page, state) }

    val ctx = V3Ctx(
        colors = colors,
        fontScale = fontScale,
        state = state,
        onAction = onAction,
        onStateChange = onStateChange,
        onResolveImage = onResolveImage
    )

    Box(modifier = modifier.fillMaxSize()) {
        PageBackground(resolved.background, colors, onResolveImage)

        val body = resolved.body
        if (body == null) {
            PageError(resolved.error ?: "未知错误", colors)
        } else if (body.type == "scroll") {
            // 页面 body 自身就是滚动容器时，把宿主的约束直接给它
            RenderNode(body, ctx, Modifier.fillMaxSize())
        } else {
            // body 默认就是 scroll 语义（规范 2.3）
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                RenderNode(body, ctx)
            }
        }
    }
}

/* ==================== 页面背景 ==================== */

@Composable
private fun PageBackground(
    background: UiBackground?,
    colors: UiV3Colors,
    onResolveImage: ((String) -> String?)?
) {
    if (background == null) {
        Box(Modifier.fillMaxSize().background(colors.bg))
        return
    }
    when (background.type.trim().lowercase()) {
        "gradient" -> Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(uiV3GradientBrush(background, size, colors.bg)) }
        )

        "image" -> {
            val path = remember(background.src, onResolveImage) {
                resolveUiImagePath(background.src, onResolveImage)
            }
            val bitmap = rememberUiBitmap(path, 2048)
            Box(Modifier.fillMaxSize().background(colors.bg)) {
                if (bitmap != null) {
                    // Android 12+ 用 RenderEffect 模糊；低版本忽略模糊只保留 scrim（规范 2.1）
                    val blurModifier = if (Build.VERSION.SDK_INT >= 31 && background.blur > 0) {
                        Modifier.blur(background.blur.coerceIn(0, 24).toFloat().dp)
                    } else {
                        Modifier
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = if (background.fit.trim().equals("contain", true)) {
                            ContentScale.Fit
                        } else {
                            ContentScale.Crop
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .alpha(background.opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f)
                            .then(blurModifier)
                    )
                }
                val scrim = background.scrim.takeIf { it.isFinite() }?.coerceIn(0f, 0.8f) ?: 0f
                if (scrim > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = scrim))
                    )
                }
            }
        }

        else -> Box(Modifier.fillMaxSize().background(parseHexColor(background.value) ?: colors.bg))
    }
}

/* ==================== 渲染模型：一次求值绑定 ==================== */

/** 渲染上下文：主题色 + 页面字号 + 宿主回调；inRow/inStack 影响默认尺寸语义。 */
private data class V3Ctx(
    val colors: UiV3Colors,
    val fontScale: Float,
    val state: JSONObject,
    val onAction: (String, JSONObject) -> Unit,
    val onStateChange: (String, Any?) -> Unit,
    val onResolveImage: ((String) -> String?)?,
    val inRow: Boolean = false,
    val inStack: Boolean = false
)

private class RPage(
    val body: RNode?,
    val background: UiBackground?,
    val error: String?
)

private class RNode(
    val node: UiNode,
    val path: String,
    val type: String,
    val visible: Boolean,
    val text: String,
    val placeholder: String,
    val src: String,
    val action: String,
    val params: JSONObject,
    val color: String,
    val value: String,
    val icon: String,
    val width: String,
    val background: UiBackground?,
    val children: List<RNode>,
    val broken: Boolean = false,
    val error: String? = null
)

private val CONTAINER_TYPES = setOf("column", "row", "stack", "scroll", "grid", "card")

/** 没写 width 时默认撑满的类型（在 Row 里除外）。 */
private val DEFAULT_FILL_TYPES = setOf(
    "column", "row", "stack", "scroll", "grid", "card",
    "button", "image", "divider", "progress", "slider", "input", "toggle"
)

private class BuildBudget {
    private var count = 0
    var overflow = false
        private set

    fun take(): Boolean {
        if (overflow) return false
        if (++count > 400) {
            overflow = true
            return false
        }
        return true
    }
}

private fun resolvePage(page: UiPage, state: JSONObject): RPage = runCatching {
    val budget = BuildBudget()
    val body = resolveNode(page.body, state, "b", 1, budget)
    val background = page.background?.let { bg ->
        bg.copy(value = evalText(bg.value, state), src = evalText(bg.src, state))
    }
    RPage(body, background, null)
}.getOrElse { e ->
    RPage(null, page.background, e.message ?: e.javaClass.simpleName)
}

private fun resolveNode(
    node: UiNode,
    state: JSONObject,
    path: String,
    depth: Int,
    budget: BuildBudget
): RNode {
    val type = node.type.trim().lowercase()
    val visible = evalVisible(node.visible, state)
    if (depth > 12 || !budget.take()) {
        return brokenRNode(node, path, "节点过多或嵌套过深", visible)
    }
    val children = if (visible && type in CONTAINER_TYPES) {
        node.children.mapIndexed { index, child ->
            // 单个子节点异常不拖垮兄弟节点
            runCatching { resolveNode(child, state, "$path.$index", depth + 1, budget) }
                .getOrElse { e -> brokenRNode(child, "$path.$index", e.message ?: e.javaClass.simpleName, true) }
        }
    } else {
        emptyList()
    }
    return runCatching {
        RNode(
            node = node,
            path = path,
            type = type,
            visible = visible,
            text = evalText(node.text, state),
            placeholder = evalText(node.placeholder, state),
            src = evalText(node.src, state),
            action = evalText(node.action, state),
            params = evalParams(node.params, state),
            color = evalText(node.color, state),
            value = evalText(node.value, state),
            icon = evalText(node.icon, state),
            width = evalText(node.width, state),
            background = node.background?.let { bg ->
                bg.copy(value = evalText(bg.value, state), src = evalText(bg.src, state))
            },
            children = children
        )
    }.getOrElse { e ->
        brokenRNode(node, path, e.message ?: e.javaClass.simpleName, visible)
    }
}

private fun brokenRNode(node: UiNode, path: String, message: String?, visible: Boolean): RNode = RNode(
    node = node,
    path = path,
    type = node.type.trim().lowercase(),
    visible = visible,
    text = "",
    placeholder = "",
    src = "",
    action = "",
    params = JSONObject(),
    color = "",
    value = "",
    icon = "",
    width = "",
    background = null,
    children = emptyList(),
    broken = true,
    error = message
)

/* ---------------- 绑定求值 ---------------- */

private val BIND_ANY = Regex("\\{\\{\\s*state\\.([A-Za-z0-9_.]+)\\s*\\}\\}")
private val BIND_FULL = Regex("^\\{\\{\\s*state\\.([A-Za-z0-9_.]+)\\s*\\}\\}$")

/** 规范 2.5：只做取值，不做表达式；缺省为空串。 */
private fun evalText(template: String, state: JSONObject): String {
    if (template.isEmpty() || !template.contains("{{")) return template
    return BIND_ANY.replace(template) { match ->
        valueText(stateValue(state, match.groupValues[1]))
    }
}

private fun evalVisible(raw: String, state: JSONObject): Boolean {
    val value = evalText(raw, state).trim().lowercase()
    // 空串按“未设置”处理（绑定缺省时不隐藏），只有显式假值才隐藏
    return value.isEmpty() || (value != "false" && value != "0" && value != "no" && value != "null")
}

private fun stateValue(state: JSONObject, path: String): Any? {
    var current: Any? = state
    for (segment in path.split('.')) {
        current = when (val node = current) {
            is JSONObject -> {
                val v = if (node.has(segment)) node.opt(segment) else null
                if (v == JSONObject.NULL) null else v
            }

            is JSONArray -> segment.toIntOrNull()
                ?.takeIf { it in 0 until node.length() }
                ?.let { node.opt(it) }

            else -> null
        }
        if (current == null) return null
    }
    return current
}

private fun valueText(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is Double -> if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    is Float -> if (value.isFinite() && value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    is String -> value
    is JSONObject, is JSONArray -> value.toString()
    else -> value.toString()
}

/** 动作参数里的字符串也支持绑定；整串是绑定且命中时保留原始类型（数字/布尔）。 */
private fun evalParams(source: JSONObject, state: JSONObject): JSONObject {
    val out = JSONObject()
    source.keys().forEach { key -> out.put(key, resolveParamValue(source.opt(key), state)) }
    return out
}

private fun resolveParamValue(value: Any?, state: JSONObject): Any? = when (value) {
    is JSONObject -> evalParams(value, state)
    is JSONArray -> {
        val arr = JSONArray()
        for (i in 0 until value.length()) {
            arr.put(resolveParamValue(value.opt(i), state))
        }
        arr
    }

    is String -> {
        val full = BIND_FULL.matchEntire(value.trim())
        if (full != null) stateValue(state, full.groupValues[1]) ?: JSONObject.NULL else evalText(value, state)
    }

    else -> value
}

/* ==================== 节点渲染 ==================== */

@Composable
private fun RenderNode(rn: RNode, ctx: V3Ctx, modifier: Modifier = Modifier) {
    if (!rn.visible) return
    val colors = ctx.colors
    if (rn.broken || rn.node.unsupported) {
        Placeholder(rn.type, rn.error, colors, modifier.padding(uiV3Padding(rn.node.margin)))
        return
    }

    val radius = when {
        rn.node.radius > 0 -> rn.node.radius.coerceIn(0, 64).dp
        rn.type in CONTAINER_TYPES || rn.type == "button" -> colors.radius
        else -> 0.dp
    }
    val shape = RoundedCornerShape(radius)

    var m = modifier.padding(uiV3Padding(rn.node.margin))
    m = m.then(sizeModifier(rn, ctx))
    if (rn.node.height > 0) m = m.height(rn.node.height.coerceIn(0, 4000).dp)
    if (rn.node.shadow > 0) m = m.shadow(rn.node.shadow.coerceIn(0, 24).dp, shape, clip = false)

    val nodeBackground = rn.background
    val hasChrome = nodeBackground != null || rn.node.border != null || radius > 0.dp
    if (hasChrome) m = m.clip(shape)
    if (rn.type == "card" && nodeBackground == null) m = m.background(colors.card, shape)
    if (nodeBackground != null) m = m.uiV3NodeBackground(nodeBackground, ctx)

    if (rn.type == "button") {
        val style = rn.node.style.trim().lowercase()
        val accent = parseHexColor(rn.color) ?: colors.primary
        if (nodeBackground == null && style != "outline" && style != "ghost") {
            m = m.background(accent, shape)
        }
        if (style == "outline" && rn.node.border == null) {
            m = m.border(1.dp, accent, shape)
        }
    }
    rn.node.border?.let { border ->
        if (border.width > 0) {
            m = m.border(
                border.width.coerceIn(1, 8).dp,
                parseHexColor(border.color) ?: colors.line,
                shape
            )
        }
    }

    // stack 子层铺满：常见用法是背景图层/遮罩层
    if (ctx.inStack && rn.node.height <= 0 && rn.width.trim().equals("fill", true)) {
        m = m.fillMaxSize()
    }

    val pad = uiV3Padding(rn.node.padding)
    when (rn.type) {
        "column", "card", "row", "stack", "scroll", "grid" -> ContainerNode(rn, ctx, m)
        "spacer" -> Spacer(spacerModifier(rn, m))
        "text" -> TextNode(rn, ctx, m, pad)
        "button" -> ButtonNode(rn, ctx, m, pad)
        "image" -> ImageNode(rn, ctx, m, pad)
        "icon" -> IconNode(rn, ctx, m, pad)
        "divider" -> DividerNode(rn, ctx, m, pad)
        "progress" -> ProgressNode(rn, ctx, m, pad)
        "badge" -> BadgeNode(rn, ctx, m, pad)
        "input" -> InputNode(rn, ctx, m, pad)
        "toggle" -> ToggleNode(rn, ctx, m, pad)
        "slider" -> SliderNode(rn, ctx, m, pad)
        else -> Placeholder(rn.type, null, colors, modifier)
    }
}

/** 节点通用背景：纯色 / 渐变 / 图片（图片走统一解码缓存）。 */
@Composable
private fun Modifier.uiV3NodeBackground(background: UiBackground, ctx: V3Ctx): Modifier {
    val colors = ctx.colors
    return when (background.type.trim().lowercase()) {
        "gradient" -> this.drawBehind { drawRect(uiV3GradientBrush(background, size, colors.bg)) }

        "image" -> {
            val path = remember(background.src, ctx.onResolveImage) {
                resolveUiImagePath(background.src, ctx.onResolveImage)
            }
            val bitmap = rememberUiBitmap(path, 2048)
            if (bitmap == null) {
                this.background(parseHexColor(background.value) ?: colors.bg)
            } else {
                this.drawBehind {
                    drawUiImage(bitmap, background.fit, background.opacity, background.scrim)
                }
            }
        }

        else -> this.background(parseHexColor(background.value) ?: colors.bg)
    }
}

/* ---------------- 布局容器 ---------------- */

@Composable
private fun ContainerNode(rn: RNode, ctx: V3Ctx, m: Modifier) {
    val isCard = rn.type == "card"
    val gap = when {
        rn.node.gap > 0 -> uiV3Gap(rn.node.gap)
        isCard -> 8.dp
        else -> 0.dp
    }
    val pad = if (isCard && rn.node.padding.isEmpty()) PaddingValues(14.dp) else uiV3Padding(rn.node.padding)
    val verticalArrangement: Arrangement.Vertical =
        if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Top

    when (rn.type) {
        "row" -> Row(
            modifier = m.padding(pad),
            horizontalArrangement = rowArrangement(rn.node.justify, gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            rn.children.forEach { child ->
                if (!child.visible) return@forEach
                val fill = child.width.trim().equals("fill", true)
                val vertical = when (child.node.align.trim().lowercase()) {
                    "start" -> Alignment.Top
                    "center" -> Alignment.CenterVertically
                    "end" -> Alignment.Bottom
                    else -> null
                }
                when {
                    fill && vertical != null -> Box(Modifier.weight(1f).align(vertical)) {
                        RenderNode(child, ctx.copy(inRow = true))
                    }

                    fill -> RenderNode(child, ctx.copy(inRow = true), Modifier.weight(1f))
                    vertical != null -> Box(Modifier.align(vertical)) {
                        RenderNode(child, ctx.copy(inRow = true))
                    }

                    else -> RenderNode(child, ctx.copy(inRow = true))
                }
            }
        }

        "stack" -> Box(modifier = m.padding(pad)) {
            rn.children.forEach { child ->
                if (!child.visible) return@forEach
                val align = when (child.node.align.trim().lowercase()) {
                    "center" -> Alignment.Center
                    "end" -> Alignment.CenterEnd
                    "bottom" -> Alignment.BottomCenter
                    else -> Alignment.TopStart
                }
                Box(Modifier.align(align)) {
                    RenderNode(child, ctx.copy(inStack = true))
                }
            }
        }

        "scroll" -> Column(
            modifier = m.padding(pad).verticalScroll(rememberScrollState()),
            verticalArrangement = verticalArrangement
        ) {
            rn.children.forEach { child -> ColumnChild(child, ctx) }
        }

        "grid" -> {
            val columns = rn.node.columns.coerceIn(2, 6)
            val rows = rn.children.filter { it.visible }.chunked(columns)
            Column(modifier = m.padding(pad), verticalArrangement = verticalArrangement) {
                rows.forEach { cells ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Start,
                        verticalAlignment = Alignment.Top
                    ) {
                        cells.forEach { child ->
                            // 网格单元自身要撑满列宽，所以不能用 inRow 语义
                            Box(Modifier.weight(1f)) {
                                RenderNode(child, ctx)
                            }
                        }
                        repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        else -> Column(modifier = m.padding(pad), verticalArrangement = verticalArrangement) {
            rn.children.forEach { child -> ColumnChild(child, ctx) }
        }
    }
}

@Composable
private fun ColumnChild(child: RNode, ctx: V3Ctx) {
    if (!child.visible) return
    val boxAlign = when (child.node.align.trim().lowercase()) {
        "center" -> Alignment.TopCenter
        "end" -> Alignment.TopEnd
        else -> null
    }
    if (boxAlign != null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = boxAlign) {
            RenderNode(child, ctx)
        }
    } else {
        RenderNode(child, ctx)
    }
}

/* ---------------- 基础组件 ---------------- */

@Composable
private fun TextNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val size = (rn.node.size.takeIf { it > 0 } ?: 16).coerceIn(6, 96)
    Text(
        text = rn.text,
        modifier = m.padding(pad),
        color = parseHexColor(rn.color) ?: colors.text1,
        fontSize = (size * ctx.fontScale).sp,
        fontWeight = fontWeightOf(rn.node.weight),
        textAlign = textAlignOf(rn.node.align) ?: TextAlign.Start,
        maxLines = rn.node.maxLines.takeIf { it > 0 } ?: Int.MAX_VALUE,
        overflow = if (rn.node.maxLines > 0) TextOverflow.Ellipsis else TextOverflow.Clip
    )
}

@Composable
private fun ButtonNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val style = rn.node.style.trim().lowercase()
    val accent = parseHexColor(rn.color) ?: colors.primary
    val fg = if (style == "outline" || style == "ghost") accent else Color.White
    val hasAction = rn.action.isNotBlank()

    Box(
        modifier = m
            .then(if (hasAction) Modifier.clickable { ctx.onAction(rn.action, copyParams(rn.params)) } else Modifier)
            .defaultMinSize(minHeight = 44.dp)
            .padding(pad)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rn.text,
            color = fg,
            fontSize = (15f * ctx.fontScale).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ImageNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val path = remember(rn.src, ctx.onResolveImage) { resolveUiImagePath(rn.src, ctx.onResolveImage) }
    val bitmap = rememberUiBitmap(path, 1024)
    val placeholderShape = RoundedCornerShape((if (rn.node.radius > 0) rn.node.radius else 8).dp)

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = if (rn.node.fit.trim().equals("contain", true)) ContentScale.Fit else ContentScale.Crop,
            modifier = m
                .padding(pad)
                .then(if (rn.node.height <= 0) Modifier.heightIn(max = 480.dp) else Modifier)
        )
    } else {
        Box(
            modifier = m
                .padding(pad)
                .then(if (rn.node.height <= 0) Modifier.height(120.dp) else Modifier)
                .clip(placeholderShape)
                .background(colors.bg3),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = uiV3BrokenImage,
                contentDescription = null,
                tint = colors.text3,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun IconNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val iconSize = (rn.node.size.takeIf { it > 0 } ?: 18).coerceIn(8, 96).dp
    Icon(
        imageVector = uiV3Icon(rn.icon),
        contentDescription = null,
        tint = parseHexColor(rn.color) ?: colors.text2,
        modifier = m.padding(pad).size(iconSize)
    )
}

@Composable
private fun DividerNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val thickness = rn.node.thickness.coerceIn(1, 12).dp
    Box(modifier = m.padding(pad)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(thickness)
                .background(parseHexColor(rn.color) ?: colors.line)
        )
    }
}

@Composable
private fun ProgressNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val height = 6.dp
    val progress = (rn.value.toFloatOrNull() ?: 0f)
        .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Box(modifier = m.padding(pad)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(colors.line)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceAtLeast(0.001f))
                    .height(height)
                    .background(parseHexColor(rn.color) ?: colors.primary)
            )
        }
    }
}

@Composable
private fun BadgeNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val accent = parseHexColor(rn.color) ?: colors.primary
    Box(modifier = m.padding(pad)) {
        Text(
            text = rn.text,
            color = accent,
            fontSize = (11f * ctx.fontScale).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(accent.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun spacerModifier(rn: RNode, m: Modifier): Modifier {
    val side = rn.node.size.coerceIn(0, 200).dp
    var out = m
    if (rn.width.isBlank()) out = out.width(side)
    if (rn.node.height <= 0) out = out.height(side)
    return out
}

/* ---------------- 可交互组件 ---------------- */

@Composable
private fun InputNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val key = rn.node.key.trim()
    val fromState = if (key.isNotBlank() && ctx.state.has(key)) valueText(ctx.state.opt(key)) else null
    val initial = fromState ?: rn.text

    var text by remember(rn.path) { mutableStateOf(initial) }
    val interaction = remember(rn.path) { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var hadFocus by remember(rn.path) { mutableStateOf(false) }
    var skipNextBlur by remember(rn.path) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // 宿主重置 state 时同步外部值（正在输入时不打断用户）
    LaunchedEffect(initial) {
        if (!focused && text != initial) text = initial
    }

    fun fireAction() {
        if (rn.action.isNotBlank()) {
            ctx.onAction(rn.action, actionParams(rn.params, key, text))
        }
    }

    LaunchedEffect(focused) {
        if (focused) {
            hadFocus = true
        } else if (hadFocus) {
            hadFocus = false
            if (skipNextBlur) skipNextBlur = false else fireAction()
        }
    }

    Box(
        modifier = m
            .padding(pad)
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.bg3)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        BasicTextField(
            value = text,
            onValueChange = { value ->
                text = value
                if (key.isNotBlank()) ctx.onStateChange(key, value)
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = colors.text1, fontSize = (14f * ctx.fontScale).sp),
            singleLine = true,
            interactionSource = interaction,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                skipNextBlur = true
                fireAction()
                focusManager.clearFocus()
            }),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth()) {
                    if (text.isEmpty() && rn.placeholder.isNotBlank()) {
                        Text(
                            text = rn.placeholder,
                            color = colors.text3,
                            fontSize = (14f * ctx.fontScale).sp
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun ToggleNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val key = rn.node.key.trim()
    val fromState = if (key.isNotBlank() && ctx.state.has(key)) toBool(ctx.state.opt(key)) else null
    val initial = fromState ?: toBool(rn.value)

    var local by remember(rn.path) { mutableStateOf<Boolean?>(null) }
    // state 里出现权威值时清掉本地乐观值；换 body 后新初始值也能立刻生效
    LaunchedEffect(initial) { local = null }
    val checked = local ?: initial

    fun toggle() {
        val next = !checked
        local = next
        if (key.isNotBlank()) ctx.onStateChange(key, next)
        if (rn.action.isNotBlank()) ctx.onAction(rn.action, actionParams(rn.params, key, next))
    }

    Row(
        modifier = m.padding(pad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (rn.text.isNotBlank()) {
            Text(
                text = rn.text,
                modifier = Modifier.weight(1f),
                color = colors.text1,
                fontSize = (14f * ctx.fontScale).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        CompactSwitch(checked = checked, onChange = { toggle() })
    }
}

@Composable
private fun SliderNode(rn: RNode, ctx: V3Ctx, m: Modifier, pad: PaddingValues) {
    val colors = ctx.colors
    val key = rn.node.key.trim()
    val min = (rn.node.min.takeIf { it.isFinite() } ?: 0f).coerceIn(-1_000_000f, 1_000_000f)
    val rawMax = (rn.node.max.takeIf { it.isFinite() } ?: 1f).coerceIn(-1_000_000f, 1_000_000f)
    val max = if (rawMax > min) rawMax else min + 1f
    val fromState = if (key.isNotBlank() && ctx.state.has(key)) toFloatOrNull(ctx.state.opt(key)) else null
    val initial = (fromState ?: rn.value.toFloatOrNull()?.takeIf { it.isFinite() } ?: min).coerceIn(min, max)

    var local by remember(rn.path) { mutableStateOf<Float?>(null) }
    // 拖动中只改本地值；state 回写后清掉本地值，以 state 为准
    LaunchedEffect(initial) { local = null }
    val current = local ?: initial

    val step = rn.node.step.takeIf { it.isFinite() } ?: 0f
    val steps = if (step > 0f && max > min) {
        (((max - min) / step).roundToInt() - 1).coerceIn(0, 1000)
    } else {
        0
    }

    Column(modifier = m.padding(pad)) {
        if (rn.text.isNotBlank()) {
            Text(
                text = rn.text,
                color = colors.text2,
                fontSize = (12f * ctx.fontScale).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Slider(
            value = current,
            onValueChange = { local = it.coerceIn(min, max) },
            onValueChangeFinished = {
                val done = (local ?: current).coerceIn(min, max)
                if (key.isNotBlank()) ctx.onStateChange(key, done)
                if (rn.action.isNotBlank()) {
                    ctx.onAction(rn.action, actionParams(rn.params, key, done))
                }
            },
            valueRange = min..max,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = colors.primary,
                inactiveTrackColor = colors.line
            )
        )
    }
}

/* ---------------- 占位/错误 ---------------- */

@Composable
private fun Placeholder(label: String, error: String?, colors: UiV3Colors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .uiV3DashedBorder(colors.text3.copy(alpha = 0.55f), 8.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (error.isNullOrBlank()) "未知节点：${label.ifBlank { "empty" }}" else "渲染失败：${error.take(80)}",
            fontSize = 11.sp,
            color = colors.text3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PageError(message: String, colors: UiV3Colors) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "页面渲染失败：${message.take(160)}",
            fontSize = 13.sp,
            color = colors.text2,
            textAlign = TextAlign.Center
        )
    }
}

/* ==================== 小工具 ==================== */

private fun sizeModifier(rn: RNode, ctx: V3Ctx): Modifier {
    val raw = rn.width.trim()
    if (raw.isEmpty()) {
        return if (rn.type in DEFAULT_FILL_TYPES && !ctx.inRow) Modifier.fillMaxWidth() else Modifier
    }
    if (raw.equals("fill", true)) return Modifier.fillMaxWidth()
    if (raw.endsWith("%")) {
        val fraction = raw.dropLast(1).trim().toFloatOrNull()
            ?.takeIf { it.isFinite() }?.div(100f)?.coerceIn(0f, 1f)
        return if (fraction != null) Modifier.fillMaxWidth(fraction) else Modifier
    }
    val widthDp = raw.toFloatOrNull()?.takeIf { it.isFinite() }
    return if (widthDp != null && widthDp > 0f) Modifier.width(widthDp.dp) else Modifier
}

private fun rowArrangement(justify: String, gap: Dp): Arrangement.Horizontal =
    when (justify.trim().lowercase()) {
        "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
        "end" -> Arrangement.spacedBy(gap, Alignment.End)
        "between" -> Arrangement.SpaceBetween
        else -> if (gap > 0.dp) Arrangement.spacedBy(gap) else Arrangement.Start
    }

private fun fontWeightOf(weight: String): FontWeight = when (weight.trim().lowercase()) {
    "medium", "500" -> FontWeight.Medium
    "semibold", "600" -> FontWeight.SemiBold
    "bold", "700" -> FontWeight.Bold
    else -> FontWeight.Normal
}

private fun textAlignOf(align: String): TextAlign? = when (align.trim().lowercase()) {
    "start" -> TextAlign.Start
    "center" -> TextAlign.Center
    "end" -> TextAlign.End
    else -> null
}

private fun toBool(value: Any?): Boolean = when (value) {
    null, JSONObject.NULL -> false
    is Boolean -> value
    is Number -> value.toDouble() != 0.0
    is String -> value.trim().lowercase() in setOf("true", "1", "yes", "on")
    else -> false
}

private fun toFloatOrNull(value: Any?): Float? = when (value) {
    null, JSONObject.NULL -> null
    is Number -> value.toFloat()
    is Boolean -> if (value) 1f else 0f
    is String -> value.trim().toFloatOrNull()
    else -> null
}.takeIf { it != null && it.isFinite() }

private fun copyParams(source: JSONObject): JSONObject {
    val out = JSONObject()
    source.keys().forEach { key -> out.put(key, source.opt(key)) }
    return out
}

/** 控件动作参数：节点 params + 当前 key/value（宿主也能从 state 里拿到）。 */
private fun actionParams(base: JSONObject, key: String, value: Any?): JSONObject {
    val out = copyParams(base)
    if (key.isNotBlank()) out.put(key, value ?: JSONObject.NULL)
    return out
}
