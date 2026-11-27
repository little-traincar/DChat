package com.dchat.app.data.tools

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dchat.app.data.api.CacheEpochNotice
import com.dchat.app.data.api.StablePrefix
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 第三期：某个工具有副作用/计费时，按会话档位决定「直接允许 / 弹一次审批 / 直接拒绝」 */
enum class ToolApprovalPolicy { ALLOW, ASK, DENY }

/**
 * 全局工具注册表（第一期：数据层）。
 *
 * - 内置工具（服务端 / 本地）+ 用户自建工具都在这**一个**注册表里，没有「按会话启用」这一层；
 * - 持久化到 `filesDir/tools.json`，结构为 `{"version":1,"tools":[...]}`；
 * - 首次加载 / 旧版本升级时把内置 seed 合并进来：**已有同 id 的工具保留用户改过的 enabled**
 *   （`option` 当前档位同样保留），元数据（名称/说明/需求/计费）以内置 seed 为准；
 * - 一次性迁移旧 `SharedPreferences` 字段（toolSearch/toolReadUrl/toolAsr/toolTts/toolOcr/
 *   thinkEnabled/safetyLevel），迁移结果写回旧 prefs（镜像），让过渡期旧代码路径不失效；
 * - 写文件先写 `.part` 再 `rename` 原子替换；JSON 坏了备份成 `tools.json.bad` 并用 seed 重建，不崩。
 *
 * 线程模型：所有公开方法在同一把锁上串行化，读接口返回快照。
 * 本期不接 UI，[addUserTool] / [updateUserTool] / [removeUserTool] 先把接口留好。
 */
object ToolRegistry {

    private const val TAG = "ToolRegistry"
    private const val FILE_NAME = "tools.json"
    private const val BAD_FILE_NAME = "tools.json.bad"
    private const val CURRENT_VERSION = 2
    private const val USER_PREFIX = "user."
    private const val EMPTY_SCHEMA =
        """{"type":"object","properties":{},"additionalProperties":false}"""

    // 4c：内置原生工具的 JSON Schema（模型请求里作为 input_schema）
    private const val FS_PATH_SCHEMA =
        """{"type":"object","properties":{"path":{"type":"string","description":"文件或目录路径；相对路径默认在当前会话工作区，shared/ 为共享区，saf:<授权名>/ 为外部授权目录"}},"required":["path"],"additionalProperties":false}"""
    private const val FS_WRITE_SCHEMA =
        """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"],"additionalProperties":false}"""
    private const val FS_MOVE_SCHEMA =
        """{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":["from","to"],"additionalProperties":false}"""
    private const val BG_RUN_SCHEMA =
        """{"type":"object","properties":{"action":{"type":"string","enum":["run_user_tool","http_request","notify","python"],"description":"受控动作：http_request / run_user_tool / notify / python（python 需设置里开启 python 工具）"},"params":{"type":"object","description":"http_request=url/method/headers/body；python=code/timeout_seconds；run_user_tool=tool_id/args；notify=title/text"}},"required":["action","params"],"additionalProperties":false}"""
    private const val SCHEDULE_CREATE_SCHEMA =
        """{"type":"object","properties":{"name":{"type":"string"},"action":{"type":"string","enum":["run_user_tool","http_request","notify","python"],"description":"受控动作：http_request / run_user_tool / notify / python（python 需设置里开启 python 工具）"},"params":{"type":"object"},"interval_minutes":{"type":"integer","description":"最小 15"},"daily_at":{"type":"string","description":"HH:mm，每天固定时间"}},"required":["name","action","params"],"additionalProperties":false}"""
    private const val IMAGEGEN_SCHEMA =
        """{"type":"object","properties":{"prompt":{"type":"string","description":"完整画面描述（中文即可）：主体内容、构图、风格、主色调/氛围。图生图时描述期望的修改/风格"},"image":{"type":"string","description":"图生图参考图：当前会话工作区相对路径、http(s) URL 或 data:image/*;base64,..."},"provider":{"type":"string","description":"通道名（不填用设置里的默认通道）：doubao / qwen / 自定义通道名"},"model":{"type":"string","description":"本次覆盖的模型 id（不填用通道配置的模型）"},"aspect":{"type":"string","enum":["1:1","2:3","3:4","9:16","16:9"],"description":"长宽比，不填用设置默认值（默认 2:3）"},"count":{"type":"integer","description":"生成张数 1–4，不填用设置默认值（默认 1）"},"text_lines":{"type":"array","items":{"type":"string"},"description":"必须逐字出现在图内的文字行（最多 20 行，每行 ≤200 字），按顺序原样渲染"},"seed":{"type":"integer","description":"随机种子 0–2147483647（支持的通道生效）"}},"required":["prompt"],"additionalProperties":false}"""

    // ---- 内置工具 id ----
    private const val ID_WEB_SEARCH = "server.web_search"
    private const val ID_READ_URL = "server.read_url"
    private const val ID_THINKING = "server.thinking"
    private const val ID_DOCUMENT_PARSE = "server.document_parse"
    private const val ID_OCR = "server.ocr"
    private const val ID_IMAGE_UNDERSTAND = "server.image_understand"
    private const val ID_ASR = "server.asr"
    private const val ID_TTS = "server.tts"
    private const val ID_SAFETY = "server.safety"
    private const val ID_TITLE = "server.title"
    private const val ID_LOCATION = "local.location"
    private const val ID_CAMERA = "local.camera"
    private const val ID_GALLERY = "local.gallery"
    private const val ID_PYTHON = "local.python"
    private const val ID_DOWNLOAD = "local.download"
    private const val ID_LOCAL_WEB_SEARCH = "local.web_search"
    private const val ID_LOCAL_WEB_FETCH = "local.web_fetch"
    private const val ID_LOCAL_TOOL_LIST = "local.tool_list"
    private const val ID_IMAGEGEN = "local.imagegen"
    /** 第五期：模型自建用户工具（写 entry.py + tool.json 并注册） */
    private const val ID_TOOL_CREATE = "local.tool_create"
    // 4c：内置原生工具（文件 / 后台任务 / 定时任务）
    private const val ID_FS_LIST = "fs.list"
    private const val ID_FS_READ = "fs.read"
    private const val ID_FS_WRITE = "fs.write"
    private const val ID_FS_APPEND = "fs.append"
    private const val ID_FS_MKDIR = "fs.mkdir"
    private const val ID_FS_MOVE = "fs.move"
    private const val ID_FS_DELETE = "fs.delete"
    private const val ID_BG_RUN = "bg.run"
    private const val ID_BG_LIST = "bg.list"
    private const val ID_BG_CANCEL = "bg.cancel"
    private const val ID_SCHEDULE_CREATE = "schedule.create"
    private const val ID_SCHEDULE_LIST = "schedule.list"
    private const val ID_SCHEDULE_CANCEL = "schedule.cancel"

    // ---- 旧 prefs 字段（一次性迁移 + 镜像回写用）----
    private const val PREF_TOOL_SEARCH = "toolSearch"
    private const val PREF_TOOL_READ_URL = "toolReadUrl"
    private const val PREF_TOOL_ASR = "toolAsr"
    private const val PREF_TOOL_TTS = "toolTts"
    private const val PREF_TOOL_OCR = "toolOcr"
    private const val PREF_TOOL_TITLE = "toolTitle"
    private const val PREF_THINK_ENABLED = "thinkEnabled"
    private const val PREF_SAFETY_LEVEL = "safetyLevel"

    private const val SAFETY_HIGH = "high"
    private const val SAFETY_LOW = "low"
    private const val SAFETY_OFF = "off"

    // 会话权限档位取值（与 AppState.Conversation.permission 一致）
    const val CONV_READ = "read"
    const val CONV_WORKSPACE = "workspace"
    const val CONV_FULL = "full"

    private const val COST_SERVER = "需联网 · 计费"
    private const val COST_LOCAL = "本机 · 免费"

    private val NET = setOf("NET")

    /** 需要「一次性审批」的工具：上传文件到服务端、可能产生费用 */
    private val APPROVAL_REQUIRED = setOf(ID_DOCUMENT_PARSE, ID_OCR)

    private val lock = Any()

    /** 有序注册表（先 seed 顺序，再文件里的用户/未知工具），只在 [lock] 内读写 */
    private val index = LinkedHashMap<String, ToolSpec>()

    @Volatile
    private var initialized = false
    private var prefsRef: SharedPreferences? = null
    private var toolsFile: File? = null

    /**
     * Compose 可观察的只读快照（第二期接入 UI）：
     * 设置页 / 会话页 chip 读这个属性，任何启用状态、档位或工具增删都会触发重组。
     * 每次变更都在 [lock] 内发布一份新的不可变列表，不会让 UI 看到半更新状态。
     */
    var tools by mutableStateOf<List<ToolSpec>>(emptyList())
        private set

    private fun publishLocked() {
        tools = index.values.toList()
    }

    // ---------------------------------------------------------------- 初始化

    /**
     * 加载 / 创建 `filesDir/tools.json`，合并内置 seed，执行一次性旧字段迁移。
     * 幂等：同进程内重复调用直接返回。任何磁盘/解析异常都不会抛出。
     */
    fun init(context: Context, prefs: SharedPreferences) {
        var appRef: Context? = null
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext ?: context
            appRef = app
            prefsRef = prefs
            val file = File(app.filesDir, FILE_NAME)
            toolsFile = file
            try {
                loadLocked(file, prefs)
            } catch (e: Exception) {
                // 兜底：哪怕读盘逻辑出意外也用 seed 起来，绝不因工具数据崩应用
                Log.w(TAG, "加载 tools.json 失败，回退内置 seed", e)
                index.clear()
                seedTools().forEach { index[it.id] = it }
                mirrorLegacyLocked()
                persistLocked()
            }
            publishLocked()
            initialized = true
        }
        // 4c：接上原生工具的持久化状态（后台任务 / 定时任务）；幂等，失败不影响启动
        appRef?.let { LocalToolsBootstrap.ensure(it) }
    }

    private fun loadLocked(file: File, prefs: SharedPreferences) {
        var persisted: List<ToolSpec>? = null
        var migrate = false

        if (!file.exists()) {
            // 首次安装：seed + 旧字段一次性迁移（读不到就落到 seed 默认值）
            migrate = true
        } else {
            val root = runCatching { JSONObject(file.readText()) }.getOrNull()
            val toolsArray = root?.optJSONArray("tools")
            if (root == null || toolsArray == null) {
                // JSON 坏了：备份坏文件，再用内置 seed 重建
                Log.w(TAG, "tools.json 解析失败，备份为 $BAD_FILE_NAME 后重建")
                backupBadFileLocked(file)
                migrate = true
            } else {
                persisted = parseTools(toolsArray)
                // 没有 version 的文件视为旧数据，执行一次性迁移；迁移后会写回 version=1
                migrate = !hasVersion(root) || root.optInt("version", 0) < CURRENT_VERSION
            }
        }

        val merged = mergeLocked(persisted ?: emptyList(), prefs, migrate)
        index.clear()
        merged.tools.forEach { index[it.id] = it }
        if (migrate) mirrorLegacyLocked()
        if (migrate || merged.added) persistLocked()
    }

    private fun hasVersion(root: JSONObject): Boolean =
        root.has("version") && !root.isNull("version") && root.optInt("version", -1) > 0

    // ---------------------------------------------------------------- 读接口

    /** 全部工具（按分组顺序：服务器工具 → 本地工具 → 我的工具/文件里追加的） */
    fun all(): List<ToolSpec> = tools

    fun byId(id: String): ToolSpec? = tools.firstOrNull { it.id == id }

    fun bySource(source: ToolSource): List<ToolSpec> = tools.filter { it.source == source }

    /** 单个工具是否启用（非 Compose 环境用；Compose 里直接读 [tools] 即可）。 */
    fun isEnabled(id: String): Boolean = tools.firstOrNull { it.id == id }?.enabled == true

    /** 读取请求级枚举档位的当前值（如 `server.safety` 的 `level`）。 */
    fun optionValue(id: String, key: String): String? =
        tools.firstOrNull { it.id == id }?.option?.takeIf { it.key == key }?.default

    // ---------------------------------------------------------------- 会话权限

    /**
     * 当前会话权限档位归一化：`read` / `workspace` / `full`，未知值回落 `workspace`。
     * 与 [Conversation.permission] 的持久化取值保持一致。
     */
    fun normalizeConversationMode(mode: String?): String {
        val m = mode.orEmpty()
        return if (m == CONV_READ || m == CONV_WORKSPACE || m == CONV_FULL) m else CONV_WORKSPACE
    }

    private fun conversationRank(mode: String?): Int = when (normalizeConversationMode(mode)) {
        CONV_READ -> 0
        CONV_FULL -> 2
        else -> 1
    }

    private fun modeRank(mode: ToolMode): Int = when (mode) {
        ToolMode.READ_ONLY -> 0
        ToolMode.WORKSPACE_WRITE -> 1
        ToolMode.FULL -> 2
    }

    /**
     * 第三期执行前校验：工具 `minMode` 排名 ≤ 当前会话档位排名才允许执行。
     * 工具不存在 / 未启用也返回 false（调用方按「不可执行」处理）。
     */
    fun canExecute(toolId: String, conversationMode: String): Boolean {
        val spec = byId(toolId) ?: return false
        if (!spec.enabled) return false
        return modeRank(spec.minMode) <= conversationRank(conversationMode)
    }

    /**
     * 从注册表一次生成模型请求所需的工具开关（第三期统一构造入口）：
     * 启用的工具 + 当前会话权限可执行才放进请求；内容安全取 option.level。
     */
    fun requestConfig(conversationMode: String): ToolRequestConfig {
        fun usable(id: String): ToolSpec? =
            byId(id)?.takeIf { it.enabled && canExecute(id, conversationMode) }

        val safetyLevel = usable(ID_SAFETY)?.option?.default.orEmpty()
        // 4a-2：客户端工具（local.python + 用户自建）以 custom tool 形式进请求；
        // 只放「已启用且当前会话权限可执行」的，服务端工具仍保持 type 形式。
        val clientTools = all()
            .filter {
                it.id == ID_PYTHON || it.source == ToolSource.USER || LocalToolRunner.handles(it.id)
            }
            .filter { it.enabled && canExecute(it.id, conversationMode) }
            .map {
                ClientToolSpec(
                    name = it.name,
                    description = it.description,
                    // schema 字段顺序也固定，避免 params map/JSONObject 迭代顺序造成前缀抖动
                    inputSchema = StablePrefix.canonicalJsonText(it.paramsSchema)
                )
            }
            .sortedBy { it.name }
        val clientArray = JSONArray()
        clientTools.forEach { t ->
            clientArray.put(
                JSONObject()
                    .put("name", t.name)
                    .put("description", t.description)
                    .put("input_schema", runCatching { JSONObject(t.inputSchema) }.getOrDefault(JSONObject()))
            )
        }
        val toolsForHash = JSONObject()
            .put("thinking", usable(ID_THINKING) != null)
            .put("web_search", usable(ID_WEB_SEARCH) != null)
            .put("read_url", usable(ID_READ_URL) != null)
            .put("safety", safetyLevel)
            .put("clientTools", clientArray)
        val toolsEpochHash = StablePrefix.sha256(StablePrefix.canonicalJson(toolsForHash))
        return ToolRequestConfig(
            thinking = usable(ID_THINKING) != null,
            // 1.0.3：搜索/阅读改为客户端 local.web_search/local.web_fetch，不再声明服务端内置工具
            webSearch = false,
            readUrl = false,
            safetyLevel = if (safetyLevel == SAFETY_HIGH || safetyLevel == SAFETY_LOW) {
                safetyLevel
            } else {
                SAFETY_OFF
            },
            title = usable(ID_TITLE) != null,
            clientTools = clientTools,
            toolsEpochHash = toolsEpochHash
        )
    }

    /**
     * 审批策略（第三期）：
     * - 需要审批的工具（文件解析、OCR：会上传并可能计费）：
     *   read 直接拒绝（不弹窗）、workspace 弹一次、full 免审批；
     * - 其它工具不走审批（是否可执行由 [canExecute] 决定）。
     */
    fun approvalPolicy(toolId: String, conversationMode: String): ToolApprovalPolicy {
        if (toolId !in APPROVAL_REQUIRED) return ToolApprovalPolicy.ALLOW
        return when (normalizeConversationMode(conversationMode)) {
            CONV_READ -> ToolApprovalPolicy.DENY
            CONV_FULL -> ToolApprovalPolicy.ALLOW
            else -> ToolApprovalPolicy.ASK
        }
    }

    /** 单次调用超时（按工具声明，默认 10s）；未知工具回落默认值 */
    fun timeoutMs(toolId: String): Int = byId(toolId)?.timeoutMs ?: 10_000

    // ---------------------------------------------------------------- 启用 / 档位

    /**
     * 更新启用状态并持久化；同时把对应的旧 prefs 字段镜像回写（过渡期旧代码仍有效）。
     * id 不存在时静默忽略（UI 传旧 id 不崩）。
     */
    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            val current = index[id] ?: return
            if (current.enabled == enabled) return
            if (enabled) {
                // 设置里关掉再打开 = 立即清除该工具的熔断暂停，给用户一个恢复入口
                ToolHealth.clearTool(id)
            }
            var updated = current.copy(enabled = enabled)
            // 内容安全：从「off 档」重新打开时回到 high，避免出现 enabled=true 但档位是 off 的矛盾态
            if (enabled && id == ID_SAFETY) {
                val option = updated.option
                if (option != null && option.default == SAFETY_OFF) {
                    updated = updated.copy(
                        option = option.copy(default = option.values.firstOrNull { it != SAFETY_OFF } ?: SAFETY_HIGH)
                    )
                }
            }
            if (updated != current) {
                index[id] = updated
                publishLocked()
                persistLocked()
            }
            // 状态没变也镜像一次，顺手修好可能过期的旧 prefs
            mirrorOneLocked(updated)
        }
        val reason = when (id) {
            ID_THINKING -> CacheEpochNotice.THINKING
            ID_SAFETY -> CacheEpochNotice.SAFETY
            else -> CacheEpochNotice.TOOLS
        }
        if (reason == CacheEpochNotice.TOOLS) CacheEpochNotice.logOnly(reason)
        else CacheEpochNotice.notify(reason)
    }

    /**
     * 设置某个工具的请求级枚举档位（如 `server.safety` 的 `level`）。
     * 内容安全切到 `off` 时同步把 enabled 置 false，切回 high/low 时置 true。
     *
     * @return 是否命中工具且档位合法
     */
    fun setOption(id: String, key: String, value: String): Boolean {
        synchronized(lock) {
            val current = index[id] ?: return false
            val option = current.option ?: return false
            if (option.key != key || value !in option.values) return false
            var updated = current.copy(option = option.copy(default = value))
            if (id == ID_SAFETY) {
                updated = updated.copy(enabled = value != SAFETY_OFF)
            }
            index[id] = updated
            publishLocked()
            persistLocked()
            mirrorOneLocked(updated)
            if (id == ID_SAFETY) CacheEpochNotice.notify(CacheEpochNotice.SAFETY)
            else CacheEpochNotice.logOnly(CacheEpochNotice.TOOLS)
            return true
        }
    }

    // ---------------------------------------------------------------- 用户工具（本期不接 UI）

    /**
     * 新建一个用户工具（id 自动生成为 `user.py.xxx`，builtIn=false）。
     *
     * @return 新建的 spec；名称为空 / 重名 / option 非法时返回 null（UI 需提示）
     */
    fun addUserTool(
        name: String,
        description: String,
        paramsSchema: String = EMPTY_SCHEMA,
        needs: Set<String> = emptySet(),
        option: ToolOption? = null,
        minMode: ToolMode = ToolMode.READ_ONLY,
        enabled: Boolean = false
    ): ToolSpec? {
        synchronized(lock) {
            val trimmedName = name.trim()
            val trimmedDesc = description.trim()
            if (trimmedName.isEmpty() || trimmedDesc.isEmpty()) return null
            if (nameTakenLocked(trimmedName, exceptId = null)) return null
            val normalizedOption = if (option == null) null else (normalizeOption(option) ?: return null)
            val spec = ToolSpec(
                id = nextUserIdLocked(trimmedName),
                name = trimmedName,
                description = trimmedDesc,
                source = ToolSource.USER,
                paramsSchema = paramsSchema.ifBlank { EMPTY_SCHEMA },
                needs = needs,
                option = normalizedOption,
                minMode = minMode,
                enabled = enabled,
                builtIn = false,
                version = 1
            )
            index[spec.id] = spec
            publishLocked()
            persistLocked()
            return spec
        }
    }

    /**
     * 更新用户工具（只传要改的字段；版本号自动 +1）。内置工具不能走这个接口。
     *
     * @return 更新后的 spec；不存在 / 不是用户工具 / 名称冲突或非法时返回 null
     */
    fun updateUserTool(
        id: String,
        name: String? = null,
        description: String? = null,
        paramsSchema: String? = null,
        needs: Set<String>? = null,
        option: ToolOption? = null,
        enabled: Boolean? = null,
        minMode: ToolMode? = null
    ): ToolSpec? {
        synchronized(lock) {
            val current = index[id] ?: return null
            if (current.builtIn || current.source != ToolSource.USER) return null

            val newName = if (name == null) current.name else name.trim()
            if (newName.isEmpty()) return null
            if (nameTakenLocked(newName, exceptId = id)) return null

            val newDesc = if (description == null) current.description else description.trim()
            if (newDesc.isEmpty()) return null

            val newOption = if (option == null) current.option else (normalizeOption(option) ?: return null)

            val updated = current.copy(
                name = newName,
                description = newDesc,
                paramsSchema = paramsSchema?.ifBlank { EMPTY_SCHEMA } ?: current.paramsSchema,
                needs = needs ?: current.needs,
                option = newOption,
                enabled = enabled ?: current.enabled,
                minMode = minMode ?: current.minMode,
                version = current.version + 1
            )
            index[id] = updated
            publishLocked()
            persistLocked()
            return updated
        }
    }

    /** 删除用户工具；内置工具返回 false（不会被删）。 */
    fun removeUserTool(id: String): Boolean {
        synchronized(lock) {
            val current = index[id] ?: return false
            if (current.builtIn || current.source != ToolSource.USER) return false
            index.remove(id)
            publishLocked()
            persistLocked()
            return true
        }
    }

    /**
     * 第四期：把磁盘上的用户工具定义 upsert 进注册表（id/name/说明/schema/权限/入口/依赖）。
     * 与 addUserTool 的区别：允许调用方指定 `user.` 开头的 id，并保留用户声明的 minMode/version。
     * @return 是否成功（id 非法/名称冲突/内置工具占位时 false）
     */
    fun upsertUserTool(spec: ToolSpec): Boolean {
        synchronized(lock) {
            val id = spec.id.trim()
            if (!id.startsWith(USER_PREFIX)) return false
            if (spec.name.isBlank() || spec.description.isBlank()) return false
            val existing = index[id]
            if (existing != null && existing.builtIn) return false
            if (nameTakenLocked(spec.name, exceptId = id)) return false
            index[id] = spec.copy(
                id = id,
                source = ToolSource.USER,
                builtIn = false,
                paramsSchema = spec.paramsSchema.ifBlank { EMPTY_SCHEMA }
            )
            publishLocked()
            persistLocked()
            return true
        }
    }

    private fun nameTakenLocked(name: String, exceptId: String?): Boolean =
        index.values.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) }

    private fun nextUserIdLocked(name: String): String {
        val slug = name.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9') c else '_' }
            .joinToString("")
            .trim('_')
            .take(24)
        val base = USER_PREFIX + "py." +
            slug.ifBlank { UUID.randomUUID().toString().replace("-", "").take(8) }
        var candidate = base
        var n = 2
        while (index.containsKey(candidate)) {
            candidate = "$base.$n"
            n++
        }
        return candidate
    }

    private fun normalizeOption(option: ToolOption): ToolOption? {
        val key = option.key.trim()
        val values = option.values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (key.isEmpty() || values.isEmpty()) return null
        val default = option.default.trim().takeIf { it in values } ?: values.first()
        return ToolOption(key, values, default)
    }

    // ---------------------------------------------------------------- 内置 seed

    private fun seedTools(): List<ToolSpec> = listOf(
        serverTool(
            ID_THINKING, "深度思考",
            "让模型先进行更深入的推理再作答，返回带推理过程的最终回答（会消耗更多 tokens）。",
            enabled = false
        ),
        serverTool(
            ID_DOCUMENT_PARSE, "文件解析",
            "解析用户上传的 PDF、Word、PPT 等文档，返回可用于问答的文本内容。",
            enabled = true,
            timeoutMs = 180_000
        ),
        serverTool(
            ID_OCR, "图片文字识别",
            "识别图片中的文字，返回图片里的文本内容。",
            enabled = false,
            hidden = true,
            timeoutMs = 120_000
        ),
        serverTool(
            ID_IMAGE_UNDERSTAND, "图片理解",
            "理解用户上传的图片画面，返回对截图、照片或图表的描述与结论。",
            enabled = true
        ),
        serverTool(
            ID_ASR, "语音识别",
            "把用户的语音转写成文字，返回识别出的文本内容。",
            enabled = false
        ),
        serverTool(
            ID_TTS, "语音合成",
            "把助手回复合成为语音，返回可播放的语音音频。",
            enabled = false
        ),
        serverTool(
            ID_SAFETY, "内容安全",
            "对请求内容做内容安全审核，返回是否命中风险以及被拦截的位置（命中时回答会被替换为拒答文案）。",
            enabled = true,
            option = ToolOption("level", listOf(SAFETY_HIGH, SAFETY_LOW, SAFETY_OFF), SAFETY_HIGH)
        ),
        serverTool(
            ID_TITLE, "会话标题",
            "在首次回答时顺带生成简短的会话标题，返回标题文本（不额外计费）。",
            enabled = true
        ),
        localTool(
            ID_LOCATION, "定位",
            "获取设备当前位置，返回经纬度与地址名称（需定位权限）。",
            needs = setOf("LOCATION")
        ),
        localTool(
            ID_CAMERA, "拍照",
            "调用相机拍摄一张照片，返回照片文件供后续识别或发送（需相机权限）。",
            needs = setOf("CAMERA"),
            // 拍照会往应用缓存落一份文件，要求至少「工作区内修改」
            minMode = ToolMode.WORKSPACE_WRITE
        ),
        localTool(
            ID_GALLERY, "相册",
            "从系统相册选择图片，返回图片文件供后续识别或发送（需读取相册权限）。",
            needs = setOf("FS_READ")
        ),
        // 4a-2：内置代码解释器。执行任意代码，要求至少「工作区内修改」；
        // needs 含 NET，但是否真的能联网由 PyRuntime 按会话档位再判一次。
        localTool(
            ID_PYTHON, "python",
            "在沙箱中执行 Python 代码并返回输出。适合计算、字符串处理、调用 HTTP API 等。",
            needs = setOf("NET"),
            minMode = ToolMode.WORKSPACE_WRITE,
            // 首次调用要冷启动解释器 + import 运行器，5s 会出现假超时；给到 20s
            timeoutMs = 20_000,
            paramsSchema = """{"type":"object","properties":{"code":{"type":"string","description":"要执行的 Python 代码"},"purpose":{"type":"string"}},"required":["code"],"additionalProperties":false}"""
        ),
        // 第五期：让模型自己创建 / 注册用户工具（用户问"能不能做个工具注册进 App"时不再说做不到）
        localTool(
            ID_TOOL_CREATE, "tool_create",
            "创建一个新的用户工具并注册到 App：写 Python 代码（入口 def run(args: dict) -> str）+ 元信息，"
                + "落盘到工具目录并挂进工具列表，下一轮即可直接调用。同名工具已存在时覆盖更新（版本号自增）。"
                + "需要工具带 UI 页面时，传 ui_mode=schema 并用 ui 给出 UI Schema v2，例如："
                + "{\"version\":2,\"title\":\"天气\",\"data\":{\"city\":\"上海\",\"temp\":\"26°C\"},\"blocks\":[{\"type\":\"card\",\"children\":[{\"type\":\"keyvalue\",\"value\":{\"城市\":\"{{city}}\",\"气温\":\"{{temp}}\"}}]}]}；"
                + "可用节点：column/row/card/group/scroll、text/markdown/image/badge/progress/keyvalue/spacer/divider、input/textarea/toggle/select/slider/date/button、list、bar/line/pie；"
                + "字符串里的 {{key}} 会用 data[key] 替换。entry.py 可选实现 def load(args, state) 返回数据 dict、def action(name, args, data, state) 处理按钮动作。",
            needs = emptySet(),
            minMode = ToolMode.WORKSPACE_WRITE,
            timeoutMs = 20_000,
            // 默认开启：用户明确要求「模型应该能创建/注册工具」，不必先去设置里打开。
            // 仍受会话权限约束（仅可查看的会话里调用会被拒绝）。
            enabled = true,
            paramsSchema = UserToolCreateTool.SCHEMA
        ),
        // ---- 4c：文件工具（fs.*） ----
        localTool(
            ID_LOCAL_WEB_SEARCH, "web_search",
            "联网检索实时信息；参数 query/count/need_content，返回标题、链接、摘要。",
            needs = NET,
            minMode = ToolMode.READ_ONLY,
            timeoutMs = 60_000,
            enabled = true,
            paramsSchema = """{"type":"object","properties":{"query":{"type":"string"},"count":{"type":"integer"},"need_content":{"type":"boolean"}},"required":["query"],"additionalProperties":false}"""
        ),
        localTool(
            ID_LOCAL_WEB_FETCH, "web_fetch",
            "读取一个 http/https 网页并返回标题与 Markdown 正文。",
            needs = NET,
            minMode = ToolMode.READ_ONLY,
            timeoutMs = 60_000,
            enabled = true,
            paramsSchema = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"],"additionalProperties":false}"""
        ),
        localTool(
            ID_LOCAL_TOOL_LIST, "tool_list",
            "列出当前会话实际可下发给模型的工具及未下发原因，供模型自查。",
            needs = emptySet(),
            minMode = ToolMode.READ_ONLY,
            timeoutMs = 10_000,
            enabled = true
        ),
        localTool(
            ID_DOWNLOAD, "download",
            "下载一个 HTTPS 文件到当前会话工作区，返回文件信息。",
            needs = emptySet(),
            minMode = ToolMode.WORKSPACE_WRITE,
            timeoutMs = 60_000,
            paramsSchema = """{"type":"object","properties":{"url":{"type":"string"},"filename":{"type":"string"},"subdir":{"type":"string"}},"required":["url"],"additionalProperties":false}"""
        ),
        // 移植 DSH imagegen：模型可调用的生图工具（默认豆包 Seedream，可切千问 / 自定义通道）
        localTool(
            ID_IMAGEGEN, "generate_image",
            "生成一张或多张高质量图片（默认豆包 Seedream 5.0 Pro；可切千问 qwen-image-3.0-pro 或设置里的自定义 OpenAI 兼容通道）。"
                + "图内文字用 text_lines 逐字给出；默认竖版 2:3；默认 1 张，用户要多张候选才传 count；"
                + "图生图传 image（会话工作区相对路径 / http(s) URL / data URI）。"
                + "调用前若用户没说清视觉风格、配色或图内文字，先问用户。",
            needs = setOf("NET"),
            minMode = ToolMode.WORKSPACE_WRITE,
            // 生图慢：任务书要求 300s（实际超时还受设置页的「超时」控制）
            timeoutMs = 300_000,
            // 默认开启：设置页填好 key 即可用；模型看不到未启用的工具
            enabled = true,
            paramsSchema = IMAGEGEN_SCHEMA
        ),
        localTool(
            ID_FS_LIST, "fs_list",
            "列出目录内容（名称/类型/大小/修改时间）。相对路径默认在当前会话工作区；shared/ 为共享区；saf:<授权名>/ 为已授权外部目录；空路径=工作区根。",
            needs = setOf("FS_READ"),
            timeoutMs = 15_000,
            paramsSchema = FS_PATH_SCHEMA
        ),
        localTool(
            ID_FS_READ, "fs_read",
            "读取文本文件内容（超过 64KB 截断；二进制或超过 10MB 只返回元信息）。",
            needs = setOf("FS_READ"),
            timeoutMs = 15_000,
            paramsSchema = FS_PATH_SCHEMA
        ),
        localTool(
            ID_FS_WRITE, "fs_write",
            "写入（覆盖）文本文件，自动创建父目录，原子写。工作区内首次会弹一次审批；工作区外需要完全权限并逐次审批；单文件上限 10MB。",
            needs = setOf("FS_WRITE"),
            minMode = ToolMode.WORKSPACE_WRITE,
            timeoutMs = 15_000,
            paramsSchema = FS_WRITE_SCHEMA
        ),
        localTool(
            ID_FS_APPEND, "fs_append",
            "向文本文件追加内容（文件不存在则创建）。权限规则同 fs_write。",
            needs = setOf("FS_WRITE"),
            minMode = ToolMode.WORKSPACE_WRITE,
            timeoutMs = 15_000,
            paramsSchema = FS_WRITE_SCHEMA
        ),
        localTool(
            ID_FS_MKDIR, "fs_mkdir",
            "创建目录（含父目录）。权限规则同 fs_write。",
            needs = setOf("FS_WRITE"),
            minMode = ToolMode.WORKSPACE_WRITE,
            timeoutMs = 15_000,
            paramsSchema = FS_PATH_SCHEMA
        ),
        localTool(
            ID_FS_MOVE, "fs_move",
            "移动或重命名文件/目录（from → to）。目标已存在时失败；跨本地/授权目录时先复制再删除。",
            needs = setOf("FS_WRITE"),
            minMode = ToolMode.WORKSPACE_WRITE,
            timeoutMs = 15_000,
            paramsSchema = FS_MOVE_SCHEMA
        ),
        localTool(
            ID_FS_DELETE, "fs_delete",
            "删除文件或目录（递归）。工作区档即可，但每次调用都会弹审批；工作区外还需完全权限。",
            needs = setOf("FS_WRITE"),
            minMode = ToolMode.WORKSPACE_WRITE,
            timeoutMs = 15_000,
            paramsSchema = FS_PATH_SCHEMA
        ),
        // ---- 4c：后台任务（bg.*，最低完全权限） ----
        localTool(
            ID_BG_RUN, "bg_run",
            "提交后台任务并立即返回 task_id。action 白名单：run_user_tool / http_request / notify / python（python 动作还要求设置里开启 python 工具）。并发上限 5，超出排队。",
            needs = setOf("NET"),
            minMode = ToolMode.FULL,
            timeoutMs = 10_000,
            paramsSchema = BG_RUN_SCHEMA
        ),
        localTool(
            ID_BG_LIST, "bg_list",
            "列出后台任务的状态、参数摘要与结果（按创建时间倒序）。",
            needs = emptySet(),
            minMode = ToolMode.FULL,
            timeoutMs = 10_000,
            paramsSchema = """{"type":"object","properties":{"limit":{"type":"integer","description":"最多返回多少条，默认 50"}},"additionalProperties":false}"""
        ),
        localTool(
            ID_BG_CANCEL, "bg_cancel",
            "取消排队中或运行中的后台任务。",
            needs = emptySet(),
            minMode = ToolMode.FULL,
            timeoutMs = 10_000,
            paramsSchema = """{"type":"object","properties":{"task_id":{"type":"string"}},"required":["task_id"],"additionalProperties":false}"""
        ),
        // ---- 4c：定时任务（schedule.*，最低完全权限，数量不限） ----
        localTool(
            ID_SCHEDULE_CREATE, "schedule_create",
            "创建周期定时任务，返回 schedule_id。interval_minutes 最小 15；或用 daily_at(HH:mm) 每天固定时间。触发后执行与 bg 相同的受控动作。",
            needs = emptySet(),
            minMode = ToolMode.FULL,
            timeoutMs = 10_000,
            paramsSchema = SCHEDULE_CREATE_SCHEMA
        ),
        localTool(
            ID_SCHEDULE_LIST, "schedule_list",
            "列出已创建的全部定时任务（含下次触发时间）。",
            needs = emptySet(),
            minMode = ToolMode.FULL,
            timeoutMs = 10_000,
            paramsSchema = EMPTY_SCHEMA
        ),
        localTool(
            ID_SCHEDULE_CANCEL, "schedule_cancel",
            "取消一个定时任务。",
            needs = emptySet(),
            minMode = ToolMode.FULL,
            timeoutMs = 10_000,
            paramsSchema = """{"type":"object","properties":{"schedule_id":{"type":"string"}},"required":["schedule_id"],"additionalProperties":false}"""
        )
    )

    private fun serverTool(
        id: String,
        name: String,
        description: String,
        enabled: Boolean,
        option: ToolOption? = null,
        hidden: Boolean = false,
        timeoutMs: Int = 10_000
    ): ToolSpec = ToolSpec(
        id = id,
        name = name,
        description = description,
        source = ToolSource.SERVER,
        needs = NET,
        option = option,
        costNote = COST_SERVER,
        enabled = enabled,
        timeoutMs = timeoutMs,
        hidden = hidden
    )

    private fun localTool(
        id: String,
        name: String,
        description: String,
        needs: Set<String>,
        minMode: ToolMode = ToolMode.READ_ONLY,
        timeoutMs: Int = 10_000,
        enabled: Boolean = false,
        paramsSchema: String = EMPTY_SCHEMA
    ): ToolSpec = ToolSpec(
        id = id,
        name = name,
        description = description,
        source = ToolSource.LOCAL,
        paramsSchema = paramsSchema,
        needs = needs,
        costNote = COST_LOCAL,
        minMode = minMode,
        enabled = enabled,
        timeoutMs = timeoutMs
    )

    // ---------------------------------------------------------------- seed 合并 / 旧字段迁移

    private data class MergedResult(val tools: List<ToolSpec>, val added: Boolean)

    private fun mergeLocked(
        persisted: List<ToolSpec>,
        prefs: SharedPreferences,
        migrate: Boolean
    ): MergedResult {
        // 文件里已有的条目按 id 索引；seed 合并后剩下的就是用户工具/未知工具，原样保留
        val remaining = LinkedHashMap<String, ToolSpec>()
        persisted.forEach { remaining[it.id] = it }

        val out = ArrayList<ToolSpec>(persisted.size + 16)
        var added = false
        // 1.0.3：旧 server.web_search / server.read_url 静默淘汰，状态迁到新的 local 工具
        val legacySearch = remaining.remove(ID_WEB_SEARCH)?.enabled
            ?: legacyBool(prefs, PREF_TOOL_SEARCH, true)
        val legacyRead = remaining.remove(ID_READ_URL)?.enabled
            ?: legacyBool(prefs, PREF_TOOL_READ_URL, true)
        val hadLocalSearch = remaining.containsKey(ID_LOCAL_WEB_SEARCH)
        val hadLocalFetch = remaining.containsKey(ID_LOCAL_WEB_FETCH)
        seedTools().forEach { seed ->
            val old = remaining.remove(seed.id)
            if (old == null) {
                added = true
                var migrated = if (migrate) migrateSeedLocked(seed, prefs) else seed
                if (!hadLocalSearch && seed.id == ID_LOCAL_WEB_SEARCH) {
                    migrated = migrated.copy(enabled = legacySearch)
                }
                if (!hadLocalFetch && seed.id == ID_LOCAL_WEB_FETCH) {
                    migrated = migrated.copy(enabled = legacyRead)
                }
                out.add(migrated)
            } else {
                out.add(mergeBuiltInLocked(seed, old))
            }
        }
        out.addAll(remaining.values)
        return MergedResult(out, added)
    }

    /**
     * 内置工具以代码里的 seed 为元数据准绳；enabled 与 option 当前档位是用户状态，
     * 保留文件里的值（满足「已有同 id 保留用户改过的 enabled，不覆盖」）。
     */
    private fun mergeBuiltInLocked(seed: ToolSpec, persisted: ToolSpec): ToolSpec {
        val seedOption = seed.option
        val option = if (seedOption == null) {
            null
        } else {
            val oldOption = persisted.option
            if (oldOption != null && oldOption.key == seedOption.key) {
                val default = oldOption.default.takeIf { it in seedOption.values } ?: seedOption.default
                seedOption.copy(default = default)
            } else {
                seedOption
            }
        }
        return seed.copy(
            enabled = if (seed.hidden) false else persisted.enabled,
            option = option,
            hidden = seed.hidden
        )
    }

    /** 一次性迁移：旧 prefs 有值用旧值，读不到用 seed 默认值。 */
    private fun migrateSeedLocked(seed: ToolSpec, prefs: SharedPreferences): ToolSpec =
        when (seed.id) {
            ID_WEB_SEARCH -> seed.copy(enabled = legacyBool(prefs, PREF_TOOL_SEARCH, seed.enabled))
            ID_READ_URL -> seed.copy(enabled = legacyBool(prefs, PREF_TOOL_READ_URL, seed.enabled))
            ID_ASR -> seed.copy(enabled = legacyBool(prefs, PREF_TOOL_ASR, seed.enabled))
            ID_TTS -> seed.copy(enabled = legacyBool(prefs, PREF_TOOL_TTS, seed.enabled))
            // OCR 已统一改用「图片理解」：强制关闭并隐藏（代码保留）
            ID_OCR -> seed.copy(enabled = false, hidden = true)
            ID_DOCUMENT_PARSE -> seed.copy(enabled = legacyBool(prefs, PREF_TOOL_OCR, seed.enabled))
            ID_THINKING -> seed.copy(enabled = legacyBool(prefs, PREF_THINK_ENABLED, seed.enabled))
            ID_SAFETY -> {
                val level = legacySafetyLevel(prefs)
                seed.copy(
                    enabled = level != SAFETY_OFF,
                    option = seed.option?.copy(default = level)
                )
            }
            else -> seed
        }

    private fun legacyBool(prefs: SharedPreferences, key: String, fallback: Boolean): Boolean =
        if (prefs.contains(key)) prefs.getBoolean(key, fallback) else fallback

    private fun legacySafetyLevel(prefs: SharedPreferences): String {
        val value = if (prefs.contains(PREF_SAFETY_LEVEL)) {
            prefs.getString(PREF_SAFETY_LEVEL, "").orEmpty()
        } else {
            ""
        }
        return if (value == SAFETY_HIGH || value == SAFETY_LOW || value == SAFETY_OFF) {
            value
        } else {
            SAFETY_HIGH
        }
    }

    // ---------------------------------------------------------------- 镜像回写旧 prefs

    private fun safetyLevelOf(spec: ToolSpec): String {
        if (!spec.enabled) return SAFETY_OFF
        val level = spec.option?.default
        return if (level == SAFETY_HIGH || level == SAFETY_LOW) level else SAFETY_HIGH
    }

    /** 迁移后一次性把结果镜像回旧 prefs（只回写迁移涉及过的字段）。 */
    private fun mirrorLegacyLocked() {
        val prefs = prefsRef ?: return
        val editor = prefs.edit()
        index[ID_WEB_SEARCH]?.let { editor.putBoolean(PREF_TOOL_SEARCH, it.enabled) }
        index[ID_READ_URL]?.let { editor.putBoolean(PREF_TOOL_READ_URL, it.enabled) }
        index[ID_ASR]?.let { editor.putBoolean(PREF_TOOL_ASR, it.enabled) }
        index[ID_TTS]?.let { editor.putBoolean(PREF_TOOL_TTS, it.enabled) }
        index[ID_OCR]?.let { editor.putBoolean(PREF_TOOL_OCR, it.enabled) }
        index[ID_THINKING]?.let { editor.putBoolean(PREF_THINK_ENABLED, it.enabled) }
        index[ID_SAFETY]?.let { editor.putString(PREF_SAFETY_LEVEL, safetyLevelOf(it)) }
        editor.apply()
    }

    /** 单个工具的 setEnabled/setOption 镜像回旧 prefs，保证过渡期旧代码路径一致。 */
    private fun mirrorOneLocked(spec: ToolSpec) {
        val prefs = prefsRef ?: return
        val editor = prefs.edit()
        when (spec.id) {
            ID_WEB_SEARCH -> editor.putBoolean(PREF_TOOL_SEARCH, spec.enabled)
            ID_READ_URL -> editor.putBoolean(PREF_TOOL_READ_URL, spec.enabled)
            ID_ASR -> editor.putBoolean(PREF_TOOL_ASR, spec.enabled)
            ID_TTS -> editor.putBoolean(PREF_TOOL_TTS, spec.enabled)
            ID_OCR -> editor.putBoolean(PREF_TOOL_OCR, spec.enabled)
            ID_THINKING -> editor.putBoolean(PREF_THINK_ENABLED, spec.enabled)
            ID_TITLE -> editor.putBoolean(PREF_TOOL_TITLE, spec.enabled)
            ID_SAFETY -> editor.putString(PREF_SAFETY_LEVEL, safetyLevelOf(spec))
            else -> return
        }
        editor.apply()
    }

    // ---------------------------------------------------------------- 磁盘读写

    /** 先写 `tools.json.part` 再 rename 原子替换；失败只记日志，不抛。 */
    private fun persistLocked(): Boolean {
        val file = toolsFile ?: return false
        return try {
            val root = JSONObject().put("version", CURRENT_VERSION)
            val array = JSONArray()
            index.values.forEach { array.put(it.toJson()) }
            root.put("tools", array)
            val text = root.toString()
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(text)
            if (!part.renameTo(file)) {
                // 个别实现下目标存在时 rename 失败：删掉目标再试一次，仍失败就直接覆写
                file.delete()
                if (!part.renameTo(file)) {
                    file.writeText(text)
                    part.delete()
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "写入 tools.json 失败", e)
            false
        }
    }

    private fun backupBadFileLocked(file: File) {
        try {
            val bad = File(file.parentFile, BAD_FILE_NAME)
            file.copyTo(bad, overwrite = true)
            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "备份坏文件 $BAD_FILE_NAME 失败", e)
        }
    }

    // ---------------------------------------------------------------- JSON 序列化

    private fun ToolSpec.toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("description", description)
        o.put("source", source.name)
        o.put("paramsSchema", paramsSchema)
        o.put("needs", JSONArray(needs.toList()))
        o.put("costNote", costNote)
        o.put("minMode", minMode.name)
        o.put("enabled", enabled)
        o.put("builtIn", builtIn)
        o.put("version", version)
        o.put("timeoutMs", timeoutMs)
        o.put("entryFunction", entryFunction)
        o.put("requirements", JSONArray(requirements))
        o.put("uiMode", uiMode)
        o.put("hidden", hidden)
        option?.let {
            o.put(
                "option",
                JSONObject()
                    .put("key", it.key)
                    .put("values", JSONArray(it.values))
                    .put("default", it.default)
            )
        }
        return o
    }

    private fun parseTools(array: JSONArray): List<ToolSpec> {
        val out = ArrayList<ToolSpec>(array.length())
        val seen = HashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val spec = parseTool(o) ?: continue
            if (!seen.add(spec.id)) continue
            out.add(spec)
        }
        return out
    }

    private fun parseTool(o: JSONObject): ToolSpec? {
        val id = optText(o, "id").trim()
        if (id.isEmpty()) return null
        val isUser = id.startsWith(USER_PREFIX)
        val source = when (optText(o, "source").trim().uppercase()) {
            "SERVER" -> ToolSource.SERVER
            "LOCAL" -> ToolSource.LOCAL
            "USER" -> ToolSource.USER
            else -> if (isUser) ToolSource.USER else return null
        }
        val name = optText(o, "name").trim()
        if (name.isEmpty()) return null
        val minMode = when (optText(o, "minMode").trim().uppercase()) {
            "WORKSPACE_WRITE" -> ToolMode.WORKSPACE_WRITE
            "FULL" -> ToolMode.FULL
            else -> ToolMode.READ_ONLY
        }
        val needs = LinkedHashSet<String>()
        o.optJSONArray("needs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val need = arr.optString(i).trim()
                if (need.isNotEmpty()) needs.add(need)
            }
        }
        val paramsSchema = optText(o, "paramsSchema").ifBlank { EMPTY_SCHEMA }
        return ToolSpec(
            id = id,
            name = name,
            description = optText(o, "description"),
            source = source,
            paramsSchema = paramsSchema,
            needs = needs,
            option = parseOption(o.optJSONObject("option")),
            costNote = optText(o, "costNote"),
            minMode = minMode,
            enabled = o.optBoolean("enabled", false),
            // 用户工具（source=USER 或 id 以 user. 开头）一律 builtIn=false
            builtIn = if (source == ToolSource.USER || isUser) false else o.optBoolean("builtIn", true),
            version = o.optInt("version", 1).coerceAtLeast(1),
            timeoutMs = o.optInt("timeoutMs", 10_000).coerceAtLeast(1_000),
            entryFunction = optText(o, "entryFunction").ifBlank { "run" },
            requirements = jsonStringList(o.optJSONArray("requirements")),
            uiMode = optText(o, "uiMode").ifBlank { "data" },
            hidden = o.optBoolean("hidden", false)
        )
    }

    private fun parseOption(o: JSONObject?): ToolOption? {
        if (o == null) return null
        val key = optText(o, "key").trim()
        val values = ArrayList<String>()
        o.optJSONArray("values")?.let { arr ->
            for (i in 0 until arr.length()) {
                val value = arr.optString(i).trim()
                if (value.isNotEmpty()) values.add(value)
            }
        }
        if (key.isEmpty() || values.isEmpty()) return null
        val default = optText(o, "default").trim().takeIf { it in values } ?: values.first()
        return ToolOption(key, values, default)
    }

    private fun optText(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.optString(key, "")

    private fun jsonStringList(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
}
