package com.dchat.app.data.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 一张已经编码好的内联图片；对应 DeepSeek Messages（/anthropic/v1/messages）协议里的 image 内容块：
 *
 * {"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"<BASE64>"}}
 *
 * 官方文档（2026-11 版）：
 * - 总览 https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/
 * - 图像理解 https://api-docs.deepseek.com/zh-cn/guides/vision/
 *
 * 文档要点（以此为准，别自创字段）：
 * - 支持格式只有 JPEG / PNG / GIF / WebP，服务端按文件实际内容判断；
 * - Anthropic 协议用 image 块，source.type = base64（另有 url / file 两种，这里用 base64）；
 * - 内联单图 ≤ 32 MiB，请求体 ≤ 48 MiB，单请求 ≤ 600 张图，图片只允许出现在 user/system/tool
 *   消息里（assistant 消息带图返回 400）；
 * - 图片会由服务端自动缩放（约 1300×1300 的等效像素），单图 token 上限 1024。
 */
data class EncodedChatImage(val mediaType: String, val base64: String) {
    /** base64 串还原成原始字节的大小（base64 占 4/3）；用于估算请求体预算 */
    val approxBytes: Long get() = base64.length.toLong() * 3 / 4
}

/**
 * 把聊天记录里的本地图片读成 base64 内联图片。
 *
 * 聊天图片本身已经由 AppState.importChatImage 落盘成长边 ≤1080 的 JPEG，
 * 所以正常路径只做「读字节 + 嗅探格式 + base64」，不再重新解码（别为了发图把大图解进内存）；
 * 只有文件超过文档上限（32 MiB）或不是官方支持的四种格式时才重压成 JPEG。
 *
 * 所有方法都会读文件 / 可能解码位图，必须在 Dispatchers.IO 上调用。
 */
object ChatImage {
    /** 官方：base64 内联单图的文件大小上限 */
    const val MAX_INLINE_BYTES = 32L * 1024 * 1024L

    /** 官方：图片单边最长像素（单请求 ≥15 张时降到 4096，这里统一按保守的 4096 处理） */
    const val MAX_EDGE = 4096

    /** 官方：整个请求体 ≤ 48 MiB，留出 JSON / 文本的余量后按 40 MiB 控制内联图片总量 */
    const val MAX_TOTAL_BYTES = 40L * 1024 * 1024L

    private const val JPEG_QUALITY = 85

    /**
     * 本地图片路径 → 可直接放进 source.data 的 base64。
     * 文件不存在 / 读取失败 / 无法解码时返回 null，由调用方跳过并统计失败张数。
     */
    fun encode(path: String): EncodedChatImage? = try {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            null
        } else {
            var bytes = file.readBytes()
            var mediaType = sniffMediaType(bytes)
            val overSize = bytes.size.toLong() > MAX_INLINE_BYTES
            val overEdge = !overSize && maxEdge(bytes) > MAX_EDGE
            if (mediaType == null || overSize || overEdge) {
                // 不在官方支持格式里 / 超过大小或边长上限：重压成 JPEG 再发，避免服务端直接 400
                val jpeg = compressToJpeg(bytes, MAX_EDGE)
                if (jpeg == null || jpeg.isEmpty()) {
                    null
                } else {
                    bytes = jpeg
                    mediaType = "image/jpeg"
                    EncodedChatImage(mediaType, Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            } else if (bytes.isEmpty()) {
                null
            } else {
                EncodedChatImage(mediaType, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
    } catch (e: Exception) {
        // 包含文件被删 / 无权限 / 解码失败；调用方按「这张图没发出去」处理
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** 按文件头判断真实格式（文档：格式由文件实际内容判断，不看扩展名） */
    private fun sniffMediaType(b: ByteArray): String? {
        if (b.size < 12) return null
        return when {
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() ->
                "image/jpeg"

            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
                b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() ->
                "image/png"

            b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() ->
                "image/gif"

            b[0] == 0x52.toByte() && b[1] == 0x49.toByte() &&
                b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
                b[8] == 0x57.toByte() && b[9] == 0x45.toByte() &&
                b[10] == 0x42.toByte() && b[11] == 0x50.toByte() ->
                "image/webp"

            else -> null
        }
    }

    /** 只读文件头拿尺寸，不解码像素 */
    private fun maxEdge(bytes: ByteArray): Int = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        maxOf(bounds.outWidth, bounds.outHeight)
    } catch (e: Exception) {
        0
    } catch (e: OutOfMemoryError) {
        0
    }

    /** 等比缩到长边 ≤ maxEdge，再压成 JPEG（仅在超出文档上限时才走这条路） */
    private fun compressToJpeg(bytes: ByteArray, maxEdge: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        return try {
            val longest = maxOf(bmp.width, bmp.height)
            val out = ByteArrayOutputStream()
            if (longest > maxEdge) {
                val s = maxEdge.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * s).toInt().coerceAtLeast(1),
                    (bmp.height * s).toInt().coerceAtLeast(1),
                    true
                )
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (scaled !== bmp) scaled.recycle()
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }
}
