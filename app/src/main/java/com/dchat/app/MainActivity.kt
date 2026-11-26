package com.dchat.app

import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dchat.app.data.AppState
import com.dchat.app.data.tools.BgTasks
import com.dchat.app.ui.chat.ChatScreen
import com.dchat.app.ui.files.SessionFilesScreen
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.settings.BackgroundScreen
import com.dchat.app.ui.settings.SettingsScreen
import com.dchat.app.ui.tasks.BgTaskScreen
import com.dchat.app.ui.tasks.ScheduleTaskScreen
import com.dchat.app.ui.theme.DchatTheme
import com.dchat.app.ui.tools.ToolUiScreen
import com.dchat.app.ui.usage.UsageScreen

enum class Screen { Chat, Settings, Background, Browser, Usage, ToolUi, BgTasks, Schedule, Files }

class MainActivity : ComponentActivity() {

    // 前台/后台可见性：可见时后台任务只在进程内跑，不起前台服务
    // （避免主线程繁忙时 startForegroundService 5 秒看门狗超时，见 BgTasks.setAppVisible）
    override fun onStart() {
        super.onStart()
        BgTasks.setAppVisible(this, true)
    }

    override fun onStop() {
        BgTasks.setAppVisible(this, false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = AppState(applicationContext)

        setContent {
            val stack = remember { mutableStateListOf<Screen>() }
            val current = stack.lastOrNull() ?: Screen.Chat
            var browserUrl by remember { mutableStateOf("https://www.bing.com") }
            var toolUiTarget by remember { mutableStateOf<String?>(null) }
            // 「当前会话的文件」页面：打开时固定传当前会话 id，页面内不再提供切换会话入口
            var filesConversationId by remember { mutableStateOf<String?>(null) }
            /** ChatScreen 通过 onOpenFiles(conversationId) 调用（签名见交付说明） */
            val openFiles: (String) -> Unit = { convId ->
                filesConversationId = convId
                stack.add(Screen.Files)
            }

            val settings = app.settings
            // 返回键提示在 DchatTheme 之外注册（要先于浮层），所以这里自己取一份当前文案
            val strings = Lang.of(settings.language, settings.region)
            val dark = settings.darkTheme
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            }

            // 先注册页面级返回，后注册的浮层（抽屉/面板/弹窗）会优先生效
            BackHandler(enabled = stack.isNotEmpty()) {
                stack.removeAt(stack.lastIndex)
            }
            var lastBackAt by remember { mutableStateOf(0L) }
            BackHandler(enabled = stack.isEmpty()) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt <= 2000L) finish()
                else {
                    lastBackAt = now
                    Toast.makeText(this@MainActivity, strings.common.exitConfirm, Toast.LENGTH_SHORT).show()
                }
            }

            DchatTheme(settings) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.dchat.app.ui.LocalUrlOpener provides { u: String ->
                        browserUrl = u
                        stack.add(Screen.Browser)
                    }
                ) {
                AnimatedContent(
                    targetState = current,
                    transitionSpec = {
                        val pushing = targetState.ordinal > initialState.ordinal
                        if (pushing) {
                            // 新页面从左侧滑入
                            (slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300)))
                                .togetherWith(fadeOut(tween(180)))
                        } else {
                            // 返回：从右往左缩回去
                            fadeIn(tween(180)).togetherWith(
                                slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(300))
                            )
                        }
                    },
                    label = "screen"
                ) { screen ->
                    when (screen) {
                        Screen.Chat -> ChatScreen(
                            app = app,
                            onOpenSettings = { stack.add(Screen.Settings) },
                            onOpenUsage = { stack.add(Screen.Usage) },
                            onOpenBgTasks = { stack.add(Screen.BgTasks) },
                            onOpenSchedule = { stack.add(Screen.Schedule) },
                            onOpenFiles = openFiles
                        )

                        Screen.BgTasks -> BgTaskScreen(
                            app = app,
                            conversationId = app.currentId,
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        )

                        Screen.Schedule -> ScheduleTaskScreen(
                            app = app,
                            conversationId = app.currentId,
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        )

                        Screen.Files -> SessionFilesScreen(
                            conversationId = filesConversationId ?: app.currentId,
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        )

                        Screen.Usage -> UsageScreen(
                            app = app,
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        )

                        Screen.Settings -> SettingsScreen(
                            app = app,
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) },
                            onOpenBackground = { stack.add(Screen.Background) },
                            onOpenToolUi = { toolId ->
                                toolUiTarget = toolId
                                stack.add(Screen.ToolUi)
                            }
                        )

                        Screen.ToolUi -> ToolUiScreen(
                            toolId = toolUiTarget.orEmpty(),
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        )

                        Screen.Background -> BackgroundScreen(
                            app = app,
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        )

                        Screen.Browser -> com.dchat.app.ui.browser.BrowserScreen(
                            app = app,
                            url = browserUrl,
                            onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
                        )
                    }
                }
                }
            }
        }
    }
}
