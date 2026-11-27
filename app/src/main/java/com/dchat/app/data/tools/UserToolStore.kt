package com.dchat.app.data.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 第四期 4a：用户自建 Python 工具的磁盘存储。
 *
 * 目录（toolId 形如 `user.hello`）：
 * ```
 * filesDir/tools/<toolId>/
 *   tool.json                 元信息（名称/说明/schema/minMode/version/入口/依赖/enabled）
 *   entry.py                  用户代码（入口函数默认 run）
 *   state/<conversationId>.json  会话级状态（4b 使用；4a 只建目录/路径）
 *   sandbox/                  脚本工作目录（唯一允许读写的目录）
 *   site-packages/            安装的纯 Python 包
 * ```
 *
 * 注册表（tools.json）仍是「工具是否启用」的唯一真源；本类负责代码与文件布局，
 * 启动时把磁盘上的定义 upsert 回 [ToolRegistry]。
 */
data class UserToolDef(
    val id: String,
    val name: String,
    val description: String,
    val paramsSchema: String,
    val minMode: ToolMode,
    val entryFunction: String = "run",
    val requirements: List<String> = emptyList(),
    val version: Int = 1,
    val enabled: Boolean = false,
    val code: String = "",
    /** 4b：`data` 自动渲染 / `schema` UI 树 */
    val uiMode: String = "data"
)

object UserToolStore {

    private const val TAG = "UserToolStore"
    private const val ROOT_DIR = "tools"
    private const val TOOL_JSON = "tool.json"
    private const val ENTRY_PY = "entry.py"
    private const val SANDBOX = "sandbox"
    private const val STATE = "state"
    private const val SITE_PACKAGES = "site-packages"

    /** 名称规则：小写字母/数字/下划线，1–32 位；同时用作 toolId 后缀与目录名 */
    val NAME_REGEX = Regex("^[a-z0-9_]{1,32}$")

    private const val DEFAULT_SCHEMA =
        """{"type":"object","properties":{},"additionalProperties":false}"""

    fun toolsRoot(context: Context): File = File(context.filesDir, ROOT_DIR)

    fun toolDir(context: Context, toolId: String): File = File(toolsRoot(context), toolId)

    fun entryFile(context: Context, toolId: String): File = File(toolDir(context, toolId), ENTRY_PY)

    fun sandboxDir(context: Context, toolId: String): File =
        File(toolDir(context, toolId), SANDBOX)

    fun sitePackagesDir(context: Context, toolId: String): File =
        File(toolDir(context, toolId), SITE_PACKAGES)

    fun stateDir(context: Context, toolId: String): File =
        File(toolDir(context, toolId), STATE)

    fun stateFile(context: Context, toolId: String, conversationId: String): File =
        File(stateDir(context, toolId), "$conversationId.json")

    /** 新工具 id：`user.<name>`（磁盘目录同名，满足 brief 的 `user.<id>/` 约定） */
    fun idForName(name: String): String = "user.$name"

    /** 读取磁盘上的全部用户工具；坏 JSON / 缺 entry.py 的条目跳过，不影响 App 启动 */
    fun list(context: Context): List<UserToolDef> {
        val root = toolsRoot(context)
        if (!root.isDirectory) return emptyList()
        return (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("user.") }
            .sortedBy { it.name }
            .mapNotNull { dir ->
                runCatching { readDir(context, dir) }
                    .onFailure { Log.w(TAG, "读取用户工具失败: ${dir.name}", it) }
                    .getOrNull()
            }
    }

    fun find(context: Context, toolId: String): UserToolDef? =
        runCatching { readDir(context, toolDir(context, toolId)) }.getOrNull()

    private fun readDir(context: Context, dir: File): UserToolDef? {
        val metaFile = File(dir, TOOL_JSON)
        if (!metaFile.isFile) return null
        val codeFile = File(dir, ENTRY_PY)
        if (!codeFile.isFile) return null
        val o = JSONObject(metaFile.readText())
        val id = o.optString("id", dir.name).ifBlank { dir.name }
        val name = o.optString("name").ifBlank { id.removePrefix("user.") }
        val minMode = runCatching { ToolMode.valueOf(o.optString("minMode", "READ_ONLY")) }
            .getOrDefault(ToolMode.READ_ONLY)
        val reqs = o.optJSONArray("requirements")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        } ?: emptyList()
        val spec = ToolRegistry.byId(id)
        return UserToolDef(
            id = id,
            name = name,
            description = o.optString("description"),
            paramsSchema = o.optString("paramsSchema").ifBlank { DEFAULT_SCHEMA },
            minMode = minMode,
            entryFunction = o.optString("entryFunction").ifBlank { "run" },
            requirements = reqs,
            version = o.optInt("version", 1).coerceAtLeast(1),
            enabled = spec?.enabled ?: o.optBoolean("enabled", false),
            code = codeFile.readText(),
            uiMode = normalizeUiMode(o.optString("uiMode"))
        )
    }

    /**
     * 新建/编辑保存：写 `entry.py` + `tool.json`，再 upsert 进注册表。
     * 编辑时 version 自增；新建默认 enabled=false（用户手动开）。
     * @return 保存后的定义；名称非法/冲突/写盘失败时 null
     */
    fun save(context: Context, def: UserToolDef): UserToolDef? {
        val name = def.name.trim()
        if (!NAME_REGEX.matches(name)) return null
        if (def.description.isBlank() || def.code.isBlank()) return null
        val targetId = idForName(name)
        val oldId = def.id.trim()
        // 名称唯一（注册表内，允许排除旧 id）
        if (ToolRegistry.all().any {
                it.id != oldId && it.id != targetId && it.name.equals(name, ignoreCase = true)
            }
        ) return null
        val targetDir = toolDir(context, targetId)
        val existingDir = if (oldId.isNotBlank() && oldId != targetId) {
            val oldDir = toolDir(context, oldId)
            if (!oldDir.isDirectory) return null
            // 改名：目录整体迁移，requirements/sandbox/state/site-packages 都保留
            if (targetDir.exists()) return null
            if (!oldDir.renameTo(targetDir)) return null
            ToolRegistry.removeUserTool(oldId)
            targetDir
        } else {
            // 4b：编辑器「试运行/预览」会先 ensureDirs，留下 sandbox/state 但没有 tool.json；
            // 新建时允许复用这种预览目录，避免“预览过就保存失败”。
            if (oldId.isBlank() && targetDir.isDirectory && File(targetDir, TOOL_JSON).isFile) {
                return null
            }
            targetDir
        }
        val existing = if (existingDir.isDirectory) readDir(context, existingDir) else null
        val version = (existing?.version ?: 0) + 1
        val enabled = existing?.enabled ?: def.enabled
        val saved = def.copy(
            id = targetId,
            name = name,
            version = version,
            enabled = enabled,
            paramsSchema = def.paramsSchema.ifBlank { DEFAULT_SCHEMA }
        )
        return try {
            ensureDirs(context, targetId)
            writeAtomic(entryFile(context, targetId), saved.code)
            writeAtomic(File(existingDir, TOOL_JSON), toJson(saved).toString())
            val registered = ToolRegistry.upsertUserTool(
                ToolSpec(
                    id = saved.id,
                    name = saved.name,
                    description = saved.description,
                    source = ToolSource.USER,
                    paramsSchema = saved.paramsSchema,
                    needs = emptySet(),
                    option = null,
                    costNote = "本机 · 免费",
                    minMode = saved.minMode,
                    enabled = saved.enabled,
                    builtIn = false,
                    version = saved.version,
                    timeoutMs = 5_000,
                    entryFunction = saved.entryFunction,
                    requirements = saved.requirements,
                    uiMode = saved.uiMode
                )
            )
            if (registered) saved else null
        } catch (e: Exception) {
            Log.w(TAG, "保存用户工具失败: $name", e)
            null
        }
    }

    fun delete(context: Context, toolId: String): Boolean {
        if (!toolId.startsWith("user.")) return false
        val removed = ToolRegistry.removeUserTool(toolId)
        runCatching { toolDir(context, toolId).deleteRecursively() }
        return removed
    }

    fun setEnabled(toolId: String, enabled: Boolean) = ToolRegistry.setEnabled(toolId, enabled)

    fun addRequirements(context: Context, toolId: String, names: List<String>): UserToolDef? {
        val def = find(context, toolId) ?: return null
        val merged = (def.requirements + names).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return save(
            context,
            def.copy(
                requirements = merged,
                // save() 会 version+1；这里保留 enabled（save 内部从磁盘/注册表取）
                enabled = ToolRegistry.byId(toolId)?.enabled ?: def.enabled
            )
        )
    }

    /** App 启动时把磁盘定义同步进注册表（只 upsert，不删除注册表里已有的） */
    fun syncIntoRegistry(context: Context) {
        list(context).forEach { def ->
            val ok = ToolRegistry.upsertUserTool(
                ToolSpec(
                    id = def.id,
                    name = def.name,
                    description = def.description,
                    source = ToolSource.USER,
                    paramsSchema = def.paramsSchema,
                    option = null,
                    costNote = "本机 · 免费",
                    minMode = def.minMode,
                    enabled = def.enabled,
                    builtIn = false,
                    version = def.version,
                    timeoutMs = 5_000,
                    entryFunction = def.entryFunction,
                    requirements = def.requirements,
                    uiMode = def.uiMode
                )
            )
            if (!ok) Log.w(TAG, "同步用户工具失败（名称冲突？）: ${def.id}")
        }
    }

    fun ensureDirs(context: Context, toolId: String) {
        toolDir(context, toolId).mkdirs()
        sandboxDir(context, toolId).mkdirs()
        stateDir(context, toolId).mkdirs()
        sitePackagesDir(context, toolId).mkdirs()
    }

    private fun toJson(def: UserToolDef): JSONObject = JSONObject()
        .put("id", def.id)
        .put("name", def.name)
        .put("description", def.description)
        .put("paramsSchema", def.paramsSchema)
        .put("minMode", def.minMode.name)
        .put("entryFunction", def.entryFunction)
        .put("requirements", JSONArray(def.requirements))
        .put("version", def.version)
        .put("enabled", def.enabled)
        .put("uiMode", normalizeUiMode(def.uiMode))

    /** 4b：会话级工具状态；不存在时返回空对象 */
    fun readState(context: Context, toolId: String, conversationId: String): JSONObject =
        runCatching {
            val f = stateFile(context, toolId, conversationId)
            if (!f.isFile) JSONObject() else JSONObject(f.readText())
        }.getOrDefault(JSONObject())

    /** 4b：写会话级工具状态（事件回传后由宿主落盘） */
    fun writeState(
        context: Context,
        toolId: String,
        conversationId: String,
        state: JSONObject
    ): Boolean = runCatching {
        ensureDirs(context, toolId)
        writeAtomic(stateFile(context, toolId, conversationId), state.toString())
        true
    }.getOrDefault(false)

    private fun normalizeUiMode(raw: String): String =
        if (raw == "schema" || raw == "pages") raw else "data"

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.writeText(text)
        if (!part.renameTo(target)) {
            target.delete()
            if (!part.renameTo(target)) {
                target.writeText(text)
                part.delete()
            }
        }
    }
}
