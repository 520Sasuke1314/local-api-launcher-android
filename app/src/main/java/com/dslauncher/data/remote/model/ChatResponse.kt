package com.dslauncher.data.remote.model

data class ChatResponse(
    val id: String?,
    val `object`: String?,
    val created: Long?,
    val model: String?,
    val choices: List<Choice>?,
    /** OpenAI 兼容接口在最后一个 chunk 里带的用量统计（部分中转站不返回） */
    val usage: Usage? = null
)

/**
 * 流式响应的 Token 用量。
 *
 * OpenAI / DeepSeek 在最后一个 chunk 里给 `usage`；字段名是下划线命名，
 * 直接当 Kotlin 属性名用，Gson 才能对上 JSON。
 */
data class Usage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

data class Choice(
    val index: Int?,
    val delta: Delta?,
    val message: Message?,
    val finish_reason: String?
)

data class Delta(
    val role: String?,
    val content: String?,
    val reasoning_content: String? = null,
    val tool_calls: List<ToolCall>? = null
)
