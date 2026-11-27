package com.dchat.app.data.tools

import android.content.Context
import com.dchat.app.ui.tools.UiV3Model
import com.dchat.app.ui.tools.UiV3Store
import org.json.JSONArray
import org.json.JSONObject

/** local.ui_write：模型写/改 UI v3 页面（只允许已存在的用户工具）。 */
object UiWriteTool {
    fun run(context: Context, argsJson: String): PyResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return fail("参数不是合法 JSON")
        }
        val toolId = args.optString("tool_id").trim()
        if (!toolId.startsWith("user.") || UserToolStore.find(context, toolId) == null) {
            return fail("tool_id 必须是已存在的用户工具（user.xxx）")
        }
        val page = args.optString("page").trim()
        if (!UiV3Model.isPageName(page)) {
            return fail("page 必须是 1-32 位小写字母/数字/下划线")
        }
        val jsonText = args.optString("json").trim()
        if (jsonText.isEmpty()) return fail("必须提供 json")
        val obj = runCatching { JSONObject(jsonText) }.getOrElse {
            return fail("json 不是合法 JSON 对象")
        }
        if (!obj.has("body") || obj.optJSONObject("body") == null) {
            return fail("页面 JSON 必须提供 body")
        }
        val validation = UiV3Store.writePage(context, toolId, page, obj.toString())
        if (!validation.ok) {
            return PyResult(
                ok = false,
                errorCode = "UI_INVALID",
                errorMessage = "UI 校验失败：\n" + validation.errors.joinToString("\n") { "· $it" }
            )
        }
        // 可选：写/合并 manifest.tab
        val tabs = args.optJSONArray("create_tab")
        if (tabs != null && tabs.length() > 0) {
            val old = runCatching {
                val f = java.io.File(UiV3Store.uiDir(context, toolId), "manifest.json")
                if (f.isFile) JSONObject(f.readText()) else null
            }.getOrNull()
            val manifest = old ?: JSONObject()
                .put("version", 3)
                .put("entry", page)
                .put("title", UserToolStore.find(context, toolId)?.name ?: toolId)
            val arr = manifest.optJSONArray("tab") ?: JSONArray()
            for (i in 0 until tabs.length()) {
                val t = tabs.optJSONObject(i) ?: continue
                val p = t.optString("page").trim()
                if (!UiV3Model.isPageName(p)) continue
                var replaced = false
                for (j in 0 until arr.length()) {
                    val e = arr.optJSONObject(j) ?: continue
                    if (e.optString("page") == p) {
                        e.put("text", t.optString("text"))
                        e.put("icon", t.optString("icon"))
                        replaced = true
                        break
                    }
                }
                if (!replaced) {
                    arr.put(JSONObject().put("page", p).put("text", t.optString("text")).put("icon", t.optString("icon")))
                }
            }
            manifest.put("tab", arr)
            val mv = UiV3Store.writeManifest(context, toolId, manifest)
            if (!mv.ok) {
                return PyResult(
                    ok = false, errorCode = "UI_INVALID",
                    errorMessage = "manifest 校验失败：\n" + mv.errors.joinToString("\n") { "· $it" }
                )
            }
        }
        val nodes = countNodes(obj.optJSONObject("body"))
        return PyResult(
            ok = true,
            output = "已写入 UI 页面 `$page`（tool=$toolId，节点数=$nodes）。打开该工具页面即可查看 v3 界面。"
        )
    }

    private fun countNodes(n: JSONObject?): Int {
        if (n == null) return 0
        var count = 1
        val arr = n.optJSONArray("children") ?: return count
        for (i in 0 until arr.length()) count += countNodes(arr.optJSONObject(i))
        return count
    }

    private fun fail(message: String) = PyResult(ok = false, errorCode = "UI_INVALID", errorMessage = message)
}
