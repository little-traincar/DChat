package com.dchat.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** 解析后的块，避免每帧重新解析 Markdown */
private sealed interface MdBlock {
    data class Para(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Bullet(val marker: String, val text: AnnotatedString) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
    data object Gap : MdBlock
}

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    fontSize: TextUnit = 14.5.sp,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false
) {
    val c = LocalColors.current
    val openerRef = com.dchat.app.ui.LocalUrlOpener.current
    // 关键：只在文本/主题变化时解析一次
    val blocks = remember(text, showCursor, c.isDark) {
        parseMarkdown(if (showCursor) "$text▍" else text, color, c.brand, c.bg3, c.text2) { openerRef(it) }
    }

    Column(modifier.fillMaxWidth()) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    b.text,
                    fontSize = when (b.level) { 1 -> 18.sp; 2 -> 16.5.sp; else -> 15.5.sp },
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp
                )

                is MdBlock.Para -> Text(b.text, fontSize = fontSize, lineHeight = 23.sp)

                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(
                        b.marker,
                        fontSize = fontSize,
                        color = c.text2,
                        modifier = Modifier.padding(start = 4.dp, end = 6.dp),
                        lineHeight = 23.sp
                    )
                    Text(b.text, fontSize = fontSize, lineHeight = 23.sp, modifier = Modifier.weight(1f))
                }

                is MdBlock.Quote -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                ) {
                    Text(b.text, fontSize = fontSize, lineHeight = 22.sp, color = c.text2)
                }

                is MdBlock.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.bg3)
                        .padding(10.dp)
                ) {
                    Text(
                        b.code,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = c.text1,
                        lineHeight = 18.sp
                    )
                }

                is MdBlock.Table -> MdTable(b.header, b.rows)

                MdBlock.Gap -> Spacer(Modifier.height(7.dp))
            }
        }
    }
}

/* ---------------- 表格 ---------------- */

private val TableCellHPad = 6.dp
private val TableCellVPad = 7.dp
private val TableMinColWidth = 44.dp
private val TableMaxColWidth = 240.dp
private val TableMaxHeight = 320.dp
private val TableBarThickness = 3.dp
private val TableMinThumbSize = 24.dp

@Composable
private fun MdTable(header: List<AnnotatedString>, rows: List<List<AnnotatedString>>) {
    val c = LocalColors.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val colCount = remember(header, rows) {
        maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    }
    // 实际 Text 的样式 = LocalTextStyle + 显式覆盖；测量用同一份样式，保证量出来的宽度和渲染一致
    val baseTextStyle = LocalTextStyle.current
    val headerStyle = remember(baseTextStyle) {
        baseTextStyle.merge(TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
    }
    val bodyStyle = remember(baseTextStyle) {
        baseTextStyle.merge(TextStyle(fontSize = 12.sp, lineHeight = 17.sp))
    }
    // 按内容测量每列自然宽度：表头与内容取最大值，再夹在 [44dp, 240dp] 之间。
    // 表头和所有行共用同一套列宽，所以短列（日期）不会被拉宽，长列到 240dp 才折行。
    val colWidthsPx = remember(header, rows, colCount, measurer, density, headerStyle, bodyStyle) {
        val padPx = with(density) { TableCellHPad.toPx() }
        val minPx = with(density) { TableMinColWidth.toPx() }
        val maxPx = with(density) { TableMaxColWidth.toPx() }
        List(colCount) { index ->
            val headerPx = header.getOrNull(index)?.let {
                measurer.measure(it, style = headerStyle, softWrap = false).size.width
            } ?: 0
            val bodyPx = rows.maxOfOrNull { row ->
                row.getOrNull(index)?.let {
                    measurer.measure(it, style = bodyStyle, softWrap = false).size.width
                } ?: 0
            } ?: 0
            (maxOf(headerPx, bodyPx) + padPx).coerceIn(minPx, maxPx)
        }
    }
    if (colCount == 0) return
    val colWidthsDp = remember(colWidthsPx, density) {
        colWidthsPx.map { with(density) { it.toDp() } }
    }
    val tableWidth = remember(colWidthsDp) { colWidthsDp.fold(0.dp) { acc, w -> acc + w } }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val hAlpha = rememberScrollbarAlpha(hScroll)
    val vAlpha = rememberScrollbarAlpha(vScroll)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // 纵向：超过 320dp 才滚动；横向：内容比气泡宽时左右滑
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = TableMaxHeight)
                .verticalScroll(vScroll)
        ) {
            Column(Modifier.horizontalScroll(hScroll)) {
                Column(
                    Modifier
                        .width(tableWidth)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.bg)
                ) {
                    Row(Modifier.fillMaxWidth().background(c.bg3)) {
                        for (col in 0 until colCount) {
                            TableCell(
                                header.getOrNull(col) ?: AnnotatedString(""),
                                headerStyle,
                                colWidthsDp[col]
                            )
                        }
                    }
                    rows.forEachIndexed { rowIndex, row ->
                        Row(Modifier.fillMaxWidth()) {
                            for (col in 0 until colCount) {
                                TableCell(
                                    row.getOrNull(col) ?: AnnotatedString(""),
                                    bodyStyle,
                                    colWidthsDp[col]
                                )
                            }
                        }
                        if (rowIndex != rows.lastIndex) {
                            Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        }
                    }
                }
            }
        }
        HorizontalScrollbar(
            state = hScroll,
            alpha = hAlpha,
            trackColor = c.text3.copy(alpha = 0.25f),
            thumbColor = c.brand,
            modifier = Modifier.matchParentSize()
        )
        VerticalScrollbar(
            state = vScroll,
            alpha = vAlpha,
            trackColor = c.text3.copy(alpha = 0.25f),
            thumbColor = c.brand,
            modifier = Modifier.matchParentSize()
        )
    }
}

@Composable
private fun TableCell(text: AnnotatedString, style: TextStyle, width: Dp) {
    val c = LocalColors.current
    Text(
        text = text,
        style = style,
        color = c.text1,
        modifier = Modifier
            .width(width)
            .padding(horizontal = TableCellHPad, vertical = TableCellVPad)
    )
}

/** 滚动条可见度：任意滚动/惯性滑动都算活动，停 1.4s 后淡出（值变化用 collectLatest 取消上一次待淡出） */
@Composable
private fun rememberScrollbarAlpha(state: ScrollState): State<Float> {
    val active = remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.value to state.isScrollInProgress }.collectLatest { (_, scrolling) ->
            if (scrolling) {
                active.value = true
            } else {
                delay(1400)
                active.value = false
            }
        }
    }
    return animateFloatAsState(
        targetValue = if (active.value) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "tableScrollbarAlpha"
    )
}

@Composable
private fun HorizontalScrollbar(
    state: ScrollState,
    alpha: State<Float>,
    trackColor: Color,
    thumbColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val viewport = state.viewportSize
        val max = state.maxValue
        val a = alpha.value
        if (viewport <= 0 || max <= 0 || a <= 0.01f) return@Canvas
        val barHeight = TableBarThickness.toPx()
        val top = size.height - barHeight
        if (top < 0f) return@Canvas
        val radius = barHeight / 2f
        drawRoundRect(
            color = trackColor.copy(alpha = trackColor.alpha * a),
            topLeft = Offset(0f, top),
            size = Size(size.width, barHeight),
            cornerRadius = CornerRadius(radius)
        )
        val ratio = viewport.toFloat() / (viewport + max)
        val thumbWidth = (size.width * ratio).coerceIn(TableMinThumbSize.toPx(), size.width)
        val fraction = (state.value.toFloat() / max).coerceIn(0f, 1f)
        drawRoundRect(
            color = thumbColor.copy(alpha = thumbColor.alpha * a),
            topLeft = Offset((size.width - thumbWidth) * fraction, top),
            size = Size(thumbWidth, barHeight),
            cornerRadius = CornerRadius(radius)
        )
    }
}

@Composable
private fun VerticalScrollbar(
    state: ScrollState,
    alpha: State<Float>,
    trackColor: Color,
    thumbColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val viewport = state.viewportSize
        val max = state.maxValue
        val a = alpha.value
        if (viewport <= 0 || max <= 0 || a <= 0.01f) return@Canvas
        val barWidth = TableBarThickness.toPx()
        val left = size.width - barWidth
        if (left < 0f) return@Canvas
        val radius = barWidth / 2f
        drawRoundRect(
            color = trackColor.copy(alpha = trackColor.alpha * a),
            topLeft = Offset(left, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = CornerRadius(radius)
        )
        val ratio = viewport.toFloat() / (viewport + max)
        val thumbHeight = (size.height * ratio).coerceIn(TableMinThumbSize.toPx(), size.height)
        val fraction = (state.value.toFloat() / max).coerceIn(0f, 1f)
        drawRoundRect(
            color = thumbColor.copy(alpha = thumbColor.alpha * a),
            topLeft = Offset(left, (size.height - thumbHeight) * fraction),
            size = Size(barWidth, thumbHeight),
            cornerRadius = CornerRadius(radius)
        )
    }
}

private fun parseMarkdown(
    src: String,
    color: Color,
    linkColor: Color,
    codeBg: Color,
    quoteColor: Color,
    opener: (String) -> Unit
): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]

        // 代码块
        if (line.trimStart().startsWith("```")) {
            val buf = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                buf.append(lines[i]).append('\n')
                i++
            }
            if (i < lines.size) i++
            out.add(MdBlock.Code(buf.toString().trimEnd()))
            continue
        }

        // 表格
        if (line.trimStart().startsWith("|") && i + 1 < lines.size && lines[i + 1].contains("---")) {
            val header = splitRow(line).map { inline(it, color, linkColor, codeBg, opener) }
            i += 2
            val rows = ArrayList<List<AnnotatedString>>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                rows.add(splitRow(lines[i]).map { inline(it, color, linkColor, codeBg, opener) })
                i++
            }
            out.add(MdBlock.Table(header, rows))
            continue
        }

        when {
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                out.add(MdBlock.Heading(level, inline(line.drop(level).trim(), color, linkColor, codeBg, opener)))
            }

            line.startsWith("- ") || line.startsWith("* ") ->
                out.add(MdBlock.Bullet("•", inline(line.drop(2).trim(), color, linkColor, codeBg, opener)))

            Regex("^\\d+[.)]\\s").containsMatchIn(line) ->
                out.add(
                    MdBlock.Bullet(
                        line.substringBefore(' '),
                        inline(line.substringAfter(' ').trim(), color, linkColor, codeBg, opener)
                    )
                )

            line.startsWith("> ") ->
                out.add(MdBlock.Quote(inline(line.drop(2).trim(), quoteColor, linkColor, codeBg, opener)))

            line.isBlank() -> out.add(MdBlock.Gap)

            else -> out.add(MdBlock.Para(inline(line, color, linkColor, codeBg, opener)))
        }
        i++
    }
    return out
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/** 行内 HTML 片段：<br>/<br/>/<BR> 一律当换行，常见实体做基本解码（&amp; 最后处理，避免二次解码） */
private fun normalizeInlineHtml(src: String): String {
    if (src.indexOf('<') < 0 && src.indexOf('&') < 0) return src
    var text = src.replace(Regex("(?i)<br\\s*/?>"), "\n")
    text = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
    return text
}

/** **粗体**、`代码`、[文字](url)；HTML 片段先归一化 */
private fun inline(text: String, color: Color, linkColor: Color, codeBg: Color, opener: (String) -> Unit): AnnotatedString {
    val src = normalizeInlineHtml(text)
    return buildAnnotatedString {
        var i = 0
        while (i < src.length) {
            val bold = src.indexOf("**", i)
            val code = src.indexOf('`', i)
            val link = src.indexOf('[', i)
            val next = listOf(bold, code, link).filter { it >= 0 }.minOrNull() ?: -1
            if (next < 0) {
                append(src.substring(i))
                return@buildAnnotatedString
            }
            if (next > i) append(src.substring(i, next))

            when (next) {
                bold -> {
                    val end = src.indexOf("**", bold + 2)
                    if (end < 0) { append(src.substring(bold)); return@buildAnnotatedString }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(src.substring(bold + 2, end))
                    }
                    i = end + 2
                }

                code -> {
                    val end = src.indexOf('`', code + 1)
                    if (end < 0) { append(src.substring(code)); return@buildAnnotatedString }
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) {
                        append(" ${src.substring(code + 1, end)} ")
                    }
                    i = end + 1
                }

                else -> {
                    // [文字](url)
                    val close = src.indexOf(']', link + 1)
                    val open = if (close > 0) src.indexOf('(', close) else -1
                    val end = if (open > 0) src.indexOf(')', open) else -1
                    if (close < 0 || open != close + 1 || end < 0) {
                        append(src.substring(link))
                        return@buildAnnotatedString
                    }
                    val label = src.substring(link + 1, close)
                    val url = src.substring(open + 1, end)
                    runCatching {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline
                                    )
                                ),
                                linkInteractionListener = { opener(url) }
                            )
                        ) { append(label) }
                    }.onFailure { append(label) }
                    i = end + 1
                }
            }
        }
    }
}
