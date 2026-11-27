package com.dchat.app.data.tools

import android.content.Context
import com.dchat.app.ui.lang.AppLanguage
import com.dchat.app.ui.lang.Lang
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 1.0.3：客户端搜索/阅读工具（/v1/tools/search、/v1/tools/fetch）。 */
object WebTools {
    const val SEARCH = "local.web_search"
    const val FETCH = "local.web_fetch"
    private const val TIMEOUT = 60_000
    private const val FETCH_LIMIT = 64 * 1024

    fun run(context: Context, toolId: String, argsJson: String, conversationId: String): PyResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return fail(WebErr.invalid(lang()), "INVALID_ARGS")
        }
        return when (toolId) {
            SEARCH -> search(context, args)
            FETCH -> fetch(context, args, conversationId)
            else -> PyResult(false, errorCode = "TOOL_NOT_FOUND", errorMessage = "Unknown web tool")
        }
    }

    private fun credentials(context: Context): Pair<String, String> {
        val p = context.getSharedPreferences("dchat_settings", Context.MODE_PRIVATE)
        return (p.getString("baseUrl", "https://api.deepseek.com") ?: "https://api.deepseek.com") to
            (p.getString("apiKey", "") ?: "")
    }

    private fun search(context: Context, args: JSONObject): PyResult {
        val q = args.optString("query").trim()
        if (q.isEmpty() || q.length > 128) {
            return fail(WebErr.invalid(lang()), "INVALID_ARGS")
        }
        val count = args.optInt("count", 10).coerceIn(1, 50)
        val needContent = args.optBoolean("need_content", false)
        val (base, key) = credentials(context)
        if (key.isBlank()) return fail(WebErr.noKey(lang()), "NO_API_KEY")
        val body = JSONObject().put("query", q).put("count", count).put("need_content", needContent)
        val r = post("$base/v1/tools/search", key, body)
        if (!r.ok) return fail(r.message, r.code)
        val root = r.json ?: return fail(WebErr.server(lang()), "SEARCH_FAILED")
        val arr = root.optJSONArray("results") ?: root.optJSONArray("data")
            ?: root.optJSONArray("items") ?: JSONArray()
        if (arr.length() == 0) return fail(WebErr.empty(lang()), "NO_RESULTS")
        val sb = StringBuilder()
        val sources = ArrayList<com.dchat.app.data.Source>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").ifBlank { o.optString("name") }
            val url = o.optString("url").ifBlank { o.optString("link") }
            val summary = o.optString("summary").ifBlank { o.optString("snippet") }
                .ifBlank { o.optString("description") }
            val date = o.optString("date").ifBlank { o.optString("published_at") }
            val site = o.optString("site_name").ifBlank { o.optString("site") }
            sources.add(com.dchat.app.data.Source(title = title, url = url, site = site.ifBlank { null }, snippet = summary.trimStart('#', ' ', '-', '*').trim().ifBlank { null }))
            sb.append("${i + 1}. $title\n")
            sb.append("   URL: $url\n")
            if (site.isNotBlank()) sb.append("   站点: $site\n")
            if (date.isNotBlank()) sb.append("   日期: $date\n")
            if (summary.isNotBlank()) sb.append(summary.take(800)).append('\n')
            if (needContent) {
                val content = o.optString("content")
                    .ifBlank { o.optString("markdown") }
                    .ifBlank { o.optString("text") }
                if (content.isNotBlank()) sb.append("   content: ").append(content.take(8000)).append('\n')
            }
            sb.append('\n')
        }
        return PyResult(true, sb.toString().trim(), sources = sources)
    }

    private fun fetch(context: Context, args: JSONObject, conversationId: String): PyResult {
        val url = args.optString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.length > 8192) {
            return fail(WebErr.invalid(lang()), "INVALID_ARGS")
        }
        val (base, key) = credentials(context)
        if (key.isBlank()) return fail(WebErr.noKey(lang()), "NO_API_KEY")
        val r = post("$base/v1/tools/fetch", key, JSONObject().put("url", url))
        if (!r.ok) return fail(r.message, r.code)
        val root = r.json ?: return fail(WebErr.server(lang()), "FETCH_FAILED")
        val title = root.optString("title").ifBlank { url }
        val content = root.optString("content").ifBlank { root.optString("markdown") }
            .ifBlank { root.optString("text") }
        if (content.isBlank()) return fail(WebErr.empty(lang()), "NO_CONTENT")
        val head = content.take(FETCH_LIMIT)
        val oneSource = listOf(
            com.dchat.app.data.Source(title = title, url = url, snippet = head.take(400))
        )
        if (content.length <= FETCH_LIMIT) {
            return PyResult(true, "标题: $title\n\n$head", sources = oneSource)
        }
        return runCatching {
            val host = runCatching { URL(url).host }.getOrDefault("web")
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dir = File(FileTools.workspaceDir(context, conversationId), "web")
            dir.mkdirs()
            val out = File(dir, "$host-$ts.md")
            out.writeText(content)
            PyResult(
                true,
                "标题: $title\n\n${head}\n\n[内容超过 64KB，完整内容已保存：${out.relativeTo(FileTools.workspaceDir(context, conversationId)).path}]",
                sources = oneSource
            )
        }.getOrElse { fail(WebErr.server(lang()), "FETCH_SAVE_FAILED") }
    }

    private data class Resp(
        val ok: Boolean,
        val code: String,
        val message: String,
        val json: JSONObject?,
        val retryable: Boolean = false
    )

    private fun post(url: String, key: String, body: JSONObject): Resp {
        var last: Resp? = null
        for (attempt in 0..1) {
            val r = postOnce(url, key, body)
            last = r
            if (r.ok || !r.retryable) return r
            if (attempt == 0) Thread.sleep(400)
        }
        return last!!
    }

    private fun postOnce(url: String, key: String, body: JSONObject): Resp {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = TIMEOUT
            c.readTimeout = TIMEOUT
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299) {
                Resp(true, "", "", json)
            } else {
                val msg = json?.optString("error")?.ifBlank { json.optJSONObject("error")?.optString("message").orEmpty() }
                    .orEmpty()
                val user = when (code) {
                    400 -> WebErr.invalid(lang())
                    401 -> WebErr.auth(lang())
                    402 -> WebErr.balance(lang())
                    403 -> WebErr.forbidden(lang())
                    404 -> WebErr.notFound(lang())
                    408, 429 -> WebErr.retry(lang())
                    in 500..599 -> WebErr.server(lang())
                    else -> WebErr.http(lang(), code)
                }
                Resp(
                    false, "HTTP_$code",
                    if (msg.isBlank()) user else "$user（$msg）",
                    json,
                    retryable = code == 408 || code == 429 || code in 500..599
                )
            }
        } catch (t: Throwable) {
            Resp(false, "NETWORK", "${WebErr.network(lang())}：${t.message ?: t.javaClass.simpleName}", null)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun fail(message: String, code: String) = PyResult(false, errorCode = code, errorMessage = message)
    private fun lang(): AppLanguage = Lang.current.language
}

/** 网络工具四语言错误文案（不依赖 ui/lang，便于独立维护）。 */
object WebErr {
    fun noKey(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "未配置 API Key，请先到设置 → API 配置"
        AppLanguage.EN -> "API key is not configured; open Settings → API"
        AppLanguage.JA -> "API キーが未設定です。設定 → API を開いてください"
        AppLanguage.KO -> "API 키가 없습니다. 설정 → API에서 구성하세요"
    }
    fun invalid(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "参数不合法，请检查 query/count/url"
        AppLanguage.EN -> "Invalid parameters; check query/count/url"
        AppLanguage.JA -> "パラメータが不正です（query/count/url）"
        AppLanguage.KO -> "잘못된 매개변수입니다(query/count/url)"
    }
    fun auth(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "API Key 无效或已过期"
        AppLanguage.EN -> "The API key is invalid or expired"
        AppLanguage.JA -> "API キーが無効または期限切れです"
        AppLanguage.KO -> "API 키가 유효하지 않거나 만료되었습니다"
    }
    fun balance(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "余额不足，请充值后再试"
        AppLanguage.EN -> "Insufficient balance; top up and retry"
        AppLanguage.JA -> "残高不足です。チャージ後にもう一度お試しください"
        AppLanguage.KO -> "잔액이 부족합니다. 충전 후 다시 시도하세요"
    }
    fun forbidden(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "目标网页被安全策略拒绝"
        AppLanguage.EN -> "The target page was blocked by the safety policy"
        AppLanguage.JA -> "対象ページは安全ポリシーにより拒否されました"
        AppLanguage.KO -> "대상 페이지가 안전 정책에 의해 차단되었습니다"
    }
    fun notFound(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "没有可用内容（404）"
        AppLanguage.EN -> "No usable content (404)"
        AppLanguage.JA -> "利用可能な内容がありません（404）"
        AppLanguage.KO -> "사용 가능한 콘텐츠가 없습니다(404)"
    }
    fun retry(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "服务繁忙或频率限制，已重试一次仍失败"
        AppLanguage.EN -> "Service busy/rate-limited; retried once and still failed"
        AppLanguage.JA -> "サービス混雑またはレート制限。1 回再試行しても失敗しました"
        AppLanguage.KO -> "서비스 혼잡/요청 제한입니다. 한 번 재시도했지만 실패했습니다"
    }
    fun server(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "服务端暂时不可用，请稍后再试"
        AppLanguage.EN -> "The service is temporarily unavailable; retry later"
        AppLanguage.JA -> "サーバーが一時的に利用できません。後で再試行してください"
        AppLanguage.KO -> "서버를 일시적으로 사용할 수 없습니다. 나중에 다시 시도하세요"
    }
    fun network(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "网络连接失败"
        AppLanguage.EN -> "Network connection failed"
        AppLanguage.JA -> "ネットワーク接続に失敗しました"
        AppLanguage.KO -> "네트워크 연결 실패"
    }
    fun http(l: AppLanguage, code: Int) = when (l) {
        AppLanguage.ZH -> "请求失败（HTTP $code）"
        AppLanguage.EN -> "Request failed (HTTP $code)"
        AppLanguage.JA -> "リクエスト失敗（HTTP $code）"
        AppLanguage.KO -> "요청 실패(HTTP $code)"
    }
    fun empty(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "没有找到内容"
        AppLanguage.EN -> "No content found"
        AppLanguage.JA -> "内容が見つかりません"
        AppLanguage.KO -> "콘텐츠를 찾을 수 없습니다"
    }
}
