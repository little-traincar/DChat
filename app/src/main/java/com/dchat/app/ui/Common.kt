package com.dchat.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.ui.theme.LocalColors
import com.dchat.app.ui.theme.brandGradient

private val avatarCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

private fun decodeSmall(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrNull()

/** 圆形头像；有本地图片就显示图片，否则渐变底 + 图标 */
@Composable
fun Avatar(
    path: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Person,
    gradient: List<Color> = brandGradient
) {
    // 全局缓存：同一个头像只解码一次，滚动时不再重复 IO + decode。
    // 注意：头像文件是**固定文件名覆盖**（filesDir/avatar_me.img），只按 path 做 key
    // 会一直命中旧图 —— 换头像看起来「不生效」。所以 key 里带上文件修改时间。
    // lastModified 不能放 remember 里，否则路径不变时它也是旧值；每次重组读一次 stat 很便宜。
    val stamp = if (path.isNullOrBlank()) 0L
    else runCatching { java.io.File(path).lastModified() }.getOrDefault(0L)
    val cacheKey = if (path.isNullOrBlank()) "" else "$path@$stamp"
    val bmp: ImageBitmap? = remember(cacheKey) {
        if (path.isNullOrBlank()) null
        else avatarCache[cacheKey] ?: decodeSmall(path, 160)?.also { avatarCache[cacheKey] = it }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.linearGradient(gradient)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.46f))
        }
    }
}

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = LocalColors.current.text2,
    iconSize: Dp = 21.dp
) {
    Box(
        modifier = Modifier.size(38.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 底部能力开关：关 = 白底灰边，开 = 蓝底白字 */
@Composable
fun ChipToggle(
    label: String,
    checked: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (checked) c.brand else c.bg)
            .border(1.dp, if (checked) c.brand else c.line2, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            tint = if (checked) Color.White else c.text2,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 12.5.sp,
            color = if (checked) Color.White else c.text2,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = LocalColors.current.text3,
        modifier = Modifier.padding(start = 20.dp, top = 13.dp, bottom = 6.dp)
    )
}

@Composable
fun SettingGroup(content: @Composable () -> Unit) {
    val c = LocalColors.current
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.bg2)
    ) { content() }
}

@Composable
fun SettingRow(
    label: String,
    value: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    divider: Boolean = false
) {
    val c = LocalColors.current
    androidx.compose.foundation.layout.Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                label,
                fontSize = 14.sp,
                color = if (danger) c.danger else c.text1,
                modifier = Modifier.weight(1f)
            )
            if (value != null) Text(value, fontSize = 13.sp, color = c.text3)
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                trailing()
            } else if (onClick != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.ChevronRight, null,
                    tint = c.text3,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(c.bg)
            )
        }
    }
}

/** 小号开关：34×20dp，和设计稿一致，行高不会比其它行高 */
@Composable
fun CompactSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    val thumb by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 16.dp else 2.dp,
        label = "thumb"
    )
    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 20.dp)
            .clip(RoundedCornerShape(50))
            .background(if (checked) c.brand else c.line2)
            .clickable { onChange(!checked) }
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumb)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    divider: Boolean = false,
    onChange: (Boolean) -> Unit
) {
    SettingRow(
        label = label,
        divider = divider,
        onClick = { onChange(!checked) },
        trailing = { CompactSwitch(checked, onChange) }
    )
}
