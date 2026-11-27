package com.dchat.app.ui.tools

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dchat.app.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * UI v3 渲染的纯工具层：颜色/主题解析、渐变画笔、图片解码缓存、内置图标映射、
 * 内边距换算、虚线占位框。这里不放任何业务状态，方便 [UiV3Renderer] 复用。
 */

/** 页面主题解析后的实际颜色；null/非法值回退 App 现有色板。 */
internal data class UiV3Colors(
    val primary: Color,
    val bg: Color,
    val card: Color,
    val bg3: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val line: Color,
    val danger: Color,
    val radius: Dp
)

internal fun uiV3Colors(theme: UiTheme, base: AppColors): UiV3Colors = UiV3Colors(
    primary = parseHexColor(theme.colorPrimary) ?: base.brand,
    bg = parseHexColor(theme.bg) ?: base.bg,
    card = parseHexColor(theme.card) ?: base.bg2,
    bg3 = base.bg3,
    text1 = parseHexColor(theme.text1) ?: base.text1,
    text2 = parseHexColor(theme.text2) ?: base.text2,
    text3 = parseHexColor(theme.text3) ?: base.text3,
    line = base.line2,
    danger = base.danger,
    radius = theme.radius.coerceIn(0, 48).dp
)

/** 页面级额外字号缩放；App 全局 fontScale 已经由 Dtheme 处理，这里只叠加页面自己的。 */
internal fun uiV3FontScale(theme: UiTheme): Float =
    theme.fontScale.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.6f, 2f) ?: 1f

/** `#RGB` / `#RGBA` / `#RRGGBB` / `#AARRGGBB`，另外支持少量关键字；非法返回 null。 */
internal fun parseHexColor(raw: String?): Color? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    if (s.startsWith("#")) {
        val hex = s.substring(1)
        val expanded = when (hex.length) {
            3, 4 -> buildString { hex.forEach { append(it).append(it) } }
            6, 8 -> hex
            else -> return null
        }
        return runCatching {
            val v = expanded.toLong(16)
            when (expanded.length) {
                6 -> Color(0xFF000000L or v)
                8 -> Color(v) // 按 Android 习惯当作 #AARRGGBB
                else -> null
            }
        }.getOrNull()
    }
    return when (s.lowercase()) {
        "transparent", "none" -> Color.Transparent
        "white" -> Color.White
        "black" -> Color.Black
        else -> null
    }
}

/** 内置图标名 → Material Rounded 图标；未知回退 star（规范 2.4）。 */
internal fun uiV3Icon(name: String): ImageVector {
    val key = name.trim().lowercase().replace('-', '_').replace(' ', '_')
    return when (key) {
        "back" -> Icons.AutoMirrored.Rounded.ArrowBack
        "close" -> Icons.Rounded.Close
        "more" -> Icons.Rounded.MoreHoriz
        "search" -> Icons.Rounded.Search
        "cloud" -> Icons.Rounded.Cloud
        "sun" -> Icons.Rounded.WbSunny
        "list" -> Icons.AutoMirrored.Rounded.FormatListBulleted
        "settings" -> Icons.Rounded.Settings
        "refresh" -> Icons.Rounded.Refresh
        "add" -> Icons.Rounded.Add
        "check" -> Icons.Rounded.Check
        "star" -> Icons.Rounded.Star
        "heart" -> Icons.Rounded.Favorite
        "arrow_right" -> Icons.Rounded.ChevronRight
        "arrow_down" -> Icons.Rounded.ExpandMore
        else -> Icons.Rounded.Star
    }
}

internal val uiV3BrokenImage: ImageVector get() = Icons.Rounded.BrokenImage

/* ---------------- 图片：解析路径 + 采样解码 + LruCache ---------------- */

private const val IMAGE_CACHE_BYTES = 16 * 1024 * 1024

private val uiImageCache = object : LruCache<String, ImageBitmap>(IMAGE_CACHE_BYTES) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        runCatching { value.asAndroidBitmap().allocationByteCount }.getOrDefault(1)
}

/**
 * 宿主 `onResolveImage` 把页面里的原始 src（`assets/bg.jpg`、`workspace:x.png`）解析成绝对路径。
 * 没传回调时只接受绝对路径 / file://（方便其他宿主直接给路径）。失败返回 null。
 */
internal fun resolveUiImagePath(src: String, resolver: ((String) -> String?)?): String? {
    val s = src.trim()
    if (s.isEmpty()) return null
    val fromHost = runCatching { resolver?.invoke(s) }.getOrNull()
    if (!fromHost.isNullOrBlank()) {
        return runCatching { File(fromHost).takeIf { it.isFile }?.absolutePath }.getOrNull()
    }
    val fallback = when {
        s.startsWith("file://") -> s.removePrefix("file://")
        s.startsWith("/") -> s
        else -> return null
    }
    return runCatching { File(fallback).takeIf { it.isFile }?.absolutePath }.getOrNull()
}

private fun decodeUiBitmap(path: String, maxDim: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
        )?.asImageBitmap()
    }
}.getOrNull()

/**
 * 在后台线程解码并缓存图片；解码完成前返回 null（调用方显示占位）。
 * key 里带文件修改时间，覆盖写同一个文件也能刷新。
 */
@Composable
internal fun rememberUiBitmap(path: String?, maxDim: Int): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    val stamp = remember(path) { runCatching { File(path).lastModified() }.getOrDefault(0L) }
    val key = "$path@$stamp#$maxDim"
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(uiImageCache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap == null) {
            val decoded = withContext(Dispatchers.IO) { decodeUiBitmap(path, maxDim) }
            if (decoded != null) {
                uiImageCache.put(key, decoded)
                bitmap = decoded
            }
        }
    }
    return bitmap
}

/** 图片缓存 key：路径 + 修改时间 + 目标长边。 */
internal fun uiV3ImageKey(path: String, maxDim: Int): String {
    val stamp = runCatching { File(path).lastModified() }.getOrDefault(0L)
    return "$path@$stamp#$maxDim"
}

/** canvas 图片 op 预加载：后台采样解码并写 LruCache，绘制帧里只查内存。 */
internal suspend fun uiV3LoadBitmap(path: String, maxDim: Int): ImageBitmap? {
    if (path.isBlank()) return null
    val key = uiV3ImageKey(path, maxDim)
    uiImageCache.get(key)?.let { return it }
    val decoded = withContext(Dispatchers.IO) { decodeUiBitmap(path, maxDim) } ?: return null
    uiImageCache.put(key, decoded)
    return decoded
}

/* ---------------- 渐变 / 图片绘制 ---------------- */

/** 按规范把 stops/angle/radial 转成 Compose Brush；stops 不足时退化为纯色。 */
internal fun uiV3GradientBrush(background: UiBackground, size: Size, fallback: Color): Brush {
    // 零尺寸（空节点还没测量）时不要构造 shader，避免非法渐变参数
    if (size.width <= 0f || size.height <= 0f) {
        return Brush.linearGradient(listOf(fallback, fallback))
    }
    val stops = background.stops
        .mapNotNull { (color, pos) ->
            val c = parseHexColor(color) ?: return@mapNotNull null
            if (!pos.isFinite()) return@mapNotNull null
            c to pos.coerceIn(0f, 1f)
        }
        .sortedBy { it.second }
    if (stops.isEmpty()) return Brush.linearGradient(listOf(fallback, fallback))
    if (stops.size == 1) return Brush.linearGradient(listOf(stops[0].first, stops[0].first))
    val colorStops = stops.map { it.second to it.first }.toTypedArray()
    val radial = background.radial
    if (radial.size >= 3) {
        val center = Offset(
            radial[0].takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.times(size.width) ?: (size.width / 2f),
            radial[1].takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.times(size.height) ?: (size.height / 2f)
        )
        val radius = (radial[2].takeIf { it.isFinite() }?.coerceAtLeast(0.01f) ?: 1f) *
            max(size.width, size.height)
        return Brush.radialGradient(*colorStops, center = center, radius = radius.coerceAtLeast(1f))
    }
    // CSS 角度语义：0=向上，90=向右，180=向下（默认 180）
    val angle = background.angle.takeIf { it.isFinite() } ?: 180f
    val rad = Math.toRadians(angle.toDouble())
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val len = abs(size.width * dx) + abs(size.height * dy)
    val start = Offset(cx - dx * len / 2f, cy - dy * len / 2f)
    val end = Offset(cx + dx * len / 2f, cy + dy * len / 2f)
    return Brush.linearGradient(*colorStops, start = start, end = end)
}

/** 在 DrawScope 里按 cover/contain 画图，并叠加 opacity/scrim。 */
internal fun DrawScope.drawUiImage(image: ImageBitmap, fit: String, opacity: Float, scrim: Float) {
    val alpha = opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    val scrimAlpha = scrim.takeIf { it.isFinite() }?.coerceIn(0f, 0.8f) ?: 0f
    if (alpha <= 0f) return
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    val w = size.width
    val h = size.height
    if (iw <= 0f || ih <= 0f || w <= 0f || h <= 0f) return
    if (fit.equals("contain", true)) {
        val scale = min(w / iw, h / ih)
        val dw = (iw * scale).roundToInt().coerceAtLeast(1)
        val dh = (ih * scale).roundToInt().coerceAtLeast(1)
        drawImage(
            image = image,
            dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
            dstSize = IntSize(dw, dh),
            alpha = alpha,
            filterQuality = FilterQuality.Low
        )
    } else {
        // cover：居中裁切源图，铺满目标区域
        val scale = max(w / iw, h / ih)
        val sw = (w / scale).roundToInt().coerceIn(1, image.width)
        val sh = (h / scale).roundToInt().coerceIn(1, image.height)
        drawImage(
            image = image,
            srcOffset = IntOffset((image.width - sw) / 2, (image.height - sh) / 2),
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            alpha = alpha,
            filterQuality = FilterQuality.Low
        )
    }
    if (scrimAlpha > 0f) {
        drawRect(Color.Black.copy(alpha = scrimAlpha))
    }
}

/* ---------------- Canvas ops 绘制 ---------------- */

/** canvas 图片 op：在给定矩形内按 cover/contain 画图，支持圆角与透明度。坐标单位 = dp。 */
internal fun DrawScope.drawUiImageInRect(
    image: ImageBitmap,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    fit: String,
    alpha: Float,
    radius: Float
) {
    val a = alpha.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    if (a <= 0f || width <= 0f || height <= 0f) return
    val native = drawContext.canvas.nativeCanvas
    val save = native.save()
    try {
        if (radius > 0f) {
            val clip = android.graphics.Path().apply {
                addRoundRect(x, y, x + width, y + height, radius, radius, android.graphics.Path.Direction.CW)
            }
            native.clipPath(clip)
        }
        val iw = image.width.toFloat()
        val ih = image.height.toFloat()
        if (iw <= 0f || ih <= 0f) return
        if (fit.equals("contain", true)) {
            val scale = min(width / iw, height / ih)
            val dw = (iw * scale).roundToInt().coerceAtLeast(1)
            val dh = (ih * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = image,
                dstOffset = IntOffset(
                    (x + (width - dw) / 2f).roundToInt(),
                    (y + (height - dh) / 2f).roundToInt()
                ),
                dstSize = IntSize(dw, dh),
                alpha = a,
                filterQuality = FilterQuality.Low
            )
        } else {
            val scale = max(width / iw, height / ih)
            val sw = (width / scale).roundToInt().coerceIn(1, image.width)
            val sh = (height / scale).roundToInt().coerceIn(1, image.height)
            drawImage(
                image = image,
                srcOffset = IntOffset((image.width - sw) / 2, (image.height - sh) / 2),
                srcSize = IntSize(sw, sh),
                dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
                alpha = a,
                filterQuality = FilterQuality.Low
            )
        }
    } finally {
        runCatching { native.restoreToCount(save) }
    }
}

/**
 * canvas 绘制指令执行器：缓存颜色/渐变/path 解析和 text Paint。
 * 所有坐标按 dp 直接画——调用方在外面把 canvas 缩放 density 倍即可。
 * 单条坏 op 只丢弃自身；save/restore 用 native canvas 栈，最外层 restoreToCount 兜底。
 */
internal class UiV3OpRenderer {
    private val colorCache = HashMap<String, Color>(64)
    private val pathCache = LinkedHashMap<String, Path>()
    private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val clipRectF = android.graphics.RectF()

    fun render(
        drawScope: DrawScope,
        ops: List<UiOp>,
        imageFor: (String) -> ImageBitmap?,
        defaultTextColor: Color
    ) {
        if (ops.isEmpty()) return
        drawScope.apply {
            val native = drawContext.canvas.nativeCanvas
            val base = native.save()
            var alpha = 1f
            var depth = 0
            val alphaStack = ArrayList<Float>(8)
            try {
                for (op in ops) {
                    if (op.unsupported) continue
                    try {
                        when (op.type) {
                            "save" -> {
                                alphaStack.add(alpha)
                                native.save()
                                depth++
                            }

                            "restore" -> if (depth > 0) {
                                native.restore()
                                depth--
                                if (alphaStack.isNotEmpty()) alpha = alphaStack.removeAt(alphaStack.lastIndex)
                            }

                            "translate" -> native.translate(op.x.finite(), op.y.finite())
                            "scale" -> native.scale(op.sx.finite(1f), op.sy.finite(1f))
                            "rotate" -> native.rotate(op.deg.finite(), op.cx.finite(), op.cy.finite())
                            "alpha" -> alpha = op.value.finite(1f).coerceIn(0f, 1f)
                            "clipRect" -> {
                                val x = op.x.finite()
                                val y = op.y.finite()
                                val w = op.w.finite()
                                val h = op.h.finite()
                                if (w > 0f && h > 0f) {
                                    clipRectF.set(x, y, x + w, y + h)
                                    native.clipRect(clipRectF)
                                }
                            }

                            "rect" -> drawRectOp(op, alpha)
                            "circle" -> drawCircleOp(op, alpha)
                            "ellipse" -> drawEllipseOp(op, alpha)
                            "line" -> drawLineOp(op, alpha, defaultTextColor)
                            "polyline" -> drawPolylineOp(op, alpha)
                            "path" -> drawPathOp(op, alpha)
                            "arc" -> drawArcOp(op, alpha)
                            "text" -> drawTextOp(op, alpha, defaultTextColor)
                            "image" -> drawImageOp(op, alpha, imageFor)
                        }
                    } catch (_: Throwable) {
                        // 单条坏 op 丢弃：坏颜色/path/图片都不影响同帧其它指令
                    }
                }
            } finally {
                runCatching { native.restoreToCount(base) }
                alphaStack.clear()
            }
        }
    }

    /* ---------------- 图形 ---------------- */

    private fun DrawScope.drawRectOp(op: UiOp, alpha: Float) {
        val w = op.w.finite()
        val h = op.h.finite()
        if (w <= 0f || h <= 0f) return
        val topLeft = Offset(op.x.finite(), op.y.finite())
        val size = Size(w, h)
        val corner = CornerRadius(op.r.finite().coerceAtLeast(0f))
        val fill = op.fill ?: op.color.takeIf { it.isNotBlank() }?.let { UiPaint.Color(it) }
        fill?.let { drawRoundRectPaint(it, topLeft, size, corner, alpha, null) }
        op.stroke?.let { drawRoundRectPaint(it, topLeft, size, corner, alpha, strokeStyle(op)) }
    }

    private fun DrawScope.drawRoundRectPaint(
        paint: UiPaint,
        topLeft: Offset,
        size: Size,
        corner: CornerRadius,
        alpha: Float,
        style: DrawStyle?
    ) {
        val a = alpha.coerceIn(0f, 1f)
        when (paint) {
            is UiPaint.Color -> cachedColor(paint.value)?.let {
                drawRoundRect(color = it, topLeft = topLeft, size = size, cornerRadius = corner, style = style ?: Fill, alpha = a)
            }

            is UiPaint.Gradient -> gradientBrush(paint)?.let {
                drawRoundRect(brush = it, topLeft = topLeft, size = size, cornerRadius = corner, style = style ?: Fill, alpha = a)
            }
        }
    }

    private fun DrawScope.drawCircleOp(op: UiOp, alpha: Float) {
        val radius = op.r.finite().coerceAtLeast(0f)
        if (radius <= 0f) return
        val center = Offset(op.cx.finite(), op.cy.finite())
        val fill = op.fill ?: op.color.takeIf { it.isNotBlank() }?.let { UiPaint.Color(it) }
        fill?.let { drawCirclePaint(it, center, radius, alpha, null) }
        op.stroke?.let { drawCirclePaint(it, center, radius, alpha, strokeStyle(op)) }
    }

    private fun DrawScope.drawCirclePaint(
        paint: UiPaint,
        center: Offset,
        radius: Float,
        alpha: Float,
        style: DrawStyle?
    ) {
        val a = alpha.coerceIn(0f, 1f)
        when (paint) {
            is UiPaint.Color -> cachedColor(paint.value)?.let {
                drawCircle(color = it, radius = radius, center = center, style = style ?: Fill, alpha = a)
            }

            is UiPaint.Gradient -> gradientBrush(paint)?.let {
                drawCircle(brush = it, radius = radius, center = center, style = style ?: Fill, alpha = a)
            }
        }
    }

    private fun DrawScope.drawEllipseOp(op: UiOp, alpha: Float) {
        val rx = op.rx.finite().coerceAtLeast(0f)
        val ry = op.ry.finite().coerceAtLeast(0f)
        if (rx <= 0f || ry <= 0f) return
        val topLeft = Offset(op.cx.finite() - rx, op.cy.finite() - ry)
        val size = Size(rx * 2f, ry * 2f)
        val fill = op.fill ?: op.color.takeIf { it.isNotBlank() }?.let { UiPaint.Color(it) }
        val a = alpha.coerceIn(0f, 1f)
        fill?.let { paint ->
            when (paint) {
                is UiPaint.Color -> cachedColor(paint.value)?.let {
                    drawOval(color = it, topLeft = topLeft, size = size, alpha = a)
                }

                is UiPaint.Gradient -> gradientBrush(paint)?.let {
                    drawOval(brush = it, topLeft = topLeft, size = size, alpha = a)
                }
            }
        }
        op.stroke?.let { paint ->
            val stroke = strokeStyle(op)
            when (paint) {
                is UiPaint.Color -> cachedColor(paint.value)?.let {
                    drawOval(color = it, topLeft = topLeft, size = size, style = stroke, alpha = a)
                }

                is UiPaint.Gradient -> gradientBrush(paint)?.let {
                    drawOval(brush = it, topLeft = topLeft, size = size, style = stroke, alpha = a)
                }
            }
        }
    }

    private fun DrawScope.drawLineOp(op: UiOp, alpha: Float, defaultColor: Color) {
        val color = cachedColor(op.color) ?: defaultColor
        drawLine(
            color = color,
            start = Offset(op.x1.finite(), op.y1.finite()),
            end = Offset(op.x2.finite(), op.y2.finite()),
            strokeWidth = op.sw.finite(1f).coerceAtLeast(0f),
            cap = capOf(op.cap),
            alpha = alpha.coerceIn(0f, 1f)
        )
    }

    private fun DrawScope.drawPolylineOp(op: UiOp, alpha: Float) {
        if (op.points.isEmpty()) return
        val path = Path()
        op.points.forEachIndexed { index, point ->
            val x = point.first.finite()
            val y = point.second.finite()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (op.closed) path.close()
        drawPathPaints(op, path, alpha)
    }

    private fun DrawScope.drawPathOp(op: UiOp, alpha: Float) {
        val path = cachedPath(op.d) ?: return
        drawPathPaints(op, path, alpha)
    }

    private fun DrawScope.drawPathPaints(op: UiOp, path: Path, alpha: Float) {
        val a = alpha.coerceIn(0f, 1f)
        op.fill?.let { paint ->
            when (paint) {
                is UiPaint.Color -> cachedColor(paint.value)?.let { drawPath(color = it, path = path, alpha = a) }
                is UiPaint.Gradient -> gradientBrush(paint)?.let { drawPath(brush = it, path = path, alpha = a) }
            }
        }
        val stroke = op.stroke ?: op.color.takeIf { it.isNotBlank() }?.let { UiPaint.Color(it) }
        if (stroke != null) {
            val style = strokeStyle(op)
            when (stroke) {
                is UiPaint.Color -> cachedColor(stroke.value)?.let {
                    drawPath(color = it, path = path, style = style, alpha = a)
                }

                is UiPaint.Gradient -> gradientBrush(stroke)?.let {
                    drawPath(brush = it, path = path, style = style, alpha = a)
                }
            }
        }
    }

    private fun DrawScope.drawArcOp(op: UiOp, alpha: Float) {
        val w = op.w.finite()
        val h = op.h.finite()
        if (w <= 0f || h <= 0f) return
        val topLeft = Offset(op.x.finite(), op.y.finite())
        val size = Size(w, h)
        val start = op.start.finite()
        val sweep = op.sweep.finite()
        val a = alpha.coerceIn(0f, 1f)
        val fill = op.fill ?: op.color.takeIf { it.isNotBlank() }?.let { UiPaint.Color(it) }
        fill?.let { paint ->
            when (paint) {
                is UiPaint.Color -> cachedColor(paint.value)?.let {
                    drawArc(color = it, startAngle = start, sweepAngle = sweep, useCenter = true, topLeft = topLeft, size = size, alpha = a)
                }

                is UiPaint.Gradient -> gradientBrush(paint)?.let {
                    drawArc(brush = it, startAngle = start, sweepAngle = sweep, useCenter = true, topLeft = topLeft, size = size, alpha = a)
                }
            }
        }
        op.stroke?.let { paint ->
            val stroke = strokeStyle(op)
            when (paint) {
                is UiPaint.Color -> cachedColor(paint.value)?.let {
                    drawArc(color = it, startAngle = start, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = size, style = stroke, alpha = a)
                }

                is UiPaint.Gradient -> gradientBrush(paint)?.let {
                    drawArc(brush = it, startAngle = start, sweepAngle = sweep, useCenter = false, topLeft = topLeft, size = size, style = stroke, alpha = a)
                }
            }
        }
    }

    private fun DrawScope.drawTextOp(op: UiOp, alpha: Float, defaultColor: Color) {
        if (op.text.isEmpty()) return
        val base = cachedColor(op.color) ?: defaultColor
        val paint = textPaint
        paint.color = base.copy(alpha = (base.alpha * alpha.coerceIn(0f, 1f)).coerceIn(0f, 1f)).toArgb()
        paint.textSize = op.size.finite(14f).takeIf { it > 0f } ?: 14f
        paint.isFakeBoldText = op.bold
        paint.typeface = if (op.bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        paint.textAlign = when (op.align.trim().lowercase()) {
            "center" -> android.graphics.Paint.Align.CENTER
            "right", "end" -> android.graphics.Paint.Align.RIGHT
            else -> android.graphics.Paint.Align.LEFT
        }
        drawContext.canvas.nativeCanvas.drawText(op.text, op.x.finite(), op.y.finite(), paint)
    }

    private fun DrawScope.drawImageOp(op: UiOp, alpha: Float, imageFor: (String) -> ImageBitmap?) {
        val bitmap = imageFor(op.src) ?: return
        val w = op.w.finite()
        val h = op.h.finite()
        if (w <= 0f || h <= 0f) return
        drawUiImageInRect(
            image = bitmap,
            x = op.x.finite(),
            y = op.y.finite(),
            width = w,
            height = h,
            fit = op.fit,
            alpha = alpha.coerceIn(0f, 1f),
            radius = op.radius.finite().coerceAtLeast(0f)
        )
    }

    /* ---------------- paint / path 缓存 ---------------- */

    private fun cachedColor(raw: String): Color? {
        if (raw.isBlank()) return null
        colorCache[raw]?.let { return it }
        val color = parseHexColor(raw) ?: return null
        if (colorCache.size > 256) colorCache.clear()
        colorCache[raw] = color
        return color
    }

    private fun gradientBrush(paint: UiPaint.Gradient): Brush? {
        val stops = paint.stops
            .mapNotNull { (raw, pos) ->
                val color = cachedColor(raw) ?: return@mapNotNull null
                val offset = pos.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                color to offset
            }
            .sortedBy { it.second }
        if (stops.isEmpty()) return null
        val colorStops: Array<Pair<Float, Color>> = if (stops.size == 1) {
            arrayOf(0f to stops[0].first, 1f to stops[0].first)
        } else {
            stops.map { it.second to it.first }.toTypedArray()
        }
        return if (paint.kind.equals("radial", true)) {
            val cx = paint.center.getOrNull(0)?.finite() ?: 0f
            val cy = paint.center.getOrNull(1)?.finite() ?: 0f
            val r = paint.radius.takeIf { it.isFinite() && it > 0f } ?: 1f
            Brush.radialGradient(*colorStops, center = Offset(cx, cy), radius = r)
        } else {
            val fromX = paint.from.getOrNull(0)?.finite() ?: 0f
            val fromY = paint.from.getOrNull(1)?.finite() ?: 0f
            val toX = paint.to.getOrNull(0)?.finite() ?: 0f
            val toY = paint.to.getOrNull(1)?.finite() ?: 0f
            if (fromX == toX && fromY == toY) {
                Brush.linearGradient(*colorStops)
            } else {
                Brush.linearGradient(*colorStops, start = Offset(fromX, fromY), end = Offset(toX, toY))
            }
        }
    }

    private fun cachedPath(d: String): Path? {
        if (d.isBlank()) return null
        pathCache[d]?.let { return it }
        val parsed = runCatching { parsePath(d) }.getOrNull() ?: return null
        if (pathCache.size > 128) pathCache.clear()
        pathCache[d] = parsed
        return parsed
    }

    /** path 子集：M/L/H/V/C/Q/Z（含小写相对坐标）；A/S/T 跳过参数不绘制。 */
    private fun parsePath(d: String): Path? {
        val tokens = PATH_TOKEN.findAll(d).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        val path = Path()
        var i = 0
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f

        fun isCommand(token: String): Boolean = token.length == 1 && token[0].isLetter()
        fun take(): Float? {
            if (i >= tokens.size || isCommand(tokens[i])) return null
            val value = tokens[i].toFloatOrNull() ?: return null
            i++
            return value
        }

        fun absolute(value: Float, origin: Float, relative: Boolean): Float =
            if (relative) origin + value else value

        var guard = 0
        while (i < tokens.size && guard++ < 4096) {
            if (isCommand(tokens[i])) {
                cmd = tokens[i][0]
                i++
            }
            val relative = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = take() ?: break
                    val y = take() ?: break
                    val px = absolute(x, cx, relative)
                    val py = absolute(y, cy, relative)
                    path.moveTo(px, py)
                    cx = px
                    cy = py
                    startX = px
                    startY = py
                    cmd = if (relative) 'l' else 'L'
                }

                'L' -> {
                    val x = take() ?: break
                    val y = take() ?: break
                    cx = absolute(x, cx, relative)
                    cy = absolute(y, cy, relative)
                    path.lineTo(cx, cy)
                }

                'H' -> {
                    val x = take() ?: break
                    cx = absolute(x, cx, relative)
                    path.lineTo(cx, cy)
                }

                'V' -> {
                    val y = take() ?: break
                    cy = absolute(y, cy, relative)
                    path.lineTo(cx, cy)
                }

                'C' -> {
                    val x1 = take() ?: break
                    val y1 = take() ?: break
                    val x2 = take() ?: break
                    val y2 = take() ?: break
                    val x = take() ?: break
                    val y = take() ?: break
                    path.cubicTo(
                        absolute(x1, cx, relative), absolute(y1, cy, relative),
                        absolute(x2, cx, relative), absolute(y2, cy, relative),
                        absolute(x, cx, relative), absolute(y, cy, relative)
                    )
                    cx = absolute(x, cx, relative)
                    cy = absolute(y, cy, relative)
                }

                'Q' -> {
                    val x1 = take() ?: break
                    val y1 = take() ?: break
                    val x = take() ?: break
                    val y = take() ?: break
                    val controlX = absolute(x1, cx, relative)
                    val controlY = absolute(y1, cy, relative)
                    val endX = absolute(x, cx, relative)
                    val endY = absolute(y, cy, relative)
                    path.quadraticTo(controlX, controlY, endX, endY)
                    cx = endX
                    cy = endY
                }

                'Z' -> {
                    path.close()
                    cx = startX
                    cy = startY
                    cmd = ' '
                }

                'A' -> repeat(7) { take() ?: return path }
                'S' -> repeat(4) { take() ?: return path }
                'T' -> repeat(2) { take() ?: return path }
                else -> return path
            }
        }
        return path
    }

    private fun strokeStyle(op: UiOp): Stroke = Stroke(
        width = op.sw.finite(1f).coerceAtLeast(0f),
        cap = capOf(op.cap)
    )

    private fun capOf(cap: String): StrokeCap = when (cap.trim().lowercase()) {
        "round" -> StrokeCap.Round
        "square" -> StrokeCap.Square
        else -> StrokeCap.Butt
    }

    private fun Float.finite(fallback: Float = 0f): Float = if (isFinite()) this else fallback

    private companion object {
        val PATH_TOKEN = Regex("[A-Za-z]|[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")
    }
}

/* ---------------- 间距 / 虚线框 ---------------- */
internal fun uiV3Insets(values: List<Int>): List<Int> = when (values.size) {
    0 -> listOf(0, 0, 0, 0)
    1 -> List(4) { values[0].coerceIn(0, 200) }
    2 -> listOf(values[0], values[1], values[0], values[1])
    3 -> listOf(values[0], values[1], values[2], values[1])
    else -> values.take(4).map { it.coerceIn(0, 200) }
}

internal fun uiV3Padding(values: List<Int>): PaddingValues {
    val p = uiV3Insets(values)
    return PaddingValues(start = p[0].dp, top = p[1].dp, end = p[2].dp, bottom = p[3].dp)
}

internal fun uiV3Gap(value: Int): Dp = value.coerceIn(0, 80).dp

/** 灰色虚线占位框（未知节点 / 渲染错误用）。 */
internal fun Modifier.uiV3DashedBorder(color: Color, radius: Dp): Modifier = this.drawBehind {
    val strokeWidth = 1.dp.toPx()
    if (size.width <= strokeWidth || size.height <= strokeWidth) return@drawBehind
    drawRoundRect(
        color = color,
        topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
        size = Size(size.width - strokeWidth, size.height - strokeWidth),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        )
    )
}
