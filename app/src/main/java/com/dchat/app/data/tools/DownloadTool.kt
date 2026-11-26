package com.dchat.app.data.tools

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** local.download：仅 HTTPS，落当前会话工作区，不跨会话。 */
object DownloadTool {
    private const val TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5

    fun run(context: Context, argsJson: String, conversationId: String): PyResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return PyResult(false, errorCode = "INVALID_ARGS", errorMessage = "参数不是合法 JSON")
        }
        val rawUrl = args.optString("url").trim()
        if (rawUrl.isEmpty()) {
            return PyResult(false, errorCode = "INVALID_ARGS", errorMessage = "url 不能为空")
        }
        if (!rawUrl.startsWith("https://", ignoreCase = true)) {
            return PyResult(false, errorCode = "HTTPS_ONLY", errorMessage = "只允许 HTTPS 下载")
        }
        val subdir = sanitizeDir(args.optString("subdir").ifBlank { "downloads" })
        val filenameArg = args.optString("filename").trim()
        var conn: HttpURLConnection? = null
        return try {
            var url = rawUrl
            var redirects = 0
            while (true) {
                val c = (URL(url).openConnection() as? HttpURLConnection)
                    ?: return PyResult(false, errorCode = "DOWNLOAD_FAILED", errorMessage = "不支持的 URL")
                conn = c
                c.instanceFollowRedirects = false
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("User-Agent", "DChat/1.0")
                c.connect()
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank() || redirects++ >= MAX_REDIRECTS) {
                        return PyResult(false, errorCode = "TOO_MANY_REDIRECTS", errorMessage = "重定向次数过多")
                    }
                    url = URL(URL(url), loc).toString()
                    if (!url.startsWith("https://", ignoreCase = true)) {
                        return PyResult(false, errorCode = "HTTPS_ONLY", errorMessage = "重定向到了非 HTTPS 地址")
                    }
                    continue
                }
                if (code !in 200..299) {
                    return PyResult(false, errorCode = "HTTP_$code", errorMessage = "下载失败：HTTP $code")
                }
                break
            }
            val c = conn!!
            val mime = c.contentType?.substringBefore(';')?.trim().orEmpty()
                .ifBlank { mimeFor(filenameArg.ifBlank { File(URL(url).path).name }) }
            val dir = File(FileTools.workspaceDir(context, conversationId), subdir)
            if (!dir.exists() && !dir.mkdirs()) {
                return PyResult(false, errorCode = "DOWNLOAD_FAILED", errorMessage = "无法创建工作区目录")
            }
            val suggested = filenameArg.ifBlank {
                URL(url).path.substringAfterLast('/').ifBlank { "download" }
            }
            val safe = sanitizeFilename(suggested)
            val out = uniqueFile(dir, safe)
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            val size = out.length()
            val display = out.relativeTo(FileTools.workspaceDir(context, conversationId)).path
            val mb = size / 1024.0 / 1024.0
            val summary = if (size >= 1024 * 1024) {
                "已下载 ${out.name}（%.1fMB）→ %s".format(Locale.ROOT, mb, display)
            } else {
                "已下载 ${out.name}（${size / 1024}KB）→ $display"
            }
            PyResult(
                ok = true,
                output = summary,
                filePath = out.absolutePath,
                mime = mime,
                sizeBytes = size,
                downloadStatus = "ok"
            )
        } catch (t: Throwable) {
            PyResult(
                false, errorCode = "DOWNLOAD_FAILED",
                errorMessage = t.message ?: t.javaClass.simpleName,
                downloadStatus = "failed"
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun sanitizeDir(raw: String): String {
        val parts = raw.replace('\\', '/').split('/')
            .map { it.replace(Regex("[\\x00-\\x1f]"), "").trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        return parts.take(3).joinToString("/").ifBlank { "downloads" }
    }

    private fun sanitizeFilename(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/')
            .replace(Regex("[\\x00-\\x1f]"), "")
            .replace(Regex("[/\\\\]"), "_")
            .trim().trim('.')
        return base.take(120).ifBlank { "download" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(dir, "${stem}_$i$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
