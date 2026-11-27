package com.dchat.app.ui.tools

import com.dchat.app.data.tools.PyResult
import org.json.JSONObject
import org.json.JSONTokener

/**
 * UI Schema v2/v3 宿主 ↔ `entry.py` 的调用协议。
 *
 * 现状：`dchat_runner.execute()`（另一条工作线维护，本任务不改）仍按 v1 约定
 * `func(args)` 调用入口函数，而 v2 约定是
 * `load(args, state)` / `action(name, args, data, state)`；
 * v3（`docs/spec/tool-ui-v3.md` §3）约定是
 * `page_load(page, args, state)` / `page_action(page, action, params, state)`。
 *
 * 为了不动 Python 运行器，宿主在**用户代码末尾追加一小段适配器**，把
 * `func(payload)` 桥接到 v2/v3 的多参数签名；适配器只用内置函数，不 import 任何模块，
 * 沙箱语义（import 白名单 / open 限制）不受影响。
 *
 * 追加的适配器函数彼此独立：v2 只调 `__dchat_v2_*`，v3 只调 `__dchat_v3_*`，互不影响。
 */
object UiV2Protocol {

    const val ENTRY_LOAD = "__dchat_v2_load"
    const val ENTRY_ACTION = "__dchat_v2_action"

    /** v3 页面加载入口（适配器函数名，非用户代码） */
    const val ENTRY_V3_LOAD = "__dchat_v3_load"

    /** v3 页面动作入口 */
    const val ENTRY_V3_ACTION = "__dchat_v3_action"

    /** v3 canvas 逐帧回调入口（P3，规范 `tool-ui-v3-canvas.md` §3） */
    const val ENTRY_V3_CANVAS_FRAME = "__dchat_v3_canvas_frame"

    /** v3 canvas 手势回调入口 */
    const val ENTRY_V3_CANVAS_GESTURE = "__dchat_v3_canvas_gesture"

    /** 追加到用户代码末尾的适配器（v2 + v3 页面 + v3 canvas 共用一段，重复追加只发生一次） */
    val ADAPTER: String = """
def __dchat_v2_arg_count(fn, fallback):
    try:
        return fn.__code__.co_argcount
    except Exception:
        return fallback

def __dchat_v2_load(payload):
    fn = globals().get("load")
    if not callable(fn):
        return {}
    args = payload.get("args") or {}
    state = payload.get("state") or {}
    if __dchat_v2_arg_count(fn, 2) >= 2:
        return fn(args, state)
    return fn(args)

def __dchat_v2_action(payload):
    fn = globals().get("action")
    if not callable(fn):
        return {"ok": True}
    name = payload.get("action") or ""
    args = payload.get("args") or {}
    data = payload.get("data") or {}
    state = payload.get("state") or {}
    n = __dchat_v2_arg_count(fn, 4)
    if n >= 4:
        return fn(name, args, data, state)
    if n == 3:
        return fn(name, args, data)
    if n == 2:
        return fn(name, args)
    return fn()

def __dchat_v3_load(payload):
    fn = globals().get("page_load")
    if not callable(fn):
        return {}
    return fn(payload.get("page", ""), payload.get("args") or {}, payload.get("state") or {})

def __dchat_v3_action(payload):
    fn = globals().get("page_action")
    if not callable(fn):
        return {"state": {}, "toast": ""}
    return fn(payload.get("page", ""), payload.get("action", ""), payload.get("params") or {}, payload.get("state") or {})

def __dchat_v3_canvas_frame(payload):
    fn = globals().get("canvas_frame")
    if not callable(fn):
        return None
    return fn(payload.get("page", ""), payload.get("view_id", ""),
              payload.get("t_ms", 0), payload.get("dt_ms", 0),
              payload.get("width", 0), payload.get("height", 0),
              payload.get("state") or {})

def __dchat_v3_canvas_gesture(payload):
    fn = globals().get("canvas_gesture")
    if not callable(fn):
        return None
    return fn(payload.get("page", ""), payload.get("view_id", ""),
              payload.get("gesture", ""), payload.get("data") or {},
              payload.get("state") or {})
""".trimIndent()

    private val LOAD_DEF = Regex("(?m)^\\s*def\\s+load\\s*\\(")
    private val ACTION_DEF = Regex("(?m)^\\s*def\\s+action\\s*\\(")
    private val PAGE_LOAD_DEF = Regex("(?m)^\\s*def\\s+page_load\\s*\\(")
    private val PAGE_ACTION_DEF = Regex("(?m)^\\s*def\\s+page_action\\s*\\(")
    private val CANVAS_FRAME_DEF = Regex("(?m)^\\s*def\\s+canvas_frame\\s*\\(")
    private val CANVAS_GESTURE_DEF = Regex("(?m)^\\s*def\\s+canvas_gesture\\s*\\(")

    /** 是否声明了 v2 的 `load()`（决定打开页面后是否后台刷新、动作后是否 refresh） */
    fun hasLoad(code: String): Boolean = LOAD_DEF.containsMatchIn(code)

    fun hasAction(code: String): Boolean = ACTION_DEF.containsMatchIn(code)

    /**
     * v3 是否声明了 `page_load()`。
     * 未声明时宿主**完全不调 Python**（规范 §3.1：直接用页面 JSON 静态渲染），
     * 这样纯静态页面在 Python 运行时不可用时也能打开。
     */
    fun hasPageLoad(code: String): Boolean = PAGE_LOAD_DEF.containsMatchIn(code)

    /** v3 是否声明了 `page_action()`；未声明时宿主忽略按钮动作（不白跑一次 Python） */
    fun hasPageAction(code: String): Boolean = PAGE_ACTION_DEF.containsMatchIn(code)

    /**
     * v3 是否声明了 canvas 逐帧回调 `canvas_frame()`。
     * 未声明时宿主不启帧循环（`tick=true` 的 canvas 退化为静态 `node.ops` 一帧）。
     */
    fun hasCanvasFrame(code: String): Boolean = CANVAS_FRAME_DEF.containsMatchIn(code)

    /**
     * v3 是否声明了 canvas 手势回调 `canvas_gesture()`。
     * 未声明时宿主把 canvas 节点的 `action` 退化为 `page_action(page, action, data, state)`。
     */
    fun hasCanvasGesture(code: String): Boolean = CANVAS_GESTURE_DEF.containsMatchIn(code)

    /** 拼上适配器；重复调用不会重复追加 */
    fun adaptedCode(code: String): String =
        if (code.contains("def __dchat_v2_load")) code else code + "\n\n" + ADAPTER + "\n"

    /** v3 宿主用：同样只追加一段（v2/v3 适配器在同一段 ADAPTER 里） */
    fun adaptedCodeV3(code: String): String =
        if (code.contains("def __dchat_v3_load")) code else code + "\n\n" + ADAPTER + "\n"

    fun openPayload(state: JSONObject): JSONObject = JSONObject()
        .put("event", "open")
        .put("state", state)

    fun loadPayload(state: JSONObject): JSONObject = JSONObject()
        .put("event", "load")
        .put("state", state)

    fun actionPayload(name: String, args: JSONObject, data: JSONObject, state: JSONObject): JSONObject =
        JSONObject()
            .put("event", "action")
            .put("action", name)
            .put("args", args)
            .put("data", data)
            .put("state", state)

    /** v3 页面加载 payload（规范 §3.1） */
    fun v3LoadPayload(page: String, args: JSONObject, state: JSONObject): JSONObject =
        JSONObject()
            .put("page", page)
            .put("args", args)
            .put("state", state)

    /** v3 动作 payload（规范 §3.2）；适配器把 params/state 分成第 3/4 个位置参数 */
    fun v3ActionPayload(
        page: String,
        action: String,
        params: JSONObject,
        state: JSONObject
    ): JSONObject = JSONObject()
        .put("page", page)
        .put("action", action)
        .put("params", params)
        .put("state", state)

    /**
     * v3 canvas 逐帧 payload（规范 `tool-ui-v3-canvas.md` §3）。
     * width/height 是 canvas 内 dp；state 是当前页 state 快照。
     */
    fun v3CanvasFramePayload(
        page: String,
        viewId: String,
        tMs: Long,
        dtMs: Long,
        width: Float,
        height: Float,
        state: JSONObject
    ): JSONObject = JSONObject()
        .put("page", page)
        .put("view_id", viewId)
        .put("t_ms", tMs)
        .put("dt_ms", dtMs)
        .put("width", width)
        .put("height", height)
        .put("state", state)

    /** v3 canvas 手势 payload；data 含 x/y/dx/dy/scale/finger（canvas dp），state 为当前页快照 */
    fun v3CanvasGesturePayload(
        page: String,
        viewId: String,
        gesture: String,
        data: JSONObject,
        state: JSONObject
    ): JSONObject = JSONObject()
        .put("page", page)
        .put("view_id", viewId)
        .put("gesture", gesture)
        .put("data", data)
        .put("state", state)

    /** 解析 Python 返回的 JSON 对象（dict）；不是对象时返回 null */
    fun parseObject(output: String): JSONObject? =
        runCatching { JSONObject(output) }.getOrNull()

    /** 解析任意 JSON（旧 UI 树可能是对象/数组/字符串） */
    fun parseAny(output: String): Any? =
        runCatching { JSONTokener(output).nextValue() }.getOrNull()

    /**
     * 返回 dict 里的错误文案：`error` / `_error`。
     * 按 D79 决定：这两个键只作为错误提示，不进入数据源。
     */
    fun errorOf(result: JSONObject?): String {
        if (result == null) return ""
        return result.optString("error").ifBlank { result.optString("_error") }.trim()
    }

    /** 去掉 `_state` / `error` / `_error`，剩下的键才是 `{{key}}` 数据源 */
    fun stripMeta(result: JSONObject): JSONObject {
        val out = JSONObject()
        result.keys().forEach { key ->
            if (key == "_state" || key == "error" || key == "_error") return@forEach
            out.put(key, result.opt(key))
        }
        return out
    }

    /** 把 Python 侧 `_state` 写回 page.json 时用：只取对象，其他类型忽略 */
    fun stateOf(result: JSONObject?): JSONObject? = result?.optJSONObject("_state")

    fun isPyUnavailable(result: PyResult): Boolean = result.errorCode == "PY_UNAVAILABLE"
}
