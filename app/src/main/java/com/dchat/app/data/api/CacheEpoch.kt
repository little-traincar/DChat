package com.dchat.app.data.api

import com.dchat.app.ui.lang.AppLanguage
import com.dchat.app.ui.lang.Lang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P3-A：请求级开关变化提示。只提示、不阻塞、不回滚用户选择。
 * 文案按当前界面语言即时生成；ChatScreen 收集后 toast，并清空。
 */
object CacheEpochNotice {
    const val THINKING = "thinking"
    const val SAFETY = "safety"
    const val TOOLS = "tools"
    const val PERMISSION = "permission"

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun notify(reason: String) {
        DCacheLog.write("epochReason=$reason")
        _message.value = text(reason, Lang.current.language)
    }

    /** 只记诊断日志，不弹提示（工具集开关用） */
    fun logOnly(reason: String) {
        DCacheLog.write("epochReason=$reason")
    }

    fun clear() {
        _message.value = null
    }

    fun text(reason: String, language: AppLanguage): String {
        val name = when (reason) {
            THINKING -> when (language) {
                AppLanguage.ZH -> "深度思考"
                AppLanguage.EN -> "Deep thinking"
                AppLanguage.JA -> "深い思考"
                AppLanguage.KO -> "깊은 사고"
            }
            SAFETY -> when (language) {
                AppLanguage.ZH -> "内容安全"
                AppLanguage.EN -> "Content safety"
                AppLanguage.JA -> "コンテンツ安全"
                AppLanguage.KO -> "콘텐츠 안전"
            }
            TOOLS -> when (language) {
                AppLanguage.ZH -> "工具集"
                AppLanguage.EN -> "Tools"
                AppLanguage.JA -> "ツールセット"
                AppLanguage.KO -> "도구 세트"
            }
            else -> when (language) {
                AppLanguage.ZH -> "权限"
                AppLanguage.EN -> "Permission"
                AppLanguage.JA -> "権限"
                AppLanguage.KO -> "권한"
            }
        }
        return when (language) {
            AppLanguage.ZH -> "已切换「$name」，本轮缓存将重建"
            AppLanguage.EN -> "\"$name\" changed; the cache will be rebuilt for this turn"
            AppLanguage.JA -> "「$name」を切り替えました。このターンのキャッシュは再構築されます"
            AppLanguage.KO -> "「$name」이(가) 변경되었습니다. 이번 턴의 캐시가 다시 생성됩니다"
        }
    }
}
