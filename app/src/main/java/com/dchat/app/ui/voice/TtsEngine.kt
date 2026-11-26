package com.dchat.app.ui.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.dchat.app.data.api.DeepSeekApi
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 官方 TTS 音色。当前官方只提供两个（文档：
 * https://api-docs.deepseek.com/zh-cn/guides/tts/ ）：
 *   stella → 海星，女声，默认
 *   tide   → 暗潮，男声
 */
data class TtsVoice(
    /** 官方 `voice_id`，存进 [com.dchat.app.data.AppSettings.ttsVoice] */
    val id: String,
    /** 设置页展示名 */
    val label: String,
    /** 设置页副标题 */
    val desc: String
)

/**
 * 语音合成封装：DeepSeek 官方 TTS HTTP 接口（`POST /v1/tools/tts`），不依赖第三方 SDK / 系统语音包。
 *
 * 官方文档：https://api-docs.deepseek.com/zh-cn/guides/tts/
 * - 整段合成请求体：{"text": "...", "voice_id": "stella|tide", "format": "wav"}；
 *   `filter_markdown` 默认 true，服务端自己会去掉 Markdown，这里**不再**清洗文本；
 * - 响应体就是音频字节流（wav → audio/wav），直接写文件；
 * - 音色只有 stella（女声，默认）/ tide（男声），语种按文本自动识别，没有语种参数。
 *
 * 对外形态尽量与改造前保持一致，方便 ChatScreen 的语音消息链路零改动：
 *  1. [speak]      直接朗读：消息下方「朗读」按钮走这条（内部合成到缓存文件再用 MediaPlayer 播）
 *  2. [synthesize] 把文本合成成 wav 文件，交给 [VoicePlayer] 播放（QQ 式语音消息）
 *  3. [fileFor]    语音消息缓存路径仍是 `filesDir/tts/<消息id>.wav`，气泡/缓存/时长/播放链路不变
 *
 * 失败一律 Toast 明确原因（鉴权/额度/格式/内容审核），不崩溃。
 */
class TtsEngine(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var ok = false

    /** 当前音色 id（null = 默认 stella） */
    @Volatile
    private var selectedVoiceId: String? = null

    /** speak 用的播放器（synthesize 的结果由调用方的 VoicePlayer 播放，不在这里） */
    private var player: MediaPlayer? = null

    /** speak 代次：stop / 新的 speak 会让旧的异步回调失效 */
    private var speakToken = 0

    /** 当前 synthesize 协程；停止生成时必须取消，否则「合成中…」会挂住 */
    private var synthJob: Job? = null

    /** 直接朗读（speak）的回调：utteranceId 原样回传 */
    var onDone: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onInitFailed: (String) -> Unit = { _ -> }

    /** 引擎就绪：官方 TTS 是纯 HTTP，没有设备语音包依赖，init 后即可用 */
    val isReady: Boolean get() = ok

    /** 官方 TTS 支持中文（语种自动识别），恒为 true，保留原字段兼容旧 UI */
    val hasChinese: Boolean get() = ok

    val currentVoiceName: String? get() = selectedVoiceId

    // ---------------- 初始化 ----------------

    /**
     * 不再初始化系统 TextToSpeech：官方 TTS 无本地依赖，[init] 只记录就绪并回调。
     * 保留该入口是为了不破坏 ChatScreen / SettingsScreen 原有的调用方式。
     */
    fun init(onReady: ((Boolean) -> Unit)? = null) {
        ok = true
        if (onReady != null) main.post { runCatching { onReady(true) } }
    }

    // ---------------- 朗读 / 合成 ----------------

    /** wav 缓存路径：filesDir/tts/<消息id>.wav（与改造前完全一致，语音消息链路不用改） */
    fun fileFor(msgId: Long): File = File(File(context.filesDir, "tts"), "$msgId.wav")

    /**
     * 把 [text] 合成到 [outFile]（官方 TTS，WAV）。
     * 文件已存在且非空时直接回调成功（缓存），不重复合成、不重复计费。
     *
     * 文本清洗交给服务端的 `filter_markdown`（默认 true），这里只做官方上限的截断：
     * 10,000 计费字符 / 64 KiB 请求体，超了截断并 Toast 说明。
     */
    fun synthesize(text: String, outFile: File, onDone: (Boolean) -> Unit) {
        if (outFile.exists() && outFile.length() > 0L) {
            if (isCacheUsable(outFile)) {
                postResult(onDone, true)
                return
            }
            // 旧版缓存是"流式 WAV"（RIFF size=0xFFFFFFFF），MediaPlayer 拿不到时长：删掉重新合成
            runCatching { outFile.delete() }
        }
        val raw = text.trim()
        if (raw.isBlank()) {
            postResult(onDone, false)
            return
        }
        val (requestText, note) = clampForTts(raw)
        if (requestText.isBlank()) {
            postResult(onDone, false)
            return
        }
        note?.let { toast(it) }
        if (!ok) {
            main.post { onInitFailed(Lang.current.chat.ttsNotReady) }
            postResult(onDone, false)
            return
        }
        val (base, key) = credentials()
        if (key.isBlank()) {
            toast(Lang.current.feat.ttsNeedApiKey)
            postResult(onDone, false)
            return
        }
        val voiceId = selectedVoiceId ?: DEFAULT_VOICE
        synthJob?.cancel()
        synthJob = scope.launch {
            // 网络请求走 Dispatchers.IO（DeepSeekApi.synthesizeSpeech 内部已切 IO）
            val result = withContext(Dispatchers.IO) {
                DeepSeekApi(base, key).synthesizeSpeech(requestText, voiceId)
            }
            result.fold(
                onSuccess = { audio ->
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { writeFile(audio.bytes, outFile) }.getOrDefault(false)
                    }
                    if (!saved) {
                        runCatching { outFile.delete() }
                        toast(Lang.current.feat.ttsWriteFailed)
                    }
                    onDone(saved)
                },
                onFailure = { e ->
                    toast(describeFailure(e))
                    runCatching { outFile.delete() }
                    onDone(false)
                }
            )
        }
    }

    /**
     * 直接朗读一条消息；utteranceId 用消息 id 的字符串，方便 UI 记录「正在读哪条」。
     * 内部先合成到 cacheDir 的 wav（同文本/音色复用缓存），再用 MediaPlayer 播放；
     * 播完回调 [onDone]，失败回调 [onError]（都已 Toast 原因）。
     */
    fun speak(utteranceId: String, text: String) {
        stop()
        val raw = text.trim()
        if (raw.isBlank()) return
        val (requestText, note) = clampForTts(raw)
        note?.let { toast(it) }
        if (requestText.isBlank()) return
        val (base, key) = credentials()
        if (key.isBlank()) {
            toast(Lang.current.feat.ttsNeedApiKey)
            onError(utteranceId)
            return
        }
        val voiceId = selectedVoiceId ?: DEFAULT_VOICE
        val file = File(
            File(context.cacheDir, "tts"),
            "speak_${safeFilePart(utteranceId)}_${voiceId}_${requestText.hashCode()}.wav"
        )
        // 同一段文本 + 同一音色直接复用缓存，避免重复计费
        if (file.exists() && file.length() > 0L) {
            play(utteranceId, file)
            return
        }
        val token = ++speakToken
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DeepSeekApi(base, key).synthesizeSpeech(requestText, voiceId)
            }
            if (token != speakToken) return@launch
            result.fold(
                onSuccess = { audio ->
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { writeFile(audio.bytes, file) }.getOrDefault(false)
                    }
                    if (token != speakToken) return@launch
                    if (saved) {
                        play(utteranceId, file)
                    } else {
                        toast(Lang.current.feat.ttsWriteFailed)
                        onError(utteranceId)
                    }
                },
                onFailure = { e ->
                    if (token == speakToken) {
                        toast(describeFailure(e))
                        onError(utteranceId)
                    }
                }
            )
        }
    }

    /** 停止当前朗读（不回调 onDone） */
    fun stop() {
        synthJob?.cancel()
        synthJob = null
        speakToken++
        val p = player
        player = null
        if (p != null) {
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
        ok = false
        selectedVoiceId = null
    }

    // ---------------- 播放（speak 用） ----------------

    private fun play(utteranceId: String, file: File) {
        val token = speakToken
        val mp = MediaPlayer()
        player?.let { runCatching { it.release() } }
        player = mp
        mp.setOnPreparedListener { p ->
            if (player !== p || token != speakToken) return@setOnPreparedListener
            runCatching { p.start() }
        }
        mp.setOnCompletionListener { p ->
            if (player === p && token == speakToken) {
                player = null
                runCatching { p.release() }
                onDone(utteranceId)
            }
        }
        mp.setOnErrorListener { p, _, _ ->
            if (player === p && token == speakToken) {
                player = null
                runCatching { p.release() }
                toast(Lang.current.feat.ttsPlayFailed)
                onError(utteranceId)
            }
            true
        }
        val prepared = runCatching {
            mp.setDataSource(file.absolutePath)
            mp.prepareAsync()
        }.isSuccess
        if (!prepared) {
            player = null
            runCatching { mp.release() }
            toast(Lang.current.feat.ttsPlayFailed)
            onError(utteranceId)
        }
    }

    // ---------------- 音色 ----------------

    /**
     * 官方音色列表（只有两个）。保留原方法名，设置页改列这份列表；
     * `AppSettings.ttsVoice` 存的是这里的 [TtsVoice.id]。
     *
     * 说明：官方没有「列出音色」的接口，列表来自 TTS 文档的固定音色表。
     */
    fun chineseVoices(): List<TtsVoice> = officialVoices()

    /** 按官方 voice_id 切换音色；也接受中文名「海星」「暗潮」。找不到返回 false */
    fun setVoiceByName(name: String): Boolean {
        val n = name.trim()
        if (n.isBlank()) return false
        val id = officialVoices().firstOrNull { it.id.equals(n, ignoreCase = true) }?.id
            ?: when (n) {
                "海星" -> "stella"
                "暗潮" -> "tide"
                else -> null
            }
            ?: return false
        selectedVoiceId = id
        return true
    }

    /** 默认女声：按文档音色表挑女性音色 → stella（海星，默认） */
    fun pickDefaultFemaleVoice(): String {
        selectedVoiceId = DEFAULT_VOICE
        return DEFAULT_VOICE
    }

    // ---------------- 工具 ----------------

    private fun postResult(cb: (Boolean) -> Unit, value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) cb(value) else main.post { cb(value) }
    }

    private fun toast(message: String) {
        if (message.isBlank()) return
        main.post {
            runCatching {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 把网络异常翻译成能直接 Toast 的原因；HTTP 错误码已在 DeepSeekApi 里映射好 */
    private fun describeFailure(e: Throwable): String {
        val L = Lang.current
        return when (e) {
            is UnknownHostException -> L.chat.asrNetFail
            is SocketTimeoutException -> L.feat.ttsTimeout
            is IOException -> L.chat.asrIoError
            else -> e.message?.takeIf { it.isNotBlank() } ?: L.feat.ttsFailed
        }
    }

    /**
     * 每次请求前现读设置里的 baseUrl / apiKey：
     * AppState 把设置存在同一个 SharedPreferences（dchat_settings）里，
     * 这样保留 `TtsEngine(context)` 构造形态的同时，设置页改完 Key 不用重建引擎。
     */
    private fun credentials(): Pair<String, String> {
        val prefs = context.getSharedPreferences("dchat_settings", Context.MODE_PRIVATE)
        val base = prefs.getString("baseUrl", null)?.trim().orEmpty().ifBlank { DEFAULT_BASE_URL }
        val key = prefs.getString("apiKey", null)?.trim().orEmpty()
        return base to key
    }

    /** 原子写文件：先写 `.part` 再改名，避免播放器读到半个文件 */
    private fun writeFile(bytes: ByteArray, target: File): Boolean {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(normalizeWav(bytes))
        if (target.exists()) target.delete()
        if (tmp.renameTo(target)) return true
        return runCatching {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            true
        }.getOrDefault(false)
    }

    /**
     * 官方 TTS 返回的是**流式 WAV**：RIFF/data chunk 的长度字段常写成 0xFFFFFFFF（未知长度占位符）。
     * 部分设备的 MediaPlayer 解析这种头拿不到时长（getDuration 返回 -2147483648）甚至无法播放，
     * 所以落盘前按实际字节数回填 RIFF size 与 data chunk size。
     */
    private fun normalizeWav(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        if (!isTag(bytes, 0, "RIFF") || !isTag(bytes, 8, "WAVE")) return bytes
        var pos = 12
        var dataSizePos = -1
        var dataStart = -1
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readIntLE(bytes, pos + 4)
            if (id == "data") {
                dataSizePos = pos + 4
                dataStart = pos + 8
                break
            }
            // fmt / LIST / fact 等：size 未知或越界就停止遍历，避免死循环
            if (size <= 0 || size > bytes.size) break
            pos += 8 + size + (size and 1)
        }
        val out = bytes.copyOf()
        if (dataSizePos >= 0 && dataStart in 1..out.size) {
            writeIntLE(out, dataSizePos, out.size - dataStart)
        }
        writeIntLE(out, 4, out.size - 8)
        return out
    }

    /** 缓存文件是否可用：流式 WAV 的 RIFF size 会是 0xFFFFFFFF，MediaPlayer 读不出时长，视为不可用 */
    private fun isCacheUsable(f: File): Boolean = runCatching {
        if (f.length() < 44L) return@runCatching false
        val head = ByteArray(12)
        f.inputStream().use { it.read(head) }
        if (!isTag(head, 0, "RIFF") || !isTag(head, 8, "WAVE")) return@runCatching true
        readIntLE(head, 4) > 0
    }.getOrDefault(true)

    private fun isTag(b: ByteArray, off: Int, tag: String): Boolean =        off + 4 <= b.size && b[off] == tag[0].code.toByte() &&
            b[off + 1] == tag[1].code.toByte() && b[off + 2] == tag[2].code.toByte() &&
            b[off + 3] == tag[3].code.toByte()

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /**
     * 官方上限截断：10,000 计费字符（汉字算 2）与 64 KiB 请求体。
     * 这里留了余量（60 KiB），并返回可 Toast 的说明；`filter_markdown` 由服务端处理。
     */
    private fun clampForTts(src: String): Pair<String, String?> {
        val L = Lang.current
        var note: String? = null
        var s = src
        // ① 计费字符上限
        val sb = StringBuilder()
        var billed = 0
        var i = 0
        while (i < s.length) {
            val step = if (Character.isHighSurrogate(s[i]) && i + 1 < s.length) 2 else 1
            val piece = s.substring(i, i + step)
            val cp = if (step == 2) {
                val hi = s[i].code
                val lo = s[i + 1].code
                0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
            } else {
                s[i].code
            }
            val cost = if (isHan(cp)) 2 else 1
            if (billed + cost > MAX_BILLED_CHARS) break
            sb.append(piece)
            billed += cost
            i += step
        }
        if (sb.length < s.length) {
            s = sb.toString()
            note = L.format(L.feat.ttsTruncateChars, s.length)
        }
        // ② 64 KiB 请求体上限
        if (s.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            val bytesSb = StringBuilder()
            var used = 0
            var j = 0
            while (j < s.length) {
                val step = if (Character.isHighSurrogate(s[j]) && j + 1 < s.length) 2 else 1
                val piece = s.substring(j, j + step)
                val n = piece.toByteArray(Charsets.UTF_8).size
                if (used + n > MAX_BODY_BYTES) break
                bytesSb.append(piece)
                used += n
                j += step
            }
            s = bytesSb.toString()
            note = note?.let { L.format(L.feat.ttsJoin, it, L.feat.ttsTruncateBytes) }
                ?: L.feat.ttsTruncateBytes
        }
        return s to note
    }

    private fun isHan(cp: Int): Boolean =
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
            cp in 0xF900..0xFAFF || cp in 0x20000..0x2FA1F

    private fun safeFilePart(src: String): String {
        val s = src.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        return s.ifBlank { "msg" }
    }

    companion object {
        /** 默认音色：海星（女声） */
        const val DEFAULT_VOICE = "stella"

        private const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /** 官方 TTS 上限：10,000 计费字符（汉字算 2） */
        private const val MAX_BILLED_CHARS = 10_000

        /** 官方 TTS 上限：请求体 64 KiB；留 4 KiB 给 JSON 包装 */
        private const val MAX_BODY_BYTES = 60 * 1024

        /** 官方音色表（文档固定两条，没有音色列表接口）；label / desc 跟随界面语言 */
        fun officialVoices(): List<TtsVoice> {
            val f = Lang.current.feat
            return listOf(
                TtsVoice(
                    id = "stella",
                    label = f.voiceStellaLabel,
                    desc = f.voiceStellaDesc
                ),
                TtsVoice(
                    id = "tide",
                    label = f.voiceTideLabel,
                    desc = f.voiceTideDesc
                )
            )
        }

        /** 设置页展示用的友好名字：官方 id → 文档音色名 */
        fun voiceLabel(name: String): String {
            val n = name.trim()
            val voices = officialVoices()
            voices.firstOrNull { it.id.equals(n, ignoreCase = true) }?.let { return it.label }
            return when (n) {
                "海星" -> voices[0].label
                "暗潮" -> voices[1].label
                "" -> voices[0].label
                else -> n
            }
        }

        /**
         * Markdown → 适合朗读的纯文本（保留旧接口，设置页/旧调用方仍可用）。
         *
         * 注意：官方 TTS 的 `filter_markdown` 默认就是 true，服务端会自己去 Markdown，
         * [synthesize] / [speak] 不再调用这个函数，避免把正常文字误删。
         */
        fun toPlainSpeech(src: String): String {
            var s = src
            // 代码块整体跳过
            s = s.replace(Regex("```[\\s\\S]*?```"), "")
            // 链接 [文字](url) → 文字
            s = s.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            // 标题 / 引用 / 列表标记
            s = s.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
            s = s.replace(Regex("(?m)^\\s{0,3}>\\s*"), "")
            s = s.replace(Regex("(?m)^\\s*[*+-]\\s+"), "")
            s = s.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
            // 粗体 / 斜体 / 行内代码
            s = s.replace("**", "").replace("__", "").replace("`", "")
            // 表格分隔行
            s = s.replace(Regex("(?m)^\\s*\\|?[\\s:|-]+\\|\\s*$"), "")
            s = s.replace('|', ' ')
            // 压缩空白
            s = s.replace(Regex("[ \\t]+"), " ")
            s = s.replace(Regex("\\n{2,}"), "。")
            return s.trim()
        }
    }
}
