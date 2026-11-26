package com.dchat.app.ui.tasks

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.AppState
import com.dchat.app.data.tools.BgTasks
import com.dchat.app.data.tools.ScheduleStore
import com.dchat.app.ui.CompactSwitch
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.Str
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 定时任务页（会话页「⋮」→ 定时任务）。
 *
 * - 只显示**当前会话**创建的任务（[ScheduleStore.listFor]）；
 * - 卡片：名称 / 动作 / 周期 / 下次运行 / 上次运行 / 启停开关；
 * - 操作：编辑（周期 / 每天时间 / 参数 JSON）、立即运行一次、删除。
 */
@Composable
fun ScheduleTaskScreen(
    app: AppState,
    conversationId: String?,
    onBack: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current

    var tick by remember { mutableStateOf(0) }
    var editTarget by remember { mutableStateOf<ScheduleStore.ScheduleRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<ScheduleStore.ScheduleRecord?>(null) }

    // 调度状态（下次运行/上次结果）会被闹钟触发改变，打开期间每 1.5s 刷新一次内存快照
    LaunchedEffect(conversationId) {
        while (true) {
            delay(1500)
            tick++
        }
    }
    val records = remember(tick, conversationId) {
        conversationId?.let { ScheduleStore.listFor(it) } ?: emptyList()
    }

    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }

    fun toggle(rec: ScheduleStore.ScheduleRecord, enabled: Boolean) {
        val updated = ScheduleStore.update(ctx, rec.id, enabled = enabled)
        if (updated == null) toast(L.format(L.local.scheduleNotFound, rec.name)) else tick++
    }

    fun runNow(rec: ScheduleStore.ScheduleRecord) {
        val task = ScheduleStore.runNow(ctx, rec.id)
        toast(if (task != null) L.tasks.runNowStarted else L.local.bgActionNotAllowed)
        tick++
    }

    fun delete(rec: ScheduleStore.ScheduleRecord) {
        ScheduleStore.remove(ctx, rec.id)
        toast(L.tasks.deleted)
        tick++
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TaskTopBar(L.tasks.scheduleTitle, records.size, onBack)
        Text(
            L.tasks.scopeHint,
            fontSize = 11.sp,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(L.tasks.emptySchedule, fontSize = 13.sp, color = c.text3)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records, key = { it.id }) { rec ->
                    ScheduleCard(
                        rec = rec,
                        onEdit = { editTarget = rec },
                        onToggle = { enabled -> toggle(rec, enabled) },
                        onRunNow = { runNow(rec) },
                        onDelete = { deleteTarget = rec }
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    val editing = editTarget
    if (editing != null) {
        ScheduleEditDialog(
            rec = editing,
            onDismiss = { editTarget = null },
            onSave = { interval, daily, params ->
                val compact = runCatching { JSONObject(params).toString() }.getOrDefault(params)
                val updated = ScheduleStore.update(
                    ctx, editing.id,
                    paramsJson = compact,
                    intervalMinutes = interval,
                    dailyAt = daily
                )
                if (updated != null) {
                    toast(L.tasks.saved)
                    editTarget = null
                    tick++
                    true
                } else {
                    false
                }
            }
        )
    }

    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = c.bg,
            shape = RoundedCornerShape(18.dp),
            title = { Text(L.tasks.deleteTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1) },
            text = {
                Column {
                    Text(del.name, fontSize = 13.sp, color = c.text1)
                    Spacer(Modifier.height(4.dp))
                    Text(L.tasks.deleteText, fontSize = 12.5.sp, color = c.text2)
                }
            },
            confirmButton = {
                TextButton(onClick = { delete(del); deleteTarget = null }) {
                    Text(L.tasks.btnDelete, color = c.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(L.tasks.btnCancel, color = c.text2) }
            }
        )
    }
}

@Composable
private fun ScheduleCard(
    rec: ScheduleStore.ScheduleRecord,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(TaskCardShape)
            .background(c.bg2)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rec.name,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (rec.enabled) c.text1 else c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!rec.enabled) {
                Text(L.tasks.paused, fontSize = 10.5.sp, color = c.text3)
                Spacer(Modifier.width(6.dp))
            }
            CompactSwitch(checked = rec.enabled, onChange = onToggle)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            actionLabel(rec.action, L) + " · " + cycleText(rec, L),
            fontSize = 11.5.sp,
            color = c.text2
        )
        Spacer(Modifier.height(4.dp))
        val lastTask = if (rec.lastTaskId.isNotBlank()) BgTasks.byId(rec.lastTaskId) else null
        val lastText = when {
            lastTask != null -> statusLabel(lastTask.status, L) + " · " +
                (lastTask.result.ifBlank { lastTask.error }).take(48)
            rec.lastRunAt > 0L -> taskTime(rec.lastRunAt)
            else -> L.tasks.neverRun
        }
        Text(
            L.tasks.labelNextRun + " " + taskTime(rec.nextAt) +
                "   " + L.tasks.labelLastRun + " " + lastText,
            fontSize = 11.sp,
            color = c.text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TaskAction(L.tasks.btnEdit, c.brand, onEdit)
            Spacer(Modifier.width(4.dp))
            TaskAction(L.tasks.btnRunNow, c.brand, onRunNow)
            Spacer(Modifier.weight(1f))
            TaskAction(L.tasks.btnDelete, c.danger, onDelete)
        }
    }
}

private fun cycleText(rec: ScheduleStore.ScheduleRecord, L: Str): String =
    if (rec.dailyAt.isNotBlank()) {
        L.format(L.tasks.cycleDaily, rec.dailyAt)
    } else {
        L.format(L.tasks.cycleEvery, rec.intervalMinutes)
    }

@Composable
private fun ScheduleEditDialog(
    rec: ScheduleStore.ScheduleRecord,
    onDismiss: () -> Unit,
    onSave: (intervalMinutes: Int?, dailyAt: String, params: String) -> Boolean
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    var intervalText by remember(rec.id) {
        mutableStateOf(if (rec.dailyAt.isBlank()) rec.intervalMinutes.toString() else "")
    }
    var dailyText by remember(rec.id) { mutableStateOf(rec.dailyAt) }
    var paramsText by remember(rec.id) { mutableStateOf(prettyJson(rec.paramsJson)) }
    var error by remember(rec.id) { mutableStateOf<String?>(null) }

    fun save() {
        val params = paramsText.trim().ifBlank { "{}" }
        if (runCatching { JSONObject(params) }.isFailure) {
            error = L.tasks.invalidJson
            return
        }
        val daily = dailyText.trim()
        val interval = intervalText.trim()
        if (daily.isBlank()) {
            val minutes = interval.toIntOrNull()
            if (minutes == null || minutes < ScheduleStore.MIN_INTERVAL_MINUTES) {
                error = L.format(L.local.scheduleMinInterval, ScheduleStore.MIN_INTERVAL_MINUTES)
                return
            }
            error = null
            if (!onSave(minutes, "", params)) error = L.local.scheduleNeedInterval
        } else {
            if (!daily.matches(Regex("^\\d{2}:\\d{2}$"))) {
                error = L.local.scheduleBadDailyAt
                return
            }
            error = null
            if (!onSave(null, daily, params)) error = L.local.scheduleNeedInterval
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        shape = RoundedCornerShape(18.dp),
        title = {
            Column {
                Text(L.tasks.editTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
                Spacer(Modifier.height(3.dp))
                Text(rec.name, fontSize = 11.5.sp, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FieldLabel(L.tasks.fieldInterval)
                DialogField(intervalText, singleLine = true) { intervalText = it }
                Spacer(Modifier.height(8.dp))
                FieldLabel(L.tasks.fieldDailyAt)
                DialogField(dailyText, singleLine = true) { dailyText = it }
                Spacer(Modifier.height(8.dp))
                FieldLabel(L.tasks.fieldParams)
                DialogField(paramsText, singleLine = false) { paramsText = it }
                val err = error
                if (err != null) {
                    Text(err, fontSize = 11.5.sp, color = c.danger, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) {
                Text(L.tasks.btnSave, color = c.brand, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(L.tasks.btnCancel, color = c.text2) }
        }
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        color = LocalColors.current.text3,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun DialogField(
    value: String,
    singleLine: Boolean,
    onChange: (String) -> Unit
) {
    val c = LocalColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 38.dp else 110.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(c.bg3)
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = 13.sp, color = c.text1),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
