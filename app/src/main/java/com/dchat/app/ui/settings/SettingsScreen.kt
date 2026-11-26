package com.dchat.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dchat.app.data.AppState
import com.dchat.app.data.Conversation
import com.dchat.app.data.DeepSeekZip
import com.dchat.app.data.tools.FileTools
import com.dchat.app.data.tools.ImageGenSettings
import com.dchat.app.data.tools.LocalToolRunner
import com.dchat.app.data.tools.PackageInstaller
import com.dchat.app.data.tools.PyProcessRuntime
import com.dchat.app.data.tools.PyRuntime
import com.dchat.app.data.tools.ToolMode
import com.dchat.app.data.tools.ToolOption
import com.dchat.app.data.tools.ToolRegistry
import com.dchat.app.data.tools.ToolSource
import com.dchat.app.data.tools.ToolSpec
import com.dchat.app.data.tools.UserToolDef
import com.dchat.app.data.tools.UserToolStore
import com.dchat.app.ui.Avatar
import com.dchat.app.ui.CompactSwitch
import com.dchat.app.ui.IconBtn
import com.dchat.app.ui.SectionLabel
import com.dchat.app.ui.SettingGroup
import com.dchat.app.ui.SettingRow
import com.dchat.app.ui.SwitchRow
import com.dchat.app.ui.lang.AppLanguage
import com.dchat.app.ui.lang.AppRegion
import com.dchat.app.ui.lang.LocalStrings
import com.dchat.app.ui.lang.Str
import com.dchat.app.ui.lang.format
import com.dchat.app.ui.tools.UserToolUi
import com.dchat.app.ui.theme.LocalColors
import com.dchat.app.ui.theme.brandGradient
import com.dchat.app.ui.voice.TtsEngine
import com.dchat.app.ui.voice.TtsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

@Composable
fun SettingsScreen(
    app: AppState,
    onBack: () -> Unit,
    onOpenBackground: () -> Unit,
    onOpenToolUi: (String) -> Unit = {}
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val s = app.settings
    val context = LocalContext.current
    val openBrowser = com.dchat.app.ui.LocalUrlOpener.current

    var showApi by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var showNick by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showSystemPrompt by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showProcDiag by remember { mutableStateOf(false) }
    // 语言 / 地区选择
    var showLanguage by remember { mutableStateOf(false) }
    var showRegion by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // 关于抽屉里的版本号一律取安装包真实版本，避免与 build.gradle 漂移
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // 音色选择：独立 TTS 实例（只在设置页活着，离开时 shutdown）
    var showVoice by remember { mutableStateOf(false) }
    var voiceList by remember { mutableStateOf<List<TtsVoice>>(emptyList()) }
    var voiceLoading by remember { mutableStateOf(false) }

    BackHandler(enabled = showTools) { showTools = false }

    // 返回键先关抽屉，再退页面
    BackHandler(enabled = showModel) { showModel = false }
    BackHandler(enabled = showApi) { showApi = false }

    val pickMyAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            // 每次换头像写新文件名：路径变了才会触发重组 + 图片缓存刷新
            val path = app.importAvatar(it, "me")
            if (path != null) app.updateSettings { st -> st.copy(myAvatar = path) }
        }
    }
    val pickModelAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val path = app.importAvatar(it, "model")
            if (path != null) app.updateSettings { st -> st.copy(modelAvatar = path) }
        }
    }

    val ctx = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val toast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }

    // 音色试听/枚举用的 TTS；第一次打开音色对话框时才初始化
    val toneTts = remember { TtsEngine(ctx) }
    DisposableEffect(Unit) {
        onDispose { toneTts.shutdown() }
    }

    // 导出全部数据：系统「创建文档」选择器 → DeepSeek v3 ZIP（全量备份）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val snapshot = app.conversations
        exportScope.launch {
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

    // 从文件导入：解析 ZIP 后追加为新会话（不覆盖已有会话；零会话/空状态下入口依然可用）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportScope.launch {
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

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Icons.Rounded.ArrowBackIosNew, onBack, iconSize = 18.dp)
                Spacer(Modifier.width(4.dp))
                Text(L.settings.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
                Spacer(Modifier.weight(1f))
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // 双头像
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(c.bg2)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ProfileCard(L.chat.drawerMe, s.myAvatar, brandGradient, Modifier.weight(1f)) {
                        pickMyAvatar.launch("image/*")
                    }
                    ProfileCard("DChat", s.modelAvatar, brandGradient, Modifier.weight(1f)) {
                        pickModelAvatar.launch("image/*")
                    }
                }

                SectionLabel(L.settings.sectionAppearance)
                SettingGroup {
                    SwitchRow(L.settings.darkMode, s.darkTheme, divider = true) { v ->
                        app.updateSettings { it.copy(darkTheme = v) }
                    }
                    SettingRow(
                        L.settings.fontSize,
                        value = fontLabel(L, s.fontScale),
                        onClick = { showFont = true },
                        divider = true
                    )
                    SettingRow(
                        L.settings.background,
                        value = if (s.bgImage != null) L.settings.bgCustom else L.settings.bgDefault,
                        onClick = onOpenBackground
                    )
                }

                // 语言和地区：与「外观」「系统和工具」同级；语言切换立即全局生效
                SectionLabel(L.settings.sectionLangRegion)
                SettingGroup {
                    SettingRow(
                        L.settings.rowRegion,
                        value = L.regionName(AppRegion.fromCode(s.region)),
                        onClick = { showRegion = true },
                        divider = true
                    )
                    SettingRow(
                        L.settings.rowLanguage,
                        value = L.languageName(AppLanguage.fromCode(s.language)),
                        onClick = { showLanguage = true }
                    )
                }

                SectionLabel(L.settings.sectionChat)
                SettingGroup {
                    SettingRow(L.settings.modelNickname, value = s.modelNickname, onClick = { showNick = true })
                }

                SectionLabel(L.settings.sectionSystemTools)
                SettingGroup {
                    SettingRow(
                        L.settings.systemPrompt,
                        value = if (s.systemPrompt.isBlank()) {
                            L.settings.notSet
                        } else {
                            s.systemPrompt.replace('\n', ' ').take(10)
                        },
                        onClick = { showSystemPrompt = true },
                        divider = true
                    )
                    SettingRow(
                        L.settings.apiConfig,
                        value = when {
                            s.apiKey.isBlank() -> L.settings.apiNotConfigured
                            s.modelName.isBlank() -> L.settings.apiConfiguredNoModel
                            else -> L.format(L.settings.apiConfiguredModel, s.modelName)
                        },
                        onClick = { showApi = true },
                        divider = true
                    )
                    SettingRow(
                        L.settings.tools,
                        value = L.settings.toolsCount,
                        onClick = { showTools = true },
                        divider = true
                    )
                    SettingRow(
                        L.settings.innerBrowser,
                        value = L.settings.tapToOpen,
                        onClick = { openBrowser("https://www.bing.com") },
                        divider = true
                    )
                    SettingRow(
                        L.settings.resetPythonRuntime,
                        value = L.settings.tapToOpen,
                        onClick = {
                            PyProcessRuntime.resetPyhome(ctx)
                            toast(L.settings.resetPythonRuntimeDone)
                        }
                    )
                }

                SectionLabel(L.settings.sectionData)
                SettingGroup {
                    SettingRow(
                        L.settings.exportAllData,
                        onClick = { exportLauncher.launch(DeepSeekZip.suggestedFileName()) },
                        divider = true
                    )
                    SettingRow(
                        L.settings.importFromFile,
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        divider = true
                    )
                    SettingRow(L.settings.deleteAllData, danger = true, onClick = { showClear = true })
                }

                SectionLabel(L.settings.sectionAbout)
                SettingGroup {
                    SettingRow(
                        L.settings.aboutDchat,
                        value = versionName,
                        onClick = { showAbout = true }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.navigationBarsPadding())
        }

        ApiSheet(
            visible = showApi,
            app = app,
            onDismiss = { showApi = false },
            onPickModel = { showModel = true }
        )
        ModelSheet(visible = showModel, app = app, onDismiss = { showModel = false })
        ToolsSheet(
            visible = showTools,
            app = app,
            onDismiss = { showTools = false },
            onOpenToolUi = onOpenToolUi,
            onOpenProcDiag = {
                showTools = false
                showProcDiag = true
            },
            onPickVoice = {
                showTools = false
                showVoice = true
                // 官方音色是固定两条（stella / tide），没有音色列表接口，直接列文档音色表
                voiceLoading = false
                voiceList = toneTts.chineseVoices()
            }
        )
        if (showProcDiag) {
            ToolProcDiagDialog(onDismiss = { showProcDiag = false })
        }
        AboutSheet(
            visible = showAbout,
            versionName = versionName,
            onDismiss = { showAbout = false }
        )

        if (showFont) {
            FontDialog(current = s.fontScale, onDismiss = { showFont = false }) { v ->
                app.updateSettings { it.copy(fontScale = v) }
                showFont = false
            }
        }
        if (showNick) {
            NickDialog(current = s.modelNickname, onDismiss = { showNick = false }) { v ->
                app.updateSettings { it.copy(modelNickname = v.ifBlank { "DChat" }) }
                showNick = false
            }
        }
        // 选择地区：底部抽屉；选项按当前界面语言书写，选中项打勾并高亮
        PickerSheet(
            visible = showRegion,
            title = L.settings.dialogPickRegion,
            hint = L.settings.regionSheetHint,
            currentCode = AppRegion.fromCode(s.region).code,
            options = AppRegion.values().map { region ->
                PickerOption(
                    code = region.code,
                    label = L.regionName(region),
                    badge = regionFlag(region),
                    isFlag = true
                )
            },
            onDismiss = { showRegion = false }
        ) { code ->
            // 改完立刻写进设置：AppSettings 是 Compose state，整个界面马上用新地区重绘
            app.updateSettings { it.copy(region = AppRegion.fromCode(code).code) }
            showRegion = false
        }
        // 选择语言：底部抽屉；选项始终用该语言自己的写法（English / 日本語 / 中文 / 한국어）
        PickerSheet(
            visible = showLanguage,
            title = L.settings.dialogPickLanguage,
            hint = L.settings.languageSheetHint,
            currentCode = AppLanguage.fromCode(s.language).code,
            options = AppLanguage.values().map { lang ->
                PickerOption(
                    code = lang.code,
                    label = lang.endonym,
                    badge = lang.endonym.take(1).uppercase()
                )
            },
            onDismiss = { showLanguage = false }
        ) { code ->
            app.updateSettings { it.copy(language = AppLanguage.fromCode(code).code) }
            showLanguage = false
        }
        if (showSystemPrompt) {
            SystemPromptDialog(current = s.systemPrompt, onDismiss = { showSystemPrompt = false }) { v ->
                app.updateSettings { it.copy(systemPrompt = v) }
                showSystemPrompt = false
            }
        }
        if (showVoice) {
            VoiceDialog(
                voices = voiceList,
                loading = voiceLoading,
                current = s.ttsVoice,
                onPreview = { v ->
                    toneTts.setVoiceByName(v.id)
                    toneTts.speak("voice_preview", L.settings.voicePreviewText)
                },
                onPick = { v ->
                    app.updateSettings { it.copy(ttsVoice = v.id) }
                    toast(L.format(L.settings.toastVoiceSwitched, TtsEngine.voiceLabel(v.id)))
                    showVoice = false
                    toneTts.stop()
                },
                onDismiss = {
                    showVoice = false
                    toneTts.stop()
                }
            )
        }
        if (showClear) {
            AlertDialog(
                onDismissRequest = { showClear = false },
                containerColor = c.bg,
                title = { Text(L.settings.deleteAllDialogTitle) },
                text = { Text(L.settings.deleteAllDialogText) },
                confirmButton = {
                    TextButton(onClick = {
                        // 清空会话 + 图片/语音缓存（设置保留），回到无会话的空状态
                        app.clearAllData()
                        showClear = false
                        toast(L.settings.toastDataCleared)
                    }) { Text(L.settings.deleteAction, color = c.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { showClear = false }) { Text(L.common.cancel) }
                }
            )
        }
    }
}

private fun fontLabel(L: Str, v: Float): String = when {
    v <= 0.95f -> L.settings.fontSmall
    v <= 1.05f -> L.settings.fontStandard
    v <= 1.2f -> L.settings.fontLarge
    else -> L.settings.fontExtraLarge
}

/**
 * 语言 / 地区选择抽屉的一个选项。
 * [badge] 是行首圆形徽标里的文字：语言用自身写法的首字符（English→E / 日本語→日），
 * 地区用国旗 emoji（[isFlag] 为 true，字号略大）。
 */
private data class PickerOption(
    val code: String,
    val label: String,
    val badge: String,
    val isFlag: Boolean = false
)

/** 地区国旗 emoji，不依赖外部图片或资源文件；具体地区名仍按当前界面语言显示 */
private fun regionFlag(region: AppRegion): String = when (region) {
    AppRegion.UK -> "🇬🇧"
    AppRegion.JP -> "🇯🇵"
    AppRegion.CN -> "🇨🇳"
    AppRegion.KR -> "🇰🇷"
}

/**
 * 单选抽屉：外观完全复用工具弹层的 [SheetScaffold]（底部滑入、顶部 22dp 圆角、
 * 点击遮罩关闭、面板内点击不关闭、navigationBarsPadding），内部是浅灰卡片分组的选项列表。
 * 点选即写设置并关闭抽屉（和工具弹层一样没有额外「确定」按钮）。
 */
@Composable
private fun PickerSheet(
    visible: Boolean,
    title: String,
    hint: String,
    currentCode: String,
    options: List<PickerOption>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    // 返回键先关抽屉（只在抽屉可见时接管，不干扰设置页其它返回逻辑）
    BackHandler(enabled = visible) { onDismiss() }
    SheetScaffold(
        visible = visible,
        title = title,
        onDismiss = onDismiss,
        subtitle = hint,
        showHandle = true
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.bg2)
        ) {
            options.forEachIndexed { index, option ->
                PickerRow(
                    option = option,
                    selected = option.code == currentCode,
                    currentLabel = L.settings.sheetCurrentLabel
                ) { onPick(option.code) }
                if (index != options.lastIndex) {
                    // 分割线与设置页一致：左右各缩进 14dp
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .height(1.dp)
                            .background(c.bg)
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    option: PickerOption,
    selected: Boolean,
    currentLabel: String,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            // 选中行整行轻微高亮；选中时徽标改用卡片底色，保证圆底不被同色盖住
            .background(if (selected) c.bg3 else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (selected) c.bg2 else c.bg3),
            contentAlignment = Alignment.Center
        ) {
            Text(
                option.badge,
                fontSize = if (option.isFlag) 15.sp else 12.5.sp,
                fontWeight = if (option.isFlag) FontWeight.Normal else FontWeight.SemiBold,
                color = c.text1,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            option.label,
            fontSize = 14.sp,
            color = c.text1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            // 右侧：当前值标记 + 品牌色打勾
            Text(currentLabel, fontSize = 11.5.sp, color = c.brand)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.Check, null, tint = c.brand, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun ProfileCard(
    name: String,
    avatar: String?,
    gradient: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(c.bg)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Avatar(avatar, 48.dp, gradient = gradient)
        Spacer(Modifier.height(7.dp))
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.text1)
        Text(L.settings.tapChangeAvatar, fontSize = 10.5.sp, color = c.text3)
    }
}

/* ---------------- 底部抽屉 ---------------- */

/**
 * 关于 DChat：从底部滑出的说明抽屉。
 * 正文按语言取自 `L.settings.aboutBody`，其中 `{v}` 替换成安装包真实版本号。
 */
@Composable
private fun AboutSheet(visible: Boolean, versionName: String, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val body = remember(L.settings.aboutBody, versionName) {
        L.settings.aboutBody.replace("{v}", versionName.ifBlank { "—" })
    }
    SheetScaffold(
        visible = visible,
        title = L.settings.aboutDchat,
        onDismiss = onDismiss,
        showHandle = true,
        contentMaxHeight = 460.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                body,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = c.text2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.bg2)
                    .clickable { onDismiss() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    L.common.close,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = c.brand
                )
            }
        }
    }
}

@Composable
private fun SheetScaffold(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    showHandle: Boolean = false,
    /** 内容超过这个高度时内部可滚动（工具页工具多，需要）；null = 保持旧的 wrapContent */
    contentMaxHeight: Dp? = null,
    content: @Composable () -> Unit
) {
    val c = LocalColors.current
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
            label = "scrim"
        ) {
            Box(Modifier.fillMaxSize().background(Color(0x77000000)).clickable { onDismiss() })
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(280)) { it },
            exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(240)) { it },
            label = "sheet"
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
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    // 可选拖动条：语言/地区选择抽屉用；工具/API 抽屉按 D26 保持无拖动条
                    if (showHandle) {
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 10.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(c.line2)
                        )
                    }
                    Text(
                        title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = c.text1,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    // 标题下方的小灰字说明（如「更改后界面文案立即切换」）
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            subtitle,
                            fontSize = 11.5.sp,
                            color = c.text3,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (contentMaxHeight != null) {
                        Column(
                            Modifier
                                .heightIn(max = contentMaxHeight)
                                .verticalScroll(rememberScrollState())
                        ) { content() }
                    } else {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiSheet(visible: Boolean, app: AppState, onDismiss: () -> Unit, onPickModel: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val s = app.settings
    var baseUrl by remember(visible) { mutableStateOf(s.baseUrl) }
    var apiKey by remember(visible) { mutableStateOf(s.apiKey) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SheetScaffold(visible, L.settings.apiConfig, onDismiss) {
        Text(L.settings.apiBaseUrl, fontSize = 11.5.sp, color = c.text3, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.bg3)
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            if (baseUrl.isEmpty()) Text("https://api.deepseek.com", fontSize = 13.5.sp, color = c.text3)
            BasicTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = c.text1),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(L.settings.apiKey, fontSize = 11.5.sp, color = c.text3, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.bg3)
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            if (apiKey.isEmpty()) Text("sk-...", fontSize = 13.5.sp, color = c.text3)
            BasicTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = c.text1),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.bg2)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPickModel() }
                    .padding(horizontal = 12.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(L.settings.apiModel, fontSize = 13.5.sp, color = c.text1, modifier = Modifier.weight(1f))
                Text(
                    s.modelName.ifBlank { L.settings.apiNoModel },
                    fontSize = 13.sp,
                    color = c.text3
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.ChevronRight, null,
                    tint = c.text3, modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(testMsg ?: L.settings.apiKeyLocalHint, fontSize = 11.sp, color = c.text3)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.bg2)
                    .clickable {
                        testMsg = L.settings.apiTesting
                        scope.launch {
                            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.dchat.app.data.api.DeepSeekApi(app.settings.baseUrl, app.settings.apiKey).listModels()
                            }
                            testMsg = r.fold(
                                onSuccess = { L.format(L.settings.apiTestOk, it.size) },
                                onFailure = { L.format(L.settings.apiTestFail, it.message?.take(60).orEmpty()) }
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Text(L.settings.apiTestBtn, fontSize = 14.5.sp, color = c.text2) }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(brandGradient))
                    .clickable {
                        app.updateSettings {
                            it.copy(
                                baseUrl = baseUrl.trim().ifBlank { "https://api.deepseek.com" },
                                apiKey = apiKey.trim()
                            )
                        }
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) { Text(L.common.save, fontSize = 14.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ModelSheet(visible: Boolean, app: AppState, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val L = LocalStrings.current
    var hint by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SheetScaffold(visible, L.settings.modelSheetTitle, onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(brandGradient))
                .clickable {
                    if (app.settings.apiKey.isBlank()) {
                        hint = L.settings.modelNeedKey
                    } else {
                        loading = true
                        hint = null
                        scope.launch {
                            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.dchat.app.data.api.DeepSeekApi(
                                    app.settings.baseUrl,
                                    app.settings.apiKey
                                ).listModels()
                            }
                            r.onSuccess { list ->
                                models = list
                                hint = if (list.isEmpty()) L.settings.modelNoReturn else null
                            }.onFailure {
                                hint = L.format(L.settings.modelFetchFail, it.message?.take(60).orEmpty())
                            }
                            loading = false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Refresh, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(L.settings.modelFetchBtn, fontSize = 14.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (models.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.bg2)
            ) {
                models.forEach { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                app.updateSettings { it.copy(modelName = id) }
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(id, fontSize = 14.sp, color = c.text1, modifier = Modifier.weight(1f))
                        if (id == app.settings.modelName) {
                            Icon(Icons.Rounded.Check, null, tint = c.brand, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.bg2)
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (loading) L.settings.modelFetching else L.settings.modelNone,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text2
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    hint ?: L.settings.modelFetchHint,
                    fontSize = 11.5.sp,
                    color = c.text3
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(L.settings.modelManual, fontSize = 11.5.sp, color = c.text3)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(23.dp))
                .background(c.bg3)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (manual.isEmpty()) Text(L.settings.modelManualHint, fontSize = 14.5.sp, color = c.text3)
            BasicTextField(
                value = manual,
                onValueChange = { manual = it },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.5.sp, color = c.text1),
                maxLines = 1,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.bg2)
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) { Text(L.common.cancel, fontSize = 14.5.sp, color = c.text2) }
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(brandGradient))
                    .clickable {
                        if (manual.isNotBlank()) {
                            app.updateSettings { it.copy(modelName = manual.trim()) }
                        }
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) { Text(L.settings.modelUse, fontSize = 14.5.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/* ---------------- 对话框 ---------------- */

@Composable
private fun FontDialog(current: Float, onDismiss: () -> Unit, onPick: (Float) -> Unit) {
    val L = LocalStrings.current
    val options = listOf(
        0.9f to L.settings.fontSmall,
        1f to L.settings.fontStandard,
        1.15f to L.settings.fontLarge,
        1.3f to L.settings.fontExtraLarge
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalColors.current.bg,
        title = { Text(L.settings.fontDialogTitle) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(v) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            fontSize = (14 * v).sp,
                            color = LocalColors.current.text1,
                            modifier = Modifier.weight(1f)
                        )
                        if (kotlin.math.abs(current - v) < 0.01f) {
                            Icon(
                                Icons.Rounded.Check, null,
                                tint = LocalColors.current.brand,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } }
    )
}

@Composable
private fun NickDialog(current: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val L = LocalStrings.current
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalColors.current.bg,
        title = { Text(L.settings.nickDialogTitle) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalColors.current.bg3)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.sp,
                        color = LocalColors.current.text1
                    ),
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text(L.common.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

@Composable
private fun SystemPromptDialog(current: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val L = LocalStrings.current
    var text by remember { mutableStateOf(current) }
    val c = LocalColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = { Text(L.settings.systemPrompt) },
        text = {
            Column {
                Text(
                    L.settings.systemPromptHint,
                    fontSize = 11.5.sp,
                    color = c.text3
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.bg3)
                        .padding(12.dp)
                ) {
                    if (text.isEmpty()) {
                        Text(L.settings.systemPromptPlaceholder, fontSize = 13.sp, color = c.text3)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = c.text1),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text(L.common.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.cancel) } }
    )
}

/* ---------------- 工具 ---------------- */

@Composable
private fun ToolsSheet(
    visible: Boolean,
    app: AppState,
    onDismiss: () -> Unit,
    onOpenToolUi: (String) -> Unit,
    onOpenProcDiag: () -> Unit,
    onPickVoice: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val s = app.settings
    // E12：历史设置里可能存的是 zh 这类语言码/旧系统音色名，显示时统一映射到官方音色名
    val voiceValue = ttsVoiceDisplayName(L, s.ttsVoice)
    // 第二期：工具行全部来自全局注册表。读 tools 是 Compose 可观察状态，
    // 设置页里任何开关/档位变化（包括会话页 chip 改动）都会让这里立即重组。
    val tools = ToolRegistry.tools
    // 正在选档位的工具 id；随抽屉开关重置，避免关掉再打开时旧弹窗又弹出来
    var optionToolId by remember(visible) { mutableStateOf<String?>(null) }
    // 第四期：用户工具列表操作 / 编辑器
    val ctx = LocalContext.current
    // 4c：SAF 授权目录（授权后视为工作区，可被 fs.* 读写）
    var safRefresh by remember(visible) { mutableStateOf(0) }
    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            FileTools.addSafRoot(ctx, uri)
            safRefresh++
        }
    }
    var userMenuTool by remember(visible) { mutableStateOf<ToolSpec?>(null) }
    var deleteUserTool by remember(visible) { mutableStateOf<ToolSpec?>(null) }
    var editorNew by remember(visible) { mutableStateOf(false) }
    var editorToolId by remember(visible) { mutableStateOf<String?>(null) }
    val editorOpen = editorNew || editorToolId != null
    // 图像生成（local.imagegen）：默认通道 / 通道配置 / 出图参数
    var imgChannelPicker by remember(visible) { mutableStateOf(false) }
    var imgAspectPicker by remember(visible) { mutableStateOf(false) }
    var imgCountPicker by remember(visible) { mutableStateOf(false) }
    var imgTimeoutPicker by remember(visible) { mutableStateOf(false) }
    var imgEditProvider by remember(visible) { mutableStateOf<String?>(null) }
    BackHandler(enabled = editorOpen) {
        editorNew = false
        editorToolId = null
    }

    // 工具变多后会超过一屏：底部 sheet 自身不带滚动，给内容区限高并允许内部滚动。
    // 按屏幕高度取 66%，小屏也不会顶出屏幕，大屏能多显示几行。
    val contentMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.66f).dp
    SheetScaffold(
        visible, L.settings.toolsSheetTitle, onDismiss,
        contentMaxHeight = contentMaxHeight
    ) {
        // 工具循环上限：不是工具（属于 agent 循环刹车），值仍存 AppSettings.toolRounds
        Text(
            L.settings.toolRoundsLabel,
            fontSize = 11.5.sp,
            color = c.text3,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                0 to L.settings.roundsUnlimited,
                3 to L.format(L.settings.roundsN, 3),
                5 to L.format(L.settings.roundsN, 5),
                10 to L.format(L.settings.roundsN, 10)
            ).forEach { (v, label) ->
                val on = s.toolRounds == v
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) c.brand else c.bg2)
                        .clickable { app.updateSettings { it.copy(toolRounds = v) } }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 12.sp, color = if (on) Color.White else c.text2)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        val serverTools = tools.filter { it.source == ToolSource.SERVER && !it.hidden }
        val localTools = tools.filter { it.source == ToolSource.LOCAL && !it.hidden }
        val userTools = tools.filter { it.source == ToolSource.USER && !it.hidden }

        ToolSection(L.tools.groupServer, L.tools.groupServerHint) {
            serverTools.forEachIndexed { index, tool ->
                ToolSpecRow(
                    tool = tool,
                    name = L.tools.names[tool.id] ?: tool.name,
                    divider = index != serverTools.lastIndex,
                    onToggle = { on -> ToolRegistry.setEnabled(tool.id, on) },
                    onOpenOption = { optionToolId = tool.id }
                )
                // 「音色」不是工具：保留为独立行，紧跟在「语音合成」后面（旧逻辑不变）
                if (tool.id == "server.tts") {
                    ToolRow(L.settings.toolVoice, voiceValue, onClick = onPickVoice)
                }
            }
        }
        ToolSection(L.tools.groupLocal, L.tools.groupLocalHint) {
            localTools.forEachIndexed { index, tool ->
                ToolSpecRow(
                    tool = tool,
                    name = L.tools.names[tool.id]
                        ?: if (tool.id == LocalToolRunner.IMAGEGEN) L.imageGen.toolName else tool.name,
                    divider = index != localTools.lastIndex,
                    onToggle = { on -> ToolRegistry.setEnabled(tool.id, on) },
                    onOpenOption = { optionToolId = tool.id }
                )
            }
            // 4c：SAF 授权目录（takePersistableUriPermission），授权后 fs.* 可用 saf:<名称>/ 访问
            val safRoots = remember(safRefresh, visible) { FileTools.safRoots(ctx) }
            ToolRow(
                name = L.local.safAddLabel,
                value = if (safRoots.isEmpty()) L.local.safNone else safRoots.size.toString(),
                onClick = { runCatching { safPicker.launch(null) } }
            )
            safRoots.forEach { root ->
                ToolRow(
                    name = root.name,
                    value = L.local.safRemoveLabel,
                    onClick = {
                        FileTools.removeSafRoot(ctx, root.name)
                        safRefresh++
                    }
                )
            }
        }
        // 图像生成（local.imagegen）：设置 → 工具 → 「图像生成」分组
        val imageGen = s.imageGen
        val imageGenCustomLabel = imageGen.customName.trim().ifBlank { L.imageGen.channelCustom }
        val imageGenDefaultLabel = when {
            imageGen.defaultProvider.equals("doubao", ignoreCase = true) -> L.imageGen.channelDoubao
            imageGen.defaultProvider.equals("qwen", ignoreCase = true) -> L.imageGen.channelQwen
            else -> imageGenCustomLabel
        }
        ToolSection(L.imageGen.sectionTitle, L.imageGen.sectionHint) {
            ToolRow(L.imageGen.defaultChannel, imageGenDefaultLabel) { imgChannelPicker = true }
            ToolRow(
                L.imageGen.channelDoubao,
                if (imageGen.doubaoApiKey.isBlank()) L.imageGen.keyUnset else L.imageGen.keySet
            ) { imgEditProvider = "doubao" }
            ToolRow(
                L.imageGen.channelQwen,
                if (imageGen.qwenApiKey.isBlank()) L.imageGen.keyUnset else L.imageGen.keySet
            ) { imgEditProvider = "qwen" }
            ToolRow(
                imageGenCustomLabel,
                if (imageGen.customConfigured()) L.imageGen.keySet else L.imageGen.notConfigured
            ) { imgEditProvider = "custom" }
            ToolRow(L.imageGen.aspect, imageGen.aspect) { imgAspectPicker = true }
            ToolRow(L.imageGen.count, imageGen.count.toString()) { imgCountPicker = true }
            ToolRow(
                L.imageGen.timeout,
                "${imageGen.timeoutSec} ${L.imageGen.timeoutUnit}"
            ) { imgTimeoutPicker = true }
        }

        ToolSection(L.tools.groupUser) {
            // 新建工具入口（空态也有）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        editorToolId = null
                        editorNew = true
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Add, null, tint = c.brand, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(L.tools.userNewTool, fontSize = 13.5.sp, color = c.brand, modifier = Modifier.weight(1f))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(c.bg)
            )
            // Python 运行时状态：启动中/不可用时在工具入口直接提示，不崩
            val pyState = PyRuntime.state
            if (pyState == PyRuntime.State.STARTING || pyState == PyRuntime.State.FAILED) {
                Text(
                    if (pyState == PyRuntime.State.STARTING) L.tools.runtimeStarting else L.tools.runtimeUnavailable,
                    fontSize = 10.5.sp,
                    color = c.text3,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            if (userTools.isEmpty()) {
                Text(
                    L.tools.empty,
                    fontSize = 12.5.sp,
                    color = c.text3,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
                )
            } else {
                userTools.forEachIndexed { index, tool ->
                    UserToolRow(
                        tool = tool,
                        description = L.tools.descriptions[tool.id] ?: tool.description,
                        divider = index != userTools.lastIndex,
                        onToggle = { on -> ToolRegistry.setEnabled(tool.id, on) },
                        onOpen = { onOpenToolUi(tool.id) },
                        onEdit = {
                            editorNew = false
                            editorToolId = tool.id
                        },
                        onMore = { userMenuTool = tool }
                    )
                }
            }
            ToolRow(
                name = toolProcDiagTitle(L.language),
                value = "",
                onClick = onOpenProcDiag
            )
        }
    }

    // 图像生成：默认通道 / 默认比例 / 默认张数 / 超时（选择后立刻写回 AppSettings）
    val imageGenState = app.settings.imageGen
    val imageGenCustomName = imageGenState.customName.trim().ifBlank { L.imageGen.channelCustom }
    PickerSheet(
        visible = imgChannelPicker,
        title = L.imageGen.channelSheetTitle,
        hint = L.imageGen.sectionHint,
        currentCode = imageGenState.defaultProvider,
        options = listOf(
            PickerOption("doubao", L.imageGen.channelDoubao, "豆"),
            PickerOption("qwen", L.imageGen.channelQwen, "千"),
            PickerOption(imageGenCustomName, imageGenCustomName, "自")
        ),
        onDismiss = { imgChannelPicker = false }
    ) { code ->
        app.updateSettings { it.copy(imageGen = it.imageGen.copy(defaultProvider = code)) }
        imgChannelPicker = false
    }
    PickerSheet(
        visible = imgAspectPicker,
        title = L.imageGen.aspect,
        hint = L.imageGen.sectionHint,
        currentCode = imageGenState.aspect,
        options = ImageGenSettings.ASPECTS.map { aspect ->
            PickerOption(aspect, aspect, aspect.substringBefore(':'))
        },
        onDismiss = { imgAspectPicker = false }
    ) { aspect ->
        app.updateSettings { it.copy(imageGen = it.imageGen.copy(aspect = aspect)) }
        imgAspectPicker = false
    }
    PickerSheet(
        visible = imgCountPicker,
        title = L.imageGen.count,
        hint = L.imageGen.sectionHint,
        currentCode = imageGenState.count.toString(),
        options = (1..ImageGenSettings.MAX_COUNT).map { n ->
            PickerOption(n.toString(), n.toString(), n.toString())
        },
        onDismiss = { imgCountPicker = false }
    ) { code ->
        val n = code.toIntOrNull() ?: 1
        app.updateSettings { it.copy(imageGen = it.imageGen.copy(count = n)) }
        imgCountPicker = false
    }
    PickerSheet(
        visible = imgTimeoutPicker,
        title = L.imageGen.timeout,
        hint = L.imageGen.sectionHint,
        currentCode = imageGenState.timeoutSec.toString(),
        options = listOf(120, 180, 300, 600).map { sec ->
            PickerOption(sec.toString(), "$sec ${L.imageGen.timeoutUnit}", sec.toString())
        },
        onDismiss = { imgTimeoutPicker = false }
    ) { code ->
        val sec = code.toIntOrNull() ?: ImageGenSettings.DEFAULT_TIMEOUT_SEC
        app.updateSettings { it.copy(imageGen = it.imageGen.copy(timeoutSec = sec)) }
        imgTimeoutPicker = false
    }
    ImageGenProviderSheet(
        visible = imgEditProvider != null,
        which = imgEditProvider ?: "doubao",
        app = app,
        onDismiss = { imgEditProvider = null }
    )

    // 档位选择（server.safety 的 high/low/off）：选中后只调 setOption，
    // setOption 内部会处理 off → enabled=false，镜像也由注册表负责。
    val optionTool = if (visible) {
        optionToolId?.let { id -> tools.firstOrNull { it.id == id } }
    } else {
        null
    }
    val option = optionTool?.option
    if (optionTool != null && option != null) {
        ToolOptionDialog(
            title = L.tools.names[optionTool.id] ?: optionTool.name,
            toolId = optionTool.id,
            option = option,
            onDismiss = { optionToolId = null }
        )
    }

    // 第四期：用户工具「更多」菜单（编辑 / 删除）
    val menuTool = userMenuTool
    if (menuTool != null) {
        AlertDialog(
            onDismissRequest = { userMenuTool = null },
            containerColor = c.bg,
            shape = RoundedCornerShape(16.dp),
            title = { Text(menuTool.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text1) },
            text = {
                Column {
                    Text(
                        L.tools.userEdit,
                        fontSize = 14.sp,
                        color = c.text1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                userMenuTool = null
                                editorNew = false
                                editorToolId = menuTool.id
                            }
                            .padding(vertical = 12.dp)
                    )
                    Text(
                        L.tools.userDelete,
                        fontSize = 14.sp,
                        color = c.danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                userMenuTool = null
                                deleteUserTool = menuTool
                            }
                            .padding(vertical = 12.dp)
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { userMenuTool = null }) { Text(L.common.cancel) }
            }
        )
    }

    // 删除确认：红色只用于删除
    val deleting = deleteUserTool
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteUserTool = null },
            containerColor = c.bg,
            shape = RoundedCornerShape(16.dp),
            title = { Text(L.tools.userDeleteConfirmTitle) },
            text = { Text(L.tools.userDeleteConfirmText, fontSize = 13.sp, color = c.text2, lineHeight = 19.sp) },
            confirmButton = {
                TextButton(onClick = {
                    UserToolStore.delete(ctx, deleting.id)
                    deleteUserTool = null
                }) { Text(L.settings.deleteAction, color = c.danger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteUserTool = null }) { Text(L.common.cancel) }
            }
        )
    }

    // 编辑器全屏浮层
    if (editorOpen) {
        UserToolEditor(
            toolId = editorToolId,
            conversationMode = app.current()?.permission ?: Conversation.PERMISSION_WORKSPACE,
            onClose = {
                editorNew = false
                editorToolId = null
            },
            onSaved = {
                editorNew = false
                editorToolId = null
                Toast.makeText(ctx, L.tools.editorSaved, Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/* ---------------- 第四期：我的工具（列表行 + 编辑器） ---------------- */

@Composable
private fun UserToolRow(
    tool: ToolSpec,
    description: String,
    divider: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val badge = when (tool.minMode) {
        ToolMode.READ_ONLY -> L.chat.permRead
        ToolMode.WORKSPACE_WRITE -> L.chat.permWorkspace
        ToolMode.FULL -> L.chat.permFull
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onEdit() }
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tool.name, fontSize = 13.5.sp, color = c.text1)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge,
                        fontSize = 9.5.sp,
                        color = c.text3,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(c.bg3)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                if (description.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        fontSize = 10.5.sp,
                        color = c.text3,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                L.tools.openTool,
                fontSize = 12.sp,
                color = c.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            CompactSwitch(checked = tool.enabled, onChange = onToggle)
            IconBtn(Icons.Rounded.MoreHoriz, onMore, tint = c.text3, iconSize = 18.dp)
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(c.bg)
            )
        }
    }
}

private data class ParamDraft(
    val name: String = "",
    val type: String = "string",
    val required: Boolean = false,
    val desc: String = ""
)

private const val USER_TOOL_TEMPLATE = """def run(args: dict) -> str:
    return "hello " + str(args)
"""

private val PARAM_TYPES = listOf("string", "number", "integer", "boolean", "object", "array")

private fun parseParamDrafts(schema: String?): List<ParamDraft> {
    if (schema.isNullOrBlank()) return emptyList()
    val o = runCatching { JSONObject(schema) }.getOrNull() ?: return emptyList()
    val props = o.optJSONObject("properties") ?: return emptyList()
    val required = o.optJSONArray("required")?.let { arr ->
        (0 until arr.length()).map { arr.optString(it) }.toSet()
    } ?: emptySet()
    return props.keys().asSequence().map { key ->
        val p = props.optJSONObject(key)
        ParamDraft(
            name = key,
            type = p?.optString("type")?.takeIf { it.isNotBlank() } ?: "string",
            required = key in required,
            desc = p?.optString("description").orEmpty()
        )
    }.toList()
}

private fun paramDraftsToSchema(params: List<ParamDraft>): String {
    val props = JSONObject()
    val required = JSONArray()
    params.filter { it.name.isNotBlank() }.forEach { p ->
        val prop = JSONObject().put("type", p.type)
        if (p.desc.isNotBlank()) prop.put("description", p.desc)
        props.put(p.name, prop)
        if (p.required) required.put(p.name)
    }
    return JSONObject()
        .put("type", "object")
        .put("properties", props)
        .put("required", required)
        .put("additionalProperties", false)
        .toString()
}

@Composable
private fun UserToolEditor(
    toolId: String?,
    conversationMode: String,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val initial = remember(toolId) { toolId?.let { UserToolStore.find(ctx, it) } }

    var name by remember(toolId) { mutableStateOf(initial?.name ?: "") }
    var description by remember(toolId) { mutableStateOf(initial?.description ?: "") }
    var code by remember(toolId) { mutableStateOf(initial?.code ?: USER_TOOL_TEMPLATE) }
    var entryFunction by remember(toolId) { mutableStateOf(initial?.entryFunction ?: "run") }
    var mode by remember(toolId) { mutableStateOf(initial?.minMode ?: ToolMode.WORKSPACE_WRITE) }
    var uiMode by remember(toolId) { mutableStateOf(initial?.uiMode ?: "data") }
    var params by remember(toolId) { mutableStateOf(parseParamDrafts(initial?.paramsSchema)) }
    var argsJson by remember(toolId) { mutableStateOf("{}") }
    var runOutput by remember(toolId) { mutableStateOf<String?>(null) }
    var previewState by remember(toolId) { mutableStateOf(JSONObject()) }
    var running by remember(toolId) { mutableStateOf(false) }
    var installName by remember(toolId) { mutableStateOf("") }
    var installMsg by remember(toolId) { mutableStateOf<String?>(null) }
    var installing by remember(toolId) { mutableStateOf(false) }
    var error by remember(toolId) { mutableStateOf<String?>(null) }

    fun save() {
        error = null
        when {
            !UserToolStore.NAME_REGEX.matches(name) -> error = L.tools.editorNameInvalid
            ToolRegistry.all().any { it.id != toolId && it.name.equals(name, ignoreCase = true) } ->
                error = L.tools.editorNameTaken
            description.isBlank() -> error = L.tools.editorDescriptionRequired
            code.isBlank() -> error = L.tools.editorCodeRequired
            params.any { it.name.isBlank() || !it.name.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$")) } ||
                params.map { it.name }.distinct().size != params.size ||
                params.any { it.type !in PARAM_TYPES } -> error = L.tools.editorParamsInvalid
            else -> {
                val saved = UserToolStore.save(
                    ctx,
                    UserToolDef(
                        id = initial?.id ?: "",
                        name = name,
                        description = description,
                        paramsSchema = paramDraftsToSchema(params),
                        minMode = mode,
                        entryFunction = entryFunction.ifBlank { "run" },
                        requirements = initial?.requirements ?: emptyList(),
                        version = initial?.version ?: 0,
                        enabled = initial?.enabled ?: false,
                        code = code,
                        uiMode = uiMode
                    )
                )
                if (saved != null) onSaved() else error = L.format(L.tools.editorSaveFailed, name)
            }
        }
    }

    fun runNow() {
        error = null
        if (!UserToolStore.NAME_REGEX.matches(name)) {
            error = L.tools.editorNameInvalid
            return
        }
        if (PyRuntime.state == PyRuntime.State.FAILED) {
            runOutput = L.tools.runtimeUnavailable
            return
        }
        running = true
        runOutput = null
        val previewId = initial?.id ?: UserToolStore.idForName(name)
        scope.launch {
            val r = PyRuntime.runCode(
                context = ctx,
                toolId = previewId,
                entryFunction = entryFunction.ifBlank { "run" },
                code = code,
                argsJson = argsJson.ifBlank { "{}" },
                conversationId = "preview",
                timeoutMs = 5_000,
                conversationMode = conversationMode
            )
            running = false
            runOutput = if (r.ok) {
                r.output.ifBlank { L.tools.editorOutputEmpty }
            } else {
                (r.errorCode ?: "TOOL_ERROR") + ": " + (r.errorMessage ?: "")
            }
        }
    }

    /** 4b：预览里点按钮/改表单 → 只重跑工具，更新预览，不碰模型 */
    fun rerunPreviewEvent(event: String, value: String) {
        if (PyRuntime.state == PyRuntime.State.FAILED) return
        running = true
        val previewId = initial?.id ?: UserToolStore.idForName(name)
        scope.launch {
            val parsed: Any = runCatching { JSONTokener(value).nextValue() }.getOrDefault(value)
            val args = JSONObject()
                .put("event", event)
                .put("value", JSONObject.wrap(parsed))
                .put("state", previewState)
            val r = PyRuntime.runCode(
                context = ctx,
                toolId = previewId,
                entryFunction = entryFunction.ifBlank { "run" },
                code = code,
                argsJson = args.toString(),
                conversationId = "preview",
                timeoutMs = 5_000,
                conversationMode = conversationMode
            )
            running = false
            if (r.ok) {
                runOutput = r.output.ifBlank { L.tools.editorOutputEmpty }
                val o = runCatching { JSONObject(r.output) }.getOrNull()
                previewState = o?.optJSONObject("state")
                    ?: o?.optJSONObject("_state")
                    ?: previewState
            } else {
                runOutput = (r.errorCode ?: "TOOL_ERROR") + ": " + (r.errorMessage ?: "")
            }
        }
    }

    fun installNow() {        val id = initial?.id ?: return
        if (installName.isBlank()) return
        installing = true
        installMsg = null
        scope.launch {
            val r = PackageInstaller.install(ctx, id, installName, allowPypi = true)
            installing = false
            installMsg = if (r.ok) {
                L.format(L.tools.installSuccess, r.installed.joinToString(", "))
            } else {
                L.format(L.tools.installFailed, r.error ?: "")
            }
        }
    }

    BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(Icons.Rounded.ArrowBackIosNew, onClose, iconSize = 18.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (initial == null) L.tools.editorTitleNew else L.tools.editorTitleEdit,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text1
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { save() }) {
                    Text(L.common.save, color = c.brand, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = c.text2, lineHeight = 17.sp)
                    Spacer(Modifier.height(8.dp))
                }
                EditorTextField(L.tools.editorName, name, { name = it }, L.tools.editorNameHint)
                Spacer(Modifier.height(10.dp))
                EditorTextField(L.tools.editorDescription, description, { description = it }, L.tools.editorDescriptionHint)
                Spacer(Modifier.height(8.dp))
                Text(L.tools.editorNetworkNotice, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
                Spacer(Modifier.height(12.dp))

                Text(L.tools.editorPermission, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        ToolMode.READ_ONLY to L.chat.permRead,
                        ToolMode.WORKSPACE_WRITE to L.chat.permWorkspace,
                        ToolMode.FULL to L.chat.permFull
                    ).forEach { (m, label) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (mode == m) c.brand else c.bg2)
                                .clickable { mode = m }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, fontSize = 11.5.sp, color = if (mode == m) Color.White else c.text2)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text(L.tools.uiMode, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        "data" to L.tools.uiModeData,
                        "schema" to L.tools.uiModeSchema
                    ).forEach { (m, label) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (uiMode == m) c.brand else c.bg2)
                                .clickable { uiMode = m }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize = 11.5.sp,
                                color = if (uiMode == m) Color.White else c.text2
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(L.tools.uiModeHint, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(L.tools.editorParams, fontSize = 11.5.sp, color = c.text3, modifier = Modifier.weight(1f))
                    Text(
                        L.tools.editorAddParam,
                        fontSize = 12.sp,
                        color = c.brand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { params = params + ParamDraft() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }
                params.forEachIndexed { index, p ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.bg2)
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EditorSmallField(
                                L.tools.editorParamName,
                                p.name,
                                { v -> params = params.toMutableList().also { it[index] = it[index].copy(name = v) } },
                                Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            EditorSmallField(
                                L.tools.editorParamType,
                                p.type,
                                { v -> params = params.toMutableList().also { it[index] = it[index].copy(type = v) } },
                                Modifier.width(92.dp)
                            )
                            IconBtn(Icons.Rounded.Delete, {
                                params = params.toMutableList().also { it.removeAt(index) }
                            }, tint = c.text3, iconSize = 16.dp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            EditorSmallField(
                                L.tools.editorParamDescription,
                                p.desc,
                                { v -> params = params.toMutableList().also { it[index] = it[index].copy(desc = v) } },
                                Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            CompactSwitch(p.required) { v ->
                                params = params.toMutableList().also { it[index] = it[index].copy(required = v) }
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(L.tools.editorParamRequired, fontSize = 11.sp, color = c.text2)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                EditorTextField(L.tools.editorEntryFunction, entryFunction, { entryFunction = it }, "run")
                Spacer(Modifier.height(10.dp))

                Text(L.tools.editorCode, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.bg3)
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = code,
                        onValueChange = { code = it },
                        textStyle = TextStyle(
                            fontSize = 12.sp,
                            color = c.text1,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 17.sp
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.height(12.dp))

                Text(L.tools.editorArgs, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(6.dp))
                EditorTextField("", argsJson, { argsJson = it }, "{}")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { runNow() }, enabled = !running) {
                        Text(
                            if (running) L.tools.editorRunning else L.tools.editorRun,
                            color = c.brand,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (PyRuntime.state == PyRuntime.State.STARTING) {
                        Text(L.tools.runtimeStarting, fontSize = 10.5.sp, color = c.text3)
                    } else if (PyRuntime.state == PyRuntime.State.FAILED) {
                        Text(L.tools.runtimeUnavailable, fontSize = 10.5.sp, color = c.text3)
                    }
                }
                Text(L.tools.editorOutput, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp, max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.bg2)
                        .padding(10.dp)
                ) {
                    Text(
                        runOutput ?: L.tools.editorOutputEmpty,
                        fontSize = 12.sp,
                        color = if (runOutput == null) c.text3 else c.text1,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(L.tools.uiPreview, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp, max = 380.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.bg2)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    if (runOutput == null) {
                        Text(L.tools.uiPreviewEmpty, fontSize = 11.sp, color = c.text3)
                    } else {
                        UserToolUi(
                            raw = runOutput ?: "",
                            uiMode = uiMode,
                            onEvent = { ev, v -> rerunPreviewEvent(ev, v) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                Text(L.tools.installTitle, fontSize = 11.5.sp, color = c.text3)
                Spacer(Modifier.height(6.dp))
                Text(L.tools.installThirdParty, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EditorTextField(L.tools.installHint, installName, { installName = it }, "", Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { installNow() },
                        enabled = initial != null && !installing && installName.isNotBlank()
                    ) {
                        Text(
                            if (installing) L.tools.editorRunning else L.tools.installAction,
                            color = c.brand
                        )
                    }
                }
                if (installMsg != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(installMsg!!, fontSize = 11.sp, color = c.text2, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun EditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    Column(modifier) {
        if (label.isNotBlank()) {
            Text(label, fontSize = 11.5.sp, color = c.text3)
            Spacer(Modifier.height(4.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.bg3)
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            if (value.isEmpty() && hint.isNotBlank()) {
                Text(hint, fontSize = 12.5.sp, color = c.text3)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontSize = 13.sp, color = c.text1),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EditorSmallField(
    hint: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.bg3)
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        if (value.isEmpty()) Text(hint, fontSize = 11.sp, color = c.text3)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(fontSize = 11.5.sp, color = c.text1),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * E12：「音色」行右侧的显示名。
 * 存储语义不变（仍写 `stella` / `tide`），只在展示时把历史脏值映射到官方音色名：
 * stella → 海星 · 女声，tide → 暗潮 · 男声，空值 / 语言码（如 zh）/ 其它未知值 → 默认女声。
 * 历史上这里直接把 `ttsVoice` 交给 `TtsEngine.voiceLabel`，旧值 zh 会原样显示成 "zh"。
 */
private fun ttsVoiceDisplayName(L: Str, stored: String): String = when (stored.trim().lowercase()) {
    "stella", "海星" -> L.feat.voiceStellaLabel
    "tide", "暗潮" -> L.feat.voiceTideLabel
    else -> L.settings.defaultFemaleVoice
}

/**
 * 请求级枚举档位选择（当前用于内容安全的 high/low/off）。
 * 档位显示名走 L.tools.level*；high/low/off 的具体含义沿用 feat.safety*Desc。
 * 选中后只调 ToolRegistry.setOption：off 会让注册表把 enabled 置 false，并镜像回旧 prefs。
 */
@Composable
private fun ToolOptionDialog(
    title: String,
    toolId: String,
    option: ToolOption,
    onDismiss: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.bg2)
            ) {
                option.values.forEachIndexed { index, value ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                ToolRegistry.setOption(toolId, option.key, value)
                                onDismiss()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(levelLabel(L, value), fontSize = 13.5.sp, color = c.text1)
                            val desc = levelDescription(L, toolId, value)
                            if (desc != null) {
                                Spacer(Modifier.height(2.dp))
                                Text(desc, fontSize = 10.5.sp, color = c.text3, lineHeight = 15.sp)
                            }
                        }
                        if (option.default == value) {
                            Icon(
                                Icons.Rounded.Check,
                                null,
                                tint = c.brand,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    if (index != option.values.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp)
                                .height(1.dp)
                                .background(c.bg)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(L.common.close) }
        }
    )
}

/** 枚举档位显示名：high/low/off 用新文案（严格/默认/关闭），其它自定义值原样显示 */
private fun levelLabel(L: Str, value: String): String = when (value) {
    "high" -> L.tools.levelHigh
    "low" -> L.tools.levelLow
    "off" -> L.tools.levelOff
    else -> value
}

/** 内容安全的档位补充说明；其它工具的档位暂不显示说明 */
private fun levelDescription(L: Str, toolId: String, value: String): String? {
    if (toolId != "server.safety") return null
    return when (value) {
        "high" -> L.feat.safetyHighDesc
        "low" -> L.feat.safetyLowDesc
        "off" -> L.feat.safetyOffDesc
        else -> null
    }
}

@Composable
private fun ToolSection(title: String, hint: String? = null, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 11.5.sp, color = c.text3)
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(hint, fontSize = 11.5.sp, color = c.text3)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg2)
    ) { content() }
    Spacer(Modifier.height(12.dp))
}

/**
 * 一行动态工具：左侧名称，右侧开关或档位值（与普通 SettingRow 同高）。
 * 普通工具点整行即可切换（和旧 ToolSwitch 一致）；带 option 的工具点整行打开档位选择。
 */
@Composable
private fun ToolSpecRow(
    tool: ToolSpec,
    name: String,
    divider: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenOption: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val option = tool.option
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (option != null) onOpenOption() else onToggle(!tool.enabled)
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, fontSize = 13.5.sp, color = c.text1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            if (option != null) {
                Text(
                    levelLabel(L, option.default),
                    fontSize = 12.5.sp,
                    color = c.text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
            } else {
                CompactSwitch(checked = tool.enabled, onChange = onToggle)
            }
        }
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(c.bg)
            )
        }
    }
}

/** 工具面板里"右侧带当前值 + 箭头"的行，样式和 SettingRow 一致 */
@Composable
private fun ToolRow(name: String, value: String, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 13.5.sp, color = c.text1, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.5.sp, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = c.text3, modifier = Modifier.size(16.dp))
    }
}

/* ---------------- 音色 ---------------- */

@Composable
private fun VoiceDialog(
    voices: List<TtsVoice>,
    loading: Boolean,
    current: String,
    onPreview: (TtsVoice) -> Unit,
    onPick: (TtsVoice) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        title = { Text(L.settings.voiceDialogTitle) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    // 官方 TTS 只有 stella / tide 两个音色，没有设备语音包概念
                    L.feat.voiceHintOfficial,
                    fontSize = 11.5.sp,
                    color = c.text3
                )
                Spacer(Modifier.height(9.dp))
                when {
                    loading -> Text(
                        L.settings.voiceLoadingText,
                        fontSize = 13.sp,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = 18.dp)
                    )

                    voices.isEmpty() -> Text(
                        L.feat.voiceEmptyOfficial,
                        fontSize = 12.5.sp,
                        color = c.text3,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    else -> Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.bg2)
                    ) {
                        voices.forEachIndexed { index, v ->
                            VoiceRow(
                                voice = v,
                                selected = v.id == current,
                                onPreview = { onPreview(v) },
                                onPick = { onPick(v) }
                            )
                            if (index != voices.lastIndex) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp)
                                        .height(1.dp)
                                        .background(c.bg)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(L.common.close) } }
    )
}

@Composable
private fun VoiceRow(
    voice: TtsVoice,
    selected: Boolean,
    onPreview: () -> Unit,
    onPick: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                TtsEngine.voiceLabel(voice.id),
                fontSize = 13.5.sp,
                color = c.text1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                voice.desc,
                fontSize = 10.5.sp,
                color = c.text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            L.settings.voicePreview,
            fontSize = 12.sp,
            color = c.brand,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { onPreview() }
                .padding(horizontal = 9.dp, vertical = 4.dp)
        )
        if (selected) {
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Rounded.Check, null, tint = c.brand, modifier = Modifier.size(16.dp))
        }
    }
}

/* ---------------- 图像生成（local.imagegen）通道配置 ---------------- */

/**
 * 一个通道的配置抽屉（豆包 / 千问 / 自定义）。
 * - 内置通道：API Key（可显示/隐藏）+ Base URL + 模型；
 * - 自定义通道：名称、Base URL、模型、Key、鉴权头/前缀、免鉴权、尺寸模板、三个请求字段开关。
 */
@Composable
private fun ImageGenProviderSheet(
    visible: Boolean,
    which: String,
    app: AppState,
    onDismiss: () -> Unit
) {
    val c = LocalColors.current
    val L = LocalStrings.current
    val settings = app.settings.imageGen
    val isCustom = which == "custom"
    val isDoubao = which == "doubao"

    var apiKey by remember(visible, which) {
        mutableStateOf(
            when {
                isDoubao -> settings.doubaoApiKey
                which == "qwen" -> settings.qwenApiKey
                else -> settings.customApiKey
            }
        )
    }
    var baseUrl by remember(visible, which) {
        mutableStateOf(
            when {
                isDoubao -> settings.doubaoBaseUrl
                which == "qwen" -> settings.qwenBaseUrl
                else -> settings.customBaseUrl
            }
        )
    }
    var model by remember(visible, which) {
        mutableStateOf(
            when {
                isDoubao -> settings.doubaoModel
                which == "qwen" -> settings.qwenModel
                else -> settings.customModel
            }
        )
    }
    var customName by remember(visible, which) { mutableStateOf(settings.customName) }
    var authHeader by remember(visible, which) { mutableStateOf(settings.customAuthHeader) }
    var authPrefix by remember(visible, which) { mutableStateOf(settings.customAuthPrefix) }
    var noAuth by remember(visible, which) { mutableStateOf(settings.customNoAuth) }
    var sizes by remember(visible, which) { mutableStateOf(settings.customSizes) }
    var sendWatermark by remember(visible, which) { mutableStateOf(settings.customSendWatermark) }
    var sendPromptExtend by remember(visible, which) {
        mutableStateOf(settings.customSendPromptExtend)
    }
    var sendSeed by remember(visible, which) { mutableStateOf(settings.customSendSeed) }

    val title = when {
        isDoubao -> L.imageGen.channelDoubao
        which == "qwen" -> L.imageGen.channelQwen
        else -> L.imageGen.channelCustom
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.66f).dp

    SheetScaffold(
        visible = visible,
        title = title,
        onDismiss = onDismiss,
        subtitle = if (isCustom) L.imageGen.customHint else null,
        contentMaxHeight = maxHeight
    ) {
        val defaultBaseUrl = when {
            isDoubao -> ImageGenSettings.DOUBAO_BASE_URL
            which == "qwen" -> ImageGenSettings.QWEN_BASE_URL
            else -> "http://127.0.0.1:11434/v1"
        }
        val defaultModel = when {
            isDoubao -> ImageGenSettings.DOUBAO_MODEL
            which == "qwen" -> ImageGenSettings.QWEN_MODEL
            else -> ""
        }
        if (isCustom) {
            ImageGenTextField(
                label = L.imageGen.customName,
                value = customName,
                placeholder = ImageGenSettings.CUSTOM_NAME,
                onChange = { customName = it }
            )
        }
        ImageGenTextField(
            label = L.imageGen.baseUrl,
            value = baseUrl,
            placeholder = defaultBaseUrl,
            onChange = { baseUrl = it }
        )
        ImageGenTextField(
            label = L.imageGen.model,
            value = model,
            placeholder = defaultModel,
            onChange = { model = it }
        )
        ImageGenTextField(
            label = L.imageGen.apiKey,
            value = apiKey,
            placeholder = "sk-...",
            password = true,
            onChange = { apiKey = it }
        )
        if (isCustom) {
            ImageGenTextField(
                label = L.imageGen.authHeader,
                value = authHeader,
                placeholder = "Authorization",
                onChange = { authHeader = it }
            )
            ImageGenTextField(
                label = L.imageGen.authPrefix,
                value = authPrefix,
                placeholder = "Bearer ",
                onChange = { authPrefix = it }
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.bg2)
            ) {
                SwitchRow(
                    label = L.imageGen.noAuth,
                    checked = noAuth,
                    divider = true
                ) { noAuth = it }
                SwitchRow(
                    label = L.imageGen.sendWatermark,
                    checked = sendWatermark,
                    divider = true
                ) { sendWatermark = it }
                SwitchRow(
                    label = L.imageGen.sendPromptExtend,
                    checked = sendPromptExtend,
                    divider = true
                ) { sendPromptExtend = it }
                SwitchRow(
                    label = L.imageGen.sendSeed,
                    checked = sendSeed
                ) { sendSeed = it }
            }
            Spacer(Modifier.height(10.dp))
            ImageGenTextField(
                label = L.imageGen.sizes,
                value = sizes,
                placeholder = ImageGenSettings.CUSTOM_SIZES_TEXT,
                hint = L.imageGen.sizesHint,
                singleLine = false,
                onChange = { sizes = it }
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(brandGradient))
                .clickable {
                    app.updateSettings { st ->
                        val ig = st.imageGen
                        val updated = when {
                            isDoubao -> ig.copy(
                                doubaoApiKey = apiKey.trim(),
                                doubaoBaseUrl = baseUrl.trim().ifBlank { ImageGenSettings.DOUBAO_BASE_URL },
                                doubaoModel = model.trim().ifBlank { ImageGenSettings.DOUBAO_MODEL }
                            )

                            which == "qwen" -> ig.copy(
                                qwenApiKey = apiKey.trim(),
                                qwenBaseUrl = baseUrl.trim().ifBlank { ImageGenSettings.QWEN_BASE_URL },
                                qwenModel = model.trim().ifBlank { ImageGenSettings.QWEN_MODEL }
                            )

                            else -> {
                                val oldName = ig.customName.trim()
                                    .ifBlank { ImageGenSettings.CUSTOM_NAME }
                                val newName = customName.trim()
                                    .ifBlank { ImageGenSettings.CUSTOM_NAME }
                                ig.copy(
                                    customName = newName,
                                    customApiKey = apiKey.trim(),
                                    customBaseUrl = baseUrl.trim(),
                                    customModel = model.trim(),
                                    customAuthHeader = authHeader.trim().ifBlank { "Authorization" },
                                    customAuthPrefix = authPrefix,
                                    customNoAuth = noAuth,
                                    customSizes = sizes.trim().ifBlank { ImageGenSettings.CUSTOM_SIZES_TEXT },
                                    customSendWatermark = sendWatermark,
                                    customSendPromptExtend = sendPromptExtend,
                                    customSendSeed = sendSeed,
                                    // 改自定义通道名时，如果它正好是默认通道，一并改名，避免默认值悬空
                                    defaultProvider = if (ig.defaultProvider == oldName) {
                                        newName
                                    } else {
                                        ig.defaultProvider
                                    }
                                )
                            }
                        }
                        // 自定义通道名变了：若它正好是默认通道，默认值保持「自定义」语义即可（同一个名字）
                        st.copy(imageGen = updated)
                    }
                    onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                L.common.save,
                fontSize = 14.5.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 配置抽屉里的一个文本输入（底部灰卡片样式，和 API 配置抽屉保持一致） */
@Composable
private fun ImageGenTextField(
    label: String,
    value: String,
    placeholder: String,
    hint: String? = null,
    password: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit
) {
    val c = LocalColors.current
    var show by remember { mutableStateOf(false) }
    Text(
        label,
        fontSize = 11.5.sp,
        color = c.text3,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.bg3)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 13.5.sp, color = c.text3, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = TextStyle(fontSize = 13.5.sp, color = c.text1),
                    singleLine = singleLine,
                    visualTransformation = if (password && !show) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (password) {
                IconBtn(
                    icon = if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    onClick = { show = !show },
                    tint = c.text3,
                    iconSize = 18.dp
                )
            }
        }
    }
    if (hint != null) {
        Text(
            hint,
            fontSize = 10.5.sp,
            color = c.text3,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }
    Spacer(Modifier.height(12.dp))
}