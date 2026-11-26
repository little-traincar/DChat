package com.dchat.app.data.tools

import android.content.Context
import android.content.SharedPreferences

/**
 * 图像生成（移植 DSH `@little-traincar/dsh-imagegen`）的设置。
 *
 * 沿用 `dchat_settings` SharedPreferences 明文存储，与现有 API key 的风险口径一致。
 * 通道 / 尺寸映射 / 请求字段裁剪全部照抄 DSH 插件：
 * - doubao：Seedream 端点，**不发 seed / negative_prompt**，发 watermark=false；
 * - qwen：DashScope 兼容端点，发 watermark=false / prompt_extend=false / seed / negative_prompt；
 * - 自定义：任意 OpenAI 兼容端点，可配鉴权头、免鉴权、尺寸模板与三个开关。
 */
data class ImageGenSettings(
    /** 默认通道：`doubao` / `qwen` / 自定义通道名（默认「自定义」） */
    val defaultProvider: String = DEFAULT_PROVIDER,
    /** 默认长宽比，取值见 [ASPECTS] */
    val aspect: String = DEFAULT_ASPECT,
    /** 默认张数 1–4 */
    val count: Int = DEFAULT_COUNT,
    /** 单次生成超时（秒），生图较慢，默认 300 */
    val timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    val doubaoApiKey: String = "",
    val doubaoBaseUrl: String = DOUBAO_BASE_URL,
    val doubaoModel: String = DOUBAO_MODEL,
    val qwenApiKey: String = "",
    val qwenBaseUrl: String = QWEN_BASE_URL,
    val qwenModel: String = QWEN_MODEL,
    val customName: String = CUSTOM_NAME,
    val customApiKey: String = "",
    val customBaseUrl: String = "",
    val customModel: String = "",
    val customAuthHeader: String = "Authorization",
    val customAuthPrefix: String = "Bearer ",
    val customNoAuth: Boolean = false,
    /** 尺寸模板：`比例=宽x高`，英文逗号分隔；也可只填一个固定尺寸（如 `1024x1024`） */
    val customSizes: String = CUSTOM_SIZES_TEXT,
    val customSendWatermark: Boolean = true,
    val customSendPromptExtend: Boolean = false,
    val customSendSeed: Boolean = true
) {

    /** 当前可用的通道名（给模型/界面提示用） */
    fun availableProviders(): List<String> {
        val custom = customName.trim().ifBlank { CUSTOM_NAME }
        return listOf("doubao", "qwen", custom)
    }

    /** 自定义通道是否已配置到「能发请求」的最低程度 */
    fun customConfigured(): Boolean =
        customBaseUrl.trim().isNotEmpty() && customModel.trim().isNotEmpty()

    /**
     * 解析本次调用使用的通道；名字不合法返回 null（调用方给出可读错误）。
     * 内置通道名大小写不敏感；自定义通道按名字精确匹配（另外接受 `custom`）。
     */
    fun provider(requested: String?): ImageGenProvider? {
        val name = requested?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultProvider.trim()
        return when {
            name.equals("doubao", ignoreCase = true) -> ImageGenProvider(
                name = "doubao",
                endpoint = normalizeEndpoint(doubaoBaseUrl),
                model = doubaoModel.trim(),
                apiKey = doubaoApiKey.trim(),
                authHeader = "Authorization",
                authPrefix = "Bearer ",
                noAuth = false,
                sizes = DOUBAO_SIZES,
                sendWatermark = true,
                sendPromptExtend = false,
                sendSeed = false,
                sendNegativePrompt = false,
                imageField = "image",
                isCustom = false
            )

            name.equals("qwen", ignoreCase = true) -> ImageGenProvider(
                name = "qwen",
                endpoint = normalizeEndpoint(qwenBaseUrl),
                model = qwenModel.trim(),
                apiKey = qwenApiKey.trim(),
                authHeader = "Authorization",
                authPrefix = "Bearer ",
                noAuth = false,
                sizes = QWEN_SIZES,
                sendWatermark = true,
                sendPromptExtend = true,
                sendSeed = true,
                sendNegativePrompt = true,
                imageField = "image",
                isCustom = false
            )

            name == customName.trim().ifBlank { CUSTOM_NAME } || name.equals("custom", ignoreCase = true) ->
                ImageGenProvider(
                    name = customName.trim().ifBlank { CUSTOM_NAME },
                    endpoint = normalizeEndpoint(customBaseUrl),
                    model = customModel.trim(),
                    apiKey = customApiKey.trim(),
                    authHeader = customAuthHeader.trim().ifBlank { "Authorization" },
                    authPrefix = customAuthPrefix,
                    noAuth = customNoAuth,
                    sizes = parseCustomSizes(customSizes),
                    sendWatermark = customSendWatermark,
                    sendPromptExtend = customSendPromptExtend,
                    sendSeed = customSendSeed,
                    sendNegativePrompt = true,
                    imageField = "image",
                    isCustom = true
                )

            else -> null
        }
    }

    /** 写回 SharedPreferences（由 AppState.saveSettings 调用） */
    fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor = editor
        .putString(KEY_DEFAULT_PROVIDER, defaultProvider)
        .putString(KEY_ASPECT, aspect)
        .putInt(KEY_COUNT, count)
        .putInt(KEY_TIMEOUT_SEC, timeoutSec)
        .putString(KEY_DOUBAO_API_KEY, doubaoApiKey)
        .putString(KEY_DOUBAO_BASE_URL, doubaoBaseUrl)
        .putString(KEY_DOUBAO_MODEL, doubaoModel)
        .putString(KEY_QWEN_API_KEY, qwenApiKey)
        .putString(KEY_QWEN_BASE_URL, qwenBaseUrl)
        .putString(KEY_QWEN_MODEL, qwenModel)
        .putString(KEY_CUSTOM_NAME, customName)
        .putString(KEY_CUSTOM_API_KEY, customApiKey)
        .putString(KEY_CUSTOM_BASE_URL, customBaseUrl)
        .putString(KEY_CUSTOM_MODEL, customModel)
        .putString(KEY_CUSTOM_AUTH_HEADER, customAuthHeader)
        .putString(KEY_CUSTOM_AUTH_PREFIX, customAuthPrefix)
        .putBoolean(KEY_CUSTOM_NO_AUTH, customNoAuth)
        .putString(KEY_CUSTOM_SIZES, customSizes)
        .putBoolean(KEY_CUSTOM_SEND_WATERMARK, customSendWatermark)
        .putBoolean(KEY_CUSTOM_SEND_PROMPT_EXTEND, customSendPromptExtend)
        .putBoolean(KEY_CUSTOM_SEND_SEED, customSendSeed)

    companion object {
        const val PREF_NAME = "dchat_settings"

        const val DEFAULT_PROVIDER = "doubao"
        const val DEFAULT_ASPECT = "2:3"
        const val DEFAULT_COUNT = 1
        const val DEFAULT_TIMEOUT_SEC = 300
        const val MAX_COUNT = 4
        const val MIN_TIMEOUT_SEC = 30
        const val MAX_TIMEOUT_SEC = 900

        const val CUSTOM_NAME = "自定义"

        const val DOUBAO_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        const val DOUBAO_MODEL = "doubao-seedream-5-5-261028"
        const val QWEN_BASE_URL = "https://maas.qianwenaiapi.com/compatible-mode/v1"
        const val QWEN_MODEL = "qwen-image-3.0-pro"

        /** 与 DSH 一致的尺寸映射 */
        val DOUBAO_SIZES: Map<String, String> = mapOf(
            "1:1" to "2048x2048",
            "2:3" to "1152x2048",
            "3:4" to "1536x2048",
            "9:16" to "1080x1920",
            "16:9" to "2560x1440"
        )
        val QWEN_SIZES: Map<String, String> = mapOf(
            "1:1" to "2048x2048",
            "2:3" to "1152x2048",
            "3:4" to "1536x2048",
            "9:16" to "1080x1920",
            "16:9" to "2048x1152"
        )
        val DEFAULT_CUSTOM_SIZES: Map<String, String> = mapOf(
            "1:1" to "1024x1024",
            "2:3" to "832x1216",
            "3:4" to "896x1152",
            "9:16" to "768x1344",
            "16:9" to "1344x768"
        )
        const val CUSTOM_SIZES_TEXT =
            "1:1=1024x1024,2:3=832x1216,3:4=896x1152,9:16=768x1344,16:9=1344x768"

        val ASPECTS: List<String> = listOf("1:1", "2:3", "3:4", "9:16", "16:9")

        private const val KEY_DEFAULT_PROVIDER = "imgDefaultProvider"
        private const val KEY_ASPECT = "imgAspect"
        private const val KEY_COUNT = "imgCount"
        private const val KEY_TIMEOUT_SEC = "imgTimeoutSec"
        private const val KEY_DOUBAO_API_KEY = "imgDoubaoApiKey"
        private const val KEY_DOUBAO_BASE_URL = "imgDoubaoBaseUrl"
        private const val KEY_DOUBAO_MODEL = "imgDoubaoModel"
        private const val KEY_QWEN_API_KEY = "imgQwenApiKey"
        private const val KEY_QWEN_BASE_URL = "imgQwenBaseUrl"
        private const val KEY_QWEN_MODEL = "imgQwenModel"
        private const val KEY_CUSTOM_NAME = "imgCustomName"
        private const val KEY_CUSTOM_API_KEY = "imgCustomApiKey"
        private const val KEY_CUSTOM_BASE_URL = "imgCustomBaseUrl"
        private const val KEY_CUSTOM_MODEL = "imgCustomModel"
        private const val KEY_CUSTOM_AUTH_HEADER = "imgCustomAuthHeader"
        private const val KEY_CUSTOM_AUTH_PREFIX = "imgCustomAuthPrefix"
        private const val KEY_CUSTOM_NO_AUTH = "imgCustomNoAuth"
        private const val KEY_CUSTOM_SIZES = "imgCustomSizes"
        private const val KEY_CUSTOM_SEND_WATERMARK = "imgCustomSendWatermark"
        private const val KEY_CUSTOM_SEND_PROMPT_EXTEND = "imgCustomSendPromptExtend"
        private const val KEY_CUSTOM_SEND_SEED = "imgCustomSendSeed"

        fun fromPrefs(prefs: SharedPreferences): ImageGenSettings {
            fun text(key: String, fallback: String): String {
                val v = prefs.getString(key, null)
                return if (v.isNullOrBlank()) fallback else v
            }
            return ImageGenSettings(
                defaultProvider = text(KEY_DEFAULT_PROVIDER, DEFAULT_PROVIDER),
                aspect = text(KEY_ASPECT, DEFAULT_ASPECT).takeIf { it in ASPECTS } ?: DEFAULT_ASPECT,
                count = prefs.getInt(KEY_COUNT, DEFAULT_COUNT).coerceIn(1, MAX_COUNT),
                timeoutSec = prefs.getInt(KEY_TIMEOUT_SEC, DEFAULT_TIMEOUT_SEC)
                    .coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC),
                doubaoApiKey = prefs.getString(KEY_DOUBAO_API_KEY, "").orEmpty(),
                doubaoBaseUrl = text(KEY_DOUBAO_BASE_URL, DOUBAO_BASE_URL),
                doubaoModel = text(KEY_DOUBAO_MODEL, DOUBAO_MODEL),
                qwenApiKey = prefs.getString(KEY_QWEN_API_KEY, "").orEmpty(),
                qwenBaseUrl = text(KEY_QWEN_BASE_URL, QWEN_BASE_URL),
                qwenModel = text(KEY_QWEN_MODEL, QWEN_MODEL),
                customName = text(KEY_CUSTOM_NAME, CUSTOM_NAME),
                customApiKey = prefs.getString(KEY_CUSTOM_API_KEY, "").orEmpty(),
                customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty(),
                customModel = prefs.getString(KEY_CUSTOM_MODEL, "").orEmpty(),
                customAuthHeader = text(KEY_CUSTOM_AUTH_HEADER, "Authorization"),
                customAuthPrefix = prefs.getString(KEY_CUSTOM_AUTH_PREFIX, "Bearer ").orEmpty(),
                customNoAuth = prefs.getBoolean(KEY_CUSTOM_NO_AUTH, false),
                customSizes = text(KEY_CUSTOM_SIZES, CUSTOM_SIZES_TEXT),
                customSendWatermark = prefs.getBoolean(KEY_CUSTOM_SEND_WATERMARK, true),
                customSendPromptExtend = prefs.getBoolean(KEY_CUSTOM_SEND_PROMPT_EXTEND, false),
                customSendSeed = prefs.getBoolean(KEY_CUSTOM_SEND_SEED, true)
            )
        }

        fun fromContext(context: Context): ImageGenSettings =
            fromPrefs(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE))

        /**
         * 端点归一化（照抄 DSH）：
         * - 已以 `/images/generations` 结尾 → 原样；
         * - 以 `/vN` 结尾 → 补 `/images/generations`；
         * - 其它 → 补 `/v1/images/generations`。
         */
        fun normalizeEndpoint(raw: String): String {
            val v = raw.trim().trimEnd('/')
            if (v.isEmpty()) return v
            if (v.endsWith("/images/generations", ignoreCase = true)) return v
            if (Regex("/v\\d+$", RegexOption.IGNORE_CASE).containsMatchIn(v)) {
                return "$v/images/generations"
            }
            return "$v/v1/images/generations"
        }

        /**
         * 解析自定义尺寸模板。支持：
         * - `1:1=1024x1024,2:3=832x1216`（按比例分档，缺的用默认档补齐）；
         * - `1024x1024`（固定尺寸，所有比例都用它）。
         */
        fun parseCustomSizes(raw: String): Map<String, String> {
            val text = raw.trim()
            if (text.isEmpty()) return DEFAULT_CUSTOM_SIZES
            val fixed = Regex("^\\d{2,5}[xX*]\\d{2,5}$")
            if (fixed.matches(text)) {
                return ASPECTS.associateWith { text }
            }
            val out = LinkedHashMap<String, String>()
            text.split(',', ';', '\n', '，', '；').forEach { part ->
                val p = part.trim()
                if (p.isEmpty()) return@forEach
                val idx = p.indexOf('=')
                if (idx <= 0) return@forEach
                val aspect = p.substring(0, idx).trim()
                val size = p.substring(idx + 1).trim().replace('*', 'x').replace('X', 'x')
                if (aspect in ASPECTS && Regex("^\\d{2,5}x\\d{2,5}$").matches(size)) {
                    out[aspect] = size
                }
            }
            if (out.isEmpty()) return DEFAULT_CUSTOM_SIZES
            // 缺的档位用默认值补齐，避免某个 aspect 没配就发不出请求
            ASPECTS.forEach { aspect ->
                if (!out.containsKey(aspect)) {
                    out[aspect] = DEFAULT_CUSTOM_SIZES.getValue(aspect)
                }
            }
            return out
        }
    }
}

/** 一次生图调用使用的通道（端点 / 模型 / 鉴权 / 尺寸 / 字段裁剪） */
data class ImageGenProvider(
    val name: String,
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val authHeader: String,
    val authPrefix: String,
    val noAuth: Boolean,
    val sizes: Map<String, String>,
    val sendWatermark: Boolean,
    val sendPromptExtend: Boolean,
    val sendSeed: Boolean,
    val sendNegativePrompt: Boolean,
    val imageField: String?,
    val isCustom: Boolean
) {
    /** 自定义通道额外要求「完全权限」：防被诱导配置恶意端点 */
    val requiresFullMode: Boolean get() = isCustom
}
