package com.dchat.app.data.tools

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.dchat.app.ui.lang.ImageGenStr
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * `local.imagegen`：移植 DSH 插件 `@little-traincar/dsh-imagegen` 的原生生图工具。
 *
 * 能力与限制（与任务书 / DSH 对齐）：
 * - 通道：doubao（Seedream）/ qwen（DashScope 兼容）/ 自定义 OpenAI 兼容端点；
 * - 尺寸按通道映射；请求字段按通道能力裁剪（watermark / prompt_extend / seed / negative_prompt / image）；
 * - 响应兼容**二进制图片 / b64_json / 远程 url** 三种；
 * - 429/5xx 指数退避最多 3 次尝试；单张下载 ≤32MB；严格 PNG/JPEG 魔数校验；
 * - 图片 URL 逐跳重定向校验（≤3 跳），拒绝内网/回环/链路本地地址（169.254/16 等）；
 *   生图端点本身是内网/本机时放行（本地部署场景）；
 * - 多张里部分失败不丢成功图；文件名 `images/gen-<yyyyMMdd-HHmmss>-<i>.<ext>`；
 * - 自定义通道额外要求会话「完全权限」。
 *
 * 出错信息会回填给模型并显示在失败卡片上，全部走 [ImageGenStr] 四语言文案。
 */
object ImageGenTool {

    const val TOOL_ID = "local.imagegen"

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BASE_MS = 400L
    private val RETRYABLE = setOf(429, 500, 502, 503, 504)
    private val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)

    /** 单张远程图片下载上限（任务书 §5.3） */
    private const val MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024
    /** 生图响应体上限（b64 单张最多 ~48MB，留出 JSON 包装余量） */
    private const val MAX_RESPONSE_BYTES = 64L * 1024 * 1024
    private const val MAX_INLINE_B64_CHARS = 64L * 1024 * 1024
    private const val MAX_DOWNLOAD_REDIRECTS = 3
    private const val MAX_REDIRECTS_POST = 2
    private const val ERROR_BODY_MAX = 4L * 1024
    private const val MAX_ERROR_TEXT = 300

    private const val MAX_PROMPT = 4000
    private const val MAX_TEXT_LINES = 20
    private const val MAX_TEXT_LINE_LEN = 200
    private const val MAX_SEED = 2_147_483_647L
    private const val MAX_MODEL_LEN = 200
    private const val MAX_IMAGE_INPUT_BYTES = 10L * 1024 * 1024
    private const val MAX_IMAGE_DATA_URI = 15 * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val IMAGE_READ_TIMEOUT_MS = 60_000

    private const val QUALITY_SUFFIX =
        "\n画面要求：专业级商业设计，构图完整、光影自然、细节精致、色彩协调；风格与主色调以用户指定为准。"
    private const val SAME_NEGATIVE_PROMPT =
        "低分辨率、模糊、畸变、肢体错误、多余手指、文字乱码或错字、水印、重复元素、低质量"

    /**
     * 执行一次生图调用。由 [LocalToolRunner] 在 IO 线程调用；本函数自己也会切 IO。
     */
    suspend fun run(
        context: Context,
        argsJson: String,
        conversationId: String,
        conversationMode: String,
        /** 用户已停止生成：在每张落盘前校验，避免停止后留下「幽灵文件」 */
        isCancelled: () -> Boolean = { false }
    ): PyResult {
        val settings = ImageGenSettings.fromContext(context)
        val timeoutMs = settings.timeoutSec.coerceIn(
            ImageGenSettings.MIN_TIMEOUT_SEC,
            ImageGenSettings.MAX_TIMEOUT_SEC
        ) * 1000L
        return withContext(Dispatchers.IO) {
            try {
                // 网络/超时是墙钟预算；放到 withTimeout 里兜底（socket 读取超时另设）
                withTimeout(timeoutMs) {
                    execute(context, argsJson, conversationId, conversationMode, settings, isCancelled)
                }
            } catch (e: TimeoutCancellationException) {
                val L = Lang.current.imageGen
                PyResult(
                    ok = false,
                    errorCode = "TOOL_TIMEOUT",
                    errorMessage = Lang.current.format(L.errTimeout, settings.timeoutSec)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenFailure) {
                PyResult(ok = false, errorCode = e.code, errorMessage = e.message)
            } catch (t: Throwable) {
                PyResult(
                    ok = false,
                    errorCode = "IMAGEGEN_FAILED",
                    errorMessage = t.message ?: t.javaClass.simpleName
                )
            }
        }
    }

    // ---------------------------------------------------------------- 主流程

    private suspend fun execute(
        context: Context,
        argsJson: String,
        conversationId: String,
        conversationMode: String,
        settings: ImageGenSettings,
        isCancelled: () -> Boolean
    ): PyResult {
        val str = Lang.current
        val L = str.imageGen

        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            throw GenFailure("INVALID_ARGS", L.errInvalidArgs)
        }

        val promptRaw = optText(args, "prompt")
        if (promptRaw.isEmpty()) throw GenFailure("INVALID_ARGS", L.errPromptEmpty)
        if (promptRaw.length > MAX_PROMPT) throw GenFailure("INVALID_ARGS", L.errPromptLong)

        val textLines = parseTextLines(args, L)

        val aspect = optText(args, "aspect").ifBlank { settings.aspect }
        if (aspect !in ImageGenSettings.ASPECTS) throw GenFailure("INVALID_ARGS", L.errAspect)

        val count = optIntArg(args, "count", L.errCount)?.let { c ->
            if (c !in 1L..ImageGenSettings.MAX_COUNT.toLong()) {
                throw GenFailure("INVALID_ARGS", L.errCount)
            }
            c.toInt()
        } ?: settings.count.coerceIn(1, ImageGenSettings.MAX_COUNT)

        val seed = optIntArg(args, "seed", L.errSeed)?.let { s ->
            if (s !in 0L..MAX_SEED) throw GenFailure("INVALID_ARGS", L.errSeed)
            s
        }

        val modelOverride = optText(args, "model")
        if (modelOverride.length > MAX_MODEL_LEN) throw GenFailure("INVALID_ARGS", L.errModel)

        val providerName = optText(args, "provider")
        val chosenName = providerName.ifBlank { settings.defaultProvider }
        val provider = settings.provider(chosenName) ?: throw GenFailure(
            "UNKNOWN_PROVIDER",
            str.format(L.errProvider, chosenName, settings.availableProviders().joinToString(" / "))
        )

        // 自定义通道：防被诱导配置恶意端点 → 额外要求完全权限
        if (provider.requiresFullMode &&
            ToolRegistry.normalizeConversationMode(conversationMode) != ToolRegistry.CONV_FULL
        ) {
            throw GenFailure("PERMISSION_DENIED", L.errCustomNeedFull)
        }

        if (provider.isCustom && provider.endpoint.isBlank()) {
            throw GenFailure("NO_ENDPOINT", L.errNoEndpoint)
        }
        if (provider.model.isBlank()) throw GenFailure("NO_MODEL", L.errNoModel)
        if (!provider.noAuth && provider.apiKey.isBlank()) {
            throw GenFailure("NO_API_KEY", str.format(L.errNeedKey, provider.name))
        }
        validateEndpoint(provider.endpoint, provider.isCustom)
        val endpoint = runCatching { URI(provider.endpoint) }.getOrElse {
            throw GenFailure("INVALID_ENDPOINT", L.errInvalidEndpoint)
        }

        val model = modelOverride.ifBlank { provider.model }
        val size = provider.sizes[aspect]
            ?: provider.sizes[ImageGenSettings.DEFAULT_ASPECT]
            ?: throw GenFailure("INVALID_ENDPOINT", L.errAspect)

        val prompt = buildPrompt(promptRaw, textLines)
        val imageInput = resolveImageInput(optText(args, "image"), context, conversationId)
        val headers = buildHeaders(provider)

        val readTimeoutMs = timeoutMsOf(settings)
        // 墙钟预算：多张按顺序请求时，每张的读超时取「剩余预算」，避免 count=4 时总耗时 4×超时
        val deadline = SystemClock.elapsedRealtime() + readTimeoutMs
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

        val files = ArrayList<PyFile>(count)
        val failures = ArrayList<PyFailure>()
        var seq = 0
        var fatal: GenFailure? = null

        try {
            var index = 0
            while (index < count) {
                coroutineContext.ensureActive()
                // 用户已停止：不再发起下一张请求（退出后清理本次已落盘的文件）
                if (isCancelled()) throw GenFailure("CANCELLED", "生成已停止")
                val remainingMs = deadline - SystemClock.elapsedRealtime()
                if (remainingMs <= 1_000L) {
                    // 预算用完：剩余张数按超时记失败（已成功的图保留）
                    val timeoutMessage = str.format(L.errTimeout, settings.timeoutSec)
                    for (rest in index until count) failures.add(PyFailure(rest + 1, timeoutMessage))
                    fatal = GenFailure("TIMEOUT", timeoutMessage)
                    break
                }
                val seedForImage = seed?.let { (it + index).coerceAtMost(MAX_SEED).toInt() }
                try {
                    val images = generateBatch(
                        endpoint = endpoint,
                        headers = headers,
                        provider = provider,
                        model = model,
                        prompt = prompt,
                        size = size,
                        aspect = aspect,
                        imageInput = imageInput,
                        seed = seedForImage,
                        readTimeoutMs = remainingMs
                            .coerceAtMost(readTimeoutMs.toLong())
                            .coerceAtLeast(1_000L)
                            .toInt()
                    )
                    if (images.isEmpty()) throw GenFailure("EMPTY_RESPONSE", L.errEmptyResponse)
                    for (image in images) {
                        // 停止后不再落盘：避免出现「已停止」但会话文件里多出一张图
                        if (isCancelled()) throw GenFailure("CANCELLED", "生成已停止")
                        seq += 1
                        files.add(saveImage(context, conversationId, stamp, seq, image))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GenFailure) {
                    // 取消不是「这张失败」：直接冒泡，由外层清理本次已落盘的文件
                    if (e.code == "CANCELLED") throw e
                    if (e.code == "TIMEOUT") {
                        // 单张超时：不再继续后面的张数，但已成功的图保留
                        failures.add(PyFailure(index + 1, e.message ?: ""))
                        fatal = e
                        break
                    }
                    failures.add(PyFailure(index + 1, e.message ?: ""))
                } catch (e: IOException) {
                    failures.add(PyFailure(index + 1, str.format(L.errNetwork, e.message ?: e.javaClass.simpleName)))
                } catch (t: Throwable) {
                    failures.add(PyFailure(index + 1, t.message ?: t.javaClass.simpleName))
                }
                index++
            }
        } catch (e: GenFailure) {
            if (e.code == "CANCELLED") {
                // 停止生成：本次调用已落盘的图全部清掉，避免「幽灵文件」留在会话文件列表里
                files.forEach { f -> runCatching { File(f.path).delete() } }
                files.clear()
            }
            throw e
        }

        if (files.isEmpty()) {
            val detail = failures.joinToString("；") { str.format(L.resultFailureItem, it.index, it.message) }
            val message = fatal?.message
                ?: str.format(L.resultAllFailed, count, detail.ifBlank { L.errEmptyResponse })
            return PyResult(ok = false, errorCode = fatal?.code ?: "IMAGEGEN_FAILED", errorMessage = message)
        }

        val output = buildString {
            append(str.format(L.resultHeader, files.size)).append('\n')
            files.forEach { f ->
                append("- ").append(relativeToWorkspace(context, conversationId, f.path))
                    .append(" (").append(f.mime).append(", ").append(formatSize(f.sizeBytes)).append(")\n")
            }
            append(str.format(L.resultChannel, provider.name, model, aspect, size))
            if (failures.isNotEmpty()) {
                append('\n').append(str.format(L.resultFailures, failures.size))
                failures.forEach { f ->
                    append('\n').append(str.format(L.resultFailureItem, f.index, f.message))
                }
            }
        }.trim()

        return PyResult(
            ok = true,
            output = output,
            filePath = files.first().path,
            mime = files.first().mime,
            sizeBytes = files.first().sizeBytes,
            files = files,
            failures = failures
        )
    }

    private fun timeoutMsOf(settings: ImageGenSettings): Int =
        settings.timeoutSec.coerceIn(ImageGenSettings.MIN_TIMEOUT_SEC, ImageGenSettings.MAX_TIMEOUT_SEC) * 1000

    private fun parseTextLines(args: JSONObject, L: ImageGenStr): List<String> {
        if (!args.has("text_lines") || args.isNull("text_lines")) return emptyList()
        val value = args.opt("text_lines")
        if (value !is JSONArray) throw GenFailure("INVALID_ARGS", L.errTextLines)
        if (value.length() > MAX_TEXT_LINES) throw GenFailure("INVALID_ARGS", L.errTextLines)
        val out = ArrayList<String>(value.length())
        for (i in 0 until value.length()) {
            val line = value.opt(i)
            if (line !is String) throw GenFailure("INVALID_ARGS", L.errTextLines)
            if (line.length > MAX_TEXT_LINE_LEN) throw GenFailure("INVALID_ARGS", L.errTextLineLong)
            out.add(line)
        }
        return out
    }

    /** 取字符串参数：缺失 / JSON null 视为空（避免 "null" 字面量混进 prompt/provider/model） */
    private fun optText(args: JSONObject, key: String): String {
        if (!args.has(key) || args.isNull(key)) return ""
        return args.optString(key).trim()
    }

    /** 兼容整数 / 数字字符串；缺失返回 null；非法值按 [errorMessage] 抛参数错误 */
    private fun optIntArg(args: JSONObject, key: String, errorMessage: String): Long? {
        if (!args.has(key) || args.isNull(key)) return null
        return when (val v = args.opt(key)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: throw GenFailure("INVALID_ARGS", errorMessage)
            else -> throw GenFailure("INVALID_ARGS", errorMessage)
        }
    }

    private fun buildPrompt(description: String, textLines: List<String>): String {
        val sb = StringBuilder(description)
        if (textLines.isNotEmpty()) {
            sb.append('\n')
                .append("图内需要出现的文字（必须逐字精确渲染，顺序、标点、换行一致，严禁增删改字或用同义替换）：")
            textLines.forEach { sb.append('「').append(it).append('」') }
        }
        sb.append(QUALITY_SUFFIX)
        return sb.toString()
    }

    private fun buildHeaders(provider: ImageGenProvider): Map<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to "DChat/1.0"
        )
        // 免鉴权端点绝不带 key（本地部署 / 内网网关）
        if (!provider.noAuth && provider.apiKey.isNotBlank()) {
            headers[provider.authHeader] = provider.authPrefix + provider.apiKey
        }
        return headers
    }

    // ---------------------------------------------------------------- 参考图（图生图）

    private suspend fun resolveImageInput(
        raw: String,
        context: Context,
        conversationId: String
    ): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val str = Lang.current
        val L = str.imageGen
        if (value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
        ) {
            return value
        }
        if (value.startsWith("data:image/", ignoreCase = true)) {
            if (value.length > MAX_IMAGE_DATA_URI) {
                throw GenFailure("IMAGE_INPUT", L.errImageInputSize)
            }
            return value
        }
        coroutineContext.ensureActive()
        val workspace = FileTools.workspaceDir(context, conversationId)
        val candidate = File(value)
        val target = if (candidate.isAbsolute) candidate else File(workspace, value)
        val wsPath = runCatching { workspace.canonicalPath }.getOrElse { workspace.absolutePath }
            .trimEnd(File.separatorChar)
        val targetPath = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
        if (targetPath != wsPath && !targetPath.startsWith(wsPath + File.separator)) {
            throw GenFailure("IMAGE_INPUT", str.format(L.errImageInput, value))
        }
        if (!target.isFile) throw GenFailure("IMAGE_INPUT", str.format(L.errImageInput, value))
        if (target.length() > MAX_IMAGE_INPUT_BYTES) throw GenFailure("IMAGE_INPUT", L.errImageInputSize)
        val mime = when (target.extension.lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> throw GenFailure("IMAGE_INPUT", str.format(L.errImageInput, value))
        }
        val bytes = runCatching { target.readBytes() }.getOrElse {
            throw GenFailure("IMAGE_INPUT", str.format(L.errImageInput, value))
        }
        if (bytes.isEmpty()) throw GenFailure("IMAGE_INPUT", str.format(L.errImageInput, value))
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- 单张生成

    private suspend fun generateBatch(
        endpoint: URI,
        headers: Map<String, String>,
        provider: ImageGenProvider,
        model: String,
        prompt: String,
        size: String,
        aspect: String,
        imageInput: String?,
        seed: Int?,
        readTimeoutMs: Int
    ): List<GenImage> {
        val L = Lang.current.imageGen

        val body = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size)
            .put("n", 1)
            .put("response_format", "b64_json")
        if (provider.sendWatermark) body.put("watermark", false)
        if (provider.sendPromptExtend) body.put("prompt_extend", false)
        val imageField = provider.imageField
        if (imageInput != null && imageField != null) body.put(imageField, imageInput)
        if (seed != null && provider.sendSeed) body.put("seed", seed)
        if (provider.sendNegativePrompt) body.put("negative_prompt", SAME_NEGATIVE_PROMPT)

        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        var uri = endpoint
        var redirects = 0
        while (true) {
            val raw = try {
                fetchWithRetry(uri, "POST", headers, payload, MAX_RESPONSE_BYTES, readTimeoutMs)
            } catch (e: SocketTimeoutException) {
                throw GenFailure("TIMEOUT", Lang.current.format(L.errTimeout, readTimeoutMs / 1000))
            }
            if (raw.status in REDIRECT_STATUS) {
                if (raw.status != 307 && raw.status != 308) {
                    throw GenFailure("REDIRECT", L.errRedirect)
                }
                val location = raw.location ?: throw GenFailure("REDIRECT", L.errRedirect)
                if (redirects >= MAX_REDIRECTS_POST) throw GenFailure("REDIRECT", L.errRedirect)
                val next = runCatching { uri.resolve(location) }.getOrElse {
                    throw GenFailure("REDIRECT", L.errRedirect)
                }
                validateEndpoint(next.toString(), provider.isCustom)
                uri = next
                redirects++
                continue
            }
            if (raw.status !in 200..299) throw httpFailure(raw)
            return decodeImages(raw, uri)
        }
    }

    /** 请求重试：429/5xx 与网络错误指数退避，最多 [MAX_ATTEMPTS] 次尝试 */
    private suspend fun fetchWithRetry(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBytes: Long,
        readTimeoutMs: Int
    ): RawResponse {
        var attempt = 0
        while (true) {
            attempt++
            coroutineContext.ensureActive()
            try {
                val raw = executeOnce(uri, method, headers, body, maxBytes, readTimeoutMs)
                if (raw.status in RETRYABLE && attempt < MAX_ATTEMPTS) {
                    delay(RETRY_BASE_MS * (1L shl (attempt - 1)))
                    continue
                }
                return raw
            } catch (e: SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) throw e
                delay(RETRY_BASE_MS * (1L shl (attempt - 1)))
            }
        }
    }

    private suspend fun executeOnce(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBytes: Long,
        readTimeoutMs: Int
    ): RawResponse {
        val conn = try {
            uri.toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw GenFailure("INVALID_ENDPOINT", Lang.current.imageGen.errInvalidEndpoint)
        }
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val contentType = conn.contentType?.substringBefore(';')?.trim().orEmpty()
            val location = conn.getHeaderField("Location")
            val bytes: ByteArray = when {
                code !in 200..299 && code in RETRYABLE -> ByteArray(0)
                code in 200..299 -> {
                    val declared = conn.contentLengthLong
                    if (declared > maxBytes) throw tooLarge(maxBytes)
                    readLimited(conn.inputStream, maxBytes)
                }

                else -> readLimited(
                    conn.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)),
                    ERROR_BODY_MAX
                )
            }
            return RawResponse(code, contentType, bytes, location?.takeIf { it.isNotBlank() })
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private suspend fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        input.use { stream ->
            while (true) {
                coroutineContext.ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                if (out.size().toLong() + read > limit) throw tooLarge(limit)
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun tooLarge(limitBytes: Long): GenFailure = GenFailure(
        "IMAGE_TOO_LARGE",
        Lang.current.format(
            Lang.current.imageGen.errTooLarge,
            (limitBytes / (1024 * 1024)).coerceAtLeast(1)
        )
    )

    private fun httpFailure(raw: RawResponse): GenFailure {
        val str = Lang.current
        val L = str.imageGen
        val detail = errorDetail(raw.bytes)
        val suffix = if (detail.isBlank()) "" else "：" + detail
        return when (raw.status) {
            401, 403 -> GenFailure("AUTH_FAILED", str.format(L.errAuth, raw.status) + suffix)
            429 -> GenFailure("RATE_LIMITED", str.format(L.errHttp, raw.status) + suffix)
            else -> GenFailure("HTTP_${raw.status}", str.format(L.errHttp, raw.status) + suffix)
        }
    }

    /** 从错误响应里尽量抠出可读信息（OpenAI 风格 `{"error":{"message":...}}`） */
    private fun errorDetail(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val text = String(bytes, StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) return ""
        val parsed = parseJsonPayload(text)
        if (parsed is JSONObject) {
            val error = parsed.opt("error")
            val msg = when (error) {
                is JSONObject -> error.optString("message").ifBlank { error.optString("msg") }
                is String -> error
                else -> parsed.optString("message").ifBlank { parsed.optString("msg") }
            }
            if (msg.isNotBlank()) return msg.take(MAX_ERROR_TEXT)
        }
        return text.take(MAX_ERROR_TEXT)
    }

    // ---------------------------------------------------------------- 响应解析（二进制 / b64 / url）

    private suspend fun decodeImages(raw: RawResponse, endpoint: URI): List<GenImage> {
        val str = Lang.current
        val L = str.imageGen
        if (raw.bytes.isEmpty()) throw GenFailure("EMPTY_BODY", L.errEmptyBody)

        val sniffed = strictImageType(raw.bytes)
        val contentType = raw.contentType.lowercase(Locale.ROOT)
        val looksBinary = contentType.startsWith("image/") ||
            (sniffed != null && !contentType.contains("json"))
        if (looksBinary) {
            val type = sniffed ?: throw GenFailure("NOT_IMAGE", L.errNotImage)
            return listOf(GenImage(raw.bytes, type))
        }

        val text = String(raw.bytes, StandardCharsets.UTF_8).trim()
        val payload = parseJsonPayload(text)
            ?: throw GenFailure("BAD_RESPONSE", str.format(L.errBadResponse, text.take(300)))
        val items = extractItems(payload)
        if (items.isEmpty()) {
            val errorBody = errorDetail(raw.bytes)
            if (errorBody.isNotBlank()) throw GenFailure("PROVIDER_ERROR", errorBody)
            throw GenFailure("EMPTY_RESPONSE", L.errEmptyResponse)
        }

        // 只有确实要从远程 URL 拉图时才解析端点主机（内网端点放行私网图片地址）
        val policy = if (items.any { it.url != null }) createDownloadPolicy(endpoint) else null
        val out = ArrayList<GenImage>(items.size)
        for (item in items) out.add(materialize(item, policy))
        return out
    }

    private fun parseJsonPayload(text: String): Any? {
        if (text.isBlank()) return null
        runCatching { JSONObject(text) }.getOrNull()?.let { return it }
        runCatching { JSONArray(text) }.getOrNull()?.let { return it }
        // 兼容网关的 JSON-lines / SSE 包装：从后往前找最后一条合法 JSON
        val lines = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines.asReversed()) {
            val candidate = line.removePrefix("data:").trim()
            if (candidate.isEmpty() || candidate == "[DONE]") continue
            runCatching { JSONObject(candidate) }.getOrNull()?.let { return it }
            runCatching { JSONArray(candidate) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun extractItems(payload: Any?): List<RawItem> {
        val items = ArrayList<RawItem>()

        fun pushUrl(raw: String?) {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return
            if (value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
            ) {
                items.add(RawItem(url = value))
            }
        }

        fun push(value: Any?) {
            when (value) {
                null, JSONObject.NULL -> Unit
                is JSONArray -> for (i in 0 until value.length()) push(value.opt(i))
                is String -> {
                    val s = value.trim()
                    if (s.isEmpty()) return
                    if (s.startsWith("http://", ignoreCase = true) ||
                        s.startsWith("https://", ignoreCase = true)
                    ) {
                        items.add(RawItem(url = s))
                    } else if (s.length > 64) {
                        items.add(RawItem(b64 = s))
                    }
                }

                is JSONObject -> {
                    val b64 = firstString(value, "b64_json", "b64", "image_base64", "base64")
                    if (b64 != null) {
                        items.add(RawItem(b64 = b64))
                        return
                    }
                    val direct = firstString(value, "url", "image_url", "image")
                    if (direct != null) {
                        pushUrl(direct)
                        if (items.isEmpty() || items.last().url != direct.trim()) {
                            // direct 可能不是 URL（例如某个网关把 b64 塞在 image 字段）
                            if (direct.trim().length > 64 &&
                                !direct.startsWith("http://", true) &&
                                !direct.startsWith("https://", true)
                            ) {
                                items.add(RawItem(b64 = direct.trim()))
                            }
                        }
                        return
                    }
                    // image_url / image 可能是对象（OpenAI chat 风格）
                    val nested = value.opt("image_url").takeIf { it is JSONObject }
                        ?: value.opt("image").takeIf { it is JSONObject }
                    if (nested is JSONObject) pushUrl(nested.optString("url"))
                }

                else -> Unit
            }
        }

        when (payload) {
            is JSONObject -> {
                var found = false
                for (key in listOf("data", "images", "image", "output", "artifacts", "result")) {
                    val value = payload.opt(key)
                    if (value != null && value != JSONObject.NULL) {
                        found = true
                        push(value)
                    }
                }
                if (items.isEmpty()) push(payload)
            }

            else -> push(payload)
        }
        return items
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.trim().isNotEmpty()) return value.trim()
        }
        return null
    }

    private suspend fun materialize(item: RawItem, policy: DownloadPolicy?): GenImage {
        val str = Lang.current
        val L = str.imageGen
        val b64 = item.b64
        if (b64 != null) {
            if (b64.length > MAX_INLINE_B64_CHARS) throw tooLarge(MAX_INLINE_B64_CHARS)
            val cleaned = b64.replace(Regex("^data:image/[a-zA-Z0-9.+-]+;base64,"), "")
            val bytes = runCatching { Base64.decode(cleaned, Base64.DEFAULT) }.getOrElse {
                throw GenFailure("BAD_RESPONSE", L.errNotImage)
            }
            if (bytes.isEmpty()) throw GenFailure("EMPTY_BODY", L.errEmptyBody)
            if (bytes.size.toLong() > MAX_DOWNLOAD_BYTES) throw tooLarge(MAX_DOWNLOAD_BYTES)
            val type = strictImageType(bytes) ?: throw GenFailure("NOT_IMAGE", L.errNotImage)
            return GenImage(bytes, type)
        }
        val url = item.url ?: throw GenFailure("EMPTY_RESPONSE", L.errEmptyResponse)
        return downloadImage(url, policy)
    }

    /** 下载响应里的图片 URL：逐跳校验重定向 + 32MB 上限 + PNG/JPEG 魔数 */
    private suspend fun downloadImage(rawUrl: String, policy: DownloadPolicy?): GenImage {
        val str = Lang.current
        val L = str.imageGen
        var target = rawUrl
        var hop = 0
        while (true) {
            val uri = assertSafeImageUrl(target, policy)
            val raw = try {
                fetchWithRetry(uri, "GET", emptyMap(), null, MAX_DOWNLOAD_BYTES, IMAGE_READ_TIMEOUT_MS)
            } catch (e: SocketTimeoutException) {
                throw GenFailure(
                    "IMAGE_TIMEOUT",
                    str.format(L.errTimeout, (IMAGE_READ_TIMEOUT_MS / 1000).coerceAtLeast(1))
                )
            }
            if (raw.status in REDIRECT_STATUS) {
                if (hop >= MAX_DOWNLOAD_REDIRECTS) throw GenFailure("REDIRECT", L.errRedirect)
                val location = raw.location ?: throw GenFailure("REDIRECT", L.errRedirect)
                target = runCatching { uri.resolve(location).toString() }.getOrElse {
                    throw GenFailure("REDIRECT", L.errRedirect)
                }
                hop++
                continue
            }
            if (raw.status !in 200..299) {
                throw GenFailure("IMAGE_DOWNLOAD_FAILED", str.format(L.errImageDownload, raw.status))
            }
            if (raw.bytes.isEmpty()) throw GenFailure("EMPTY_BODY", L.errEmptyBody)
            val type = strictImageType(raw.bytes) ?: throw GenFailure("NOT_IMAGE", L.errNotImage)
            return GenImage(raw.bytes, type)
        }
    }

    // ---------------------------------------------------------------- SSRF 防护

    /** 生图端点是否在本机/内网：决定图片 URL 是否允许私网地址 */
    private fun createDownloadPolicy(endpoint: URI): DownloadPolicy {
        val host = runCatching { endpoint.host }.getOrNull()
        if (host.isNullOrBlank()) return DownloadPolicy(allowPrivate = false)
        return DownloadPolicy(allowPrivate = resolveTargetKind(host) == TargetKind.PRIVATE)
    }

    private fun assertSafeImageUrl(rawUrl: String, policy: DownloadPolicy?): URI {
        val str = Lang.current
        val L = str.imageGen
        val parsed = runCatching { URI(rawUrl) }.getOrElse {
            throw GenFailure("SSRF_BLOCKED", str.format(L.errSsrf, rawUrl.take(120)))
        }
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw GenFailure("SSRF_BLOCKED", str.format(L.errSsrf, rawUrl.take(120)))
        }
        val host = parsed.host?.trim().orEmpty()
        if (host.isEmpty()) throw GenFailure("SSRF_BLOCKED", str.format(L.errSsrf, rawUrl.take(120)))
        when (resolveTargetKind(host)) {
            TargetKind.UNKNOWN -> throw GenFailure("SSRF_BLOCKED", str.format(L.errCannotResolve, host))
            TargetKind.PRIVATE -> if (policy?.allowPrivate != true) {
                throw GenFailure("SSRF_BLOCKED", str.format(L.errSsrf, host))
            }

            TargetKind.PUBLIC -> Unit
        }
        return parsed
    }

    private fun validateEndpoint(endpoint: String, isCustom: Boolean) {
        val str = Lang.current
        val L = str.imageGen
        val uri = runCatching { URI(endpoint) }.getOrElse {
            throw GenFailure("INVALID_ENDPOINT", L.errInvalidEndpoint)
        }
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> Unit
            "http" -> {
                if (!isCustom) throw GenFailure("HTTPS_ONLY", L.errHttpsOnly)
                val host = uri.host?.trim().orEmpty()
                if (host.isEmpty()) throw GenFailure("INVALID_ENDPOINT", L.errInvalidEndpoint)
                if (resolveTargetKind(host) != TargetKind.PRIVATE) {
                    throw GenFailure("HTTP_PRIVATE_ONLY", L.errHttpPrivateOnly)
                }
            }

            else -> throw GenFailure("INVALID_ENDPOINT", L.errInvalidEndpoint)
        }
        if (uri.host.isNullOrBlank()) throw GenFailure("INVALID_ENDPOINT", L.errInvalidEndpoint)
    }

    private enum class TargetKind { PUBLIC, PRIVATE, UNKNOWN }

    private fun resolveTargetKind(rawHost: String): TargetKind {
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) return TargetKind.UNKNOWN
        if (host == "localhost" || host.endsWith(".localhost")) return TargetKind.PRIVATE
        if (isIpv4Literal(host) || host.contains(':')) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: return TargetKind.UNKNOWN
            return if (isBlockedAddress(address)) TargetKind.PRIVATE else TargetKind.PUBLIC
        }
        val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            ?: return TargetKind.UNKNOWN
        if (resolved.isEmpty()) return TargetKind.UNKNOWN
        for (address in resolved) {
            if (isBlockedAddress(address)) return TargetKind.PRIVATE
        }
        return TargetKind.PUBLIC
    }

    private fun normalizeHost(raw: String): String {
        var host = raw.trim().lowercase(Locale.ROOT)
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        if (host.endsWith(".")) host = host.dropLast(1)
        return host
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } &&
                part.toInt() in 0..255
        }
    }

    private fun isBlockedAddress(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 4) return isBlockedV4(bytes)
        if (bytes.size == 16) {
            // IPv4-mapped IPv6（::ffff:a.b.c.d）先还原成 IPv4 再判
            if (bytes.take(10).all { it == 0.toByte() } &&
                bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
            ) {
                return isBlockedV4(bytes.copyOfRange(12, 16))
            }
            val allZero = bytes.all { it == 0.toByte() }
            if (allZero) return true // ::
            if (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return true // ::1
            if ((bytes[0].toInt() and 0xFE) == 0xFC) return true // fc00::/7
            if (bytes[0].toInt() == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80) return true // fe80::/10
            if (bytes[0].toInt() == 0xFF) return true // ff00::/8
            return false
        }
        return true // 未知地址族按内网处理（fail-closed）
    }

    private fun isBlockedV4(bytes: ByteArray): Boolean {
        val b = IntArray(4) { bytes[it].toInt() and 0xFF }
        return when {
            b[0] == 0 -> true // 0.0.0.0/8
            b[0] == 10 -> true // 10/8
            b[0] == 100 && b[1] in 64..127 -> true // 100.64/10
            b[0] == 127 -> true // 127/8
            b[0] == 169 && b[1] == 254 -> true // 169.254/16（含云元数据 169.254.169.254）
            b[0] == 172 && b[1] in 16..31 -> true // 172.16/12
            b[0] == 192 && b[1] == 0 && b[2] == 0 -> true // 192.0.0/24
            b[0] == 192 && b[1] == 168 -> true // 192.168/16
            b[0] == 198 && b[1] in 18..19 -> true // 198.18/15
            b[0] in 224..239 -> true // 224/4 multicast
            b[0] >= 240 -> true // 240/4 reserved
            else -> false
        }
    }

    // ---------------------------------------------------------------- 落盘 / 工具函数

    private fun saveImage(
        context: Context,
        conversationId: String,
        stamp: String,
        index: Int,
        image: GenImage
    ): PyFile {
        val L = Lang.current.imageGen
        val workspace = FileTools.workspaceDir(context, conversationId)
        val dir = File(workspace, "images")
        if (!dir.exists() && !dir.mkdirs()) {
            throw GenFailure("SAVE_FAILED", Lang.current.format(L.errSaveFailed, "mkdirs failed"))
        }
        val ext = if (image.mediaType == "image/jpeg") "jpg" else "png"
        var file = File(dir, "gen-$stamp-$index.$ext")
        var n = 1
        while (file.exists()) {
            n += 1
            file = File(dir, "gen-$stamp-$index-$n.$ext")
        }
        try {
            // 二进制写，禁止任何文本转换（任务书 §3）
            file.outputStream().use { it.write(image.bytes) }
        } catch (e: IOException) {
            throw GenFailure(
                "SAVE_FAILED",
                Lang.current.format(L.errSaveFailed, e.message ?: e.javaClass.simpleName)
            )
        }
        return PyFile(file.absolutePath, image.mediaType, file.length())
    }

    private fun relativeToWorkspace(context: Context, conversationId: String, path: String): String {
        val workspace = FileTools.workspaceDir(context, conversationId)
        return runCatching { File(path).relativeTo(workspace).path }.getOrElse { path }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }

    private fun strictImageType(data: ByteArray): String? {
        if (data.size >= 8 &&
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
            data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() &&
            data[4] == 0x0D.toByte() && data[5] == 0x0A.toByte() &&
            data[6] == 0x1A.toByte() && data[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        if (data.size >= 3 &&
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        return null
    }

    // ---------------------------------------------------------------- 内部类型

    private data class GenImage(val bytes: ByteArray, val mediaType: String)

    private data class RawItem(val url: String? = null, val b64: String? = null)

    private data class DownloadPolicy(val allowPrivate: Boolean)

    private class RawResponse(
        val status: Int,
        val contentType: String,
        val bytes: ByteArray,
        val location: String?
    )

    class GenFailure(val code: String, override val message: String) : Exception(message)
}
