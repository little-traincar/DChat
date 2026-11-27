package com.dchat.app.ui.lang

import androidx.compose.runtime.staticCompositionLocalOf
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Currency
import java.util.Locale

// ============================================================================
// 界面语言 / 地区
//
// 文案表按「语言」分四份（StringsZh / StringsEn / StringsJa / StringsKo），
// 地区只影响数字、日期、货币的格式化，以及作为用户元信息（latest_reminder）的一部分。
// ============================================================================

/** 界面语言。code 存进 SharedPreferences；endonym 同时是 latest_reminder 的 language 取值。 */
enum class AppLanguage(val code: String, val endonym: String, val locale: Locale) {
    EN("en", "English", Locale.ENGLISH),
    JA("ja", "日本語", Locale.JAPAN),
    ZH("zh", "中文", Locale.CHINA),
    KO("ko", "한국어", Locale.KOREAN);

    companion object {
        /** 默认中文：与 AppSettings 的默认值保持一致 */
        val DEFAULT = ZH

        fun fromCode(code: String?): AppLanguage =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
    }
}

/** 地区：决定日期/数字/货币格式，并按官方 latest_reminder 文档作为 location 传给模型。 */
enum class AppRegion(val code: String, val locale: Locale, val dayPattern: String) {
    UK("uk", Locale.UK, "dd/MM"),
    JP("jp", Locale.JAPAN, "MM/dd"),
    CN("cn", Locale.CHINA, "MM-dd"),
    KR("kr", Locale.KOREAN, "MM.dd");

    /**
     * 地区名按当前界面语言书写（同时作为 latest_reminder 的 location 字段；
     * 官方文档只说 location 可为空、可含逗号，没有规定书写语言，这里跟随界面语言最自然）。
     */
    fun nameIn(language: AppLanguage): String = when (this) {
        UK -> when (language) {
            AppLanguage.EN -> "United Kingdom"
            AppLanguage.JA -> "イギリス"
            AppLanguage.ZH -> "英国"
            AppLanguage.KO -> "영국"
        }

        JP -> when (language) {
            AppLanguage.EN -> "Japan"
            AppLanguage.JA -> "日本"
            AppLanguage.ZH -> "日本"
            AppLanguage.KO -> "일본"
        }

        CN -> when (language) {
            AppLanguage.EN -> "China"
            AppLanguage.JA -> "中国"
            AppLanguage.ZH -> "中国"
            AppLanguage.KO -> "중국"
        }

        KR -> when (language) {
            AppLanguage.EN -> "South Korea"
            AppLanguage.JA -> "韓国"
            AppLanguage.ZH -> "韩国"
            AppLanguage.KO -> "한국"
        }
    }

    companion object {
        val DEFAULT = CN

        fun fromCode(code: String?): AppRegion =
            values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
    }
}

// ============================================================================
// 文案表：按界面区域切成几段，避免单个 data class 的构造参数过多，
// 也让「某一块漏翻了」一眼看得出来。所有字段都没有默认值 —— 少翻一条就编译不过。
// ============================================================================

/** 通用按钮 / 兜底文案 */
data class CommonStr(
    val exitConfirm: String,
    val cancel: String,
    val save: String,
    val close: String,
    val unknownError: String
)

/** 会话页（顶栏、抽屉、空态、消息气泡、输入区、弹窗、Toast） */
data class ChatStr(
    // 会话操作菜单：右上角「⋮」保留导出 / 导入；重命名 / 删除会话在抽屉行「⋯」里
    val menuExport: String,
    val menuImport: String,
    val menuRename: String,
    val menuDeleteChat: String,
    // 删除会话二次确认弹窗
    val deleteChatHint: String,
    val deleteChatAction: String,
    // 抽屉
    val drawerMe: String,
    val drawerTapAvatar: String,
    val drawerBalance: String,
    val drawerNewChat: String,
    val drawerSearch: String,
    val drawerSettings: String,
    // 空态
    val emptyGreeting: String,
    val emptySubtitle: String,
    val suggestWritingTitle: String,
    val suggestWritingDesc: String,
    val suggestWritingPrompt: String,
    val suggestCodeTitle: String,
    val suggestCodeDesc: String,
    val suggestCodePrompt: String,
    val suggestSearchTitle: String,
    val suggestSearchDesc: String,
    val suggestSearchPrompt: String,
    val suggestFileTitle: String,
    val suggestFileDesc: String,
    val suggestFilePrompt: String,
    // 回复下方的操作
    val actionCopy: String,
    val actionRead: String,
    val actionStopRead: String,
    val actionSpeakVoice: String,
    val actionStopVoice: String,
    val copied: String,
    val noSpeakableText: String,
    // 输入区
    val chipThinking: String,
    val chipSearch: String,
    val chipTts: String,
    val composerHint: String,
    val holdToTalk: String,
    val releaseToEnd: String,
    // 工具步骤时间线
    val stepThinking: String,
    val stepThought: String,
    val stepSearching: String,
    val stepSearched: String,
    val stepOpening: String,
    val stepOpened: String,
    // 语音气泡
    val voiceSynthesizing: String,
    val voiceUnavailable: String,
    val voiceLabel: String,
    val voiceExpandText: String,
    val voiceCollapseText: String,
    // 位置消息
    val locationLabel: String,
    val locationUnknown: String,
    val locationOpenFailed: String,
    val locationSendTitle: String,
    val locationSendHint: String,
    val locationSendAction: String,
    // 图片 / 位置占位（同时用于会话标题预览与复制）
    val imagePlaceholder: String,
    val locationPlaceholder: String,
    val removeImageDesc: String,
    /** 全屏看图：加载失败 / 加载中 */
    val imageLoadFailed: String,
    val loading: String,
    // Toast / 提示
    val toastLocating: String,
    val toastMaxImages: String,
    val toastImageReadFailed: String,
    val toastImagesPartial: String,
    val toastPhotoSaveFailed: String,
    val toastNoCamera: String,
    val toastCameraTempFailed: String,
    val toastNeedLocation: String,
    val toastExported: String,
    val toastExportFailed: String,
    val toastImportEmpty: String,
    val toastImported: String,
    val toastImportFailed: String,
    val toastMicOn: String,
    val toastNeedMic: String,
    val toastFileRead: String,
    val toastFileReadFailed: String,
    val toastImagesAllFailed: String,
    val toastImagesSomeFailed: String,
    val toastRequestFailed: String,
    val requestBuildFailed: String,
    // 语音识别（ASR）
    val asrTranscribing: String,
    val asrBusy: String,
    val asrNoPermission: String,
    val asrNeedApiKey: String,
    val asrMicOpenFailed: String,
    val asrMicStartFailed: String,
    val asrRecordFailed: String,
    val asrTooShort: String,
    val asrEmptyResult: String,
    val asrNetFail: String,
    val asrTimeout: String,
    val asrIoError: String,
    val asrFailed: String,
    // 语音识别接口错误（按官方 ASR 文档错误码）
    val asrHttpBadFormat: String,
    val asrHttpForbidden: String,
    val asrHttpUploadTimeout: String,
    val asrHttpTooLarge: String,
    val asrHttpTooMany: String,
    val asrHttpBusy: String,
    val asrHttpReadTimeout: String,
    val asrHttpFail: String,
    val asrHttpFailDetail: String,
    // 语音合成（TTS）
    val ttsInitFailed: String,
    val ttsNotReady: String,
    val ttsNoZhPack: String,
    val ttsFailed: String,
    // 重命名会话
    val renameTitle: String,
    val renameHint: String,
    val renamePlaceholder: String,
    // 加号面板
    val plusAlbum: String,
    val plusCamera: String,
    val plusFile: String,
    val plusLocation: String,
    // 发给模型的文本（不是界面文案，但同样跟随界面语言）
    val modelLocationNote: String,
    val modelImageNotSent: String,
    val modelToolLimit: String,
    // 没配 API Key 时的本地模拟回复
    val mockHeader: String,
    val mockYouSaid: String,
    val mockImages: String,
    val mockVoice: String,
    val mockGoSettings: String,
    val mockImageOnly: String,
    // ---- 第三期：会话权限（顶栏盾牌）----
    val permTitle: String,
    val permRead: String,
    val permReadDesc: String,
    val permWorkspace: String,
    val permWorkspaceDesc: String,
    val permFull: String,
    val permFullDesc: String,
    val permFullRisk: String,
    /** 工具被当前会话权限拒绝：%s = 当前档位名 */
    val permDenied: String,
    // ---- 第三期：工具关闭/权限不足的入口提示 ----
    val toolOffFileParse: String,
    /** 图片理解关闭时追加给模型的说明：%d = 张数 */
    val modelImageUnderstandOff: String,
    val toolOffAsr: String,
    val toolOffTts: String,
    val toolOffLocation: String,
    val toolOffCamera: String,
    val toolOffGallery: String,
    // ---- 第三期：客户端工具调用卡片 ----
    val toolCallTitle: String,
    val toolCallArgs: String,
    val toolCallResult: String,
    val toolCallError: String,
    val toolCallDuration: String,
    /** 工具输出超过 64KB 截断时的省略提示：%d = 省略字符数 */
    val toolOutputTruncated: String,
    // ---- 第三期补充：一次性工具审批 / 超时 / 熔断 ----
    val approveAllow: String,
    val approveReject: String,
    val approveHint: String,
    /** 文件解析审批正文：%s = 文件名 */
    val approveFileParse: String,
    val approveOcr: String,
    val toolRejected: String,
    /** 拒绝结果回给模型：%s = 工具名，第二个 %s = 错误码 */
    val modelToolRejected: String,
    /** 工具超时：%d = 秒，%s = 错误码 */
    val toolTimeout: String,
    val toolPaused: String,
    /** {0}=剩余秒数 */
    val toolPausedRetry: String,
    val generating: String,
    val stopped: String,
    val stoppedEmpty: String,
    /** {0}=文件数，{1}=大小 */
    val deleteChatFilesHint: String,
    val downloadSaveToPhone: String,
    // ---- 1.0.3（D97 / D99）----
    /** 回答因 max_tokens 被截断时的气泡提示 */
    val answerTruncated: String,
    /** 文档配额（每请求 ≤20）超限提示：%d = 上限 */
    val docLimitNotice: String,
    /** 停止生成时补回给模型的占位 tool_result 文案 */
    val toolStoppedResult: String,
    /** 下载卡「大小未知」（服务端没给 Content-Length / 落盘文件读不到长度） */
    val sizeUnknown: String,
    /** Python 输出落盘提示：%s = 相对路径，%s = 大小 */
    val pythonOutputSaved: String,
    /** Python 工具卡「完整输出：文件名（大小）」：%s = 文件名，%s = 大小 */
    val pythonFullOutput: String,
    /** 文本预览截断提示：%s = 只显示的大小 */
    val previewTruncated: String,
    // ---- 聊天页「⋮」菜单：用户工具入口 + 工具弹层 ----
    /** 菜单项/弹层标题 */
    val userTools: String,
    /** 空列表提示 */
    val userToolsEmpty: String,
    /** 工具卡片标签：web 模式 */
    val userToolsWeb: String,
    /** 工具卡片标签：data 模式 */
    val userToolsData: String
)

/** 设置页 */
data class SettingsStr(
    val title: String,
    val tapChangeAvatar: String,
    // 外观
    val sectionAppearance: String,
    val darkMode: String,
    val fontSize: String,
    val background: String,
    val bgCustom: String,
    val bgDefault: String,
    // 对话
    val sectionChat: String,
    val modelNickname: String,
    // 语言和地区
    val sectionLangRegion: String,
    val rowRegion: String,
    val rowLanguage: String,
    val dialogPickRegion: String,
    val dialogPickLanguage: String,
    // 语言 / 地区选择抽屉：标题下的小灰字说明 + 选中行右侧的「当前」标记
    val languageSheetHint: String,
    val regionSheetHint: String,
    val sheetCurrentLabel: String,
    // 系统和工具
    val sectionSystemTools: String,
    val systemPrompt: String,
    val notSet: String,
    val apiConfig: String,
    val apiNotConfigured: String,
    val apiConfiguredNoModel: String,
    val apiConfiguredModel: String,
    val tools: String,
    val toolsCount: String,
    val innerBrowser: String,
    val tapToOpen: String,
    val resetPythonRuntime: String,
    val resetPythonRuntimeDone: String,
    // 数据
    val sectionData: String,
    val exportAllData: String,
    val importFromFile: String,
    val deleteAllData: String,
    val deleteAllDialogTitle: String,
    val deleteAllDialogText: String,
    val deleteAction: String,
    val toastDataCleared: String,
    // 关于
    val sectionAbout: String,
    val aboutDchat: String,
    /** 关于抽屉正文；`{v}` 会被替换为版本号 */
    val aboutBody: String,
    // 字号
    val fontSmall: String,
    val fontStandard: String,
    val fontLarge: String,
    val fontExtraLarge: String,
    val fontDialogTitle: String,
    // API 配置抽屉
    val apiBaseUrl: String,
    val apiKey: String,
    val apiModel: String,
    val apiNoModel: String,
    val apiKeyLocalHint: String,
    val apiTesting: String,
    val apiTestOk: String,
    val apiTestFail: String,
    val apiTestBtn: String,
    // 模型抽屉
    val modelSheetTitle: String,
    val modelNeedKey: String,
    val modelFetchBtn: String,
    val modelNoReturn: String,
    val modelFetchFail: String,
    val modelFetching: String,
    val modelNone: String,
    val modelFetchHint: String,
    val modelManual: String,
    val modelManualHint: String,
    val modelUse: String,
    // 系统提示词弹窗
    val systemPromptHint: String,
    val systemPromptPlaceholder: String,
    // 昵称弹窗
    val nickDialogTitle: String,
    // 工具面板
    val toolsSheetTitle: String,
    val toolRoundsLabel: String,
    val roundsUnlimited: String,
    val roundsN: String,
    val serverTools: String,
    val toolSearch: String,
    val toolReadUrl: String,
    val systemTools: String,
    val toolAsr: String,
    val toolTts: String,
    val toolVoice: String,
    val defaultFemaleVoice: String,
    val toolOcr: String,
    val userTools: String,
    val toolsNone: String,
    // 音色弹窗
    val voiceDialogTitle: String,
    val voiceHint: String,
    val voiceLoadingText: String,
    val voiceEmpty: String,
    val voicePreview: String,
    val voiceQualityVeryHigh: String,
    val voiceQualityHigh: String,
    val voiceQualityNormal: String,
    val voiceQualityLow: String,
    val voiceMeta: String,
    val voicePreviewText: String,
    val toastVoiceSwitched: String
)

/** 用量统计页 */
data class UsageStr(
    val title: String,
    val currentSession: String,
    val noSession: String,
    val statInput: String,
    val statOutput: String,
    val statTotal: String,
    val statCost: String,
    val sessionFoot: String,
    val dailyTitle: String,
    val dailySubtitle: String,
    val hourlyTitle: String,
    val hourlySubtitle: String,
    val hourTemplate: String,
    val byModelTitle: String,
    val byModelSubtitle: String,
    val byModelEmpty: String,
    val modelMeta: String,
    val costTitle: String,
    val costEmpty: String,
    val cacheHitInput: String,
    val cacheMissInput: String,
    val cacheHitRate: String,
    val cacheRateDetail: String,
    val costOutput: String,
    val costTotal: String,
    val costTotalDetail: String,
    val emptyChart: String,
    val modelUnrecorded: String,
    // ---- 改版：7/30 天切换、堆叠柱状图、当天明细、缓存命中率进度条 ----
    /** 天数切换按钮：%d = 7 / 30 */
    val rangeDays: String,
    /** 每日卡片没选中某天时的操作提示 */
    val dailyHint: String,
    /** 堆叠柱图例（与柱体三色一一对应） */
    val legendHit: String,
    val legendMiss: String,
    val legendOutput: String,
    /** 选中某天的卡片副标题：%s=日期，%d=请求数，%s=命中率 */
    val daySummary: String,
    val dayDetailTitle: String,
    val dayNoData: String,
    val requestsLabel: String,
    /** 请求数数值：%d */
    val requestCount: String,
    /** 命中率说明：%s=命中 tokens，%s=总输入 tokens */
    val hitRateDetail: String,
    val hitRateEmpty: String,
    /** 输入拆分：%s=命中 tokens，%s=未命中 tokens */
    val inputBreakdown: String,
    val dailyListTitle: String,
    /** 每日明细为空：%d=天数 */
    val dailyListEmpty: String,
    /** 每日明细行副文案：%d=请求数，%s=命中率，%s=输出 tokens */
    val dailyRowMeta: String,
    val todayLabel: String,
    /** 估算价格免责声明（当前会话开销卡片底部） */
    val estimateNote: String
)

/** 内置浏览器 / 背景页 / 文件解析 / 定位 / ZIP 导入导出 / 网络错误 */
data class MiscStr(
    // 内置浏览器
    val browserEnterUrl: String,
    val browserUrlHint: String,
    val browserSearchHint: String,
    val browserGo: String,
    // 背景页
    val backgroundTitle: String,
    val backgroundPreview: String,
    val sectionZoom: String,
    val zoomFill: String,
    val zoomStretch: String,
    val zoomCrop: String,
    val sectionImage: String,
    val pickFromAlbum: String,
    val restoreDefault: String,
    val pureColor: String,
    val backgroundHint: String,
    // 文件解析
    val fileNameUnnamed: String,
    val fileUnsupported: String,
    val fileReadFailed: String,
    val fileTooLarge: String,
    val fileBinary: String,
    val fileEmpty: String,
    val fileWrap: String,
    // 定位
    val locServiceUnavailable: String,
    val locSwitchOff: String,
    val locNoPermission: String,
    val locFailed: String,
    val locGotCurrent: String,
    // 历史记录 ZIP
    val zipCannotWrite: String,
    val zipExportFailed: String,
    val zipCannotRead: String,
    val zipNotDeepSeekFirst: String,
    val zipIndexBad: String,
    val zipMissingManifest: String,
    val zipImportFailed: String,
    val zipManifestBad: String,
    val zipVersionUnsupported: String,
    val zipSessionBad: String,
    // review 修复（P1 / OOM）：ZIP 导入的三道上限（超限中止整次导入）
    val zipEntryTooLarge: String,
    val zipTooManyEntries: String,
    val zipTotalTooLarge: String,
    // 网络 / 接口通用错误
    val netError: String,
    val emptyResponse: String,
    val parseFailed: String,
    val balanceNoInfos: String
)

/**
 * 本轮新增能力的文案（服务端文件解析 / OCR / 官方 TTS / 内容安全 / 会话标题）。
 * 单独一段，是为了集中翻译、方便对照；模板里的 %s / %d 由 Str.format 填。
 */
data class FeatStr(
    // ---- 文件解析（本地 + 服务端）----
    val fileUnsupportedLocal: String,
    val fileTooLargeLocal: String,
    val fileUnsupportedServer: String,
    val fileTooLargeServer: String,
    val fileSizeUnknown: String,
    val fileTimeout: String,
    val fileParseFailed: String,
    val fileServerHead: String,
    val fileServerBody: String,
    val fileNeedApiKey: String,
    val fileUploading: String,
    val fileDone: String,
    // ---- OCR ----
    val ocrFailed: String,
    val ocrServerFailed: String,
    val ocrLimit: String,
    val ocrSupplementHeader: String,
    val ocrParseFail: String,
    val ocrBad400: String,
    val ocrForbidden: String,
    val ocrInsufficient: String,
    val ocrUploadTimeout: String,
    val ocrTooLarge: String,
    val ocrTooMany: String,
    val ocrUploadFail500: String,
    val ocrBusy: String,
    val ocrTimeout504: String,
    val ocrHttpFail: String,
    val ocrHttpFailDetail: String,
    val ocrWithReason: String,
    val ocrPassword: String,
    val ocrTooManyPages: String,
    val ocrFileTooLarge: String,
    val ocrTimeout: String,
    val ocrBusyShort: String,
    val ocrUnsupported: String,
    // ---- Files API 上传 ----
    val uploadResponseParseFail: String,
    val uploadNoFileId: String,
    val uploadBad400: String,
    val uploadBad400Detail: String,
    val uploadForbidden: String,
    val uploadInsufficient: String,
    val uploadTimeout: String,
    val uploadTooLarge: String,
    val uploadTooMany: String,
    val uploadIncomplete: String,
    val uploadBusy: String,
    val uploadParseTimeout: String,
    val uploadHttpFail: String,
    val uploadHttpFailDetail: String,
    // ---- 官方 TTS ----
    val ttsNeedApiKey: String,
    val ttsWriteFailed: String,
    val ttsPlayFailed: String,
    val ttsTimeout: String,
    val ttsFailed: String,
    val ttsTruncateChars: String,
    val ttsTruncateBytes: String,
    val ttsJoin: String,
    val ttsEmptyResponse: String,
    val ttsBadModeration: String,
    val ttsBadNoText: String,
    val ttsForbidden: String,
    val ttsInsufficient: String,
    val ttsTooLarge: String,
    val ttsTooMany: String,
    val ttsBusy: String,
    val ttsHttpTimeout: String,
    val ttsHttpFail: String,
    val ttsHttpFailDetail: String,
    // ---- 内容安全 / 拒答 ----
    val inputBlocked: String,
    val refusalBase: String,
    val refusalWithDetail: String,
    // ---- 设置页新增项 ----
    val titleToggle: String,
    val safetyLabel: String,
    val safetyOff: String,
    val safetyLow: String,
    val safetyHigh: String,
    val safetyOffDesc: String,
    val safetyLowDesc: String,
    val safetyHighDesc: String,
    val safetyDialogText: String,
    val voiceHintOfficial: String,
    val voiceEmptyOfficial: String,
    val voiceStellaLabel: String,
    val voiceStellaDesc: String,
    val voiceTideLabel: String,
    val voiceTideDesc: String
)

/**
 * 工具页（第二期起从 ToolRegistry 动态渲染）的文案。
 * 名称 / 说明按工具 id 查表：内置工具有多语言；查不到（如用户自建工具）时 UI 回退到
 * `ToolSpec.name` / `ToolSpec.description`（description 保留中文，本来也是给模型看的）。
 */
data class ToolsStr(
    // 三组标题 + 组标题旁的灰色提示（服务器：需联网计费 / 本地：本机免费）
    val groupServer: String,
    val groupServerHint: String,
    val groupLocal: String,
    val groupLocalHint: String,
    val groupUser: String,
    // 「我的工具」为空时的占位
    val empty: String,
    // 请求级枚举档位（server.safety: high/low/off）
    val levelHigh: String,
    val levelLow: String,
    val levelOff: String,
    // tool id -> 界面显示名 / 一行说明
    val names: Map<String, String>,
    val descriptions: Map<String, String>,
    // ---- 第四期 4a：我的工具列表 / 编辑器 / 试运行 / 安装依赖 ----
    val userNewTool: String,
    val userEdit: String,
    val userDelete: String,
    val userDeleteConfirmTitle: String,
    val userDeleteConfirmText: String,
    val editorTitleNew: String,
    val editorTitleEdit: String,
    val editorName: String,
    val editorNameHint: String,
    val editorDescription: String,
    val editorDescriptionHint: String,
    /** 网络口径 B：工具可联网的风险提示 */
    val editorNetworkNotice: String,
    val editorParams: String,
    val editorParamName: String,
    val editorParamType: String,
    val editorParamRequired: String,
    val editorParamDescription: String,
    val editorAddParam: String,
    val editorDeleteParam: String,
    val editorPermission: String,
    val editorEntryFunction: String,
    val editorCode: String,
    val editorArgs: String,
    val editorRun: String,
    val editorRunning: String,
    val editorOutput: String,
    val editorOutputEmpty: String,
    val editorNameInvalid: String,
    val editorNameTaken: String,
    val editorDescriptionRequired: String,
    val editorCodeRequired: String,
    val editorParamsInvalid: String,
    val editorTimeout: String,
    val editorTimeoutHint: String,
    val editorTimeoutInvalid: String,
    val editorSaved: String,
    /** 保存失败：%s */
    val editorSaveFailed: String,
    val installTitle: String,
    val installHint: String,
    val installAction: String,
    val installThirdParty: String,
    /** 已安装：%s */
    val installSuccess: String,
    /** 安装失败：%s */
    val installFailed: String,
    val installNativeIndexOnly: String,
    val runtimeUnavailable: String,
    val runtimeStarting: String,
    // ---- 4b：工具 UI ----
    val uiMode: String,
    val uiModeData: String,
    val uiModeWeb: String,
    val uiModeWebHint: String,
    val uiModeHint: String,
    val uiPreview: String,
    val uiPreviewEmpty: String,
    val uiInvalidJson: String,
    val uiTooLarge: String,
    val uiImageUnavailable: String,
    val uiEventFailed: String,
    val openTool: String,
    val resetState: String,
    val rerun: String,
    // ---- UI Schema v2（静态视图 / 快照 / 动作） ----
    val uiStale: String,
    val uiActionConfirmTitle: String,
    val uiConfirmAction: String,
    /** 动作失败：%s */
    val uiActionFailed: String,
    /** 页面打开失败：%s */
    val uiOpenFailed: String,
    /** Python 运行时不可用，必须包含「设置 → 工具 → 重置」的恢复入口 */
    val uiPyUnavailable: String,
    /** 表单必填：%s */
    val uiFormRequired: String,
    /** 表单数字校验：%s */
    val uiFormNumber: String,
    val uiListEmpty: String,
    val uiWorking: String,
    val uiSubmit: String,
    val uiRetry: String
)

/**
 * 某个「语言 + 地区」组合下的整套文案与格式化规则。
 * 文本部分按语言共享，地区只影响格式化，所以实例很少（4×4 缓存）。
 */
class Str internal constructor(
    val language: AppLanguage,
    val region: AppRegion,
    val common: CommonStr,
    val chat: ChatStr,
    val settings: SettingsStr,
    val usage: UsageStr,
    val misc: MiscStr,
    val feat: FeatStr,
    val tools: ToolsStr,
    /** 第四期 4c：内置原生工具（fs.* / bg.* / schedule.*）的审批、错误与通知文案 */
    val local: NativeStr,
    /** 任务管理界面（会话页「⋮」入口 + 后台任务页 + 定时任务页） */
    val tasks: TasksStr,
    /** 「当前会话的文件」页面 */
    val files: FilesStr,
    /** 图像生成（local.imagegen）设置与错误文案 */
    val imageGen: ImageGenStr
) {
    /** 界面语言的 Locale（日期 / 数字 / 星期） */
    val locale: Locale get() = language.locale

    /** 当前地区的 Locale（数字 / 货币 / 短日期） */
    val regionLocale: Locale get() = region.locale

    /** 语言名用该语言自己的写法（English / 日本語 / 中文 / 한국어） */
    fun languageName(language: AppLanguage): String = language.endonym

    /** 地区名按当前界面语言显示 */
    fun regionName(region: AppRegion): String = region.nameIn(this.language)
}

// ============================================================================
// 全局入口
// ============================================================================

/**
 * 非 Compose 代码（Toast、数据层、网络错误）读 [Lang.current]；
 * Compose 里统一用 `LocalStrings.current`。两者在 DchatTheme 重组时同步。
 */
object Lang {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Str>()

    @Volatile
    var current: Str = of(AppLanguage.DEFAULT, AppRegion.DEFAULT)
        private set

    fun of(language: AppLanguage, region: AppRegion): Str =
        cache.getOrPut(language.code + "|" + region.code) {
            Str(
                language = language,
                region = region,
                common = commonTable(language),
                chat = chatTable(language),
                settings = settingsTable(language),
                usage = usageTable(language),
                misc = miscTable(language),
                feat = featTable(language),
                tools = toolsTable(language),
                local = nativeTable(language),
                tasks = tasksTable(language),
                files = filesTable(language),
                imageGen = imageGenTable(language)
            )
        }

    fun of(language: String?, region: String?): Str =
        of(AppLanguage.fromCode(language), AppRegion.fromCode(region))

    /** 由 DchatTheme 在每次重组时同步，保证开关切完立刻生效 */
    fun sync(str: Str) {
        current = str
    }

    private fun commonTable(language: AppLanguage): CommonStr = when (language) {
        AppLanguage.EN -> commonEn()
        AppLanguage.JA -> commonJa()
        AppLanguage.ZH -> commonZh()
        AppLanguage.KO -> commonKo()
    }

    private fun chatTable(language: AppLanguage): ChatStr = when (language) {
        AppLanguage.EN -> chatEn()
        AppLanguage.JA -> chatJa()
        AppLanguage.ZH -> chatZh()
        AppLanguage.KO -> chatKo()
    }

    private fun settingsTable(language: AppLanguage): SettingsStr = when (language) {
        AppLanguage.EN -> settingsEn()
        AppLanguage.JA -> settingsJa()
        AppLanguage.ZH -> settingsZh()
        AppLanguage.KO -> settingsKo()
    }

    private fun usageTable(language: AppLanguage): UsageStr = when (language) {
        AppLanguage.EN -> usageEn()
        AppLanguage.JA -> usageJa()
        AppLanguage.ZH -> usageZh()
        AppLanguage.KO -> usageKo()
    }

    private fun miscTable(language: AppLanguage): MiscStr = when (language) {
        AppLanguage.EN -> miscEn()
        AppLanguage.JA -> miscJa()
        AppLanguage.ZH -> miscZh()
        AppLanguage.KO -> miscKo()
    }

    private fun featTable(language: AppLanguage): FeatStr = when (language) {
        AppLanguage.EN -> featEn()
        AppLanguage.JA -> featJa()
        AppLanguage.ZH -> featZh()
        AppLanguage.KO -> featKo()
    }

    private fun toolsTable(language: AppLanguage): ToolsStr = when (language) {
        AppLanguage.EN -> toolsEn()
        AppLanguage.JA -> toolsJa()
        AppLanguage.ZH -> toolsZh()
        AppLanguage.KO -> toolsKo()
    }

    private fun nativeTable(language: AppLanguage): NativeStr = when (language) {
        AppLanguage.EN -> nativeEn()
        AppLanguage.JA -> nativeJa()
        AppLanguage.ZH -> nativeZh()
        AppLanguage.KO -> nativeKo()
    }

    private fun tasksTable(language: AppLanguage): TasksStr = when (language) {
        AppLanguage.EN -> tasksEn()
        AppLanguage.JA -> tasksJa()
        AppLanguage.ZH -> tasksZh()
        AppLanguage.KO -> tasksKo()
    }

    private fun filesTable(language: AppLanguage): FilesStr = when (language) {
        AppLanguage.EN -> filesEn()
        AppLanguage.JA -> filesJa()
        AppLanguage.ZH -> filesZh()
        AppLanguage.KO -> filesKo()
    }

    private fun imageGenTable(language: AppLanguage): ImageGenStr = when (language) {
        AppLanguage.EN -> imageGenEn()
        AppLanguage.JA -> imageGenJa()
        AppLanguage.ZH -> imageGenZh()
        AppLanguage.KO -> imageGenKo()
    }
}

/** Compose 里的统一入口：`val L = LocalStrings.current` */
val LocalStrings = staticCompositionLocalOf { Lang.current }

// ============================================================================
// 格式化：模板填空 + 数字 / 日期 / 货币
// ============================================================================

/** 模板填空。模板名形如 `"%d 个会话"`，参数按顺序替换。 */
fun Str.format(template: String, vararg args: Any?): String =
    String.format(Locale.ROOT, template, *args)

/** 中文的「条」「个」这类量词差异由各语言模板负责，这里只做数字本地化。 */
private fun Str.number(value: Long): String =
    NumberFormat.getIntegerInstance(regionLocale).format(value)

/** 按地区本地化的 CNY 金额（成本本来就以人民币计价，不做汇率换算）。 */
fun Str.money(cost: Double): String =
    money(cost, currencyCode = "CNY")

/** 按地区本地化金额；[currencyCode] 来自接口（余额可能是 USD）。 */
fun Str.money(cost: Double, currencyCode: String): String {
    val symbol = currencySymbol(currencyCode, regionLocale)
    val decimals = if (cost >= 1.0 || cost <= 0.0) 2 else 4
    val n = NumberFormat.getNumberInstance(regionLocale).apply {
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }.format(cost)
    return "$symbol $n"
}

/** 顶部汇总用短格式：太小就显示 <0.01，避免金额挤不下 */
fun Str.moneyCompact(cost: Double): String {
    val symbol = currencySymbol("CNY", regionLocale)
    return when {
        cost <= 0.0 -> "$symbol 0.00"
        cost < 0.01 -> "$symbol <0.01"
        else -> "$symbol " + String.format(regionLocale, "%.2f", cost)
    }
}

/**
 * 百分比：保留 1 位小数，小数点符号跟随地区（如 80.0% / 80,0 %）。
 * ratio 是 0..1；传 0 显示 0.0%，不会出现 NaN（调用方负责空态判断）。
 */
fun Str.percent(ratio: Double): String =
    String.format(regionLocale, "%.1f%%", ratio.coerceIn(0.0, 1.0) * 100.0)

/** 余额占位（没配 Key / 还没查到时） */
fun Str.balanceDash(currencyCode: String? = "CNY"): String {
    val symbol = currencySymbol(currencyCode ?: "CNY", regionLocale)
    return "$symbol --"
}

private fun currencySymbol(code: String, locale: Locale): String =
    runCatching { Currency.getInstance(code).getSymbol(locale) }
        .getOrDefault(if (code.equals("USD", ignoreCase = true)) "$" else "CNY")
        .ifBlank { code }

/** tokens 展示：K / M 缩写，分组符号按地区 */
fun Str.tokens(value: Long): String {
    val loc = regionLocale
    return when {
        value >= 999_950L -> String.format(loc, "%.2fM", value / 1_000_000.0)
        value >= 1_000L -> String.format(loc, "%.1fK", value / 1_000.0)
        else -> number(value)
    }
}

/** 纯数字（图表刻度等） */
fun Str.plainNumber(value: Long): String = number(value)

/** 用量页横轴日期：地区决定 MM-dd / dd/MM / MM.dd */
fun Str.dayLabel(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(region.dayPattern, regionLocale))

/** 用量页横轴小时 */
fun Str.hourLabel(hour: Int): String = format(usage.hourTemplate, hour)

/** latest_reminder 里的星期：必须用 language 对应的语言书写 */
fun Str.weekdayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, locale)

/** 会话标题里历史上写入的固定占位词，按当前语言重新显示 */
fun Str.conversationTitle(raw: String): String {
    val t = raw.trim()
    return when {
        t.isEmpty() || t in NEW_CHAT_TITLES -> chat.drawerNewChat
        t in IMAGE_TITLES -> chat.imagePlaceholder
        t in LOCATION_TITLES -> chat.locationPlaceholder
        else -> raw
    }
}

/** 这个标题是不是「默认未命名」（各语言下都算，兼容老数据） */
fun Str.isDefaultChatTitle(raw: String): Boolean {
    val t = raw.trim()
    return t.isEmpty() || t in NEW_CHAT_TITLES
}

/** 各语言下「新对话」这个默认标题（老数据里固定是中文那次写的） */
private val NEW_CHAT_TITLES = setOf("新对话", "New chat", "新しい会話", "새 대화")

/** 图片消息被当标题时的占位词 */
private val IMAGE_TITLES = setOf("[图片]", "[Image]", "[画像]", "[이미지]")

/** 位置消息被当标题时的占位词 */
private val LOCATION_TITLES = setOf("[位置]", "[Location]", "[位置情報]", "[위치]")
