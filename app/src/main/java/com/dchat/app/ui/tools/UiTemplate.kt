package com.dchat.app.ui.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * UI Schema v2 的模板插值：只支持单层 `{{key}}`。
 *
 * 约定（brief §3）：
 * - `{{key}}` 替换为数据源里的同名 key，缺失 → 空串；
 * - **不做**表达式 / 条件 / 循环 / 多级路径，保持简单可预测；
 * - 数据源 = 最近一次 `load` 返回 ∪ `page.json` 快照 ∪ `ui.json` 的静态 `data`；
 * - list 行模板的数据源额外叠加当前元素的字段（见 [UiV2Renderer]）。
 */
object UiTemplate {

    private val KEY = Regex("\\{\\{([^{}]+)\\}\\}")

    /** 渲染一个模板字符串；data 里没有的 key 变成空串 */
    fun render(template: String, data: JSONObject): String = render(template, asMap(data))

    fun render(template: String, data: Map<String, Any?>): String {
        if (template.isEmpty() || !template.contains("{{")) return template
        return KEY.replace(template) { match ->
            valueText(data[match.groupValues[1].trim()])
        }
    }

    /** JSONObject → 只读 Map（保留插入顺序） */
    fun asMap(obj: JSONObject?): Map<String, Any?> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, Any?>(obj.length())
        obj.keys().forEach { key -> out[key] = obj.opt(key) }
        return out
    }

    /** 递归渲染任意 JSON 里所有字符串值（交给旧渲染器前先把 {{}} 展开） */
    fun renderJson(value: Any?, data: JSONObject): Any? = when (value) {
        is JSONObject -> {
            val out = JSONObject()
            value.keys().forEach { key -> out.put(key, renderJson(value.opt(key), data)) }
            out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) out.put(renderJson(value.opt(i), data))
            out
        }
        is String -> render(value, data)
        else -> value
    }

    /** 顶层合并：overlay 覆盖 base；两者都不会被修改 */
    fun merge(base: JSONObject?, overlay: JSONObject?): JSONObject {
        val out = JSONObject()
        base?.keys()?.forEach { key -> out.put(key, base.opt(key)) }
        overlay?.keys()?.forEach { key -> out.put(key, overlay.opt(key)) }
        return out
    }

    /** 模板值转字符串：数字去掉多余的 .0，null/undefined → 空串 */
    fun valueText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is Double -> if (value.isFinite() && value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Float -> valueText(value.toDouble())
        is Number, is Boolean -> value.toString()
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }
}
