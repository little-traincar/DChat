package com.dchat.app.data

import com.dchat.app.ui.lang.AppLanguage

/** 本地数据损坏的用户提示（不依赖 ui/lang，避免与 UI 写域冲突） */
object StorageAlerts {
    fun corruptBackup(language: AppLanguage, backupName: String): String = when (language) {
        AppLanguage.ZH ->
            "会话文件损坏，已备份为「$backupName」，原数据未删除；请从设置 → 数据导入备份，或联系支持。"
        AppLanguage.EN ->
            "The conversation file was corrupted. It was backed up as \"$backupName\"; your data was not deleted. Import the backup from Settings → Data or contact support."
        AppLanguage.JA ->
            "会話ファイルが壊れています。「$backupName」にバックアップしました。元データは削除していません。設定 → データからバックアップをインポートするか、サポートに連絡してください。"
        AppLanguage.KO ->
            "대화 파일이 손상되었습니다. 「$backupName」로 백업했으며 원본 데이터는 삭제하지 않았습니다. 설정 → 데이터에서 백업을 가져오거나 지원팀에 문의하세요."
    }
}
