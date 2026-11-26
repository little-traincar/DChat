package com.dchat.app.data

import android.content.Context
import android.net.Uri
import com.dchat.app.ui.lang.Lang
import com.dchat.app.ui.lang.format
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * DeepSeek 网页端历史记录 ZIP（export_format_version = 3）的导出 / 导入。
 *
 * 归档结构（写入顺序，见 docs/HISTORY_FORMAT.md）：
 *   manifest.json                                  必须是第一个条目
 *   chat_sessions/{session_id}.json                每个会话一个 JSON（含消息树）
 *   chat_session_index.json                        会话索引（在所有会话之后、图片之前）
 *   chat_sessions/{session_id}_files/...           附件原件（本项目暂不导出）
 *
 * 只依赖 java.util.zip + org.json，不引用第三方库。导出用 STORE，不压缩。
 */
object DeepSeekZip {

    private const val FORMAT_VERSION = 4
    private val READ_VERSIONS = setOf(3, 4)
    private const val MANIFEST = "manifest.json"
    private const val INDEX = "chat_session_index.json"
    private const val USER = "user.json"
    private const val SESSIONS_DIR = "chat_sessions/"

    private const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    /** 单会话导出文件名里标题片段的最大码点数（超出截断，避免文件名过长） */
    private const val MAX_TITLE_CODE_POINTS = 32

    // review 修复（P1 / OOM）：导入 ZIP 的三道硬上限，任何一道超限都中止整次导入
    /** 单个条目解压后上限 8MB */
    private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    /** 条目总数上限 500 */
    private const val MAX_ENTRIES = 500
    /** 整包解压总量上限 200MB */
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    /** 文件系统非法字符 + 控制字符：标题里出现时统一替换成下划线 */
    private val ILLEGAL_TITLE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * 建议的导出文件名：
     *   - 全量导出（[title] 为空）：deepseek_data-YYYY-MM-DD.zip
     *   - 单会话导出：deepseek_<标题>-YYYY-MM-DD.zip（非法字符替换、超长截断）
     */
    fun suggestedFileName(title: String? = null): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val date = fmt.format(Date())
        val base = sanitizeTitle(title)
        return if (base == null) "deepseek_data-$date.zip" else "deepseek_$base-$date.zip"
    }

    /** 会话标题 → 可安全用于文件名的片段；空标题返回 null（退回全量导出命名） */
    private fun sanitizeTitle(title: String?): String? {
        val raw = title?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val cleaned = ILLEGAL_TITLE_CHARS.replace(raw, "_").trim()
        if (cleaned.isEmpty()) return null
        // 按码点截断，避免把 emoji 的代理对切成半个字符
        val end = if (cleaned.codePointCount(0, cleaned.length) <= MAX_TITLE_CODE_POINTS) {
            cleaned.length
        } else {
            cleaned.offsetByCodePoints(0, MAX_TITLE_CODE_POINTS)
        }
        return cleaned.substring(0, end).trim().trimEnd(' ', '.', '_').ifBlank { null }
    }

    // ---------------- 导出 ----------------

    /**
     * 把本地会话写成 v3 ZIP。成功时返回导出的会话数量。
     * manifest.json 永远是第一个条目。
     */
    fun exportTo(context: Context, uri: Uri, conversations: List<Conversation>): Result<Int> = try {
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IOException(Lang.current.misc.zipCannotWrite)
        BufferedOutputStream(out).use { bos ->
            ZipOutputStream(bos, Charsets.UTF_8).use { zos ->
                // 1) manifest.json 必须第一个
                putStored(
                    zos, MANIFEST,
                    JSONObject().put("export_format_version", FORMAT_VERSION)
                        .toString().toByteArray(Charsets.UTF_8)
                )
                // 2) 每个会话一个 JSON
                conversations.forEach { conv ->
                    val sessionJson = buildSession(conv).toByteArray(Charsets.UTF_8)
                    val name = "$SESSIONS_DIR${conv.id}.json"
                    putStored(zos, name, sessionJson)
                }
                // 3) 会话索引（在所有会话之后、附件之前）
                putStored(
                    zos, INDEX,
                    buildIndex(conversations).toByteArray(Charsets.UTF_8)
                )
                // 4) 缓存诊断（不依赖 logcat；解压即可看逐轮 prefix/hit）
                runCatching {
                    val diag = java.io.File(context.filesDir, "dcache.log")
                    if (diag.isFile && diag.length() > 0L) {
                        putStored(zos, "diagnostics/dcache.log", diag.readBytes())
                    }
                }
                // 5) 附件：本项目没有图片附件，跳过
            }
        }
        Result.success(conversations.size)
    } catch (e: Exception) {
        Result.failure(IOException(e.message ?: Lang.current.misc.zipExportFailed, e))
    }

    /** 以 STORE（不压缩）写入一个条目，需自行填 size / crc */
    private fun putStored(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        val crc = CRC32()
        crc.update(bytes)
        entry.crc = crc.value
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun buildSession(conv: Conversation): String {
        val stamp = if (conv.updatedAt > 0) conv.updatedAt else System.currentTimeMillis()
        val iso = formatIso(stamp)

        val messages = JSONArray()
        var currentMessageId = 0
        conv.messages.forEachIndexed { index, msg ->
            val id = index + 1 // 会话内自增，父 id 恒小于子 id
            val mo = JSONObject()
            mo.put("id", id)
            mo.put("parent_id", if (index == 0) JSONObject.NULL else index.toLong())
            mo.put("role", if (msg.fromMe) "USER" else "ASSISTANT")
            mo.put("status", toArchiveStatus(msg.status))
            mo.put("thinking_enabled", msg.steps.any { it is Step.Thinking })
            mo.put("search_enabled", msg.steps.any { it is Step.Search })
            mo.put("accumulated_token_usage", 0)
            mo.put("inserted_at", iso)
            mo.put("fragments", buildFragments(msg))
            messages.put(mo)
            currentMessageId = id
        }

        return JSONObject()
            .put("id", conv.id)
            .put("title", conv.title)
            .put("pinned", false)
            .put("current_message_id", currentMessageId)
            .put("inserted_at", iso)
            .put("updated_at", iso)
            .put("messages", messages)
            .toString()
    }

    private fun buildIndex(conversations: List<Conversation>): String {
        val arr = JSONArray()
        conversations.forEach { conv ->
            val stamp = if (conv.updatedAt > 0) conv.updatedAt else System.currentTimeMillis()
            val iso = formatIso(stamp)
            arr.put(
                JSONObject()
                    .put("id", conv.id)
                    .put("title", conv.title)
                    .put("pinned", false)
                    .put("message_count", conv.messages.size)
                    .put("inserted_at", iso)
                    .put("updated_at", iso)
            )
        }
        return arr.toString()
    }

    /** 本地 ChatMsg → 归档 fragments */
    private fun buildFragments(msg: ChatMsg): JSONArray {
        val out = JSONArray()
        if (msg.fromMe) {
            // 图片 / 位置消息本地 text 可能为空：导出时用预览占位，避免写出空 REQUEST
            val content = msg.text.ifBlank {
                when {
                    msg.images.isNotEmpty() -> "[图片]"
                    msg.lat != null && msg.lng != null -> "[位置]"
                    else -> ""
                }
            }
            out.put(JSONObject().put("type", "REQUEST").put("content", content))
            return out
        }
        var hasResponse = false
        msg.steps.forEach { step ->
            when (step) {
                is Step.Thinking -> out.put(
                    JSONObject()
                        .put("type", "THINK")
                        .put("content", step.text)
                        .put("elapsed_secs", step.secs ?: JSONObject.NULL)
                )

                is Step.Text -> {
                    out.put(JSONObject().put("type", "RESPONSE").put("content", step.text))
                    hasResponse = true
                }

                is Step.Search -> {
                    val results = JSONArray()
                    step.results.forEach { s ->
                        results.put(
                            JSONObject()
                                .put("url", s.url)
                                .put("title", s.title)
                                .put("snippet", s.snippet ?: "")
                                .put("published_at", JSONObject.NULL)
                                .put("site_name", s.site ?: JSONObject.NULL)
                        )
                    }
                    val queries = JSONArray()
                    step.queries.forEach { queries.put(it) }
                    out.put(
                        JSONObject()
                            .put("type", "TOOL_SEARCH")
                            .put("queries", queries)
                            .put("results", results)
                            .put("status", "FINISHED")
                    )
                }

                is Step.Fetch -> {
                    val first = step.results.firstOrNull()
                    val url = step.url.ifBlank { first?.url.orEmpty() }
                    if (url.isNotBlank() || first != null) {
                        out.put(
                            JSONObject()
                                .put("type", "TOOL_OPEN")
                                .put("status", "FINISHED")
                                .put("url", url.ifBlank { JSONObject.NULL })
                                .put("title", first?.title?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                                .put("snippet", first?.snippet?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                                .put("site_name", first?.site?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                                .put("published_at", JSONObject.NULL)
                        )
                    }
                }

                // 第三期新增的客户端工具调用卡片：DeepSeek 归档格式没有对应片段类型，
                // 导出时跳过（正文、搜索、打开网页仍照常导出）
                is Step.ToolCall -> Unit
                // 第四期下载工具：归档格式暂无对应片段，导出时跳过（与 ToolCall 同）
                is Step.Download -> Unit
            }
        }
        // 没有正文片段但有纯文本（例如失败提示、未接 API 的本地模拟回复）时补一条 RESPONSE
        if (!hasResponse && msg.text.isNotBlank()) {
            out.put(JSONObject().put("type", "RESPONSE").put("content", msg.text))
        }
        return out
    }

    private fun toArchiveStatus(status: String): String = when (status) {
        ChatMsg.STATUS_WIP -> "WIP"
        ChatMsg.STATUS_FAILED -> "FAILED"
        // 内容安全拒答在 DeepSeek 历史格式里对应 CONTENT_FILTER，导出时保留这个语义
        ChatMsg.STATUS_REFUSED -> "CONTENT_FILTER"
        else -> "FINISHED"
    }

    // ---------------- 导入 ----------------

    /**
     * 解析 v3 ZIP（含消息树），沿 current_message_id 所在分支还原成本地会话列表。
     * 全部校验通过才返回成功；任何容器/元数据不合法都整次失败，不产生半个结果。
     */
    fun importFrom(context: Context, uri: Uri): Result<List<Conversation>> = try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException(Lang.current.misc.zipCannotRead)
        val sessions = ArrayList<Conversation>()
        var manifestSeen = false
        var firstRealEntry = true

        BufferedInputStream(input).use { bis ->
            ZipInputStream(bis, Charsets.UTF_8).use { zis ->
                var entryCount = 0
                var totalBytes = 0L
                // 统一读取入口：维护「解压总量」预算，超限抛异常中止导入
                fun readChecked(): ByteArray {
                    val bytes = readEntry(zis, MAX_TOTAL_BYTES - totalBytes)
                    totalBytes += bytes.size
                    return bytes
                }
                var entry = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        throw IOException(
                            Lang.current.format(Lang.current.misc.zipTooManyEntries, MAX_ENTRIES)
                        )
                    }
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('\\', '/')
                        if (firstRealEntry) {
                            firstRealEntry = false
                            // 文档要求 manifest.json 必须是第一个条目
                            if (name != MANIFEST) {
                                throw IOException(Lang.current.misc.zipNotDeepSeekFirst)
                            }
                        }
                        // 先看 ZIP 头里声明的解压大小（可被伪造，下面还有流式校验兜底）
                        if (entry.size > MAX_ENTRY_BYTES) {
                            throw IOException(
                                Lang.current.format(
                                    Lang.current.misc.zipEntryTooLarge,
                                    sizeText(MAX_ENTRY_BYTES)
                                )
                            )
                        }
                        when {
                            name == MANIFEST -> {
                                manifestSeen = true
                                validateManifest(String(readChecked(), Charsets.UTF_8))
                            }

                            // 账号信息，导入时跳过
                            name == USER -> Unit

                            name == INDEX -> {
                                // 索引可选；存在时必须是合法 JSON 数组
                                val text = String(readChecked(), Charsets.UTF_8)
                                if (text.isNotBlank()) {
                                    try {
                                        JSONArray(text)
                                    } catch (e: Exception) {
                                        throw IOException(Lang.current.misc.zipIndexBad, e)
                                    }
                                }
                            }

                            name.startsWith(SESSIONS_DIR) && name.endsWith(".json") -> {
                                val json = String(readChecked(), Charsets.UTF_8)
                                sessions.add(parseSession(json))
                            }

                            // 附件原件等：第一期不支持，直接跳过
                            else -> Unit
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        if (!manifestSeen) throw IOException(Lang.current.misc.zipMissingManifest)
        Result.success(sessions)
    } catch (e: Exception) {
        Result.failure(IOException(e.message ?: Lang.current.misc.zipImportFailed, e))
    }

    /**
     * 读单个 entry（review 修复 P1 / OOM）：单条 8MB、整包总量 200MB 两道流式上限。
     * 超限抛 IOException 中止整次导入（不静默跳过、不把截断数据当成内容）。
     */
    private fun readEntry(zis: ZipInputStream, remainingTotal: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(8192)
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) {
                throw IOException(
                    Lang.current.format(Lang.current.misc.zipEntryTooLarge, sizeText(MAX_ENTRY_BYTES))
                )
            }
            if (total > remainingTotal) {
                throw IOException(
                    Lang.current.format(Lang.current.misc.zipTotalTooLarge, sizeText(MAX_TOTAL_BYTES))
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
        bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

    private fun validateManifest(text: String) {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException(Lang.current.misc.zipManifestBad, e)
        }
        val version = o.optInt("export_format_version", -1)
        if (version !in READ_VERSIONS) {
            throw IOException(Lang.current.format(Lang.current.misc.zipVersionUnsupported, version, FORMAT_VERSION))
        }
    }

    private fun parseSession(text: String): Conversation {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException(Lang.current.misc.zipSessionBad, e)
        }
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val title = strOrNull(o, "title")?.takeIf { it.isNotBlank() } ?: Lang.current.chat.drawerNewChat
        val updatedAt = parseIso(strOrNull(o, "updated_at"))
            ?: parseIso(strOrNull(o, "inserted_at"))
            ?: System.currentTimeMillis()
        val messagesArr = o.optJSONArray("messages") ?: JSONArray()
        val branch = currentBranch(messagesArr, o.optInt("current_message_id", -1))

        val base = System.nanoTime()
        val messages = ArrayList<ChatMsg>(branch.size)
        branch.forEachIndexed { index, node ->
            toChatMsg(node, base + index)?.let { messages.add(it) }
        }
        return Conversation(id = id, title = title, updatedAt = updatedAt, messages = messages)
    }

    /**
     * 以 current_message_id 为当前节点，沿 parent_id 回溯到根，得到当前分支的线性消息序列。
     * current_message_id 缺失/非法时退化为「id 最大的叶子」。
     */
    private fun currentBranch(messages: JSONArray, currentId: Int): List<JSONObject> {
        val byId = LinkedHashMap<Int, JSONObject>()
        for (i in 0 until messages.length()) {
            val o = messages.optJSONObject(i) ?: continue
            if (o.isNull("id")) continue
            val id = o.optInt("id", Int.MIN_VALUE)
            if (id == Int.MIN_VALUE || byId.containsKey(id)) continue
            byId[id] = o
        }
        if (byId.isEmpty()) return emptyList()

        var cursor = if (byId.containsKey(currentId)) currentId else byId.keys.max()
        val path = ArrayList<JSONObject>()
        val seen = HashSet<Int>()
        while (true) {
            if (!seen.add(cursor)) break
            val node = byId[cursor] ?: break
            path.add(node)
            val parent = if (node.isNull("parent_id")) null else {
                val p = node.optInt("parent_id", Int.MIN_VALUE)
                if (p == Int.MIN_VALUE || p == cursor || !byId.containsKey(p)) null else p
            } ?: break
            cursor = parent
        }
        path.reverse()
        return path
    }

    /** 归档消息 → 本地 ChatMsg；未知角色返回 null（丢弃该条） */
    private fun toChatMsg(node: JSONObject, localId: Long): ChatMsg? {
        val role = (strOrNull(node, "role") ?: "").uppercase(Locale.US)
        val fromMe = when (role) {
            "USER" -> true
            "ASSISTANT" -> false
            else -> return null
        }
        val status = fromArchiveStatus(strOrNull(node, "status"))
        val fragments = node.optJSONArray("fragments")

        if (fromMe) {
            val sb = StringBuilder()
            if (fragments != null) {
                for (i in 0 until fragments.length()) {
                    val f = fragments.optJSONObject(i) ?: continue
                    if (strOrNull(f, "type") == "REQUEST") sb.append(strOrNull(f, "content").orEmpty())
                }
            }
            return ChatMsg(
                id = localId,
                fromMe = true,
                text = sb.toString(),
                steps = emptyList(),
                status = status
            )
        }

        val steps = ArrayList<Step>()
        val textBuilder = StringBuilder()
        if (fragments != null) {
            for (i in 0 until fragments.length()) {
                val f = fragments.optJSONObject(i) ?: continue
                when (strOrNull(f, "type")) {
                    "THINK" -> steps.add(
                        Step.Thinking(
                            text = strOrNull(f, "content").orEmpty(),
                            secs = if (f.isNull("elapsed_secs")) null else f.optInt("elapsed_secs")
                        )
                    )

                    "RESPONSE" -> {
                        val content = strOrNull(f, "content").orEmpty()
                        steps.add(Step.Text(content))
                        textBuilder.append(content)
                    }

                    "TOOL_SEARCH" -> steps.add(
                        Step.Search(
                            queries = parseQueries(f.optJSONArray("queries")),
                            results = parseSources(f.optJSONArray("results"))
                        )
                    )

                    "TOOL_OPEN" -> {
                        val url = strOrNull(f, "url").orEmpty()
                        val title = strOrNull(f, "title")
                        val site = strOrNull(f, "site_name")
                        val snippet = strOrNull(f, "snippet")
                        if (url.isNotBlank() || !title.isNullOrBlank()) {
                            val results =
                                if (!title.isNullOrBlank() || !site.isNullOrBlank() || !snippet.isNullOrBlank()) {
                                    listOf(Source(title = title ?: url, url = url, site = site, snippet = snippet))
                                } else emptyList()
                            steps.add(Step.Fetch(url = url, results = results))
                        }
                    }

                    // 其余片段类型（TEMPLATE_RESPONSE / TIP / CONTENT_RISK / FILE / READ_LINK 等）第一期直接丢弃
                    else -> Unit
                }
            }
        }

        val text = textBuilder.toString()
        // 失败回复只有正文片段时保留纯文本，让 UI 用红色错误样式渲染
        val finalSteps =
            if (status == ChatMsg.STATUS_FAILED && steps.isNotEmpty() && steps.all { it is Step.Text }) {
                emptyList()
            } else {
                steps
            }
        return ChatMsg(
            id = localId,
            fromMe = false,
            text = text,
            steps = finalSteps,
            status = status
        )
    }

    private fun fromArchiveStatus(status: String?): String = when (status) {
        "WIP" -> ChatMsg.STATUS_FINISHED // 导入的未完成回复按「已停止」处理，避免一直显示打字光标
        "CONTENT_FILTER" -> ChatMsg.STATUS_REFUSED
        "FAILED",
        "CONTEXT_LENGTH_EXCEEDED",
        "TIMEOUT" -> ChatMsg.STATUS_FAILED

        else -> ChatMsg.STATUS_FINISHED
    }

    private fun parseQueries(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val q = arr.optString(i)
            if (q.isNotBlank()) out.add(q)
        }
        return out
    }

    private fun parseSources(arr: JSONArray?): List<Source> {
        if (arr == null) return emptyList()
        val out = ArrayList<Source>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = strOrNull(o, "url").orEmpty()
            if (url.isBlank()) continue
            out.add(
                Source(
                    title = strOrNull(o, "title")?.takeIf { it.isNotBlank() } ?: url,
                    url = url,
                    site = strOrNull(o, "site_name")?.takeIf { it.isNotBlank() },
                    snippet = strOrNull(o, "snippet")?.takeIf { it.isNotBlank() }
                )
            )
        }
        return out
    }

    // ---------------- 工具 ----------------

    /** optString 对 JSON null 会返回 "null"，这里统一成真正的 null */
    private fun strOrNull(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key)

    /** 本地时间戳 → ISO8601 UTC（2026-11-11T02:33:23.270Z） */
    private fun formatIso(timeMs: Long): String {
        val fmt = SimpleDateFormat(ISO_PATTERN, Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(timeMs))
    }

    /** ISO8601 UTC → 本地时间戳；解析失败返回 null（由调用方兜底） */
    private fun parseIso(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // 文档说明小数秒精度不固定，先统一补/截断成 3 位毫秒再交给 SimpleDateFormat
        val normalized = normalizeFraction(text) ?: text
        val patterns = arrayOf(
            ISO_PATTERN,
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.S'Z'"
        )
        for (p in patterns) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(normalized)?.time
            } catch (_: Exception) {
                // 换下一种格式
            }
        }
        return null
    }

    /** 2026-11-11T02:33:23.27Z → 2026-11-11T02:33:23.270Z（不足补 0，超出截断到毫秒） */
    private fun normalizeFraction(text: String): String? {
        val m = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d+))?(Z)$").find(text)
            ?: return null
        val frac = m.groupValues[2]
        val ms = if (frac.isEmpty()) "000" else (frac + "000").take(3)
        return m.groupValues[1] + "." + ms + "Z"
    }
}
