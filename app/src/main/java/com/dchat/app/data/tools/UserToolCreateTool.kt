package com.dchat.app.data.tools

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第五期：让**模型自己创建 / 注册用户工具**（`local.tool_create`）。
 *
 * 背景：用户问「你能不能做一个工具，注册到这个 App」时，模型以前只能回答"做不到"——
 * 用户工具只能在设置里手写。这里补上闭环：
 *   模型写 `entry.py` + 元信息 → 落 `files/tools/user.<name>/` → 注册进 `ToolRegistry`
 *   → 下一次请求的工具列表里就出现这个工具，模型可以直接调用。
 *
 * 与用户手写工具完全同构（`UserToolStore.save` 同一条通道），因此沙箱、权限档位、
 * 版本号、`state/`、`site-packages/` 行为都一致。
 */
object UserToolCreateTool {

    private const val TAG = "ToolCreate"

    /** 参数 schema：模型自查用；字段与 [run] 解析保持一致 */
    val SCHEMA = """
        {"type":"object","properties":{
          "name":{"type":"string","description":"工具名：小写字母/数字/下划线，1-32 位，例如 word_count"},
          "description":{"type":"string","description":"给模型看的一句话：这个工具做什么、返回什么"},
          "code":{"type":"string","description":"Python 代码，必须定义入口函数 def run(args: dict) -> str"},
          "entry_function":{"type":"string","description":"入口函数名，默认 run"},
          "params_schema":{"type":"string","description":"参数 JSON Schema 字符串（可选），例如 {\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}；不填表示无参数"},
          "requirements":{"type":"array","items":{"type":"string"},"description":"可选：pip 依赖包名列表（创建时只记录，安装走工具编辑页）"},
          "min_mode":{"type":"string","enum":["read_only","workspace_write","full"],"description":"最低权限档位，默认 workspace_write"},
          "enabled":{"type":"boolean","description":"创建后是否立即启用，默认 true"},
          "ui_mode":{"type":"string","enum":["data","schema","pages"],"description":"界面模式：data=自动渲染；schema=v2 单页；pages=v3 多页原生界面"},
          "ui":{"type":"string","description":"UI Schema JSON（v2: ui_mode=schema 时必填；v3: ui_mode=pages 时可传页面 JSON，body 缺省会生成占位）。v3 速查：version=3，body.type=column/row/stack/scroll/grid/text/button/image/divider/progress/badge/input/toggle/slider/card，最小示例 {\"version\":3,\"body\":{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"},
          "ui_entry":{"type":"string","description":"v3 入口页名（ui_mode=pages 时必填，1-32 位小写字母/数字/下划线）"}
        },"required":["name","description","code"],"additionalProperties":false}
    """.trimIndent()

    private const val DEFAULT_PARAMS = """{"type":"object","properties":{},"additionalProperties":false}"""
    private const val MAX_CODE_CHARS = 60_000
    private const val MAX_DESC_CHARS = 300

    suspend fun run(context: Context, argsJson: String): PyResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return fail("INVALID_ARGS", "参数不是合法 JSON：${it.message}")
        }
        val name = args.optString("name").trim()
        val description = args.optString("description").trim()
        val code = args.optString("code")
        val entry = args.optString("entry_function").trim().ifBlank { "run" }

        if (!UserToolStore.NAME_REGEX.matches(name)) {
            return fail(
                "INVALID_NAME",
                "工具名不合法：`$name`（只能用小写字母/数字/下划线，1-32 位）"
            )
        }
        if (description.isBlank()) return fail("INVALID_ARGS", "description 不能为空")
        if (description.length > MAX_DESC_CHARS) {
            return fail("INVALID_ARGS", "description 太长（上限 $MAX_DESC_CHARS 字符）")
        }
        if (code.isBlank()) return fail("INVALID_ARGS", "code 不能为空")
        if (code.length > MAX_CODE_CHARS) {
            return fail("INVALID_ARGS", "code 太长（上限 ${MAX_CODE_CHARS / 1000}K 字符）")
        }
        if (!code.contains("def $entry(")) {
            return fail(
                "INVALID_CODE",
                "code 里找不到入口函数 `def $entry(args: dict) -> str:`，请按这个签名实现"
            )
        }
        if (!entry.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) {
            return fail("INVALID_ARGS", "entry_function 不是合法 Python 标识符：`$entry`")
        }

        val paramsSchema = args.optString("params_schema").trim().ifBlank { DEFAULT_PARAMS }
        val schemaObj = runCatching { JSONObject(paramsSchema) }.getOrElse {
            return fail("INVALID_SCHEMA", "params_schema 不是合法 JSON：${it.message}")
        }
        if (!schemaObj.has("type")) {
            return fail("INVALID_SCHEMA", "params_schema 缺少 \"type\":\"object\"")
        }

        val minMode = when (args.optString("min_mode").trim().lowercase()) {
            "read_only" -> ToolMode.READ_ONLY
            "full" -> ToolMode.FULL
            else -> ToolMode.WORKSPACE_WRITE
        }
        if (name == "tool_create") {
            return fail("INVALID_NAME", "`tool_create` 是内置工具名，请换一个")
        }

        val requirements = buildList {
            val arr = args.optJSONArray("requirements") ?: return@buildList
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).trim()
                if (p.isNotEmpty()) add(p)
            }
        }

        // UI Schema：data / v2 schema / v3 pages
        val uiMode = args.optString("ui_mode").trim().lowercase().ifBlank { "data" }
        if (uiMode !in setOf("data", "schema", "pages")) {
            return fail("INVALID_UI", "ui_mode 只能是 data、schema 或 pages")
        }
        val uiEntry = args.optString("ui_entry").trim()
        if (uiMode == "pages" && !com.dchat.app.ui.tools.UiV3Model.isPageName(uiEntry)) {
            return fail("INVALID_UI", "ui_mode=pages 时必须提供合法的 ui_entry（1-32 位小写字母/数字/下划线）")
        }
        val uiRaw = args.optString("ui").trim()
        var uiSpec: JSONObject? = null
        var pageSpec: JSONObject? = null
        if (uiMode == "schema") {
            if (uiRaw.isBlank()) return fail("INVALID_UI", "ui_mode=schema 时必须提供 ui（UI Schema v2 JSON）")
            uiSpec = runCatching {
                if (uiRaw.startsWith("[")) {
                    JSONObject().put("version", 2).put("blocks", JSONArray(uiRaw))
                } else {
                    val o = JSONObject(uiRaw)
                    if (o.has("blocks")) o.put("version", 2) else null
                }
            }.onFailure { Log.w(TAG, "ui json 解析失败: $name", it) }.getOrNull()
                ?: return fail("INVALID_UI", "ui 不是合法 JSON，或缺少 blocks 数组")
            val blocks = uiSpec.optJSONArray("blocks")
            if (blocks == null || blocks.length() == 0) {
                return fail("INVALID_UI", "ui.blocks 不能为空")
            }
        }
        if (uiMode == "pages") {
            pageSpec = runCatching {
                if (uiRaw.startsWith("{")) JSONObject(uiRaw) else null
            }.getOrNull() ?: JSONObject()
            pageSpec!!.put("version", 3)
            if (pageSpec!!.optJSONObject("body") == null) {
                pageSpec!!.put(
                    "body",
                    JSONObject().put("type", "column").put(
                        "children", JSONArray().put(JSONObject().put("type", "text").put("text", "页面已创建"))
                    )
                )
            }
            if (pageSpec!!.optString("title").isBlank()) pageSpec!!.put("title", name)
            val v = com.dchat.app.ui.tools.UiV3Model.validatePage(pageSpec!!)
            if (!v.ok) return fail("INVALID_UI", "v3 页面校验失败：" + v.errors.joinToString("; "))
        }

        val existing = ToolRegistry.all().firstOrNull { it.id == UserToolStore.idForName(name) }
        val wasExisting = existing != null

        val saved = runCatching {
            UserToolStore.save(
                context,
                UserToolDef(
                    id = if (wasExisting) UserToolStore.idForName(name) else "",
                    name = name,
                    description = description,
                    paramsSchema = schemaObj.toString(),
                    minMode = minMode,
                    entryFunction = entry,
                    requirements = requirements,
                    enabled = args.optBoolean("enabled", true),
                    uiMode = uiMode,
                    code = code
                )
            )
        }.onFailure { Log.w(TAG, "创建工具失败: $name", it) }.getOrNull()
            ?: return fail(
                "SAVE_FAILED",
                "创建失败：名称可能已被占用、描述/代码为空，或写盘失败（同名工具已存在时会被覆盖）"
            )

        if (uiMode == "pages" && pageSpec != null) {
            val written = runCatching {
                val manifest = JSONObject()
                    .put("version", 3)
                    .put("entry", uiEntry)
                    .put("title", name)
                    .put(
                        "tab",
                        JSONArray().put(
                            JSONObject().put("page", uiEntry).put("text", name).put("icon", "star")
                        )
                    )
                val mv = com.dchat.app.ui.tools.UiV3Store.writeManifest(context, saved.id, manifest)
                if (!mv.ok) error(mv.errors.joinToString("; "))
                val pv = com.dchat.app.ui.tools.UiV3Store.writePage(
                    context, saved.id, uiEntry, pageSpec.toString()
                )
                if (!pv.ok) error(pv.errors.joinToString("; "))
                true
            }.getOrDefault(false)
            if (!written) {
                runCatching { UserToolStore.delete(context, saved.id) }
                return fail("SAVE_FAILED_UI", "工具已创建但 v3 页面写入失败，已回滚删除；请检查页面 JSON 后重试")
            }
        }

        // schema 模式下把视图写进 ui.json（原子写；失败则把工具回滚删掉，避免留下打不开的页面）
        if (uiMode == "schema" && uiSpec != null) {
            val written = runCatching {
                com.dchat.app.ui.tools.UiSpecStore.write(context, saved.id, uiSpec)
            }.getOrDefault(false)
            if (!written) {
                runCatching { UserToolStore.delete(context, saved.id) }
                return fail("SAVE_FAILED_UI", "工具已创建但 UI 视图写入失败，已回滚删除；请检查 ui JSON 后重试")
            }
        }

        val id = saved.id
        val modeText = when (saved.minMode) {
            ToolMode.READ_ONLY -> "仅可查看"
            ToolMode.WORKSPACE_WRITE -> "工作区内修改"
            ToolMode.FULL -> "完全权限"
        }
        val schemaHint = schemaObj.optJSONObject("properties")?.keys()?.asSequence()?.toList().orEmpty()
        val argsHint = if (schemaHint.isEmpty()) "无参数" else "参数：" + schemaHint.joinToString(", ")
        val output = buildString {
            append(if (wasExisting) "已更新用户工具" else "已创建用户工具")
            append(" `").append(name).append("`（id=").append(id).append("，版本 v").append(saved.version)
            append("，权限档位：").append(modeText)
            append(if (saved.enabled) "，已启用" else "，未启用").append("）\n")
            append("入口：").append(saved.entryFunction).append("(").append(argsHint).append(")\n")
            append("下一轮请求起这个工具会出现在工具列表里，可直接按工具名调用；也可以由用户在你的工具页面打开视图。")
        }
        return PyResult(ok = true, output = output)
    }

    private fun fail(code: String, message: String): PyResult =
        PyResult(ok = false, errorCode = code, errorMessage = message)
}
