package com.dchat.app.ui.tools

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.tools.PyResult
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.data.tools.ToolMode
import com.dchat.app.data.tools.ToolRegistry
import com.dchat.app.data.tools.UserToolDef
import com.dchat.app.data.tools.UserToolStore
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "UiV2Screen"
private const val PAGE_STATE = "page"
private const val DEFAULT_TIMEOUT_MS = 5_000

/**
 * 用户工具独立全屏页面（UI Schema v2）。
 *
 * 打开顺序：
 * 1. 有合法 `ui.json` → **立即静态渲染**（`ui.json.data ∪ state/page.json`），随后后台跑 `load`；
 * 2. 没有 `ui.json` → 走旧工具链路 `open`，成功后把返回的 UI 树包成 `legacy` 块写盘，
 *    下次打开就走静态路径，不再执行 `open`；
 * 3. `load` 失败只显示「数据可能已过期」，页面不白屏、不暂停工具；`action` 失败只提示该动作。
 *
 * 动作/加载串行化：同一个工具的 Python 调用用 Mutex 排队，避免 `entry_preview.py`
 * 临时文件并发写入互相覆盖。
 */
@Composable
fun ToolUiScreen(toolId: String, onBack: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val def = remember(toolId) { UserToolStore.find(ctx, toolId) }
    val registrySpec = remember(toolId) { ToolRegistry.byId(toolId) }
    val fallbackTitle = remember(toolId) {
        (def?.name ?: "").ifBlank { registrySpec?.name ?: toolId }
    }
    val convMode = when (def?.minMode ?: registrySpec?.minMode) {
        ToolMode.READ_ONLY -> ToolRegistry.CONV_READ
        ToolMode.FULL -> ToolRegistry.CONV_FULL
        else -> ToolRegistry.CONV_WORKSPACE
    }
    val pyMutex = remember(toolId) { Mutex() }

    var uiSpec by remember(toolId) { mutableStateOf<JSONObject?>(null) }
    var data by remember(toolId) { mutableStateOf(JSONObject()) }
    var pageState by remember(toolId) { mutableStateOf(JSONObject()) }
    var loading by remember(toolId) { mutableStateOf(true) }
    var refreshing by remember(toolId) { mutableStateOf(false) }
    var actionRunning by remember(toolId) { mutableStateOf<String?>(null) }
    var legacyRunning by remember(toolId) { mutableStateOf(false) }
    var stale by remember(toolId) { mutableStateOf(false) }
    var fatalError by remember(toolId) { mutableStateOf<String?>(null) }
    var actionError by remember(toolId) { mutableStateOf<String?>(null) }

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun detailOf(r: PyResult): String =
        (r.errorMessage ?: "").ifBlank { r.errorCode ?: "TOOL_ERROR" }.take(300)

    fun failText(r: PyResult): String =
        if (UiV2Protocol.isPyUnavailable(r)) L.tools.uiPyUnavailable
        else L.format(L.tools.uiActionFailed, detailOf(r))

    fun openFailText(r: PyResult): String =
        if (UiV2Protocol.isPyUnavailable(r)) L.tools.uiPyUnavailable
        else L.format(L.tools.uiOpenFailed, detailOf(r))

    fun loadTimeout(spec: JSONObject): Int =
        (spec.optJSONObject("load")?.optInt("timeoutMs", DEFAULT_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(500, 30_000)

    fun showLoadErrors(spec: JSONObject): Boolean =
        spec.optJSONObject("load")?.optBoolean("showErrors", true) ?: true

    suspend fun callPy(
        d: UserToolDef,
        entry: String,
        code: String,
        payload: JSONObject,
        timeoutMs: Int
    ): PyResult = pyMutex.withLock {
        PyRuntime.runCode(
            context = ctx,
            toolId = toolId,
            entryFunction = entry,
            code = code,
            argsJson = payload.toString(),
            conversationId = PAGE_STATE,
            timeoutMs = timeoutMs,
            conversationMode = convMode
        )
    }

    suspend fun saveState(state: JSONObject) {
        pageState = state
        withContext(Dispatchers.IO) {
            UserToolStore.writeState(ctx, toolId, PAGE_STATE, state)
        }
    }

    /** 旧工具动作返回 UI 树里可能带 state/_state，沿用 v1 语义写回 */
    suspend fun applyLegacyState(parsed: Any?) {
        val obj = parsed as? JSONObject ?: return
        val st = obj.optJSONObject("state") ?: obj.optJSONObject("_state") ?: return
        saveState(st)
    }

    /** 后台 load：成功合并数据；失败只做「数据可能已过期」提示（showErrors=false 时静默） */
    suspend fun doLoad(d: UserToolDef, spec: JSONObject) {
        val payload = UiV2Protocol.loadPayload(pageState)
        val timeout = loadTimeout(spec)
        Log.d(TAG, "load tool=$toolId timeout=$timeout")
        val r = callPy(d, UiV2Protocol.ENTRY_LOAD, UiV2Protocol.adaptedCode(d.code), payload, timeout)
        if (!r.ok) {
            Log.w(TAG, "load failed tool=$toolId code=${r.errorCode} msg=${r.errorMessage}")
            if (showLoadErrors(spec)) stale = true
            return
        }
        val result = UiV2Protocol.parseObject(r.output)
        if (result == null) {
            Log.w(TAG, "load output is not a JSON object tool=$toolId")
            if (showLoadErrors(spec)) stale = true
            return
        }
        val err = UiV2Protocol.errorOf(result)
        if (err.isNotBlank()) {
            Log.w(TAG, "load reported error tool=$toolId: $err")
            if (showLoadErrors(spec)) {
                stale = true
                actionError = err
            }
            return
        }
        UiV2Protocol.stateOf(result)?.let { saveState(it) }
        data = UiTemplate.merge(data, UiV2Protocol.stripMeta(result))
        stale = false
    }

    /** 旧工具 open 链路：成功后包成 legacy 块落盘为 ui.json，之后走静态 */
    suspend fun openLegacy(d: UserToolDef, saved: JSONObject, initial: Boolean) {
        if (initial) loading = true
        fatalError = null
        Log.i(TAG, "legacy-open tool=$toolId initial=$initial")
        val r = callPy(
            d = d,
            entry = d.entryFunction,
            code = d.code,
            payload = UiV2Protocol.openPayload(saved),
            timeoutMs = DEFAULT_TIMEOUT_MS
        )
        if (initial) loading = false
        if (!r.ok) {
            val msg = openFailText(r)
            Log.w(TAG, "legacy-open failed tool=$toolId code=${r.errorCode}")
            if (initial) {
                fatalError = msg
            } else {
                actionError = msg
                stale = true
                toast(msg)
            }
            return
        }
        val parsed = UiV2Protocol.parseAny(r.output)
        val schemaMode = d.uiMode == "schema" &&
            parsed is JSONObject && parsed.optString("type").isNotBlank()
        val spec = UiSpecStore.legacy(parsed ?: r.output, if (schemaMode) "schema" else "data")
        uiSpec = spec
        data = UiTemplate.merge(spec.optJSONObject("data"), saved)
        stale = false
        withContext(Dispatchers.IO) { UiSpecStore.write(ctx, toolId, spec) }
        applyLegacyState(parsed)
        Log.i(TAG, "migrated tool=$toolId mode=${if (schemaMode) "schema" else "data"}")
        if (UiV2Protocol.hasLoad(d.code)) doLoad(d, spec)
    }

    /** 纯旧工具的手动刷新：重跑 open 并更新 legacy 树（页面打开本身不会再触发） */
    suspend fun refreshLegacy(d: UserToolDef, spec: JSONObject) {
        Log.d(TAG, "legacy-refresh tool=$toolId")
        val r = callPy(
            d = d,
            entry = d.entryFunction,
            code = d.code,
            payload = UiV2Protocol.openPayload(pageState),
            timeoutMs = DEFAULT_TIMEOUT_MS
        )
        if (!r.ok) {
            val msg = openFailText(r)
            actionError = msg
            stale = true
            toast(msg)
            return
        }
        val parsed = UiV2Protocol.parseAny(r.output)
        val next = UiSpecStore.withLegacySchema(spec, parsed ?: r.output)
        uiSpec = next
        withContext(Dispatchers.IO) { UiSpecStore.write(ctx, toolId, next) }
        applyLegacyState(parsed)
        stale = false
    }

    fun refresh() {
        val d = def ?: return
        if (refreshing || actionRunning != null || legacyRunning) return
        scope.launch {
            refreshing = true
            actionError = null
            try {
                val spec = uiSpec
                if (spec == null) {
                    openLegacy(d, pageState, initial = false)
                } else if (UiV2Protocol.hasLoad(d.code)) {
                    doLoad(d, spec)
                } else {
                    val legacy = UiSpecStore.legacyBlock(spec)
                    if (legacy != null) refreshLegacy(d, spec) else stale = false
                }
            } finally {
                refreshing = false
            }
        }
    }

    /** v2 动作：args 就是动作字段；失败只提示该动作，不白屏、不暂停工具 */
    fun onAction(name: String, args: JSONObject) {
        val d = def ?: return
        if (actionRunning != null || legacyRunning) return
        scope.launch {
            actionRunning = name
            actionError = null
            try {
                Log.d(TAG, "action tool=$toolId name=$name")
                val r = callPy(
                    d = d,
                    entry = UiV2Protocol.ENTRY_ACTION,
                    code = UiV2Protocol.adaptedCode(d.code),
                    payload = UiV2Protocol.actionPayload(name, args, data, pageState),
                    timeoutMs = loadTimeout(uiSpec ?: JSONObject())
                )
                if (!r.ok) {
                    val msg = failText(r)
                    actionError = msg
                    toast(msg)
                    return@launch
                }
                val result = UiV2Protocol.parseObject(r.output)
                if (result != null) {
                    val err = UiV2Protocol.errorOf(result)
                    if (err.isNotBlank()) {
                        actionError = err
                        toast(err)
                        return@launch
                    }
                    UiV2Protocol.stateOf(result)?.let { saveState(it) }
                    data = UiTemplate.merge(data, UiV2Protocol.stripMeta(result))
                }
                stale = false
                val spec = uiSpec
                if (spec != null && UiV2Protocol.hasLoad(d.code)) doLoad(d, spec)
            } finally {
                actionRunning = null
            }
        }
    }

    /** legacy 块里的旧事件（button/input 的 event）：仍按 v1 `run(event)` 执行并更新旧树 */
    fun onLegacyEvent(event: String, value: String) {
        val d = def ?: return
        if (actionRunning != null || legacyRunning) return
        scope.launch {
            legacyRunning = true
            actionError = null
            try {
                val parsedValue: Any = runCatching { JSONTokener(value).nextValue() }.getOrDefault(value)
                val payload = JSONObject()
                    .put("event", event)
                    .put("state", pageState)
                    .put("value", JSONObject.wrap(parsedValue))
                Log.d(TAG, "legacy-event tool=$toolId event=$event")
                val r = callPy(d, d.entryFunction, d.code, payload, DEFAULT_TIMEOUT_MS)
                if (!r.ok) {
                    val msg = failText(r)
                    actionError = msg
                    toast(msg)
                    return@launch
                }
                val parsed = UiV2Protocol.parseAny(r.output)
                val current = uiSpec
                if (current != null) {
                    val next = UiSpecStore.withLegacySchema(current, parsed ?: r.output)
                    uiSpec = next
                    withContext(Dispatchers.IO) { UiSpecStore.write(ctx, toolId, next) }
                    applyLegacyState(parsed)
                }
                stale = false
            } finally {
                legacyRunning = false
            }
        }
    }

    fun resetState() {
        if (refreshing || actionRunning != null || legacyRunning) return
        scope.launch {
            withContext(Dispatchers.IO) {
                UserToolStore.stateFile(ctx, toolId, PAGE_STATE).delete()
            }
            pageState = JSONObject()
            data = UiTemplate.merge(uiSpec?.optJSONObject("data"), JSONObject())
            stale = false
            actionError = null
            val d = def ?: return@launch
            refreshing = true
            try {
                val spec = uiSpec
                if (spec == null) {
                    openLegacy(d, pageState, initial = false)
                } else if (UiV2Protocol.hasLoad(d.code)) {
                    doLoad(d, spec)
                } else {
                    val legacy = UiSpecStore.legacyBlock(spec)
                    if (legacy != null) refreshLegacy(d, spec) else stale = false
                }
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(toolId) {
        val d = def
        if (d == null) {
            loading = false
            fatalError = "TOOL_NOT_FOUND: $toolId"
            return@LaunchedEffect
        }
        val saved = withContext(Dispatchers.IO) {
            UserToolStore.readState(ctx, toolId, PAGE_STATE)
        }
        pageState = saved
        val hasFile = withContext(Dispatchers.IO) { UiSpecStore.file(ctx, toolId).isFile }
        val spec = if (hasFile) {
            withContext(Dispatchers.IO) { UiSpecStore.read(ctx, toolId) }
        } else {
            null
        }
        if (spec != null) {
            Log.i(TAG, "open-static tool=$toolId")
            uiSpec = spec
            data = UiTemplate.merge(spec.optJSONObject("data"), saved)
            loading = false
            if (UiV2Protocol.hasLoad(d.code)) doLoad(d, spec)
        } else {
            if (hasFile) {
                // 文件在但解析失败：保留旧文件不动，提示可能过期，然后尝试旧链路
                Log.w(TAG, "ui.json unreadable tool=$toolId")
                stale = true
            }
            openLegacy(d, saved, initial = true)
        }
    }

    val screenTitle = uiSpec?.optString("title").orEmpty().ifBlank { fallbackTitle }
    val busy = refreshing || actionRunning != null || legacyRunning

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Rounded.ArrowBackIosNew, onBack, iconSize = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text(
                screenTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { resetState() }) {
                Text(L.tools.resetState, fontSize = 11.5.sp, color = c.text3)
            }
            IconBtn(Icons.Rounded.Refresh, { refresh() }, tint = c.text3, iconSize = 19.dp)
        }

        if (busy) {
            Text(
                L.tools.uiWorking,
                fontSize = 10.5.sp,
                color = c.text3,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
        }
        if (stale) {
            StaleBanner(
                text = L.tools.uiStale,
                retry = L.tools.uiRetry,
                onRetry = { refresh() }
            )
        }
        val err = actionError
        if (!err.isNullOrBlank()) {
            Text(
                err,
                fontSize = 11.5.sp,
                color = c.danger,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        Box(Modifier.fillMaxSize()) {
            val spec = uiSpec
            val fatal = fatalError
            when {
                spec != null -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        UiV2Renderer(
                            spec = spec,
                            data = data,
                            onAction = { name, args -> onAction(name, args) },
                            onLegacyEvent = { event, value -> onLegacyEvent(event, value) }
                        )
                    }
                }

                fatal != null -> Column(Modifier.padding(16.dp)) {
                    Text(fatal, fontSize = 12.5.sp, color = c.danger, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { refresh() }) {
                        Text(L.tools.uiRetry, fontSize = 12.sp, color = c.brand)
                    }
                }

                loading -> Text(
                    L.tools.editorRunning,
                    fontSize = 12.sp,
                    color = c.text3,
                    modifier = Modifier.padding(16.dp)
                )

                else -> Text(
                    L.tools.uiPreviewEmpty,
                    fontSize = 12.sp,
                    color = c.text3,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun StaleBanner(text: String, retry: String, onRetry: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.bg2)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = c.text2,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) {
            Text(retry, fontSize = 11.sp, color = c.brand)
        }
    }
}
