package com.dchat.app.ui.tools

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.dchat.app.data.tools.PyProcessRuntime
import com.dchat.app.ui.LocalUrlOpener
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

private const val TAG = "ToolWebScreen"

/** 工具页资源的虚拟 origin：`https://dchat.local/<toolId>/<path>`（自定义拦截，不联网） */
private const val WEB_HOST = "dchat.local"

/** 单文件服务上限（web_write 限 512KB/文件、8MB 总量，这里再兜一层，防异常文件撑爆内存） */
private const val MAX_ASSET_BYTES = 16L * 1024 * 1024

/** 工具页三种状态：web 正常 / web 缺入口 / 旧 v3/v2 已下线 / data 模式无独立页面 / 工具不存在 */
private enum class ToolPageMode { WEB, WEB_MISSING, LEGACY, DATA, NOT_FOUND }

private data class ToolPageInfo(val mode: ToolPageMode, val webRoot: File?)

/**
 * 用户工具独立页面（Web 模式，规范 `docs/spec/tool-web-ui.md`）。
 *
 * - `tool.json` 的 `uiMode == "web"` 且存在 `web/index.html` → 用 WebView 打开；
 * - 资源全部走自定义 `shouldInterceptRequest`：`https://dchat.local/<toolId>/<path>` 映射到
 *   `filesDir/tools/<toolId>/web/<path>`（不引入 androidx.webkit、不加载 file://）；
 * - 除 `dchat.local` 外所有请求返回空 404（**默认禁网**）：外链由 JS/页面点击时经
 *   `shouldOverrideUrlLoading` 交给内置浏览器打开，WebView 本身不跳转；
 * - 旧 `pages`/`schema`（v3/v2）界面已下线：显示可读错误页，提示让模型重建为 web 模式；
 * - `data` 模式没有独立页面（工具结果仍在聊天里按卡片渲染）；
 * - JS ↔ 原生桥见 [ToolWebBridge]（`window.dchat.call/run/saveState/loadState/close`）。
 *
 * @param permission 当前会话权限档位（read/workspace/full），由 MainActivity 从 AppState
 *   当前会话传入；缺失/非法按 read 兜底（不硬编码 full）。
 */
@Composable
fun ToolUiScreen(
    toolId: String,
    onBack: () -> Unit,
    conversationId: String? = null,
    permission: String = ""
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current
    val externalOpener = LocalUrlOpener.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val appContext = remember(ctx) { ctx.applicationContext }

    // 会话权限：只认三档；拿不到/非法时安全回落 read（与 ChatScreen currentPermission() 同一来源）
    val sessionPermission = remember(permission, conversationId) {
        val p = permission.trim().lowercase()
        when {
            p == "read" || p == "workspace" || p == "full" -> p
            else -> {
                Log.w(TAG, "unknown session permission='$permission' conv=${conversationId ?: "-"}, fallback read")
                "read"
            }
        }
    }

    var pageInfo by remember(toolId) { mutableStateOf<ToolPageInfo?>(null) }
    var loadError by remember(toolId) { mutableStateOf<String?>(null) }
    // 首屏遮罩：WebView 创建 + 页面脚本（可能同步等 Python）期间先给用户一个即时反馈
    var pageReady by remember(toolId) { mutableStateOf(false) }
    /** 渲染进程崩溃后 WebView 已不可用：换成占位错误页（重试时才重建 WebView） */
    var webGone by remember(toolId) { mutableStateOf(false) }
    var reloadKey by remember(toolId) { mutableIntStateOf(0) }
    // WebView / 桥：WebView 按 reloadKey 重建；桥按 WebView 实例登记，onRelease 精确取消，避免误伤新页
    val webViewHolder = remember(toolId) { arrayOfNulls<WebView>(1) }
    val bridges = remember(toolId) { HashMap<WebView, ToolWebBridge>() }
    // 页面已退出：晚到的 WebView 回调（loading/close/renderGone）直接丢弃，避免误 pop 其它页面
    val alive = remember(toolId) { booleanArrayOf(true) }

    // 打开耗时埋点：[0]=createMs [1]=loadMs [2]=firstRunMs [3]=已打印(0/1)；-1 = 未发生
    val openStart = remember(toolId) { SystemClock.elapsedRealtime() }
    val timing = remember(toolId) { longArrayOf(-1L, -1L, -1L, 0L) }
    val logTiming: () -> Unit = remember(toolId) {
        {
            if (timing[0] >= 0 && timing[1] >= 0 && timing[2] >= 0 && timing[3] == 0L) {
                timing[3] = 1L
                Log.i(
                    TAG,
                    "web open: create=${timing[0]}ms load=${timing[1]}ms firstRun=${timing[2]}ms"
                )
                // 真机 logcat 在部分 ROM 上看不到应用日志：同时落盘一份，方便 adb run-as 读取测速
                runCatching {
                    java.io.File(appContext.filesDir, "web_perf.log").appendText(
                        "${System.currentTimeMillis()} tool=$toolId create=${timing[0]} load=${timing[1]} firstRun=${timing[2]}\n"
                    )
                }
            }
        }
    }

    LaunchedEffect(toolId) {
        val info = withContext(Dispatchers.IO) { inspectToolPage(ctx, toolId) }
        pageInfo = info
        // 确定是 Web 页后立即预热 Python（non-suspend、立即返回、失败静默），缩短首次 dchat.run 等待
        if (info?.mode == ToolPageMode.WEB) {
            runCatching {
                PyProcessRuntime.warm(
                    context = appContext,
                    toolId = toolId,
                    conversationId = conversationId.orEmpty(),
                    conversationMode = sessionPermission
                )
            }.onFailure { Log.w(TAG, "warm failed tool=$toolId", it) }
        }
    }

    // 系统返回键 = 退出工具页（规范 §4：不交给 WebView 历史）
    BackHandler { onBack() }

    // 工具页是整屏 WebView：状态栏透明、内容画到状态栏下面，图标转白色（工具页背景由页面决定）
    val rootView = LocalView.current
    val hostActivity = remember(rootView) { findHostActivity(rootView.context) }
    DisposableEffect(toolId, hostActivity) {
        val window = hostActivity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, rootView) }
        val prevLight = controller?.isAppearanceLightStatusBars
        if (controller != null) controller.isAppearanceLightStatusBars = false
        onDispose {
            alive[0] = false
            // 兜底：正常路径 AndroidView.onRelease 已销毁；这里处理“未走 onRelease”的极端情况
            bridges.values.forEach { runCatching { it.cancelInFlight() } }
            bridges.clear()
            destroyWebView(webViewHolder[0])
            webViewHolder[0] = null
            // 恢复进入前的状态栏图标主题（聊天/设置页是浅底 → 深色图标）
            if (controller != null && prevLight != null) {
                controller.isAppearanceLightStatusBars = prevLight
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
    ) {
        val info = pageInfo
        when {
            info == null -> ToolStatusPage(L.tools.uiWorking)

            info.mode == ToolPageMode.WEB -> {
                val webRoot = info.webRoot
                if (webRoot == null) {
                    ToolStatusPage("缺少 web 目录：请让模型用 local.web_write 写入 web/index.html 后重试")
                } else {
                    if (!webGone) {
                        key(reloadKey) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { viewCtx ->
                                    createToolWebView(
                                        context = viewCtx,
                                        appContext = appContext,
                                        toolId = toolId,
                                        conversationId = conversationId,
                                        permission = sessionPermission,
                                        webRoot = webRoot,
                                        openExternal = { url -> runCatching { externalOpener(url) } },
                                        onLoadError = { msg ->
                                            mainHandler.post { if (alive[0]) loadError = msg }
                                        },
                                        onRenderGone = {
                                            // 渲染进程崩溃：立即摘掉 WebView、取消桥调用、显示占位错误页（App 不崩）
                                            mainHandler.post {
                                                if (!alive[0]) return@post
                                                webGone = true
                                                loadError = "工具页面已停止（渲染进程崩溃），可重试或返回"
                                                bridges.values.forEach { runCatching { it.cancelInFlight() } }
                                                bridges.clear()
                                            }
                                        },
                                        onCloseRequest = { mainHandler.post { if (alive[0]) onBack() } },
                                        onBridgeCreated = { wv, bridge -> bridges[wv] = bridge },
                                        webViewProvider = { webViewHolder[0] },
                                        onFirstRun = {
                                            mainHandler.post {
                                                if (!alive[0]) return@post
                                                timing[2] = SystemClock.elapsedRealtime() - openStart
                                                logTiming()
                                            }
                                        },
                                        onPageLoaded = {
                                            mainHandler.post {
                                                if (!alive[0]) return@post
                                                pageReady = true
                                                if (timing[1] < 0) {
                                                    timing[1] = SystemClock.elapsedRealtime() - openStart
                                                    logTiming()
                                                    // 页面不调 Python 时 firstRun 永远为 -1：加载后 2.5s 兜底落盘一次
                                                    mainHandler.postDelayed({
                                                        if (alive[0] && timing[3] == 0L) {
                                                            timing[3] = 1L
                                                            runCatching {
                                                                java.io.File(
                                                                    appContext.filesDir,
                                                                    "web_perf.log"
                                                                ).appendText(
                                                                    "${System.currentTimeMillis()} tool=$toolId " +
                                                                        "create=${timing[0]} load=${timing[1]} firstRun=${timing[2]}\n"
                                                                )
                                                            }
                                                        }
                                                    }, 2500L)
                                                }
                                            }
                                        }
                                    ).also { wv ->
                                        webViewHolder[0] = wv
                                        timing[0] = SystemClock.elapsedRealtime() - openStart
                                        logTiming()
                                    }
                                },
                                onRelease = { wv ->
                                    if (webViewHolder[0] === wv) webViewHolder[0] = null
                                    bridges.remove(wv)?.let { runCatching { it.cancelInFlight() } }
                                    destroyWebView(wv)
                                }
                            )
                        }
                    }
                    val err = loadError
                    if (err != null) {
                        ToolErrorOverlay(
                            message = err,
                            retryText = L.tools.uiRetry,
                            closeText = L.common.close,
                            onRetry = {
                                loadError = null
                                webGone = false
                                pageReady = false
                                reloadKey++
                            },
                            onClose = onBack
                        )
                    }
                }
            }

            info.mode == ToolPageMode.LEGACY -> ToolStatusPage(
                "该工具界面已升级，请让模型重建为 web 模式"
            )

            info.mode == ToolPageMode.WEB_MISSING -> ToolStatusPage(
                "缺少 web/index.html：请让模型用 local.web_write 写入页面后重试"
            )

            info.mode == ToolPageMode.DATA -> ToolStatusPage(
                "该工具没有独立页面（数据模式），工具运行结果会显示在聊天里"
            )

            else -> ToolStatusPage("工具不存在或已被删除：$toolId")
        }

        // 首屏遮罩：WebView 创建 + 页面脚本执行（可能同步等 Python）期间先显示加载态
        if (info?.mode == ToolPageMode.WEB && !pageReady && loadError == null) {
            Box(
                Modifier.fillMaxSize().background(c.bg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        color = c.text2,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(L.tools.uiWorking, fontSize = 13.sp, color = c.text2)
                    Spacer(Modifier.height(6.dp))
                    Text("首次打开稍慢，之后会更快", fontSize = 11.sp, color = c.text3)
                }
            }
        }
        // 右上角浮动 ×（同现有工具页样式：40dp 圆形 + 半透明底 + 白 ×）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 12.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = L.common.close,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ 状态页 / 错误浮层

@Composable
private fun ToolStatusPage(text: String) {
    val c = LocalColors.current
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 12.5.sp,
            color = c.text3,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}

@Composable
private fun ToolErrorOverlay(
    message: String,
    retryText: String,
    closeText: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    val c = LocalColors.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(c.bg)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                message,
                fontSize = 12.5.sp,
                color = c.text2,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = onRetry) {
                    Text(retryText, fontSize = 13.sp, color = c.brand)
                }
                TextButton(onClick = onClose) {
                    Text(closeText, fontSize = 13.sp, color = c.text3)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 页面状态探测

/** 读 tool.json 原始 uiMode + web/index.html 是否存在；旧 ui.json / ui/manifest.json 视为 legacy */
private fun inspectToolPage(ctx: Context, toolId: String): ToolPageInfo {
    val dir = runCatching { com.dchat.app.data.tools.UserToolStore.toolDir(ctx, toolId) }.getOrNull()
        ?: return ToolPageInfo(ToolPageMode.NOT_FOUND, null)
    if (!dir.isDirectory) return ToolPageInfo(ToolPageMode.NOT_FOUND, null)
    val meta = File(dir, "tool.json")
    if (!meta.isFile) return ToolPageInfo(ToolPageMode.NOT_FOUND, null)
    val uiMode = runCatching { JSONObject(meta.readText()).optString("uiMode").trim() }
        .getOrDefault("")
    val webRoot = File(dir, "web")
    val hasIndex = File(webRoot, "index.html").isFile
    val legacyFiles = File(dir, "ui.json").isFile || File(File(dir, "ui"), "manifest.json").isFile
    val mode = when {
        uiMode == "web" && hasIndex -> ToolPageMode.WEB
        uiMode == "web" -> ToolPageMode.WEB_MISSING
        uiMode == "schema" || uiMode == "pages" -> ToolPageMode.LEGACY
        legacyFiles -> ToolPageMode.LEGACY
        else -> ToolPageMode.DATA
    }
    return ToolPageInfo(mode, webRoot)
}

// ------------------------------------------------------------------ WebView 构建

private fun findHostActivity(context: Context): Activity? {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
private fun createToolWebView(
    context: Context,
    appContext: Context,
    toolId: String,
    conversationId: String?,
    permission: String,
    webRoot: File,
    openExternal: (String) -> Unit,
    onLoadError: (String) -> Unit,
    onRenderGone: () -> Unit,
    onCloseRequest: () -> Unit,
    onBridgeCreated: (WebView, ToolWebBridge) -> Unit,
    webViewProvider: () -> WebView?,
    onFirstRun: () -> Unit,
    onPageLoaded: () -> Unit
): WebView {
    val wv = WebView(context)
    wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    wv.isVerticalScrollBarEnabled = false
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        setGeolocationEnabled(false)
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = false
        displayZoomControls = false
        setSupportZoom(false)
        cacheMode = WebSettings.LOAD_NO_CACHE
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        textZoom = 100
    }
    val canonicalRoot = runCatching { webRoot.canonicalFile }.getOrDefault(webRoot)
    wv.webViewClient = ToolWebViewClient(
        toolId = toolId,
        webRoot = canonicalRoot,
        openExternal = openExternal,
        onLoadError = onLoadError,
        onRenderGone = onRenderGone,
        onPageLoaded = onPageLoaded
    )
    wv.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            // JS 异常 / console.error：只记日志，不弹窗、不崩
            Log.d(
                TAG,
                "console[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                    "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
            )
            return true
        }

        /** 禁止 window.open / target=_blank 新窗口 */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?
        ): Boolean = false

        /** JS alert/confirm/prompt 一律静默（只记日志），避免页面卡住或弹系统框 */
        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            Log.d(TAG, "js alert suppressed: $message")
            runCatching { result?.confirm() }
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?
        ): Boolean {
            Log.d(TAG, "js confirm suppressed (cancel): $message")
            runCatching { result?.cancel() }
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult?
        ): Boolean {
            Log.d(TAG, "js prompt suppressed: $message")
            runCatching { result?.cancel() }
            return true
        }
    }
    val bridge = ToolWebBridge(
        appContext = appContext,
        toolId = toolId,
        conversationId = conversationId,
        permission = permission,
        webViewProvider = webViewProvider,
        onClose = onCloseRequest,
        onFirstRun = onFirstRun
    )
    onBridgeCreated(wv, bridge)
    wv.addJavascriptInterface(bridge, ToolWebBridge.NAME)
    // 入口固定 index.html；页面内多页由前端 hash 路由实现
    val entryUrl = "https://$WEB_HOST/${Uri.encode(toolId)}/index.html"
    wv.loadUrl(entryUrl)
    return wv
}

/**
 * 销毁 WebView（主线程）：停加载 → about:blank → 断桥 → 从容器移除 → destroy，
 * 避免后台继续跑 JS / 泄漏 / 退出后崩溃。可重复调用（内部全部 runCatching）。
 */
private fun destroyWebView(wv: WebView?) {
    if (wv == null) return
    runCatching { wv.stopLoading() }
    runCatching { wv.loadUrl("about:blank") }
    runCatching { wv.removeJavascriptInterface(ToolWebBridge.NAME) }
    runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
    runCatching { wv.destroy() }
}

// ------------------------------------------------------------------ WebViewClient（本地资源 + 默认禁网）

private class ToolWebViewClient(
    private val toolId: String,
    private val webRoot: File,
    private val openExternal: (String) -> Unit,
    private val onLoadError: (String) -> Unit,
    private val onRenderGone: () -> Unit,
    private val onPageLoaded: () -> Unit
) : WebViewClient() {

    /**
     * 只放行 `https://dchat.local/<toolId>/<path>` → 工具 `web/` 目录；其它所有请求（外网、
     * 其它工具、file://、http）返回空 404 —— **默认禁网**，且不能跨工具读文件。
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse {
        val url = request?.url ?: return notFoundResponse()
        if (url.scheme?.lowercase() != "https" || url.host != WEB_HOST) return notFoundResponse()
        val segments = runCatching { url.pathSegments }.getOrNull() ?: return notFoundResponse()
        if (segments.size < 2 || segments[0] != toolId) return notFoundResponse()
        val rel = segments.drop(1).joinToString("/")
        if (rel.isBlank() || rel.startsWith("/") || rel.contains("..") || rel.contains('\\')) {
            return notFoundResponse()
        }
        val root = runCatching { webRoot.canonicalFile }.getOrNull() ?: return notFoundResponse()
        val target = runCatching { File(root, rel).canonicalFile }.getOrNull() ?: return notFoundResponse()
        if (!target.path.startsWith(root.path + File.separator) || !target.isFile) {
            return notFoundResponse()
        }
        if (target.length() > MAX_ASSET_BYTES) {
            Log.w(TAG, "asset too large, deny: ${target.name} (${target.length()} bytes)")
            return notFoundResponse()
        }
        val mime = mimeOf(target.name)
        return runCatching {
            WebResourceResponse(
                mime,
                charsetOf(mime),
                200,
                "OK",
                mapOf(
                    "Cache-Control" to "no-store, no-cache",
                    "Access-Control-Allow-Origin" to "*"
                ),
                target.inputStream()
            )
        }.getOrElse {
            Log.w(TAG, "open asset failed: ${target.absolutePath}", it)
            notFoundResponse()
        }
    }

    /** http/https 外链 → 内置浏览器；本地同源导航交给拦截器；其它 scheme 一律阻止 */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return true
        val scheme = url.scheme?.lowercase() ?: return true
        if (scheme != "http" && scheme != "https") return true
        if (scheme == "https" && url.host == WEB_HOST) return false
        runCatching { openExternal(url.toString()) }
            .onFailure { Log.w(TAG, "open external failed: $url", it) }
        return true
    }

    /** 页面加载完成：注入状态栏高度 CSS 变量 + 记录 “web open: load=…ms” 计时（仅主页面） */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url?.startsWith("https://$WEB_HOST/") == true) {
            runCatching { onPageLoaded() }
        }
        val ctx = view?.context ?: return
        val res = ctx.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val px = if (id > 0) res.getDimensionPixelSize(id) else 0
        val dp = if (res.displayMetrics.density > 0f) (px / res.displayMetrics.density) else 0f
        runCatching {
            view.evaluateJavascript(
                "document.documentElement.style.setProperty('--dchat-sb','${dp}px');",
                null
            )
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame != true) return
        val detail = runCatching { error?.description?.toString() }.getOrNull()
        Log.w(TAG, "main frame load error: $detail")
        onLoadError("页面加载失败：${detail ?: "网络/资源不可用"}。可重试或退出")
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        if (request?.isForMainFrame != true) return
        val code = runCatching { errorResponse?.statusCode }.getOrNull() ?: 0
        if (code >= 400) {
            Log.w(TAG, "main frame http error: $code")
            onLoadError("页面加载失败（HTTP $code）：资源可能缺失，可重试或退出")
        }
    }

    /**
     * 渲染进程崩溃（OOM/引擎崩）：**必须处理并 return true**，否则系统会杀掉整个 App 进程。
     * 这里销毁该 WebView、回调宿主换成占位错误页（可重试/返回），App 不崩。
     */
    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        Log.w(TAG, "web render process gone, didCrash=${runCatching { detail?.didCrash() }.getOrNull()}")
        runCatching {
            view?.stopLoading()
            view?.removeJavascriptInterface(ToolWebBridge.NAME)
            (view?.parent as? ViewGroup)?.removeView(view)
            view?.destroy()
        }
        runCatching { onRenderGone() }
        return true
    }
}

// ------------------------------------------------------------------ 资源小工具

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "js", "mjs" -> "application/javascript"
    "css" -> "text/css"
    "json" -> "application/json"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "ico" -> "image/x-icon"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "wasm" -> "application/wasm"
    "txt", "md" -> "text/plain"
    else -> "application/octet-stream"
}

private fun charsetOf(mime: String): String? = when {
    mime.startsWith("text/") -> "utf-8"
    mime == "application/javascript" -> "utf-8"
    mime == "application/json" -> "utf-8"
    mime == "image/svg+xml" -> "utf-8"
    else -> null
}

/** 空 404：默认禁网/拦截不匹配时的统一响应（不会泄漏任何文件内容） */
private fun notFoundResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    404,
    "Not Found",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0))
)
