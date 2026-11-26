package com.dchat.app.ui.lang

/**
 * 「当前会话的文件」页面文案（workspace 递归列表 / 查看 / 导出 / 删除）。
 */
data class FilesStr(
    val title: String,
    val hint: String,
    val empty: String,
    val btnView: String,
    val btnExport: String,
    val btnDelete: String,
    val btnCancel: String,
    val btnClose: String,
    val deleteTitle: String,
    val deleteText: String,
    val deleteDone: String,
    /** 已导出到「下载/DChat」：%s */
    val exportSuccess: String,
    val exportUnsupported: String,
    /** 导出失败：%s */
    val exportFailed: String,
    /** 无法打开文件：%s */
    val openFailed: String
)

internal fun filesZh(): FilesStr = FilesStr(
    title = "会话文件",
    hint = "仅显示当前会话工作区里的文件",
    empty = "当前会话还没有文件",
    btnView = "查看",
    btnExport = "导出",
    btnDelete = "删除",
    btnCancel = "取消",
    btnClose = "关闭",
    deleteTitle = "删除文件？",
    deleteText = "删除后不可恢复。",
    deleteDone = "已删除",
    exportSuccess = "已导出到「下载/DChat」：%s",
    exportUnsupported = "当前系统版本不支持直接写入手机下载目录",
    exportFailed = "导出失败：%s",
    openFailed = "无法打开文件：%s"
)

internal fun filesEn(): FilesStr = FilesStr(
    title = "Chat files",
    hint = "Only files in this chat's workspace are shown",
    empty = "No files in this chat yet",
    btnView = "Open",
    btnExport = "Export",
    btnDelete = "Delete",
    btnCancel = "Cancel",
    btnClose = "Close",
    deleteTitle = "Delete file?",
    deleteText = "This cannot be undone.",
    deleteDone = "Deleted",
    exportSuccess = "Exported to Download/DChat: %s",
    exportUnsupported = "This system version does not support writing directly to the Downloads folder",
    exportFailed = "Export failed: %s",
    openFailed = "Cannot open file: %s"
)

internal fun filesJa(): FilesStr = FilesStr(
    title = "会話ファイル",
    hint = "この会話のワークスペース内のファイルのみ表示します",
    empty = "この会話にはまだファイルがありません",
    btnView = "開く",
    btnExport = "エクスポート",
    btnDelete = "削除",
    btnCancel = "キャンセル",
    btnClose = "閉じる",
    deleteTitle = "ファイルを削除しますか？",
    deleteText = "削除すると元に戻せません。",
    deleteDone = "削除しました",
    exportSuccess = "「ダウンロード/DChat」にエクスポートしました：%s",
    exportUnsupported = "このシステムバージョンはダウンロードフォルダへの直接書き込みに対応していません",
    exportFailed = "エクスポートに失敗しました：%s",
    openFailed = "ファイルを開けません：%s"
)

internal fun filesKo(): FilesStr = FilesStr(
    title = "대화 파일",
    hint = "이 대화의 워크스페이스에 있는 파일만 표시합니다",
    empty = "이 대화에는 아직 파일이 없습니다",
    btnView = "열기",
    btnExport = "내보내기",
    btnDelete = "삭제",
    btnCancel = "취소",
    btnClose = "닫기",
    deleteTitle = "파일을 삭제할까요?",
    deleteText = "삭제하면 되돌릴 수 없습니다.",
    deleteDone = "삭제했습니다",
    exportSuccess = "「다운로드/DChat」에 내보냈습니다: %s",
    exportUnsupported = "이 시스템 버전은 다운로드 폴더에 직접 쓰기를 지원하지 않습니다",
    exportFailed = "내보내기 실패: %s",
    openFailed = "파일을 열 수 없습니다: %s"
)
