package com.dchat.app.ui.files

import android.content.Intent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dchat.app.data.tools.FileTools
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.chat.ImageViewer
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.theme.LocalColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「当前会话的文件」页面。
 *
 * - **只列**当前会话工作区 `filesDir/workspaces/<convId>/` 的递归文件（不列 shared/）；
 * - 查看：图片 → 全屏 ImageViewer；文本（md/txt/json/log/csv…）→ 内置文本查看；其它 → 系统打开/分享；
 * - 导出：复用 [FileTools.exportFile] → MediaStore「下载/DChat」（与 fs-export 同一段工具函数）；
 * - 删除：二次确认（危险色）；
 * - 页面只接收当前会话 id，没有任何「切换会话」入口（严格隔离）。
 */
@Composable
fun SessionFilesScreen(
    conversationId: String?,
    onBack: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current

    var tick by remember { mutableStateOf(0) }
    var imagePreview by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var textPreview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    val root = remember(conversationId) { conversationId?.let { FileTools.workspaceDir(ctx, it) } }
    val files = remember(tick, conversationId, root) {
        val base = root
        if (base == null || !base.isDirectory) {
            emptyList()
        } else {
            base.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(base).path }
                .toList()
        }
    }

    fun toast(msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun relPath(f: File): String =
        runCatching { f.relativeTo(root!!).path }.getOrDefault(f.name)

    fun openSystem(f: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            val mime = mimeOf(f.name)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (view.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(view)
            } else {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(send, f.name))
            }
        }.onFailure {
            toast(L.format(L.files.openFailed, it.message ?: it.javaClass.simpleName))
        }
    }

    fun viewFile(f: File) {
        when (f.extension.lowercase(Locale.ROOT)) {
            in IMAGE_EXTS -> {
                // 与聊天图片一致：传入当前会话列表里的全部图片 + 被点那张的索引，
                // 全屏 ImageViewer 里即可缩放 + 左右滑动切换（严格限定当前会话列表）
                val imageFiles = files
                    .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXTS }
                    .map { it.absolutePath }
                val index = imageFiles.indexOf(f.absolutePath)
                imagePreview = if (index >= 0) {
                    imageFiles to index
                } else {
                    listOf(f.absolutePath) to 0
                }
            }
            in TEXT_EXTS -> runCatching { f.readText() }.fold(
                onSuccess = { textPreview = relPath(f) to it },
                onFailure = { toast(L.format(L.files.openFailed, it.message ?: "")) }
            )
            else -> openSystem(f)
        }
    }

    fun exportFile(f: File) {
        when (val r = FileTools.exportFile(ctx, f)) {
            is FileTools.ExportOutcome.Ok ->
                toast(L.format(L.files.exportSuccess, FileTools.EXPORT_DIR_NAME + "/" + r.displayName))
            is FileTools.ExportOutcome.Unsupported -> toast(L.files.exportUnsupported)
            is FileTools.ExportOutcome.Failed -> toast(L.format(L.files.exportFailed, r.detail))
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // 顶栏：返回 + 标题 + 当前文件数
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
            Text(L.files.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
            Spacer(Modifier.weight(1f))
            Text(
                files.size.toString(),
                fontSize = 12.5.sp,
                color = c.text3,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
        Text(
            L.files.hint,
            fontSize = 11.sp,
            color = c.text3,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
        )
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(L.files.empty, fontSize = 13.sp, color = c.text3)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(files, key = { it.absolutePath }) { f ->
                    FileRow(
                        f = f,
                        relativePath = relPath(f),
                        onView = { viewFile(f) },
                        onExport = { exportFile(f) },
                        onDelete = { deleteTarget = f }
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    // 图片全屏（复用聊天页 ImageViewer）
    val preview = imagePreview
    if (preview != null) {
        ImageViewer(
            images = preview.first,
            initialIndex = preview.second,
            onDismiss = { imagePreview = null }
        )
    }

    // 文本内置查看器
    val text = textPreview
    if (text != null) {
        AlertDialog(
            onDismissRequest = { textPreview = null },
            containerColor = c.bg,
            shape = RoundedCornerShape(18.dp),
            title = {
                Text(text.first, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Box(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text.second,
                        fontSize = 12.sp,
                        color = c.text1,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { textPreview = null }) {
                    Text(L.files.btnClose, color = c.brand, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    // 删除二次确认
    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = c.bg,
            shape = RoundedCornerShape(18.dp),
            title = { Text(L.files.deleteTitle, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1) },
            text = {
                Column {
                    Text(relPath(del), fontSize = 13.sp, color = c.text1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(L.files.deleteText, fontSize = 12.5.sp, color = c.text2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = runCatching { del.delete() }.getOrDefault(false)
                    deleteTarget = null
                    if (ok) {
                        toast(L.files.deleteDone)
                        tick++
                    } else {
                        toast(L.format(L.files.openFailed, del.name))
                    }
                }) {
                    Text(L.files.btnDelete, color = c.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(L.files.btnCancel, color = c.text2)
                }
            }
        )
    }
}

@Composable
private fun FileRow(
    f: File,
    relativePath: String,
    onView: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val icon: ImageVector = when (f.extension.lowercase(Locale.ROOT)) {
        in IMAGE_EXTS -> Icons.Rounded.Image
        in TEXT_EXTS -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.bg2)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.text2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    relativePath,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.text1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    formatSize(f.length()) + " · " + formatTime(f.lastModified()),
                    fontSize = 11.sp,
                    color = c.text3
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onView() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) { Text(L.files.btnView, fontSize = 12.sp, color = c.brand, fontWeight = FontWeight.Medium) }
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onExport() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) { Text(L.files.btnExport, fontSize = 12.sp, color = c.brand, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onDelete() }
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) { Text(L.files.btnDelete, fontSize = 12.sp, color = c.danger, fontWeight = FontWeight.Medium) }
        }
    }
}

private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

private val TEXT_EXTS = setOf(
    "md", "markdown", "txt", "json", "log", "csv", "tsv", "xml", "html", "htm",
    "yaml", "yml", "ini", "conf", "properties", "kt", "java", "py", "js", "ts",
    "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift", "sql", "sh", "bat",
    "gradle", "kts", "toml", "srt", "vtt"
)

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "pdf" -> "application/pdf"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "txt", "log" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(ms: Long): String =
    runCatching { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms)) }.getOrDefault("--")
