package com.dchat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.dchat.app.data.AppSettings
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.LocalStrings

/** 一套语义色，浅色/深色各一份 */
data class AppColors(
    val bg: Color,
    val bg2: Color,
    val bg3: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val line: Color,
    val line2: Color,
    val brand: Color,
    val danger: Color,
    val isDark: Boolean
)

val LightColors = AppColors(
    bg = Color(0xFFFFFFFF),
    bg2 = Color(0xFFF6F7F9),
    bg3 = Color(0xFFF1F3F6),
    text1 = Color(0xFF12141A),
    text2 = Color(0xFF5F6674),
    text3 = Color(0xFF9AA1AC),
    line = Color(0xFFF0F2F5),
    line2 = Color(0xFFE6E9EF),
    brand = Color(0xFF4D6BFE),
    danger = Color(0xFFFF4D4F),
    isDark = false
)

val DarkColors = AppColors(
    bg = Color(0xFF0D0E12),
    bg2 = Color(0xFF171A20),
    bg3 = Color(0xFF1C1F26),
    text1 = Color(0xFFF3F5F8),
    text2 = Color(0xFF98A0AE),
    text3 = Color(0xFF6E7686),
    line = Color(0xFF1E222A),
    line2 = Color(0xFF2A2F39),
    brand = Color(0xFF4D6BFE),
    danger = Color(0xFFFF4D4F),
    isDark = true
)

val LocalColors = staticCompositionLocalOf { LightColors }

val brandGradient: List<Color> = listOf(Color(0xFF7E95FF), Color(0xFF4D6BFE))

/** 默认背景渐变（用户没选图片时） */
val defaultWall: List<Color> = listOf(Color(0xFF93A5FF), Color(0xFF6D83F2), Color(0xFF3A48C6))

@Composable
fun DchatTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = settings.darkTheme
    val colors = if (dark) DarkColors else LightColors
    val density = LocalDensity.current
    // 当前文案表：语言/地区改完由 AppSettings 触发重组，这里立刻换成新的那套
    val strings = Lang.of(settings.language, settings.region)
    // 非 Compose 代码（Toast、数据层、网络错误文案）通过 Lang.current 读取，这里同步一次
    SideEffect { Lang.sync(strings) }

    // 字号全局缩放：改 fontScale，所有 sp 一起变
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalStrings provides strings,
        LocalDensity provides Density(density.density, density.fontScale * settings.fontScale)
    ) {
        val scheme = if (dark) {
            darkColorScheme(
                primary = colors.brand,
                onPrimary = Color.White,
                background = colors.bg,
                onBackground = colors.text1,
                surface = colors.bg,
                onSurface = colors.text1,
                error = colors.danger
            )
        } else {
            lightColorScheme(
                primary = colors.brand,
                onPrimary = Color.White,
                background = colors.bg,
                onBackground = colors.text1,
                surface = colors.bg,
                onSurface = colors.text1,
                error = colors.danger
            )
        }
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
