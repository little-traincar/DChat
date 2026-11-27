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
          "timeout_sec":{"type":"integer","description":"工具最长执行秒数，0=不限时，默认 30；请根据工具实际需要自行决定（例如纯计算 10-30、建 UI 60-120、长任务可不限）"},
          "needs":{"type":"array","items":{"type":"string","enum":["toast","vibrate","clipboard","notify","share","open_url","battery","network","sensors","device","locate","camera","gallery"]},"description":"可选：声明用到的 droid 能力，仅用于展示/提示，不改变权限判定"},
          "enabled":{"type":"boolean","description":"创建后是否立即启用，默认 true"},
          "ui_mode":{"type":"string","enum":["data","web"],"description":"界面模式：data=工具返回 JSON 自动渲染；web=HTML/CSS/JS 工具页（web/index.html）"},
          "ui":{"type":"string","description":"可选；web 模式忽略此字段，页面请创建后用 local.web_write 写 web/index.html"}
        },"required":["name","description","code"],"additionalProperties":false}
    """.trimIndent()

    private const val DEFAULT_PARAMS = """{"type":"object","properties":{},"additionalProperties":false}"""
    private const val MAX_CODE_CHARS = 60_000
    private const val MAX_DESC_CHARS = 300
    private val DROID_NEEDS = setOf(
        "toast", "vibrate", "clipboard", "notify", "share", "open_url",
        "battery", "network", "sensors", "device", "locate", "camera", "gallery"
    )

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
        val timeoutSec = args.optInt("timeout_sec", 30)
        if (timeoutSec < 0) {
            return fail("INVALID_TIMEOUT", "timeout_sec 必须大于等于 0（0=不限时，默认 30）")
        }
        val needs = buildList {
            val arr = args.optJSONArray("needs") ?: return@buildList
            for (i in 0 until arr.length()) {
                val n = arr.optString(i).trim().lowercase()
                if (n in DROID_NEEDS && n !in this) add(n)
            }
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

        // UI 模式：data（自动渲染）或 web（WebView 工具页）
        val uiMode = args.optString("ui_mode").trim().lowercase().ifBlank { "data" }
        if (uiMode !in setOf("data", "web")) {
            return fail("INVALID_UI", "ui_mode 只能是 data 或 web")
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
                    timeoutMs = if (timeoutSec == 0) 0 else
                        (timeoutSec.toLong() * 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    needs = needs,
                    code = code
                )
            )
        }.onFailure { Log.w(TAG, "创建工具失败: $name", it) }.getOrNull()
            ?: return fail(
                "SAVE_FAILED",
                "创建失败：名称可能已被占用、描述/代码为空，或写盘失败（同名工具已存在时会被覆盖）"
            )

        // web 模式：写最小占位页；失败回滚
        if (uiMode == "web") {
            val html = """
                <!doctype html>
                <html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                <style>body{font-family:system-ui,sans-serif;padding:20px;background:#f5f6f8;color:#1a1a1a}</style>
                </head><body><h2>$name</h2><p>这是一个 Web 工具页占位；模型可用 local.web_write 修改 web/index.html。</p></body></html>
            """.trimIndent()
            val args = JSONObject()
                .put("tool_id", saved.id)
                .put("path", "index.html")
                .put("content", html)
            val r = WebWriteTool.run(context, args.toString())
            if (!r.ok) {
                runCatching { UserToolStore.delete(context, saved.id) }
                return fail("SAVE_FAILED_UI", "工具已创建但 web/index.html 写入失败，已回滚删除：${r.errorMessage}")
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
            append("超时：").append(if (saved.timeoutMs == 0) "不限时" else "${saved.timeoutMs / 1000}s").append("\n")
            append("下一轮请求起这个工具会出现在工具列表里，可直接按工具名调用；也可以由用户在你的工具页面打开视图。")
        }
        return PyResult(ok = true, output = output)
    }

    private fun fail(code: String, message: String): PyResult =
        PyResult(ok = false, errorCode = code, errorMessage = message)
}
