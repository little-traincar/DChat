package com.dchat.app.ui.tasks

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.AppState
import com.dchat.app.data.tools.BgTasks
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.theme.LocalColors
import kotlinx.coroutines.delay

/**
 * 后台任务页（会话页「⋮」→ 后台任务）。
 *
 * - 只显示**当前会话**创建的任务（[BgTasks.listFor]）；
 * - 点卡片看参数 + 完整结果；
 * - 运行中/排队中可取消；已结束可删除记录、重跑。
 */
@Composable
fun BgTaskScreen(
    app: AppState,
    conversationId: String?,
    onBack: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current

    var tick by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<BgTasks.TaskRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<BgTasks.TaskRecord?>(null) }

    // 任务在后台线程完成，列表页打开期间轮询刷新（数据量小，1.5s 读一次内存快照）
    LaunchedEffect(conversationId) {
        while (true) {
            delay(1500)
            tick++
        }
    }
    val records = remember(tick, conversationId) {
        conversationId?.let { BgTasks.listFor(it) } ?: emptyList()
    }

    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }

    fun cancelTask(rec: BgTasks.TaskRecord) {
        BgTasks.cancel(rec.id)
        toast(L.tasks.cancelled)
        tick++
    }

    fun deleteTask(rec: BgTasks.TaskRecord) {
        BgTasks.remove(rec.id)
        toast(L.tasks.deleted)
        tick++
    }

    fun rerunTask(rec: BgTasks.TaskRecord) {
        val created = BgTasks.rerun(ctx, rec.id)
        toast(if (created != null) L.tasks.rerunStarted else L.local.bgActionNotAllowed)
        tick++
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        TaskTopBar(L.tasks.bgTitle, records.size, onBack)
        Text(
            L.tasks.scopeHint,
            fontSize = 11.sp,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(L.tasks.emptyBg, fontSize = 13.sp, color = c.text3)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records, key = { it.id }) { rec ->
                    BgTaskCard(
                        rec = rec,
                        onClick = { selected = rec },
                        onCancel = { cancelTask(rec) },
                        onDelete = { deleteTarget = rec },
                        onRerun = { rerunTask(rec) }
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    val detail = selected
    if (detail != null) {
        BgTaskDetailDialog(
            rec = detail,
            onClose = { selected = null },
            onCancel = { cancelTask(detail); selected = null },
            onRerun = { rerunTask(detail); selected = null }
        )
    }

    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = c.bg,
            shape = RoundedCornerShape(18.dp),
            title = { Text(L.tasks.deleteTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1) },
            text = { Text(L.tasks.deleteText, fontSize = 13.sp, color = c.text2) },
            confirmButton = {
                TextButton(onClick = { deleteTask(del); deleteTarget = null }) {
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
private fun BgTaskCard(
    rec: BgTasks.TaskRecord,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onRerun: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(TaskCardShape)
            .background(c.bg2)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(rec.status)
            Spacer(Modifier.width(8.dp))
            Text(
                actionLabel(rec.action, L),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(taskTime(rec.createdAt), fontSize = 10.5.sp, color = c.text3)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            rec.paramsJson.take(180),
            fontSize = 11.5.sp,
            color = c.text2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                L.tasks.labelDuration + " " + taskDuration(rec.startedAt, rec.finishedAt),
                fontSize = 11.sp,
                color = c.text3
            )
            Spacer(Modifier.weight(1f))
            if (isActive(rec.status)) {
                TaskAction(L.tasks.btnCancel, c.danger, onCancel)
            } else {
                TaskAction(L.tasks.btnRerun, c.brand, onRerun)
                TaskAction(L.tasks.btnDelete, c.danger, onDelete)
            }
        }
        if (rec.status == BgTasks.STATUS_FAILED && rec.error.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(rec.error.take(160), fontSize = 11.sp, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else if (rec.status == BgTasks.STATUS_DONE && rec.result.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(rec.result.take(160), fontSize = 11.sp, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BgTaskDetailDialog(
    rec: BgTasks.TaskRecord,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onRerun: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.bg,
        shape = RoundedCornerShape(18.dp),
        title = {
            Column {
                Text(L.tasks.detailTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(rec.status)
                    Spacer(Modifier.width(6.dp))
                    Text(actionLabel(rec.action, L), fontSize = 12.sp, color = c.text2)
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DetailLine(L.tasks.labelStatus, statusLabel(rec.status, L))
                DetailLine(L.tasks.labelCreated, taskTime(rec.createdAt))
                DetailLine(L.tasks.labelDuration, taskDuration(rec.startedAt, rec.finishedAt))
                DetailLine("ID", rec.id)
                DetailBlock(L.tasks.labelParams, prettyJson(rec.paramsJson))
                if (rec.result.isNotBlank()) DetailBlock(L.tasks.labelResult, rec.result)
                if (rec.error.isNotBlank()) DetailBlock(L.tasks.labelError, rec.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(L.tasks.btnClose, color = c.brand, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            if (isActive(rec.status)) {
                TextButton(onClick = onCancel) { Text(L.tasks.btnCancel, color = c.danger) }
            } else {
                TextButton(onClick = onRerun) { Text(L.tasks.btnRerun, color = c.brand) }
            }
        }
    )
}
