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
        val durationMs: Long = 0L,
        /**
         * 1.0.3：Python/用户工具输出超过 64KB 时，完整内容落盘的绝对路径（否则 null）。
         * 只用于气泡卡片的「完整输出」入口；给模型的 tool_result 已在 ChatScreen 截到 64KB。
         * 该字段不落库（重启后卡片不再显示入口），避免把工作区绝对路径写进会话 JSON。
         */
        val outputPath: String? = null,
        /** 完整输出文件的字节数（outputPath 为空时为 0） */
        val outputBytes: Long = 0L
    ) : Step()
}
