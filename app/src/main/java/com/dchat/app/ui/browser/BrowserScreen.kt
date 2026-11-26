package com.dchat.app.ui.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dchat.app.data.AppState
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.theme.LocalColors

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(app: AppState, url: String, onBack: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var address by remember(url) { mutableStateOf(url) }
    var showInput by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(url) }

    fun go(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) {
            u = if (u.contains(".")) "https://$u"
            else "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(u, "UTF-8")
        }
        address = u
        webView?.loadUrl(u)
    }

    // 先退网页历史，退不动了才退出浏览器
    BackHandler(enabled = canGoBack) {
        webView?.let { wv ->
            if (wv.canGoBack()) wv.goBack()
            canGoBack = wv.canGoBack()
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Rounded.ArrowBackIosNew, onBack, iconSize = 18.dp)
            Spacer(Modifier.width(4.dp))
            // 整条地址栏可点，点开弹输入框（对话框一定能拿到键盘）
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.bg3)
                    .clickable {
                        draft = address
                        showInput = true
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    address.replace("https://", "").replace("http://", ""),
                    fontSize = 13.sp,
                    color = c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
            IconBtn(Icons.Rounded.Refresh, { webView?.reload() }, iconSize = 18.dp)
        }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setBackgroundColor(android.graphics.Color.WHITE)
                    // 去掉 UA 里的 WebView 标记，很多站点（含百度跳转页）见到就返回空页
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.loadsImagesAutomatically = true
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    // 硬件层渲染，滚动更顺
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                    overScrollMode = android.view.View.OVER_SCROLL_NEVER
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            canGoBack = view.canGoBack()
                            u?.let { address = it }
                        }

                        // 拦截 baiduboxapp:// 这类自定义 scheme，取出内层真实地址
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest
                        ): Boolean = handleScheme(view, request.url.toString())

                        @Deprecated("old api")
                        override fun shouldOverrideUrlLoading(view: WebView, u: String?): Boolean {
                            if (u == null) return false
                            return handleScheme(view, u)
                        }

                        private fun handleScheme(view: WebView, u: String): Boolean {
                            if (u.startsWith("http://") || u.startsWith("https://")) return false
                            val inner = runCatching {
                                android.net.Uri.parse(u).getQueryParameter("url")
                            }.getOrNull()
                            if (!inner.isNullOrBlank() && inner.startsWith("http")) {
                                view.loadUrl(inner)
                            }
                            return true
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            }
        )
        Spacer(Modifier.navigationBarsPadding())
    }

    if (showInput) {
        AlertDialog(
            onDismissRequest = { showInput = false },
            containerColor = c.bg,
            title = { Text(L.misc.browserEnterUrl) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.bg3)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        if (draft.isEmpty()) {
                            Text(L.misc.browserUrlHint, fontSize = 13.5.sp, color = c.text3)
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = c.text1),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        L.misc.browserSearchHint,
                        fontSize = 11.sp,
                        color = c.text3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    go(draft)
                    showInput = false
                }) { Text(L.misc.browserGo) }
            },
            dismissButton = {
                TextButton(onClick = { showInput = false }) { Text(L.common.cancel) }
            }
        )
    }
}
