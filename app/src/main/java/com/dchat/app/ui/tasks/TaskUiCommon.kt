package com.dchat.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.tools.BgTasks
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.Str
import com.dchat.app.ui.theme.LocalColors
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 任务页统一卡片圆角（和设置页/工具页一致） */
internal val TaskCardShape = RoundedCornerShape(22.dp)

internal const val TASK_SUCCESS = 0xFF2FB344

/** 列表里的时间戳：MM-dd HH:mm */
internal fun taskTime(ms: Long): String {
    if (ms <= 0L) return "--"
    return runCatching {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("--")
}

/** 后台任务耗时；运行中按当前时刻算 */
internal fun taskDuration(startedAt: Long, finishedAt: Long): String {
    if (startedAt <= 0L) return "--"
    val end = if (finishedAt > 0L) finishedAt else System.currentTimeMillis()
    val ms = (end - startedAt).coerceAtLeast(0L)
    return when {
        ms < 1000L -> "${ms}ms"
        ms < 60_000L -> String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
        else -> "${ms / 60_000L}m ${ms % 60_000L / 1000L}s"
    }
}

/** 参数 JSON 美化；解析失败原样返回（不隐藏信息） */
internal fun prettyJson(raw: String): String =
    runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw.ifBlank { "{}" })

/** 受控动作的界面名称 */
internal fun actionLabel(action: String, L: Str): String = when (action) {
    BgTasks.ACTION_RUN_USER_TOOL -> L.tasks.acRunUserTool
    BgTasks.ACTION_HTTP -> L.tasks.acHttp
    BgTasks.ACTION_NOTIFY -> L.tasks.acNotify
    BgTasks.ACTION_PYTHON -> L.tasks.acPython
    else -> action.ifBlank { L.tasks.acUnknown }
}

/** 后台任务状态名 */
internal fun statusLabel(status: String, L: Str): String = when (status) {
    BgTasks.STATUS_RUNNING -> L.tasks.stRunning
    BgTasks.STATUS_QUEUED -> L.tasks.stQueued
    BgTasks.STATUS_DONE -> L.tasks.stDone
    BgTasks.STATUS_FAILED -> L.tasks.stFailed
    BgTasks.STATUS_CANCELLED -> L.tasks.stCancelled
    else -> status
}

internal fun isActive(status: String): Boolean =
    status == BgTasks.STATUS_RUNNING || status == BgTasks.STATUS_QUEUED

/** 两个任务页共用的顶栏：返回 + 标题 + 右侧本会话数量 */
@Composable
internal fun TaskTopBar(title: String, count: Int, onBack: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(46.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(Icons.Rounded.ArrowBackIosNew, onBack, iconSize = 18.dp)
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
        Spacer(Modifier.weight(1f))
        Text(
            count.toString(),
            fontSize = 12.5.sp,
            color = c.text3,
            modifier = Modifier.padding(end = 16.dp)
        )
    }
}

/** 状态胶囊：RUNNING/QUEUED/DONE/FAILED/CANCELLED */
@Composable
internal fun StatusChip(status: String) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val fg = when (status) {
        BgTasks.STATUS_RUNNING -> c.brand
        BgTasks.STATUS_DONE -> Color(TASK_SUCCESS)
        BgTasks.STATUS_FAILED -> c.danger
        else -> c.text3
    }
    val bg = when (status) {
        BgTasks.STATUS_RUNNING -> c.brand.copy(alpha = 0.12f)
        BgTasks.STATUS_DONE -> Color(TASK_SUCCESS).copy(alpha = 0.13f)
        BgTasks.STATUS_FAILED -> c.danger.copy(alpha = 0.12f)
        else -> c.bg
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(statusLabel(status, L), fontSize = 10.5.sp, color = fg, fontWeight = FontWeight.Medium)
    }
}

/** 卡片底部的文字按钮（取消/删除/重跑/编辑等） */
@Composable
internal fun TaskAction(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

/** 详情里的「字段名：值」一行 */
@Composable
internal fun DetailLine(label: String, value: String) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 12.sp, color = c.text3, modifier = Modifier.width(72.dp))
        Text(value, fontSize = 12.sp, color = c.text1)
    }
}

/** 详情里的大段文本块（参数 / 结果） */
@Composable
internal fun DetailBlock(title: String, text: String) {
    val c = LocalColors.current
    Text(
        title,
        fontSize = 11.5.sp,
        color = c.text3,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg3)
            .padding(10.dp)
    ) {
        Text(text.ifBlank { "--" }, fontSize = 11.5.sp, color = c.text1, lineHeight = 16.sp)
    }
}
