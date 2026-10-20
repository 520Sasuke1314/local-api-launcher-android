package com.dslauncher.data.remote.model

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val top_p: Double? = null,
    val top_k: Int? = null,
    /** 附加到当前用户消息的图片（data URL），用于无识图模型时的原生多模态发送 */
    val images: List<String> = emptyList(),
    /** 深度思考开关（DeepSeek/兼容代理）。null = 不发送（交给服务端默认）；
     * false = 显式关闭思考。Gson 序列化会省略 null，避免不认识的第三方接口报错 */
    val thinking_enabled: Boolean? = null,
    /** 深度思考（新版协议）：DeepSeek 官方 API 的 {"type":"enabled"/"disabled"} 对象。
     * Gson 对 JsonElement 字段原样序列化；null = 不发送 */
    val thinking: com.google.gson.JsonObject? = null,
    /** 工具定义（OpenAI 兼容 function calling，如 search_web）。null = 不发送 */
    val tools: List<ToolDef>? = null,
    /**
     * 思考力度档位：-1=自动 / 0=关闭 / 1=low / 2=medium / 3=high / 4=max（5=xhigh 预留）。
     *
     * 该字段【不参与 body 序列化】，Gson 必须忽略它，原因有二：
     *  1) 它只用来给两条通道各自拼装力度参数，本身不是协议字段，直接塞进 body 会被
     *     OpenAI / 中转站当成未知字段而 400；
     *  2) 两条通道的下发形式不同 —— OpenAI 兼容通道在 buildJsonBody 里手动写入
     *     reasoning_effort，Anthropic 通道在请求头里发 X-Thinking-Level，
     *     都不依赖这个字段被序列化。
     *
     * 用 transient（Java 语义）+ @Expose(serialize=false) 双保险：Gson 默认跳过 transient 字段，
     * 即使将来换成 excludeFieldsWithoutExposeAnnotation() 的 Gson 实例也不会漏出去。
     */
    @com.google.gson.annotations.Expose(serialize = false, deserialize = false)
    @kotlin.jvm.Transient
    val thinkingLevel: Int? = null
)

/** OpenAI 兼容的工具定义（function calling） */
data class ToolDef(
    val type: String = "function",
    val function: ToolFunctionDef
)

data class ToolFunctionDef(
    val name: String,
    val description: String,
    /** JSON Schema 对象，Gson 原样序列化；null = 省略 */
    val parameters: com.google.gson.JsonObject? = null
)

data class Message(
    val role: String,
    val content: String? = null,
    /** 助手发起的工具调用（拼接时用） */
    val tool_calls: List<ToolCall>? = null,
    /** 工具结果消息的回填 id（role=tool 时） */
    val tool_call_id: String? = null,
    /** 工具结果消息的工具名（role=tool 时） */
    val name: String? = null
)

data class ToolCall(
    val id: String?,
    val type: String? = null,
    val function: ToolCallFunction
)

data class ToolCallFunction(
    val name: String?,
    val arguments: String? = null
)
