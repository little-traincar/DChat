package com.dchat.app.ui.tools

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.ui.CompactSwitch
import com.dchat.app.ui.chat.MarkdownText
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.theme.LocalColors
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.roundToInt

/**
 * 4b：用户工具 UI 渲染器。
 * - uiMode=data：按 JSON 形状自动渲染（对象→键值卡、数组→列表、数字→大数值、字符串/Markdown→文本卡）
 * - uiMode=schema：渲染白名单 UI 树
 * 防护：节点 ≤500、深度 ≤12、单文本 ≤8000；超限显示提示不崩。
 * 只用 LocalColors 主题 token，不出现任意颜色。
 */
@Composable
fun UserToolUi(
    raw: String,
    uiMode: String,
    modifier: Modifier = Modifier,
    onEvent: (String, String) -> Unit = { _, _ -> }
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    if (raw.isBlank()) return
    val root = remember(raw) {
        runCatching { JSONTokener(raw).nextValue() }.getOrNull()
    }
    if (root == null) {
        UiTextCard(raw.take(TEXT_LIMIT), modifier)
        return
    }
    if (uiMode == "schema" && root is JSONObject && root.optString("type").isNotBlank()) {
        val budget = remember(raw) { UiBudget() }
        val state = remember(raw) { mutableStateMapOf<String, String>() }
        RenderNode(
            node = root,
            depth = 1,
            path = "root",
            budget = budget,
            state = state,
            onEvent = onEvent,
            modifier = modifier
        )
        if (budget.overflow) {
            Text(
                L.tools.uiTooLarge,
                fontSize = 11.sp,
                color = c.text3,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    } else {
        DataRoot(root, modifier, onEvent)
    }
}

private const val TEXT_LIMIT = 8000
private const val NODE_LIMIT = 500
private const val DEPTH_LIMIT = 12

private class UiBudget {
    var count = 0
    var overflow = false
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

private fun cut(s: String): String = if (s.length > TEXT_LIMIT) s.take(TEXT_LIMIT) + "…" else s

@Composable
private fun DataRoot(root: Any, modifier: Modifier, onEvent: (String, String) -> Unit) {
    when (root) {
        is JSONObject -> KeyValueCard(root, modifier, onEvent)
        is JSONArray -> {
            val objs = (0 until root.length()).mapNotNull { root.optJSONObject(it) }
            if (objs.size == root.length() && objs.isNotEmpty()) {
                ObjectList(objs, modifier)
            } else {
                Column(modifier) {
                    for (i in 0 until root.length()) {
                        DataRoot(root.opt(i) ?: JSONObject.NULL, Modifier.fillMaxWidth(), onEvent)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        is Number -> BigNumber(root.toString(), modifier)
        is Boolean -> UiTextCard(root.toString(), modifier)
        is String -> {
            if (looksMarkdown(root)) MarkdownCard(root, modifier) else UiTextCard(root, modifier)
        }
        else -> UiTextCard(root.toString(), modifier)
    }
}

@Composable
private fun KeyValueCard(obj: JSONObject, modifier: Modifier, onEvent: (String, String) -> Unit) {
    val c = LocalColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        obj.keys().forEach { k ->
            val v = obj.opt(k)
            if (v is JSONObject || v is JSONArray) {
                Text(k, fontSize = 11.5.sp, color = c.text3, fontWeight = FontWeight.Medium)
                DataRoot(v, Modifier.fillMaxWidth(), onEvent)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        cut(k),
                        fontSize = 12.sp,
                        color = c.text3,
                        modifier = Modifier.width(92.dp)
                    )
                    Text(
                        cut(valueText(v)),
                        fontSize = 12.5.sp,
                        color = c.text1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ObjectList(items: List<JSONObject>, modifier: Modifier) {
    val c = LocalColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .padding(vertical = 4.dp)
    ) {
        items.forEachIndexed { index, item ->
            val keys = item.keys().asSequence().toList()
            val title = keys.firstOrNull()?.let { valueText(item.opt(it)) } ?: "#${index + 1}"
            val sub = keys.drop(1).take(3).joinToString(" · ") { k -> "${k}: ${valueText(item.opt(k))}" }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(cut(title), fontSize = 13.sp, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) {
                    Text(cut(sub), fontSize = 11.sp, color = c.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (index != items.lastIndex) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(c.line2))
            }
        }
    }
}

@Composable
private fun BigNumber(value: String, modifier: Modifier) {
    val c = LocalColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = c.brand)
    }
}

@Composable
private fun UiTextCard(text: String, modifier: Modifier) {
    val c = LocalColors.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .padding(10.dp)
    ) {
        Text(cut(text), fontSize = 12.5.sp, color = c.text1, lineHeight = 18.sp)
    }
}

@Composable
private fun MarkdownCard(text: String, modifier: Modifier) {
    val c = LocalColors.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
            .padding(10.dp)
    ) {
        MarkdownText(cut(text), color = c.text1)
    }
}

private fun looksMarkdown(s: String): Boolean =
    s.contains("##") || s.contains("**") || (s.contains('|') && s.contains('\n'))

private fun valueText(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is String -> v
    else -> v.toString()
}

/* ---------------- schema tree ---------------- */

private fun JSONObject.childrenArray(): JSONArray? = optJSONArray("children")

@Composable
private fun RenderNode(
    node: JSONObject,
    depth: Int,
    path: String,
    budget: UiBudget,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (depth > DEPTH_LIMIT || !budget.take()) return
    val type = node.optString("type").lowercase()
    val style = node.optJSONObject("style")
    val pad = style?.optInt("padding", 0) ?: 0
    val gap = style?.optInt("gap", 8) ?: 8
    val emphasis = style?.optBoolean("emphasis", false) ?: false
    val mod = if (pad > 0) modifier.padding(pad.dp) else modifier
    when (type) {
        "column" -> Column(mod, verticalArrangement = Arrangement.spacedBy(gap.dp)) {
            children(node).forEachIndexed { i, child ->
                RenderNode(child, depth + 1, "$path.c$i", budget, state, onEvent, Modifier.fillMaxWidth())
            }
        }
        "row" -> Row(mod, horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
            children(node).forEachIndexed { i, child ->
                RenderNode(child, depth + 1, "$path.r$i", budget, state, onEvent)
            }
        }
        "card", "group" -> {
            val c = LocalColors.current
            Column(
                mod
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.bg2)
                    .border(1.dp, c.line2, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(gap.dp)
            ) {
                children(node).forEachIndexed { i, child ->
                    RenderNode(child, depth + 1, "$path.g$i", budget, state, onEvent, Modifier.fillMaxWidth())
                }
            }
        }
        "spacer" -> Spacer(Modifier.height((node.optInt("value", 8)).coerceIn(0, 80).dp))
        "divider" -> {
            val c = LocalColors.current
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(c.line2))
        }
        "scroll" -> Column(
            mod
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
        ) {
            children(node).forEachIndexed { i, child ->
                RenderNode(child, depth + 1, "$path.s$i", budget, state, onEvent, Modifier.fillMaxWidth())
            }
        }
        "text" -> UiTextNode(node, emphasis, mod)
        "markdown" -> MarkdownCard(node.optString("value"), mod)
        "image" -> UiImageNode(node, mod)
        "badge" -> UiBadge(node, mod)
        "progress" -> UiProgress(node, mod)
        "keyvalue" -> {
            val obj = node.optJSONObject("value") ?: node
            KeyValueCard(obj, mod, onEvent)
        }
        "input", "textarea" -> UiInput(node, path, depth, state, onEvent, mod)
        "toggle" -> UiToggle(node, path, state, onEvent, mod)
        "select" -> UiSelect(node, path, state, onEvent, mod)
        "slider" -> UiSlider(node, path, state, onEvent, mod)
        "date" -> UiDate(node, path, state, onEvent, mod)
        "button" -> UiButton(node, depth, state, onEvent, mod)
        "list" -> UiList(node, depth, path, budget, state, onEvent, mod)
        "bar" -> UiBarChart(node, mod)
        "line" -> UiLineChart(node, mod)
        "pie" -> UiPieChart(node, mod)
        else -> Unit // 白名单外直接忽略
    }
}

private fun children(node: JSONObject): List<JSONObject> {
    val arr = node.childrenArray() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

@Composable
private fun UiTextNode(node: JSONObject, emphasis: Boolean, modifier: Modifier) {
    val c = LocalColors.current
    val value = cut(node.optString("value"))
    val style = node.optString("style")
    val size = when (style) {
        "title" -> 16.sp
        "number" -> 26.sp
        "caption" -> 11.sp
        else -> 12.5.sp
    }
    Text(
        value,
        modifier = modifier,
        fontSize = size,
        color = if (emphasis) c.text1 else c.text2,
        fontWeight = if (emphasis || style == "title" || style == "number") FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = if (style == "number") 32.sp else 18.sp
    )
}

@Composable
private fun UiImageNode(node: JSONObject, modifier: Modifier) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current
    val path = node.optString("value")
    val bmp = remember(path) {
        runCatching { BitmapFactory.decodeFile(if (path.startsWith("file://")) path.removePrefix("file://") else path) }
            .getOrNull()
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.fillMaxWidth().heightIn(max = 240.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
    } else {
        Text(L.tools.uiImageUnavailable, fontSize = 11.sp, color = c.text3, modifier = modifier)
    }
}

@Composable
private fun UiBadge(node: JSONObject, modifier: Modifier) {
    val c = LocalColors.current
    Text(
        cut(node.optString("value")),
        fontSize = 11.sp,
        color = c.brand,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(c.brand.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun UiProgress(node: JSONObject, modifier: Modifier) {
    val c = LocalColors.current
    val p = node.optDouble("value", 0.0).coerceIn(0.0, 1.0).toFloat()
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(c.line2)) {
        Box(Modifier.fillMaxWidth(p).fillMaxSize().background(c.brand))
    }
}

@Composable
private fun UiInput(
    node: JSONObject,
    path: String,
    depth: Int,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier
) {
    val c = LocalColors.current
    val isArea = node.optString("type") == "textarea"
    val initial = state[path] ?: node.optString("value")
    var text by remember(node, path) { mutableStateOf(initial) }
    Column(modifier) {
        if (node.optString("label").isNotBlank()) {
            Text(node.optString("label"), fontSize = 11.sp, color = c.text3)
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (isArea) 64.dp else 38.dp, max = if (isArea) 120.dp else 38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.bg3)
                .padding(horizontal = 9.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    state[path] = it
                    val ev = node.optString("event")
                    if (ev.isNotBlank()) onEvent(ev, it)
                },
                textStyle = TextStyle(fontSize = 12.5.sp, color = c.text1),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun UiToggle(
    node: JSONObject,
    path: String,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier
) {
    val c = LocalColors.current
    val initial = (state[path] ?: node.optString("value")) == "true"
    var checked by remember(node, path) { mutableStateOf(initial) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(cut(node.optString("label", node.optString("value"))), fontSize = 12.sp, color = c.text1, modifier = Modifier.weight(1f))
        CompactSwitch(checked) {
            checked = it
            state[path] = it.toString()
            val ev = node.optString("event")
            if (ev.isNotBlank()) onEvent(ev, it.toString())
        }
    }
}

@Composable
private fun UiSelect(
    node: JSONObject,
    path: String,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier
) {
    val c = LocalColors.current
    val options = node.optJSONArray("options")?.let { a ->
        (0 until a.length()).map { i -> valueText(a.opt(i)) }
    } ?: emptyList()
    val initial = state[path] ?: node.optString("value").ifBlank { options.firstOrNull() ?: "" }
    var selected by remember(node, path) { mutableStateOf(initial) }
    Column(modifier) {
        if (node.optString("label").isNotBlank()) {
            Text(node.optString("label"), fontSize = 11.sp, color = c.text3)
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(c.bg3)
                .clickable {
                    if (options.isNotEmpty()) {
                        val idx = options.indexOf(selected)
                        selected = options[(idx + 1) % options.size]
                        state[path] = selected
                        val ev = node.optString("event")
                        if (ev.isNotBlank()) onEvent(ev, selected)
                    }
                }
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            Text(selected.ifBlank { options.firstOrNull() ?: "" }, fontSize = 12.sp, color = c.text1)
            Text("▾", fontSize = 11.sp, color = c.text3, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun UiSlider(
    node: JSONObject,
    path: String,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier
) {
    val c = LocalColors.current
    val min = node.optDouble("min", 0.0)
    val max = node.optDouble("max", 100.0).coerceAtLeast(min + 0.0001)
    val step = node.optDouble("step", 1.0).coerceAtLeast(0.0001)
    val initial = (state[path] ?: node.optString("value"))?.toDoubleOrNull() ?: min
    var value by remember(node, path) { mutableFloatStateOf(initial.toFloat()) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cut(node.optString("label")), fontSize = 11.sp, color = c.text3, modifier = Modifier.weight(1f))
            Text(formatNum(value.toDouble()), fontSize = 11.sp, color = c.text2)
        }
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = { v ->
                val snapped = (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max)
                value = snapped.toFloat()
                state[path] = formatNum(snapped)
            },
            valueRange = min.toFloat()..max.toFloat(),
            onValueChangeFinished = {
                val ev = node.optString("event")
                if (ev.isNotBlank()) onEvent(ev, formatNum(value.toDouble()))
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun UiDate(
    node: JSONObject,
    path: String,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier
) {
    val c = LocalColors.current
    val initial = state[path] ?: node.optString("value")
    var text by remember(node, path) { mutableStateOf(initial) }
    Column(modifier) {
        Text(cut(node.optString("label", "YYYY-MM-DD")), fontSize = 11.sp, color = c.text3)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bg3)
                .padding(horizontal = 9.dp, vertical = 9.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    state[path] = it
                    val ev = node.optString("event")
                    if (ev.isNotBlank()) onEvent(ev, it)
                },
                textStyle = TextStyle(fontSize = 12.sp, color = c.text1),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun UiButton(
    node: JSONObject,
    depth: Int,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier
) {
    val c = LocalColors.current
    val label = cut(node.optString("label", node.optString("value", "OK")))
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(c.brand)
            .clickable {
                val event = node.optString("event").ifBlank { "click" }
                val payload = JSONObject()
                if (state.isNotEmpty()) {
                    state.forEach { (k, v) -> payload.put(k.substringAfterLast('.'), v) }
                }
                val v = node.optString("value")
                if (v.isNotBlank()) payload.put("value", v)
                onEvent(event, payload.toString())
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.5.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UiList(
    node: JSONObject,
    depth: Int,
    path: String,
    budget: UiBudget,
    state: MutableMap<String, String>,
    onEvent: (String, String) -> Unit,
    modifier: Modifier
) {
    val c = LocalColors.current
    val itemsArr = node.optJSONArray("items")
    val itemTemplate = node.optJSONObject("itemTemplate")
    val swipeDelete = node.optBoolean("swipeDelete", false)
    val listItems: List<JSONObject> = if (itemsArr != null) {
        (0 until itemsArr.length()).mapNotNull { i ->
            when (val v = itemsArr.opt(i)) {
                is JSONObject -> v
                else -> null
            }
        }
    } else children(node)
    if (listItems.isEmpty()) return
    LazyColumn(
        modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.bg2)
    ) {
        itemsIndexed(listItems) { index, item ->
            val density = LocalDensity.current
            var offsetX by remember(index, item) { mutableFloatStateOf(0f) }
            val threshold = with(density) { (-64).dp.toPx() }
            val row = @Composable {
                val itemNode = itemTemplate?.let { mergeTemplate(it, item) } ?: item
                Row(
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .then(
                            if (swipeDelete) Modifier.pointerInput(index, item) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (offsetX < threshold) {
                                            onEvent("delete", item.toString())
                                        }
                                        offsetX = 0f
                                    }
                                ) { change, drag ->
                                    offsetX += drag
                                    change.consume()
                                }
                            } else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        if (depth + 1 <= DEPTH_LIMIT) {
                            RenderNode(itemNode, depth + 1, "$path.i$index", budget, state, onEvent, Modifier.fillMaxWidth())
                        }
                    }
                    if (swipeDelete) {
                        Text("‹", fontSize = 16.sp, color = c.text3)
                    }
                }
            }
            row()
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(c.line2))
        }
    }
}

private fun mergeTemplate(template: JSONObject, item: JSONObject): JSONObject {
    return runCatching { JSONObject(template.toString()).apply { item.keys().forEach { k -> put(k, item.opt(k)) } } }
        .getOrDefault(item)
}

@Composable
private fun UiBarChart(node: JSONObject, modifier: Modifier) {
    val c = LocalColors.current
    val data = chartData(node)
    if (data.isEmpty()) return
    val max = data.maxOf { it.second }.coerceAtLeast(0.0001)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            data.forEach { (_, v) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(top = (120 * (1 - v / max)).toFloat().dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(c.brand)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            data.forEach { (label, _) ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    fontSize = 9.5.sp,
                    color = c.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun UiLineChart(node: JSONObject, modifier: Modifier) {
    val c = LocalColors.current
    val data = chartData(node)
    if (data.isEmpty()) return
    val max = data.maxOf { it.second }.coerceAtLeast(0.0001)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val step = if (data.size <= 1) 0f else size.width / (data.size - 1)
            val pts = data.mapIndexed { i, d ->
                Offset(i * step, size.height - (d.second / max).toFloat() * size.height)
            }
            val path = Path().apply {
                pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            drawPath(path, c.brand, style = Stroke(width = 3f))
            pts.forEach { drawCircle(c.brand, radius = 4f, center = it) }
        }
        Row(Modifier.fillMaxWidth()) {
            data.forEach { (label, _) ->
                Text(label, modifier = Modifier.weight(1f), fontSize = 9.5.sp, color = c.text3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UiPieChart(node: JSONObject, modifier: Modifier) {
    val c = LocalColors.current
    val data = chartData(node)
    if (data.isEmpty()) return
    val total = data.sumOf { it.second.coerceAtLeast(0.0) }.coerceAtLeast(0.0001)
    val shades = listOf(1f, 0.78f, 0.56f, 0.36f, 0.2f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(120.dp)) {
            var start = -90f
            data.forEachIndexed { i, d ->
                val sweep = (d.second / total * 360).toFloat()
                drawArc(
                    color = c.brand.copy(alpha = shades[i % shades.size]),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = true
                )
                start += sweep
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            data.forEachIndexed { i, d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(c.brand.copy(alpha = shades[i % shades.size])))
                    Spacer(Modifier.width(5.dp))
                    Text("${d.first}: ${formatNum(d.second)}", fontSize = 10.5.sp, color = c.text2)
                }
            }
        }
    }
}

private fun chartData(node: JSONObject): List<Pair<String, Double>> {
    val arr = node.optJSONArray("data") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        (o.optString("label") to o.optDouble("value", 0.0))
    }
}

private fun formatNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else String.format("%.2f", d)
