package com.dchat.app.ui.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 查看器底色：近黑 + 轻微透明，和 dsh 的全屏看图一致 */
private val ViewerScrim = Color(0xF2000000)

private const val MIN_SCALE = 0.8f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f

/** 拖到边界外时的阻尼系数，松手回弹 */
private const val EDGE_RESISTANCE = 0.35f

/**
 * 全屏图片查看器（最上层浮层，盖住顶栏/输入区）。
 *
 * - 背景近黑，图片 ContentScale.Fit 居中；
 * - 双指缩放 0.8x~5x、放大后拖动平移（边界阻尼 + 松手回弹）；
 * - 双击放大到 2.5x / 还原；
 * - 点图片外的背景、点右上角关闭、返回键都能关；
 * - 多图用 HorizontalPager 左右翻页，底部显示「2 / 5」。
 *
 * 注意：手势没有直接用 detectTransformGestures —— 它会无条件消费单指拖动，
 * 把 HorizontalPager 的左右翻页吃掉。这里用同源的 awaitEachGesture +
 * calculateZoom/calculatePan/calculateCentroid 自己实现：单指且未放大时不消费，
 * 翻页交给 Pager；双指或已放大时才接管手势。缩放/平移数学与 detectTransformGestures 相同。
 */
@Composable
fun ImageViewer(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return
    val L = com.dchat.app.ui.lang.LocalStrings.current
    // 打开时定位到用户点的那张；越界时夹到有效范围
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex)
    ) { images.size }

    // 全屏查看用原图，但按屏幕长边的 2 倍做 inSampleSize 降采样，避免大图 OOM
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val maxPx = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        val longEdge = maxOf(configuration.screenWidthDp, configuration.screenHeightDp) * density
        (longEdge * 2f).roundToInt().coerceAtLeast(1080)
    }

    // 后注册的 BackHandler 优先级更高：这里有浮层时先关查看器，再退页面
    BackHandler { onDismiss() }

    // 近黑浮层盖住状态栏/导航栏：临时把系统栏图标切成浅色，关闭时恢复原样
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNav != null) controller?.isAppearanceLightNavigationBars = oldNav
        }
    }

    Box(Modifier.fillMaxSize().background(ViewerScrim)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomableImage(
                path = images[page],
                maxPx = maxPx,
                onDismiss = onDismiss
            )
        }

        // 右上角关闭：浅色半透明圆形底 + 白色 ×
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = L.common.close,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // 页码：多图时才显示，点击不穿透到背景（避免点页码误关）
        if (images.size > 1) {
            Text(
                text = "${pagerState.currentPage + 1} / ${images.size}",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

/** 单张可缩放图片：自己解码、自己管缩放/平移状态，页面滑走即释放 */
@Composable
private fun ZoomableImage(
    path: String,
    maxPx: Int,
    onDismiss: () -> Unit
) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    val L = com.dchat.app.ui.lang.LocalStrings.current
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var animJob by remember(path) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    // 动画 spec 在这里创建：手势/协程里不能调用 @Composable 的 tween()
    val settleSpec = remember { TweenSpec<Float>(durationMillis = 200) }
    val doubleTapSpec = remember { TweenSpec<Float>(durationMillis = 220, easing = FastOutSlowInEasing) }

    LaunchedEffect(path, maxPx) {
        failed = false
        // 解码必须在 IO 线程做，避免主线程卡顿
        val decoded = withContext(Dispatchers.IO) { decodeViewerImage(path, maxPx) }
        failed = decoded == null
        bitmap = decoded
    }

    // 双击：未放大 → 以双击点为焦点放大到 2.5x；已放大 → 还原
    fun animateDoubleTap(tap: Offset, viewport: IntSize) {
        animJob?.cancel()
        animJob = scope.launch {
            val fromScale = scale
            val fromOffset = offset
            val toScale = if (fromScale > 1.01f) 1f else DOUBLE_TAP_SCALE
            val center = Offset(viewport.width / 2f, viewport.height / 2f)
            val zoomRatio = toScale / fromScale
            val raw = fromOffset * zoomRatio + (tap - center) * (1f - zoomRatio)
            val toOffset = clampedOffset(raw, fittedSize(bitmap, viewport), viewport, toScale)
            animate(0f, 1f, animationSpec = doubleTapSpec) { fraction, _ ->
                scale = fromScale + (toScale - fromScale) * fraction
                offset = fromOffset + (toOffset - fromOffset) * fraction
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 点击/双击：点图像外的背景关掉；双击图像放大或还原
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        if (!insideImage(pos, size, bitmap, scale, offset)) onDismiss()
                    },
                    onDoubleTap = { pos ->
                        if (!insideImage(pos, size, bitmap, scale, offset)) {
                            onDismiss()
                        } else {
                            animateDoubleTap(pos, size)
                        }
                    }
                )
            }
            // 双指缩放 + 放大后的单指平移 + 边界阻尼/回弹
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // 新触摸打断上次的回弹/双击动画
                    animJob?.cancel()
                    var handled = false
                    // 已放大时的单指拖动要过 touch slop 才接管；
                    // 否则单击/双击的轻微抖动会被 consume 掉，双击还原会失灵
                    var pastSlop = false
                    var slopAccum = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        val multi = pressed >= 2
                        // 单指且未放大：不消费，让 HorizontalPager 翻页
                        if (!multi && scale <= 1.001f) continue
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (!multi && !pastSlop) {
                            slopAccum += panChange
                            if (slopAccum.getDistance() <= viewConfiguration.touchSlop) continue
                            pastSlop = true
                        }
                        handled = true
                        val oldScale = scale
                        val newScale = (oldScale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        val zoomRatio = newScale / oldScale
                        // 以双指中心为焦点缩放：先绕视图中心缩放，再补平移
                        val centroid = event.calculateCentroid(useCurrent = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val rawOffset = offset * zoomRatio +
                            (centroid - center) * (1f - zoomRatio) + panChange
                        val content = fittedSize(bitmap, size)
                        val nextOffset = rubberClamped(rawOffset, content, size, newScale)
                        scale = newScale
                        offset = nextOffset
                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }
                    }
                    // 松手后把越界/缩回时的偏移夹回边界内。
                    // 注意：awaitPointerEventScope 是 @RestrictsSuspension 作用域，
                    // 不能直接 await animate()，所以动画放到外部协程里跑
                    if (handled) {
                        val content = fittedSize(bitmap, size)
                        val target = clampedOffset(offset, content, size, scale)
                        if ((target - offset).getDistanceSquared() > 0.25f) {
                            val start = offset
                            animJob = scope.launch {
                                animate(0f, 1f, animationSpec = settleSpec) { fraction, _ ->
                                    offset = start + (target - start) * fraction
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp == null) {
            if (failed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.BrokenImage,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(L.chat.imageLoadFailed, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White.copy(alpha = 0.85f),
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(L.chat.loading, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }
        } else {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    // 平移用 layout offset（屏幕像素、不受缩放影响），缩放用 graphicsLayer
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            )
        }
    }
}

private fun fittedSize(bitmap: ImageBitmap?, viewport: IntSize): Size {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0 ||
        viewport.width <= 0 || viewport.height <= 0
    ) {
        return Size.Zero
    }
    val k = minOf(
        viewport.width.toFloat() / bitmap.width,
        viewport.height.toFloat() / bitmap.height
    )
    return Size(bitmap.width * k, bitmap.height * k)
}

private fun panLimit(content: Size, viewport: IntSize, scale: Float): Offset {
    if (viewport.width <= 0 || viewport.height <= 0) return Offset.Zero
    val maxX = ((content.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((content.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(maxX, maxY)
}

/** 硬夹取：松手回弹的终点、双击缩放的终点都用它 */
private fun clampedOffset(raw: Offset, content: Size, viewport: IntSize, scale: Float): Offset {
    val limit = panLimit(content, viewport, scale)
    return Offset(
        raw.x.coerceIn(-limit.x, limit.x),
        raw.y.coerceIn(-limit.y, limit.y)
    )
}

/** 拖动时超出边界按阻尼走，松手再回弹回来 */
private fun rubberClamped(raw: Offset, content: Size, viewport: IntSize, scale: Float): Offset {
    val limit = panLimit(content, viewport, scale)
    fun rubber(value: Float, max: Float): Float = when {
        value > max -> max + (value - max) * EDGE_RESISTANCE
        value < -max -> -max + (value + max) * EDGE_RESISTANCE
        else -> value
    }
    return Offset(rubber(raw.x, limit.x), rubber(raw.y, limit.y))
}

/** 判断点击位置是否落在图片实际显示区域（点图像外才算点背景） */
private fun insideImage(
    pos: Offset,
    viewport: IntSize,
    bitmap: ImageBitmap?,
    scale: Float,
    offset: Offset
): Boolean {
    val content = fittedSize(bitmap, viewport)
    if (content.width <= 0f || content.height <= 0f) return false
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val halfW = content.width * scale / 2f
    val halfH = content.height * scale / 2f
    val left = center.x - halfW + offset.x
    val top = center.y - halfH + offset.y
    return pos.x in left..(left + halfW * 2f) && pos.y in top..(top + halfH * 2f)
}

/**
 * 按屏幕尺寸采样解码本地图片：全屏用原图，但长边超过 [maxPx] 时按 2 的幂
 * 递减 inSampleSize，保证解码后的长边不超过 maxPx（默认 = 屏幕长边 × 2）。
 */
private fun decodeViewerImage(path: String, maxPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / sample > maxPx) sample *= 2
    BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
    )?.asImageBitmap()
}.getOrNull()
