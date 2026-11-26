package com.dchat.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** 打开链接的统一入口（内置浏览器） */
val LocalUrlOpener = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }
