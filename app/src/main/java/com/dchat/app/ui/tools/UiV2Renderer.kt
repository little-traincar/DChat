package com.dchat.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.ui.CompactSwitch
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.theme.LocalColors
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * UI Schema v2 渲染器：渲染 `ui.json` 的 `blocks`。
 *
 * 支持（本阶段最小集 + 复用旧渲染器）：
 * - `stat`：大号数字卡（label/value/sub）
 * - `list`：`source` 数据数组 + `item` 模板（title/subtitle/value/badge）；
 *   行内 `swipe` 动作支持**左滑**与**长按**触发，动作带 spec.actions 的 confirm/tone
 * - `form`：text/number/textarea/date/select/toggle，`required` / `number` 校验，`submit` 动作
 * - `actions`：动作按钮组（也兜底渲染 spec.actions 里声明的动作）
 * - `button`：单个动作按钮
 * - `legacy`：`{"type":"legacy","schema":<旧 UI 树>}` → 转调现有 [UserToolUi]/RenderNode
 * - 其它白名单节点（text/card/keyvalue/bar/line/pie/...）→ 先做 `{{}}` 插值，
 *   再转调现有 [UserToolUi]，不重复实现
 *
 * 预算沿用旧渲染器：单文本 8000、节点 500、深度 12。
 */
@Composable
fun UiV2Renderer(
    spec: JSONObject,
    data: JSONObject,
    onAction: (name: String, args: JSONObject) -> Unit,
    onLegacyEvent: (event: String, value: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val blocks = spec.optJSONArray("blocks") ?: JSONArray()
    val actions = spec.optJSONObject("actions")
    val key = spec.toString()

    var pending by remember(key) { mutableStateOf<V2Pending?>(null) }
    val budget = remember(key) { V2Budget() }

    /** 触发一个动作：spec.actions 声明了 confirm 就先弹二次确认 */
    val fire: (String, JSONObject) -> Unit = { name, args ->
        if (name.isNotBlank()) {
            val meta = actions?.optJSONObject(name)
            // org.json 的 optString 会把 JSON null 读成字符串 "null"；这里显式排除，
            // 否则 {"confirm": null} 会错误地弹出一个文案为 "null" 的确认框。
            val confirm = if (meta != null && meta.has("confirm") && !meta.isNull("confirm")) {
                meta.optString("confirm")
            } else {
                ""
            }
            val tone = if (meta != null && meta.has("tone") && !meta.isNull("tone")) {
                meta.optString("tone")
            } else {
                ""
            }
            if (confirm.isNotBlank()) {
                pending = V2Pending(name, args, confirm, tone)
            } else {
                onAction(name, args)
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            RenderV2Block(
                block = block,
                depth = 1,
                path = "b$i",
                data = data,
                actions = actions,
                budget = budget,
                onAction = fire,
                onLegacyEvent = onLegacyEvent
            )
        }
        if (budget.overflow) {
            Text(
                L.tools.uiTooLarge,
                fontSize = 11.sp,
                color = c.text3,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }

    val p = pending
    if (p != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = c.bg,
            shape = RoundedCornerShape(16.dp),
            title = { Text(L.tools.uiActionConfirmTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text1) },
            text = { Text(p.confirm, fontSize = 13.sp, color = c.text2, lineHeight = 19.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onAction(p.name, p.args)
                }) {
                    Text(
                        L.tools.uiConfirmAction,
                        color = if (p.tone == "danger") c.danger else c.brand
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(L.common.cancel, color = c.text2)
                }
            }
        )
    }
}

private data class V2Pending(
    val name: String,
    val args: JSONObject,
    val confirm: String,
    val tone: String
)

private const val TEXT_LIMIT = 8000
private const val NODE_LIMIT = 500
private const val DEPTH_LIMIT = 12

private class V2Budget {
    private var count = 0
    var overflow = false
        private set

    fun take(): Boolean {
        if (overflow) return false
        count++
        if (count > NODE_LIMIT) {
            overflow = true
            return false
        }
        return true
    }
}

@Composable
private fun RenderV2Block(
    block: JSONObject,
    depth: Int,
    path: String,
    data: JSONObject,
    actions: JSONObject?,
    budget: V2Budget,
    onAction: (String, JSONObject) -> Unit,
    onLegacyEvent: (event: String, value: String) -> Unit
) {
    if (depth > DEPTH_LIMIT || !budget.take()) return
    when (val type = block.optString("type").lowercase()) {
        "stat" -> V2Stat(block, data)
        "list" -> {
            // v2 list 用 source + item；老式 items/itemTemplate 交给旧渲染器
            if (block.has("source") || block.has("item")) {
                V2List(block, data, path, onAction)
            } else {
                V2LegacyNode(block, data, onLegacyEvent)
            }
        }
        "form" -> V2Form(block, data, path, onAction)
        "actions" -> V2Actions(block, actions, data, onAction)
        "button" -> V2Button(
            label = UiTemplate.render(block.optString("label", block.optString("value")), data)
                .ifBlank { block.optString("action") },
            tone = block.optString("tone"),
            onClick = {
                onAction(
                    block.optString("action").ifBlank { block.optString("event") },
                    block.optJSONObject("args") ?: JSONObject()
                )
            }
        )
        "legacy" -> V2Legacy(block, onLegacyEvent)
        else -> {
            if (type.isBlank()) return
            V2LegacyNode(block, data, onLegacyEvent)
        }
    }
}

/* ---------------- stat ---------------- */

@Composable
private fun V2Stat(block: JSONObject, data: JSONObject) {
    val c = LocalColors.current
    val label = UiTemplate.render(block.optString("label"), data)
    val value = UiTemplate.render(block.optString("value"), data)
    val sub = UiTemplate.render(block.optString("sub", block.optString("hint")), data)
    val tone = block.optString("tone")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .border(1.dp, c.line2, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (label.isNotBlank()) {
            Text(
                cut(label),
                fontSize = 11.5.sp,
                color = c.text3,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(3.dp))
        }
        Text(
            cut(value.ifBlank { "—" }),
            fontSize = 27.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (tone == "danger") c.danger else c.text1
        )
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(cut(sub), fontSize = 11.sp, color = c.text3)
        }
    }
}

/* ---------------- list ---------------- */

private data class V2SwipeAction(
    val action: String,
    val label: String,
    val tone: String,
    val args: JSONObject?
)

private fun swipeActionsOf(item: JSONObject): List<V2SwipeAction> {
    val arr = item.optJSONArray("swipe") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        when (val v = arr.opt(i)) {
            is JSONObject -> {
                val action = v.optString("action")
                if (action.isBlank()) null
                else V2SwipeAction(
                    action = action,
                    label = v.optString("label").ifBlank { action },
                    tone = v.optString("tone"),
                    args = v.optJSONObject("args")
                )
            }
            is String -> if (v.isBlank()) null else V2SwipeAction(v, v, "", null)
            else -> null
        }
    }
}

@Composable
private fun V2List(
    block: JSONObject,
    data: JSONObject,
    path: String,
    onAction: (String, JSONObject) -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val source = block.optString("source")
    val template = block.optJSONObject("item") ?: JSONObject()
    val emptyText = UiTemplate.render(block.optString("empty"), data).ifBlank { L.tools.uiListEmpty }

    val items: List<JSONObject> = when (val v = if (source.isBlank()) null else data.opt(source)) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> listOf(v)
        else -> emptyList()
    }
    if (items.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.bg2)
                .padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            Text(cut(emptyText), fontSize = 12.sp, color = c.text3)
        }
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .border(1.dp, c.line2, RoundedCornerShape(10.dp))
    ) {
        items.forEachIndexed { index, item ->
            V2ListRow(
                template = template,
                rowData = UiTemplate.merge(data, item),
                index = index,
                rowKey = "$path:$index",
                onAction = onAction
            )
            if (index != items.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(1.dp)
                        .background(c.line2)
                )
            }
        }
    }
}

@Composable
private fun V2ListRow(
    template: JSONObject,
    rowData: JSONObject,
    index: Int,
    rowKey: String,
    onAction: (String, JSONObject) -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val actions = swipeActionsOf(template)
    val first = actions.firstOrNull()
    val title = UiTemplate.render(template.optString("title"), rowData).ifBlank { "#${index + 1}" }
    val subtitle = UiTemplate.render(template.optString("subtitle"), rowData)
    val value = UiTemplate.render(template.optString("value"), rowData)
    val badge = UiTemplate.render(template.optString("badge"), rowData)
    val verified = template.optJSONObject("verified") // 预留：暂不渲染

    var offsetX by remember(rowKey) { mutableFloatStateOf(0f) }
    var picker by remember(rowKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    // 必须是正数：之前写成 (-72).dp.toPx() 会让下面的 coerceIn 变成空区间直接崩
    val threshold = with(density) { 72.dp.toPx() }.coerceAtLeast(1f)
    val minOffset = minOf(-threshold * 2f, 0f)

    fun rowArgs(extra: JSONObject? = null): JSONObject {
        val out = JSONObject()
        rowData.keys().forEach { key -> out.put(key, rowData.opt(key)) }
        out.put("index", index)
        extra?.keys()?.forEach { key -> out.put(key, extra.opt(key)) }
        return out
    }

    fun trigger(action: V2SwipeAction) {
        onAction(action.action, rowArgs(action.args))
    }

    fun triggerFirst() {
        first?.let { trigger(it) }
    }

    val dragModifier = if (actions.isEmpty()) {
        Modifier
    } else {
        Modifier.pointerInput(actions, rowData.toString()) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount).coerceIn(minOffset, 0f)
                },
                onDragEnd = {
                    if (offsetX < -threshold) triggerFirst()
                    offsetX = 0f
                },
                onDragCancel = { offsetX = 0f }
            )
        }
    }
    val longPressModifier = if (actions.isEmpty()) {
        Modifier
    } else {
        Modifier.pointerInput(actions, rowData.toString()) {
            detectTapGestures(
                onLongPress = {
                    if (actions.size <= 1) triggerFirst() else picker = true
                }
            )
        }
    }

    Box(Modifier.fillMaxWidth()) {
        if (offsetX < 0f && first != null) {
            Row(
                Modifier
                    .matchParentSize()
                    .background(
                        if (first.tone == "danger") c.danger.copy(alpha = 0.10f)
                        else c.brand.copy(alpha = 0.08f)
                    )
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    first.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (first.tone == "danger") c.danger else c.brand
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .background(c.bg2)
                .then(dragModifier)
                .then(longPressModifier)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                if (badge.isNotBlank()) {
                    Text(
                        cut(badge),
                        fontSize = 10.sp,
                        color = c.brand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(c.brand.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                }
                Text(
                    cut(title),
                    fontSize = 13.sp,
                    color = c.text1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        cut(subtitle),
                        fontSize = 11.sp,
                        color = c.text3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (value.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(cut(value), fontSize = 12.5.sp, color = c.text2, fontWeight = FontWeight.Medium)
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text("‹", fontSize = 15.sp, color = c.text3)
            }
        }
    }

    if (picker) {
        AlertDialog(
            onDismissRequest = { picker = false },
            containerColor = c.bg,
            shape = RoundedCornerShape(16.dp),
            title = { Text(L.tools.uiActionConfirmTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text1) },
            text = {
                Column {
                    actions.forEach { act ->
                        Text(
                            act.label,
                            fontSize = 14.sp,
                            color = if (act.tone == "danger") c.danger else c.text1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picker = false
                                    trigger(act)
                                }
                                .padding(vertical = 11.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { picker = false }) { Text(L.common.cancel, color = c.text2) }
            }
        )
    }
    if (verified != null) {
        // 保持引用，避免未使用警告（未来可渲染审核标记）
    }
}

/* ---------------- form ---------------- */

@Composable
private fun V2Form(
    block: JSONObject,
    data: JSONObject,
    path: String,
    onAction: (String, JSONObject) -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val fields = block.optJSONArray("fields") ?: JSONArray()
    val submit = block.optJSONObject("submit")
    val formKey = "$path:${block.toString().hashCode()}"
    val fieldList = remember(formKey) {
        (0 until fields.length()).mapNotNull { fields.optJSONObject(it) }
    }
    val values = remember(formKey) {
        mutableStateMapOf<String, String>().apply {
            fieldList.forEach { field ->
                val name = field.optString("name")
                if (name.isNotBlank()) this[name] = field.optString("value")
            }
        }
    }
    val errors = remember(formKey) { mutableStateMapOf<String, String>() }

    fun submitNow() {
        errors.clear()
        val out = JSONObject()
        var valid = true
        fieldList.forEach { field ->
            val name = field.optString("name")
            if (name.isBlank()) return@forEach
            val label = UiTemplate.render(field.optString("label"), data).ifBlank { name }
            val type = field.optString("type", "text").lowercase()
            val required = field.optBoolean("required", false)
            val raw = (values[name] ?: "").trim()
            if (raw.isBlank()) {
                if (required) {
                    errors[name] = L.format(L.tools.uiFormRequired, label)
                    valid = false
                }
                return@forEach
            }
            when (type) {
                "number", "slider" -> {
                    val d = raw.toDoubleOrNull()
                    if (d == null) {
                        errors[name] = L.format(L.tools.uiFormNumber, label)
                        valid = false
                    } else {
                        out.put(name, d)
                    }
                }
                "toggle" -> out.put(name, raw == "true")
                else -> out.put(name, raw)
            }
        }
        if (!valid) return
        val action = submit?.optString("action").orEmpty()
        if (action.isNotBlank()) onAction(action, out)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .border(1.dp, c.line2, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        fieldList.forEach { field ->
            val name = field.optString("name")
            if (name.isBlank()) return@forEach
            V2FormField(
                field = field,
                data = data,
                value = values[name] ?: "",
                error = errors[name],
                onChange = { v ->
                    values[name] = v
                    if (errors.containsKey(name)) errors.remove(name)
                }
            )
        }
        if (submit != null) {
            V2Button(
                label = UiTemplate.render(submit.optString("label"), data).ifBlank { L.tools.uiSubmit },
                tone = submit.optString("tone"),
                onClick = { submitNow() }
            )
        }
    }
}

@Composable
private fun V2FormField(
    field: JSONObject,
    data: JSONObject,
    value: String,
    error: String?,
    onChange: (String) -> Unit
) {
    val c = LocalColors.current
    val type = field.optString("type", "text").lowercase()
    val label = UiTemplate.render(field.optString("label"), data).ifBlank { field.optString("name") }
    val hint = UiTemplate.render(field.optString("hint", field.optString("placeholder")), data)
    Column(Modifier.fillMaxWidth()) {
        if (type == "toggle") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(cut(label), fontSize = 12.sp, color = c.text1, modifier = Modifier.weight(1f))
                CompactSwitch(value == "true") { onChange(it.toString()) }
            }
        } else {
            if (label.isNotBlank()) {
                Text(cut(label), fontSize = 11.sp, color = c.text3)
                Spacer(Modifier.height(4.dp))
            }
            when (type) {
                "select" -> {
                    val options = field.optJSONArray("options")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i -> UiTemplate.valueText(arr.opt(i)) }
                    } ?: emptyList()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        options.forEach { option ->
                            val selected = option == value
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) c.brand else c.bg3)
                                    .clickable { onChange(option) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    cut(option),
                                    fontSize = 11.5.sp,
                                    color = if (selected) Color.White else c.text2
                                )
                            }
                        }
                    }
                }
                "textarea" -> V2TextField(
                    value = value,
                    hint = hint,
                    singleLine = false,
                    keyboardType = KeyboardType.Text,
                    onChange = onChange
                )
                "number" -> V2TextField(
                    value = value,
                    hint = hint,
                    singleLine = true,
                    keyboardType = KeyboardType.Decimal,
                    onChange = onChange
                )
                else -> V2TextField(
                    value = value,
                    hint = hint,
                    singleLine = true,
                    keyboardType = if (type == "date") KeyboardType.Text else KeyboardType.Text,
                    onChange = onChange
                )
            }
        }
        if (!error.isNullOrBlank()) {
            Text(
                cut(error),
                fontSize = 10.5.sp,
                color = c.danger,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun V2TextField(
    value: String,
    hint: String,
    singleLine: Boolean,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit
) {
    val c = LocalColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 36.dp else 64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.bg3)
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        if (value.isEmpty() && hint.isNotBlank()) {
            Text(cut(hint), fontSize = 12.5.sp, color = c.text3)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 12.5.sp, color = c.text1),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/* ---------------- actions / button ---------------- */

private data class V2ActionItem(
    val action: String,
    val label: String,
    val tone: String,
    val args: JSONObject?
)

@Composable
private fun V2Actions(
    block: JSONObject,
    declared: JSONObject?,
    data: JSONObject,
    onAction: (String, JSONObject) -> Unit
) {
    val items = mutableListOf<V2ActionItem>()
    val arr = block.optJSONArray("items")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is JSONObject -> {
                    val action = v.optString("action")
                    if (action.isNotBlank()) {
                        items.add(
                            V2ActionItem(
                                action,
                                UiTemplate.render(v.optString("label"), data).ifBlank { action },
                                v.optString("tone"),
                                v.optJSONObject("args")
                            )
                        )
                    }
                }
                is String -> if (v.isNotBlank()) items.add(V2ActionItem(v, v, "", null))
            }
        }
    } else {
        val single = block.optString("action")
        if (single.isNotBlank()) {
            items.add(
                V2ActionItem(
                    single,
                    UiTemplate.render(block.optString("label"), data).ifBlank { single },
                    block.optString("tone"),
                    block.optJSONObject("args")
                )
            )
        }
    }
    if (items.isEmpty() && declared != null) {
        declared.keys().forEach { name ->
            val meta = declared.optJSONObject(name)
            items.add(V2ActionItem(name, name, meta?.optString("tone").orEmpty(), null))
        }
    }
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            V2Button(label = item.label, tone = item.tone) {
                onAction(item.action, item.args ?: JSONObject())
            }
        }
    }
}

@Composable
private fun V2Button(
    label: String,
    tone: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    val danger = tone == "danger"
    val bg = when {
        !enabled -> c.bg3
        danger -> c.danger.copy(alpha = 0.12f)
        else -> c.brand
    }
    val fg = when {
        !enabled -> c.text3
        danger -> c.danger
        else -> Color.White
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(cut(label), fontSize = 12.5.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/* ---------------- legacy wrapper ---------------- */

@Composable
private fun V2Legacy(block: JSONObject, onLegacyEvent: (String, String) -> Unit) {
    val raw = block.opt("schema") ?: return
    val text = when (raw) {
        is JSONObject, is JSONArray -> raw.toString()
        is String -> raw
        else -> raw.toString()
    }
    val uiMode = block.optString("uiMode").ifBlank { "schema" }
    UserToolUi(
        raw = text,
        uiMode = uiMode,
        onEvent = onLegacyEvent,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 非 v2 专用的白名单节点：插值后交给旧 RenderNode 渲染 */
@Composable
private fun V2LegacyNode(
    block: JSONObject,
    data: JSONObject,
    onLegacyEvent: (String, String) -> Unit
) {
    val rendered = runCatching { UiTemplate.renderJson(block, data) as? JSONObject }.getOrNull() ?: block
    UserToolUi(
        raw = rendered.toString(),
        uiMode = "schema",
        onEvent = onLegacyEvent,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun cut(text: String): String =
    if (text.length > TEXT_LIMIT) text.take(TEXT_LIMIT) + "…" else text
