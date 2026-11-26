package com.dchat.app.ui.lang

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * DeepSeek 官方「用户元信息标记」：把当前日期、用户所在地区、平台和语言告诉模型。
 *
 * 文档：https://api-docs.deepseek.com/zh-cn/guides/latest_reminder/
 * （总览 https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/ ）
 *
 * 关键点（以文档为准，别自创字段）：
 * - 它**不是请求体字段**，而是 `messages` 数组里的一条消息：
 *   `{"role": "latest_reminder", "content": "2026-11-09,星期一,新加坡,Web,中文"}`
 * - content 格式：`<date>,<weekday>,<location>,<platform>,<language>`
 *     - date     ：`YYYY-MM-DD`
 *     - weekday  ：date 当天星期几，用 language 对应的语言书写（Monday / 星期一 / 月曜日 / 월요일）
 *     - location ：可选、可为空、可含逗号；这里用地区名
 *     - platform ：`App` 或 `Web`（本 App 固定 `App`）
 *     - language ：用该语言自身书写（English / 日本語 / 中文 / 한국어，四个都在官方支持列表里）
 * - 这条消息后面必须紧跟一条 `user` 消息，否则 400；content 必须是字符串，否则 422。
 * - Messages API 与 Chat Completions API 支持，Responses API 不支持。
 * - 建议随历史回传之前各轮的这类消息，可提升上下文缓存命中（不回传也不会报错）。
 */
object LatestReminder {

    /** 本项目是 Android App，platform 固定 App（文档取值只有 App / Web） */
    const val PLATFORM = "App"

    /** 语言 + 地区的组合标识：只有它变了，才需要在下一轮重新告诉模型 */
    fun key(language: AppLanguage, region: AppRegion): String = "${language.code}|${region.code}"

    fun key(str: Str): String = key(str.language, str.region)

    /** 按文档拼 content；location 用当前语言下的地区名，language 用该语言自己的写法 */
    fun content(str: Str, today: LocalDate = LocalDate.now()): String = listOf(
        today.format(DateTimeFormatter.ISO_LOCAL_DATE),
        str.weekdayName(today.dayOfWeek),
        str.regionName(str.region),
        PLATFORM,
        str.language.endonym
    ).joinToString(",")
}
