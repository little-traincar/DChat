package com.dchat.app.data.api

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 缓存前缀稳定化 + 诊断（D76 / 缓存阶段 A）。
 *
 * - [StablePrefix]：递归按 key 排序的 JSON 序列化 + SHA-256；
 * - [PrefixCacheSession]：AppState 绑定当前会话与已存 toolsEpochHash；
 * - [DCache]：只做观测，记录 prefixHash / 三段 hash / 命中率，不改变请求内容。
 */
object StablePrefix {
    fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val out = JSONObject()
            val keys = value.keys().asSequence().toList().sorted()
            keys.forEach { k -> out.put(k, canonical(value.opt(k))) }
            out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) out.put(canonical(value.opt(i)))
            out
        }
        else -> value
    }

    fun canonicalJson(value: Any?): String = canonical(value).toString()

    /** 把任意 JSON 字符串规范成 key 有序的字符串；非法 JSON 回退空对象 schema */
    fun canonicalJsonText(raw: String): String = runCatching {
        canonicalJson(JSONObject(raw))
    }.getOrDefault("""{"type":"object","properties":{}}""")

    fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}

/** AppState 在切换会话时绑定；DeepSeekApi 读取它来按会话诊断 */
object PrefixCacheSession {
    @Volatile
    var conversationId: String = ""
    @Volatile
    var toolsEpochHash: String = ""
    @Volatile
    private var persist: ((String, String) -> Unit)? = null

    fun bind(conversationId: String?, toolsEpochHash: String?, persist: ((String, String) -> Unit)?) {
        synchronized(this) {
            this.conversationId = conversationId.orEmpty()
            this.toolsEpochHash = toolsEpochHash.orEmpty()
            this.persist = persist
        }
    }

    fun persistEpoch(hash: String, forConversationId: String) {
        if (forConversationId.isBlank()) return
        synchronized(this) {
            if (forConversationId == conversationId) toolsEpochHash = hash
            persist?.invoke(forConversationId, hash)
        }
    }
}

/**
 * 不依赖 logcat 的逐轮缓存诊断文件：filesDir/dcache.log（上限 200KB，滚动保留后一半）。
 * 全量导出 zip 会带上 diagnostics/dcache.log。
 */
object DCacheLog {
    private const val MAX_BYTES = 200 * 1024
    private var file: File? = null

    fun attach(f: File) {
        file = f
    }

    @Synchronized
    fun write(line: String) {
        val f = file ?: return
        runCatching {
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.takeLast(MAX_BYTES / 2))
            }
        }
    }
}

/**
 * 只读观测器。同一个 cacheKey 记录上一轮 system/tools/history 三段 hash 和用量；
 * 日志 tag 固定为 DCache，便于 adb logcat 过滤。
 */
object DCache {

    data class Snapshot(
        val reqId: String,
        val prefixHash: String,
        val systemHash: String,
        val toolsHash: String,
        val historyHash: String,
        val fullHistoryHash: String,
        val toolNames: Set<String>,
        val messageCount: Int = 0,
        val cacheRead: Int = 0,
        val prompt: Int = 0,
        val fingerprints: List<String> = emptyList()
    )

    private const val TAG = "DCache"
    private val lock = Any()
    private val last = HashMap<String, Snapshot>()
    private val byReq = HashMap<String, Snapshot>()
    private val lastUsage = HashMap<String, Pair<Int, Int>>()

    fun previousToolNames(key: String): Set<String>? = synchronized(lock) { last[key]?.toolNames }

    fun previousMessageCount(key: String): Int = synchronized(lock) { last[key]?.messageCount ?: 0 }

    /** 请求构造完成后调用；返回是否变化以及三段各自是否变化 */
    fun onRequest(
        key: String,
        reqId: String,
        systemHash: String,
        toolsHash: String,
        historyHash: String,
        prefixHash: String,
        toolNames: Set<String>,
        messageCount: Int,
        fullHistoryHash: String,
        newTurns: Int,
        fingerprints: List<String>
    ): String? {
        val prev = synchronized(lock) { last[key] }
        val changed = prev != null && prev.prefixHash != prefixHash
        val sysChanged = prev != null && prev.systemHash != systemHash
        val toolsChanged = prev != null && prev.toolsHash != toolsHash
        // 本轮的「稳定前缀」应与上一轮完整 messages 对齐；新增 tail 单独 newTurns。
        val histChanged = prev != null && prev.fullHistoryHash != historyHash
        var firstDiff = -1
        if (prev != null) {
            val old = prev.fingerprints
            val upto = minOf(old.size, fingerprints.size)
            for (i in 0 until upto) {
                if (old[i] != fingerprints[i]) {
                    firstDiff = i
                    break
                }
            }
            if (firstDiff < 0 && fingerprints.size < old.size) firstDiff = fingerprints.size
        }
        val snapshot = Snapshot(
            reqId, prefixHash, systemHash, toolsHash, historyHash, fullHistoryHash, toolNames,
            messageCount, prev?.cacheRead ?: 0, prev?.prompt ?: 0, fingerprints
        )
        synchronized(lock) {
            last[key] = snapshot
            byReq[reqId] = snapshot
            if (byReq.size > 128) byReq.remove(byReq.keys.first())
            if (last.size > 64) last.remove(last.keys.first())
        }
        val reason = if (!changed) "stable"
        else buildString {
            append("changed")
            if (sysChanged) append(" system")
            if (toolsChanged) append(" tools")
            if (histChanged) append(" history")
        }
        val fpStr = fingerprints.joinToString("|")
        Log.i(
            TAG,
            "req=$reqId prefix=$prefixHash state=$reason sys=$systemHash tools=$toolsHash " +
                "history=$historyHash newTurns=$newTurns firstDiff=$firstDiff " +
                "prevCacheRead=${prev?.cacheRead ?: 0} prevPrompt=${prev?.prompt ?: 0} key=$key fps=$fpStr"
        )
        DCacheLog.write(
            "request req=$reqId prefix=$prefixHash state=$reason sys=$systemHash tools=$toolsHash " +
                "history=$historyHash newTurns=$newTurns firstDiff=$firstDiff " +
                "prevCacheRead=${prev?.cacheRead ?: 0} prevPrompt=${prev?.prompt ?: 0} key=$key fps=$fpStr"
        )
        return if (changed) reason else null
    }

    fun onUsage(reqId: String, key: String, source: String, cacheRead: Int, prompt: Int) {
        // 只记录，不逐事件打日志；下一轮 onRequest 带上一轮命中数。
        val snapshot = synchronized(lock) { byReq[reqId] ?: last[key] } ?: return
        synchronized(lock) {
            // 命中率分母用 delta 的累计输入（服务端工具定义只计入 delta，start 会少 998）
            if (source == "message_delta") {
                byReq[reqId] = snapshot.copy(cacheRead = cacheRead, prompt = prompt)
                if (last[key]?.reqId == reqId) {
                    last[key] = snapshot.copy(cacheRead = cacheRead, prompt = prompt)
                }
            }
            if (lastUsage[reqId] == (cacheRead to prompt)) return
            lastUsage[reqId] = cacheRead to prompt
        }
        val rate = if (prompt > 0) "%.1f".format(cacheRead * 100.0 / prompt) else "0.0"
        DCacheLog.write(
            "usage req=$reqId source=$source prefix=${snapshot.prefixHash} " +
                "cacheRead=$cacheRead prompt=$prompt hit=${rate}% key=$key"
        )
    }

    fun reset() {
        synchronized(lock) { last.clear() }
    }
}
