package com.dchat.app.data.api

import android.util.Log
import com.dchat.app.data.tools.ToolRequestConfig

import com.dchat.app.data.Source
import com.dchat.app.data.Step
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 流式过程中抛出的事件 */
sealed class ApiEvent {
    data class BlockStart(val index: Int, val step: Step) : ApiEvent()
    /** 4a-2：模型请求调用客户端工具（tool_use）开始/结束；input 由 input_json_delta 分片拼接 */
    data class ToolUseStart(val index: Int, val id: String, val name: String) : ApiEvent()
    data class ToolUseEnd(val index: Int, val id: String, val name: String, val inputJson: String) : ApiEvent()
    data class Delta(val index: Int, val text: String) : ApiEvent()
    /** 本轮 assistant 的原始 content 块（thinking+signature/text/tool_use），用于原样回放 */
    data class AssistantRaw(val rawJson: String) : ApiEvent()
    data class Done(
        val stopReason: String?,
        val toolCalls: Int,
        /**
         * 被拒答时的说明。官方 `safety_check` 拒答会带 `stop_details.explanation`，
         * 图片被拦时这里会写明是哪几张（如 `messages.1.content.0`）。
         */
        val stopExplanation: String? = null
    ) : ApiEvent()
    data class Limit(val rounds: Int) : ApiEvent()
    data class Failed(val message: String) : ApiEvent()

    /**
     * 输入被内容安全审核拦截（HTTP 400 `Content Exists Risk`）。
     * 与普通网络/接口失败分开，UI 用明确的中文提示而不是「请求失败」。
     * 文档：https://api-docs.deepseek.com/zh-cn/guides/safety/
     */
    data class InputBlocked(val message: String) : ApiEvent()

    /**
     * 会话标题（`title: {"enabled": true}` 时由服务端顺带生成）。
     * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/conversation_title/
     * 流式响应里位于 `message_delta` 事件的 `delta.title`。
     */
    data class Title(val title: String) : ApiEvent()

    /**
     * 一条回复的 token 用量。message_start 里先带一次（只有输入侧），
     * message_delta 里再带最终累计值；以最后一次为准，不要相加。
     * promptTokens 是总输入（= input_tokens + cache_read_input_tokens + cache_creation_input_tokens）。
     */
    data class Usage(
        val promptTokens: Int,
        val cacheReadTokens: Int,
        val completionTokens: Int,
        val model: String,
        /**
         * `usage.output_tokens_details.safety_tokens`：内容安全审核消耗的 tokens。
         * 官方说明它**已包含在 output_tokens 里**，这里单独记录只用于展示/核对，不要二次相加。
         */
        val safetyTokens: Int = 0,
        /** usage 事件来源：message_start / message_delta */
        val source: String = "",
        /** 服务端原始 usage JSON（P3-B 诊断） */
        val rawUsage: String = ""
    ) : ApiEvent()
}

/**
 * 一条要发给模型的消息：文本 + 已编码好的内联图片。
 *
 * 图片按官方「图像理解」文档走 Anthropic Messages 协议的 image 内容块
 * （https://api-docs.deepseek.com/zh-cn/guides/vision/ ）；
 * 官方限制图片只能出现在 user / system / tool 消息里，assistant 消息带图会 400，
 * 所以 [DeepSeekApi] 只会给 user 侧拼图片块。
 */
data class ChatTurn(
    val fromMe: Boolean,
    val text: String,
    val images: List<EncodedChatImage> = emptyList(),
    /** 历史 assistant 的原始 content 块（JSON 数组字符串）；旧消息为空时回退 text 拼装 */
    val rawAssistant: String = "",
    /** 历史 assistant 携带的完整工具链 JSON 数组；非空时展开为多条消息 */
    val rawChain: String = "",
    /**
     * 这一轮 user 消息前要带上的「用户元信息标记」（官方 latest_reminder）内容。
     * 空 = 这轮不带；历史里的标记原样回传可提升上下文缓存命中。
     */
    val reminder: String = "",
    /**
     * 本条 user 消息引用的服务端文件解析结果（Files API 返回的 `file_id`）。
     * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/document_parsing/
     * 只能在 user 消息里用 document 内容块引用；历史轮要重放才能保持多轮文档问答。
     */
    val documents: List<String> = emptyList()
)

/** GET /user/balance 的账户余额（金额保留服务端给的字符串小数） */
data class BalanceInfo(
    val isAvailable: Boolean,
    /** CNY / USD */
    val currency: String,
    val totalBalance: Double,
    val grantedBalance: Double,
    val toppedUpBalance: Double
)

/**
 * POST /v1/tools/asr 的识别结果。
 * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/asr/
 * 响应示例：{"request_id":"...","text":"上海今天天气怎么样？","duration_ms":3200}
 */
data class AsrResult(
    val requestId: String,
    val text: String,
    /** 服务端返回的音频时长（毫秒），仅用于日志/展示，不参与业务 */
    val durationMs: Long
)

/**
 * POST /v1/tools/tts 的整段合成结果。
 * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/tts/
 * 响应体就是音频字节流（`format=wav` 时 Content-Type 为 `audio/wav`），没有 URL、没有 JSON 包装。
 */
data class SpeechAudio(
    val bytes: ByteArray,
    val contentType: String?
)

/**
 * POST /files 上传并解析后的文件对象（文件解析第一步）。
 * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/document_parsing/
 * 响应示例：{"id":"file-api-xxx","object":"file","bytes":1048576,
 *            "created_at":1762790000,"filename":"report.pdf","purpose":"text_recognition"}
 */
data class UploadedFile(
    val id: String,
    val filename: String,
    val bytes: Long,
    val purpose: String
)

/** OCR 识别结果里的单页 */
data class OcrPage(
    val index: Int,
    /** 该页模型原始输出；逐页识别的文本保留 `<｜ref｜>` / `<｜box｜>` 等标记 */
    val text: String,
    /** stop；length 表示输出达到上限、该页文本不完整 */
    val finishReason: String
)

/**
 * POST /v1/tools/ocr 的识别结果。
 * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/ocr/
 * 注意：OCR 结果不会存 Files API，也不会交给对话模型，只供本地展示/自行使用。
 */
data class OcrResult(
    val id: String,
    val pageCount: Int,
    val pages: List<OcrPage>,
    val purpose: String
) {
    /** 所有页按顺序拼接的识别文本（保留服务端原始标记，调用方按需清理） */
    val text: String get() = pages.joinToString("\n\n") { it.text }.trim()
}


/**
 * DeepSeek Messages 协议（/anthropic/v1/messages）客户端。
 * 服务器工具（deepseek_web_search / deepseek_read_url）由服务端执行，
 * 一次请求里可能包含多轮：thinking → server_tool_use → *_tool_result → … → text
 */
class DeepSeekApi(
    private val baseUrl: String,
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 服务端 + 客户端工具统一按 name 字典序；schema 递归排序，保证前缀稳定。 */
    private fun buildTools(cfg: ToolRequestConfig): JSONArray {
        val list = ArrayList<JSONObject>()
        if (cfg.webSearch) {
            list.add(JSONObject().put("type", "deepseek_web_search").put("name", "deepseek_web_search"))
        }
        if (cfg.readUrl) {
            list.add(JSONObject().put("type", "deepseek_read_url").put("name", "deepseek_read_url"))
        }
        cfg.clientTools.forEach { c ->
            list.add(
                JSONObject()
                    .put("name", c.name)
                    .put("description", c.description)
                    .put("input_schema", parseInputSchema(c.inputSchema))
            )
        }
        list.sortBy { it.optString("name") }
        return JSONArray().apply { list.forEach { put(it) } }
    }

    private fun parseInputSchema(schema: String): JSONObject =
        runCatching {
            StablePrefix.canonical(JSONObject(schema)) as JSONObject
        }.getOrElse {
            JSONObject().put("type", "object").put("properties", JSONObject())
        }

    /**
     * 第三期：可取消的 OkHttp 调用。
     * 协程被取消（`withTimeout` / 用户停止）时立刻 `cancel()` 请求，关闭连接，
     * 避免上传/OCR 在后台继续跑。超时由调用方的 [kotlinx.coroutines.withTimeout] 控制。
     */
    private suspend fun Call.awaitCancellable(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
            }
        })
    }

    fun streamChat(
        model: String,
        systemPrompt: String,
        history: List<ChatTurn>,
        prompt: String,
        /** 本次用户消息附带的图片（已 base64 编码）；空表示纯文本消息 */
        promptImages: List<EncodedChatImage> = emptyList(),
        /**
         * 本次要在 user 消息**之前**紧挨着插入的「用户元信息标记」内容（官方 latest_reminder）；
         * 空表示这轮不带。格式与用法见 ui/lang/LatestReminder。
         */
        promptReminder: String = "",
        /**
         * 第三期：请求级工具开关统一由 ToolRegistry.requestConfig() 生成
         * （已按「工具启用 + 当前会话权限可执行」过滤）。
         */
        requestTools: ToolRequestConfig = ToolRequestConfig(),
        maxRounds: Int = 0,
        onEvent: (ApiEvent) -> Unit,
        /** 本次用户消息引用的服务端文件解析结果（Files API 返回的 file_id） */
        documents: List<String> = emptyList(),
        /**
         * 4a-2 工具循环：false 表示历史里已包含本轮 user 消息（后续轮），不再追加 prompt；
         * [rawMessages] 是手工构造的 assistant(tool_use) / user(tool_result) 消息，追加到 messages 末尾。
         */
        appendPrompt: Boolean = true,
        rawMessages: List<JSONObject> = emptyList(),
        /** 缓存诊断用会话 key；空则由 PrefixCacheSession / system 推导 */
        cacheKey: String = "",
        /** 请求身份（turn.round），用于把 usage 归属到正确请求 */
        requestTag: String = ""
    ): Call {
        val builtMessages = JSONArray().apply {
            history.forEach { turn ->
                val chain = if (!turn.fromMe && turn.rawChain.isNotBlank()) {
                    runCatching { JSONArray(turn.rawChain) }.getOrNull()
                } else null
                if (chain != null && chain.length() > 0) {
                    for (i in 0 until chain.length()) put(chain.getJSONObject(i))
                } else {
                    put(messageBlock(turn.fromMe, turn.text, turn.images, turn.documents, turn.rawAssistant))
                }
            }
            if (appendPrompt) {
                put(messageBlock(fromMe = true, text = prompt, images = promptImages, documents = documents))
            }
            rawMessages.forEach { put(it) }
        }
        // DSH 序列化规则：tool_result 块在最前 + 相邻同角色合并；不再发送 latest_reminder 角色。
        val messages = normalizeWireMessages(builtMessages)

        // 缓存阶段 A：工具按 name 字典序，schema 字段顺序固定；tools 先构建，供 epoch/hash 使用。
        val tools = buildTools(requestTools)
        val toolsJson = tools.toString()
        val cacheConversationId = PrefixCacheSession.conversationId
        val sessionKey = cacheConversationId.ifBlank {
            cacheKey.ifBlank { "sys:" + StablePrefix.sha256(systemPrompt) }
        }
        val reqId = (requestTag.ifBlank { "req" }) + "#" + java.util.UUID.randomUUID().toString().take(8)
        val toolNames = (0 until tools.length()).mapNotNull {
            tools.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
        }.toSet()
        val toolsEpoch = StablePrefix.sha256(systemPrompt + "\u0000" + toolsJson)
        val prevEpoch = if (cacheConversationId.isNotBlank()) PrefixCacheSession.toolsEpochHash else ""
        val effectiveSystem = systemPrompt
        if (prevEpoch.isNotBlank() && prevEpoch != toolsEpoch) {
            // 工具变化已体现在请求体里；不再向 system 追加提示，只记诊断日志。
            Log.i("DCache", "tools epoch changed old=$prevEpoch new=$toolsEpoch key=$sessionKey")
        }
        if (cacheConversationId.isNotBlank() && prevEpoch != toolsEpoch) {
            PrefixCacheSession.persistEpoch(toolsEpoch, cacheConversationId)
        }

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 8192)
            put("stream", true)
            if (effectiveSystem.isNotBlank()) put("system", effectiveSystem)
            if (requestTools.thinking) put("thinking", JSONObject().put("type", "enabled"))
            if (tools.length() > 0) put("tools", tools)
            // 官方「模型内容安全」：level 只能是 high / low；关闭时请求体完全不出现该字段
            if (requestTools.safetyLevel == "high" || requestTools.safetyLevel == "low") {
                put("safety_check", JSONObject().put("level", requestTools.safetyLevel))
            }
            // 官方「会话标题」：title.enabled=true 时响应里带标题，标题不计费
            if (requestTools.title) put("title", JSONObject().put("enabled", true))
            put("messages", messages)
        }

        // 缓存阶段 A4：只观测，不改变请求内容/顺序。
        val systemHash = StablePrefix.sha256(effectiveSystem)
        val toolsHash = StablePrefix.sha256(toolsJson)
        // history 只算「上一轮请求里已存在的消息」；本轮新增 tail 计入 newTurns，避免每轮都误报 history 变化。
        val prevCount = DCache.previousMessageCount(sessionKey)
        val stableCount = prevCount.coerceIn(0, messages.length())
        val stableHistory = JSONArray().apply {
            for (i in 0 until stableCount) put(messages.opt(i))
        }
        val historyHash = StablePrefix.sha256(stableHistory.toString())
        val fullHistoryHash = StablePrefix.sha256(messages.toString())
        val newTurns = (messages.length() - stableCount).coerceAtLeast(0)
        val prefixHash = StablePrefix.sha256(systemHash + toolsHash + stableHistory.toString())
        val fingerprints = (0 until messages.length()).map { i ->
            val mo = messages.optJSONObject(i)
            val role = mo?.optString("role").orEmpty()
            val s = mo?.toString().orEmpty()
            "$i:$role:${s.length}:${StablePrefix.sha256(s).take(8)}"
        }
        DCache.onRequest(
            sessionKey, reqId, systemHash, toolsHash, historyHash, prefixHash,
            toolNames, messages.length(), fullHistoryHash, newTurns, fingerprints
        )
        // 缓存定位（只读诊断）：完整 body / 前 3 条 message / reminder / system 字节数。
        val bodyText = body.toString()
        val bodyBytes = bodyText.toByteArray(Charsets.UTF_8)
        val systemActual = body.optString("system", "")
        val first3 = (0 until minOf(3, messages.length())).joinToString("|") { i ->
            val mo = messages.optJSONObject(i)
            val role = mo?.optString("role").orEmpty()
            val s = mo?.toString().orEmpty()
            "$i:$role:len=${s.length}:${StablePrefix.sha256(s)}"
        }
        val reminderHashes = (0 until messages.length()).mapNotNull { i ->
            val mo = messages.optJSONObject(i) ?: return@mapNotNull null
            if (mo.optString("role") == "latest_reminder") {
                val c = mo.optString("content")
                "len=${c.length}:${StablePrefix.sha256(c)}"
            } else null
        }
        val lens = (0 until messages.length()).joinToString(",") { i ->
            messages.optJSONObject(i)?.toString()?.length?.toString() ?: "0"
        }
        DCacheLog.write(
            "diag req=$reqId bodyBytes=${bodyBytes.size} bodyHash=${StablePrefix.sha256(bodyText)} " +
                "systemBytes=${systemActual.toByteArray(Charsets.UTF_8).size} " +
                "sysHash=${StablePrefix.sha256(systemActual)} title=${body.has("title")} " +
                "msgs=${messages.length()} first3=$first3 reminders=${reminderHashes.joinToString(",")} lens=$lens"
        )
        var usageRawLogged = false
        var usageRawDeltaLogged = false
        val emit: (ApiEvent) -> Unit = { ev ->
            if (ev is ApiEvent.Usage) {
                DCache.onUsage(reqId, sessionKey, ev.source, ev.cacheReadTokens, ev.promptTokens)
                if (ev.rawUsage.isNotBlank()) {
                    if (ev.source == "message_start" && !usageRawLogged) {
                        usageRawLogged = true
                        DCacheLog.write("usageRaw req=$reqId source=message_start raw=${ev.rawUsage}")
                    }
                    if (ev.source == "message_delta" && !usageRawDeltaLogged) {
                        usageRawDeltaLogged = true
                        DCacheLog.write("usageRaw req=$reqId source=message_delta raw=${ev.rawUsage}")
                    }
                }
            }
            onEvent(ev)
        }

        // 通过 Anthropic 兼容 /messages 引用 Files API 上传的文档时，必须带这个 beta 头
        val hasDocuments = documents.isNotEmpty() || history.any { it.documents.isNotEmpty() }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/anthropic/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("accept", "text/event-stream")
            .apply { if (hasDocuments) addHeader("anthropic-beta", "files-api-2025-04-14") }
            .post(body.toString().toRequestBody(jsonType))
            .build()

        return client.newCall(request).apply {
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    emit(ApiEvent.Failed(e.message ?: Lang.current.misc.netError))
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val err = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                            // 输入被内容安全审核拦截：官方固定返回 400 Content Exists Risk，
                            // 映射成明确中文提示，不要塞进「请求失败」这种笼统文案
                            if (resp.code == 400 && err.contains("Content Exists Risk", ignoreCase = true)) {
                                emit(
                                    ApiEvent.InputBlocked(Lang.current.feat.inputBlocked)
                                )
                                return
                            }
                            emit(ApiEvent.Failed("HTTP ${resp.code} ${err.take(300)}"))
                            return
                        }
                        val source = resp.body?.source() ?: run {
                            emit(ApiEvent.Failed(Lang.current.misc.emptyResponse))
                            return
                        }
                        try {
                            parseSse(source, maxRounds, emit)
                        } catch (e: Exception) {
                            emit(ApiEvent.Failed(e.message ?: Lang.current.misc.parseFailed))
                        }
                    }
                }
            })
        }
    }

    /**
     * 组一条 messages 元素。
     *
     * 没有图片/文档时 content 仍然是纯字符串（纯文本请求与改动前完全一致，不动老行为）；
     * 有图片时按官方「图像理解」文档拼 content 块数组；
     * 有文档时按官方「文件解析」文档拼 document 块：
     *   {"type": "document", "source": {"type": "file", "file_id": "file-api-xxx"}}
     *
     * 文档：https://api-docs.deepseek.com/zh-cn/guides/vision/ 、
     *      https://api-docs.deepseek.com/zh-cn/guides/document_parsing/#document-qa
     * 注意：图片/文档只能出现在 user / system / tool 消息里，assistant 消息带图/文档会 400，
     * 所以非 user 侧即使传了也直接忽略，只发文本。
     */
    private fun messageBlock(
        fromMe: Boolean,
        text: String,
        images: List<EncodedChatImage>,
        documents: List<String> = emptyList(),
        rawAssistant: String = ""
    ): JSONObject {
        val role = if (fromMe) "user" else "assistant"
        if (!fromMe && rawAssistant.isNotBlank()) {
            val raw = runCatching { JSONArray(rawAssistant) }.getOrNull()
            if (raw != null && raw.length() > 0) {
                return JSONObject().put("role", role).put("content", raw)
            }
        }
        val imgs = if (fromMe) images else emptyList()
        val docs = if (fromMe) documents.filter { it.isNotBlank() } else emptyList()
        if (imgs.isEmpty() && docs.isEmpty()) {
            return JSONObject().put("role", role).put("content", text)
        }
        val content = JSONArray()
        // 先文档、再文本、最后图片：文档按官方示例放在 text 之前
        docs.forEach { fileId ->
            content.put(
                JSONObject()
                    .put("type", "document")
                    .put("source", JSONObject().put("type", "file").put("file_id", fileId))
            )
        }
        // 纯图片消息（不带文字）时 content 里只有 image 块，符合文档里「content 为块数组」的要求
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        imgs.forEach { img ->
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", img.mediaType)
                            .put("data", img.base64)
                    )
            )
        }
        return JSONObject().put("role", role).put("content", content)
    }

    /**
     * 组一条「用户元信息标记」消息（官方 latest_reminder）。
     *
     * 文档：https://api-docs.deepseek.com/zh-cn/guides/latest_reminder/
     * {"role": "latest_reminder", "content": "2026-11-09,星期一,新加坡,Web,中文"}
     * - content 必须是字符串（否则 422），格式 `<date>,<weekday>,<location>,<platform>,<language>`；
     * - 这条消息后面必须紧跟一条 user 消息，否则 400；不能插在 tool_use 与 tool_result 之间；
     * - Messages API 与 Chat Completions API 支持，Responses API 不支持。
     */
    private fun reminderBlock(content: String): JSONObject =
        JSONObject().put("role", "latest_reminder").put("content", content)

    /** DSH 对齐：content 统一数组；tool_result 排最前；相邻同 role 合并。 */
    private fun normalizeWireMessages(src: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val mo = src.optJSONObject(i) ?: continue
            val role = mo.optString("role")
            val blocks = contentBlocks(mo.opt("content"))
            if (role == "user") {
                val tools = ArrayList<JSONObject>()
                val others = ArrayList<JSONObject>()
                for (j in 0 until blocks.length()) {
                    val b = blocks.optJSONObject(j) ?: continue
                    if (b.optString("type") == "tool_result") tools.add(b) else others.add(b)
                }
                val reordered = JSONArray()
                tools.forEach { reordered.put(it) }
                others.forEach { reordered.put(it) }
                mo.put("content", reordered)
            } else {
                mo.put("content", blocks)
            }
            val prev = if (out.length() > 0) out.optJSONObject(out.length() - 1) else null
            if (prev != null && prev.optString("role") == role) {
                val merged = contentBlocks(prev.opt("content"))
                for (j in 0 until blocks.length()) merged.put(blocks.opt(j))
                prev.put("content", merged)
            } else {
                out.put(mo)
            }
        }
        return out
    }

    private fun contentBlocks(v: Any?): JSONArray = when (v) {
        is JSONArray -> v
        is String -> JSONArray().put(JSONObject().put("type", "text").put("text", v))
        else -> JSONArray()
    }

    /** GET /models */
    fun listModels(): Result<List<String>> = runCatching {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} $text")
            val arr = JSONObject(text).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
        }
    }

    /**
     * GET /user/balance —— 查询账号余额（DeepSeek 开放平台「查询余额」，官方文档：
     * https://api-docs.deepseek.com/zh-cn/api/get-user-balance ）。
     *
     * 响应：{"is_available": true, "balance_infos": [{"currency":"CNY",
     *        "total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}
     *
     * 网络请求固定跑在 Dispatchers.IO，调用方直接 await 即可，别在主线程 execute()。
     * 优先返回 CNY 账户；没有 CNY 时退回首项。
     */
    suspend fun getBalance(): Result<BalanceInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/user/balance")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            // 流式对话用的 client readTimeout=0（不超时），查余额这种一次性 GET 单独给个上限，避免长时间占用线程
            val balanceClient = client.newBuilder()
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            balanceClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val o = JSONObject(text)
                val arr = o.optJSONArray("balance_infos") ?: JSONArray()
                val infos = (0 until arr.length()).mapNotNull { i ->
                    val b = arr.optJSONObject(i) ?: return@mapNotNull null
                    BalanceInfo(
                        isAvailable = o.optBoolean("is_available", false),
                        currency = b.optString("currency", "CNY").ifBlank { "CNY" },
                        totalBalance = b.optString("total_balance", "0").toDoubleOrNull() ?: 0.0,
                        grantedBalance = b.optString("granted_balance", "0").toDoubleOrNull() ?: 0.0,
                        toppedUpBalance = b.optString("topped_up_balance", "0").toDoubleOrNull() ?: 0.0
                    )
                }
                infos.firstOrNull { it.currency.equals("CNY", ignoreCase = true) }
                    ?: infos.firstOrNull()
                    ?: error(Lang.current.misc.balanceNoInfos)
            }
        }
    }

    // ---------- 语音识别 ASR ----------

    /**
     * 语音识别：POST /v1/tools/asr（multipart/form-data，音频放 `file` 字段）。
     *
     * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/asr/
     * - 一次请求发送**完整音频**，返回完整转写文本，**不支持流式**（没有中间结果）；
     * - 支持 16 位 PCM WAV / Ogg Opus，采样率 8000~48000，单/双声道（双声道只识别左声道），
     *   最大 8 MiB、最长 120 秒；
     * - 鉴权按官方文档用 `Authorization: Bearer <API Key>`（与 /models、/user/balance 一致）；
     * - 响应：{"request_id": "...", "text": "...", "duration_ms": 3200}。
     *
     * 网络请求固定跑在 Dispatchers.IO；调用方 await 结果即可（AsrEngine 内部调用）。
     * 失败时 Result 里是可直接给用户看的中文原因（网络异常由调用方再兜底）。
     */
    suspend fun transcribe(wav: ByteArray): Result<AsrResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()

            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/tools/asr")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            // 流式对话那个 client 的 readTimeout=0（不超时），单次识别得有自己的上限；
            // 120s 是官方允许的最长音频，再给上传/排队留点余量
            val asrClient = client.newBuilder()
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            asrClient.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(asrErrorMessage(resp.code, text))
                val o = JSONObject(text)
                val transcribed = o.optString("text").trim()
                if (transcribed.isBlank()) error(Lang.current.chat.asrEmptyResult)
                AsrResult(
                    requestId = o.optString("request_id"),
                    text = transcribed,
                    durationMs = o.optLong("duration_ms")
                )
            }
        }
    }

    /** 按官方 ASR 文档的错误码表给出明确原因（400/408/413/429/503/504） */
    private fun asrErrorMessage(code: Int, body: String): String {
        val L = Lang.current
        val s = L.chat
        return when (code) {
            400 -> s.asrHttpBadFormat
            401, 403 -> L.format(s.asrHttpForbidden, code)
            408 -> L.format(s.asrHttpUploadTimeout, code)
            413 -> L.format(s.asrHttpTooLarge, code)
            429 -> L.format(s.asrHttpTooMany, code)
            503 -> L.format(s.asrHttpBusy, code)
            504 -> L.format(s.asrHttpReadTimeout, code)
            else -> {
                val detail = body.trim().take(120)
                if (detail.isBlank()) L.format(s.asrHttpFail, code)
                else L.format(s.asrHttpFailDetail, code, detail)
            }
        }
    }

    // ---------- 语音合成 TTS ----------

    /**
     * 整段语音合成：POST /v1/tools/tts。
     *
     * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/tts/
     * - 鉴权 `Authorization: Bearer <API Key>`（不是 x-api-key）；
     * - JSON 请求体：{"text": "...", "voice_id": "stella|tide", "format": "wav"}；
     *   `filter_markdown` 默认 true，服务端自己会去掉 Markdown，这里不重复清洗；
     * - 响应体直接就是音频流（wav → audio/wav），边合成边返回，没有 URL、没有 JSON 包装；
     * - text 不能为空、最多 10,000 个计费字符、请求体 ≤64 KiB；单次最多 30 分钟音频；
     * - 错误码：400（空文本 / Content Exists Risk）、429（并发超限）、503、504。
     *
     * 网络请求固定跑在 Dispatchers.IO；失败时 Result 里是可直接 Toast 的中文原因。
     */
    suspend fun synthesizeSpeech(
        text: String,
        voiceId: String = "stella",
        format: String = "wav"
    ): Result<SpeechAudio> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("text", text)
                .put("voice_id", voiceId.ifBlank { "stella" })
                .put("format", format)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/tools/tts")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            // 音频边合成边返回，单次最多 30 分钟：按流式读，读间隔给足；请求体只有几十 KiB
            val ttsClient = client.newBuilder()
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .build()
            ttsClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                    error(ttsErrorMessage(resp.code, err))
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.isEmpty()) error(Lang.current.feat.ttsEmptyResponse)
                SpeechAudio(bytes, resp.header("Content-Type"))
            }
        }
    }

    /** 官方 TTS 错误码 → 明确原因（400/401/403/429/503/504） */
    private fun ttsErrorMessage(code: Int, body: String): String {
        val L = Lang.current
        val f = L.feat
        return when (code) {
            400 -> if (body.contains("Content Exists Risk", ignoreCase = true)) {
                f.ttsBadModeration
            } else {
                f.ttsBadNoText
            }
            401, 403 -> L.format(f.ttsForbidden, code)
            402 -> L.format(f.ttsInsufficient, code)
            413 -> L.format(f.ttsTooLarge, code)
            429 -> L.format(f.ttsTooMany, code)
            503 -> L.format(f.ttsBusy, code)
            504 -> L.format(f.ttsHttpTimeout, code)
            else -> {
                val detail = body.trim().take(120)
                if (detail.isBlank()) L.format(f.ttsHttpFail, code)
                else L.format(f.ttsHttpFailDetail, code, detail)
            }
        }
    }

    // ---------- 文件解析（Files API 上传 + 服务端解析）----------

    /**
     * 上传文件到 Files API 并让服务端解析：POST /files（multipart/form-data）。
     *
     * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/document_parsing/
     * - 表单里 `purpose` 必须写在 `file` 之前，否则 400；
     * - purpose：
     *   - `text_recognition`：PDF/Word/PPT/图片，只提取文字（模型看不到文档里的图）；
     *   - `rich_layout_recognition`：同上，但图文混排（裁出的图会占输入 token）；
     *   - `user_data`：纯文本/表格/电子书（XLSX/EPUB 等）上传时转 Markdown，不计费；
     * - **解析在服务端完成、解析成功后才返回 file_id**，解析期间不发送任何数据，
     *   所以超时要按「上传 + 解析」给足；
     * - 原始文件与解析结果都不支持下载，只能在对话请求里用 file_id 引用。
     *
     * @param size 文件字节数（用于 Content-Length）；未知时传 -1 走 chunked
     * @param open 打开文件流的回调（在 OkHttp 写请求体时于 IO 线程调用，不要提前打开）
     */
    suspend fun uploadDocument(
        filename: String,
        mimeType: String,
        size: Long,
        purpose: String,
        open: () -> InputStream?
    ): Result<UploadedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // 官方要求 purpose 字段必须放在 file 之前
                .addFormDataPart("purpose", purpose)
                .addFormDataPart(
                    "file",
                    filename,
                    FileStreamRequestBody(mimeType.toMediaTypeOrNull(), size, open)
                )
                .build()
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/files")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            val uploadClient = client.newBuilder()
                // 上传需在 120s 内完成，解析最长 120s；再给网络/排队留余量
                .writeTimeout(180, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
            uploadClient.newCall(request).awaitCancellable().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(uploadErrorMessage(resp.code, text))
                val o = runCatching { JSONObject(text) }.getOrNull()
                    ?: error(Lang.current.format(Lang.current.feat.uploadResponseParseFail, resp.code))
                val id = o.optString("id")
                if (id.isBlank()) error(Lang.current.feat.uploadNoFileId)
                UploadedFile(
                    id = id,
                    filename = o.optString("filename", filename),
                    bytes = o.optLong("bytes", size),
                    purpose = o.optString("purpose", purpose)
                )
            }
        }
    }

    /** 官方「文件解析」错误码表 → 明确原因（400/408/413/429/500/503/504） */
    private fun uploadErrorMessage(code: Int, body: String): String {
        val L = Lang.current
        val f = L.feat
        return when (code) {
            400 -> {
                val detail = body.trim().take(120)
                if (detail.isBlank()) f.uploadBad400
                else L.format(f.uploadBad400Detail, detail)
            }
            401, 403 -> L.format(f.uploadForbidden, code)
            402 -> L.format(f.uploadInsufficient, code)
            408 -> L.format(f.uploadTimeout, code)
            413 -> L.format(f.uploadTooLarge, code)
            429 -> L.format(f.uploadTooMany, code)
            500 -> L.format(f.uploadIncomplete, code)
            503 -> L.format(f.uploadBusy, code)
            504 -> L.format(f.uploadParseTimeout, code)
            else -> {
                val detail = body.trim().take(120)
                if (detail.isBlank()) L.format(f.uploadHttpFail, code)
                else L.format(f.uploadHttpFailDetail, code, detail)
            }
        }
    }

    // ---------- OCR ----------

    /**
     * 文字识别：POST /v1/tools/ocr（multipart/form-data）。
     *
     * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/ocr/
     * - 表单字段只有 `file`（必须带文件名）和 `purpose`，传其他字段会 400；
     * - purpose：`text_recognition`（不含图片的 Word/PPT 直接提取文字，其他文件逐页识别）或
     *   `rich_layout_recognition`（所有文件逐页识别、保留图文版式）；只要文字就用前者；
     * - 识别过程中服务端持续发换行符保活，完成后返回一个 JSON；换行是 JSON 前导空白，trim 后解析；
     * - 响应：{"id","object":"ocr.result","purpose","page_count",
     *         "pages":[{"index","text","finish_reason"}],"usage"}；
     * - 逐页识别的 text 保留 `<｜ref｜>` / `<｜box｜>` 等标记与坐标，可自行清理；
     * - 限制：文件 ≤100 MiB、≤1000 页；上传 120s、总时长 180s；
     * - 识别开始后状态码已是 200，失败时响应体是 {"error":{...}}，也要检查。
     *
     * 注意（官方原文）：OCR 结果**不会存入 Files API，也不会交给对话模型**；
     * 想让模型基于文档/图片回答，应使用文件解析（document_parsing）而不是 OCR。
     */
    suspend fun recognizeText(
        filename: String,
        mimeType: String,
        size: Long,
        purpose: String = "text_recognition",
        open: () -> InputStream?
    ): Result<OcrResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", purpose)
                .addFormDataPart(
                    "file",
                    filename,
                    FileStreamRequestBody(mimeType.toMediaTypeOrNull(), size, open)
                )
                .build()
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/v1/tools/ocr")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            // 上传 120s + 识别总时长 180s，再留余量
            val ocrClient = client.newBuilder()
                .writeTimeout(180, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()
            ocrClient.newCall(request).awaitCancellable().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(ocrErrorMessage(resp.code, text))
                val o = runCatching { JSONObject(text.trim()) }.getOrNull()
                    ?: error(Lang.current.format(Lang.current.feat.ocrParseFail, resp.code))
                // 识别开始后失败：200 但响应体是错误对象
                o.optJSONObject("error")?.let { e ->
                    error(ocrMessageFor(e.optString("message"), Lang.current.feat.ocrServerFailed))
                }
                val pagesArr = o.optJSONArray("pages") ?: JSONArray()
                val pages = (0 until pagesArr.length()).mapNotNull { i ->
                    val p = pagesArr.optJSONObject(i) ?: return@mapNotNull null
                    OcrPage(
                        index = p.optInt("index", i),
                        text = p.optString("text"),
                        finishReason = p.optString("finish_reason")
                    )
                }
                OcrResult(
                    id = o.optString("id"),
                    pageCount = o.optInt("page_count", pages.size),
                    pages = pages,
                    purpose = o.optString("purpose", purpose)
                )
            }
        }
    }

    /** 官方 OCR 错误码表 → 明确原因（400/402/408/413/429/500/503/504） */
    private fun ocrErrorMessage(code: Int, body: String): String {
        val L = Lang.current
        val f = L.feat
        return when (code) {
            400 -> ocrBodyMessage(body, f.ocrBad400)
            401, 403 -> L.format(f.ocrForbidden, code)
            402 -> L.format(f.ocrInsufficient, code)
            408 -> L.format(f.ocrUploadTimeout, code)
            413 -> L.format(f.ocrTooLarge, code)
            429 -> L.format(f.ocrTooMany, code)
            500 -> L.format(f.ocrUploadFail500, code)
            503 -> L.format(f.ocrBusy, code)
            504 -> L.format(f.ocrTimeout504, code)
            else -> {
                val detail = body.trim().take(120)
                if (detail.isBlank()) L.format(f.ocrHttpFail, code)
                else L.format(f.ocrHttpFailDetail, code, detail)
            }
        }
    }

    /** 从 OCR 错误响应体里取 error.message 并按官方错误说明翻译 */
    private fun ocrBodyMessage(body: String, fallback: String): String {
        val msg = runCatching {
            JSONObject(body.trim()).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return ocrMessageFor(msg, fallback)
    }

    /** 官方 OCR 错误表里「重试无法解决」的几类，翻译成明确原因 */
    private fun ocrMessageFor(message: String?, fallback: String): String {
        val m = message.orEmpty()
        if (m.isBlank()) return fallback
        val L = Lang.current
        val f = L.feat
        return when {
            m.contains("password-protected", ignoreCase = true) -> f.ocrPassword
            m.contains("more than 1000 pages", ignoreCase = true) -> f.ocrTooManyPages
            m.contains("too large", ignoreCase = true) -> f.ocrFileTooLarge
            m.contains("did not finish", ignoreCase = true) -> f.ocrTimeout
            m.contains("temporarily unavailable", ignoreCase = true) -> f.ocrBusyShort
            m.contains("format is not supported", ignoreCase = true) ||
                m.contains("could not be parsed", ignoreCase = true) ||
                m.contains("could not be decoded", ignoreCase = true) -> f.ocrUnsupported
            else -> L.format(f.ocrWithReason, fallback, m)
        }
    }

    // ---------- SSE ----------

    private fun parseSse(source: okio.BufferedSource, maxRounds: Int, onEvent: (ApiEvent) -> Unit) {
        var event = ""
        val data = StringBuilder()
        var toolCalls = 0
        var lastToolInput: JSONObject? = null
        var modelName = ""
        // message_start 的输入侧用量：有些 Anthropic 兼容实现只在 message_start 给 input_tokens，
        // message_delta 只给 output_tokens，所以最后上报时缺失的字段要回退到这里
        var startPromptTokens = 0
        var startCacheReadTokens = 0
        // 4a-2：客户端 tool_use 的 id/name 与流式 input_json_delta 缓冲
        val toolUseMeta = HashMap<Int, Pair<String, String>>()
        val toolUseJson = HashMap<Int, StringBuilder>()
        val toolUseInitial = HashMap<Int, String>()
        // 缓存阶段 A：按 index 保存服务端原始内容块（thinking 含 signature），结束时原样组装
        val rawBlocks = java.util.TreeMap<Int, JSONObject>()

        fun dispatch() {
            // 每个事件的 data 缓冲区必须在分发后清空：分支里带 `?: return` 的
            // 早退（content_block_stop 没有 tool_use、content_block 缺失等）以前会
            // 跳过清理，导致后续事件把上一个事件的 JSON 前缀拼进来，
            // JSONObject 只解析第一个对象 → 文本块 BlockStart 被吞、Done 永不触发。
            try {
                if (data.isNotEmpty()) {
                    val payload = data.toString()
                    when (event) {
                    "message_start" -> {
                        // Anthropic 协议：message_start.message 里带模型名与首次 usage（只有输入侧）
                        val o = JSONObject(payload)
                        val message = o.optJSONObject("message")
                        if (message != null) {
                            modelName = message.optString("model", modelName)
                            message.optJSONObject("usage")?.let { u ->
                                startPromptTokens = totalPromptTokens(u)
                                startCacheReadTokens = u.optInt("cache_read_input_tokens", 0)
                                onEvent(
                                    ApiEvent.Usage(
                                        promptTokens = startPromptTokens,
                                        cacheReadTokens = startCacheReadTokens,
                                        completionTokens = u.optInt("output_tokens", 0),
                                        model = modelName,
                                        safetyTokens = safetyTokensOf(u),
                                        source = "message_start",
                                        rawUsage = u.toString()
                                    )
                                )
                            }
                        }
                    }

                    "content_block_start" -> {
                        val o = JSONObject(payload)
                        val idx = o.optInt("index")
                        val block = o.optJSONObject("content_block") ?: return
                        when (val type = block.optString("type")) {
                            "thinking" -> {
                                rawBlocks[idx] = JSONObject(block.toString())
                                onEvent(
                                    ApiEvent.BlockStart(idx, Step.Thinking())
                                )
                            }

                            "text" -> {
                                rawBlocks[idx] = JSONObject(block.toString())
                                onEvent(ApiEvent.BlockStart(idx, Step.Text()))
                            }

                            // 4a-2：客户端工具调用（不是服务端 server_tool_use）
                            "tool_use" -> {
                                rawBlocks[idx] = JSONObject(block.toString())
                                val id = block.optString("id")
                                val name = block.optString("name")
                                toolUseMeta[idx] = id to name
                                toolUseJson[idx] = StringBuilder()
                                val input = block.optJSONObject("input")
                                if (input != null && input.length() > 0) {
                                    toolUseInitial[idx] = input.toString()
                                }
                                onEvent(ApiEvent.ToolUseStart(idx, id, name))
                            }

                            "server_tool_use" -> {
                                toolCalls++
                                if (maxRounds > 0 && toolCalls > maxRounds) {
                                    onEvent(ApiEvent.Limit(maxRounds))
                                    return
                                }
                                lastToolInput = block.optJSONObject("input")
                            }

                            "web_search_tool_result" -> {
                                val queries = (lastToolInput?.optJSONArray("queries") ?: JSONArray()).let { a ->
                                    (0 until a.length()).map { a.optString(it) }
                                }
                                onEvent(
                                    ApiEvent.BlockStart(
                                        idx,
                                        Step.Search(queries = queries, results = parseResults(block))
                                    )
                                )
                            }

                            "web_fetch_tool_result" -> {
                                val url = lastToolInput?.optString("url").orEmpty()
                                onEvent(
                                    ApiEvent.BlockStart(
                                        idx,
                                        Step.Fetch(url = url, results = parseResults(block))
                                    )
                                )
                            }

                            else -> if (type.isNotBlank()) { /* 其它块先忽略 */ }
                        }
                    }

                    "content_block_delta" -> {
                        val o = JSONObject(payload)
                        val idx = o.optInt("index")
                        val delta = o.optJSONObject("delta") ?: return
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val t = delta.optString("text")
                                rawBlocks[idx]?.put("text", rawBlocks[idx]?.optString("text").orEmpty() + t)
                                onEvent(ApiEvent.Delta(idx, t))
                            }
                            "thinking_delta" -> {
                                val t = delta.optString("thinking")
                                rawBlocks[idx]?.put("thinking", rawBlocks[idx]?.optString("thinking").orEmpty() + t)
                                onEvent(ApiEvent.Delta(idx, t))
                            }
                            // thinking 签名：Anthropic 兼容在 signature_delta 里给
                            "signature_delta" -> {
                                rawBlocks[idx]?.put("signature", delta.optString("signature"))
                            }
                            // 4a-2：工具参数分片
                            "input_json_delta" -> toolUseJson[idx]?.append(delta.optString("partial_json"))
                            else -> Unit
                        }
                    }

                    "content_block_stop" -> {
                        val o = JSONObject(payload)
                        val idx = o.optInt("index")
                        val meta = toolUseMeta.remove(idx) ?: return
                        val buffered = toolUseJson.remove(idx)?.toString().orEmpty()
                        val inputJson = buffered.takeIf { it.isNotBlank() }
                            ?: toolUseInitial.remove(idx)
                            ?: "{}"
                        rawBlocks[idx]?.put(
                            "input",
                            runCatching { JSONObject(inputJson) }.getOrDefault(JSONObject())
                        )
                        onEvent(ApiEvent.ToolUseEnd(idx, meta.first, meta.second, inputJson))
                    }

                    "message_delta" -> {
                        val o = JSONObject(payload)
                        // 最终 token 用量在这里（累计值，message_start 的量不要相加）；先发用量再收尾。
                        // 兼容：若这一帧没带输入侧字段，就用 message_start 的输入用量补上。
                        o.optJSONObject("usage")?.let { u ->
                            val hasInput = u.has("input_tokens") ||
                                u.has("cache_read_input_tokens") ||
                                u.has("cache_creation_input_tokens")
                            val rawPrompt = if (hasInput) totalPromptTokens(u) else startPromptTokens
                            val rawCache = if (hasInput) {
                                u.optInt("cache_read_input_tokens", 0)
                            } else {
                                startCacheReadTokens
                            }
                            onEvent(
                                ApiEvent.Usage(
                                    // 服务端实测 start/delta 的 input 相等；按 Anthropic 语义以 delta 累计值为准，缺失才回退 start。
                                    promptTokens = rawPrompt,
                                    cacheReadTokens = rawCache,
                                    completionTokens = u.optInt("output_tokens", 0),
                                    model = modelName,
                                    safetyTokens = safetyTokensOf(u),
                                    source = "message_delta",
                                    rawUsage = u.toString()
                                )
                            )
                        }
                        val delta = o.optJSONObject("delta")
                        // 会话标题：流式在 message_delta 的 delta.title（官方 conversation_title 文档）
                        val generatedTitle = delta?.optString("title").orEmpty()
                        if (generatedTitle.isNotBlank()) onEvent(ApiEvent.Title(generatedTitle))
                        val stop = delta?.optString("stop_reason")
                        // 拒答说明：stop_details 可能跟 stop_reason 一起在 delta 里，也可能在事件顶层；
                        // 图片被拦时 explanation 会写明是哪几张图
                        val stopDetails = delta?.optJSONObject("stop_details")
                            ?: o.optJSONObject("stop_details")
                        val explanation = stopDetails?.optString("explanation")
                            ?.takeIf { it.isNotBlank() && it != "null" }
                        if (rawBlocks.isNotEmpty()) {
                            val raw = JSONArray()
                            rawBlocks.values.forEach { raw.put(it) }
                            onEvent(ApiEvent.AssistantRaw(raw.toString()))
                        }
                        onEvent(ApiEvent.Done(stop, toolCalls, explanation))
                    }
                }
            }
            } finally {
                // 无论分支是否早退，都要把这一格的缓冲与事件名清掉（见函数开头说明）
                data.setLength(0)
                event = ""
            }
        }

        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trim())
                }
            }
        }
        dispatch()
    }

    /** 总输入 tokens = 未命中 input_tokens + 缓存命中 + 缓存写入（官方：后两者不要漏算输入） */
    private fun totalPromptTokens(usage: JSONObject): Int {
        val raw = usage.optInt("input_tokens", 0)
        val cacheRead = usage.optInt("cache_read_input_tokens", 0)
        val cacheCreate = usage.optInt("cache_creation_input_tokens", 0)
        return (raw + cacheRead + cacheCreate).coerceAtLeast(0)
    }

    /**
     * 内容安全审核消耗的 tokens：`usage.output_tokens_details.safety_tokens`。
     * 官方说明这部分**已包含在 output_tokens 里**，这里只单独记录，不参与求和。
     */
    private fun safetyTokensOf(usage: JSONObject): Int =
        usage.optJSONObject("output_tokens_details")?.optInt("safety_tokens", 0) ?: 0

    private fun parseResults(block: JSONObject): List<Source> {
        val arr = block.optJSONArray("content") ?: return emptyList()
        val out = ArrayList<Source>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            out.add(
                Source(
                    title = o.optString("title").ifBlank { url },
                    url = url,
                    site = o.optString("site_name").ifBlank { null },
                    snippet = o.optString("snippet").ifBlank { null }
                )
            )
        }
        return out
    }

    /**
     * 用 ContentResolver 的 InputStream **流式**上传文件，避免把 100 MiB 文档整个读进内存。
     * [open] 在 OkHttp 写请求体时才调用（IO 线程），每次 writeTo 重新打开一个流。
     */
    private class FileStreamRequestBody(
        private val mediaType: MediaType?,
        private val length: Long,
        private val open: () -> InputStream?
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            val input = open() ?: throw java.io.IOException(Lang.current.misc.fileReadFailed)
            input.use { src ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    if (n > 0) sink.write(buf, 0, n)
                }
            }
        }
    }
}
