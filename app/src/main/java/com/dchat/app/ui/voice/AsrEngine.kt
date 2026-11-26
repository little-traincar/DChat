package com.dchat.app.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dchat.app.data.api.AsrResult
import com.dchat.app.data.api.DeepSeekApi
import com.dchat.app.ui.lang.ChatStr
import com.dchat.app.ui.lang.Lang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 语音识别状态 */
enum class AsrStatus {
    Idle,

    /** 正在录音（按住说话期间） */
    Listening,

    /** 已松手，正在上传音频做识别；接口不支持流式，所以没有中间结果 */
    Transcribing,

    Error
}

/**
 * 语音输入：用系统 [AudioRecord] 录 16 kHz / 单声道 / 16 bit PCM，
 * 松手后封装成 WAV，调用 DeepSeek 语音识别接口转成文字（官方向导：
 * https://api-docs.deepseek.com/zh-cn/guides/asr/ ）。
 *
 * 接口约束（按官方文档）：
 * - `POST {baseUrl}/v1/tools/asr`，multipart/form-data 的 `file` 字段；
 * - 16 位 PCM WAV 或 Ogg Opus，采样率 8k~48k，单/双声道，≤8 MiB、≤120 秒；
 * - 鉴权 `Authorization: Bearer <API Key>`；
 * - 一次请求发完整音频、返回完整文本，**不支持流式**，所以 [onPartial] 永远不会回调；
 * - 成功返回 {"request_id","text","duration_ms"}。
 *
 * 调用线程：start / stop / cancel / destroy 可以在任意线程调（内部切到主线程），
 * 录音读取、WAV 封装和网络请求都在后台线程，不卡 UI；所有回调都在主线程。
 */
class AsrEngine(
    context: Context,
    /** 每次开始识别时现读设置：设置页改完 API Key / BaseUrl 不用重建引擎 */
    private val baseUrl: () -> String,
    private val apiKey: () -> String
) {
    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onStatus: (AsrStatus, String?) -> Unit = { _, _ -> }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前界面文案；每次报错时现取，切语言后立刻生效 */
    private val t: ChatStr get() = Lang.current.chat

    /** 当前这次「按下 → 录音 → 转写」会话；同一时刻只允许一个 */
    @Volatile
    private var session: Session? = null

    @Volatile
    private var destroyed = false

    /** 一次按压对应一个 Session，内部标志都是跨线程读的，所以用 @Volatile */
    private class Session(val recorder: AudioRecord) {
        @Volatile
        var recording = true

        @Volatile
        var cancelled = false

        @Volatile
        var transcribing = false

        var job: Job? = null
    }

    /** 麦克风权限是否已授予（ChatScreen 仍负责首次申请） */
    fun isAvailable(): Boolean = hasPermission()

    /**
     * 按下开始录音。同一时刻重复调用是幂等的：正在录音时静默忽略（同一次按压只会启动一次）；
     * 上一条还在转写时给出一次提示，不会并发两路录音。
     */
    fun start() = mainThread {
        if (destroyed) return@mainThread

        val current = session
        if (current != null) {
            if (!current.recording) {
                // 上一轮已松手、正在上传/识别：明确告诉用户稍等，避免"按了没反应"
                onStatus(AsrStatus.Error, t.asrBusy)
            }
            return@mainThread
        }

        if (!hasPermission()) {
            onStatus(AsrStatus.Error, t.asrNoPermission)
            return@mainThread
        }

        val key = apiKey().trim()
        if (key.isBlank()) {
            onStatus(AsrStatus.Error, t.asrNeedApiKey)
            return@mainThread
        }
        val url = baseUrl().trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }

        val recorder = createRecorder()
        if (recorder == null) {
            onStatus(AsrStatus.Error, t.asrMicOpenFailed)
            return@mainThread
        }
        val startedOk = runCatching { recorder.startRecording() }.isSuccess &&
            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!startedOk) {
            runCatching { recorder.release() }
            onStatus(AsrStatus.Error, t.asrMicStartFailed)
            return@mainThread
        }

        val s = Session(recorder)
        session = s
        onStatus(AsrStatus.Listening, null)
        launchCapture(s, url, key)
    }

    /**
     * 松手：停止录音，采集协程收尾后自动上传识别，最终结果走 [onFinal] / [onStatus]。
     * 幂等：没在录音时调用不做任何事。
     */
    fun stop() = mainThread {
        val s = session ?: return@mainThread
        if (s.recording) s.recording = false
    }

    /** 放弃本次输入：停止录音并丢掉结果（不请求接口） */
    fun cancel() = mainThread {
        val s = session ?: return@mainThread
        discard(s)
        session = null
    }

    /** 页面销毁时调用：停录音、取消在途请求、释放资源；之后 start 不再生效 */
    fun destroy() {
        mainThread {
            destroyed = true
            val s = session
            if (s != null) {
                discard(s)
                session = null
            }
            scope.cancel()
        }
    }

    // ---------- 录音 ----------

    private fun launchCapture(s: Session, url: String, key: String) {
        s.job = scope.launch(Dispatchers.IO) {
            val pcm = ByteArrayOutputStream(SAMPLE_RATE * 2 * 8)
            val readBuf = ByteArray(READ_BUF_SIZE)
            var readError = false
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // 阻塞读，直到松手 / 到 120 秒上限 / 读失败
                while (s.recording && !s.cancelled) {
                    if (SystemClock.elapsedRealtime() - startedAt >= MAX_RECORD_MS) break
                    val n = s.recorder.read(readBuf, 0, readBuf.size)
                    if (n > 0) {
                        pcm.write(readBuf, 0, n)
                    } else if (n < 0) {
                        readError = true
                        break
                    }
                }
            } catch (_: Throwable) {
                readError = true
            } finally {
                s.recording = false
                runCatching { s.recorder.stop() }
                runCatching { s.recorder.release() }
            }

            if (s.cancelled || session !== s) return@launch

            val raw = pcm.toByteArray()
            val durationMs = raw.size * 1000L / (SAMPLE_RATE * 2L)
            if (readError) {
                failSession(s, t.asrRecordFailed)
                return@launch
            }
            if (durationMs < MIN_RECORD_MS) {
                failSession(s, t.asrTooShort)
                return@launch
            }

            s.transcribing = true
            mainThread {
                if (session === s && !s.cancelled) onStatus(AsrStatus.Transcribing, null)
            }

            val result: Result<AsrResult> = try {
                // 网络请求内部跑在 Dispatchers.IO（DeepSeekApi.transcribe）
                DeepSeekApi(url, key).transcribe(wrapWav(raw))
            } catch (c: CancellationException) {
                return@launch
            } catch (t: Throwable) {
                Result.failure(t)
            }

            mainThread {
                if (session !== s || s.cancelled) return@mainThread
                session = null
                result.fold(
                    onSuccess = { r ->
                        if (r.text.isBlank()) {
                            onStatus(AsrStatus.Error, t.asrEmptyResult)
                        } else {
                            onFinal(r.text)
                        }
                    },
                    onFailure = { e -> onStatus(AsrStatus.Error, describeFailure(e)) }
                )
            }
        }
    }

    private fun createRecorder(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val bufferSize = maxOf(minBuffer * 2, READ_BUF_SIZE * 4)
        // 优先用「语音识别」音源（系统不做多余音效处理，识别更准），不行再退回普通麦克风
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            runCatching { recorder.release() }
        }
        return null
    }

    /** PCM（小端、16 bit）加 44 字节 WAV 头：官方要求「16 位 PCM WAV」 */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size + (pcm.size and 1)
        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeAscii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
        writeAscii("RIFF")
        writeIntLE(36 + dataSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeIntLE(16)                       // fmt chunk 长度
        writeShortLE(1)                      // PCM
        writeShortLE(1)                      // 单声道
        writeIntLE(SAMPLE_RATE)              // 采样率
        writeIntLE(SAMPLE_RATE * 2)          // 每秒字节数 = 采样率 × 声道 × 位宽/8
        writeShortLE(2)                      // block align
        writeShortLE(16)                     // 位宽
        writeAscii("data")
        writeIntLE(dataSize)
        out.write(pcm)
        if (pcm.size and 1 == 1) out.write(0) // 补成偶数字节，避免有的解析器挑剔
        return out.toByteArray()
    }

    // ---------- 收尾 / 工具 ----------

    private fun failSession(s: Session, message: String) {
        mainThread {
            if (session !== s || s.cancelled) return@mainThread
            session = null
            onStatus(AsrStatus.Error, message)
        }
    }

    private fun discard(s: Session) {
        s.cancelled = true
        s.recording = false
        s.job?.cancel()
        runCatching { s.recorder.stop() }
        runCatching { s.recorder.release() }
    }

    private fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 把异常翻译成能直接 Toast 的原因；HTTP 错误在 DeepSeekApi 里已经映射好 */
    private fun describeFailure(e: Throwable): String = when (e) {
        is SecurityException -> t.asrNoPermission
        is UnknownHostException -> t.asrNetFail
        is SocketTimeoutException -> t.asrTimeout
        is IOException -> t.asrIoError
        else -> e.message?.takeIf { it.isNotBlank() } ?: t.asrFailed
    }

    private fun mainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        /** 16 kHz 单声道 16 bit —— 官方 ASR 支持的采样率里语音识别最常用的一档 */
        private const val SAMPLE_RATE = 16000

        /** 太短的按压不发请求（省流量/省计费），提示重按 */
        private const val MIN_RECORD_MS = 400L

        /** 官方上限 120 秒；这里留 5 秒余量，避免刚好卡上限被服务端拒绝（115s × 32 KB/s ≈ 3.7 MiB，远小于 8 MiB） */
        private const val MAX_RECORD_MS = 115_000L

        /** 每次读 100 ms 音频，松手后最多再读一块就收尾，响应够快 */
        private const val READ_BUF_SIZE = 3200

        private const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}
