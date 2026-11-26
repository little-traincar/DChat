package com.dchat.app.data

/** 搜索/抓取来源 */
data class Source(
    val title: String,
    val url: String,
    val site: String? = null,
    val snippet: String? = null
)

/** 一次回答里的一个步骤（时间线单元） */
sealed class Step {
    data class Thinking(val text: String = "", val secs: Int? = null) : Step()

    data class Search(
        val queries: List<String> = emptyList(),
        val results: List<Source> = emptyList()
    ) : Step()

    data class Fetch(
        val url: String = "",
        val results: List<Source> = emptyList()
    ) : Step()

    /** local.download 的文件卡：路径与摘要状态；图片由气泡单独渲染 */
    data class Download(
        val filePath: String = "",
        val mime: String = "",
        val sizeBytes: Long = 0L,
        val status: String = "ok",
        val note: String = ""
    ) : Step()

    data class Text(val text: String = "") : Step()

    /**
     * 客户端工具调用（第三期：文件解析 / OCR 等）。
     * 时间线里渲染成可折叠卡片；args/result/error 只存短摘要，不存大段原文。
     */
    data class ToolCall(
        val toolId: String = "",
        val toolName: String = "",
        val args: String = "",
        val result: String = "",
        val error: String = "",
        val durationMs: Long = 0L
    ) : Step()
}
