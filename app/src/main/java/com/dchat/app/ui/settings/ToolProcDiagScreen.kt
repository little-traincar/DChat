package com.dchat.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.tools.ProcInfo
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.ui.lang.AppLanguage
import com.dchat.app.ui.lang.Lang

private object ProcDiagText {
    fun title(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "Python 进程诊断"
        AppLanguage.EN -> "Python process diagnostics"
        AppLanguage.JA -> "Python プロセス診断"
        AppLanguage.KO -> "Python 프로세스 진단"
    }
    fun refresh(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "刷新"
        AppLanguage.EN -> "Refresh"
        AppLanguage.JA -> "更新"
        AppLanguage.KO -> "새로 고침"
    }
    fun restart(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "重启"
        AppLanguage.EN -> "Restart"
        AppLanguage.JA -> "再起動"
        AppLanguage.KO -> "재시작"
    }
    fun close(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "关闭"
        AppLanguage.EN -> "Close"
        AppLanguage.JA -> "閉じる"
        AppLanguage.KO -> "닫기"
    }
    fun empty(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "暂无子进程"
        AppLanguage.EN -> "No child processes"
        AppLanguage.JA -> "子プロセスはありません"
        AppLanguage.KO -> "하위 프로세스 없음"
    }
    fun restartAll(l: AppLanguage) = when (l) {
        AppLanguage.ZH -> "全部重启"
        AppLanguage.EN -> "Restart all"
        AppLanguage.JA -> "すべて再起動"
        AppLanguage.KO -> "모두 재시작"
    }
}

fun toolProcDiagTitle(language: AppLanguage): String = ProcDiagText.title(language)

@Composable
fun ToolProcDiagDialog(onDismiss: () -> Unit) {
    val l = Lang.current.language
    var rows by remember { mutableStateOf(PyRuntime.toolProcSnapshot()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ProcDiagText.title(l)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (rows.isEmpty()) Text(ProcDiagText.empty(l), fontSize = 12.sp)
                rows.forEach { p: ProcInfo ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.kind} · ${p.toolId}", fontSize = 12.sp)
                            Text(
                                "pid=${p.pid} rss=${p.rssKb}KB ${p.state} crash=${p.crashCount}" +
                                    (p.backoffUntil.takeIf { it > 0 }?.let { " backoff=${it}ms" } ?: "") +
                                    (p.lastError.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                fontSize = 10.sp
                            )
                        }
                        TextButton(onClick = {
                            PyRuntime.restartToolProcess(p.toolId)
                            rows = PyRuntime.toolProcSnapshot()
                        }) { Text(ProcDiagText.restart(l), fontSize = 11.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { rows = PyRuntime.toolProcSnapshot() }) {
                Text(ProcDiagText.refresh(l))
            }
            TextButton(onClick = {
                PyRuntime.restartAllToolProcesses()
                rows = PyRuntime.toolProcSnapshot()
            }) { Text(ProcDiagText.restartAll(l)) }
            TextButton(onClick = onDismiss) { Text(ProcDiagText.close(l)) }
        }
    )
}
