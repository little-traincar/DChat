package com.dchat.app.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Square
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dchat.app.data.AppState
import com.dchat.app.data.ChatMsg
import com.dchat.app.data.Conversation
import com.dchat.app.data.DeepSeekZip
import com.dchat.app.data.Source
import com.dchat.app.data.Step
import com.dchat.app.data.api.ApiEvent
import com.dchat.app.data.api.ChatImage
import com.dchat.app.data.api.ChatTurn
import com.dchat.app.data.api.DeepSeekApi
import com.dchat.app.data.api.EncodedChatImage
import com.dchat.app.data.tools.BgTasks
import com.dchat.app.data.tools.FileTools
import com.dchat.app.data.tools.LocalToolRunner
import com.dchat.app.data.tools.PyFailure
import com.dchat.app.data.tools.PyFile
import com.dchat.app.data.tools.PyResult
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.data.tools.ToolApproval
import com.dchat.app.data.tools.ToolApprovalPolicy
import com.dchat.app.data.tools.ToolApprovalRequest
import com.dchat.app.data.api.CacheEpochNotice
import com.dchat.app.data.chat.ChatRuntime
import com.dchat.app.data.tools.ToolHealth
import com.dchat.app.data.tools.ToolRegistry
import com.dchat.app.data.tools.ToolSource
import com.dchat.app.data.tools.ScheduleStore
import com.dchat.app.data.tools.UserToolStore
import com.dchat.app.ui.Avatar
import com.dchat.app.ui.ChipToggle
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.file.FileText
import com.dchat.app.ui.lang.LatestReminder
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.Str
import com.dchat.app.ui.lang.balanceDash
import com.dchat.app.ui.lang.conversationTitle
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.lang.isDefaultChatTitle
import com.dchat.app.ui.lang.money
import com.dchat.app.ui.theme.LocalColors
import com.dchat.app.ui.theme.brandGradient
import com.dchat.app.ui.theme.defaultWall
import com.dchat.app.ui.voice.AsrEngine
import com.dchat.app.ui.voice.AsrStatus
import com.dchat.app.ui.voice.TtsEngine
import com.dchat.app.ui.voice.VoicePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ChatScreen(
    app: AppState,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenBgTasks: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    onOpenFiles: (String) -> Unit = {},
    /** 过渡兼容：旧 MainActivity 只有无参入口 */
    onOpenSessionFiles: () -> Unit = {}
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val s = app.settings
    val messages = app.current()?.messages ?: emptyList()
    val hasWall = s.bgImage != null

    var input by remember { mutableStateOf("") }
    // 工具开关统一读写全局注册表（Compose 可观察）：会话页 chip 和设置页工具页是同一份状态    // 过渡期旧 prefs 镜像ToolRegistry 内部负责，这里不再直接改 AppSettings
val toolSpecs = ToolRegistry.tools
    fun toolEnabled(id: String): Boolean = toolSpecs.firstOrNull { it.id == id }?.enabled == true
    val thinking = toolEnabled("server.thinking")
    // 1.0.3：搜索/阅读改为客户端工具 local.web_search / local.web_fetch（服务端内置工具已淘汰）
    val search = toolEnabled("local.web_search")
    val ttsEnabled = toolEnabled("server.tts")
    val ocrEnabled = toolEnabled("server.ocr")
    fun setThinking(v: Boolean) = ToolRegistry.setEnabled("server.thinking", v)
    fun setSearch(v: Boolean) = ToolRegistry.setEnabled("local.web_search", v)
    fun setTts(v: Boolean) = ToolRegistry.setEnabled("server.tts", v)
    var voice by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // 顶栏盾牌：本会话权限浮层
    var permMenu by remember { mutableStateOf(false) }
    // 抽屉行「⋯」菜单选中的目标会话：重命名/删除都作用于它，不一定是当前会话
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    // 单会话导出：点「导出这个会话」时记录当前会话快照；文件选择期间切换会话也不会导错
var exportTarget by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    // 1.0.3：生成任务挂在进程级 ChatRuntime 上，离开聊天页不会被取消（见 ChatRuntime 注释）
    // 「本轮生成」标识：流式回调、工具循环都是新起的协程，不能靠 job.isActive 判断
    // （外层 launch 只是把 OkHttp 请求 enqueue 出去就结束了，job 立刻变 inactive）。
    // 只有用户点停止 / 新发一轮时才把 token 换掉，工具循环据此判断是否还该继续。
    // 1.0.3：生图占位卡的进度起点（elapsedRealtime 毫秒，0 = 当前没有进行中的生图）
    var imageGenStartedAt by remember { mutableStateOf(ChatRuntime.imageGenStartedAt) }
    // D97：本轮工具链的实时快照。停止生成时用它补齐「assistant 有 tool_use 但没有配对 tool_result」
    // 的悬空调用（否则下一轮请求 400）；正常结束时它也会清洗后落库给后续轮次。
    val liveRawChain = remember { mutableStateListOf<JSONObject>() }
    var recording by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    // 全屏看图：本次消息的全部图 + 当前点击的索引；null = 查看器关闭
var imageViewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 1.0.3：按会话记忆滚动位置（内存级，App 重启不保留）
    val scrollMemory = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    var scrollRestored by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }

    // P3-A：中途切换请求级开关只提示缓存重建，不阻塞、不回滚
    LaunchedEffect(Unit) {
        CacheEpochNotice.message.collect { msg ->
            if (msg != null) {
                toast(msg)
                CacheEpochNotice.clear()
            }
        }
    }

    // ---- 第三期：会话权限 / 工具入口校验 ----
    fun currentPermission(): String = Conversation.normalizePermission(app.current()?.permission)
    fun permissionLabel(mode: String): String = when (Conversation.normalizePermission(mode)) {
        Conversation.PERMISSION_READ -> L.chat.permRead
        Conversation.PERMISSION_FULL -> L.chat.permFull
        else -> L.chat.permWorkspace
    }

    /** 工具关闭 → 提示「已关闭」；会话权限不足 → 提示「权限不足」；不能执行时返回 false */
    fun ensureToolUsable(toolId: String, offMessage: String): Boolean {
        if (!toolEnabled(toolId)) {
            toast(offMessage)
            return false
        }
        val mode = currentPermission()
        if (!ToolRegistry.canExecute(toolId, mode)) {
            toast(L.format(L.chat.permDenied, permissionLabel(mode)))
            return false
        }
        // 第三期熔断：同一会话内连续失败 3 次的工具暂停
        val cid = app.currentId
        if (cid != null && ToolHealth.isPaused(cid, toolId)) {
            val sec = ToolHealth.retryInSeconds(cid, toolId)
            toast(if (sec > 0) L.format(L.chat.toolPausedRetry, sec) else L.chat.toolPaused)
            return false
        }
        return true
    }

    // 待下一次 AI 回复展示的客户端工具调用步骤（文件解析发生在本轮发送之前，先攒着）
val pendingToolSteps = remember { mutableStateListOf<Step>() }
    // 待下一次发给模型的客户端工具错误说明（拒绝/超时/轮次上限；以文本形式回给模型）
val pendingToolNotices = remember { mutableStateListOf<String>() }
    // 客户端主动发起的工具调用数（文件解析 + OCR），随下一次发送一起计入 toolRounds
    var pendingClientToolCalls by remember { mutableStateOf(0) }

    fun currentConversationId(): String? = app.currentId

    /** 熔断提示：带剩余冷却秒数与设置页恢复入口 */
    fun toolPausedText(toolId: String): String {
        val cid = currentConversationId() ?: return L.chat.toolPaused
        val sec = ToolHealth.retryInSeconds(cid, toolId)
        return if (sec > 0) L.format(L.chat.toolPausedRetry, sec) else L.chat.toolPaused
    }

    /** 第三期熔断：同一会话内连续失败 3 次的工具暂停 */
    fun toolHealthBlocked(toolId: String): Boolean {
        val cid = currentConversationId() ?: return false
        if (ToolHealth.isPaused(cid, toolId)) {
            toast(toolPausedText(toolId))
            return true
        }
        return false
    }

    fun noteToolSuccess(toolId: String) {
        currentConversationId()?.let { ToolHealth.recordSuccess(it, toolId) }
    }

    fun noteToolFailure(toolId: String) {
        val cid = currentConversationId() ?: return
        if (ToolHealth.recordFailure(cid, toolId)) toast(toolPausedText(toolId))
    }

    /** 客户端工具轮次：0 = 无上限 */
    fun clientRoundLimitReached(): Boolean {
        val limit = app.settings.toolRounds
        return limit > 0 && pendingClientToolCalls >= limit
    }

    /** 把客户端工具失败/拒绝以「工具错误」形式回给模型（下次请求带上*/
    fun recordToolErrorForModel(toolId: String, toolName: String, args: String, code: String, message: String) {
        pendingToolSteps.add(
            Step.ToolCall(
                toolId = toolId,
                toolName = toolName,
                args = args,
                error = code + ": " + message,
                durationMs = 0L
            )
        )
        pendingToolNotices.add(L.format(L.chat.modelToolRejected, toolName, code))
    }

    // 打开抽屉 / 面板前先收起输入法，否则键盘会把抽屉挤成半截
    fun closeKeyboard() {
        runCatching {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // ---- 待发送附件：相册多/ 拍照 输入框上方缩略图；定确认后直接发位置消息 ----
    val pendingImages = remember { mutableStateListOf<String>() }
    // 待发送的服务端文件解析结果（Files API file_id）：发送时document 内容块引用，
    // 文本类小文件仍走本地读取，不会进这里
val pendingDocs = remember { mutableStateListOf<FileText.ServerDoc>() }
    var cameraTempFile by remember { mutableStateOf<File?>(null) }
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var pendingLocation by remember { mutableStateOf<GeoPlace?>(null) }
    var showLocationConfirm by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }

    fun locateNow() {
        if (locating) return
        locating = true
        Toast.makeText(ctx, L.chat.toastLocating, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = LocationPicker.locate(ctx)
            locating = false
            result.fold(
                onSuccess = {
                    noteToolSuccess("local.location")
                    pendingLocation = it
                    showLocationConfirm = true
                },
                onFailure = {
                    noteToolFailure("local.location")
                    toast(it.message ?: L.misc.locFailed)
                }
            )
        }
    }

    // 相册：系统图片选择器（最9 张），选完先落+ 预览，不立刻发
val albumPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val room = (9 - pendingImages.size).coerceAtLeast(0)
            if (room == 0) {
                toast(L.chat.toastMaxImages)
                return@launch
            }
            val picked = uris.take(room)
            val paths = withContext(Dispatchers.IO) {
                picked.mapNotNull { uri -> app.importChatImage(uri) }
            }
            if (paths.isEmpty()) {
                noteToolFailure("local.gallery")
                toast(L.chat.toastImageReadFailed)
            } else {
                noteToolSuccess("local.gallery")
                pendingImages.addAll(paths)
                if (uris.size > room) toast(L.format(L.chat.toastImagesPartial, room))
            }
        }
    }

    // 拍照：系统相机写一份临时原图到 cacheDir/camera，拍完再压缩chat_images 并进预览
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = cameraTempFile
        val uri = cameraTempUri
        cameraTempFile = null
        cameraTempUri = null
        if (!ok || file == null || uri == null) {
            file?.delete()
            // 用户取消拍照不算失败；其它异常由下面的保存失败路径记
return@rememberLauncherForActivityResult
        }
        scope.launch {
            val path = withContext(Dispatchers.IO) { app.importChatImage(uri) }
            withContext(Dispatchers.IO) { file.delete() }
            if (path == null) {
                noteToolFailure("local.camera")
                toast(L.chat.toastPhotoSaveFailed)
            } else {
                noteToolSuccess("local.camera")
                if (pendingImages.size >= 9) {
                    withContext(Dispatchers.IO) { File(path).delete() }
                    toast(L.chat.toastMaxImages)
                } else {
                    pendingImages.add(path)
                }
            }
        }
    }

    fun launchCameraApp() {
        // 系统相机 App 拍照不需CAMERA 权限；没有相机硬相机 App 时只 Toast，不
if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            noteToolFailure("local.camera")
            toast(L.chat.toastNoCamera)
            return
        }
        try {
            val dir = File(ctx.cacheDir, "camera")
            if (!dir.exists() && !dir.mkdirs()) {
                noteToolFailure("local.camera")
                toast(L.chat.toastCameraTempFailed)
                return
            }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            cameraTempFile = file
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            noteToolFailure("local.camera")
            cameraTempFile?.delete()
            cameraTempFile = null
            cameraTempUri = null
            toast(L.chat.toastNoCamera)
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            locateNow()
        } else {
            toast(L.chat.toastNeedLocation)
        }
    }

    fun requestLocation() {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            locateNow()
        } else {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 导出这个会话：系统「创建文档」选择只把当前会话写成 DeepSeek v3 ZIP
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 进程被回收重建时 exportTarget 可能已丢，兜底用当前会话，避免写0 字节文件
        val snapshot = exportTarget.ifEmpty { app.current()?.let { listOf(it) } ?: emptyList() }
        exportTarget = emptyList()
        if (snapshot.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DeepSeekZip.exportTo(ctx, uri, snapshot)
            }
            toast(
                result.fold(
                    onSuccess = { L.format(L.chat.toastExported, it) },
                    onFailure = { L.format(L.chat.toastExportFailed, it.message ?: L.common.unknownError) }
                )
            )
        }
    }

    // 导入：选择 ZIP 解析后追加为新会话（不覆盖已有会话）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                DeepSeekZip.importFrom(ctx, uri)
            }
            result.fold(
                onSuccess = { list ->
                    if (list.isEmpty()) {
                        toast(L.chat.toastImportEmpty)
                    } else {
                        val added = app.addConversations(list)
                        toast(L.format(L.chat.toastImported, added.size))
                    }
                },
                onFailure = { toast(L.format(L.chat.toastImportFailed, it.message ?: L.common.unknownError)) }
            )
        }
    }
    // ---- 语音识别（DeepSeek ASR：AudioRecord 16kHz WAV 松手后上传转写）----
    // 引擎每次 start 时现读设置，设置页改API Key / BaseUrl 不用重建引擎
    val asr = remember {
        AsrEngine(
            ctx,
            baseUrl = { app.settings.baseUrl },
            apiKey = { app.settings.apiKey }
        )
    }
    var voicePartial by remember { mutableStateOf<String?>(null) }
    // 手指是否仍按在「按住说话」上：权限弹窗会让手势结束，授权回调靠它判断要不要立刻开
var voicePressed by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 只有用户仍按住才开录：避免「松手后还在录」和同一次按压启动两
if (voicePressed) {
                voicePartial = null
                asr.start()
            } else {
                toast(L.chat.toastMicOn)
            }
        } else {
            voicePressed = false
            recording = false
            toast(L.chat.toastNeedMic)
        }
    }
    asr.onPartial = { voicePartial = it }
    asr.onFinal = { text ->
        voicePartial = null
        input = if (input.isBlank()) text else input + text
    }
    asr.onStatus = { status, msg ->
        when (status) {
            AsrStatus.Transcribing -> voicePartial = L.chat.asrTranscribing
            AsrStatus.Error -> {
                voicePartial = null
                msg?.let { toast(it) }
            }
            AsrStatus.Listening, AsrStatus.Idle -> voicePartial = null
        }
    }

    // ---- 语音合成（DeepSeek 官方 TTS HTTP 接口 QQ 式语音消息播放器 ----
    val tts = remember { TtsEngine(ctx) }
    val player = remember { VoicePlayer() }
    var speakingId by remember { mutableStateOf<Long?>(null) }       // 正在播放的消id
    var playProgress by remember { mutableStateOf(0f) }             // 播放进度 0..1
    var synthesizingId by remember { mutableStateOf<Long?>(null) }   // 正在合成的消id
    var autoSpokenId by remember { mutableStateOf<Long?>(null) }
    // 只有"这次自己发出的那回答完才自动合成+播放：打开 App / 切换会话时不要突然读旧消
var autoSpeakId by remember { mutableStateOf<Long?>(null) }
    val durations = remember { mutableStateMapOf<Long, Int>() }
    val voiceFailed = remember { mutableStateMapOf<Long, Boolean>() }

    fun speakableText(m: ChatMsg): String =
        if (m.text.isNotBlank()) m.text
        else m.steps.filterIsInstance<Step.Text>().joinToString("") { it.text }

    /**
     * 给模型看的「文本部分」。图片不再拼「（用户附了 N 张图片）」这种占位说明：
     * 图片会由 DeepSeekApi 按官方图像理解文档用 image 内容块真实发给模型（send 里的图片编码）     * - 位置消息：转成「（用户发送了位置地址> <lat>,<lng>）」     * 气泡展示始终用干净m.text，这份只用于请求/ 历史上下文里的文本     */
    fun modelContent(m: ChatMsg): String {
        val parts = ArrayList<String>(2)
        if (m.text.isNotBlank()) parts.add(m.text)
        if (m.lat != null && m.lng != null) {
            val addr = m.place.ifBlank { m.placeDetail }.ifBlank { L.chat.locationUnknown }
            parts.add(
                L.format(
                    L.chat.modelLocationNote,
                    addr,
                    formatCoord(m.lat),
                    formatCoord(m.lng)
                )
            )
        }
        return parts.joinToString("\n")
    }

    /** A1：历史回放文本优先用发送时冻结的 modelText；旧消息没有则回退旧逻辑。 */
    fun replayModelText(m: ChatMsg): String =
        if (m.fromMe) m.modelText.ifBlank { modelContent(m) } else modelContent(m)

    /** DSH 口径：日期/地区/语言元信息并进 system，不再发 latest_reminder 角色。 */
    fun systemWithMeta(base: String): String {
        val meta = LatestReminder.content(L).trim()
        val b = base.trim()
        return if (b.isEmpty()) meta else if (meta.isEmpty()) b else b + "\n\n" + meta
    }

    fun stopVoice() {
        player.stop()
        speakingId = null
        playProgress = 0f
    }

    /** 播放已经合成好的 wav；文件不存在返回 false（由调用方决定是否按需合成*/
    fun playVoiceFile(m: ChatMsg): Boolean {
        val file = tts.fileFor(m.id)
        if (!file.exists() || file.length() <= 0L) return false
        speakingId = m.id
        playProgress = 0f
        if (!durations.containsKey(m.id)) {
            scope.launch {
                val d = withContext(Dispatchers.IO) { player.durationOf(file) }
                if (d > 0) durations[m.id] = d
            }
        }
        player.play(
            file,
            onProgress = { p -> if (speakingId == m.id) playProgress = p },
            onEnd = {
                if (speakingId == m.id) {
                    speakingId = null
                    playProgress = 0f
                }
            }
        )
        return true
    }

    /** 合成这条消息的语音；play=true 合成完直接播（手动点击），false 只准备不播放（自动生成语音消息用*/
    fun voiceMessage(m: ChatMsg, play: Boolean = true) {
        if (synthesizingId == m.id) return
        if (play && playVoiceFile(m)) return
        val body = speakableText(m)
        if (body.isBlank()) {
            toast(L.chat.noSpeakableText)
            return
        }
        synthesizingId = m.id
        voiceFailed.remove(m.id)
        if (!tts.isReady) {
            // 引擎还没初始化完（比如刚打开 App 就收到回复）：等它好再合
tts.init { ok ->
                synthesizingId = null
                if (ok) {
                    voiceMessage(m)
                } else {
                    voiceFailed[m.id] = true
                    toast(L.chat.ttsInitFailed)
                }
            }
            return
        }
        tts.synthesize(body, tts.fileFor(m.id)) { ok ->
            // 合成期间用户又点了别的语音：这次结果作废，别把播放抢回来
            if (synthesizingId != m.id) return@synthesize
            synthesizingId = null
            if (!ok) {
                voiceFailed[m.id] = true
                // 具体失败原因（鉴额度/内容审核/网络）已TtsEngine 内部 Toast，这里不再重复弹
            } else {
                // 手动点气泡播放不受「语音合成」开关影响：开关只管下一条回
voiceFailed.remove(m.id)
                scope.launch {
                    val d = withContext(Dispatchers.IO) { player.durationOf(tts.fileFor(m.id)) }
                    if (d > 0) durations[m.id] = d
                    if (play) playVoiceFile(m)
                }
            }
        }
    }

    fun applyVoiceSetting() {
        val saved = app.settings.ttsVoice
        if (saved.isNotBlank() && tts.setVoiceByName(saved)) return
        tts.pickDefaultFemaleVoice()
    }

    tts.onDone = { speakingId = null }
    tts.onError = { speakingId = null }
    tts.onInitFailed = { msg -> toast(msg) }
    DisposableEffect(Unit) {
        tts.init { ok -> if (ok) applyVoiceSetting() }
        onDispose {
            // 注意：这里**不能**取消生成任务（apiCall/job）——离开聊天页去设置时生成要继续；
            // 停止生成由 stop() 统一走 ChatRuntime.cancelCurrent()
            asr.destroy()
            stopVoice()
            player.release()
            tts.shutdown()
        }
    }

    // 本次发出的回复流式结束后：标记成语音消息 + 自动合成播放一次（历史消息不标记）
    val lastMsg = messages.lastOrNull()
    LaunchedEffect(lastMsg?.id, lastMsg?.status) {
        val m = lastMsg ?: return@LaunchedEffect
        if (!m.fromMe && m.id == autoSpeakId &&
            m.status == ChatMsg.STATUS_FINISHED && autoSpokenId != m.id
        ) {
            autoSpokenId = m.id
            autoSpeakId = null
            if (ttsEnabled && speakableText(m).isNotBlank()) {
                if (!m.voice) app.updateMessage(m.id) { it.copy(voice = true) }
                // 只合成、不自动播放：语音消息送达后由用户点气泡播
voiceMessage(m.copy(voice = true), play = false)
            }
        }
    }

    // 切会话时清掉"待自动朗标记并停掉播放，避免串会打开就念旧消
LaunchedEffect(app.currentId) {
        autoSpeakId = null
        stopVoice()
    }
    LaunchedEffect(ttsEnabled) {
        if (!ttsEnabled) stopVoice()
    }

    // 自愈：进程被杀 / 生成任务丢失后，残留的「生成中」消息不能永远转圈。
    // 只在确实没有活跃生成任务时处理（正常流式期间 ChatRuntime.job 是 active）。
    LaunchedEffect(app.currentId) {
        if (ChatRuntime.job?.isActive == true) return@LaunchedEffect
        val list = app.current()?.messages ?: return@LaunchedEffect
        list.filter { it.status == ChatMsg.STATUS_WIP }.forEach { m ->
            app.updateMessage(m.id) {
                it.copy(
                    status = ChatMsg.STATUS_FAILED,
                    text = it.text.ifBlank { "生成已中断（应用被关闭或连接断开），可重新发送" }
                )
            }
        }
    }

    // 历史语音消息：音频缓存还在的话先读出时长，气泡按语音长度    // （跟「语音合成」开关无关：历史语音消息要一直能显示时长和播放）
    LaunchedEffect(app.currentId) {
        val list = app.current()?.messages ?: return@LaunchedEffect
        for (msg in list) {
            if (!msg.voice || durations.containsKey(msg.id)) continue
            val f = tts.fileFor(msg.id)
            if (!f.exists() || f.length() <= 0L) continue
            val d = withContext(Dispatchers.IO) { player.durationOf(f) }
            if (d > 0) durations[msg.id] = d
        }
    }

    // ---- 文件解析双通道 ----
    // 文本类小文件：本地读取（快、免费），与改造前一致；
    // PDF/Word/PPT/图片/表格/电子书：POST /files 上传，服务端解析成功返回 file_id，发送时document 块引用    // 官方文档：https://api-docs.deepseek.com/zh-cn/guides/document_parsing/
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = FileText.displayName(ctx, uri)
            // 文本类且不超过本地读取上本地通道（快、免费）            // 超过 1 MiB 的文/ 表格 / 电子/ PDF / Word / PPT / 图片 官方服务端解
val size = runCatching { FileText.sizeOf(ctx, uri) }.getOrNull() ?: -1L
            val localText = FileText.isLocalText(name) &&
                (size <= 0L || size <= FileText.MAX_BYTES.toLong())
            if (localText) {
                val result = withContext(Dispatchers.IO) { FileText.read(ctx, uri) }
                result.fold(
                    onSuccess = { picked ->
                        input = FileText.wrap(picked)
                        toast(L.format(L.chat.toastFileRead, picked.name))
                    },
                    onFailure = { toast(it.message ?: L.chat.toastFileReadFailed) }
                )
                return@launch
            }
            // 第三期：服务端解析类文件受「文件解析」开+ 会话权限控制            // 纯文本类小文件仍走上面的本地读取，不受该开关影响
val parseToolId = "server.document_parse"
            val parseToolName = L.tools.names[parseToolId] ?: "未知工具"
            if (toolHealthBlocked(parseToolId)) {
                recordToolErrorForModel(parseToolId, parseToolName, name, "TOOL_PAUSED", toolPausedText(parseToolId))
                return@launch
            }
            if (!ensureToolUsable(parseToolId, L.chat.toolOffFileParse)) return@launch
            if (clientRoundLimitReached()) {
                val limit = app.settings.toolRounds
                toast(L.format(L.chat.modelToolLimit, limit))
                recordToolErrorForModel(parseToolId, parseToolName, name, "TOOL_LIMIT", L.format(L.chat.modelToolLimit, limit))
                return@launch
            }
            // 第三期一次性审批：read 直接拒（不弹窗）；workspace 弹一次；full 免审
when (ToolRegistry.approvalPolicy(parseToolId, currentPermission())) {
                ToolApprovalPolicy.DENY -> {
                    toast(L.format(L.chat.permDenied, permissionLabel(currentPermission())))
                    recordToolErrorForModel(parseToolId, parseToolName, name, "APPROVAL_REJECTED", L.chat.toolRejected)
                    return@launch
                }

                ToolApprovalPolicy.ASK -> {
                    val allowed = ToolApproval.request(
                        toolId = parseToolId,
                        toolName = parseToolName,
                        action = L.format(L.chat.approveFileParse, name),
                        args = L.format(L.chat.toolCallArgs, name)
                    )
                    if (!allowed) {
                        toast(L.chat.toolRejected)
                        recordToolErrorForModel(parseToolId, parseToolName, name, "APPROVAL_REJECTED", L.chat.toolRejected)
                        return@launch
                    }
                }

                ToolApprovalPolicy.ALLOW -> Unit
            }
            // 服务端解析通道：先检查鉴权，再上传（大文件解析可能要几十秒）
            val key = app.settings.apiKey
            if (key.isBlank()) {
                toast(L.feat.fileNeedApiKey)
                return@launch
            }
            toast(L.feat.fileUploading)
            pendingClientToolCalls += 1
            val parseTimeoutMs = ToolRegistry.timeoutMs(parseToolId)
            val parseStarted = android.os.SystemClock.elapsedRealtime()
            val result = withToolTimeout(parseTimeoutMs) {
                FileText.uploadForParsing(
                    ctx,
                    uri,
                    DeepSeekApi(app.settings.baseUrl, key)
                )
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - parseStarted
            result.fold(
                onSuccess = { doc ->
                    noteToolSuccess(parseToolId)
                    pendingToolSteps.add(
                        Step.ToolCall(
                            toolId = parseToolId,
                            toolName = parseToolName,
                            args = name,
                            result = "file_id=" + doc.fileId,
                            durationMs = elapsed
                        )
                    )
                    pendingDocs.add(doc)
                    input = FileText.serverNotice(doc) + input
                    toast(L.format(L.feat.fileDone, doc.name))
                },
                onFailure = { e ->
                    noteToolFailure(parseToolId)
                    val timeout = e is ToolTimeoutException
                    val errorCode = if (timeout) "TOOL_TIMEOUT" else "TOOL_FAILED"
                    val errMsg = if (timeout) {
                        L.format(L.chat.toolTimeout, parseTimeoutMs / 1000, errorCode)
                    } else {
                        e.message ?: L.feat.fileParseFailed
                    }
                    pendingToolSteps.add(
                        Step.ToolCall(
                            toolId = parseToolId,
                            toolName = parseToolName,
                            args = name,
                            error = "$errorCode: $errMsg",
                            durationMs = elapsed
                        )
                    )
                    pendingToolNotices.add(L.format(L.chat.modelToolRejected, parseToolName, errorCode))
                    toast(errMsg)
                }
            )
        }
    }

    // 是否跟随到底部：用户往上滑就停，滑回底发消聚焦输入框时恢复
    var follow by remember { mutableStateOf(true) }

    // 贴底：必须从 AppState **实时**取当前会话    // 之前捕获的是外层局部变messages 首帧的快照），LaunchedEffect 只启动一次，
    // 于是永远拿到那个空列自动滚动整个失效
suspend fun stickToBottom() {
        val list = app.current()?.messages ?: return
        if (list.isEmpty()) return
        runCatching { listState.scrollToItem(list.lastIndex, Int.MAX_VALUE) }
    }

    // 用户上滑 暂停跟随（模型还在回复也不抢）；滑回底部 恢复跟随
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (!canForward) follow = true else if (scrolling) follow = false
            }
    }

    // 只在内容增长（新消息 / 流式增长）时贴底；切换会话时重建，避免流还指向旧列表
    // 注意：不能只盯正文长度——思搜索/打开网页阶段正文还是空的，这里要把步骤的增长也纳
LaunchedEffect(listState, app.currentId) {
        snapshotFlow {
            val list = app.current()?.messages
            val last = list?.lastOrNull()
            val steps = last?.steps
            var stepWeight = 0
            if (steps != null) {
                stepWeight = steps.size
                for (st in steps) {
                    stepWeight += when (st) {
                        is Step.Text -> st.text.length
                        is Step.Thinking -> st.text.length
                        is Step.Search -> st.queries.size + st.results.size
                        is Step.Fetch -> st.results.size + st.url.length
                        is Step.Download -> st.filePath.length + st.note.length
                        is Step.ToolCall -> st.toolName.length + st.args.length + st.result.length + st.error.length
                    }
                }
            }
            (list?.size ?: 0) to ((last?.text?.length ?: 0) + stepWeight)
        }.drop(1).collect { (n, _) -> if (follow && n > 0) stickToBottom() }
    }

    // 1.0.3：切会话时恢复该会话上次的滚动位置；没记录过（或记录越界）才贴底。
    // 之前这里无条件 follow=true + 贴底，导致「返回某个会话」总是被拽到最底部。
    LaunchedEffect(app.currentId) {
        val count = app.current()?.messages?.size ?: 0
        val saved = app.currentId?.let { scrollMemory[it] }
        if (saved != null && saved.first in 0 until count) {
            follow = false
            runCatching { listState.scrollToItem(saved.first, saved.second) }
            // 等一帧布局完成；恢复后**保持不跟随**（布局未完成时 canScrollForward 不可靠，
            // 用它判断会把刚恢复的位置又贴回底部）。用户手动滑到底会自动重新跟随。
            withFrameNanos { }
            runCatching { listState.scrollToItem(saved.first, saved.second) }
        } else {
            follow = true
            stickToBottom()
        }
        scrollRestored = true
    }

    // 持续按会话记忆滚动位置（内存级）：firstVisibleItemIndex + offset。
    // 放在恢复 effect 之后声明，保证切会话时先读到旧值再被新的滚动状态覆盖。
    LaunchedEffect(listState, app.currentId) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { pos -> app.currentId?.let { scrollMemory[it] = pos } }
    }

    // 键盘升降：视口高度一变就贴底。layoutInfo 在布局完成后才更新    // 所以这里的 clamp 基准是新视口，不会滚完还差一截
LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .collect { if (follow && scrollRestored) stickToBottom() }
    }

    // 输入框获得焦点时，直接切到贴底模
LaunchedEffect(inputFocused) {
        if (inputFocused) follow = true
    }


    // 浮层优先消费返回键：先关浮层，再退页面
    BackHandler(enabled = menu) { menu = false }
    BackHandler(enabled = permMenu) { permMenu = false }
    BackHandler(enabled = sheet) { sheet = false }
    BackHandler(enabled = drawer) { drawer = false }

    fun send(
        text: String,
        fromVoice: Boolean = false,
        images: List<String> = emptyList(),
        place: GeoPlace? = null,
        /** 本条消息引用的服务端解析文档（Files API 返回file_id*/
        docs: List<FileText.ServerDoc> = emptyList()
    ) {
        val t = text.trim()
        // 文字 / 图片 / 位置 / 服务端解析文档至少有一项才
if (t.isEmpty() && images.isEmpty() && place == null && docs.isEmpty()) return
        follow = true
        val s0 = app.settings
        // 第三期：本次请求的会话权/ 工具开关统一在发送时快照一次，
        // 避免发送过程中用户改权限导致请求前后不一致
val convMode = currentPermission()
        val allowImages = ToolRegistry.canExecute("server.image_understand", convMode)
        val allowOcr = ToolRegistry.canExecute("server.ocr", convMode)
        val baseRequestTools = ToolRegistry.requestConfig(convMode)
        // 文件解析等客户端调用发生在发送之前，攒下的步骤挂到这条新回复的时间线最前面
        val carriedSteps = pendingToolSteps.toList()
        pendingToolSteps.clear()
        // 客户端工具错误说明（拒绝/超时/轮次上限）随本请求以文本形式回给模型
        val carriedNotices = pendingToolNotices.toList()
        pendingToolNotices.clear()
        // 发送前已发生的客户端调用（文件解析）计入本轮统一轮次预算
        val carriedClientCalls = pendingClientToolCalls
        pendingClientToolCalls = 0
        // ---- 用户元信息（DeepSeek 官方 latest_reminder）：语言/地区变了才在下一轮带----
        // 官方文档：https://api-docs.deepseek.com/zh-cn/guides/latest_reminder/
        // 它不是请求体字段，而是 messages 里紧user 的一条消息；这里按「当前会话上一次带        // 那条标记」比较，语言或地区不同才带，不改就不重复发；新会话第一条也带一次（否则该会        // 的模型上下文里没有用户地语言）。不写进任何可见消息，只作为 ChatMsg 的隐藏字段
val reminderKey = LatestReminder.key(L)
        val lastReminderKey = messages.asReversed()
            .firstOrNull { it.fromMe && it.reminderKey.isNotBlank() }?.reminderKey
        // 没填 Key 走本地模拟，不会真的发给模型，就别标记「已告知
val reminderText = if (s0.apiKey.isNotBlank() && lastReminderKey != reminderKey) {
            LatestReminder.content(L)
        } else {
            ""
        }
        // 历史上下= 文本（modelContent：位置转说明文字 本地图片路径        // 图片不再变成「（用户附了 N 张图片）」文字，而是真正编码image 内容块发给模
val historyMsgs = messages.filter {
            replayModelText(it).isNotBlank() ||
                (it.fromMe && (it.images.isNotEmpty() || it.docs.isNotEmpty()))
        }
        // 历史图片只重放最近几条「带图用户消息」：官方限制请求8 MiB、单请求 00 张，
        // 全部历史都带图既没必要也容易超限；最近几轮足以保持多轮问图的连续
        val replayImageIds = historyMsgs
            .filter { it.fromMe && it.images.isNotEmpty() }
            .map { it.id }
            .toHashSet()
        // 本条消息引用的服务端文档（去重 + 官方上限；先占预算）
        val promptDocList = docs.map { it.fileId }.filter { it.isNotBlank() }.distinct()
        val promptDocs = promptDocList.take(HISTORY_DOC_LIMIT)
        // 1.0.3 / D97 ⑤：官方「每个请求最多引用 20 个文档」是**历史重放 + 本条**合并计数。
        // 以前历史先占满 20、本条又 take(20)，合计最多 40 → 400。现在本条先占、历史只能用剩余名额。
        var docOverflow = promptDocList.size > promptDocs.size
        val replayDocs = HashMap<Long, List<String>>()
        var docBudget = (HISTORY_DOC_LIMIT - promptDocs.size).coerceAtLeast(0)
        for (m in historyMsgs) {
            if (!m.fromMe || m.docs.isEmpty()) continue
            val ids = m.docs.distinct()
            if (docBudget <= 0) {
                docOverflow = true
                continue
            }
            if (ids.size > docBudget) docOverflow = true
            val take = ids.take(docBudget)
            replayDocs[m.id] = take
            docBudget -= take.size
        }

        val userMsg = ChatMsg(
            fromMe = true,
            text = t,
            images = images,
            docs = promptDocs,
            lat = place?.lat,
            lng = place?.lng,
            place = place?.title.orEmpty(),
            placeDetail = place?.detail.orEmpty(),
            reminder = reminderText,
            reminderKey = if (reminderText.isNotBlank()) reminderKey else "",
            at = System.currentTimeMillis()
        )
        val modelPrompt = modelContent(userMsg)
        val cacheTurnNo = messages.count { it.fromMe } + 1
        app.addMessage(userMsg.copy(modelText = modelPrompt))
        val aiId = System.nanoTime()
        app.addMessage(
            ChatMsg(
                id = aiId,
                fromMe = false,
                text = "",
                steps = carriedSteps,
                status = ChatMsg.STATUS_WIP,
                voice = ttsEnabled,
                at = System.currentTimeMillis()
            )
        )
        // 只有发送时「语音合成」已经打开，这条回复完成后才会自动合成成语音消息；
        // 中途才打开开关的，不把这条旧回复变成语音
        autoSpeakId = if (ttsEnabled) aiId else null

        // 自己发消息也要贴底：刚插入的两条要等布局完成才量得出来，
        // 内容增长那条 flow 可能在布局前就跑完了，所以这里补两次
        scope.launch {
            delay(30)
            stickToBottom()
            delay(120)
            stickToBottom()
        }

        // 没填 Key：本地模
if (s0.apiKey.isBlank()) {
            val body = buildReply(L, modelPrompt, fromVoice, search, imageCount = images.size)
            ChatRuntime.job = ChatRuntime.scope.launch {
                for (i in 1..body.length) {
                    delay(14)
                    app.updateMessageLite(aiId) { it.copy(text = body.substring(0, i)) }
                }
                app.updateMessage(aiId) { it.copy(text = body, status = ChatMsg.STATUS_FINISHED, at = System.currentTimeMillis()) }
            }
            return
        }

        val steps = mutableListOf<Step>().apply { addAll(carriedSteps) }
        val indexPos = HashMap<Int, Int>()
        // 缓存阶段 A：本轮最近一次 assistant 原始 content 块，用于历史原样回放
        var lastAssistantRaw = ""
        // D97：本轮实际追加的工具链放在 composable 级 liveRawChain 里（停止时要读它补配对）
        liveRawChain.clear()
        var lastPushAt = 0L
        // SSE usage 先于 message_delta(Done) 到达；以最后一次上报为准（message_start 只带输入侧）
        var usage: ApiEvent.Usage? = null
        // 内容安全拒答文案；非 null 表示本次回答safety_check 拦截
        var refusal: String? = null

        fun push(done: Boolean) {
            // 拒答后不再接受流式内容：避免 Done 之后残留delta 把拒答文案覆盖成「生成中
if (!done && refusal != null) return
            // 流式时按 ~30fps 节流，避免每chunk 都重建整张列
val now = android.os.SystemClock.elapsedRealtime()
            if (!done && now - lastPushAt < 33) return
            lastPushAt = now
            // 收尾时丢掉「空的思考块」：某些轮次服务端只发 thinking 块的 signature、
            // 正文为空，卡片会永远显示「正在思考…」
            if (done) steps.removeAll { it is Step.Thinking && it.text.isBlank() }
            val txt = steps.filterIsInstance<Step.Text>().joinToString("") { it.text }
            val snapshot = steps.toList()
            if (done) {
                val u = usage
                val refusedText = refusal
                // D97：rawAssistant 不再整条丢弃——清洗时 thinking 块（含 signature）原样保留，
                // 只压缩 text；就算仍超限也不允许删 thinking（删了带 tools 的历史会 400）。
                val rawForStore = if (RAW_REPLAY) sanitizeAssistantRawForStore(lastAssistantRaw) else ""
                app.updateMessage(aiId) {
                    // 拒答：官方要求删除已流式下发的正文，换成我们的拒答文案；
                    // STATUS_REFUSED 让气泡保持中性文字色，不用红色错误样
if (refusedText != null) {
                        it.copy(
                            steps = emptyList(),
                            text = refusedText,
                            status = ChatMsg.STATUS_REFUSED,
                            promptTokens = u?.promptTokens ?: it.promptTokens,
                            completionTokens = u?.completionTokens ?: it.completionTokens,
                            cacheReadTokens = u?.cacheReadTokens ?: it.cacheReadTokens,
                            safetyTokens = u?.safetyTokens ?: it.safetyTokens,
                            modelName = u?.model?.takeIf { m -> m.isNotBlank() }
                                ?: s0.modelName.ifBlank { "deepseek-flash" },
                            at = System.currentTimeMillis()
                        )
                    } else {
                        it.copy(
                            steps = snapshot,
                            text = txt,
                            rawAssistant = rawForStore,
                            rawTruncated = RAW_REPLAY && rawForStore.length < lastAssistantRaw.length,
                            rawChain = if (RAW_REPLAY && liveRawChain.size > 1) {
                                sanitizeChainForStore(liveRawChain.toList())
                            } else "",
                            status = ChatMsg.STATUS_FINISHED,
                            promptTokens = u?.promptTokens ?: it.promptTokens,
                            completionTokens = u?.completionTokens ?: it.completionTokens,
                            cacheReadTokens = u?.cacheReadTokens ?: it.cacheReadTokens,
                            safetyTokens = u?.safetyTokens ?: it.safetyTokens,
                            modelName = u?.model?.takeIf { m -> m.isNotBlank() }
                                ?: s0.modelName.ifBlank { "deepseek-flash" },
                            at = System.currentTimeMillis()
                        )
                    }
                }
            } else {
                app.updateMessageLite(aiId) { it.copy(steps = snapshot, text = txt) }
            }
        }

        /**
         * 结束气泡；官方 stop_reason=max_tokens（输出达到模型上限）时在末尾补一行「已截断」提示。
         * D97 ④：必须让用户明确知道这不是完整回答。
         */
        fun finishWithTruncation(stopReason: String?) {
            if (stopReason == "max_tokens") {
                steps.add(Step.Text("\n\n" + L.chat.answerTruncated))
            }
            push(true)
        }

        // D97 ⑤：文档配额被截断时在气泡里明确提示（不用 Toast，避免连发时刷屏）
        if (docOverflow) {
            steps.add(Step.Text("\n\n" + L.format(L.chat.docLimitNotice, HISTORY_DOC_LIMIT)))
            push(false)
        }

        val api = DeepSeekApi(s0.baseUrl, s0.apiKey)

        // 图片读盘 + base64 编码必须Dispatchers.IO 上做。失败不能静默：
        // - 本次消息的图片读不出来：Toast 提示 + 在请求文本里注明「有 N 张图片未能发送」；
        // - 历史消息的图片读不出来：直接跳过（文件可能已被清理），不影响本次发送
ChatRuntime.job = ChatRuntime.scope.launch {
            // 本轮生成的标识：工具循环 / 后续轮次用它判断「这一轮是否还该继续」
            val myGen = ChatRuntime.begin()
            val prepared = withContext(Dispatchers.IO) {
                // base64 内联总量按官方请求体上限8 MiB）留余量后控制在 40 MiB，超出的图片跳过
                var budget = ChatImage.MAX_TOTAL_BYTES
                fun takeImages(paths: List<String>): List<EncodedChatImage> {
                    val out = ArrayList<EncodedChatImage>(paths.size)
                    for (path in paths) {
                        val img = ChatImage.encode(path) ?: continue
                        if (img.approxBytes > budget) continue
                        budget -= img.approxBytes
                        out.add(img)
                    }
                    return out
                }
                val turns = ArrayList<ChatTurn>(historyMsgs.size)
                for (m in historyMsgs) {
                    // 官方限制：图片只能出现在 user / system / tool 消息里，assistant 带图400
                    // 第三期：图片理解工具关闭/权限不足时，历史图片也不放进请求（气泡仍正常显示
                    val imgs = if (allowImages && m.id in replayImageIds) takeImages(m.images) else emptyList()
                    val replayText = replayModelText(m)
                    if (m.fromMe && m.modelText.isBlank() && replayText.isNotBlank()) {
                        // 旧消息补齐 modelText；只写模型回放字段，不改可见 text
                        app.updateMessageLite(m.id) { it.copy(modelText = replayText) }
                    }
            // 1.0.3 / D97：跨模型也回放 raw（尤其 thinking 块 + signature）。官方不校验签名，
            // 反过来「跨模型就整条丢 raw」会让带 tools 的历史缺 thinking 而 400；只有模型专属的
            // 其它字段目前没有（tool_use/text 都是通用块），无需额外剥离。
            turns.add(
                ChatTurn(
                    fromMe = m.fromMe,
                    text = replayText,
                    images = imgs,
                    rawAssistant = if (m.fromMe || !RAW_REPLAY) "" else m.rawAssistant,
                    rawChain = if (m.fromMe || !RAW_REPLAY) "" else m.rawChain,
                            // 之前各轮latest_reminder 原样回传（官方建议，能提升上下文缓存命中
reminder = if (m.fromMe) m.reminder else "",
                            // 服务端解析文档：历史轮也要重file_id，多轮才能接着问同一份文
documents = replayDocs[m.id] ?: emptyList()
                        )
                    )
                }
                Triple(
                    turns,
                    if (allowImages) takeImages(images) else emptyList(),
                    if (allowImages) images.size else 0
                )
            }
            val historyTurns = prepared.first
            val promptImages = prepared.second
            val failedImages = (prepared.third - promptImages.size).coerceAtLeast(0)

            // 只有图片、且一张都没编码成功：别发一条空消息给模型，直接把失败原因告诉用
if (promptImages.isEmpty() && failedImages > 0 && modelPrompt.isBlank()) {
                val msg = L.chat.toastImagesAllFailed
                toast(msg)
                app.updateMessage(aiId) {
                    it.copy(text = msg, status = ChatMsg.STATUS_FAILED, at = System.currentTimeMillis())
                }
                return@launch
            }
            if (failedImages > 0) toast(L.format(L.chat.toastImagesSomeFailed, failedImages))
            // 有图片没发出去就在文本里说明，别让模型以为自己看全了            // 图片理解关闭时也告诉模型「用户发了图但工具关着」，而不是让模型完全不知
val promptText = buildList {
                addAll(carriedNotices)
                add(modelPrompt)
                if (failedImages > 0) add(L.format(L.chat.modelImageNotSent, failedImages))
                if (!allowImages && images.isNotEmpty()) {
                    add(L.format(L.chat.modelImageUnderstandOff, images.size))
                }
            }.filter { it.isNotBlank() }.joinToString("\n")

            // ---- 服务OCR（注册表里的 server.ocr 开+ 会话权限 + 一次性审批）----
            // 官方 OCR 文档明确：识别结果不会存 Files API，也不会交给对话模型            // 所以这里把它作为「模型上下文补充」：识别当前消息图片里的文字，附加在 prompt 末尾            // 图片本身仍按 image 内容块正常发送。OCR 失败Toast，不影响正常对话
var ocrSupplement = ""
            var ocrErrorNotes = ""
            var clientToolCalls = carriedClientCalls
            var requestTools = baseRequestTools
            var requestMaxRounds = s0.toolRounds
            val ocrToolId = "server.ocr"
            val ocrToolName = L.tools.names[ocrToolId] ?: "OCR"
            // 轮次已用满（例如发送前多次文件解析）：不再弹审批、不调用 OCR，只提示
            val ocrRoundLimitHit = s0.toolRounds > 0 && clientToolCalls >= s0.toolRounds
            if (ocrEnabled && allowOcr && images.isNotEmpty() && ocrRoundLimitHit) {
                steps.add(Step.Text("\n\n" + L.format(L.chat.modelToolLimit, s0.toolRounds)))
                push(false)
            }
            if (ocrEnabled && allowOcr && images.isNotEmpty() && !ocrRoundLimitHit &&
                !toolHealthBlocked(ocrToolId)
            ) {
                var approvalOk = true
                // 第三期一次性审批：read 直接拒（不弹窗）；workspace 弹一次；full 免审
when (ToolRegistry.approvalPolicy(ocrToolId, convMode)) {
                    ToolApprovalPolicy.DENY -> {
                        approvalOk = false
                        toast(L.format(L.chat.permDenied, permissionLabel(convMode)))
                        ocrErrorNotes += "\n\n" + L.format(L.chat.modelToolRejected, ocrToolName, "APPROVAL_REJECTED")
                        steps.add(
                            Step.ToolCall(
                                toolId = ocrToolId,
                                toolName = ocrToolName,
                                args = images.size.toString(),
                                error = "APPROVAL_REJECTED: " + L.chat.toolRejected
                            )
                        )
                    }

                    ToolApprovalPolicy.ASK -> {
                        val allowed = ToolApproval.request(
                            toolId = ocrToolId,
                            toolName = ocrToolName,
                            action = L.chat.approveOcr,
                            args = L.format(L.chat.toolCallArgs, images.size.toString())
                        )
                        if (!allowed) {
                            approvalOk = false
                            toast(L.chat.toolRejected)
                            ocrErrorNotes += "\n\n" + L.format(L.chat.modelToolRejected, ocrToolName, "APPROVAL_REJECTED")
                            steps.add(
                                Step.ToolCall(
                                    toolId = ocrToolId,
                                    toolName = ocrToolName,
                                    args = images.size.toString(),
                                    error = "APPROVAL_REJECTED: " + L.chat.toolRejected
                                )
                            )
                        }
                    }

                    ToolApprovalPolicy.ALLOW -> Unit
                }
                if (approvalOk) {
                    val recognized = ArrayList<String>()
                    var ocrTried = 0
                    var limitHit = false
                    val ocrTimeoutMs = ToolRegistry.timeoutMs(ocrToolId)
                    for (path in images) {
                        if (ocrTried >= OCR_MAX_IMAGES) break
                        // 统一轮次刹车：文件解+ OCR 都算客户端调
if (s0.toolRounds > 0 && clientToolCalls >= s0.toolRounds) {
                            limitHit = true
                            break
                        }
                        if (toolHealthBlocked(ocrToolId)) break
                        ocrTried++
                        val file = File(path)
                        if (!file.exists() || file.length() <= 0L) continue
                        val started = android.os.SystemClock.elapsedRealtime()
                        val r = withToolTimeout(ocrTimeoutMs) {
                            api.recognizeText(
                                filename = file.name,
                                // pendingImages 都是 AppState.importChatImage 落盘的长080 JPEG
                                mimeType = "image/jpeg",
                                size = file.length(),
                                open = { file.inputStream() }
                            )
                        }
                        val elapsed = android.os.SystemClock.elapsedRealtime() - started
                        clientToolCalls++
                        r.fold(
                            onSuccess = { ocr ->
                                noteToolSuccess(ocrToolId)
                                val cleaned = cleanOcrText(ocr.text)
                                if (cleaned.isNotBlank()) recognized.add(cleaned)
                                steps.add(
                                    Step.ToolCall(
                                        toolId = ocrToolId,
                                        toolName = ocrToolName,
                                        args = file.name,
                                        result = cleaned.take(120),
                                        durationMs = elapsed
                                    )
                                )
                            },
                            onFailure = { e ->
                                noteToolFailure(ocrToolId)
                                val timeout = e is ToolTimeoutException
                                val errorCode = if (timeout) "TOOL_TIMEOUT" else "TOOL_FAILED"
                                val errMsg = if (timeout) {
                                    L.format(L.chat.toolTimeout, ocrTimeoutMs / 1000, errorCode)
                                } else {
                                    (e.message ?: L.feat.ocrServerFailed)
                                }
                                steps.add(
                                    Step.ToolCall(
                                        toolId = ocrToolId,
                                        toolName = ocrToolName,
                                        args = file.name,
                                        error = "$errorCode: " + errMsg.take(120),
                                        durationMs = elapsed
                                    )
                                )
                                ocrErrorNotes += "\n\n" + L.format(L.chat.modelToolRejected, ocrToolName, errorCode)
                                toast(L.format(L.feat.ocrFailed, errMsg))
                            }
                        )
                        push(false)
                        // 熔断：本次失败后该工具已被暂停就停止继续调用
                        if (currentConversationId()?.let { ToolHealth.isPaused(it, ocrToolId) } == true) break
                    }
                    if (images.size > OCR_MAX_IMAGES) toast(L.format(L.feat.ocrLimit, OCR_MAX_IMAGES))
                    if (limitHit) {
                        steps.add(Step.Text("\n\n" + L.format(L.chat.modelToolLimit, s0.toolRounds)))
                        push(false)
                    }
                    if (recognized.isNotEmpty()) {
                        ocrSupplement = "\n\n" + L.feat.ocrSupplementHeader + "\n" +
                            truncateMiddle(recognized.joinToString("\n\n"), TOOL_OUTPUT_LIMIT, L)
                    }
                }
            }
            // E12：统一轮次预算——文件解+ OCR 的客户端调用都算toolRounds            // 剩余额度才给服务端工具；剩余0 时不再把 web_search/read_url 放进请求
            // （接口里 maxRounds=0 表示无上限）
if (s0.toolRounds > 0) {
                val remaining = (s0.toolRounds - clientToolCalls).coerceAtLeast(0)
                requestMaxRounds = if (remaining == 0) 1 else remaining
                if (remaining == 0) {
                    requestTools = requestTools.copy(webSearch = false, readUrl = false)
                }
            }
            // A 方案：标题只在会话第一条请求带；后续统一不带，避免请求体变化废缓存
            if (historyMsgs.isNotEmpty()) {
                requestTools = requestTools.copy(title = false)
            }
            val promptWithOcr = buildString {
                append(promptText)
                if (ocrSupplement.isNotBlank()) append(ocrSupplement)
                if (ocrErrorNotes.isNotBlank()) append(ocrErrorNotes)
            }
            if (promptWithOcr != modelPrompt) {
                // A1：OCR 补充也算「真正发给模型的 user 原文」，冻结进 modelText
                app.updateMessageLite(userMsg.id) { it.copy(modelText = promptWithOcr) }
            }

            // 4a-2：模tool_use 收集（第一轮）
            val pendingToolUses = mutableListOf<PendingToolUse>()
            var toolLoopRound = 0

            // 客户端工具执+ assistant(tool_use)/user(tool_result) 回传 + 后续
val ToolLoop = object {
                suspend fun stream(extra: List<JSONObject>) {
                    if (!ChatRuntime.isCurrent(myGen)) return
                    // 默认（toolRounds = 0）不设客户端轮次上限：跟 DSH 一样，
                    // 什么时候停止调用工具由模型自己决定（end_turn 收尾），用户可随时点「停止」。
                    // 只有在设置里显式填了上限时才截断。
                    if (s0.toolRounds > 0 && toolLoopRound >= s0.toolRounds) {
                        steps.add(Step.Text("\n\n" + L.format(L.chat.modelToolLimit, s0.toolRounds)))
                        push(true)
                        return
                    }
                    toolLoopRound++
                    // 每一轮都按**当前**会话权限重建工具清单：用户中途在盾牌菜单改档位，
                    // 下一轮请求就按新档位给工具（否则要等下一条消息才生效）。
                    val roundMode = currentPermission()
                    var cfg = ToolRegistry.requestConfig(roundMode).copy(title = false)
                    var maxR = s0.toolRounds
                    if (s0.toolRounds > 0) {
                        val remaining = (s0.toolRounds - clientToolCalls).coerceAtLeast(0)
                        if (remaining <= 0) {
                            cfg = cfg.copy(webSearch = false, readUrl = false)
                            maxR = 1
                        } else {
                            maxR = remaining
                        }
                    }
                    val loopHistory = historyTurns + ChatTurn(
                        fromMe = true,
                        text = promptWithOcr,
                        images = promptImages,
                        reminder = reminderText,
                        documents = promptDocs
                    )
                    val pending = mutableListOf<PendingToolUse>()
                    var callRaw = ""
                    val call = runCatching {
                        withContext(Dispatchers.IO) {
                            api.streamChat(
                                model = s0.modelName.ifBlank { "deepseek-flash" },
                                systemPrompt = systemWithMeta(s0.systemPrompt),
                                history = loopHistory,
                                prompt = "",
                                promptImages = emptyList(),
                                promptReminder = "",
                                requestTools = cfg,
                                maxRounds = maxR,
                                documents = emptyList(),
                                appendPrompt = false,
                                rawMessages = extra,
                                requestTag = "$cacheTurnNo.${toolLoopRound + 1}",
                                onEvent = { ev ->
                                    if (ev is ApiEvent.AssistantRaw) {
                                        callRaw = ev.rawJson
                                        lastAssistantRaw = ev.rawJson
                                    }
                                    ChatRuntime.scope.launch {
                                        if (!ChatRuntime.isCurrent(myGen)) return@launch
                                        when (ev) {
                                            is ApiEvent.AssistantRaw -> Unit
                                            is ApiEvent.BlockStart -> {
                                                steps.add(ev.step)
                                                indexPos[ev.index] = steps.lastIndex
                                                push(false)
                                            }

                                            is ApiEvent.Delta -> {
                                                val pos = indexPos[ev.index] ?: return@launch
                                                steps[pos] = when (val st = steps[pos]) {
                                                    is Step.Thinking -> st.copy(text = st.text + ev.text)
                                                    is Step.Text -> st.copy(text = st.text + ev.text)
                                                    else -> st
                                                }
                                                push(false)
                                            }

                                            is ApiEvent.ToolUseStart -> Unit
                                            is ApiEvent.ToolUseEnd -> pending.add(
                                                PendingToolUse(ev.id, ev.name, ev.inputJson)
                                            )

                                            is ApiEvent.Usage -> usage = ev

                                            is ApiEvent.Title -> {
                                                val cid = app.currentId ?: return@launch
                                                val conv = app.conversations.firstOrNull { it.id == cid }
                                                    ?: return@launch
                                                val auto = userMsg.text.replace('\n', ' ').take(18)
                                                if (conv.title == auto || L.isDefaultChatTitle(conv.title)) {
                                                    app.rename(cid, ev.title.take(40))
                                                }
                                            }

                                            is ApiEvent.Done -> {
                                                if (ev.stopReason == "refusal") {
                                                    steps.clear()
                                                    indexPos.clear()
                                                    val base = L.feat.refusalBase
                                                    refusal = if (ev.stopExplanation.isNullOrBlank()) {
                                                        base
                                                    } else {
                                                        L.format(L.feat.refusalWithDetail, base, ev.stopExplanation)
                                                    }
                                                    push(true)
                                                } else if (pending.isNotEmpty()) {
                                                    val uses = pending.toList()
                                                    pending.clear()
                                                    push(false)
                                                    continueWithTools(uses, extra, callRaw)
                                                } else {
                                                    if (callRaw.isNotBlank()) {
                                                        liveRawChain.add(
                                                            JSONObject().put("role", "assistant")
                                                                .put("content", JSONArray(callRaw))
                                                        )
                                                    }
                                                    finishWithTruncation(ev.stopReason)
                                                }
                                            }

                                            is ApiEvent.InputBlocked -> {
                                                toast(ev.message)
                                                app.updateMessage(aiId) {
                                                    it.copy(
                                                        text = ev.message,
                                                        status = ChatMsg.STATUS_REFUSED,
                                                        at = System.currentTimeMillis()
                                                    )
                                                }
                                            }

                                            is ApiEvent.Limit -> {
                                                steps.add(
                                                    Step.Text(
                                                        "\n\n" + L.format(L.chat.modelToolLimit, ev.rounds + clientToolCalls)
                                                    )
                                                )
                                                push(true)
                                            }

                                            is ApiEvent.Failed -> {
                                                if (!isCancelledMessage(ev.message)) {
                                                    toast(L.format(L.chat.toastRequestFailed, ev.message.take(120)))
                                                }
                                                app.updateMessage(aiId) {
                                                    it.copy(
                                                        text = L.format(L.chat.toastRequestFailed, ev.message),
                                                        status = ChatMsg.STATUS_FAILED,
                                                        at = System.currentTimeMillis()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                    call.onFailure { e ->
                        val msg = e.message ?: L.chat.requestBuildFailed
                        toast(L.format(L.chat.toastRequestFailed, msg.take(120)))
                        app.updateMessage(aiId) {
                            it.copy(
                                text = L.format(L.chat.toastRequestFailed, msg),
                                status = ChatMsg.STATUS_FAILED,
                                at = System.currentTimeMillis()
                            )
                        }
                    }
                    ChatRuntime.call = call.getOrNull()
                }

                suspend fun continueWithTools(
                    uses: List<PendingToolUse>,
                    extra: List<JSONObject>,
                    assistantRaw: String = ""
                ) {
                    if (!ChatRuntime.isCurrent(myGen)) return
                    val assistantContent = JSONArray()
                    val resultContent = JSONArray()
                    var limitHit = false
                    val convId = app.currentId ?: "preview"
                    for (u in uses) {
                        // 用户已停止：不再执行后续工具调用（工具循环是独立 scope.launch，
                        // job.cancel() 不会自动终止它，必须每次用 genToken 校验）
                        if (!ChatRuntime.isCurrent(myGen)) return
                        if (s0.toolRounds > 0 && clientToolCalls >= s0.toolRounds) {
                            limitHit = true
                            break
                        }
                        val spec = ToolRegistry.all().firstOrNull {
                            it.name == u.name && (
                                it.id == "local.python" || it.source == ToolSource.USER ||
                                    LocalToolRunner.handles(it.id)
                                )
                        }
                        val displayName = spec?.let { L.tools.names[it.id] ?: it.name } ?: u.name
                        // 权限实时读：用户在工具链跑到一半时改档位，后面的调用立刻按新档位判定
                        val liveMode = currentPermission()
                        val started = android.os.SystemClock.elapsedRealtime()
                        var ok = false
                        var output = ""
                        var errorCode: String? = null
                        var errorMsg: String? = null
                        var dlPath: String? = null
                        var dlMime = ""
                        var dlSize = 0L
                        // local.imagegen：一次可能产出多张图 + 部分失败（成功图不丢）
                        var genFiles: List<PyFile> = emptyList()
                        var genFailures: List<PyFailure> = emptyList()
                        // 1.0.3：Python/用户工具输出超 64KB 时，完整输出落盘路径
                        var outputPath: String? = null
                        // 1.0.3：web_search / web_fetch 的结构化来源（渲染搜索/打开链接卡片，与旧服务端工具一致）
                        var webSources: List<com.dchat.app.data.Source> = emptyList()
                        var webQuery = ""
                        var webUrl = ""
                        runCatching {
                            val inObj = JSONObject(u.inputJson)
                            webQuery = inObj.optString("query")
                            webUrl = inObj.optString("url")
                        }
                        if (spec == null) {
                            errorCode = "TOOL_NOT_FOUND"
                            errorMsg = "模型返回了未知工具：${u.name}"
                        } else if (!ToolRegistry.canExecute(spec.id, liveMode)) {
                            errorCode = "PERMISSION_DENIED"
                            errorMsg = L.format(L.chat.permDenied, permissionLabel(liveMode))
                        } else if (currentConversationId()?.let { ToolHealth.isPaused(it, spec.id) } == true) {
                            errorCode = "TOOL_PAUSED"
                            errorMsg = toolPausedText(spec.id)
                        } else {
                            clientToolCalls++
                            // 生图较慢：执行期间在气泡里放一张「生成中…」占位卡（停止键可取消）
                            var genPlaceholder: Step.Download? = null
                            if (spec.id == LocalToolRunner.IMAGEGEN) {
                                val ph = Step.Download(
                                    status = "generating",
                                    note = L.imageGen.generating
                                )
                                genPlaceholder = ph
                                // 1.0.3：占位卡要显示「已用时 Ns」，记下开始时刻（elapsedRealtime）
                                imageGenStartedAt = android.os.SystemClock.elapsedRealtime()
                                ChatRuntime.imageGenStartedAt = imageGenStartedAt
                                steps.add(ph)
                                // 绕过 push 的 30fps 节流，让占位卡立刻出现
                                lastPushAt = 0L
                                push(false)
                            }
                            val r: PyResult = if (spec.id == "local.python") {
                                val code = runCatching {
                                    JSONObject(u.inputJson).optString("code")
                                }.getOrDefault("")
                                if (code.isBlank()) {
                                    PyResult(
                                        ok = false,
                                        errorCode = "INVALID_ARGS",
                                        errorMessage = "code 不能为空"
                                    )
                                } else {
                                    PyRuntime.runPythonCode(
                                        context = ctx,
                                        code = code,
                                        argsJson = u.inputJson,
                                        conversationId = convId,
                                        timeoutMs = spec.timeoutMs,
                                        conversationMode = liveMode
                                    )
                                }
                            } else if (LocalToolRunner.handles(spec.id)) {
                                // 4c：fs.* / bg.* / schedule.* 原生工具，权限与审批都在执行器内部处理
                                LocalToolRunner.run(
                                    context = ctx,
                                    toolId = spec.id,
                                    argsJson = u.inputJson,
                                    conversationId = convId,
                                    mode = liveMode,
                                    // 停止后丢弃迟到的生图结果（落盘前也会再查一次）
                                    isCancelled = { !ChatRuntime.isCurrent(myGen) }
                                )
                            } else {
                                val def = UserToolStore.find(ctx, spec.id)
                                if (def == null) {
                                    PyResult(ok = false, errorCode = "TOOL_NOT_FOUND", errorMessage = "工具代码不存在")
                                } else {
                                    PyRuntime.runCode(
                                        context = ctx,
                                        toolId = spec.id,
                                        entryFunction = def.entryFunction,
                                        code = def.code,
                                        argsJson = u.inputJson,
                                        conversationId = convId,
                                        timeoutMs = spec.timeoutMs,
                                        conversationMode = liveMode
                                    )
                                }
                            }
                            // 结果回来就撤掉占位卡；被用户停止时由 stop() 兜底清理
                            imageGenStartedAt = 0L
                            ChatRuntime.imageGenStartedAt = 0L
                            if (genPlaceholder != null) {
                                steps.removeAll { item -> (item as? Step.Download) === genPlaceholder }
                            }
                            ok = r.ok
                            output = r.output
                            errorCode = r.errorCode
                            errorMsg = r.errorMessage
                            dlPath = r.filePath
                            dlMime = r.mime.orEmpty()
                            dlSize = r.sizeBytes
                            genFiles = r.files
                            genFailures = r.failures
                            outputPath = r.outputPath
                            webSources = r.sources
                            if (ok) noteToolSuccess(spec.id)
                            else if (errorCode != "PY_UNAVAILABLE") noteToolFailure(spec.id)
                        }
                        // 用户已停止：丢弃迟到的工具结果——不加卡、不回填 tool_result、不继续下一轮。
                        // 工具循环跑在独立 scope.launch 里，job.cancel() 无法中断阻塞中的生图请求。
                        if (!ChatRuntime.isCurrent(myGen)) return
                        val elapsed = android.os.SystemClock.elapsedRealtime() - started
                        val resultText = if (ok) {
                            output
                        } else {
                            (errorCode ?: "TOOL_ERROR") + ": " + (errorMsg ?: "")
                        }
                        // 1.0.3：Python/用户工具输出超过 64KB 时，完整内容（≤1MB）已落盘，
                        // PyResult.outputPath 给出路径。回给模型的文本只取 TOOL_OUTPUT_LIMIT，
                        // 并附一句「完整输出在哪、怎么用 fs.read 读」的提示。
                        var pythonOutputHint = ""
                        val outPath = outputPath
                        if (ok && !outPath.isNullOrBlank()) {
                            val outFile = File(outPath)
                            val rel = runCatching {
                                outFile.relativeTo(FileTools.workspaceDir(ctx, convId)).path
                            }.getOrElse { outPath.substringAfterLast('/') }
                            val bytes = runCatching { outFile.length() }.getOrDefault(0L)
                            pythonOutputHint = "\n\n" + L.format(
                                L.chat.pythonOutputSaved,
                                rel,
                                formatImageSize(bytes.coerceAtLeast(0L))
                            )
                        }
                        if (spec?.id == "local.download") {
                            steps.add(
                                Step.Download(
                                    filePath = dlPath.orEmpty(),
                                    mime = dlMime,
                                    sizeBytes = dlSize,
                                    status = if (ok) "ok" else (errorCode ?: "failed"),
                                    note = if (ok) output else resultText
                                )
                            )
                        } else if (spec?.id == LocalToolRunner.IMAGEGEN) {
                            // 生图：每张成功图一张下载卡（复用多图滑动/全屏/保存），失败项红卡
                            if (ok) {
                                genFiles.forEach { f ->
                                    steps.add(
                                        Step.Download(
                                            filePath = f.path,
                                            mime = f.mime,
                                            sizeBytes = f.sizeBytes,
                                            status = "ok",
                                            note = ""
                                        )
                                    )
                                }
                                genFailures.forEach { fail ->
                                    steps.add(
                                        Step.Download(
                                            status = "failed",
                                            note = L.format(
                                                L.imageGen.resultFailureItem,
                                                fail.index,
                                                fail.message
                                            )
                                        )
                                    )
                                }
                            } else {
                                steps.add(
                                    Step.Download(
                                        status = errorCode ?: "failed",
                                        note = resultText.take(1000)
                                    )
                                )
                            }
                        } else if (spec?.id == "local.web_search" && ok && webSources.isNotEmpty()) {
                            // 与旧服务端联网工具一致的「搜索了 N 个来源」卡片
                            steps.add(
                                Step.Search(
                                    queries = listOf(webQuery).filter { it.isNotBlank() },
                                    results = webSources
                                )
                            )
                        } else if (spec?.id == "local.web_fetch" && ok && webSources.isNotEmpty()) {
                            // 与旧服务端联网工具一致的「打开链接」卡片
                            steps.add(Step.Fetch(url = webUrl, results = webSources))
                        } else {
                            steps.add(
                                Step.ToolCall(
                                    toolId = spec?.id ?: u.name,
                                    toolName = displayName,
                                    args = u.inputJson.take(500),
                                    result = if (ok) output.take(4000) else "",
                                    error = if (ok) "" else resultText.take(1000),
                                    durationMs = elapsed,
                                    // 1.0.3：Python 输出 >64KB 时完整输出的落盘路径（气泡卡片可点查看）
                                    outputPath = outputPath,
                                    outputBytes = outputPath?.let {
                                        runCatching { File(it).length() }.getOrDefault(0L)
                                    } ?: 0L
                                )
                            )
                        }
                        push(false)
                        assistantContent.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", u.id)
                                .put("name", u.name)
                                .put(
                                    "input",
                                    runCatching { JSONObject(u.inputJson) }.getOrDefault(JSONObject())
                                )
                        )
                        // 图回填给模型：先压缩再内联（大图不再静默跳过）。
                        // - 原文件 >1.5MB：解码后缩到长边 ≤1536px、JPEG 85；仍 >1.5MB 再降一档；
                        // - 落盘文件保持原图不动，只把压缩版塞进 tool_result；
                        // - 多图合计 ≤6MB，超预算 / 压不到目标的逐条明确告诉模型「仅落盘」。
                        val inlineCandidates = ArrayList<Pair<String, String>>() // 绝对路径 to mime
                        if (ok) {
                            if (spec?.id == LocalToolRunner.IMAGEGEN) {
                                genFiles.forEach { inlineCandidates.add(it.path to it.mime) }
                            } else if (dlPath != null && dlMime.startsWith("image/")) {
                                inlineCandidates.add(dlPath to dlMime)
                            }
                        }
                        var inlineEncoded: List<Triple<String, ByteArray, String>> = emptyList()
                        var inlineNotice = ""
                        if (inlineCandidates.isNotEmpty()) {
                            val pack = withContext(Dispatchers.IO) {
                                var used = 0L
                                val encoded = ArrayList<Triple<String, ByteArray, String>>()
                                val skipped = ArrayList<String>()
                                for ((path, mime) in inlineCandidates) {
                                    val file = File(path)
                                    if (!file.isFile || file.length() <= 0L) {
                                        skipped.add("${file.name}：文件不可读，仅落盘")
                                        continue
                                    }
                                    val raw = runCatching { file.readBytes() }.getOrNull()
                                    if (raw == null || raw.isEmpty()) {
                                        skipped.add("${file.name}：文件读取失败，仅落盘")
                                        continue
                                    }
                                    var bytes = raw
                                    var mediaType = mime
                                    if (raw.size.toLong() > TOOL_INLINE_RAW_LIMIT) {
                                        val compressed = compressImageForInline(raw, 1536, 85)
                                            ?: compressImageForInline(raw, 1280, 80)
                                        if (compressed == null || compressed.isEmpty() ||
                                            compressed.size.toLong() > TOOL_INLINE_RAW_LIMIT
                                        ) {
                                            skipped.add(
                                                "${file.name}（${formatImageSize(raw.size.toLong())}）：" +
                                                    "压缩后仍超过 1.5MB，未内联，仅保存到会话文件"
                                            )
                                            continue
                                        }
                                        bytes = compressed
                                        mediaType = "image/jpeg"
                                    }
                                    if (used + bytes.size.toLong() > TOOL_INLINE_TOTAL_LIMIT) {
                                        skipped.add(
                                            "${file.name}（${formatImageSize(bytes.size.toLong())}）：" +
                                                "多图内联合计超过 6MB，未内联，仅保存到会话文件"
                                        )
                                        continue
                                    }
                                    used += bytes.size.toLong()
                                    encoded.add(
                                        Triple(
                                            mediaType,
                                            bytes,
                                            android.util.Base64.encodeToString(
                                                bytes,
                                                android.util.Base64.NO_WRAP
                                            )
                                        )
                                    )
                                }
                                encoded to skipped
                            }
                            inlineEncoded = pack.first
                            if (pack.second.isNotEmpty()) {
                                inlineNotice = buildString {
                                    append("图片内联提示（模型看不到下列图片，仅落盘）：")
                                    pack.second.forEach { append('\n').append("- ").append(it) }
                                }
                            }
                        }
                        // 回给模型的文本：正文最多 TOOL_OUTPUT_LIMIT（64KB 字符），再拼落盘/内联提示
                        val resultTextForModel = buildString {
                            append(resultText.take(TOOL_OUTPUT_LIMIT))
                            if (inlineNotice.isNotBlank()) append("\n\n").append(inlineNotice)
                            if (pythonOutputHint.isNotBlank()) append(pythonOutputHint)
                        }
                        val resultBlocks = JSONArray()
                        resultBlocks.put(
                            JSONObject().put("type", "text")
                                .put("text", resultTextForModel)
                        )
                        inlineEncoded.forEach { (mime, bytes, b64) ->
                            // 压缩/预算都过完后再兜一层：至少保证是图片且不是空字节
                            if (bytes.isNotEmpty()) {
                                resultBlocks.put(
                                    JSONObject().put("type", "image").put(
                                        "source",
                                        JSONObject()
                                            .put("type", "base64")
                                            .put("media_type", mime)
                                            .put("data", b64)
                                    )
                                )
                            }
                        }
                        resultContent.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", u.id)
                                .put(
                                    "content",
                                    if (resultBlocks.length() > 1 || inlineNotice.isNotBlank() ||
                                        pythonOutputHint.isNotBlank()
                                    ) {
                                        resultBlocks
                                    } else {
                                        resultText.take(TOOL_OUTPUT_LIMIT)
                                    }
                                )
                                .put("is_error", !ok)
                        )
                    }
                    if (limitHit) {
                        steps.add(Step.Text("\n\n" + L.format(L.chat.modelToolLimit, s0.toolRounds)))
                        push(true)
                        return
                    }
                    if (assistantContent.length() == 0) {
                        push(true)
                        return
                    }
                    val nextExtra = extra + JSONObject()
                        .put("role", "assistant")
                        .put(
                            "content",
                            if (RAW_REPLAY && assistantRaw.isNotBlank()) {
                                runCatching { JSONArray(assistantRaw) }.getOrDefault(assistantContent)
                            } else {
                                assistantContent
                            }
                        ) + JSONObject()
                        .put("role", "user")
                        .put("content", resultContent)
                    liveRawChain.clear()
                    liveRawChain.addAll(nextExtra)
                    stream(nextExtra)
                }
            }

            val call = runCatching {
                // 组请求体（含 base64 大字符串JSON 序列化）也放 IO：别把几 MB 的串拼在主线程上
                withContext(Dispatchers.IO) {
                    api.streamChat(
                        model = s0.modelName.ifBlank { "deepseek-flash" },
                        systemPrompt = systemWithMeta(s0.systemPrompt),
                        history = historyTurns,
                        prompt = promptWithOcr,
                        promptImages = promptImages,
                        promptReminder = reminderText,
                        // C：请求级工具开关由 ToolRegistry.requestConfig() 统一生成（启+ 权限过滤
requestTools = requestTools,
                        maxRounds = requestMaxRounds,
                        // 官方文件解析：本条消息用 document 内容块引file_id（自动带 files-api beta 头）
                        documents = promptDocs,
                        requestTag = "$cacheTurnNo.1",
                        onEvent = { ev ->
                            if (ev is ApiEvent.AssistantRaw) lastAssistantRaw = ev.rawJson
                            ChatRuntime.scope.launch {
                                if (!ChatRuntime.isCurrent(myGen)) return@launch
                                when (ev) {
                                    is ApiEvent.AssistantRaw -> Unit
                                    is ApiEvent.BlockStart -> {
                                        steps.add(ev.step)
                                        indexPos[ev.index] = steps.lastIndex
                                        push(false)
                                    }

                                    is ApiEvent.Delta -> {
                                        val pos = indexPos[ev.index] ?: return@launch
                                        steps[pos] = when (val st = steps[pos]) {
                                            is Step.Thinking -> st.copy(text = st.text + ev.text)
                                            is Step.Text -> st.copy(text = st.text + ev.text)
                                            else -> st
                                        }
                                        push(false)
                                    }

                                    is ApiEvent.Usage -> usage = ev

                                    is ApiEvent.ToolUseStart -> Unit
                                    is ApiEvent.ToolUseEnd -> pendingToolUses.add(
                                        PendingToolUse(ev.id, ev.name, ev.inputJson)
                                    )

                                    is ApiEvent.Title -> {
                                        // 服务端顺带给的会话标题：只在标题还是首条消息自动截断「新对话」时替换                                        // 不覆盖用户手动改过的标题
                                        val cid = app.currentId ?: return@launch
                                        val conv = app.conversations.firstOrNull { it.id == cid }
                                            ?: return@launch
                                        val auto = userMsg.text.replace('\n', ' ').take(18)
                                        if (conv.title == auto || L.isDefaultChatTitle(conv.title)) {
                                            app.rename(cid, ev.title.take(40))
                                        }
                                    }

                                    is ApiEvent.Done -> {
                                        // 官方内容安全：stop_reason=refusal 时删除已展示正文，换成拒答文案；
                                        // 图片被拦stop_details.explanation 会写明是哪几张图，一并展
if (ev.stopReason == "refusal") {
                                            // 先清掉已流式展示的步骤正文，push(true) 会写拒答文案
                                            steps.clear()
                                            indexPos.clear()
                                            val base = L.feat.refusalBase
                                            refusal = if (ev.stopExplanation.isNullOrBlank()) {
                                                base
                                            } else {
                                                L.format(L.feat.refusalWithDetail, base, ev.stopExplanation)
                                            }
                                            push(true)
                                        } else if (pendingToolUses.isNotEmpty()) {
                                            // 4a-2：先把工具执行完并回传，再继续下一轮；此时不要结束气泡
                                            val uses = pendingToolUses.toList()
                                            pendingToolUses.clear()
                                            push(false)
                                            ChatRuntime.scope.launch { ToolLoop.continueWithTools(uses, emptyList(), lastAssistantRaw) }
                                        } else {
                                            if (lastAssistantRaw.isNotBlank()) {
                                                liveRawChain.add(
                                                    JSONObject().put("role", "assistant")
                                                        .put("content", JSONArray(lastAssistantRaw))
                                                )
                                            }
                                            finishWithTruncation(ev.stopReason)
                                        }
                                    }

                                    is ApiEvent.InputBlocked -> {
                                        // HTTP 400 Content Exists Risk：输入被审核拦截，明确提示（不叫「请求失败」）
                                        toast(ev.message)
                                        app.updateMessage(aiId) {
                                            it.copy(
                                                text = ev.message,
                                                status = ChatMsg.STATUS_REFUSED,
                                                at = System.currentTimeMillis()
                                            )
                                        }
                                    }

                                    is ApiEvent.Limit -> {
                                        // 服务端轮+ 客户端工具轮次统一计数后再提示（E12
steps.add(
                                            Step.Text(
                                                "\n\n" + L.format(L.chat.modelToolLimit, ev.rounds + clientToolCalls)
                                            )
                                        )
                                        push(true)
                                    }

                                    is ApiEvent.Failed -> {
                                        // 用户主动「停止」时 OkHttp 也会Canceled 失败收尾，这种不弹报
if (!isCancelledMessage(ev.message)) {
                                            toast(L.format(L.chat.toastRequestFailed, ev.message.take(120)))
                                        }
                                        app.updateMessage(aiId) {
                                            it.copy(
                                                text = L.format(L.chat.toastRequestFailed, ev.message),
                                                status = ChatMsg.STATUS_FAILED,
                                                at = System.currentTimeMillis()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }
            call.onFailure { e ->
                // 组请求体就失败了（比JSON 序列化异常）：也不能让回复卡在「生成中
val msg = e.message ?: L.chat.requestBuildFailed
                toast(L.format(L.chat.toastRequestFailed, msg.take(120)))
                app.updateMessage(aiId) {
                    it.copy(
                        text = L.format(L.chat.toastRequestFailed, msg),
                        status = ChatMsg.STATUS_FAILED,
                        at = System.currentTimeMillis()
                    )
                }
            }
            ChatRuntime.call = call.getOrNull()
        }
    }

    fun stop() {
        // 进程级运行时统一取消：断流 + 作废 token + 取消协程（离开页面后回来也能停）
        ChatRuntime.cancelCurrent()
        imageGenStartedAt = 0L
        // 停止时同时取消正在跑的 TTS（synthesize/speak），否则语音气泡会卡在「合成中…」
        runCatching { tts.stop() }
        synthesizingId = null
        stopVoice()
        // 用户主动停止生成：这条不完整的回复不做语音，也不自动播放
        autoSpeakId = null
        val wip = messages.lastOrNull { it.status == ChatMsg.STATUS_WIP }
        if (wip != null) {
            val body = speakableText(wip)
            // D97：停止时如果本轮工具链里 assistant(tool_use) 已发出但还没有配对的 tool_result，
            // 下一轮请求会 400「工具结果未配对」。这里补齐占位 tool_result 再落库；
            // 已完整配对的链也顺手落库，避免停止后模型忘记刚做过的工具调用（D92）。
            val repairedChain = if (RAW_REPLAY && liveRawChain.isNotEmpty()) {
                sanitizeChainForStore(
                    repairDanglingToolUses(liveRawChain.toList(), L.chat.toolStoppedResult)
                )
            } else ""
            app.updateMessage(wip.id) {
                it.copy(
                    status = ChatMsg.STATUS_STOPPED,
                    voice = false,
                    text = if (body.isBlank()) L.chat.stoppedEmpty else it.text,
                    rawChain = if (repairedChain.isNotBlank()) repairedChain else it.rawChain,
                    // 停止时撤掉生图的「生成中…」占位卡，避免一直转圈
                    steps = it.steps.filterNot { s ->
                        s is Step.Download && s.status == "generating"
                    }
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ChatWall(app)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // ---- 顶栏 ----
            Row(
                Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Icons.Rounded.Menu, {
                    closeKeyboard()
                    drawer = true
                }, tint = if (hasWall) Color.White else c.text2)
                Spacer(Modifier.width(6.dp))
                Text(
                    s.modelNickname,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasWall) Color.White else c.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                // 第三期：本会话权限（工具全局可用，权限按会话
IconBtn(Icons.Rounded.Shield, {
                    closeKeyboard()
                    permMenu = true
                }, tint = if (hasWall) Color.White else c.text2)
                Spacer(Modifier.width(2.dp))
                IconBtn(Icons.Rounded.MoreVert, { closeKeyboard(); menu = true }, tint = if (hasWall) Color.White else c.text2)
            }

            // ---- 消息----
            if (messages.isEmpty()) {
                EmptyState(
                    nickname = s.modelNickname,
                    onPick = { input = it },
                    hasWall = hasWall,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageRow(
                            m = m,
                            myAvatar = s.myAvatar,
                            modelAvatar = s.modelAvatar,
                            onWall = hasWall,
                            speaking = speakingId == m.id,
                            // 只看消息自身的标记：一旦这条回复生成时是语音消息，就永远按语音气泡显示                            // 「语音合成」开关只影响下一条回复，不回头改历史消息的形
voiceMode = m.voice,
                            voicePlaying = speakingId == m.id,
                            voiceProgress = if (speakingId == m.id) playProgress else 0f,
                            voiceSynthesizing = synthesizingId == m.id,
                            voiceDurationMs = durations[m.id] ?: 0,
                            voiceFailed = voiceFailed[m.id] == true,
                            onVoiceClick = {
                                when {
                                    speakingId == m.id -> stopVoice()
                                    synthesizingId == m.id -> toast(L.chat.voiceSynthesizing)
                                    // 第三期：需要重新合成时先检查「语音合成」工具开关；
                                    // 缓存还在的历史语音仍可直接播放，不受开关影                                    !ttsEnabled && !tts.fileFor(m.id).exists() -> toast(L.chat.toolOffTts)
                                    else -> voiceMessage(m)
                                }
                            },
                            // 展开/收起「转文字」后要重新贴底，否则展开的正文在视口
onToggleVoiceText = {
                                scope.launch {
                                    delay(80)
                                    stickToBottom()
                                    delay(120)
                                    stickToBottom()
                                }
                            },
                            // 点气泡里的图打开全屏查看器（带这条消息的全部图片 + 当前索引
onOpenImage = { imgs, index ->
                                closeKeyboard()
                                imageViewer = imgs to index
                            },
                            onCopy = {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                val copyText = m.text.ifBlank {
                                    when {
                                        m.images.isNotEmpty() -> L.chat.imagePlaceholder
                                        m.lat != null && m.lng != null -> L.chat.locationPlaceholder
                                        else -> ""
                                    }
                                }
                                cm.setPrimaryClip(ClipData.newPlainText("DChat", copyText))
                                toast(L.chat.copied)
                            },
                            onSpeak = {
                                if (!ttsEnabled) {
                                    toast(L.chat.toolOffTts)
                                } else if (speakingId == m.id) {
                                    tts.stop()
                                    speakingId = null
                                } else {
                                    val body = speakableText(m)
                                    if (body.isBlank()) {
                                        toast(L.chat.noSpeakableText)
                                    } else {
                                        speakingId = m.id
                                        tts.speak(m.id.toString(), body)
                                    }
                                }
                            },
                            // 1.0.3：生图占位卡显示已用时 + 卡片内「取消」（调现有 stop()）
                            generatingStartedAt = if (m.status == ChatMsg.STATUS_WIP) imageGenStartedAt else 0L,
                            onStopGenerating = if (m.status == ChatMsg.STATUS_WIP) ({ stop() }) else null,
                        )
                    }
                }
            }

            // ---- 输入----
            Composer(
                input = input,
                onInputChange = { input = it },
                thinking = thinking,
                onThinking = { setThinking(!thinking) },
                search = search,
                onSearch = { setSearch(!search) },
                tts = ttsEnabled,
                onTts = { setTts(!ttsEnabled) },
                voice = voice,
                onToggleVoice = { voice = !voice },
                recording = recording,
                voiceHint = voicePartial,
                onRecording = { rec ->
                    if (rec) {
                        // 第三期：语音识别是注册表工具，关闭或当前会话权限不足时不开始录
if (ensureToolUsable("server.asr", L.chat.toolOffAsr)) {
                            // 同一次按压只启动一次：重复的按下事件直接忽
if (!voicePressed) {
                                voicePressed = true
                                recording = true
                                val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    voicePartial = null
                                    asr.start()
                                } else {
                                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    } else {
                        voicePressed = false
                        recording = false
                        asr.stop()
                    }
                },
                onPlus = { closeKeyboard(); sheet = true },
        onSend = {
                    // 图片 + 文字 + 服务端解析文档一起发；只有图文件没文字也能发
                    val images = pendingImages.toList()
                    val docs = pendingDocs.toList()
                    send(input, images = images, docs = docs)
                    input = ""
                    pendingImages.clear()
                    pendingDocs.clear()
                },
                generating = messages.any { it.status == ChatMsg.STATUS_WIP },
                onStop = { stop() },
                pendingImages = pendingImages,
                onRemoveImage = { index ->
                    if (index in pendingImages.indices) {
                        val removed = pendingImages.removeAt(index)
                        scope.launch(Dispatchers.IO) {
                            runCatching { File(removed).delete() }
                        }
                    }
                },
                onVoiceRelease = {
                    // 最终识别结果是异步回来的，asr.onFinal 写回输入框：
                    // 这里不再伪造一条消息，也不做同步判
},
                onFocusChanged = { inputFocused = it },
                nickname = s.modelNickname,
                hasWall = hasWall
            )
            Spacer(Modifier.height(4.dp))
        }

        // 更多菜单：贴着右上角「⋮」正下方。之前用 Popup + 固定偏移，在不同机型/insets 下会飘很远，
        // 改成布局内浮层，位置由自己控
if (menu) {
            val convIdForTasks = app.currentId
            FileMenu(
                bgCount = if (convIdForTasks != null) BgTasks.countFor(convIdForTasks) else 0,
                scheduleCount = if (convIdForTasks != null) ScheduleStore.countFor(convIdForTasks) else 0,
                onDismiss = { menu = false },
                onExport = {
                    menu = false
                    val conv = app.current()
                    if (conv != null) {
                        // 真正只导出当前会话，文件名带会话标题
                        exportTarget = listOf(conv)
                        exportLauncher.launch(
                            DeepSeekZip.suggestedFileName(L.conversationTitle(conv.title))
                        )
                    }
                },
                onImport = {
                    menu = false
                    importLauncher.launch(arrayOf("*/*"))
                },
                onOpenBgTasks = {
                    menu = false
                    onOpenBgTasks()
                },
                onOpenSchedule = {
                    menu = false
                    onOpenSchedule()
                },
                onOpenSessionFiles = {
                    menu = false
                    val cid = app.currentId
                    if (cid != null) onOpenFiles(cid)
                    onOpenSessionFiles()
                }
            )
        }

        // 第三期：本会话权限浮层（与「⋮」同风格的布局内浮层，不用 Popup
if (permMenu) {
            val cid = app.currentId
            PermissionMenu(
                current = currentPermission(),
                onDismiss = { permMenu = false },
                onPick = { mode ->
                    if (cid != null) app.setPermission(cid, mode)
                    permMenu = false
                }
            )
        }

        // 第三期：一次性工具审批弹窗（文件解析/OCR
val pendingApproval = ToolApproval.pending
        if (pendingApproval != null) {
            ToolApprovalDialog(
                request = pendingApproval,
                onAllow = { ToolApproval.complete(true) },
                onReject = { ToolApproval.complete(false) }
            )
        }

        DrawerOverlay(
            visible = drawer,
            app = app,
            onDismiss = { drawer = false },
            onOpenSettings = {
                drawer = false
                onOpenSettings()
            },
            onOpenUsage = {
                drawer = false
                onOpenUsage()
            },
            onRenameConversation = { conv -> renameTarget = conv },
            onDeleteConversation = { conv -> deleteTarget = conv }
        )

        PlusSheet(
            visible = sheet,
            onDismiss = { sheet = false },
            thinking = thinking,
            search = search,
            onPickFile = {
                sheet = false
                // 不用 MIME 过滤：不同厂商的文件提供方给 .txt/.md 报的 MIME 不一致，
                // 过滤会把正常文件挡掉；文本类本地读取，PDF/Word/PPT/图片等由 FileText 走服务端解析
                filePicker.launch(arrayOf("*/*"))
            },
            onPickAlbum = {
                sheet = false
                if (ensureToolUsable("local.gallery", L.chat.toolOffGallery)) {
                    albumPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            },
            onTakePhoto = {
                sheet = false
                if (ensureToolUsable("local.camera", L.chat.toolOffCamera)) {
                    launchCameraApp()
                }
            },
            onLocate = {
                sheet = false
                if (ensureToolUsable("local.location", L.chat.toolOffLocation)) {
                    requestLocation()
                }
            }
        )

        // 定位确认：先展示地址卡片，确认后发一条位置消息（不需要文字输入）
        if (showLocationConfirm) {
            val loc = pendingLocation
            AlertDialog(
                onDismissRequest = {
                    showLocationConfirm = false
                    pendingLocation = null
                },
                containerColor = c.bg,
                title = { Text(L.chat.locationSendTitle) },
                text = {
                    Column {
                        if (loc != null) {
                            LocationMessageCard(
                                place = loc.title,
                                detail = loc.detail,
                                lat = loc.lat,
                                lng = loc.lng,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(L.chat.locationSendHint, fontSize = 11.5.sp, color = c.text3)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val place = pendingLocation
                        showLocationConfirm = false
                        pendingLocation = null
                        if (place != null) send("", place = place)
                    }) { Text(L.chat.locationSendAction) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showLocationConfirm = false
                        pendingLocation = null
                    }) { Text(L.common.cancel) }
                }
            )
        }

        // 重命名：抽屉行「⋯」里选中哪个会话就改哪个，不再只作用于当前会
val renameConv = renameTarget
        if (renameConv != null) {
            RenameDialog(
                current = renameConv.title,
                onDismiss = { renameTarget = null }
            ) { title ->
                app.rename(renameConv.id, title.ifBlank { L.chat.drawerNewChat })
                renameTarget = null
            }
        }

        // 删除会话：先二次确认，确认后交给 AppState.delete（删的是当前会话时它会切到列表第一个，
        // 删到一个不剩时会新建一个空会话，和原行为保持一致）
        val deleteConv = deleteTarget
        if (deleteConv != null) {
            val (fileCount, fileBytes) = remember(deleteConv.id) { app.workspaceStats(deleteConv.id) }
            DeleteChatDialog(
                title = L.chat.menuDeleteChat,
                hint = L.chat.deleteChatHint + "\n" +
                    L.format(L.chat.deleteChatFilesHint, fileCount, formatDownloadSize(fileBytes)),
                confirmLabel = L.chat.deleteChatAction,
                onDismiss = { deleteTarget = null },
                onConfirm = {
                    app.delete(deleteConv.id)
                    deleteTarget = null
                }
            )
        }

        // 全屏看图：最外层浮层，盖住顶栏和输入区；返回键由查看器自己先消费
        val viewer = imageViewer
        if (viewer != null) {
            ImageViewer(
                images = viewer.first,
                initialIndex = viewer.second,
                onDismiss = { imageViewer = null }
            )
        }
    }
}

/* ---------------- 背景 ---------------- */

@Composable
private fun ChatWall(app: AppState) {
    val s = app.settings
    val c = LocalColors.current
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (s.bgImage != null) {
            val bmp = remember(s.bgImage) { bitmapOf(s.bgImage) }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = if (s.bgScale == 1) ContentScale.FillBounds else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(Brush.linearGradient(defaultWall))
                )
            }
        }
    }
}

private fun bitmapOf(path: String?): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        if (path.isNullOrBlank()) null
        else android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
    }.getOrNull()

/* ---------------- 空---------------- */

@Composable
private fun EmptyState(
    nickname: String,
    onPick: (String) -> Unit,
    hasWall: Boolean,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    // 有背景图时文字改白色 + 阴影，否则压在图上看不清
    val titleStyle = androidx.compose.ui.text.TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = if (hasWall) Color.White else c.text1,
        shadow = if (hasWall) androidx.compose.ui.graphics.Shadow(
            color = Color(0x99000000),
            offset = androidx.compose.ui.geometry.Offset(0f, 2f),
            blurRadius = 8f
        ) else null
    )

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(21.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8FA3FF), Color(0xFF4D6BFE)))),
            contentAlignment = Alignment.Center
        ) {
            Text("D", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Text(L.format(L.chat.emptyGreeting, nickname), style = titleStyle)
        Spacer(Modifier.height(8.dp))
        Text(
            L.chat.emptySubtitle,
            fontSize = 13.sp,
            color = if (hasWall) Color.White else c.text3,
            lineHeight = 21.sp,
            style = if (hasWall) androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(
                    color = Color(0x99000000),
                    offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                    blurRadius = 6f
                )
            ) else androidx.compose.ui.text.TextStyle.Default
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SuggestionCard(
                L.chat.suggestWritingTitle,
                L.chat.suggestWritingDesc,
                Icons.Rounded.Edit,
                Modifier.weight(1f)
            ) {
                onPick(L.chat.suggestWritingPrompt)
            }
            SuggestionCard(
                L.chat.suggestCodeTitle,
                L.chat.suggestCodeDesc,
                Icons.Rounded.Code,
                Modifier.weight(1f)
            ) {
                onPick(L.chat.suggestCodePrompt)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SuggestionCard(
                L.chat.suggestSearchTitle,
                L.chat.suggestSearchDesc,
                Icons.Rounded.Public,
                Modifier.weight(1f)
            ) {
                onPick(L.chat.suggestSearchPrompt)
            }
            SuggestionCard(
                L.chat.suggestFileTitle,
                L.chat.suggestFileDesc,
                Icons.Rounded.InsertDriveFile,
                Modifier.weight(1f)
            ) {
                onPick(L.chat.suggestFilePrompt)
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.bg)
            .border(1.dp, c.line2, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(c.bg3),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = c.brand, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
        Spacer(Modifier.height(3.dp))
        Text(desc, fontSize = 11.5.sp, color = c.text3, lineHeight = 16.sp)
    }
}

/* ---------------- 消息 ---------------- */

@Composable
private fun MessageRow(
    m: ChatMsg,
    myAvatar: String?,
    modelAvatar: String?,
    onWall: Boolean,
    speaking: Boolean,
    voiceMode: Boolean,
    voicePlaying: Boolean,
    voiceProgress: Float,
    voiceSynthesizing: Boolean,
    voiceDurationMs: Int,
    voiceFailed: Boolean,
    onVoiceClick: () -> Unit,
    onOpenImage: (List<String>, Int) -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onToggleVoiceText: () -> Unit = {},
    /** 1.0.3：生图占位卡的开始时刻（elapsedRealtime 毫秒）；0 = 不显示已用时 */
    generatingStartedAt: Long = 0L,
    /** 1.0.3：生图占位卡里的「取消」，调用现有 stop()；null = 不显示按钮 */
    onStopGenerating: (() -> Unit)? = null
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val bubbleShapeMe = RoundedCornerShape(topStart = 18.dp, topEnd = 5.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    val bubbleShapeAi = RoundedCornerShape(topStart = 5.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)

    if (m.fromMe) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top
        ) {
            if (m.lat != null && m.lng != null) {
                // 位置消息：气泡本身就QQ 式位置卡片，点开系统地图
                LocationMessageCard(
                    place = m.place,
                    detail = m.placeDetail,
                    lat = m.lat ?: 0.0,
                    lng = m.lng ?: 0.0,
                    modifier = Modifier.widthIn(max = 280.dp),
                    text = m.text
                )
            } else {
                Box(
                    Modifier
                        .widthIn(max = 280.dp)
                        .clip(bubbleShapeMe)
                        .background(c.bg3)
                        .border(1.dp, c.line2, bubbleShapeMe)
                        .padding(horizontal = 13.dp, vertical = 10.dp)
                ) {
                    if (m.images.isEmpty()) {
                        Text(m.text, fontSize = 14.5.sp, color = c.text1, lineHeight = 23.sp)
                    } else {
                        Column {
                            // 图片在气泡内 张单列/4 张两列、其余三列网
ChatImageGrid(m.images) { index -> onOpenImage(m.images, index) }
                            if (m.text.isNotBlank()) {
                                Spacer(Modifier.height(7.dp))
                                Text(m.text, fontSize = 14.5.sp, color = c.text1, lineHeight = 23.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Avatar(myAvatar, 32.dp)
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Avatar(modelAvatar, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                if (voiceMode) {
                    // QQ 式语音消息：扬声+ 波形 + 时长，文字默认收
VoiceMessageBubble(
                        m = m,
                        playing = voicePlaying,
                        progress = voiceProgress,
                        synthesizing = voiceSynthesizing,
                        durationMs = voiceDurationMs,
                        failed = voiceFailed,
                        onClick = onVoiceClick,
                        onToggleText = onToggleVoiceText
                    )
                } else {
                    if (m.steps.isNotEmpty()) {
                        Column(
                            Modifier
                                .widthIn(max = 320.dp)
                                .clip(bubbleShapeAi)
                                .background(c.bg2)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            StepTimeline(
                                m.steps,
                                m.status == ChatMsg.STATUS_WIP,
                                onOpenImage,
                                generatingStartedAt = generatingStartedAt,
                                onStopGenerating = onStopGenerating
                            )
                        }
                    } else {
                        Column(
                            Modifier
                                .widthIn(max = 292.dp)
                                .clip(bubbleShapeAi)
                                .background(c.bg2)
                                .padding(horizontal = 13.dp, vertical = 10.dp)
                        ) {
                            MarkdownText(
                                m.text,
                                color = if (m.status == ChatMsg.STATUS_FAILED) c.danger else c.text1,
                                showCursor = m.status == ChatMsg.STATUS_WIP
                            )
                        }
                    }
                    if (m.status == ChatMsg.STATUS_WIP) {
                        Spacer(Modifier.height(4.dp))
                        GeneratingDots(c.brand)
                    }
                    if (m.status == ChatMsg.STATUS_STOPPED) {
                        Spacer(Modifier.height(4.dp))
                        Text(L.chat.stopped, fontSize = 11.sp, color = c.text3)
                    }
                    if (m.steps.isNotEmpty() || m.text.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MessageAction(Icons.Rounded.ContentCopy, L.chat.actionCopy, onCopy)
                            Spacer(Modifier.width(10.dp))
                            MessageAction(
                                if (speaking) Icons.Rounded.Stop else Icons.Rounded.VolumeUp,
                                if (speaking) L.chat.actionStopRead else L.chat.actionRead,
                                onSpeak
                            )
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Rounded.Refresh, null, tint = c.text3, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = c.text3, modifier = Modifier.size(15.dp))
    }
}

/* ---------------- 图片消息 ---------------- */

private val chatPhotoCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

/** 按目标像素采样解码聊天图片，避免每次滚动都解 1080p 原图 */
private fun decodeChatPhoto(path: String, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    android.graphics.BitmapFactory.decodeFile(
        path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    )?.asImageBitmap()
}.getOrNull()

@Composable
private fun ChatPhoto(
    path: String,
    modifier: Modifier = Modifier,
    targetPx: Int = 512,
    onClick: (() -> Unit)? = null
) {
    val c = LocalColors.current
    val key = "$targetPx:$path"
    val bmp = remember(key) {
        chatPhotoCache[key] ?: decodeChatPhoto(path, targetPx)?.also { chatPhotoCache[key] = it }
    }
    val bodyModifier = if (onClick != null) modifier.clickable { onClick() } else modifier
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = bodyModifier
        )
    } else {
        Box(bodyModifier.background(c.bg3), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Image, null, tint = c.text3, modifier = Modifier.size(20.dp))
        }
    }
}

/** 用户气泡里的图片网格 200dp/4 张两列，其余三列；圆角裁切，最大宽254dp */
@Composable
private fun ChatImageGrid(paths: List<String>, onImageClick: (Int) -> Unit) {
    if (paths.isEmpty()) return
    val gap = 3.dp
    val columns = when {
        paths.size == 1 -> 1
        paths.size == 2 || paths.size == 4 -> 2
        else -> 3
    }
    val gridWidth = if (columns == 1) 200.dp else 254.dp
    val cell = (gridWidth - gap * (columns - 1)) / columns
    Column(
        modifier = Modifier.width(gridWidth),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        paths.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEachIndexed { columnIndex, path ->
                    ChatPhoto(
                        path = path,
                        modifier = Modifier.size(cell).clip(RoundedCornerShape(7.dp)),
                        targetPx = 512,
                        onClick = { onImageClick(rowIndex * columns + columnIndex) }
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.size(cell)) }
            }
        }
    }
}

/* ---------------- 位置消息卡片 ---------------- */

@Composable
private fun LocationMessageCard(
    place: String,
    detail: String,
    lat: Double,
    lng: Double,
    modifier: Modifier = Modifier,
    text: String = ""
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current
    fun openMap() {
        val label = place.ifBlank { detail }.ifBlank { L.chat.locationLabel }
        val geo = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, L.chat.locationOpenFailed, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.bg2)
            .border(1.dp, c.line2, RoundedCornerShape(14.dp))
            .clickable { openMap() }
            .padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(c.bg3),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Place,
                    contentDescription = L.chat.locationLabel,
                    tint = c.brand,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    place.ifBlank { L.chat.locationLabel },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        fontSize = 11.5.sp,
                        color = c.text3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(15.dp))
        }
        if (text.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(text, fontSize = 14.5.sp, color = c.text1, lineHeight = 23.sp)
        }
    }
}

/* ---------------- 待发送图片缩略图 ---------------- */

@Composable
private fun PendingImageStrip(images: List<String>, onRemove: (Int) -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        images.forEachIndexed { index, path ->
            Box(Modifier.size(64.dp)) {
                ChatPhoto(
                    path = path,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    targetPx = 192
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(c.danger)
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = L.chat.removeImageDesc,
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

private fun formatCoord(value: Double): String = String.format(Locale.US, "%.6f", value)

/* ---------------- 语音消息气泡 ---------------- */

@Composable
private fun VoiceMessageBubble(
    m: ChatMsg,
    playing: Boolean,
    progress: Float,
    synthesizing: Boolean,
    durationMs: Int,
    failed: Boolean,
    onClick: () -> Unit,
    onToggleText: () -> Unit = {}
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val bubbleShapeAi = RoundedCornerShape(topStart = 5.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val secs = if (durationMs > 0) (durationMs / 1000f).roundToInt().coerceAtLeast(1) else 0
    // QQ 一样按语音长度把气泡拉长，最320dp
    val bubbleWidth = if (secs in 1..18) (150 + secs * 9).dp else 176.dp

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .widthIn(max = 320.dp)
                .width(bubbleWidth.coerceAtMost(320.dp))
                .clip(bubbleShapeAi)
                .background(c.bg2)
                .clickable { onClick() }
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (playing) Icons.Rounded.Stop else Icons.Rounded.VolumeUp,
                contentDescription = if (playing) L.chat.actionStopVoice else L.chat.actionSpeakVoice,
                tint = if (playing) c.brand else c.text2,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(10.dp))
            when {
                m.status == ChatMsg.STATUS_STOPPED ->
                    Text(L.chat.stopped, fontSize = 12.5.sp, color = c.text3)
                synthesizing -> Text(L.chat.voiceSynthesizing, fontSize = 12.5.sp, color = c.text3)
                failed -> Text(L.chat.voiceUnavailable, fontSize = 12.5.sp, color = c.text3)
                else -> {
                    VoiceWave(progress = if (playing) progress else 0f, active = playing)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (secs > 0) "${secs} 秒" else L.chat.voiceLabel,
                        fontSize = 12.5.sp,
                        color = if (playing) c.brand else c.text3,
                        fontWeight = if (playing) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .clickable {
                    expanded = !expanded
                    if (expanded) onToggleText()
                }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                tint = c.text3,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                if (expanded) L.chat.voiceCollapseText else L.chat.voiceExpandText,
                fontSize = 11.5.sp,
                color = c.text3
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(bubbleShapeAi)
                    .background(c.bg2)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (m.steps.isNotEmpty()) {
                    StepTimeline(m.steps, m.status == ChatMsg.STATUS_WIP)
                } else {
                    MarkdownText(
                        m.text,
                        color = if (m.status == ChatMsg.STATUS_FAILED) c.danger else c.text1
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceWave(progress: Float, active: Boolean) {
    val c = LocalColors.current
    // 静态波形：播放时按进度已播的竖条染成品牌色
    val bars = listOf(6, 11, 17, 9, 20, 13, 7, 16, 10, 18)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5f.dp)
    ) {
        bars.forEachIndexed { i, h ->
            val played = active && progress > (i + 0.5f) / bars.size
            Box(
                Modifier
                    .width(2.5f.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (played) c.brand else c.text3.copy(alpha = 0.38f))
            )
        }
    }
}

/* ---------------- 步骤时间---------------- */

@Composable
private fun StepTimeline(
    steps: List<Step>,
    streaming: Boolean,
    onOpenImage: (List<String>, Int) -> Unit = { _, _ -> },
    /** 1.0.3：生图占位卡开始时刻（elapsedRealtime）；0 = 不显示已用时 */
    generatingStartedAt: Long = 0L,
    /** 1.0.3：生图占位卡上的「取消」 */
    onStopGenerating: (() -> Unit)? = null
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    // 1.0.3：Python/用户工具输出超 64KB 时给「完整输出」入口，点开在弹窗里看全文
    var outputPreviewFile by remember { mutableStateOf<File?>(null) }
    // 同一条消息里所有图片下载：点任意一张都能左右滑
    val downloadImages = steps.filterIsInstance<Step.Download>()
        .filter { it.mime.startsWith("image/") && it.filePath.isNotBlank() && File(it.filePath).isFile }
        .map { it.filePath }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var i = 0
        while (i < steps.size) {
            when (val st = steps[i]) {
                is Step.Thinking -> CollapsibleStep(
                    icon = Icons.Rounded.Lightbulb,
                    title = if (st.text.isBlank()) L.chat.stepThinking else L.chat.stepThought,
                    body = st.text
                )

                is Step.Search -> SourceStepCard(
                    icon = Icons.Rounded.Search,
                    title = if (st.results.isEmpty()) {
                        L.chat.stepSearching
                    } else {
                        L.format(L.chat.stepSearched, st.results.size)
                    },
                    queries = st.queries,
                    sources = st.results
                )

                is Step.Fetch -> SourceStepCard(
                    icon = Icons.Rounded.Public,
                    title = if (st.results.isEmpty() && st.url.isBlank()) {
                        L.chat.stepOpening
                    } else {
                        L.format(L.chat.stepOpened, st.results.size.coerceAtLeast(1))
                    },
                    queries = if (st.url.isBlank()) emptyList() else listOf(st.url),
                    sources = st.results
                )

                is Step.Download -> DownloadStepCard(
                    st,
                    imagePaths = downloadImages,
                    imageIndex = downloadImages.indexOf(st.filePath).coerceAtLeast(0),
                    onOpenImages = onOpenImage,
                    generatingStartedAt = generatingStartedAt,
                    onStopGenerating = onStopGenerating
                )

                is Step.ToolCall -> {
                    CollapsibleStep(
                        icon = Icons.Rounded.Build,
                        title = L.format(L.chat.toolCallTitle, st.toolName.ifBlank { st.toolId }),
                        body = buildString {
                            if (st.args.isNotBlank()) append(L.format(L.chat.toolCallArgs, st.args)).append('\n')
                            if (st.result.isNotBlank()) append(L.format(L.chat.toolCallResult, st.result)).append('\n')
                            if (st.durationMs > 0) append(L.format(L.chat.toolCallDuration, st.durationMs))
                        }.trim(),
                        failed = st.error.isNotBlank(),
                        errorLine = if (st.error.isNotBlank()) {
                            L.format(L.chat.toolCallError, st.error)
                        } else ""
                    )
                    // 1.0.3：Python/用户工具输出 >64KB 时完整内容已落盘，给一个可点入口看全文
                    val fullPath = st.outputPath
                    if (st.error.isBlank() && !fullPath.isNullOrBlank()) {
                        val fullFile = File(fullPath)
                        if (fullFile.isFile) {
                            Text(
                                L.format(
                                    L.chat.pythonFullOutput,
                                    fullFile.name,
                                    formatImageSize(
                                        if (st.outputBytes > 0L) st.outputBytes else fullFile.length()
                                    )
                                ),
                                fontSize = 11.sp,
                                color = c.brand,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { outputPreviewFile = fullFile }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                is Step.Text -> {
                    // 连续text 块其实是同一条回答（工具轮次把它切成了多块）                    // 分开渲染会在接缝处多出换段落，所以合并后一次渲
val sb = StringBuilder()
                    var j = i
                    while (j < steps.size && steps[j] is Step.Text) {
                        sb.append((steps[j] as Step.Text).text)
                        j++
                    }
                    MarkdownText(
                        sb.toString(),
                        color = c.text1,
                        showCursor = streaming && j - 1 == steps.lastIndex
                    )
                    i = j - 1
                }
            }
            i++
        }
    }
    // 完整输出预览弹窗（点「完整输出：python_out.txt（1.2MB）」打开）
    outputPreviewFile?.let { f ->
        TextOutputDialog(file = f, onDismiss = { outputPreviewFile = null })
    }
}

private const val TEXT_PREVIEW_LIMIT = 256 * 1024

/**
 * 1.0.3：工具完整输出的只读文本查看弹窗（Python 输出 >64KB 落盘后的入口）。
 * 超过 [TEXT_PREVIEW_LIMIT] 只显示前 256KB，并在底部注明完整内容在会话文件里。
 */
@Composable
private fun TextOutputDialog(file: File, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    var content by remember(file) { mutableStateOf<String?>(null) }
    var truncated by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        val read = withContext(Dispatchers.IO) {
            runCatching {
                if (file.length() > TEXT_PREVIEW_LIMIT.toLong()) {
                    val bytes = ByteArray(TEXT_PREVIEW_LIMIT)
                    var total = 0
                    file.inputStream().use { input ->
                        while (total < bytes.size) {
                            val n = input.read(bytes, total, bytes.size - total)
                            if (n <= 0) break
                            total += n
                        }
                    }
                    String(bytes, 0, total, Charsets.UTF_8) to true
                } else {
                    file.readText() to false
                }
            }.getOrNull()
        }
        content = read?.first ?: ""
        truncated = read?.second == true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = {
            Text(
                file.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val body = content
                if (body == null) {
                    Text(L.chat.loading, fontSize = 12.sp, color = c.text3)
                } else {
                    Text(
                        body.ifBlank { "—" },
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = c.text2
                    )
                    if (truncated) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            L.format(
                                L.chat.previewTruncated,
                                formatImageSize(TEXT_PREVIEW_LIMIT.toLong())
                            ),
                            fontSize = 10.5.sp,
                            color = c.text3
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(L.common.close, fontSize = 14.sp, color = c.text2)
            }
        }
    )
}

@Composable
private fun DownloadStepCard(
    step: Step.Download,
    imagePaths: List<String> = emptyList(),
    imageIndex: Int = 0,
    onOpenImages: (List<String>, Int) -> Unit = { _, _ -> },
    /** 1.0.3：生图执行中的开始时刻（elapsedRealtime）；>0 时显示「已用时 N 秒」 */
    generatingStartedAt: Long = 0L,
    /** 1.0.3：执行中的「取消」（复用聊天页的 stop()） */
    onStopGenerating: (() -> Unit)? = null
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val file = remember(step.filePath) { File(step.filePath) }
    val failed = step.status != "ok"
    val viewerImages = imagePaths.ifEmpty { listOf(step.filePath) }
    val viewerIndex = imageIndex.coerceIn(0, viewerImages.lastIndex)
    fun openViewer() = onOpenImages(viewerImages, viewerIndex)
    val isImage = step.mime.startsWith("image/") && file.isFile
    // 1.0.3：DownloadTool 在响应缺 Content-Length 时会把 sizeBytes 记成 0（假 0KB）。
    // 展示层兜底取落盘文件的实际长度；仍取不到才显示「大小未知」。
    val actualBytes = remember(step.filePath, step.sizeBytes) {
        if (step.sizeBytes > 0L) step.sizeBytes else runCatching { file.length() }.getOrDefault(0L)
    }
    // 失败卡 / 空路径 / 0 字节：没有可保存/查看的文件，不渲染按钮（只留错误信息）
    val canUseFile = !failed && step.filePath.isNotBlank() && actualBytes > 0L && file.isFile
    val bitmap = remember(step.filePath, file.length()) {
        if (isImage) decodeChatPhoto(step.filePath, 1600) else null
    }
    // 生图执行中的占位卡：转圈 +「生成中… 已用时 N 秒」+「取消」按钮，不显示保存/打开按钮
    if (step.status == "generating") {
        // 秒表：ImageGenTool 目前没暴露「第 K/3 次尝试」，先只显示已用时，等它暴露 K 再接
        var elapsedSec by remember(step, generatingStartedAt) {
            mutableStateOf(
                if (generatingStartedAt > 0L) {
                    ((android.os.SystemClock.elapsedRealtime() - generatingStartedAt) / 1000L).toInt()
                } else 0
            )
        }
        LaunchedEffect(step, generatingStartedAt) {
            if (generatingStartedAt <= 0L) return@LaunchedEffect
            while (true) {
                delay(500)
                elapsedSec =
                    ((android.os.SystemClock.elapsedRealtime() - generatingStartedAt) / 1000L).toInt()
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.bg)
                .border(1.dp, c.line2, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                color = c.brand,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (generatingStartedAt > 0L) {
                    L.format(L.imageGen.generatingElapsed, elapsedSec)
                } else {
                    L.imageGen.generating
                },
                fontSize = 12.sp,
                color = c.text2,
                modifier = Modifier.weight(1f)
            )
            if (onStopGenerating != null) {
                TextButton(onClick = onStopGenerating) {
                    Text(L.common.cancel, fontSize = 12.sp, color = c.text2)
                }
            }
        }
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (failed) c.danger.copy(alpha = 0.06f) else c.bg)
            .border(
                1.dp,
                if (failed) c.danger.copy(alpha = 0.55f) else c.line2,
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
    ) {
        if (isImage && bitmap != null) {
            BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                val ratio = if (bitmap.height > 0) {
                    bitmap.width.toFloat() / bitmap.height.toFloat()
                } else 1f
                val desired = if (ratio > 0f) maxWidth / ratio else maxWidth
                val capped = desired > 320.dp
                val h = if (capped) 320.dp else desired
                Box(Modifier.fillMaxWidth().height(h)) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = file.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable { openViewer() }
                    )
                    if (capped) {
                        Text(
                            "查看完整图片",
                            fontSize = 10.5.sp,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                .clickable { openViewer() }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Description,
                    null,
                    tint = if (failed) c.danger else c.text2,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name.ifBlank { if (failed) L.imageGen.toolName else "download" },
                        fontSize = 12.5.sp,
                        color = if (failed) c.danger else c.text1
                    )
                    if (step.filePath.isNotBlank()) {
                        Text(
                            (if (actualBytes > 0L) formatDownloadSize(actualBytes) else L.chat.sizeUnknown) +
                                " · " + step.mime.ifBlank { "application/octet-stream" },
                            fontSize = 10.5.sp,
                            color = c.text3
                        )
                    }
                }
            }
        }
        if (step.note.isNotBlank()) {
            Text(
                step.note,
                fontSize = 11.sp,
                color = if (failed) c.danger else c.text3,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (canUseFile) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isImage) {
                    TextButton(onClick = { openDownloadedFile(ctx, file, step.mime) }) {
                        Text("打开", fontSize = 11.sp)
                    }
                }
                TextButton(onClick = { saveDownloadToPhone(ctx, file, step.mime) }) {
                    Text(L.chat.downloadSaveToPhone, fontSize = 11.sp)
                }
            }
        }
    }
}

private fun formatDownloadSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else if (bytes >= 1024) "${bytes / 1024} KB" else "$bytes B"

/** 工具结果回填：单张图超过这个大小就先压缩再内联 */
private const val TOOL_INLINE_RAW_LIMIT = 1536L * 1024L

/** 工具结果回填：多图内联合计上限（压缩后原始字节之和） */
private const val TOOL_INLINE_TOTAL_LIMIT = 6L * 1024L * 1024L

private fun formatImageSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) "%.1fMB".format(bytes / 1024.0 / 1024.0)
    else if (bytes >= 1024) "${bytes / 1024}KB" else "$bytes B"

/**
 * 把大图压到适合内联给模型的尺寸：
 * 先只读 bounds 算 inSampleSize 解码，长边仍超 [maxEdge] 再等比缩放，最后压成 JPEG [quality]。
 * 失败/无法解码/OOM 返回 null，由调用方明确告诉模型「未内联，仅落盘」。
 * 注意：只用于 tool_result 回填，**不修改落盘原图**。
 */
private fun compressImageForInline(raw: ByteArray, maxEdge: Int, quality: Int): ByteArray? {
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(
                raw,
                0,
                raw.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            try {
                val longest = maxOf(bmp.width, bmp.height)
                val scaled = if (longest > maxEdge) {
                    val scale = maxEdge.toFloat() / longest
                    android.graphics.Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bmp
                }
                try {
                    val out = java.io.ByteArrayOutputStream()
                    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                } finally {
                    if (scaled !== bmp) scaled.recycle()
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

private fun openDownloadedFile(ctx: android.content.Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, null))
    }.onFailure { Toast.makeText(ctx, it.message ?: "无法打开文件", Toast.LENGTH_SHORT).show() }
}

private fun saveDownloadToPhone(ctx: android.content.Context, file: File, mime: String) {
    if (!file.isFile) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Toast.makeText(ctx, "仅支持 Android 10+ 保存到下载目录", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/DChat")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建下载文件")
        ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("无法写入下载文件")
        Toast.makeText(ctx, "已保存到 下载/DChat", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, it.message ?: "保存失败", Toast.LENGTH_SHORT).show() }
}

/**
 * 1.0.3：联网搜索 / 阅读链接的步骤卡片。
 *
 * 与旧版服务端联网工具的外观保持一致：折叠头 + 展开后的「来源子卡片」；
 * 每张子卡片 = 标题 + 站点 + 摘要 + 链接，点击用**内置浏览器**打开。
 */
/** 来源链接可能是 http://；内置 WebView 默认禁止明文，统一升级到 https 再打开。 */
private fun secureUrl(url: String): String =
    if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

@Composable
private fun SourceStepCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    queries: List<String>,
    sources: List<Source>
) {
    val c = LocalColors.current
    val openUrl = com.dchat.app.ui.LocalUrlOpener.current
    var expanded by remember { mutableStateOf(false) }
    val hasBody = queries.any { it.isNotBlank() } || sources.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg)
            .border(1.dp, c.line2, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasBody) { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = c.text3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                fontSize = 12.5.sp,
                color = c.text2,
                modifier = Modifier.weight(1f)
            )
            if (hasBody) {
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null, tint = c.text3, modifier = Modifier.size(16.dp)
                )
            }
        }
        if (expanded && hasBody) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                if (queries.any { it.isNotBlank() }) {
                    queries.filter { it.isNotBlank() }.forEach { q ->
                        Text(
                            "· $q",
                            fontSize = 11.5.sp,
                            color = c.text3,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                sources.forEachIndexed { index, s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = s.url.isNotBlank()) { openUrl(secureUrl(s.url)) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 前导：地球图标（浅品牌底块）
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(c.brand.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Public,
                                null,
                                tint = c.brand,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.title.ifBlank { s.url },
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.text1,
                                lineHeight = 19.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val site = s.site.orEmpty()
                            val domain = s.url
                                .removePrefix("https://").removePrefix("http://")
                                .substringBefore('/').substringBefore('?')
                            val meta = listOf(site, domain).filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotBlank()) {
                                Text(
                                    meta,
                                    fontSize = 11.sp,
                                    color = c.text3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Rounded.ChevronRight,
                            null,
                            tint = c.text3.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (index < sources.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(0.6.dp).background(c.line2))
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    failed: Boolean = false,
    errorLine: String = ""
) {
    val c = LocalColors.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (failed) c.danger.copy(alpha = 0.06f) else c.bg)
            .border(
                1.dp,
                if (failed) c.danger.copy(alpha = 0.55f) else c.line2,
                RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (failed) c.danger else c.text3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                title,
                fontSize = 12.5.sp,
                color = if (failed) c.danger else c.text2,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null, tint = if (failed) c.danger else c.text3, modifier = Modifier.size(16.dp)
            )
        }
        if (open && (body.isNotBlank() || errorLine.isNotBlank())) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                if (body.isNotBlank()) {
                    Text(
                        body,
                        fontSize = 12.sp,
                        color = c.text3,
                        lineHeight = 18.sp
                    )
                }
                if (errorLine.isNotBlank()) {
                    Text(
                        errorLine,
                        fontSize = 12.sp,
                        color = c.danger,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

/* ---------------- 输入---------------- */

@Composable
private fun GeneratingDots(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "generating-dots")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "generating-dots-alpha"
    )
    Text("• • •", fontSize = 14.sp, color = color.copy(alpha = alpha))
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    thinking: Boolean,
    onThinking: () -> Unit,
    search: Boolean,
    onSearch: () -> Unit,
    tts: Boolean,
    onTts: () -> Unit,
    voice: Boolean,
    onToggleVoice: () -> Unit,
    recording: Boolean,
    voiceHint: String?,
    onRecording: (Boolean) -> Unit,
    onPlus: () -> Unit,
    onSend: () -> Unit,
    generating: Boolean,
    onStop: () -> Unit,
    pendingImages: List<String>,
    onRemoveImage: (Int) -> Unit,
    onVoiceRelease: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    nickname: String,
    hasWall: Boolean
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    var focused by remember { mutableStateOf(false) }
    val pulse = if (generating) {
        val transition = rememberInfiniteTransition(label = "generating")
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "generatingAlpha"
        ).value
    } else 1f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipToggle(L.chat.chipThinking, thinking, Icons.Rounded.Lightbulb, onThinking)
            Spacer(Modifier.width(8.dp))
            ChipToggle(L.chat.chipSearch, search, Icons.Rounded.Public, onSearch)
            Spacer(Modifier.width(8.dp))
            ChipToggle(L.chat.chipTts, tts, Icons.Rounded.VolumeUp, onTts)
        }
        Spacer(Modifier.height(8.dp))
        // 待发送图片预览：输入框正上方，点右上× 删除单张；继续打增删后才点发
if (pendingImages.isNotEmpty()) {
            PendingImageStrip(pendingImages, onRemoveImage)
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(23.dp))
                .background(c.bg3)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Rounded.Add, onPlus, tint = c.text2, iconSize = 21.dp)
            if (voice) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(if (recording) c.brand.copy(alpha = 0.12f) else Color.Transparent)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onRecording(true)
                                    tryAwaitRelease()
                                    onRecording(false)
                                    onVoiceRelease()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        voiceHint?.takeIf { it.isNotBlank() }
                            ?: if (recording) L.chat.releaseToEnd else L.chat.holdToTalk,
                        fontSize = 14.5.sp,
                        color = if (recording || !voiceHint.isNullOrBlank()) c.brand else c.text2,
                        fontWeight = FontWeight.Medium
                    )
                }
                IconBtn(Icons.Rounded.Keyboard, onToggleVoice, tint = c.text2, iconSize = 20.dp)
            } else {
                Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    // 点进来输入时，占位文字立刻消
if (input.isEmpty() && !focused) {
                        Text(
                            L.format(L.chat.composerHint, nickname),
                            fontSize = 14.5.sp,
                            color = c.text3
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.5.sp,
                            color = c.text1
                        ),
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                focused = it.isFocused
                                onFocusChanged(it.isFocused)
                            }
                    )
                }
                if (generating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "• • •",
                            fontSize = 14.sp,
                            color = c.brand.copy(alpha = pulse),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Box(
                            Modifier.size(34.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF5B78FF), Color(0xFF4D6BFE))))
                                .clickable { onStop() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Square,
                                contentDescription = L.chat.generating,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                } else if (input.isBlank() && pendingImages.isEmpty()) {
                    IconBtn(Icons.Rounded.Mic, onToggleVoice, tint = c.text2, iconSize = 21.dp)
                } else {
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF5B78FF), Color(0xFF4D6BFE))))
                            .clickable { onSend() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Send, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/* ---------------- 更多菜单 ---------------- */

@Composable
private fun FileMenu(
    bgCount: Int,
    scheduleCount: Int,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenBgTasks: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSessionFiles: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    // 全屏透明遮罩，点空白处关闭（不抢菜单本身的点击）
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 38.dp, end = 6.dp)
                .width(174.dp)
                .shadow(18.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(c.bg)
                .padding(vertical = 5.dp)
        ) {
            MenuRow(L.chat.menuExport, Icons.Rounded.Upload, onClick = onExport)
            MenuRow(L.chat.menuImport, Icons.Rounded.Download, onClick = onImport)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .height(1.dp)
                    .background(c.line2)
            )
            MenuRow(L.files.title, Icons.Rounded.Folder, onClick = onOpenSessionFiles)
            // 4c v2：任务管理入口（带当前会话数量；0 时置灰但可点进空态页）
            MenuRow(
                label = L.format(L.tasks.menuCount, L.tasks.scheduleTitle, scheduleCount),
                icon = Icons.Rounded.Schedule,
                muted = scheduleCount == 0,
                onClick = onOpenSchedule
            )
            MenuRow(
                label = L.format(L.tasks.menuCount, L.tasks.bgTitle, bgCount),
                icon = Icons.Rounded.Bolt,
                muted = bgCount == 0,
                onClick = onOpenBgTasks
            )
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    muted: Boolean = false,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            tint = if (danger) c.danger else if (muted) c.text3 else c.text2,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 13.5.sp,
            color = if (danger) c.danger else if (muted) c.text3 else c.text1
        )
    }
}

/* ---------------- 本会话权限浮---------------- */

/**
 * 第三期：一次性工具审批弹窗（allowed-once） * 主题色沿App 配色；「拒绝」用 danger 红，其它按钮不用红；
 * 点弹窗外/返回键都onDismissRequest 拒绝（fail-closed） */
@Composable
private fun ToolApprovalDialog(
    request: ToolApprovalRequest,
    onAllow: () -> Unit,
    onReject: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    AlertDialog(
        onDismissRequest = onReject,
        containerColor = c.bg,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                request.toolName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.text1
            )
        },
        text = {
            Column {
                Text(request.action, fontSize = 13.sp, color = c.text1, lineHeight = 19.sp)
                Spacer(Modifier.height(8.dp))
                Text(request.args, fontSize = 11.5.sp, color = c.text3, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(L.chat.approveHint, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(L.chat.approveAllow, color = c.brand, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text(L.chat.approveReject, color = c.danger)
            }
        }
    )
}

/**
 * 顶栏盾牌点开的本会话权限浮层：与「⋮」菜单同风格— * 全屏透明遮罩 + TopEnd 布局内浮层（不用 Popup），点空白关闭 */
@Composable
private fun PermissionMenu(
    current: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
    ) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 38.dp, end = 6.dp)
                .width(252.dp)
                .shadow(18.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(c.bg)
                .padding(vertical = 6.dp)
        ) {
            Text(
                L.chat.permTitle,
                fontSize = 11.5.sp,
                color = c.text3,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            PermissionRow(
                id = Conversation.PERMISSION_READ,
                label = L.chat.permRead,
                desc = L.chat.permReadDesc,
                current = current,
                brand = c.brand,
                onPick = onPick
            )
            PermissionRow(
                id = Conversation.PERMISSION_WORKSPACE,
                label = L.chat.permWorkspace,
                desc = L.chat.permWorkspaceDesc,
                current = current,
                brand = c.brand,
                onPick = onPick
            )
            PermissionRow(
                id = Conversation.PERMISSION_FULL,
                label = L.chat.permFull,
                desc = L.chat.permFullDesc,
                current = current,
                brand = c.brand,
                onPick = onPick,
                risk = L.chat.permFullRisk
            )
        }
    }
}

@Composable
private fun PermissionRow(
    id: String,
    label: String,
    desc: String,
    current: String,
    brand: androidx.compose.ui.graphics.Color,
    onPick: (String) -> Unit,
    risk: String? = null
) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(id) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.5.sp, color = c.text1)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
            if (risk != null) {
                Spacer(Modifier.height(2.dp))
                // 风险提示用中性色（红色只留给删除操作
Text(risk, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
            }
        }
        if (current == id) {
            Icon(Icons.Rounded.Check, null, tint = brand, modifier = Modifier.size(16.dp))
        }
    }
}

/* ---------------- 抽屉 ---------------- */

@Composable
private fun DrawerOverlay(    visible: Boolean,
    app: AppState,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
    onRenameConversation: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiKey = app.settings.apiKey
    val baseUrl = app.settings.baseUrl

    // 当前余额：打开抽屉时查一GET /user/balance。没Key / 请求失败都只显示占位，不打扰用户
    var balance by remember { mutableStateOf<String?>(null) }
    // 金额格式跟随地区，所region 变了要重重排一
LaunchedEffect(visible, apiKey, baseUrl, L.region) {
        if (!visible) return@LaunchedEffect
        if (apiKey.isBlank()) {
            balance = null
            return@LaunchedEffect
        }
        // 保留上一次的值到本次请求结束，避免每次打开抽屉金额闪一下占位符
        val info = DeepSeekApi(baseUrl, apiKey).getBalance().getOrNull()
        balance = info?.let { L.money(it.totalBalance, it.currency) }
    }

    // 点头系统相册（PickVisualMedia，低版本自动回退到系统文件选择器）落盘后立刻换掉所有「我」的头像
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val oldPath = app.settings.myAvatar
            // 文件名带时间戳：Avatar 按路径做解码缓存，同名覆盖不会刷
val name = "avatar_me_${System.currentTimeMillis()}.img"
            val path = withContext(Dispatchers.IO) { app.importImage(uri, name) }
            if (path == null) {
                Toast.makeText(ctx, L.chat.toastImageReadFailed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            app.updateSettings { st -> st.copy(myAvatar = path) }
            // 清掉自己上一次生成的头像文件（设置页写入avatar_me.img 不动
if (!oldPath.isNullOrBlank() && oldPath != path) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val old = java.io.File(oldPath)
                        if (old.name.startsWith("avatar_me_")) old.delete()
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            label = "drawer-scrim"
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { onDismiss() })
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(280)) { -it },
            exit = slideOutHorizontally(tween(240)) { -it },
            label = "drawer-panel"
        ) {
            Column(
            Modifier
                .fillMaxWidth(0.66f)
                .fillMaxHeight()
                .background(c.bg2)
                // 吃掉抽屉内部面板的空白点击：否则事件会穿透到背后全屏遮罩，点空白就把抽屉关了
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .statusBarsPadding()
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .clickable {
                            pickAvatar.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                ) {
                    Avatar(app.settings.myAvatar, 46.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(L.chat.drawerMe, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
                    Text(L.chat.drawerTapAvatar, fontSize = 11.sp, color = c.text3, maxLines = 1)
                }
                // 当前余额（头像区右侧）：点金额进「用量统计」页
                Column(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onOpenUsage() }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(L.chat.drawerBalance, fontSize = 10.sp, color = c.text3, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        balance ?: L.balanceDash(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text1,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF5B78FF), Color(0xFF4D6BFE))))
                    .clickable {
                        app.newChat()
                        onDismiss()
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(L.chat.drawerNewChat, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(c.bg3)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.text3, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(L.chat.drawerSearch, fontSize = 12.5.sp, color = c.text3)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(app.conversations, key = { it.id }) { conv ->
                    // 每行自己的「⋯」菜单开关：同一时间只会有一行展开（点别处Popup 自己收起
var rowMenu by remember(conv.id) { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 只有标题区域可点（切换会话）：水波纹裁剪在圆角内，不会覆盖右侧「⋯
Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    app.select(conv.id)
                                    onDismiss()
                                }
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.ChatBubbleOutline, null,
                                tint = c.text3,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                L.conversationTitle(conv.title),
                                fontSize = 13.sp,
                                color = c.text1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 行最右侧「⋯」：点开小菜单（重命名/删除会话），不触发切会话
                        Box {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { rowMenu = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.MoreHoriz, null,
                                    tint = c.text3,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = rowMenu,
                                onDismissRequest = { rowMenu = false },
                                containerColor = c.bg,
                                shape = RoundedCornerShape(16.dp),
                                tonalElevation = 0.dp
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            L.chat.menuRename,
                                            fontSize = 13.5.sp,
                                            color = c.text1
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Edit, null,
                                            tint = c.text2,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    },
                                    onClick = {
                                        rowMenu = false
                                        onRenameConversation(conv)
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            L.chat.menuDeleteChat,
                                            fontSize = 13.5.sp,
                                            color = c.danger
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Delete, null,
                                            tint = c.danger,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    },
                                    onClick = {
                                        rowMenu = false
                                        onDeleteConversation(conv)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Settings, null, tint = c.text2, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(9.dp))
                Text(L.chat.drawerSettings, fontSize = 13.5.sp, color = c.text1)
            }
            }
        }
    }
}

/* ---------------- 加号面板 ---------------- */

@Composable
private fun PlusSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    thinking: Boolean,
    search: Boolean,
    onPickFile: () -> Unit,
    onPickAlbum: () -> Unit,
    onTakePhoto: () -> Unit,
    onLocate: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(160)),
            label = "sheet-scrim"
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x55000000)).clickable { onDismiss() })
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(tween(280)) { it },
            exit = slideOutVertically(tween(240)) { it },
            label = "sheet-panel"
        ) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(c.bg)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { }
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        PlusItem(L.chat.plusAlbum, Icons.Rounded.Image, onPickAlbum)
                        PlusItem(L.chat.plusCamera, Icons.Rounded.PhotoCamera, onTakePhoto)
                        PlusItem(L.chat.plusFile, Icons.Rounded.InsertDriveFile, onPickFile)
                        PlusItem(L.chat.plusLocation, Icons.Rounded.Place, onLocate)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlusItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)? = null
) {
    val c = LocalColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(17.dp)).clickable { onClick() } else Modifier
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(c.bg3),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = c.text2, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.5.sp, color = c.text2)
    }
}

/* ---------------- 重命名会---------------- */

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = { Text(L.chat.renameTitle) },
        text = {
            Column {
                Text(L.chat.renameHint, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.bg3)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(L.chat.renamePlaceholder, fontSize = 14.sp, color = c.text3)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = c.text1),
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text(L.common.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/* ---------------- 删除会话确认 ---------------- */

/** 删除会话的二次确认：确认按钮用危险色，取消保持普通色 */
@Composable
private fun DeleteChatDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = { Text(title) },
        text = { Text(hint, fontSize = 12.5.sp, color = c.text3) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, fontSize = 14.sp, color = c.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L.common.cancel, fontSize = 14.sp, color = c.text2)
            }
        }
    )
}

/* ---------------- 本地模拟回复 ---------------- */

/**
 * 历史消息里最多重放多少条「带图用户消息」的图片 * 官方限制：请求体 8 MiB、单请求 00 张图；最近几轮足以保持多轮问图的连续性，
 * 再往前的图片只保留文字，避免请求体随会话变长无限膨胀 */
private const val HISTORY_IMAGE_MSGS = 6
/** 缓存阶段 A：raw assistant 回放总开关；关掉完全回到旧拼装 */
private const val RAW_REPLAY = true
/** 单条 rawAssistant 上限（32KB）：超限只压缩 text，thinking 块（含 signature）永远保留（D97） */
private const val RAW_ASSISTANT_MAX = 32 * 1024
/**
 * rawChain 单条上限。持久化前已把 tool_result 里的 image 块（base64）替换成占位文字，
 * 正常工具链只有几 KB；这里留 256KB 余量，避免"工具返回长文本"时整条链被丢弃。
 */
private const val RAW_CHAIN_MAX = 256 * 1024
/** 持久化工具链里，单个 tool_result 文本块的上限（超出截断），防止一条超长输出把链挤爆 */
private const val RAW_CHAIN_TEXT_BLOCK_MAX = 8 * 1024

/**
 * 把本轮工具链清洗成可持久化的 JSON 字符串。
 *
 * 背景（用户反馈的 bug）：`rawChain` 是「模型上一轮调用了哪些工具、工具返回了什么」的唯一记忆来源。
 * 以前直接把请求体里的 tool_result 原样存下来，而图像类工具（`local.download` / `local.imagegen`）
 * 会在 tool_result 里带 base64 图片，一条链轻松超过 64KB 上限 → `takeIf` 整条丢弃 →
 * 后续轮次模型完全看不到这轮的工具调用，于是出现"自己做过的事说没做过"。
 *
 * 这里做三件事（1.0.3 / D97 更新）：
 * 1. 把 image 块替换成一行文字占位（图片本体在会话文件里，模型不需要 base64）；
 * 2. 文本块截断到 [RAW_CHAIN_TEXT_BLOCK_MAX]；
 * 3. **超限时逐级缩短文本块，绝不整条丢弃**：thinking 块（含 signature）必须原样保留——
 *    带 tools 的历史缺 thinking 会让下一轮请求 400。只有文本块会被压缩，最后兜底返回链本身。
 */
private fun sanitizeChainForStore(chain: List<JSONObject>): String {
    if (chain.isEmpty()) return ""
    var textCap = RAW_CHAIN_TEXT_BLOCK_MAX
    while (true) {
        val json = buildChainJson(chain, textCap)
        if (json.length <= RAW_CHAIN_MAX || textCap == 0) return json
        textCap = when {
            textCap > 1024 -> 1024
            textCap > 128 -> 128
            else -> 0
        }
    }
}

private fun buildChainJson(chain: List<JSONObject>, textCap: Int): String {
    val out = JSONArray()
    for (msg in chain) {
        val role = msg.optString("role")
        if (role.isBlank()) continue
        val cleaned = JSONObject().put("role", role)
        when (val content = msg.opt("content")) {
            is String -> cleaned.put("content", content.take(textCap))
            is JSONArray -> {
                val blocks = JSONArray()
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    blocks.put(sanitizeChainBlock(block, textCap))
                }
                cleaned.put("content", blocks)
            }
            else -> cleaned.put("content", "")
        }
        out.put(cleaned)
    }
    return out.toString()
}

/** 单个 content 块：去掉 image（换成文字占位），tool_result 的内部 content 递归处理，文本块按 textCap 截断 */
private fun sanitizeChainBlock(block: JSONObject, textCap: Int): JSONObject {
    val type = block.optString("type")
    if (type == "image") {
        return JSONObject().put("type", "text").put("text", "[图片已省略：原图保存在会话文件里]")
    }
    // D97：thinking / redacted_thinking 原样保留（含 signature），任何情况下都不许裁掉
    if (type == "thinking" || type == "redacted_thinking") return JSONObject(block.toString())
    val copy = JSONObject(block.toString())
    when (val inner = block.opt("content")) {
        is JSONArray -> {
            val arr = JSONArray()
            for (i in 0 until inner.length()) {
                val child = inner.optJSONObject(i) ?: continue
                arr.put(sanitizeChainBlock(child, textCap))
            }
            copy.put("content", arr)
        }
        is String -> copy.put("content", inner.take(textCap))
        else -> Unit
    }
    if (type == "text") {
        copy.put("text", block.optString("text").take(textCap))
    }
    return copy
}

/**
 * 1.0.3 / D97：清洗要落库的 rawAssistant（assistant content 块 JSON 数组）。
 *
 * 以前超过 32KB 整条丢弃；但「请求带 tools 时历史必须回传 thinking 块（含 signature）」，
 * 整条丢会让下一轮 400。现在：thinking / redacted_thinking 永远原样保留，
 * 只按预算压缩 text 块；即使 text 压到 0 仍超限也返回（thinking 优先，不丢）。
 */
private fun sanitizeAssistantRawForStore(raw: String): String {
    if (raw.isBlank()) return ""
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return ""
    if (arr.length() == 0) return ""
    var textCap = 8 * 1024
    while (true) {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val block = arr.optJSONObject(i) ?: continue
            val type = block.optString("type")
            when {
                type == "thinking" || type == "redacted_thinking" ->
                    out.put(JSONObject(block.toString()))
                type == "text" -> {
                    val copy = JSONObject(block.toString())
                    copy.put("text", block.optString("text").take(textCap))
                    out.put(copy)
                }
                else -> out.put(JSONObject(block.toString()))
            }
        }
        val json = out.toString()
        if (json.length <= RAW_ASSISTANT_MAX || textCap == 0) return json
        textCap = when {
            textCap > 2048 -> 2048
            textCap > 256 -> 256
            else -> 0
        }
    }
}

/**
 * 1.0.3 / D97：停止 / 异常退出工具循环时，assistant 里可能已经有 tool_use 但没有配对的
 * tool_result，下一轮请求会 400「工具结果未配对」。给所有没配对的 tool_use 补一条占位
 * tool_result（作为 user 消息追加在链尾），保证历史结构合法。
 */
private fun repairDanglingToolUses(chain: List<JSONObject>, placeholder: String): List<JSONObject> {
    if (chain.isEmpty()) return chain
    val useIds = LinkedHashSet<String>()
    val resultIds = HashSet<String>()
    for (msg in chain) {
        val content = msg.optJSONArray("content") ?: continue
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "tool_use" -> block.optString("id").takeIf { it.isNotBlank() }?.let { useIds.add(it) }
                "tool_result" -> block.optString("tool_use_id").takeIf { it.isNotBlank() }?.let { resultIds.add(it) }
            }
        }
    }
    val missing = useIds.filterNot { it in resultIds }
    if (missing.isEmpty()) return chain
    val results = JSONArray()
    missing.forEach { id ->
        results.put(
            JSONObject()
                .put("type", "tool_result")
                .put("tool_use_id", id)
                .put("content", placeholder)
                .put("is_error", true)
        )
    }
    return chain + JSONObject().put("role", "user").put("content", results)
}

/**
 * 历史消息里最多重放多少个服务端解析文档（Files API file_id） * 官方限制：每个请求最多引20 个文档、文档文字合 MiB，超出返400 */
private const val HISTORY_DOC_LIMIT = 20

/** 服务OCR 只识别当前消息的前几张图：OCR 按页计费，图片太多会又慢又贵 */
private const val OCR_MAX_IMAGES = 3

/** 客户端工具单次输出上限（字符）：超过保头+ 省略计数（第三期 E13 的输出截断） */
private const val TOOL_OUTPUT_LIMIT = 64 * 1024

/** 第三期：按工具声明的 timeoutMs 超时后返TOOL_TIMEOUT 错误，而不是抛崩溃 */
private class ToolTimeoutException : Exception("TOOL_TIMEOUT")

/** 4a-2：模tool_use 的完整调*/
private data class PendingToolUse(
    val id: String,
    val name: String,
    val inputJson: String
)

private suspend fun <T> withToolTimeout(timeoutMs: Int, block: suspend () -> Result<T>): Result<T> {
    val startedAt = android.os.SystemClock.elapsedRealtime()
    val result = try {
        withTimeout(timeoutMs.toLong()) { block() }
    } catch (e: TimeoutCancellationException) {
        return Result.failure(ToolTimeoutException())
    }
    // 内部 runCatching / 文件读取可能把取消异常吞成普通失败；用耗时兜底，保证超时稳定映TOOL_TIMEOUT
    if (android.os.SystemClock.elapsedRealtime() - startedAt >= timeoutMs) {
        return Result.failure(ToolTimeoutException())
    }
    if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) {
        currentCoroutineContext().ensureActive()
        return Result.failure(ToolTimeoutException())
    }
    return result
}

private fun truncateMiddle(raw: String, maxChars: Int, L: Str): String {
    if (raw.length <= maxChars) return raw
    val half = maxChars / 2
    val omitted = raw.length - maxChars
    return raw.substring(0, half) + "\n" +
        L.format(L.chat.toolOutputTruncated, omitted) + "\n" +
        raw.substring(raw.length - half)
}

/**
 * 清理 OCR 识别文本：逐页识别的原始输出带 `<｜ref标题<ref`  * `<｜box[[x1,y1,x2,y2]]<box` 这类标记，作为模型上下文时只保留文字 */
private fun cleanOcrText(raw: String): String = raw
    // 去掉 <｜ref / <｜box 等全角标记（含成对闭合标签）
    .replace(Regex("<[^>]*>"), "")
    // 去掉归一化坐标数[[52, 40, 948, 96]]
    .replace(Regex("\\[\\[[^\\]]*\\]\\]"), "")
    .replace(Regex("[ \\t]+"), " ")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()

/** 用户主动点「停止」时 OkHttp Canceled 收尾；这不算接口报错，不要弹失败 Toast */
private fun isCancelledMessage(msg: String): Boolean {
    val s = msg.lowercase(Locale.ROOT)
    return "canceled" in s || "cancelled" in s || "ȡ" in s || "socket closed" in s
}

private fun buildReply(
    L: Str,
    prompt: String,
    fromVoice: Boolean,
    searchOn: Boolean,
    imageCount: Int = 0
): String {
    return buildString {
        append(L.chat.mockHeader).append("\n\n")
        append(
            L.format(
                L.chat.mockYouSaid,
                prompt.take(40).ifBlank { if (imageCount > 0) L.chat.mockImageOnly else "" }
            )
        )
        if (imageCount > 0) {
            append("\n")
            append(L.format(L.chat.mockImages, imageCount))
        }
        if (fromVoice) append("\n").append(L.chat.mockVoice)
        append("\n\n").append(L.chat.mockGoSettings)
    }
}
