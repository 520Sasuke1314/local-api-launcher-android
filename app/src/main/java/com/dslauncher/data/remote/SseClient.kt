package com.dslauncher.data.remote

import com.google.gson.Gson
import com.dslauncher.data.remote.model.ChatRequest
import com.dslauncher.data.remote.model.ChatResponse
import com.dslauncher.data.remote.model.ToolCall
import com.dslauncher.data.remote.model.ToolCallFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class SseClient(private val okHttpClient: OkHttpClient) {
    private val gson = Gson()
    // 流式读取专用 client：空闲读超时 60s。若接口保持连接但不返回数据，
    // 阻塞在 readUtf8Line() 会永久卡住导致 UI 一直显示省略号；超时后抛出
    // SocketTimeoutException，让上层结束生成状态并显示错误。
    private val streamingClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        /** 不识别新版 thinking 协议字段的接口（按 baseUrl 记录，首次 400 后不再携带该字段） */
        private val thinkingUnsupported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * 思考力度请求头名。
         * 仅 Anthropic 原生通道使用；OpenAI 兼容通道改走请求体的 reasoning_effort 字段。
         */
        const val HEADER_THINKING_LEVEL = "X-Thinking-Level"

        /**
         * 思考力度请求体字段名。
         * OpenAI 兼容通道用它下发力度；Anthropic 不认这个字段，只认请求头。
         */
        const val BODY_REASONING_EFFORT = "reasoning_effort"

        /**
         * 档位 → 力度字符串：-1/0 返回 null（不下发）；1=low / 2=medium / 3=high / 4=max / 5=xhigh。
         * 两条通道共用同一套取值——Anthropic 拿它当 X-Thinking-Level 头的值，
         * OpenAI 兼容通道拿它当 reasoning_effort 的值。
         */
        fun thinkingLevelHeaderValue(level: Int?): String? = when (level) {
            1 -> "low"
            2 -> "medium"
            3 -> "high"
            4 -> "max"
            5 -> "xhigh"
            else -> null
        }
    }

    /** 组装请求 JSON：可选剥离 thinking 字段；原生多模态时重组最后一条用户消息 */
    private fun buildJsonBody(request: ChatRequest, includeThinking: Boolean): String {
        val root = com.google.gson.JsonParser.parseString(gson.toJson(request)).asJsonObject
        if (!includeThinking) root.remove("thinking")
        // 思考力度：OpenAI 兼容通道用请求体的 reasoning_effort（low/medium/high/max，原样下发）。
        // 与 thinking 字段相互独立——thinking 管开关，reasoning_effort 管力度；
        // 即使 thinking 因 400 被回退剥掉，力度字段也保留（OpenAI 官方只认这个字段）。
        thinkingLevelHeaderValue(request.thinkingLevel)?.let { root.addProperty(BODY_REASONING_EFFORT, it) }
        if (request.images.isNotEmpty()) {
            // 原生多模态：把最后一条用户消息的 content 展开为内容数组（text + image_url）。
            // Gson 会把 images 字段序列化进去，但标准接口不认识它，需在此手动剔除并重组。
            root.remove("images")
            val msgs = root.getAsJsonArray("messages")
            if (msgs.size() > 0) {
                val last = msgs.get(msgs.size() - 1).asJsonObject
                val text = last.get("content").takeIf { !it.isJsonNull }?.asString ?: ""
                val contentArr = com.google.gson.JsonArray()
                if (text.isNotBlank()) {
                    val t = com.google.gson.JsonObject()
                    t.addProperty("type", "text")
                    t.addProperty("text", text)
                    contentArr.add(t)
                }
                request.images.forEach { dataUrl ->
                    val img = com.google.gson.JsonObject()
                    img.addProperty("type", "image_url")
                    val u = com.google.gson.JsonObject()
                    u.addProperty("url", dataUrl)
                    img.add("image_url", u)
                    contentArr.add(img)
                }
                last.add("content", contentArr)
            }
        }
        return root.toString()
    }

    /**
     * OpenAI 兼容的 SSE 流式对话接口。
     * 返回的 Flow 逐段发出增量文本。
     * @param onThinking 思考内容回调（reasoning_content 字段）
     * @param onToolCall 工具调用回调：流式过程中累积到完整工具调用参数时触发
     */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        request: ChatRequest,
        onThinking: (String) -> Unit = {},
        onToolCall: (ToolCall) -> Unit = {},
        onUsage: (promptTokens: Int, completionTokens: Int) -> Unit = { _, _ -> }
    ): Flow<String> = flow {
        val baseKey = baseUrl.trimEnd('/')
        // 新版 thinking 协议字段：DeepSeek 官方/中转站识别；OpenAI 等不识别会 400，
        // 首次请求失败后回退为不带该字段重试，并把该接口记入黑名单避免反复重试
        var includeThinking = request.thinking != null && !thinkingUnsupported.contains(baseKey)
        var jsonBody = buildJsonBody(request, includeThinking)
        var reqBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
        var fullUrl = "${baseKey}${"/chat/completions"}"
        // 思考力度已改走请求体的 reasoning_effort（见 buildJsonBody），OpenAI 兼容通道不再发请求头。
        var httpRequest = Request.Builder()
            .url(fullUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(reqBody)
            .build()

        var response = withContext(Dispatchers.IO) {
            streamingClient.newCall(httpRequest).execute()
        }
        if (!response.isSuccessful && (response.code == 400 || response.code == 422) && includeThinking) {
            response.close()
            thinkingUnsupported.add(baseKey)
            includeThinking = false
            jsonBody = buildJsonBody(request, includeThinking)
            reqBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            httpRequest = Request.Builder()
                .url(fullUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(reqBody)
                .build()
            response = withContext(Dispatchers.IO) {
                streamingClient.newCall(httpRequest).execute()
            }
        }
        if (!response.isSuccessful) {
            val errBody = response.body?.string().orEmpty()
            throw IOException("API error: ${response.code} ${response.message} $errBody")
        }
        val source = response.body?.source() ?: throw IOException("Empty body")
        // 收集推理内容（reasoning_content，DeepSeek/GLM/Kimi 等思考模型），
        // 在首个正文 token 前以 [思考]...[/思考] 前缀输出，供消息气泡折叠展示。
        val reasoning = StringBuilder()
        var reasoningEmitted = false
        // 是否发出了任何内容（思考或正文）；是否发出了非空白正文
        var emitted = false
        var bodyEmitted = false
        // 工具调用累积（按工具 id 分片合并，null 时沿用上一个 id）
        val toolCalls = LinkedHashMap<String, MutableList<ToolCallFragment>>()
        var lastToolKey = ""
        // 原始响应累积，用于非流式 JSON 兜底 & 空响应时把真实返回抛给上层诊断
        val rawBody = StringBuilder()
        // 无正文保护：接口可能持续返回 data 帧（空 delta / role 帧 / 心跳），
        // 这种"有数据但无正文"不会触发 readTimeout，导致循环永不结束、UI 永远省略号。
        // 只要在限定时间内没有解析出任何非空正文，就主动报错并把原始返回带上。
        val startedAt = System.currentTimeMillis()
        // 性能优化：累积式节流 emit —— 每满一批（约 60 字符）或距上次推送超过
        // 50ms 就批量 emit 一次，提高帧率至 ~20fps，实现德芙般丝滑流式体验。
        var lastEmitTime = System.currentTimeMillis()
        val accumulatedContent = StringBuilder()
        // 网络读行在 IO 线程执行，解析与 emit 留在 flow 上下文（与 collect 同一线程），
        // 从机制上保证 emit 与 collect 上下文一致，彻底避免跨线程 emit 违规。
        try {
                try {
                    var lineCount = 0
                    var emittedCount = 0
                    while (true) {
                        val line = withContext(Dispatchers.IO) {
                            if (source.exhausted()) null else source.readUtf8Line()
                        } ?: break
                        val trimmed = line.trim()
                        lineCount++
                        if (trimmed.isEmpty()) continue
                        rawBody.append(trimmed).append('\n')
                        // 兼容 `data:{...}`（无空格）与 `data: {...}` 两种常见 SSE 格式
                        if (trimmed.startsWith("data:")) {
                            val data = trimmed.removePrefix("data:").trim()
                            if (data == "[DONE]") {
                                break
                            }
                            // 解析与 emit 分离：只有 fromJson 在 try-catch 内，
                            // emit 异常不会被误记为「解析失败」，也不会破坏 Flow 异常透明性。
                            val chunk = try {
                                gson.fromJson(data, ChatResponse::class.java)
                            } catch (e: Exception) {
                                null
                            }
                            // 捕获用量：OpenAI / DeepSeek 会在最后一个 chunk 顶层带 usage
                            chunk?.usage?.let { u ->
                                val p = u.prompt_tokens ?: 0
                                val c = u.completion_tokens ?: 0
                                if (p > 0 || c > 0) onUsage(p, c)
                            }
                            chunk?.choices?.firstOrNull()?.let { choice ->
                                val delta = choice.delta
                                // 兼容部分中转站：流式分片把正文放在 message 而非 delta
                                val reasoningText = delta?.reasoning_content
                                val contentText = delta?.content ?: choice.message?.content
                                if (reasoningText != null) {
                                    reasoning.append(reasoningText)
                                    onThinking(reasoningText)
                                }
                                // 累积工具调用：流式片段按到达顺序归入 bucket。
                                // 同一工具调用多次分片用同一 key；不同调用以非空 id 为界新开 bucket。
                                (delta?.tool_calls ?: choice.message?.tool_calls)?.forEach { tc ->
                                    val key = tc.id ?: (lastToolKey)
                                    lastToolKey = key
                                    val fragments = toolCalls.getOrPut(key) { ArrayList() }
                                    fragments.add(ToolCallFragment(tc))
                                }
                                contentText?.let { content ->
                                    // 不再在 SseClient 层面注入 [思考] 标签，由上层统一处理
                                    // 这样保持与官网通道一致：thinking 通过 onThinking 回调，正文通过 emit
                                    emitted = true
                                    if (content.isNotBlank()) bodyEmitted = true
                                    emittedCount++
                                    // 累积内容，达到节流阈值（60 字符 / 50ms）时批量 emit，提升帧率至 ~20fps
                                    accumulatedContent.append(content)
                                    val now = System.currentTimeMillis()
                                    if (accumulatedContent.length >= 60 || now - lastEmitTime >= 50) {
                                        val batch = accumulatedContent.toString()
                                        accumulatedContent.clear()
                                        lastEmitTime = now
                                        emit(batch)
                                    }
                                }
                            }
                        }
                        // 性能优化：每50行记录一次日志，减少日志频率
                        if (lineCount % 50 == 0) { }
                        // 无正文保护：思考中（有 reasoning）给 120s，否则 25s。
                        // 达到时限仍无正文 → 中断并抛错，避免界面永远显示省略号。
                        // 工具调用流只发 delta.tool_calls 不含正文，故有工具调用时跳过该保护。
                        if (!bodyEmitted && toolCalls.isEmpty()) {
                            val limit = if (reasoning.isNotEmpty()) 120_000L else 25_000L
                            if (System.currentTimeMillis() - startedAt > limit) {
                                val snippet = rawBody.toString().trim().take(800)
                                throw IOException(
                                    "接口在 ${limit / 1000} 秒内未返回任何正文" +
                                        (if (snippet.isNotEmpty()) "。接口原始返回：\n$snippet" else "（接口未返回任何数据）")
                                )
                            }
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    throw IOException(
                        "接口响应超时（60 秒内未收到任何数据）。请检查网络，或该接口是否支持流式输出、模型名是否正确。"
                    )
                }
        } finally {
            response.close()
        }
        // 优化：批量推送剩余的累积内容
        if (accumulatedContent.isNotEmpty()) {
            emit(accumulatedContent.toString())
        }
        // 合并工具调用分片并回调（工具调用与正文互斥：出现工具调用即意味着要执行工具，
        // 上层据此运行 search_web 并把结果回填，再二次请求模型生成最终答案）
        if (toolCalls.isNotEmpty()) {
            toolCalls.values.forEach { frags ->
                val merged = mergeFragments(frags)
                if (merged.function.name != null) onToolCall(merged)
            }
            // 工具调用触发时不应再走"无正文"报错路径
            if (!bodyEmitted) bodyEmitted = true
        }
        // 非流式兜底：兼容忽略 stream=true 直接返回完整 JSON 的服务
        // （Ollama / 中转站等），覆盖整段 JSON、美化打印 JSON、每行一个 JSON 三种情况
        if (reasoning.isEmpty() && rawBody.isNotEmpty()) {
            val raw = rawBody.toString()
            // 1) 整段直接解析（单行完整 JSON / 美化打印的多行 JSON）
            try {
                val whole = gson.fromJson(raw, ChatResponse::class.java)
                whole?.usage?.let { u ->
                    val p = u.prompt_tokens ?: 0
                    val c = u.completion_tokens ?: 0
                    if (p > 0 || c > 0) onUsage(p, c)
                }
                whole?.choices?.firstOrNull()?.let { c ->
                    (c.delta?.content ?: c.message?.content)?.takeIf { it.isNotBlank() }?.let {
                        emitted = true
                        bodyEmitted = true
                        emit(it)
                    }
                }
            } catch (e: Exception) {
                // 忽略：整段不是合法 JSON
            }
            // 2) 逐行解析（每行一个 JSON 的非标准流式返回）
            if (!emitted) {
                for (line in raw.lines()) {
                    val candidate = line.removePrefix("data:").trim()
                    if (candidate.isEmpty() || candidate == "[DONE]") continue
                    try {
                        val whole = gson.fromJson(candidate, ChatResponse::class.java)
                        whole?.choices?.firstOrNull()?.let { c ->
                            (c.delta?.content ?: c.message?.content)?.takeIf { it.isNotBlank() }?.let {
                                emitted = true
                                bodyEmitted = true
                                emit(it)
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略单行解析错误
                    }
                }
            }
        }
        // 没有任何非空白正文：把真实原因抛给上层，而不是让界面永远只显示省略号。
        if (!bodyEmitted) {
            if (reasoning.isNotEmpty()) {
                throw IOException("模型只返回了思考内容，没有输出正文（请检查模型类型 / 接口配置是否支持输出正文）。")
            }
            val snippet = rawBody.toString().trim().take(300)
            val apiError = extractErrorFromRaw(rawBody.toString())
            throw IOException(
                "接口未返回任何可显示内容（请检查模型名、baseUrl 与参数是否被该接口支持）。" +
                    (apiError?.let { "\n接口错误信息：$it" }
                        ?: if (snippet.isNotEmpty()) "\n接口原始返回：$snippet" else "\n接口返回为空")
            )
        }
    }

    /** 从原始响应中尝试提取 OpenAI 风格错误信息 {"error":{"message":"..."}} */
    private fun extractErrorFromRaw(raw: String): String? {
        val singleLine = raw.lines().firstOrNull()?.removePrefix("data:")?.trim() ?: return null
        return try {
            val obj = org.json.JSONObject(singleLine)
            val err = obj.optJSONObject("error")
            err?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 工具调用在流式中的分片（同一 index 的多个 delta 需合并） */
    private data class ToolCallFragment(
        val id: String?,
        val type: String?,
        val name: String?,
        val arguments: String?
    ) {
        constructor(tc: ToolCall) : this(tc.id, tc.type, tc.function.name, tc.function.arguments)
    }

    /** 把同一次工具调用的多个分片合并为完整 ToolCall */
    private fun mergeFragments(frags: List<ToolCallFragment>): ToolCall {
        var id: String? = null
        var type: String? = null
        var name: String? = null
        val args = StringBuilder()
        frags.forEach { f ->
            if (f.id != null) id = f.id
            if (f.type != null) type = f.type
            if (f.name != null) name = f.name
            if (f.arguments != null) args.append(f.arguments)
        }
        return ToolCall(id = id, type = type, function = ToolCallFunction(name = name, arguments = args.toString()))
    }
}
