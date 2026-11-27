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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

/* ---------------- 间距 / 虚线框 ---------------- */

/** 规范：padding/margin 支持 [l,t,r,b] 或单值；2/3 个值时按常见习惯补齐。 */
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
