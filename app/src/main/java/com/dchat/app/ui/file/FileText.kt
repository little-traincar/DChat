package com.dchat.app.ui.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import com.dchat.app.data.api.DeepSeekApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/**
 * 「文件解析」双通道：
 *
 * 1. **本地文本通道**（快、免费）：txt/md/json/csv/代码等纯文本文件直接读进输入框，
 *    逻辑与改造前一致，上限 [MAX_BYTES]。
 * 2. **服务端解析通道**（官方文件解析，Files API）：
 *    POST /files 上传 PDF/Word/PPT/图片/表格/电子书，上传时由服务端完成解析并返回 file_id，
 *    之后在 /anthropic/v1/messages 的 user 消息里用 document 内容块引用这个 file_id。
 *
 * 官方文档：
 * - 文件解析：https://api-docs.deepseek.com/zh-cn/guides/document_parsing/
 * - Files API：https://api-docs.deepseek.com/zh-cn/guides/files_api/
 *
 * 重要（按文档）：服务端**不支持下下载**上传的原始文件与解析结果，所以客户端拿不到解析后的纯文本，
 * 只能拿到 `file_id` 并在对话请求里引用。因此输入框里拼的是「服务端解析完成，本条消息引用该文件」
 * 的说明文字（不是伪造的解析正文），并在 UI 上明确标注这是服务端解析结果。
 */
object FileText {

    /** 本地文本通道单文件上限（防止一次塞爆上下文），与改造前一致 */
    const val MAX_BYTES = 1024 * 1024

    /** 官方文件解析：PDF/Word/PPT/图片 单个文件上限 100 MiB */
    const val MAX_SERVER_BYTES = 100L * 1024 * 1024

    /** 官方：纯文本/表格/电子书（user_data）上传上限 32 MiB */
    const val MAX_USER_DATA_BYTES = 32L * 1024 * 1024

    private val textExts = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "html", "htm",
        "yaml", "yml", "ini", "conf", "properties", "kt", "java", "py", "js", "ts",
        "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "sql",
        "sh", "bat", "gradle", "kts", "toml", "srt", "vtt"
    )

    /**
     * 走官方文件解析（text_recognition / rich_layout_recognition）的扩展名 → MIME。
     * 官方文档：PDF、Word（DOC、DOCX）、PowerPoint（PPT、PPTX），以及 PNG、JPEG、WebP、GIF、BMP、TIFF 等图片。
     */
    private val parseExts = mapOf(
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "tif" to "image/tiff",
        "tiff" to "image/tiff"
    )

    /**
     * 官方「纯文本、表格与电子书」：上传时不解析，purpose=user_data，
     * 服务端会把 XLSX/XLS 与 EPUB/MOBI 转成 Markdown（不支持 AZW3 和带 DRM 的电子书）。
     */
    private val userDataExts = mapOf(
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xls" to "application/vnd.ms-excel",
        "epub" to "application/epub+zip",
        "mobi" to "application/x-mobipocket-ebook"
    )

    /** 本地读到的文本文件 */
    data class Picked(val name: String, val text: String)

    /** 服务端解析完成、可在对话请求里引用的文件 */
    data class ServerDoc(
        /** Files API 返回的 file_id，如 `file-api-xxxxxxxx` */
        val fileId: String,
        val name: String,
        /** text_recognition / rich_layout_recognition / user_data */
        val purpose: String
    )

    fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
        }
        return name.ifBlank { Lang.current.misc.fileNameUnnamed }
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** 文本类小文件走本地通道（无扩展名时按文本尝试，保持改造前行为） */
    fun isLocalText(name: String): Boolean {
        val ext = extOf(name)
        return ext.isEmpty() || ext in textExts
    }

    /** 该文件是否需要/可以走官方服务端解析 */
    fun isServerParseable(name: String): Boolean {
        val ext = extOf(name)
        return ext in parseExts || ext in userDataExts || ext in textExts
    }

    // ---------------- 本地文本通道（与改造前一致） ----------------

    fun read(context: Context, uri: Uri): Result<Picked> {
        val L = Lang.current
        val name = displayName(context, uri)
        val ext = extOf(name)
        if (ext.isNotEmpty() && ext !in textExts) {
            return Result.failure(
                IllegalArgumentException(L.format(L.feat.fileUnsupportedLocal, ext))
            )
        }
        return runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(L.misc.fileReadFailed)
            if (bytes.size > MAX_BYTES) {
                error(L.format(L.feat.fileTooLargeLocal, bytes.size / 1024, MAX_BYTES / 1024))
            }
            // 粗略判断二进制：出现大量 0 字节就不是文本
            if (bytes.take(4096).count { it == 0.toByte() } > 8) {
                error(L.misc.fileBinary)
            }
            val raw = String(bytes, Charsets.UTF_8)
            if (raw.isBlank()) error(L.misc.fileEmpty)
            Picked(name, raw)
        }
    }

    /** 生成本地文本塞进输入框的提示格式（与改造前一致） */
    fun wrap(picked: Picked): String =
        Lang.current.format(Lang.current.misc.fileWrap, picked.name, picked.text)

    // ---------------- 服务端解析通道 ----------------

    /**
     * 上传并让服务端解析（[DeepSeekApi.uploadDocument]，内部走 Dispatchers.IO）。
     *
     * 按官方文档选择 purpose：
     * - PDF/Word/PPT/图片 → `text_recognition`（只提取文字；如果以后要看文档里的图，可改 rich_layout_recognition）
     * - XLSX/XLS/EPUB/MOBI → `user_data`（上传时转 Markdown，不计费）
     *
     * 客户端先按官方大小限制拦截，超限直接给明确提示，不浪费上传/计费。
     */
    suspend fun uploadForParsing(
        context: Context,
        uri: Uri,
        api: DeepSeekApi
    ): Result<ServerDoc> = withContext(Dispatchers.IO) {
        val L = Lang.current
        runCatching {
            val name = displayName(context, uri)
            val ext = extOf(name)
            val isParse = ext in parseExts
            val isText = ext in textExts
            val isUserData = ext in userDataExts || isText
            if (!isParse && !isUserData) {
                error(L.format(L.feat.fileUnsupportedServer, ext))
            }
            val purpose = if (isParse) "text_recognition" else "user_data"
            val limit = if (isParse) MAX_SERVER_BYTES else MAX_USER_DATA_BYTES
            val size = sizeOf(context, uri)
            if (size > limit) {
                error(L.format(L.feat.fileTooLargeServer, formatSize(size), formatSize(limit)))
            }
            val mime = context.contentResolver.getType(uri)
                ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: parseExts[ext]
                ?: userDataExts[ext]
                ?: if (isText) "text/plain" else "application/octet-stream"
            val uploadResult = api.uploadDocument(
                filename = name,
                mimeType = mime,
                size = size,
                purpose = purpose,
                open = { context.contentResolver.openInputStream(uri) }
            )
            if (uploadResult.isFailure) {
                error(describeFailure(uploadResult.exceptionOrNull()))
            }
            val uploaded = uploadResult.getOrThrow()
            ServerDoc(
                fileId = uploaded.id,
                name = uploaded.filename.ifBlank { name },
                purpose = uploaded.purpose.ifBlank { purpose }
            )
        }
    }

    /**
     * 服务端解析完成后拼进输入框的说明。
     *
     * 保留改造前的「请看下面这个文件（name）的内容：」包装，但明确写清这是**服务端解析结果**；
     * 因为官方不支持下下载解析文本，输入框里放的是引用说明而不是伪造的正文。
     */
    fun serverNotice(doc: ServerDoc): String {
        val L = Lang.current
        return L.format(L.feat.fileServerHead, doc.name) + L.feat.fileServerBody
    }

    /** 读取 ContentResolver 给出的文件大小；读不到 / 为空时抛错 */
    fun sizeOf(context: Context, uri: Uri): Long {
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) size = c.getLong(idx)
            }
        }
        if (size <= 0L) {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    // AssetFileDescriptor.UNKNOWN_LENGTH = -1
                    if (afd.length > 0L) size = afd.length
                }
            }
        }
        if (size <= 0L) error(Lang.current.feat.fileSizeUnknown)
        return size
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / 1024.0 / 1024.0)
        else -> "${bytes / 1024} KiB"
    }

    /** 网络异常翻译成明确原因；HTTP 错误码已在 DeepSeekApi 里映射好 */
    private fun describeFailure(e: Throwable?): String {
        val L = Lang.current
        return when (e) {
            is UnknownHostException -> L.chat.asrNetFail
            is SocketTimeoutException -> L.feat.fileTimeout
            is IOException -> L.chat.asrIoError
            else -> e?.message?.takeIf { it.isNotBlank() } ?: L.feat.fileParseFailed
        }
    }
}
