package com.dchat.app.ui.tools

import org.json.JSONArray
import org.json.JSONObject

/** UI v3 纯数据模型：不依赖 Compose，供 Store / 渲染器共用。 */
data class UiTab(val page: String, val text: String, val icon: String)

data class UiManifest(
    val version: Int,
    val entry: String,
    val title: String,
    val tab: List<UiTab>
)

data class UiTheme(
    val colorPrimary: String = "",
    val bg: String = "",
    val card: String = "",
    val text1: String = "",
    val text2: String = "",
    val text3: String = "",
    val radius: Int = 14,
    val fontScale: Float = 1f,
    val dark: Boolean = false
)

data class UiBackground(
    val type: String = "color",
    val value: String = "",
    val stops: List<Pair<String, Float>> = emptyList(),
    val angle: Float = 180f,
    val radial: List<Float> = emptyList(),
    val src: String = "",
    val fit: String = "cover",
    val blur: Int = 0,
    val scrim: Float = 0f,
    val opacity: Float = 1f
)

data class UiBorder(val width: Int = 1, val color: String = "#ECEEF1")

data class UiNode(
    val type: String = "",
    val unsupported: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val text: String = "",
    val src: String = "",
    val key: String = "",
    val action: String = "",
    val params: JSONObject = JSONObject(),
    val icon: String = "",
    val color: String = "",
    val background: UiBackground? = null,
    val border: UiBorder? = null,
    val padding: List<Int> = emptyList(),
    val margin: List<Int> = emptyList(),
    val width: String = "",
    val height: Int = 0,
    val size: Int = 0,
    val radius: Int = 0,
    val shadow: Int = 0,
    val align: String = "",
    val justify: String = "",
    val gap: Int = 0,
    val columns: Int = 0,
    val fit: String = "cover",
    val weight: String = "normal",
    val maxLines: Int = 0,
    val style: String = "filled",
    val visible: String = "true",
    val value: String = "",
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.01f,
    val thickness: Int = 1,
    val placeholder: String = "",
    val raw: JSONObject = JSONObject()
)

data class UiPage(
    val version: Int,
    val title: String,
    val theme: UiTheme,
    val background: UiBackground?,
    val body: UiNode
)

data class UiV3Validation(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

object UiV3Model {
    private val pageName = Regex("^[a-z0-9_]{1,32}$")

    fun isPageName(s: String): Boolean = pageName.matches(s)

    fun parsePage(json: JSONObject): UiPage? = runCatching {
        val bodyObj = json.optJSONObject("body") ?: return null
        val themeObj = json.optJSONObject("theme")
        val theme = UiTheme(
            colorPrimary = themeObj?.optString("colorPrimary").orEmpty(),
            bg = themeObj?.optString("bg").orEmpty(),
            card = themeObj?.optString("card").orEmpty(),
            text1 = themeObj?.optString("text1").orEmpty(),
            text2 = themeObj?.optString("text2").orEmpty(),
            text3 = themeObj?.optString("text3").orEmpty(),
            radius = themeObj?.optInt("radius", 14) ?: 14,
            fontScale = themeObj?.optDouble("fontScale", 1.0)?.toFloat() ?: 1f,
            dark = themeObj?.optBoolean("dark", false) == true
        )
        UiPage(
            version = json.optInt("version", 0),
            title = json.optString("title"),
            theme = theme,
            background = parseBackground(json.optJSONObject("background")),
            body = parseNode(bodyObj)
        )
    }.getOrNull()

    fun parseManifest(json: JSONObject): UiManifest? = runCatching {
        val entry = json.optString("entry").trim()
        if (!isPageName(entry)) return null
        val tabs = ArrayList<UiTab>()
        val arr = json.optJSONArray("tab") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val page = o.optString("page").trim()
            if (isPageName(page)) {
                tabs.add(UiTab(page, o.optString("text"), o.optString("icon")))
            }
        }
        UiManifest(json.optInt("version", 0), entry, json.optString("title"), tabs)
    }.getOrNull()

    fun validateManifest(json: JSONObject): UiV3Validation {
        val errors = ArrayList<String>()
        if (json.optInt("version", 0) != 3) errors.add("manifest.version 必须为 3")
        val entry = json.optString("entry").trim()
        if (!isPageName(entry)) errors.add("manifest.entry 必须是 1-32 位小写字母/数字/下划线")
        val arr = json.optJSONArray("tab")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null || !isPageName(o.optString("page"))) {
                    errors.add("tab[$i].page 非法")
                }
            }
        }
        return UiV3Validation(errors)
    }

    fun validatePage(json: JSONObject): UiV3Validation {
        val errors = ArrayList<String>()
        if (json.optInt("version", 0) != 3) errors.add("version 必须为 3")
        val body = json.optJSONObject("body")
        if (body == null) {
            errors.add("必须提供 body")
        } else {
            var count = 0
            fun walk(n: JSONObject, depth: Int) {
                count++
                if (depth > 10) {
                    if (errors.none { it.startsWith("嵌套深度") }) errors.add("嵌套深度不能超过 10")
                    return
                }
                val bg = n.optJSONObject("background")
                if (bg != null) validateBg(bg, errors)
                val arr = n.optJSONArray("children") ?: return
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { walk(it, depth + 1) }
                }
            }
            walk(body, 1)
            if (count > 300) errors.add("节点数不能超过 300（当前 $count）")
            val bg = json.optJSONObject("background")
            if (bg != null) validateBg(bg, errors)
        }
        return UiV3Validation(errors.distinct())
    }

    private fun validateBg(bg: JSONObject, errors: MutableList<String>) {
        if (bg.optString("type") == "image") {
            val src = bg.optString("src")
            if (!safeSrc(src)) errors.add("background.src 只能是 workspace: 或工具目录相对路径，且不能包含 ..")
        }
    }

    /** src 安全校验（真实解析在 UiV3Store.resolveSrc） */
    fun safeSrc(src: String): Boolean {
        if (src.isBlank() || src.contains("..") || src.startsWith("/") || src.contains('\\')) return false
        if (src.startsWith("workspace:")) {
            val p = src.removePrefix("workspace:")
            return p.isNotBlank() && !p.startsWith("/") && !p.contains("..")
        }
        return true
    }

    fun evalBindings(text: String, state: JSONObject): String {
        return Regex("\\{\\{\\s*state\\.([A-Za-z0-9_.]+)\\s*\\}\\}").replace(text) { m ->
            var cur: Any? = state
            var missing = false
            for (p in m.groupValues[1].split('.')) {
                val o = cur as? JSONObject
                if (o == null || !o.has(p)) {
                    missing = true
                    break
                }
                cur = o.opt(p)
            }
            if (missing) "" else when (cur) {
                null, JSONObject.NULL -> ""
                is String -> cur
                else -> cur.toString()
            }
        }
    }

    private fun parseBackground(o: JSONObject?): UiBackground? {
        if (o == null) return null
        val stops = ArrayList<Pair<String, Float>>()
        o.optJSONArray("stops")?.let { arr ->
            for (i in 0 until arr.length()) {
                val pair = arr.optJSONArray(i) ?: continue
                val color = pair.optString(0)
                val pos = pair.optDouble(1, 0.0).toFloat()
                if (color.isNotBlank()) stops.add(color to pos)
            }
        }
        val radial = ArrayList<Float>()
        o.optJSONArray("radial")?.let { arr ->
            for (i in 0 until arr.length()) radial.add(arr.optDouble(i, 0.0).toFloat())
        }
        return UiBackground(
            type = o.optString("type", "color"),
            value = o.optString("value"),
            stops = stops,
            angle = o.optDouble("angle", 180.0).toFloat(),
            radial = radial,
            src = o.optString("src"),
            fit = o.optString("fit", "cover"),
            blur = o.optInt("blur", 0).coerceIn(0, 24),
            scrim = o.optDouble("scrim", 0.0).toFloat().coerceIn(0f, 0.8f),
            opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
        )
    }

    private fun parseNode(o: JSONObject): UiNode {
        val type = o.optString("type").lowercase()
        val known = type in setOf(
            "column", "row", "stack", "scroll", "grid", "spacer",
            "text", "button", "image", "icon", "divider", "progress", "badge",
            "input", "toggle", "slider", "card"
        )
        val children = ArrayList<UiNode>()
        o.optJSONArray("children")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { children.add(parseNode(it)) }
        }
        fun ints(name: String): List<Int> {
            val a = o.optJSONArray(name)
            if (a != null) return (0 until a.length()).map { a.optInt(it) }
            val v = o.optInt(name, Int.MIN_VALUE)
            return if (v != Int.MIN_VALUE) listOf(v) else emptyList()
        }
        val border = o.optJSONObject("border")?.let {
            UiBorder(it.optInt("width", 1), it.optString("color", "#ECEEF1"))
        }
        return UiNode(
            type = type,
            unsupported = !known,
            children = children,
            text = o.optString("text"),
            src = o.optString("src"),
            key = o.optString("key"),
            action = o.optString("action"),
            params = o.optJSONObject("params") ?: JSONObject(),
            icon = o.optString("icon").ifBlank { o.optString("name") },
            color = o.optString("color"),
            background = parseBackground(o.optJSONObject("background")),
            border = border,
            padding = ints("padding"),
            margin = ints("margin"),
            width = o.optString("width"),
            height = o.optInt("height", 0),
            size = o.optInt("size", 16),
            radius = o.optInt("radius", 0),
            shadow = o.optInt("shadow", 0),
            align = o.optString("align"),
            justify = o.optString("justify"),
            gap = o.optInt("gap", 0),
            columns = o.optInt("columns", 2).coerceIn(2, 6),
            fit = o.optString("fit", "cover"),
            weight = o.optString("weight", "normal"),
            maxLines = o.optInt("maxLines", 0),
            style = o.optString("style", "filled"),
            visible = o.optString("visible", "true"),
            value = o.optString("value"),
            min = o.optDouble("min", 0.0).toFloat(),
            max = o.optDouble("max", 1.0).toFloat(),
            step = o.optDouble("step", 0.01).toFloat(),
            thickness = o.optInt("thickness", 1),
            placeholder = o.optString("placeholder"),
            raw = o
        )
    }
}
