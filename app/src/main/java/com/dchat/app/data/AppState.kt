package com.dchat.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dchat.app.data.api.CacheEpochNotice
import com.dchat.app.data.api.DCacheLog
import com.dchat.app.data.api.PrefixCacheSession
import com.dchat.app.data.tools.BgTasks
import com.dchat.app.data.tools.FileTools
import com.dchat.app.data.tools.ImageGenSettings
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.data.tools.ScheduleStore
import com.dchat.app.data.tools.ToolHealth
import com.dchat.app.data.tools.ToolRegistry
import com.dchat.app.data.tools.UserToolStore
import com.dchat.app.ui.lang.Lang
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 搜索来源（第一期只用于展示） */
data class SourceRef(val title: String, val url: String)

/** 一条消息 */
data class ChatMsg(
    val id: Long = System.nanoTime(),
    val fromMe: Boolean,
    val text: String = "",
    /**
     * 缓存阶段 A1：这一轮真正发给模型的 user 原文（含 OCR/位置等补充）。
     * 回放历史时直接用它，避免 modelContent 因语言/位置格式化漂移；旧消息为空时回退。
     * 只用于构造模型请求，不写入导出 zip / 分享文案。
     */
    val modelText: String = "",
    /** 缓存阶段 A：assistant 本轮原始 content 块 JSON（thinking+signature/text/tool_use）；旧为空 */
    val rawAssistant: String = "",
    /** rawAssistant 超 32KB 被丢弃时为 true；回退旧拼装 */
    val rawTruncated: Boolean = false,
    /** 本轮实际追加给模型的完整工具链（assistant/tool_result/.../最终 assistant）JSON 数组 */
    val rawChain: String = "",
    /** 助手消息的步骤时间线；有值时优先按步骤渲染 */
    val steps: List<Step> = emptyList(),
    val status: String = STATUS_FINISHED,
    /**
     * 是否是一条"语音消息"：只有语音合成打开后、用户新发出并刚结束的回复才会置 true。
     * 历史回复保持 false，即便之后再打开语音合成也仍然按纯文字显示。
     */
    val voice: Boolean = false,
    /** 本条回复消耗的输入 tokens（含缓存命中部分）；旧数据 / 未记录时为 0 */
    val promptTokens: Int = 0,
    /** 本条回复消耗的输出 tokens；旧数据 / 未记录时为 0 */
    val completionTokens: Int = 0,
    /** promptTokens 中命中上下文缓存的部分（按 DeepSeek 缓存命中价计费）；旧数据为 0 */
    val cacheReadTokens: Int = 0,
    /**
     * 内容安全审核消耗的 tokens（`usage.output_tokens_details.safety_tokens`）。
     * 官方说明它已包含在 [completionTokens] 里，这里单独记录用于核对/展示，不要二次相加。
     * 旧数据 / 未开启内容安全时为 0。
     */
    val safetyTokens: Int = 0,
    /** 生成这条回复的模型名（按模型统计与计价用）；旧数据为空 */
    val modelName: String = "",
    /** 消息时间戳（毫秒）。AI 回复写完成时间；0 = 旧数据未知 */
    val at: Long = 0L,
    /** 用户消息附带的本地图片路径（filesDir/chat_images/…）；旧数据 / 无图片为空 */
    val images: List<String> = emptyList(),
    /** 位置消息的纬度；null = 不是位置消息 */
    val lat: Double? = null,
    /** 位置消息的经度；null = 不是位置消息 */
    val lng: Double? = null,
    /** 位置主标题（地址名，如「成都市武侯区…」）；旧数据为空 */
    val place: String = "",
    /** 位置副标题（详细地址或坐标）；旧数据为空 */
    val placeDetail: String = "",
    /**
     * 这条用户消息随带的「用户元信息标记」（DeepSeek latest_reminder）内容；
     * 空 = 发这条时没有带。只在语言/地区变化后的第一次发送时写入，之后随历史原样回传。
     * 不参与任何界面渲染，纯上下文用。
     */
    val reminder: String = "",
    /** [reminder] 对应的「语言|地区」标识，用来判断下次要不要重新告知 */
    val reminderKey: String = "",
    /**
     * 本条用户消息引用的服务端文件解析结果（Files API 的 file_id，PDF/Word/PPT/图片等）。
     * 历史轮会随消息重放，保证多轮还能基于同一份文档提问；旧数据为空。
     */
    val docs: List<String> = emptyList()
) {
    companion object {
        const val STATUS_FINISHED = "FINISHED"
        const val STATUS_WIP = "WIP"
        const val STATUS_FAILED = "FAILED"
        /** 用户主动停止生成：保留已生成正文，气泡显示「已停止」 */
        const val STATUS_STOPPED = "STOPPED"

        /**
         * 内容安全拒答：服务端返回 `stop_reason: "refusal"` 时使用。
         * 已展示的正文会被清空，换成中性拒答文案；UI 用普通文字色，不用红色错误样式。
         */
        const val STATUS_REFUSED = "REFUSED"
    }
}

/** 一个会话 */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    // 存储用的中性占位标题：显示时由 Str.conversationTitle() 按当前界面语言翻译，
    // 所以老数据里的「新对话」不会因为切语言而变成别的语言（也不需要迁移数据）。
    val title: String = "新对话",
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 本会话权限档位：`read`（仅可查看）/ `workspace`（工作区内修改）/ `full`（完全权限）。
     * 第三期起工具全局可用、权限按会话；老数据缺省/未知值统一回落 [PERMISSION_WORKSPACE]。
     */
    val permission: String = PERMISSION_WORKSPACE,
    /** 缓存阶段 A：本会话最近一次 system+tools 的稳定 hash（旧数据为空，首轮自动生成） */
    val toolsEpochHash: String = "",
    val messages: List<ChatMsg> = emptyList()
) {
    companion object {
        const val PERMISSION_READ = "read"
        const val PERMISSION_WORKSPACE = "workspace"
        const val PERMISSION_FULL = "full"

        /** 权限读写归一化：只认三档，其它值（含 null / 旧数据）一律 workspace */
        fun normalizePermission(raw: String?): String {
            val p = raw.orEmpty()
            return if (p == PERMISSION_READ || p == PERMISSION_WORKSPACE || p == PERMISSION_FULL) {
                p
            } else {
                PERMISSION_WORKSPACE
            }
        }
    }
}

/** 全部本地设置 */
data class AppSettings(
    val followSystem: Boolean = false,
    val darkTheme: Boolean = false,
    val fontScale: Float = 1f,
    val modelName: String = "",
    val modelNickname: String = "DChat",
    val systemPrompt: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val apiKey: String = "",
    /** 0 表示无上限 */
    val toolRounds: Int = 0,
    /** 工具开关 */
    val toolSearch: Boolean = true,
    val toolReadUrl: Boolean = true,
    val toolAsr: Boolean = false,
    val toolOcr: Boolean = false,
    val toolTts: Boolean = false,
    /** 会话标题：让服务端在第一次回答时顺带生成（官方 conversation_title），不额外计费 */
    val toolTitle: Boolean = false,
    /**
     * 内容安全档位（官方 safety_check）：
     * `high` 基础风控（默认，推荐）、`low` API 默认检查、`off` 请求体里完全不出现该字段。
     */
    val safetyLevel: String = "high",
    /** 语音消息音色：存官方 voice_id（stella / tide），空 = 默认女声 stella */
    val ttsVoice: String = "",
    /** 对话页「深度思考」开关，持久化，重启后保持 */
    val thinkEnabled: Boolean = false,
    val myAvatar: String? = null,
    val modelAvatar: String? = null,
    val bgImage: String? = null,
    /** 0 铺满 / 1 拉伸 / 2 截取 */
    val bgScale: Int = 0,
    val bgPreset: Int = 0,
    /** 界面语言：en / ja / zh / ko（见 ui/lang/AppLanguage） */
    val language: String = "zh",
    /** 地区：uk / jp / cn / kr（影响日期数字货币格式，并作为用户元信息传给模型） */
    val region: String = "cn",
    /** 图像生成（local.imagegen）：默认通道 / 密钥 / 出图参数 */
    val imageGen: ImageGenSettings = ImageGenSettings()
)

/**
 * 全局状态 + 本地持久化。
 * 第一期：设置存 SharedPreferences，会话存 filesDir/conversations.json。
 * （会话量变大后再换 Room，接口保持不变。）
 */
class AppState(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("dchat_settings", Context.MODE_PRIVATE)
    private val convoFile = File(ctx.filesDir, "conversations.json")

    private companion object {
        /** 「删除所有数据」时清理的聊天缓存目录（相对 filesDir） */
        val CHAT_CACHE_DIRS = listOf("chat_images", "tts")
    }

    var settings: AppSettings by mutableStateOf(loadSettings())
        private set

    /** 解析 conversations.json 失败时的用户提示；由 ChatScreen 消费，不静默重置 */
    var storageWarning: String? by mutableStateOf(null)
        private set

    var conversations: List<Conversation> by mutableStateOf(loadConversations())
        private set

    var currentId: String? by mutableStateOf(
        prefs.getString("currentId", null)?.takeIf { id -> conversations.any { it.id == id } }
            ?: conversations.firstOrNull()?.id
    )
        private set

    init {
        // 缓存诊断文件：不依赖 logcat；全量导出时 DeepSeekZip 会带上
        DCacheLog.attach(File(ctx.filesDir, "dcache.log"))
        // 工具注册表（第一期数据层）：内部幂等，加载/迁移失败会回退内置 seed，不影响启动。
        ToolRegistry.init(ctx, prefs)
        // 第四期：把磁盘上的用户 Python 工具同步进注册表；Python 运行时后台初始化，失败降级
        UserToolStore.syncIntoRegistry(ctx)
        PyRuntime.initAsync(ctx)
        bindCacheSession()
    }

    /** 缓存诊断：把当前会话/epoch 绑定给 DeepSeekApi；epoch 变化异步落回 Conversation */
    private fun bindCacheSession() {
        val handler = Handler(Looper.getMainLooper())
        PrefixCacheSession.bind(currentId, current()?.toolsEpochHash) { id, hash ->
            handler.post {
                conversations = conversations.map {
                    if (it.id == id) it.copy(toolsEpochHash = hash) else it
                }
                persistConversations()
            }
        }
    }

    // ---------- 会话 ----------

    fun current(): Conversation? = conversations.firstOrNull { it.id == currentId }

    /** 删除确认用：工作区文件数与总字节数 */
    fun workspaceStats(id: String): Pair<Int, Long> {
        val dir = FileTools.workspaceDir(ctx, id)
        if (!dir.exists()) return 0 to 0L
        var count = 0
        var size = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                count++
                size += f.length()
            }
        }
        return count to size
    }

    fun newChat(): Conversation {
        val c = Conversation()
        conversations = listOf(c) + conversations
        currentId = c.id
        prefs.edit().putString("currentId", c.id).apply()
        persistConversations()
        bindCacheSession()
        return c
    }

    /** 切换会话：把它移到列表最前面（当前会话永远置顶） */
    fun select(id: String) {
        val target = conversations.firstOrNull { it.id == id } ?: return
        conversations = listOf(target) + conversations.filterNot { it.id == id }
        currentId = id
        prefs.edit().putString("currentId", id).apply()
        persistConversations()
        bindCacheSession()
    }

    fun rename(id: String, title: String) {
        conversations = conversations.map { if (it.id == id) it.copy(title = title) else it }
        persistConversations()
    }

    /** 修改某个会话的权限档位（read / workspace / full，未知值归一到 workspace） */
    fun setPermission(id: String, permission: String) {
        val p = Conversation.normalizePermission(permission)
        val old = conversations.firstOrNull { it.id == id }?.permission
        conversations = conversations.map { if (it.id == id) it.copy(permission = p) else it }
        persistConversations()
        if (old != p) CacheEpochNotice.notify(CacheEpochNotice.PERMISSION)
    }

    fun delete(id: String) {
        BgTasks.removeForConversation(id)
        ScheduleStore.removeForConversation(ctx, id)
        ToolHealth.clearConversation(id)
        runCatching { FileTools.workspaceDir(ctx, id).deleteRecursively() }
        conversations = conversations.filterNot { it.id == id }
        if (currentId == id) {
            currentId = conversations.firstOrNull()?.id
            prefs.edit().putString("currentId", currentId).apply()
        }
        if (conversations.isEmpty()) newChat()
        persistConversations()
        bindCacheSession()
    }

    fun clearAll() {
        conversations = emptyList()
        newChat()
    }

    /**
     * 删除所有数据（设置 → 数据 → 删除所有数据）：
     * 清空会话列表、把当前会话置空、删除图片/语音缓存目录，设置本身保持不变。
     * 空状态下仍可新建会话（[addMessage] 会兜底创建）或从文件导入。
     */
    fun clearAllData() {
        BgTasks.removeAll()
        ScheduleStore.removeAll(ctx)
        ToolHealth.clearAll()
        runCatching { File(ctx.filesDir, FileTools.WORKSPACES_DIR).deleteRecursively() }
        conversations = emptyList()
        currentId = null
        bindCacheSession()
        // 先把 SharedPreferences 里的 currentId 清掉，避免重启后悬空引用旧会话
        prefs.edit().remove("currentId").apply()
        // 持久化成空列表：下次启动不会再把旧会话读回来
        persistConversations()
        // 图片 / 语音缓存只删除目录内容，目录本身留着（建目录逻辑已经能自动重建）
        CHAT_CACHE_DIRS.forEach { name ->
            runCatching { File(ctx.filesDir, name).deleteRecursively() }
        }
        // 语音试听缓存落在 cacheDir/tts，同样属于语音缓存
        runCatching { File(ctx.cacheDir, "tts").deleteRecursively() }
    }

    /**
     * 批量追加会话（导入历史记录用）。
     * 只追加、绝不覆盖或删除已有会话；id 与现有会话（或本批内前面项）冲突时重新生成。
     * 列表按传入顺序追加到末尾，返回实际插入的会话。
     */
    fun addConversations(list: List<Conversation>): List<Conversation> {
        if (list.isEmpty()) return emptyList()
        val used = conversations.mapTo(HashSet()) { it.id }
        val inserted = list.map { incoming ->
            var id = incoming.id.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            while (!used.add(id)) id = UUID.randomUUID().toString()
            incoming.copy(id = id)
        }
        conversations = conversations + inserted
        // 空状态（删除所有数据后）导入：没有当前会话时选中第一条导入项，否则回到会话页
        // 还是空态，容易让人以为导入没成功。select 会把它置顶并持久化；空列表导入时
        // 它本来就是第一条，所以「追加到列表末尾」的顺序不变。
        if (currentId == null) {
            select(inserted.first().id)
        } else {
            persistConversations()
        }
        return inserted
    }

    /** 追加消息；首条用户消息会自动成为会话标题 */
    fun addMessage(msg: ChatMsg) {
        val id = currentId ?: newChat().id
        conversations = conversations.map { c ->
            if (c.id != id) c
            else {
                val title =
                    if (c.messages.isEmpty() && msg.fromMe) {
                        msg.text.replace('\n', ' ').take(18).ifBlank { messagePreview(msg) }
                    } else c.title
                c.copy(
                    messages = c.messages + msg,
                    updatedAt = System.currentTimeMillis(),
                    title = title.ifBlank { c.title }
                )
            }
        }
        persistConversations()
    }

    fun updateMessage(msgId: Long, block: (ChatMsg) -> ChatMsg) {
        val id = currentId ?: return
        conversations = conversations.map { c ->
            if (c.id != id) c
            else c.copy(messages = c.messages.map { if (it.id == msgId) block(it) else it })
        }
        persistConversations()
    }

    /** 流式追加用：只改内存，不落盘 */
    fun updateMessageLite(msgId: Long, block: (ChatMsg) -> ChatMsg) {
        val id = currentId ?: return
        conversations = conversations.map { c ->
            if (c.id != id) c
            else c.copy(messages = c.messages.map { if (it.id == msgId) block(it) else it })
        }
    }

    // ---------- 设置 ----------

    fun updateSettings(block: (AppSettings) -> AppSettings) {
        settings = block(settings)
        saveSettings()
    }

    /** 把选中的图片压缩后复制到私有目录（避免每次显示都解码原图） */
    fun importImage(uri: android.net.Uri, name: String, maxDim: Int = 512): String? =
        importImageTo(uri, File(ctx.filesDir, name), maxDim, 88)

    /**
     * 头像：**每次换都写新文件名**（`avatar_<kind>_<ts>.img`）。
     *
     * 以前固定写 `avatar_me.img`：路径不变 → Compose 的 `remember(path)`/图片缓存命中旧图，
     * 而且 `updateSettings` 出来的 AppSettings 在 data class 意义上"没变"，重组都会被跳过，
     * 表现为「换了头像不生效」。换文件名后路径真的变了，重组和缓存都会刷新。
     */
    fun importAvatar(uri: android.net.Uri, kind: String, maxDim: Int = 512): String? {
        val file = File(ctx.filesDir, "avatar_${kind}_${System.currentTimeMillis()}.img")
        val path = importImageTo(uri, file, maxDim, 88) ?: return null
        // 清掉同类的旧头像（含旧版固定名），避免文件越攒越多
        runCatching {
            val legacy = File(ctx.filesDir, "avatar_${kind}.img").absolutePath
            ctx.filesDir.listFiles()?.forEach { f ->
                val n = f.name
                if ((n.startsWith("avatar_${kind}_") || f.absolutePath == legacy) &&
                    f.absolutePath != path
                ) {
                    f.delete()
                }
            }
        }
        return path
    }

    /**
     * 聊天图片：落到 filesDir/chat_images/，长边 ≤1080、JPEG 质量 85（会话里只存路径）。
     * 与 importImage 同一套等比缩放逻辑；文件名带时间戳，避免覆盖后 Compose 图片缓存不刷新。
     */
    fun importChatImage(uri: android.net.Uri, maxDim: Int = 1080): String? {
        val dir = File(ctx.filesDir, "chat_images")
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = "chat_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        return importImageTo(uri, File(dir, name), maxDim, 85)
    }

    private fun importImageTo(
        uri: android.net.Uri,
        target: File,
        maxDim: Int,
        quality: Int
    ): String? = try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim * 2) sample *= 2
        val bmp = android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        val scaled = if (longest > maxDim) {
            val s = maxDim.toFloat() / longest
            android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true
            )
        } else bmp
        // 系统相机 / 相册图片常带 EXIF 方向；BitmapFactory 不自动应用，这里按方向转正后再压
        val oriented = applyExifOrientation(scaled, readExifOrientation(bytes))
        target.outputStream().use {
            oriented.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it)
        }
        if (oriented !== scaled) scaled.recycle()
        if (scaled !== bmp) bmp.recycle()
        target.absolutePath
    } catch (e: Exception) {
        runCatching { target.delete() }
        null
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(
        bmp: android.graphics.Bitmap,
        orientation: Int
    ): android.graphics.Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        return android.graphics.Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height, matrix, true
        )
    }

    // ---------- 读写 ----------

    private fun loadSettings(): AppSettings {
        return AppSettings(
            followSystem = prefs.getBoolean("followSystem", false),
            darkTheme = prefs.getBoolean("darkTheme", false),
            fontScale = prefs.getFloat("fontScale", 1f),
            modelName = prefs.getString("modelName", "") ?: "",
            modelNickname = prefs.getString("modelNickname", "DChat") ?: "DChat",
            systemPrompt = prefs.getString("systemPrompt", "") ?: "",
            baseUrl = prefs.getString("baseUrl", "https://api.deepseek.com") ?: "https://api.deepseek.com",
            apiKey = prefs.getString("apiKey", "") ?: "",
            toolRounds = prefs.getInt("toolRounds", 0),
            toolSearch = prefs.getBoolean("toolSearch", true),
            toolReadUrl = prefs.getBoolean("toolReadUrl", true),
            toolAsr = prefs.getBoolean("toolAsr", false),
            toolOcr = prefs.getBoolean("toolOcr", false),
            toolTts = prefs.getBoolean("toolTts", false),
            toolTitle = prefs.getBoolean("toolTitle", false),
            safetyLevel = prefs.getString("safetyLevel", "high") ?: "high",
            ttsVoice = prefs.getString("ttsVoice", "") ?: "",
            thinkEnabled = prefs.getBoolean("thinkEnabled", false),
            myAvatar = prefs.getString("myAvatar", null),
            modelAvatar = prefs.getString("modelAvatar", null),
            bgImage = prefs.getString("bgImage", null),
            bgScale = prefs.getInt("bgScale", 0),
            bgPreset = prefs.getInt("bgPreset", 0),
            language = prefs.getString("language", "zh") ?: "zh",
            region = prefs.getString("region", "cn") ?: "cn",
            imageGen = ImageGenSettings.fromPrefs(prefs)
        )
    }

    private fun saveSettings() {
        val s = settings
        // 工具开关以 ToolRegistry 为准（第二期起设置页/会话页 chip 都直接写注册表）；
        // 这里把旧字段写成注册表当前值，避免「改完工具再改其它设置」把镜像值覆盖回旧的。
        val editor = prefs.edit()
            .putBoolean("followSystem", s.followSystem)
            .putBoolean("darkTheme", s.darkTheme)
            .putFloat("fontScale", s.fontScale)
            .putString("modelName", s.modelName)
            .putString("modelNickname", s.modelNickname)
            .putString("systemPrompt", s.systemPrompt)
            .putString("baseUrl", s.baseUrl)
            .putString("apiKey", s.apiKey)
            .putInt("toolRounds", s.toolRounds)
            .putBoolean("toolSearch", ToolRegistry.isEnabled("server.web_search"))
            .putBoolean("toolReadUrl", ToolRegistry.isEnabled("server.read_url"))
            .putBoolean("toolAsr", ToolRegistry.isEnabled("server.asr"))
            .putBoolean("toolOcr", ToolRegistry.isEnabled("server.ocr"))
            .putBoolean("toolTts", ToolRegistry.isEnabled("server.tts"))
            .putBoolean("toolTitle", ToolRegistry.isEnabled("server.title"))
            .putString("safetyLevel", ToolRegistry.optionValue("server.safety", "level") ?: s.safetyLevel)
            .putString("ttsVoice", s.ttsVoice)
            .putBoolean("thinkEnabled", ToolRegistry.isEnabled("server.thinking"))
            .putString("myAvatar", s.myAvatar)
            .putString("modelAvatar", s.modelAvatar)
            .putString("bgImage", s.bgImage)
            .putInt("bgScale", s.bgScale)
            .putInt("bgPreset", s.bgPreset)
            .putString("language", s.language)
            .putString("region", s.region)
        // 图像生成设置：字段较多，统一交给 ImageGenSettings 写回（明文，与现有 API key 一致）
        s.imageGen.writeTo(editor)
        editor.apply()
    }

    private fun loadConversations(): List<Conversation> {
        if (!convoFile.exists()) return listOf(Conversation())
        return try {
            val arr = JSONArray(convoFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val msgs = o.optJSONArray("messages") ?: JSONArray()
                val updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                Conversation(
                    id = o.getString("id"),
                    title = o.optString("title", "新对话"),
                    updatedAt = updatedAt,
                    // 旧数据没有 permission：缺省/未知值统一回落到 workspace
                    permission = Conversation.normalizePermission(
                        if (o.has("permission") && !o.isNull("permission")) o.optString("permission") else null
                    ),
                    toolsEpochHash = o.optString("toolsEpochHash", ""),
                    messages = (0 until msgs.length()).map { j ->
                        val m = msgs.getJSONObject(j)
                        ChatMsg(
                            id = m.getLong("id"),
                            fromMe = m.getBoolean("fromMe"),
                            text = m.optString("text", ""),
                            steps = parseSteps(m.optJSONArray("steps")),
                            status = m.optString("status", ChatMsg.STATUS_FINISHED),
                            voice = m.optBoolean("voice", false),
                            promptTokens = m.optInt("promptTokens", 0),
                            completionTokens = m.optInt("completionTokens", 0),
                            cacheReadTokens = m.optInt("cacheReadTokens", 0),
                            modelText = m.optString("modelText", ""),
                            rawAssistant = m.optString("rawAssistant", ""),
                            rawTruncated = m.optBoolean("rawTruncated", false),
                            rawChain = m.optString("rawChain", ""),
                            safetyTokens = m.optInt("safetyTokens", 0),
                            modelName = m.optString("modelName", ""),
                            // 旧数据没有逐条时间：先落到 0，统计时再退化用会话 updatedAt
                            at = m.optLong("at", 0L),
                            images = strList(m.optJSONArray("images"))
                                .filter { it.isNotBlank() },
                            lat = optNullableDouble(m, "lat"),
                            lng = optNullableDouble(m, "lng"),
                            place = m.optString("place", ""),
                            placeDetail = m.optString("placeDetail", ""),
                            reminder = m.optString("reminder", ""),
                            reminderKey = m.optString("reminderKey", ""),
                            docs = strList(m.optJSONArray("docs")).filter { it.isNotBlank() }
                        )
                    }
                )
            }
            // 文件存在但为空数组 = 用户执行过「删除所有数据」，保持零会话空状态；
            // 首次安装没有该文件时才在上面的 !exists 分支给一个空白会话。
        } catch (e: Exception) {
            // 绝不静默重置：坏文件先备份保留，再给用户可操作提示
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val backup = File(ctx.filesDir, "conversations.corrupt.$stamp.json")
            runCatching {
                if (convoFile.isFile) {
                    if (!convoFile.renameTo(backup)) {
                        convoFile.copyTo(backup, overwrite = true)
                        convoFile.delete()
                    }
                }
            }
            storageWarning = StorageAlerts.corruptBackup(Lang.current.language, backup.name)
            // 不依赖 ChatScreen 的即时提示：App 启动阶段直接 Toast 一次
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, storageWarning, android.widget.Toast.LENGTH_LONG).show()
            }
            listOf(Conversation())
        }
    }

    fun clearStorageWarning() {
        storageWarning = null
    }

    private fun persistConversations() {
        try {
            val arr = JSONArray()
            conversations.forEach { c ->
                val co = JSONObject()
                co.put("id", c.id)
                co.put("title", c.title)
                co.put("updatedAt", c.updatedAt)
                co.put("permission", c.permission)
                co.put("toolsEpochHash", c.toolsEpochHash)
                val ms = JSONArray()
                val rawCutoff = c.messages.size - 30
                // rawChain 只持久化最近 10 条
                val chainCutoff = c.messages.size - 10
                c.messages.forEachIndexed { idx, m ->
                    val mo = JSONObject()
                    mo.put("id", m.id)
                    mo.put("fromMe", m.fromMe)
                    mo.put("text", m.text)
                    if (m.modelText.isNotBlank()) mo.put("modelText", m.modelText)
                    if (m.rawAssistant.isNotBlank() && idx >= rawCutoff) {
                        mo.put("rawAssistant", m.rawAssistant)
                    }
                    if (m.rawTruncated) mo.put("rawTruncated", true)
                    if (m.rawChain.isNotBlank() && idx >= chainCutoff) mo.put("rawChain", m.rawChain)
                    mo.put("status", m.status)
                    mo.put("voice", m.voice)
                    mo.put("promptTokens", m.promptTokens)
                    mo.put("completionTokens", m.completionTokens)
                    mo.put("cacheReadTokens", m.cacheReadTokens)
                    mo.put("safetyTokens", m.safetyTokens)
                    mo.put("modelName", m.modelName)
                    mo.put("at", m.at)
                    mo.put("images", JSONArray(m.images))
                    if (m.docs.isNotEmpty()) mo.put("docs", JSONArray(m.docs))
                    mo.put("lat", m.lat ?: JSONObject.NULL)
                    mo.put("lng", m.lng ?: JSONObject.NULL)
                    mo.put("place", m.place)
                    mo.put("placeDetail", m.placeDetail)
                    if (m.reminder.isNotBlank()) {
                        mo.put("reminder", m.reminder)
                        mo.put("reminderKey", m.reminderKey)
                    }
                    mo.put("steps", stepsToJson(m.steps))
                    ms.put(mo)
                }
                co.put("messages", ms)
                arr.put(co)
            }
            writeAtomic(convoFile, arr.toString())
        } catch (_: Exception) {
        }
    }

    /** 会话文件原子写：临时文件 + fsync + rename；失败保留旧文件，绝不覆盖 */
    private fun writeAtomic(target: File, text: String) {
        val part = File(target.parentFile, target.name + ".part")
        java.io.FileOutputStream(part).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!part.renameTo(target)) {
            // 少数文件系统 rename 不覆盖；先删再 rename，至少不写半截
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                throw java.io.IOException("rename conversation file failed")
            }
        }
    }
}

// ---------- 步骤序列化 ----------

/** 会话标题 / 列表预览兜底：图片消息「[图片]」、位置消息「[位置]」（跟随当前界面语言） */
private fun messagePreview(msg: ChatMsg): String = when {
    msg.images.isNotEmpty() -> com.dchat.app.ui.lang.Lang.current.chat.imagePlaceholder
    msg.lat != null && msg.lng != null -> com.dchat.app.ui.lang.Lang.current.chat.locationPlaceholder
    else -> ""
}

private fun optNullableDouble(o: JSONObject, key: String): Double? =
    if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }

private fun parseSteps(arr: JSONArray?): List<Step> {
    if (arr == null) return emptyList()
    val out = ArrayList<Step>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        when (o.optString("type")) {
            "thinking" -> out.add(Step.Thinking(o.optString("text")))
            "text" -> out.add(Step.Text(o.optString("text")))
            "search" -> out.add(
                Step.Search(
                    queries = strList(o.optJSONArray("queries")),
                    results = parseSources(o.optJSONArray("results"))
                )
            )
            "fetch" -> out.add(
                Step.Fetch(
                    url = o.optString("url"),
                    results = parseSources(o.optJSONArray("results"))
                )
            )
            "download" -> out.add(
                Step.Download(
                    filePath = o.optString("filePath"),
                    mime = o.optString("mime"),
                    sizeBytes = o.optLong("sizeBytes"),
                    status = o.optString("status", "ok"),
                    note = o.optString("note")
                )
            )
            "tool" -> out.add(
                Step.ToolCall(
                    toolId = o.optString("toolId"),
                    toolName = o.optString("toolName"),
                    args = o.optString("args"),
                    result = o.optString("result"),
                    error = o.optString("error"),
                    durationMs = o.optLong("durationMs", 0L)
                )
            )
        }
    }
    return out
}

private fun strList(a: JSONArray?): List<String> =
    if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

private fun parseSources(a: JSONArray?): List<Source> =
    if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        Source(
            title = o.optString("title"),
            url = o.optString("url"),
            site = o.optString("site").ifBlank { null },
            snippet = o.optString("snippet").ifBlank { null }
        )
    }

private fun stepsToJson(steps: List<Step>): JSONArray {
    val arr = JSONArray()
    steps.forEach { s ->
        val o = JSONObject()
        when (s) {
            is Step.Thinking -> { o.put("type", "thinking"); o.put("text", s.text) }
            is Step.Text -> { o.put("type", "text"); o.put("text", s.text) }
            is Step.Search -> {
                o.put("type", "search")
                o.put("queries", JSONArray(s.queries))
                o.put("results", sourcesToJson(s.results))
            }
            is Step.Fetch -> {
                o.put("type", "fetch")
                o.put("url", s.url)
                o.put("results", sourcesToJson(s.results))
            }
            is Step.Download -> {
                o.put("type", "download")
                o.put("filePath", s.filePath)
                o.put("mime", s.mime)
                o.put("sizeBytes", s.sizeBytes)
                o.put("status", s.status)
                o.put("note", s.note)
            }
            is Step.ToolCall -> {
                o.put("type", "tool")
                o.put("toolId", s.toolId)
                o.put("toolName", s.toolName)
                o.put("args", s.args)
                o.put("result", s.result)
                o.put("error", s.error)
                o.put("durationMs", s.durationMs)
            }
        }
        arr.put(o)
    }
    return arr
}

private fun sourcesToJson(list: List<Source>): JSONArray {
    val a = JSONArray()
    list.forEach { s ->
        a.put(
            JSONObject()
                .put("title", s.title)
                .put("url", s.url)
                .put("site", s.site ?: "")
                .put("snippet", s.snippet ?: "")
        )
    }
    return a
}
