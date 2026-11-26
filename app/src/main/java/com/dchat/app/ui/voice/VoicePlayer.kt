package com.dchat.app.ui.voice

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * 语音消息播放器：MediaPlayer 简单封装。
 * - 同一时刻只播一条：play 新的一条会先停掉旧的
 * - 播放时按 200ms 驱动进度回调 0..1
 * - stop()/release() 释放资源，不会回调 onEnd
 */
class VoicePlayer {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressCb: ((Float) -> Unit)? = null
    private var endCb: (() -> Unit)? = null
    private var durationMs = 0

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val d = durationMs
            val pos = runCatching { p.currentPosition }.getOrDefault(0)
            if (d > 0) progressCb?.invoke((pos.toFloat() / d).coerceIn(0f, 1f))
            handler.postDelayed(this, 200)
        }
    }

    fun play(file: File, onProgress: (Float) -> Unit, onEnd: () -> Unit) {
        stop()
        if (!file.exists() || file.length() <= 0L) {
            onEnd()
            return
        }
        val mp = MediaPlayer()
        player = mp
        progressCb = onProgress
        endCb = onEnd
        durationMs = 0
        val prepared = runCatching { mp.setDataSource(file.absolutePath) }.isSuccess
        if (!prepared) {
            finish()
            return
        }
        mp.setOnPreparedListener { p ->
            if (player !== p) return@setOnPreparedListener
            durationMs = runCatching { p.duration }.getOrDefault(0)
            runCatching { p.start() }
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        }
        mp.setOnCompletionListener { p ->
            if (player !== p) return@setOnCompletionListener
            finish()
        }
        mp.setOnErrorListener { p, _, _ ->
            if (player === p) finish()
            true
        }
        runCatching { mp.prepareAsync() }.onFailure { finish() }
    }

    /** 停止当前播放（不触发 onEnd 回调） */
    fun stop() {
        handler.removeCallbacks(ticker)
        progressCb = null
        endCb = null
        durationMs = 0
        val p = player
        player = null
        if (p != null) {
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
    }

    fun release() {
        stop()
    }

    /** 读取音频时长（毫秒），读不到返回 0；调用方最好放在 IO 线程 */
    fun durationOf(file: File): Int {
        if (!file.exists() || file.length() <= 0L) return 0
        val mp = MediaPlayer()
        return try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            runCatching { mp.duration.coerceAtLeast(0) }.getOrDefault(0)
        } catch (e: Exception) {
            0
        } finally {
            runCatching { mp.release() }
        }
    }

    private fun finish() {
        handler.removeCallbacks(ticker)
        val cb = endCb
        endCb = null
        progressCb = null
        durationMs = 0
        val p = player
        player = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        cb?.invoke()
    }
}
