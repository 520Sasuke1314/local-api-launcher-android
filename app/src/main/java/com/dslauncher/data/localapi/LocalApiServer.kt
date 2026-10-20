package com.dslauncher.data.localapi

import com.dslauncher.App
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 本地 OpenAI 兼容 API 服务。
 *
 * GET  /v1/models               → 模型列表
 * POST /v1/chat/completions     → 对话（支持 stream）
 * GET  /health                  → {"ok":true}
 *
 * 鉴权：设置了 key 时需带 Authorization: Bearer <key>。
 * 后端：DeepSeekWebRepository（官网网页接口逆向）。
 */
class LocalApiServer(
    private val app: App,
    private val port: Int,
    private val token: String?,
    /** true = 绑定 0.0.0.0，开放局域网访问；false = 仅本机 127.0.0.1 */
    private val allowLan: Boolean = false
) {
    private var serverSocket: ServerSocket? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val pool = Executors.newCachedThreadPool()

    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            // 开放局域网时绑定 0.0.0.0，否则只监听回环地址。
            val host = if (allowLan) "0.0.0.0" else "127.0.0.1"
            ss.bind(InetSocketAddress(host, port))
        } catch (e: Exception) {
            lastError = e.message ?: "端口绑定失败"
            runCatching { ss.close() }
            return
        }
        serverSocket = ss
        isRunning = true
        lastError = null
        pool.execute {
            while (isRunning) {
                val client = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { runCatching { handle(client) } }
            }
        }
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 0
            val raw = c.getInputStream()
            val headerBytes = readUntilDoubleCrlf(raw) ?: return
            val headerText = String(headerBytes, Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) return
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            var contentLength = 0
            var chunked = false
            var authHeader: String? = null
            // Anthropic 兼容客户端用 `x-api-key` 传密钥（Claude SDK / Claude Code 等），
            // 与 Bearer 二选一即可。
            var apiKeyHeader: String? = null
            // 带 anthropic-version 的请求 → 模型列表等接口按 Anthropic 形状返回
            var anthropicVersion: String? = null
            // 显式会话控制头（对齐 superapi 的 x-force-session-id）：
            // 客户端想锁定某个官网会话、或强制开新会话时，带上一个新值即可，
            // 不必再依赖「system + 首条 user 消息」指纹猜测。
            var forceSession: String? = null
            // 工具并发放行开关（默认关闭 = 一次一个）。客户端可显式打开。
            var allowMultiTools = false
            for (i in 1 until lines.size) {
                val line = lines[i]
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val k = line.substring(0, idx).trim().lowercase()
                val v = line.substring(idx + 1).trim()
                when (k) {
                    "content-length" -> contentLength = v.toIntOrNull() ?: 0
                    "transfer-encoding" -> if (v.contains("chunked", ignoreCase = true)) chunked = true
                    "authorization" -> authHeader = v
                    "x-api-key" -> if (v.isNotBlank()) apiKeyHeader = v
                    "anthropic-version" -> if (v.isNotBlank()) anthropicVersion = v
                    "x-force-session-id", "x-conversation-id", "x-session-id" ->
                        if (v.isNotBlank()) forceSession = v
                    "x-allow-multi-tools", "x-parallel-tools", "x-allow-parallel-tools" ->
                        if (v == "1" || v.equals("true", ignoreCase = true)) allowMultiTools = true
                }
            }

            val out = BufferedOutputStream(c.getOutputStream())

            // 网页控制台：静态页面本身不需要鉴权（里面没有任何隐私数据，只有 UI），
            // 真正的数据与操作走 /admin/api/*，那里照常校验 key。
            val isAdminPage = method == "GET" &&
                (path == "/" || path == "/admin" || path == "/console" ||
                    path == "/index.html" || path == "/favicon.ico")

            // 鉴权（Bearer 或 Anthropic 风格的 x-api-key 都接受）
            if (!token.isNullOrBlank() && !isAdminPage) {
                val provided = authHeader?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
                    ?: apiKeyHeader?.trim()
                if (provided != token) {
                    writeJson(out, 401, """{"error":{"message":"invalid api key"}}""")
                    return
                }
            }

            when {
                // ---------------- 网页控制台 ----------------
                isAdminPage -> {
                    if (path == "/favicon.ico") writeRaw(out, 204, "", "image/x-icon")
                    else writeHtml(out, AdminWeb.HTML)
                }

                method == "GET" && path == "/admin/api/state" ->
                    writeJson(out, 200, adminStateJson())

                method == "POST" && path == "/admin/api/action" -> {
                    val body = try {
                        if (chunked) readChunked(raw) else readN(raw, contentLength)
                    } catch (e: Exception) {
                        writeJson(out, 400, """{"ok":false,"message":"read body failed"}""")
                        return
                    }
                    writeJson(out, 200, adminAction(body))
                }

                method == "GET" && path == "/health" ->
                    writeJson(out, 200, """{"ok":true}""")

                // 调试：把一段模型输出丢给 DSML 解析器，看解析出的 tool_calls 与清洗后的正文
                method == "POST" && path == "/debug/dsml" -> {
                    val body = try {
                        if (chunked) readChunked(raw) else readN(raw, contentLength)
                    } catch (e: Exception) {
                        writeJson(out, 400, """{"error":{"message":"read body failed"}}""")
                        return
                    }
                    writeJson(out, 200, dsmlDebugJson(body))
                }

                method == "GET" && path == "/debug/last-stream" ->
                    writeJson(
                        out, 200,
                        org.json.JSONObject()
                            .put("count", com.dslauncher.data.qwen.lastStreamFrames.size)
                            .put(
                                "frames",
                                org.json.JSONArray(
                                    com.dslauncher.data.qwen.lastStreamFrames.toList()
                                )
                            )
                            .toString()
                    )

                // 诊断：最近一次上游（官网）原始返回。用来排查「返回空气」到底是
                // 上游给了什么（非 SSE 错误体 / 空 / 乱码）。
                // 2026-10：Qwen 通道同样进入统计（旧版只有 DeepSeek，Qwen 出问题无从查）。
                // 顶层 length/raw 保持 = DeepSeek（向后兼容），Qwen 看 .qwen.raw。
                method == "GET" && path == "/debug/last-upstream" -> {
                    val raw = com.dslauncher.data.remote.DeepSeekWebClient.lastUpstreamRaw
                    val qwRaw = com.dslauncher.data.qwen.QwenWebClient.lastUpstreamRaw
                    writeJson(
                        out, 200,
                        org.json.JSONObject()
                            .put("length", raw.length)
                            .put("raw", raw)
                            .put(
                                "qwen",
                                org.json.JSONObject()
                                    .put("length", qwRaw.length)
                                    .put("raw", qwRaw)
                            )
                            .toString()
                    )
                }

                method == "GET" && (path == "/v1/models" || path == "/models") ->
                    writeJson(out, 200, if (anthropicVersion != null) anthropicModelsJson() else modelsJson())

                method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> {
                    val body = try {
                        if (chunked) readChunked(raw) else readN(raw, contentLength)
                    } catch (e: Exception) {
                        writeJson(out, 400, """{"error":{"message":"read body failed"}}""")
                        return
                    }
                    handleChat(out, body, forceSession, allowMultiTools)
                }

                // ---------------- Anthropic 兼容（Messages API） ----------------
                method == "POST" && (path == "/v1/messages" || path == "/anthropic/v1/messages" ||
                    path == "/messages") -> {
                    val body = try {
                        if (chunked) readChunked(raw) else readN(raw, contentLength)
                    } catch (e: Exception) {
                        writeAnthropicError(out, 400, "invalid_request_error", "read body failed")
                        return
                    }
                    handleAnthropicMessages(out, body, forceSession, allowMultiTools)
                }

                method == "POST" && (path == "/v1/messages/count_tokens" ||
                    path == "/anthropic/v1/messages/count_tokens") -> {
                    val body = try {
                        if (chunked) readChunked(raw) else readN(raw, contentLength)
                    } catch (e: Exception) {
                        writeAnthropicError(out, 400, "invalid_request_error", "read body failed")
                        return
                    }
                    writeJson(out, 200, anthropicCountTokens(body))
                }

                method == "OPTIONS" -> {
                    val head = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                else -> writeJson(out, 404, """{"error":{"message":"not found"}}""")
            }
        }
    }

    /** 读到 header 结束（\r\n\r\n），返回含结尾的 header 字节。 */
    private fun readUntilDoubleCrlf(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toByteArray()
            buf.write(b)
            val n = buf.size()
            if (n >= 4) {
                val arr = buf.toByteArray()
                if (arr[n - 4] == 13.toByte() && arr[n - 3] == 10.toByte() &&
                    arr[n - 2] == 13.toByte() && arr[n - 1] == 10.toByte()
                ) return arr
            }
        }
    }

    /** 按 Content-Length 读取 n 个「字节」，再按 UTF-8 解码。 */
    private fun readN(input: InputStream, n: Int): String {
        if (n <= 0) return ""
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(bytes, read, n - read)
            if (r < 0) break
            read += r
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    /** 解析 Transfer-Encoding: chunked 请求体。 */
    private fun readChunked(input: InputStream): String {
        val result = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().split(";")[0].toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)
                break
            }
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val r = input.read(chunk, read, size - read)
                if (r < 0) break
                read += r
            }
            result.write(chunk, 0, read)
            readLine(input)
        }
        return String(result.toByteArray(), Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
            if (b == 10) return String(buf.toByteArray(), Charsets.UTF_8).trimEnd('\r')
            buf.write(b)
        }
    }

    private fun modelsJson(): String {
        val arr = JSONArray()
        for (id in MODEL_IDS) {
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("created", 0)
                    .put("owned_by", "deepseek-web")
            )
        }
        // mixed = 混合模型：每次请求从 DeepSeek + Qwen 双池可用账号中随机抽一个
        arr.put(
            JSONObject()
                .put("id", MIXED_MODEL)
                .put("object", "model")
                .put("created", 0)
                .put("owned_by", "dslauncher")
        )
        // Qwen 通道：首项为当前配置的默认 Qwen 模型，其余为网页端常见模型
        for (id in qwenModelIds()) {
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("created", 0)
                    .put("owned_by", "qwen-web")
            )
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }

    /** Qwen 模型列表（默认模型置顶去重）。优先用 /api/v2/models 动态清单（免鉴权）。 */
    private fun qwenModelIds(): List<String> {
        val current = app.settings.qwenModel
        return (listOf(current) + app.qwenRepo.models()).distinct()
    }

    /** Anthropic 形状的模型列表（带 anthropic-version 头的客户端走这里）。 */
    private fun anthropicModelsJson(): String {
        val arr = JSONArray()
        for (id in MODEL_IDS + listOf(MIXED_MODEL) + qwenModelIds()) {
            arr.put(
                JSONObject()
                    .put("type", "model")
                    .put("id", id)
                    .put("display_name", id)
                    .put("created_at", "2025-01-01T00:00:00Z")
            )
        }
        return JSONObject()
            .put("data", arr)
            .put("has_more", false)
            .put("first_id", if (arr.length() > 0) arr.optJSONObject(0).optString("id") else "")
            .put("last_id", if (arr.length() > 0) arr.optJSONObject(arr.length() - 1).optString("id") else "")
            .toString()
    }

    /** 本次请求的服务方。 */
    private enum class Provider { DEEPSEEK, QWEN }

    /**
     * 按请求的 model 决定服务方：
     *  - "mixed"（混合模型）：从 DeepSeek + Qwen 两个池的全部可用账号中随机抽一个；
     *  - Qwen 模型名（qwen3.8-max 等）→ Qwen 通道；
     *  - 其余（deepseek-chat/reasoner 及未知值）→ DeepSeek 通道（原行为）。
     * 返回 null 表示 mixed 但两池都无可用账号，调用方直接 502。
     */
    private fun resolveForModel(model: String): Pair<Provider, String?>? {
        if (model.equals(MIXED_MODEL, ignoreCase = true)) {
            app.accounts.sweepExpired()
            app.qwenAccounts.sweepExpired()
            val now = System.currentTimeMillis()
            val ds = app.accounts.list().filter { it.available(now) && !it.needRelogin }
                .map { Provider.DEEPSEEK to it.id }
            val qw = app.qwenAccounts.list().filter { it.available(now) && !it.needRelogin }
                .map { Provider.QWEN to it.id }
            return (ds + qw).randomOrNull()
        }
        val isQwen = qwenModelIds().any { it.equals(model, ignoreCase = true) }
        if (isQwen) {
            // 关键：把客户端请求的模型名一路带到上游。
            // 旧代码这里只决定通道、不改模型名，而 QwenWebRepository 内部固定用
            // settings.qwenModel —— 于是「客户端选 3.7，实际跑 3.8」。
            com.dslauncher.data.qwen.qwenModelOverride = model
            return Provider.QWEN to null
        }
        com.dslauncher.data.qwen.qwenModelOverride = null
        return Provider.DEEPSEEK to null
    }

    /** mixed 模式无可用账号时的合并诊断文案。 */
    private fun mixedDiagnosis(): String {
        val ds = app.accounts.list().size
        val qw = app.qwenAccounts.list().size
        if (ds == 0 && qw == 0) {
            return "DeepSeek 与 Qwen 账号池均为空，请先在「账号池」里登录添加账号"
        }
        return "混合模式无可用账号 —— DeepSeek：${app.accounts.diagnosis()}；Qwen：${app.qwenAccounts.diagnosis()}"
    }

    private fun handleChat(out: BufferedOutputStream, body: String, forcedSession: String? = null, allowMultiHeader: Boolean = false) {
        val req = runCatching { JSONObject(body) }.getOrNull()
        if (req == null) {
            writeJson(out, 400, """{"error":{"message":"invalid json"}}""")
            return
        }
        val model = req.optString("model", DEFAULT_MODEL)
        // 按 model 路由：mixed=双池随机，Qwen 模型名=Qwen 通道，其余=DeepSeek（原行为）。
        val dispatch = resolveForModel(model)
        if (dispatch == null) {
            writeJson(out, 502, """{"error":{"message":"${mixedDiagnosis().replace("\"", "'")}"}}""")
            return
        }
        val (provider, forcedAccountId) = dispatch
        val stream = req.optBoolean("stream", true)
        val messages = req.optJSONArray("messages")
        val tools = req.optJSONArray("tools")
        // 开发者模式：只有开启时才注入工具调用协议。
        // 关闭时（默认）纯聊天——即使客户端带了 tools 也彻底忽略，不注入任何工具提示词。
        // 注入格式按服务方区分：DeepSeek 走 [Tool Call: ...] 协议（原样保留），
        // Qwen 走 qwenhook 的 <tool_call>{json}</tool_call> 协议。
        val devMode = app.settings.devMode
        val hasTools = devMode && tools != null && tools.length() > 0
        val toolInstruction = if (hasTools) {
            if (provider == Provider.QWEN) buildQwenToolInstruction(tools) else buildToolInstruction(tools)
        } else ""
        // 工具并发策略：默认「一次一个、等结果」。只有客户端显式带
        // x-allow-multi-tools: 1 / x-parallel-tools: 1，或本轮用户消息明确允许
        //（如「可以一次多个」「允许并行」）时，才允许多个工具并行。
        val allowMultiple = allowMultiHeader || lastUserAllowsMultiple(messages)
        // 上下文转文件：OpenAI 兼容客户端每轮都会把完整 messages 发过来。
        // 之前是把整段历史全拼进 prompt —— 客户端多轮后 prompt 越滚越长，而且官网
        // 侧 parent_message_id 链一断就彻底失忆。现在拆开：
        //   本轮用户输入 → prompt（真正要问的）
        //   此前历史     → contextTxt（走官网 txt 文件 + ref_file_ids，链路更稳）
        val prompt = buildLastUserPrompt(messages, toolInstruction)
        val historyTxt = buildHistoryTxt(messages)
        if (prompt.isBlank()) {
            writeJson(out, 400, """{"error":{"message":"empty messages"}}""")
            return
        }
        val id = "chatcmpl-" + System.currentTimeMillis()
        // 按请求内容解析官网会话 id：不再写死 CONV_ID=999，
        // 否则客户端每次「新建对话」都命中同一个官网 session+parent 链，
        // 表现就是「新对话接着上一个窗口继续聊」。
        val convId = resolveConversationId(req, messages, forcedSession)

        // 深度思考开关：启动器里的开关此前只作用于 App 内聊天页，
        // 开放 API 这条通道完全没读它 —— 表现就是「拨了没反应」。
        val deepThink = app.settings.deepThink
        if (stream) {
            streamChat(out, id, model, prompt, hasTools, convId, historyTxt, deepThink, allowMultiple, provider, forcedAccountId)
        } else {
            collectChat(out, id, model, prompt, hasTools, convId, historyTxt, deepThink, allowMultiple, provider, forcedAccountId)
        }
    }

    /**
     * 解析请求对应的官网会话 id。
     *
     * 为什么需要它：OpenAI 兼容协议本身没有「会话」概念（每次请求都带完整 messages），
     * 而官网侧是靠 session_id + parent_message_id 链维持上下文的。之前这里写死
     * CONV_ID = 999L，于是**所有客户端的所有对话共用同一个官网会话** ——
     * 表现就是「新建对话却接着上一个窗口继续聊」「上下文删不掉」。
     *
     * 规则（按优先级）：
     *  0) 请求头显式会话控制：x-force-session-id / x-conversation-id / x-session-id
     *  1) 请求体显式会话字段：conversation_id / chat_id / session_id
     *  2) 兜底：system 内容 + 第一条 user 消息内容的哈希 —— 同一对话追加消息时，
     *     「第一条 user 消息」不变，因此会稳定命中同一个官网会话；新建对话时
     *     第一句话不同，自然落到新会话。
     *     注意：**不把** user 字段单独当会话键 —— 很多 OpenAI 兼容客户端把它
     *     写死成同一个值，单独用它做键会让「新建对话」反复命中老会话。
     */
    private fun resolveConversationId(req: JSONObject, messages: JSONArray?, forcedSession: String? = null): Long {
        fun keyToId(k: String): Long =
            ("dslauncher_" + k).hashCode().toLong() and 0x7fffffffL

        // 0) 显式请求头（x-force-session-id / x-conversation-id / x-session-id）最高优先：
        //    客户端想锁定会话、或强开新会话时换个新值即可，不依赖指纹。
        if (!forcedSession.isNullOrBlank()) return keyToId("forced=$forcedSession")

        for (f in listOf("conversation_id", "chat_id", "session_id")) {
            val v = req.opt(f)
            if (v != null && v.toString().isNotBlank() && v.toString() != "null") {
                return keyToId("$f=$v")
            }
        }
        // 注意：**不能**把 user 字段单独当会话键。
        // OpenAI 兼容客户端普遍会带 user，且很多客户端把它写死成同一个值，
        // 一旦单独用它做键，「新建对话」就会反复命中同一条官网会话 ——
        // 表现就是「新对话仍在老会话里，只是丢了上下文」。
        // 这里把 user 并入下方的「system + 首条 user」指纹即可。

        val sb = StringBuilder()
        if (messages != null) {
            // system 指令（若有）：作为会话指纹的一部分，避免不同系统提示串流
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                if (m.optString("role") == "system") {
                    sb.append(extractContent(m.opt("content")))
                    break
                }
            }
            // 第一条 user 消息：对话的稳定起点
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                if (m.optString("role") == "user") {
                    sb.append('|').append(extractContent(m.opt("content")))
                    break
                }
            }
        }
        if (sb.isEmpty()) return DEFAULT_CONV_ID
        return keyToId("conv=$sb")
    }

    /**
     * 把 OpenAI 的 tools 定义翻译成「文本注入」提示，附加到 prompt 末尾。
     *
     * 官网接口不支持 tools 字段，只能靠提示词约定让模型输出工具调用块。
     * 两条硬约束：
     *   1) 格式唯一：只认 [Tool Call: 名称 {json}]，压制 <|tool_calls_begin|>、
     *      [T[T、代码围栏 JSON 等退化变体（长对话下模型最爱乱来）；
     *   2) 单发优先：默认一次只输出一个工具调用块，输出后立即停止、等结果，
     *      只有用户在本轮明确允许多个时才并行。多输出的块由 extractToolCalls 截断兜底。
     */
    private fun buildToolInstruction(tools: JSONArray): String {
        val sb = StringBuilder()
        sb.append("\n\n【工具调用协议（务必严格遵守）】\n")
        sb.append("需要调用工具时，你只能输出工具调用块，不要写解释、前言或 Markdown 代码块。\n\n")
        sb.append("★ 一次只输出一个工具调用块，输出后立即停止，等待工具执行结果返回。\n")
        sb.append("  在收到结果之前，绝对不要输出第二个工具调用块，也不要假设结果。\n")
        sb.append("  只有用户在本轮明确说「可以一次多个 / 允许并行 / 同时执行」时，才可一次输出多个。\n")
        sb.append("★ 绝对禁止输出任何 <|...|> 形式的特殊标记，例如 <|tool_calls_begin|>、<|tool_call_begin|>、\n")
        sb.append("  <|tool_calls_end|>、<|tool_call_end|>、<|end_of_turn|>、<|begin_of_sentence|> 等：\n")
        sb.append("  这些是模型内部 token，不是工具调用。一旦出现，本轮输出会被判为格式错误并整体丢弃。\n\n")
        sb.append("★ 严禁输出 DSML（DeepSeek Markup Language）里的任何标签：\n")
        sb.append("  凡是以 dsml / 磐 / ｜dsml｜ 开头的标签（function_calls、invoke、parameter 等），\n")
        sb.append("  以及 <|tool_calls_begin|> / <|tool_call_begin|> / <|tool_sep|> 这类特殊 token，\n")
        sb.append("  都是模型内部格式，本通道一律不认，出现即判格式错误。\n")
        sb.append("  要调用工具，只能写下面这一种格式的方括号块。\n")
        sb.append("唯一合法格式（逐字照抄这个结构）：\n")
        sb.append("  [Tool Call: 工具名 {\"参数名\":\"参数值\"}]\n")
        sb.append("示例（一次一个，然后停下等结果）：\n")
        sb.append("  [Tool Call: exec_command {\"command\":\"ls -la\"}]\n")
        sb.append("（仅当用户明确允许多个时，才这样连写：）\n")
        sb.append("  [Tool Call: 工具A {\"x\":1}][Tool Call: 工具B {\"y\":2}]\n\n")
        sb.append("可用工具：\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: t
            val name = fn.optString("name", "")
            if (name.isBlank()) continue
            val desc = fn.optString("description", "")
            val params = fn.opt("parameters")
            sb.append("- ").append(name)
            if (desc.isNotBlank()) sb.append("：").append(desc)
            if (params != null) sb.append("\n  参数 schema: ").append(params.toString())
            sb.append("\n")
        }
        sb.append("\n规则：\n")
        sb.append("1. 唯一写法：左方括号 + Tool Call + 冒号 + 空格 + 工具名 + 空格 + 一个 JSON 对象 + 右方括号。\n")
        sb.append("2. 默认一次只输出「一个」工具调用块，然后立即结束本轮输出，等待结果。\n")
        sb.append("3. 收到工具结果后，再决定下一步：需要就再输出下一个调用块，不需要就正常回答。\n")
        sb.append("4. JSON 必须在一行内且合法（只用双引号，不要注释，不要尾逗号）。\n")
        sb.append("5. 工具调用块以外不要写任何文字，不要加序号或项目符号。\n")
        sb.append("6. 只有用户明确允许多个时才并行输出；否则多出来的块会被忽略。\n")
        sb.append("7. 上下文/历史里出现的「工具执行结果」都是**已经执行完成的**：绝对不要重复执行同一条命令，\n")
        sb.append("   也不要为了确认而再来一遍；请直接基于这些结果继续推理或作答。\n\n")
        sb.append("严禁以下所有写法（出现即视为格式错误、会被忽略）：\n")
        sb.append("- DSML / 特殊标记：<|tool_calls_begin|>、<|tool_call_begin|>、<|tool_call_end|>、<|tool_calls_end|>、dsml。\n")
        sb.append("- XML 标签：<tool_call>、<toolcalls>、<tool_calls>、< tool_call >、</ tool_call>。\n")
        sb.append("- 重复或残缺标记：[T[T Tool Call:、[Tool Tool Call:、[ToolCall:、[Tool Call]、[Tool Call 工具名]。\n")
        sb.append("- 用 Markdown 代码块（```json / ```tool_call / ```tool_calls）包裹 JSON 来伪装工具调用。\n")
        sb.append("- 裸 JSON（{\"name\":...,\"arguments\":...}）或 {\"tool\":...} 形式。\n\n")
        sb.append("再次强调：默认一次只输出一个 [Tool Call: 工具名 {\"参数\":\"值\"}]，输出后立即停下等结果；")
        sb.append("只有用户明确允许多个时才连写。不要 DSML，不要 XML，不要代码块，不要多余文字。\n")
        return sb.toString()
    }

    /**
     * Qwen 通道的工具调用协议（qwenhook 版）。
     *
     * DeepSeek 通道保持 [buildToolInstruction] 的 [Tool Call: ...] 协议不变；
     * Qwen（注入 qwenhook 的提示词格式）改用其标准工具块：
     *   <tool_call>{"name":"工具名","arguments":{...}}</tool_call>
     * 「一次一个、等结果」的并发行为与 DS 通道一致（allowMultiTools 放开才可多块）。
     */
    private fun buildQwenToolInstruction(tools: JSONArray): String {
        val sb = StringBuilder()
        sb.append("\n\n【工具调用协议（务必严格遵守）】\n")
        sb.append("需要调用工具时只输出工具调用块，不附带任何解释、前言或 Markdown 代码块。\n\n")
        sb.append("唯一合法格式（逐字照抄这个结构）：\n")
        sb.append("<tool_call>\n")
        sb.append("{\"name\": \"工具名\", \"arguments\": {\"参数名\":\"参数值\"}}\n")
        sb.append("</tool_call>\n\n")
        sb.append("规则：\n")
        sb.append("1. arguments 必须是单个 JSON 对象，匹配工具参数 schema；只用双引号，不要注释，不要尾逗号。\n")
        sb.append("2. 默认一次只输出一个 <tool_call> 块，输出后立即停止本轮输出，工具结果下一轮以 [Tool result] 行返回。\n")
        sb.append("   在收到结果之前，绝对不要输出第二个工具调用块，也不要假设结果。\n")
        sb.append("3. 只有用户在本轮明确说「可以一次多个 / 允许并行 / 同时执行」时，才可一次输出多个 <tool_call> 块。\n")
        sb.append("4. 不需要工具时用自然语言正文回答，不要输出空的或示例用的工具调用块。\n")
        sb.append("5. 工具失败/不可用/报错时换方案或直接回答，不要卡住、不要重复同一个失败调用。\n")
        sb.append("6. 上下文/历史里的工具结果都是**已经执行完成的**：绝对不要重复执行同一条命令，直接基于结果继续。\n")
        sb.append("7. 绝对禁止输出 <|...|> 形式的特殊标记（如 <|tool_calls_begin|>），也不要用 [Tool Call: ...] 方括号格式。\n\n")
        sb.append("8. 绝对禁止输出 DSML 标签（function_calls / invoke / parameter 这类以 dsml 开头的标签）。\n\n")
        sb.append("可用工具：\n")
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: t
            val name = fn.optString("name", "")
            if (name.isBlank()) continue
            val desc = fn.optString("description", "")
            val params = fn.opt("parameters")
            sb.append("- ").append(name)
            if (desc.isNotBlank()) sb.append("：").append(desc)
            if (params != null) sb.append("\n  参数 schema: ").append(params.toString())
            sb.append("\n")
        }
        sb.append("\n再次强调：默认一次只输出一个 <tool_call> 块，输出后立即停下等结果；")
        sb.append("只有用户明确允许多个时才连写多个块。不要 DSML，不要方括号格式，不要代码块，不要多余文字。\n")
        return sb.toString()
    }

    /**
     * 本轮用户消息是否「明确允许多个工具并行」。
     *
     * 只有「允许语气词 + 多个语义词」同时出现才算，避免把普通的「多」误判。
     * 例：「这几个可以一次多个命令」→ true；「我有很多问题」→ false。
     */
    private fun lastUserAllowsMultiple(messages: JSONArray?): Boolean {
        if (messages == null) return false
        var lastUser = ""
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") {
                val c = extractContent(m.opt("content"))
                if (c.isNotBlank()) lastUser = c
            }
        }
        if (lastUser.isBlank()) return false
        val t = lastUser.lowercase()
        val allowWord = listOf("可以", "允许", "请", "能", "可", "allow", "parallel", "一起")
            .any { t.contains(it) }
        val multiWord = listOf("一次多个", "多个", "并行", "同时", "批量", "全部", "parallel", "batch", "multi")
            .any { t.contains(it) }
        return allowWord && multiWord
    }

    /**
     * 本轮 prompt = 最后一条 user 消息 + 其后的工具结果（本轮新增）+ 工具说明。
     *
     * 工具结果「单独随本轮 prompt 一次性下发」，且**不写入持久上下文**
     *（见 buildHistoryTxt，工具结果在那里被整体跳过）：
     *   - 好处 1：本轮模型能看到结果，任务得继续；
     *   - 好处 2：结果不进 context.txt / 会话链，下一轮模型看不到旧「命令+结果」，
     *     不会「照着历史一直重复命令」；
     *   - 好处 3：省掉大量上下文体积（工具输出动辄几十 KB）。
     */
    private fun buildLastUserPrompt(messages: JSONArray?, toolInstruction: String): String {
        if (messages == null) return ""
        var lastUserIdx = -1
        var last = ""
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") {
                val c = extractContent(m.opt("content"))
                if (c.isNotBlank()) {
                    last = c
                    lastUserIdx = i
                }
            }
        }
        val sb = StringBuilder()
        if (last.isNotBlank()) sb.append(last)
        // 本轮工具结果：只挂在 prompt 里一次性下发，不写入持久上下文。
        if (lastUserIdx >= 0) {
            val results = StringBuilder()
            for (i in lastUserIdx + 1 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                if (m.optString("role") != "tool") continue
                val c = extractContent(m.opt("content"))
                if (c.isBlank()) continue
                results.append("\n工具结果：").append(c)
            }
            if (results.isNotEmpty()) {
                sb.append("\n\n【本轮工具执行结果（仅本轮可见；请据此继续，不要重复执行同一命令）】")
                sb.append(results)
            }
        }
        if (toolInstruction.isNotBlank()) sb.append("\n\n").append(toolInstruction)
        return sb.toString().trim()
    }

    /**
     * 组装「本轮之前」的历史文本，交给 repo 上传为 context.txt。
     * 取最后一条 user 消息之前的所有消息；**不含工具结果（tool）**——工具结果
     * 只随本轮 prompt 一次性下发，不进持久上下文，避免模型「照着历史重复命令」
     * 并显著减小上下文体积。
     */
    /**
     * 上下文溢出阈值（参考 superapi 的 context_overflow_threshold 语义）：
     * context.txt 总字节超过该值时启动压缩，保留「开头锚点 + 最近内容」，
     * 避免官网文件持续膨胀触发哈希漂移 / 上传风暴。
     */
    private val CTX_OVERFLOW_BYTES = 380_000

    /** 工具结果单条最大字符数（工具输出是上下文膨胀主力）。 */
    private val TOOL_RESULT_MAX_CHARS = 1_200

    private fun buildHistoryTxt(messages: JSONArray?): String {
        if (messages == null) return ""
        var lastUser = -1
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "user") lastUser = i
        }
        if (lastUser <= 0) return ""
        val sb = StringBuilder()
        for (i in 0 until lastUser) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = extractContent(m.opt("content"))
            if (content.isBlank()) continue
            when (role) {
                "system" -> sb.append("系统指令：").append(content).append("\n")
                "assistant" -> {
                    // 工具调用块本身保留「调用过什么」的事实，但要改写成纯文本描述再入库：
                    // 直接塞原始 [Tool Call: …] 会让模型模仿该格式（「聊久了流口水」）。
                    val calls = extractToolCallNames(content)
                    if (calls.isNotBlank()) {
                        sb.append("助手（以下工具调用已经执行完毕，禁止重复执行）：")
                            .append(calls).append("\n")
                    }
                    val clean = stripToolCallBlocks(content)
                    if (clean.isNotBlank()) sb.append("助手：").append(clean).append("\n")
                }
                "tool" -> {
                    // 工具结果**必须写进上下文**。
                    //
                    // 旧实现把 tool 消息整体跳过，只在产生它的那一轮随 prompt 下发一次。
                    // 单账号时靠官网会话链还能撑住；但多账号轮询下会话链会随账号切换
                    // （见 DeepSeekWebRepository 的会话按账号隔离），等于「结果出现一轮就消失」，
                    // 模型于是无限重复同一条命令。单条做截断，避免上下文被工具输出撑爆。
                    val t = truncateMiddle(content, TOOL_RESULT_MAX_CHARS)
                    sb.append("工具执行结果（已执行，不要重复调用同一条命令）：")
                        .append(t).append("\n")
                }
                else -> sb.append("用户：").append(content).append("\n")
            }
        }
        val full = sb.toString().trim()
        // 溢出压缩（memory_consolidation）：保留开头锚点 + 最近内容。
        if (full.toByteArray(Charsets.UTF_8).size <= CTX_OVERFLOW_BYTES) return full
        return consolidateContext(full, CTX_OVERFLOW_BYTES)
    }

    /**
     * 上下文固化（参考 superapi 的 memory_consolidation）：
     * 超阈值时保留开头 10% 作为「锚点」，其余预算留给最近的对话尾部，
     * 中间用一行省略标记说明，避免整段历史被硬切丢。
     */
    private fun consolidateContext(text: String, budgetBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= budgetBytes) return text
        val headBudget = (budgetBytes * 10) / 100
        val tailBudget = budgetBytes - headBudget - 64
        // 按字符边界取头、尾（避免把多字节字符切坏）。
        val head = takeBytesFromStart(text, headBudget)
        val tail = takeBytesFromEnd(text, tailBudget)
        return head + "\n…（中间历史已按上下文溢出阈值省略）…\n" + tail
    }

    /** 从头部取不超过 maxBytes 的字符子串（不拆多字节字符）。 */
    private fun takeBytesFromStart(s: String, maxBytes: Int): String {
        val sb = StringBuilder()
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val need = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (n + need > maxBytes) break
            sb.appendCodePoint(cp)
            n += need
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** 从尾部取不超过 maxBytes 的字符子串（不拆多字节字符）。 */
    private fun takeBytesFromEnd(s: String, maxBytes: Int): String {
        val sb = StringBuilder()
        var n = 0
        var i = s.length
        while (i > 0) {
            val cp = s.codePointBefore(i)
            val need = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (n + need > maxBytes) break
            sb.appendCodePoint(cp)
            n += need
            i -= Character.charCount(cp)
        }
        return sb.reverse().toString()
    }

    /**
     * 从助手正文里提取工具调用块，改写成「工具名 + 参数」的纯文本描述。
     *
     * 目的：把「调用过什么」保留进上下文（否则换账号后模型完全不知道自己执行过），
     * 同时**不给模型可模仿的原始格式** —— 历史里直接出现 `[Tool Call: …]`
     * 会让模型学着一直吐这种前缀。
     */
    private fun extractToolCallNames(text: String): String {
        val out = ArrayList<String>()
        var s = normalizeToolMarkers(text)
        val tag = "[Tool Call:"
        while (true) {
            val a = s.indexOf(tag)
            if (a < 0) break
            val b = s.indexOf("]", a)
            if (b < 0) break
            val body = s.substring(a + tag.length, b).trim()
            if (body.isNotBlank()) out.add(body)
            s = s.substring(b + 1)
        }
        return out.joinToString("；")
    }

    /** 剥离历史正文里的工具调用块（防止模型模仿历史格式）。 */
    private fun stripToolCallBlocks(s: String): String {
        var out = normalizeToolMarkers(s)
        // 方括号风格 [Tool Call: ... ]
        while (true) {
            val a = out.indexOf("[Tool Call:")
            if (a < 0) break
            val b = out.indexOf("]", a)
            out = if (b < 0) out.substring(0, a)
            else out.substring(0, a) + out.substring(b + 1)
        }
        // XML 风格 <tool_calls>...</tool_calls> 与 
        out = out.replace(Regex("(?s)<tool_calls?>.*?</tool_calls?>"), "")
        out = out.replace(Regex("(?s)<tool_call>.*?</tool_call>"), "")
        return out.trim()
    }

    /** 头尾截断：保留开头与结尾，中间省略。 */
    private fun truncateMiddle(s: String, maxChars: Int): String {
        if (s.length <= maxChars) return s
        val head = maxChars / 3
        val tail = maxChars - head
        val omitted = s.length - maxChars
        val sb = StringBuilder()
        sb.append(s.take(head))
        sb.append('\n')
        sb.append("…（中间省略 ").append(omitted).append(" 字符）…")
        sb.append('\n')
        sb.append(s.takeLast(tail))
        return sb.toString()
    }

    private fun buildPrompt(messages: JSONArray?, toolInstruction: String): String {
        if (messages == null) return ""
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = extractContent(m.opt("content"))
            if (content.isBlank()) continue
            when (role) {
                "system" -> sb.append("系统指令：").append(content).append("\n\n")
                "assistant" -> sb.append("助手：").append(content).append("\n\n")
                "tool" -> sb.append("工具结果：").append(content).append("\n\n")
                else -> sb.append("用户：").append(content).append("\n\n")
            }
        }
        if (toolInstruction.isNotBlank()) sb.append(toolInstruction)
        return sb.toString().trim()
    }

    private fun extractContent(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until v.length()) {
                val o = v.optJSONObject(i)
                if (o != null) {
                    val t = o.optString("text", "")
                    if (t.isNotBlank()) sb.append(t)
                } else {
                    sb.append(v.optString(i, ""))
                }
            }
            sb.toString()
        }
        else -> v.toString()
    }

    private fun makeToolCall(index: Int, name: String, args: String): JSONObject {
        val a = runCatching { JSONObject(args) }.getOrElse { JSONObject() }
        return JSONObject()
            .put("index", index)
            .put("id", "call_" + System.currentTimeMillis().toString(36) + "_" + index)
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", name).put("arguments", a.toString())
            )
    }

    /**
     * 归一化「乱格式」变体，尽量修回标准标记，作为长对话格式退化的兜底。
     * 只做保守替换，不改变正文语义。
     */
    private fun normalizeToolMarkers(s: String): String {
        var out = s
        // DSML / 特殊 token -> 标准标记
        out = out.replace(Regex("(?i)<\\|?\\s*tool_calls?_begin\\s*\\|?>"), "[Tool Call:")
        out = out.replace(Regex("(?i)<\\|?\\s*tool_calls?_end\\s*\\|?>"), "]")
        out = out.replace(Regex("(?i)<\\s*/?\\s*dsml[^>]*>"), "")
        // XML -> 标准标记
        out = out.replace(Regex("(?i)<\\s*tool_call\\s*>"), "[Tool Call:")
        out = out.replace(Regex("(?i)<\\s*/\\s*tool_call\\s*>"), "]")
        out = out.replace(Regex("(?i)<\\s*/?\\s*tool_calls\\s*>"), "")
        // 重复 / 残缺前缀折叠 -> [Tool Call:
        //   [T[T Tool Call: / [T Tool Call: / [ToolCall:
        out = out.replace(
            Regex("\\[\\s*T(?:\\s*\\[?\\s*T)*\\s*ool\\s*Call\\s*:?", RegexOption.IGNORE_CASE),
            "[Tool Call:"
        )
        //   [Tool Tool Call: / [Tool  Tool Call:
        out = out.replace(
            Regex("\\[\\s*(?:Tool\\s+)+Call\\s*:?", RegexOption.IGNORE_CASE),
            "[Tool Call:"
        )
        // 兜底：任何残留的 <|...|> 特殊 token（模型内部标记）一律整体剔除，
        // 绝不让它们出现在最终正文里（含 <|end_of_turn|>、<|begin_of_sentence|> 等）。
        // DSML 残留标签（function_calls / invoke / parameter 等）整体剥掉，绝不让它漏进正文。
        out = out.replace(
            Regex("(?is)</?(?:[^<>{}]{0,26}?(?:dsml|磐)[^<>{}]{0,26}?)?(function_calls?|tool_calls?|parameter|invoke)\\s*[^<>]{0,48}>"),
            ""
        )
        out = out.replace(Regex("<\\|[^|]*\\|>"), "")
        return out
    }

    /**
     * 把一段工具调用 JSON 解析进 calls。兼容两种形态：
     *   {"name":..,"arguments":{..}}          —— 我们的 <tool_call> 格式
     *   [{"function":{..},"id":"call..","type":"function"}]  —— OpenAI 数组（superapi 认的）
     */
    private fun addParsedJson(raw: String, calls: JSONArray) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        runCatching {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val fn = o.optJSONObject("function") ?: o
                    val name = fn.optString("name")
                    if (name.isBlank()) continue
                    val args = fn.opt("arguments") ?: fn.opt("parameters") ?: "{}"
                    calls.put(makeToolCall(calls.length(), name, args.toString()))
                }
            } else {
                val o = JSONObject(trimmed)
                val name = o.optString("name")
                if (name.isBlank()) return
                val args = o.opt("arguments") ?: o.opt("parameters") ?: "{}"
                calls.put(makeToolCall(calls.length(), name, args.toString()))
            }
        }
    }

    /**
     * 从模型输出里提取工具调用，返回 (去掉调用块后的正文, tool_calls 或 null)。
     * 支持两种格式：
     *   [Tool Call: name {"k":"v"}]     —— superapi 风格
     *   <tool_call>{"name":...,"arguments":{...}}</tool_call>
     */
    /** 正文里出现这些标记就停止「边收边发」，转由末尾统一清理/解析（DSML 兜底）。 */
    private val DSML_OPEN_RE = Regex("(?s)<[^<>{}]{0,26}?(invoke\\s+name|function_calls?|tool_calls?_begin)")

    /** 正文级 DSML 清理：把模型误输出的 DSML 工具块整块剔除，只留自然语言。 */
    private fun stripDsmlFromText(text: String): String = parseDsmlCalls(text).first

    /**
     * DSML（DeepSeek Markup Language）与特殊 token 形态的工具调用解析。
     *
     * 新版 DeepSeek 模型经常无视提示词，直吐自家格式，典型有两种：
     *
     * 1) DSML 标签块（前缀五花八门：dsml / 磐 / ｜dsml｜ …）：
     * ```text
     *   <xxx function_calls>
     *     <xxx invoke name="get_weather">
     *       <xxx parameter name="city" string="true">Paris<xxx /parameter>
     *     <xxx /invoke>
     *   <xxx /function_calls>
     * ```
     * 2) tokenizer 直出形态：`tool_call_begin … function<sep>名称 {json} … tool_call_end`
     *
     * 本函数把两种都解析成标准 `tool_calls`，并把标记从正文里整块剔除 ——
     * 客户端（OpenAI / Anthropic 两种协议）拿到的就是正确格式，而不是一坨 DSML。
     */
    private fun parseDsmlCalls(text: String): Pair<String, JSONArray?> {
        val calls = JSONArray()
        // 标签名前缀用「0~26 个非空白字符」宽松匹配，兼容各家渲染出的奇怪前缀
        val invokeRe = Regex(
            "(?is)<[^<>{}]{0,26}?invoke\\s+name\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</?[^<>{}]{0,26}?invoke\\s*>"
        )
        val paramRe = Regex(
            "(?is)<[^<>{}]{0,26}?parameter\\s+name\\s*=\\s*[\"']([^\"']+)[\"']([^>]*)>(.*?)(?:</?[^<>{}]{0,26}?parameter\\s*>|$)"
        )
        var body = invokeRe.replace(text) { m ->
            parseDsmlInvoke(m.groupValues[1].trim(), m.groupValues[2], paramRe, calls)
            ""
        }
        // 特殊 token 形态：tool_call_begin … tool_call_end
        val tokenRe = Regex(
            "(?is)<[^<>{}]{0,26}?tool_calls?_begin[^>]*>(.*?)(?:</?[^<>{}]{0,26}?tool_calls?_end[^>]*>|$)"
        )
        body = tokenRe.replace(body) { m ->
            parseDsmlTokenBody(m.groupValues[1], calls)
            ""
        }
        // 收尾：任何残留的 DSML 标签整体剥掉（wrapper / 自定义前缀都清）
        body = body.replace(
            Regex("(?is)</?(?:[^<>{}]{0,26}?(?:dsml|磐)[^<>{}]{0,26}?)?(function_calls?|tool_calls?|parameter|invoke)\\s*[^<>]{0,48}>"),
            ""
        )
        return body to (if (calls.length() == 0) null else calls)
    }

    /** 解析单个 DSML invoke：优先 parameter 子元素，其次整段 JSON。 */
    private fun parseDsmlInvoke(name: String, inner: String, paramRe: Regex, calls: JSONArray) {
        val args = JSONObject()
        var any = false
        for (pm in paramRe.findAll(inner)) {
            val key = pm.groupValues[1].trim()
            val attrs = pm.groupValues[2]
            val value = pm.groupValues[3].trim()
            if (key.isEmpty()) continue
            // string="false" 时按 JSON 解析（数字/布尔/null），否则原样字符串
            val asString = !Regex("(?i)string\\s*=\\s*[\"']?false").containsMatchIn(attrs)
            args.put(
                key,
                if (asString) value else (runCatching { JSONTokener(value).nextValue() }.getOrNull() ?: value)
            )
            any = true
        }
        if (!any) {
            // 兜底：invoke 体内直接给了 JSON（部分网关会这么干）
            extractFirstJsonObject(inner)?.let { js ->
                runCatching { JSONObject(js) }.getOrNull()?.let { o ->
                    o.keys().forEach { k -> args.put(k, o.opt(k)) }
                }
            }
        }
        if (name.isNotBlank()) calls.put(makeToolCall(calls.length(), name, args.toString()))
    }

    /** 取第一段成对的 JSON 对象文本（大括号配对扫描，忽略字符串内的括号）。 */
    private fun extractFirstJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (j in start until s.length) {
            val c = s[j]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, j + 1)
                    }
                }
            }
        }
        return null
    }

    /** 特殊 token 块体：`function<sep>名称 {json}` / `名称{json}`。 */
    private fun parseDsmlTokenBody(innerRaw: String, calls: JSONArray) {
        val jsonStart = innerRaw.indexOf('{')
        val head = if (jsonStart >= 0) innerRaw.substring(0, jsonStart) else innerRaw
        val argsRaw = if (jsonStart >= 0) {
            extractFirstJsonObject(innerRaw.substring(jsonStart)) ?: "{}"
        } else "{}"
        var name = head
            .replace(Regex("(?is)<[^>]{0,60}>"), " ")
            .replace(Regex("(?i)function"), " ")
            .replace(Regex("[\\s|｜▁:：]+"), " ")
            .trim()
            .trim('"', ' ', '\'')
        if (name.contains(' ')) name = name.substringAfterLast(' ')
        if (name.isNotBlank()) calls.put(makeToolCall(calls.length(), name, argsRaw))
    }


    private fun extractToolCalls(text: String, allowMultiple: Boolean): Pair<String, JSONArray?> {
        val calls = JSONArray()
        // ① DSML / 特殊 token 形态优先解析（新版模型常见），解析后从正文里整块剔除，
        //    再走下面的常规格式归一化 —— 保证客户端拿到的是标准 tool_calls，而不是一坨 DSML。
        val dsml = parseDsmlCalls(text)
        dsml.second?.let { arr -> for (i in 0 until arr.length()) calls.put(arr.getJSONObject(i)) }
        var body = normalizeToolMarkers(dsml.first)

        // ③ <tool_call> 直接跟 JSON 对象的形态（模型最常输出的就是这个）：
        //    normalize 后形如 [Tool Call:{"name":..,"arguments":..}]，名字不在标记后面。
        //    必须在**抽取器**里认出来，否则既抽不到调用、块又被删掉 →
        //    正文空 + 无调用 = 客户端「返回空气」。
        val inlineObj = Regex("(?is)\\[Tool Call:\\s*(\\{[\\s\\S]*?\\})\\s*\\]")
        for (m in inlineObj.findAll(body)) addParsedJson(m.groupValues[1], calls)
        body = inlineObj.replace(body, "")

        // ① ```tool_call ...``` 围栏块（superapi 也认这个格式）—— 剥围栏取 JSON
        val fence = Regex("(?is)```[ \\t]*(?:tool_call|tool_calls)[ \\t]*\\r?\\n([\\s\\S]*?)```")
        for (m in fence.findAll(body)) addParsedJson(m.groupValues[1], calls)
        body = fence.replace(body, "")
        // ② 裸的 OpenAI 数组形态（superapi 的正则原样照搬）
        val openaiArr = Regex("(?is)\\[\\{\\s*\"function\"\\s*:\\s*\\{[\\s\\S]*?\\}\\s*,\\s*\"id\"\\s*:\\s*\"call[^\"]*\"\\s*,\\s*\"type\"\\s*:\\s*\"function\"\\s*\\}\\]")
        for (m in openaiArr.findAll(body)) addParsedJson(m.groupValues[0], calls)
        body = openaiArr.replace(body, "")

        // XML 风格
        val xml = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
        for (m in xml.findAll(body)) {
            val inner = m.groupValues[1].trim()
            val json = runCatching { JSONObject(inner) }.getOrNull()
            if (json != null) {
                val name = json.optString("name", "")
                val args = json.opt("arguments")
                if (name.isNotBlank()) {
                    calls.put(makeToolCall(calls.length(), name, (args ?: "{}").toString()))
                }
            }
            body = body.replace(m.value, "")
        }

        // [Tool Call: name {...}] 风格（手写扫描，正确处理嵌套花括号与字符串）
        val marker = "[Tool Call:"
        val outSb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val idx = body.indexOf(marker, i)
            if (idx < 0) {
                outSb.append(body, i, body.length)
                break
            }
            outSb.append(body, i, idx)
            var j = idx + marker.length
            while (j < body.length && body[j].isWhitespace()) j++
            val nameStart = j
            while (j < body.length && !body[j].isWhitespace() && body[j] != ']' && body[j] != '{') j++
            val name = body.substring(nameStart, j).trim()
            while (j < body.length && body[j].isWhitespace()) j++
            var args = "{}"
            if (j < body.length && body[j] == '{') {
                val s0 = j
                var depth = 0
                var inStr = false
                var esc = false
                while (j < body.length) {
                    val c = body[j]
                    if (inStr) {
                        if (esc) esc = false
                        else if (c == '\\') esc = true
                        else if (c == '"') inStr = false
                    } else {
                        if (c == '"') inStr = true
                        else if (c == '{') depth++
                        else if (c == '}') {
                            depth--
                            if (depth == 0) {
                                j++
                                break
                            }
                        }
                    }
                    j++
                }
                args = body.substring(s0, j)
            }
            while (j < body.length && body[j] != ']') j++
            if (j < body.length) j++
            if (name.isNotBlank()) {
                calls.put(makeToolCall(calls.length(), name, args))
            }
            i = j
        }
        body = outSb.toString().trim()

        // 索引重排：DSML 与常规格式混用时，保证 index 从 0 连续编号。
        for (i in 0 until calls.length()) runCatching { calls.getJSONObject(i).put("index", i) }

        // 单发优先：未获用户许可时，只保留第一个工具调用，其余丢弃。
        // 保证「一次一个、等结果」——多输出的块不会被客户端并行执行。
        val finalCalls = if (!allowMultiple && calls.length() > 1) {
            JSONArray().put(calls.getJSONObject(0))
        } else if (calls.length() > 0) calls else null
        return body to finalCalls
    }

    private fun streamChat(
        out: BufferedOutputStream,
        id: String,
        model: String,
        prompt: String,
        hasTools: Boolean,
        convId: Long,
        contextTxt: String?,
        thinkingEnabled: Boolean,
        allowMultiple: Boolean,
        provider: Provider,
        forcedAccountId: String?
    ) {
        // 关键：HTTP/1.1 下 SSE 必须显式声明分块传输，否则响应既无 Content-Length
        // 也无 Transfer-Encoding，客户端无法判定消息边界，只能一直读到连接关闭，
        // 所有 SSE 帧会被憋到流结束才一次性交付 —— 表现就是「没有流式」。
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()

        /** 按 HTTP/1.1 chunked 格式写出一段数据：<十六进制长度>\r\n<内容>\r\n */
        fun writeRawChunk(payload: ByteArray) {
            if (payload.isEmpty()) return
            out.write((Integer.toHexString(payload.size) + "\r\n").toByteArray(Charsets.UTF_8))
            out.write(payload)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        var emittedChars = 0
        var repeatStop = false
        val guard = RepeatGuard()

        fun writeChunk(delta: JSONObject, finish: String?) {
            // 复读熔断：正文一旦判定复读，就不再下发新正文（工具块/结束帧不受影响）
            if (delta.has("content")) {
                val c = delta.optString("content")
                if (c.isNotEmpty()) {
                    if (repeatStop) return
                    if (!guard.accept(c)) {
                        repeatStop = true
                        return
                    }
                    emittedChars += c.length
                }
            }
            val choice = JSONObject()
                .put("index", 0)
                .put("delta", delta)
                .put("finish_reason", finish ?: JSONObject.NULL)
            val frame = JSONObject()
                .put("id", id)
                .put("object", "chat.completion.chunk")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", model)
                .put("choices", JSONArray().put(choice))
            writeRawChunk("data: $frame\n\n".toByteArray(Charsets.UTF_8))
        }

        /** 以 0 长度块结束 chunked 流，并兼容 SSE 的 [DONE] 语义 */
        fun writeDone() {
            writeRawChunk("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            out.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        try {
            // 本地 API 层把模型吐出的 <tool_call> 文本转成结构化 tool_calls 给客户端执行；
            // Qwen 协议层不做任何归一化（那边只留提示词）。
            if (!hasTools) {
                writeChunk(JSONObject().put("role", "assistant").put("content", ""), null)
                // 未启用工具时同样要拦 DSML：模型可能无视提示词直吐自家标记，
                // 不能让它当正文流出去。做法与工具路径一致：hold-back + 命中标记即停，
                // 末尾统一把 DSML 整块清掉，只发清洗后的正文。
                val pending = StringBuilder()
                var streaming = true
                val hold = 64
                runBlocking {
                    upstreamChat(convId, prompt, contextTxt, thinkingEnabled, provider, forcedAccountId) { t ->
                        writeChunk(JSONObject().put("reasoning_content", t), null)
                    }.collect { chunk ->
                        pending.append(chunk)
                        if (streaming) {
                            if (DSML_OPEN_RE.containsMatchIn(pending.toString())) {
                                streaming = false
                            } else if (pending.length > hold) {
                                val emitLen = pending.length - hold
                                writeChunk(JSONObject().put("content", pending.substring(0, emitLen)), null)
                                pending.delete(0, emitLen)
                            }
                        }
                    }
                }
                val cleanText = stripDsmlFromText(pending.toString())
                if (cleanText.isNotBlank()) writeChunk(JSONObject().put("content", cleanText), null)
                writeChunk(JSONObject(), "stop")
                writeDone()
                return
            }

            // 有工具：**边收边流式吐正文**，同时留存全文用于末尾解析工具调用。
            //
            // 之前这里是「先收全再解析再发」——导致所有带 tools 的客户端（大量
            // OpenAI 兼容客户端默认带 tools）永远看不到流式，表现就是「还是没有流式」。
            // 现在改成流式：正文实时下发，只保留一小段尾巴（hold-back）防止把工具调用
            // 标记（[Tool Call: / <tool_call>）当正文吐出去；一旦命中标记立即停止正文流式，
            // 转入仅累积模式，末尾再统一解析 tool_calls。
            val full = StringBuilder()
            val pending = StringBuilder()
            var streaming = true
            val hold = 48 // ≥ "<tool_call>{ ... " 的长度：hold 太短会把标记切碎，导致漏检
            // 真正的工具块起点：<tool_call> / <tool_calls> 紧跟 JSON 左花括号，
            // 或 [Tool Call: 名称。正文里“提到”这个标签（不带花括号）不算。
            val TOOL_OPEN_RE = Regex(
                    "(?s)<tool_calls?>\\s*\\{|\\[Tool Call:\\s*[A-Za-z_]|```[ \\t]*tool_call|"
                        + "<[^<>{}]{0,26}?(?:invoke\\s+name|function_calls?|tool_calls?_begin)"
                )
            writeChunk(JSONObject().put("role", "assistant").put("content", ""), null)
            runBlocking {
                upstreamChat(convId, prompt, contextTxt, thinkingEnabled, provider, forcedAccountId) { t ->
                    writeChunk(JSONObject().put("reasoning_content", t), null)
                }
                    .collect { chunk ->
                        full.append(chunk)
                        if (streaming) {
                            pending.append(chunk)
                            val acc = pending.toString()
                            if (TOOL_OPEN_RE.containsMatchIn(acc)) {
                                // 命中「真」工具块起点：停止正文流式，交给末尾解析。
                                //
                                // 注意：必须是 <tool_call> 紧跟 { 才算。模型经常把提示词里的
                                // 字面量（如「默认一次只输出一个 <tool_call> 块」）复述出来，
                                // 旧逻辑见到 "<tool_call>" 三个字就永久 streaming=false，
                                // 整段正文被吞 → 客户端表现为「一调用工具就截断」。
                                streaming = false
                            } else if (pending.length > hold) {
                                val emitLen = pending.length - hold
                                writeChunk(JSONObject().put("content", pending.substring(0, emitLen)), null)
                                pending.delete(0, emitLen)
                            }
                        }
                    }
            }
            val (text, calls) = extractToolCalls(full.toString(), allowMultiple)
            if (streaming && pending.isNotEmpty() && !repeatStop) {
                // 全程没命中工具块：把 hold-back 的尾巴补发出去
                writeChunk(JSONObject().put("content", pending.toString()), null)
            }
            if (calls != null) {
                writeChunk(JSONObject().put("tool_calls", calls), "tool_calls")
            } else if (!streaming && !repeatStop) {
                // 曾命中标记但最终没解析出调用：补发去掉标记后的正文。
                // 兜底：连正文都被 normalize 清空了，就吐原始全文 —— 宁可吐原文，也不吐空气。
                val fallback = text.ifBlank { full.toString() }
                if (fallback.isNotBlank()) writeChunk(JSONObject().put("content", fallback), null)
                writeChunk(JSONObject(), "stop")
            } else {
                writeChunk(JSONObject(), "stop")
            }
            writeDone()
        } catch (e: Exception) {
            com.dslauncher.data.qwen.QwenWebClient.lastUpstreamMeta =
                "stream error: " + (e.message ?: e.javaClass.simpleName)
            // 已经吐过正文：按「正常结束」收尾，客户端不会再显示「中断/错误」
            //（上游的会话链断裂等异常往往发生在半途，硬报错会打断用户界面）
            if (emittedChars > 0) {
                writeChunk(JSONObject(), "stop")
                writeDone()
            } else {
                val err = JSONObject()
                    .put("error", JSONObject().put("message", e.message ?: "upstream error"))
                writeRawChunk("data: $err\n\n".toByteArray(Charsets.UTF_8))
                writeDone()
            }
        }
    }

    /**
     * 按调度结果取上游对话流：Qwen 走 QwenWebRepository，DeepSeek 走 DeepSeekWebRepository（原链路）。
     * mixed 模式下 forcedAccountId 指定本次使用的账号。
     */
    private fun upstreamChat(
        convId: Long,
        prompt: String,
        contextTxt: String?,
        thinkingEnabled: Boolean,
        provider: Provider,
        forcedAccountId: String?,
        onThinking: (String) -> Unit
    ): Flow<String> = when (provider) {
        Provider.QWEN -> app.qwenRepo.chatStream(
            conversationId = convId,
            prompt = prompt,
            thinkingEnabled = thinkingEnabled,
            contextTxt = contextTxt,
            forcedAccountId = forcedAccountId,
            onThinking = onThinking
        )
        Provider.DEEPSEEK -> app.webRepo.chatStream(
            conversationId = convId,
            prompt = prompt,
            contextTxt = contextTxt,
            thinkingEnabled = thinkingEnabled,
            forcedAccountId = forcedAccountId,
            onThinking = onThinking
        )
    }

    private fun collectChat(
        out: BufferedOutputStream,
        id: String,
        model: String,
        prompt: String,
        hasTools: Boolean,
        convId: Long,
        contextTxt: String?,
        thinkingEnabled: Boolean,
        allowMultiple: Boolean,
        provider: Provider,
        forcedAccountId: String?
    ) {
        val sb = StringBuilder()
        // 思考内容（reasoning_content）：非流式下也累积，最后放进 message.reasoning_content，
        // 否则「不显示思考内容」。之前这里没传 onThinking，reasoning 被直接丢弃。
        val reasoningSb = StringBuilder()
        var error: String? = null
        runBlocking {
            runCatching {
                upstreamChat(convId, prompt, contextTxt, thinkingEnabled, provider, forcedAccountId) { t ->
                    reasoningSb.append(t)
                }
                    .collect { sb.append(it) }
            }.onFailure { error = it.message }
        }
        if (error != null) {
            writeJson(
                out, 502,
                JSONObject().put("error", JSONObject().put("message", error)).toString()
            )
            return
        }

        val message = JSONObject().put("role", "assistant")
        var finish = "stop"
        // 本地 API 层恢复抽取：文本 -> 结构化 tool_calls（Qwen 协议层仍不解析）
        if (hasTools) {
            val (text, calls) = extractToolCalls(sb.toString(), allowMultiple)
            message.put("content", text)
            if (calls != null) {
                // 非流式不需要 index 字段
                val clean = JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    c.remove("index")
                    clean.put(c)
                }
                message.put("tool_calls", clean)
                finish = "tool_calls"
            }
        } else {
            // 非工具路径同样清理 DSML，避免标记混进正文
            message.put("content", truncateRepetition(stripDsmlFromText(sb.toString())))
        }

        val resp = JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", model)
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", finish)
                )
            )
        writeJson(out, 200, resp.toString())
    }

    // ------------------------------------------------------------ Anthropic 兼容层
    //
    // 同一条上游链路（DeepSeek / Qwen 网页端）同时暴露两套协议，客户端按需选：
    //   OpenAI 兼容    ：POST /v1/chat/completions（原生实现）
    //   Anthropic 兼容 ：POST /v1/messages（本层把 Messages API 翻译成同一条链路）
    // 账号池、会话隔离、配额、工具协议、深度思考全部复用，客户端只需改 base_url。

    /** Anthropic 错误体：{"type":"error","error":{"type":..,"message":..}} */
    private fun writeAnthropicError(out: BufferedOutputStream, code: Int, type: String, message: String) {
        writeJson(
            out, code,
            JSONObject()
                .put("type", "error")
                .put("error", JSONObject().put("type", type).put("message", message))
                .toString()
        )
    }

    /**
     * 复读熔断：模型陷入「同一句话反复输出」时，及时判定并截断。
     *
     * 判据：已发正文的**末尾**出现「同一小段（8~120 字）连续重复 ≥3 次」。
     * 命中后 [cutAt] = 第一次出现的位置，调用方停止继续下发并只保留第一遍。
     * 只用滚动窗口（600 字）判定，成本极低。
     */
    private class RepeatGuard {
        private val buf = StringBuilder()
        var cutAt: Int = -1
            private set
        private var total: Int = 0

        /** true = 正常，可继续下发；false = 判定复读，应停止下发。 */
        fun accept(text: String): Boolean {
            if (cutAt >= 0) return false
            buf.append(text)
            total += text.length
            if (buf.length > 600) buf.delete(0, buf.length - 600)
            val n = buf.length
            if (n < 120) return true
            val maxP = minOf(120, n / 3)
            for (p in 8..maxP) {
                val a = buf.substring(n - p)
                if (a.isBlank()) continue
                if (a != buf.substring(n - 2 * p, n - p)) continue
                if (a == buf.substring(n - 3 * p, n - 2 * p)) {
                    cutAt = total - 3 * p
                    return false
                }
            }
            return true
        }
    }

    /** 非流式：对整段文本做一遍复读截断（没复读就原样返回）。 */
    private fun truncateRepetition(text: String): String {
        if (text.length < 120) return text
        val g = RepeatGuard()
        var i = 0
        while (i < text.length) {
            val end = minOf(text.length, i + 64)
            if (!g.accept(text.substring(i, end))) break
            i = end
        }
        val cut = g.cutAt
        return if (cut in 1 until text.length) text.substring(0, cut) else text
    }

    /** 粗略 token 估算：CJK 按 1 token/字，其余按 4 字符/token。 */
    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            val c = ch.code
            if (c in 0x2E80..0x9FFF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF || c in 0x3000..0x303F) cjk++
            else other++
        }
        return cjk + (other + 3) / 4
    }

    /**
     * Anthropic Messages 请求 → 内部（OpenAI 形状）消息数组。
     * - `system` 可为字符串或 `[{type:text,text:..}]`；
     * - `content` 可为字符串或块数组（text / image / tool_use / tool_result / thinking）；
     *   tool_use / tool_result 走本项目基于文本的工具协议，转成可读文本回灌上下文。
     */
    private fun anthropicMessages(req: JSONObject): JSONArray {
        val out = JSONArray()
        val sys = req.opt("system")
        val sysText = when (sys) {
            null -> ""
            is String -> sys
            is JSONArray -> (0 until sys.length()).joinToString("") { i ->
                val b = sys.optJSONObject(i) ?: return@joinToString ""
                if (b.optString("type") == "text") b.optString("text") else ""
            }

            else -> sys.toString()
        }
        if (sysText.isNotBlank()) out.put(JSONObject().put("role", "system").put("content", sysText))
        val arr = req.optJSONArray("messages") ?: return out
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            out.put(
                JSONObject()
                    .put("role", m.optString("role", "user"))
                    .put("content", anthropicContentToText(m.opt("content")))
            )
        }
        return out
    }

    /** 把 Anthropic 的 content（字符串 / 块数组）拍平成文本，供内部链路使用。 */
    private fun anthropicContentToText(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is JSONArray -> {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i) ?: continue
                when (b.optString("type")) {
                    "text" -> sb.append(b.optString("text"))
                    "thinking" -> { /* 思考块不回灌 */ }
                    "image" -> sb.append("[图片]")
                    "tool_use" -> sb.append("[Tool Call: ").append(b.optString("name"))
                        .append("(").append(b.opt("input")?.toString() ?: "{}").append(")]\n")

                    "tool_result" -> sb.append("[工具结果] ")
                        .append(anthropicContentToText(b.opt("content"))).append("\n")

                    else -> b.optString("text").let { if (it.isNotBlank()) sb.append(it) }
                }
            }
            sb.toString()
        }

        else -> content.toString()
    }

    /** Anthropic tools（{name,description,input_schema}）→ OpenAI 形状（{type:function,function:{..}}）。 */
    private fun anthropicToolsToOpenAi(tools: JSONArray?): JSONArray? {
        if (tools == null || tools.length() == 0) return null
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: JSONObject()
                .put("name", t.optString("name"))
                .put("description", t.optString("description"))
                .put("parameters", t.opt("input_schema") ?: JSONObject().put("type", "object"))
            out.put(JSONObject().put("type", "function").put("function", fn))
        }
        return if (out.length() == 0) null else out
    }

    /** 请求体里的全部输入文本（用于 token 估算）。 */
    private fun anthropicInputText(req: JSONObject): String {
        val sb = StringBuilder()
        sb.append(anthropicContentToText(req.opt("system")))
        val arr = req.optJSONArray("messages")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                sb.append(anthropicContentToText(arr.optJSONObject(i)?.opt("content")))
            }
        }
        sb.append(req.optJSONArray("tools")?.toString() ?: "")
        return sb.toString()
    }

    /** `POST /v1/messages/count_tokens` → {"input_tokens": N}（估算值）。 */
    private fun anthropicCountTokens(body: String): String {
        val req = runCatching { JSONObject(body) }.getOrNull()
            ?: return JSONObject().put("input_tokens", 0).toString()
        return JSONObject().put("input_tokens", estimateTokens(anthropicInputText(req))).toString()
    }

    /** Anthropic 兼容主入口：翻译请求 → 复用内部上游链路 → 按 Anthropic 形状回包。 */
    private fun handleAnthropicMessages(
        out: BufferedOutputStream,
        body: String,
        forcedSession: String?,
        allowMultiHeader: Boolean
    ) {
        val req = runCatching { JSONObject(body) }.getOrNull()
        if (req == null) {
            writeAnthropicError(out, 400, "invalid_request_error", "请求体不是合法 JSON")
            return
        }
        val model = req.optString("model", DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
        val dispatch = resolveForModel(model)
        if (dispatch == null) {
            writeAnthropicError(out, 502, "api_error", mixedDiagnosis())
            return
        }
        val (provider, forcedAccountId) = dispatch
        val messages = anthropicMessages(req)
        val tools = anthropicToolsToOpenAi(req.optJSONArray("tools"))
        val devMode = app.settings.devMode
        val hasTools = devMode && tools != null
        val toolInstruction = if (hasTools) {
            if (provider == Provider.QWEN) buildQwenToolInstruction(tools!!) else buildToolInstruction(tools!!)
        } else ""
        val allowMultiple = allowMultiHeader || lastUserAllowsMultiple(messages)
        val prompt = buildLastUserPrompt(messages, toolInstruction)
        if (prompt.isBlank()) {
            writeAnthropicError(out, 400, "invalid_request_error", "messages 为空：至少需要一条 user 消息")
            return
        }
        val historyTxt = buildHistoryTxt(messages)
        val convId = resolveConversationId(req, messages, forcedSession)
        // 思考：Anthropic 用 thinking:{type:"enabled",budget_tokens:N} 表达
        val thinkingReq = req.optJSONObject("thinking")?.optString("type") == "enabled"
        // 上游是否思考 = App 的深度思考开关 或 客户端显式要求
        val deepThink = app.settings.deepThink || thinkingReq
        // 但 thinking 块**只在客户端显式开启 thinking 时**才下发 —— 老版 Anthropic SDK
        // 不认识 thinking 块类型，多发了反而会解析失败。
        val exposeThinking = thinkingReq
        val maxTokens = req.optInt("max_tokens", 0)
        val inputTokens = estimateTokens(anthropicInputText(req))
        val id = "msg_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)

        if (req.optBoolean("stream", false)) {
            streamAnthropic(
                out, id, model, prompt, hasTools, convId, historyTxt, deepThink, exposeThinking,
                allowMultiple, provider, forcedAccountId, maxTokens, inputTokens
            )
        } else {
            collectAnthropic(
                out, id, model, prompt, hasTools, convId, historyTxt, deepThink, exposeThinking,
                allowMultiple, provider, forcedAccountId, maxTokens, inputTokens
            )
        }
    }

    /**
     * 流式（Anthropic SSE 事件序列）：
     * message_start → (thinking 块) → text 块 → 工具块 → message_delta → message_stop
     */
    private fun streamAnthropic(
        out: BufferedOutputStream,
        id: String,
        model: String,
        prompt: String,
        hasTools: Boolean,
        convId: Long,
        contextTxt: String?,
        thinkingEnabled: Boolean,
        exposeThinking: Boolean,
        allowMultiple: Boolean,
        provider: Provider,
        forcedAccountId: String?,
        maxTokens: Int,
        inputTokens: Int
    ) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "anthropic-version: 2023-06-01\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()

        fun writeRawChunk(payload: ByteArray) {
            if (payload.isEmpty()) return
            out.write((Integer.toHexString(payload.size) + "\r\n").toByteArray(Charsets.UTF_8))
            out.write(payload)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun sse(event: String, data: JSONObject) {
            writeRawChunk(("event: " + event + "\ndata: " + data.toString() + "\n\n").toByteArray(Charsets.UTF_8))
        }

        fun finishStream() {
            out.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        var nextIndex = 0
        var thinkIdx = -1
        var textIdx = -1

        fun startThinking() {
            if (thinkIdx >= 0) return
            thinkIdx = nextIndex++
            sse(
                "content_block_start",
                JSONObject().put("type", "content_block_start").put("index", thinkIdx)
                    .put("content_block", JSONObject().put("type", "thinking").put("thinking", ""))
            )
        }

        fun startText() {
            if (textIdx >= 0) return
            textIdx = nextIndex++
            sse(
                "content_block_start",
                JSONObject().put("type", "content_block_start").put("index", textIdx)
                    .put("content_block", JSONObject().put("type", "text").put("text", ""))
            )
        }

        fun stopBlock(i: Int) {
            if (i >= 0) sse("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", i))
        }

        fun thinkDelta(t: String) {
            if (!exposeThinking) return
            startThinking()
            sse(
                "content_block_delta",
                JSONObject().put("type", "content_block_delta").put("index", thinkIdx)
                    .put("delta", JSONObject().put("type", "thinking_delta").put("thinking", t))
            )
        }

        var emittedChars = 0
        var repeatStop = false
        val guard = RepeatGuard()

        fun textDelta(c: String) {
            // 复读熔断：判定复读后停止下发新正文（块收尾照常）
            if (c.isNotEmpty()) {
                if (repeatStop) return
                if (!guard.accept(c)) {
                    repeatStop = true
                    return
                }
                emittedChars += c.length
            }
            startText()
            sse(
                "content_block_delta",
                JSONObject().put("type", "content_block_delta").put("index", textIdx)
                    .put("delta", JSONObject().put("type", "text_delta").put("text", c))
            )
        }

        // message_start：立刻下发，客户端据此进入流式 UI
        sse(
            "message_start",
            JSONObject().put("type", "message_start").put(
                "message",
                JSONObject().put("id", id).put("type", "message").put("role", "assistant")
                    .put("model", model).put("content", JSONArray())
                    .put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)
                    .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", 0))
            )
        )
        sse("ping", JSONObject().put("type", "ping"))

        var stopReason = "end_turn"
        val full = StringBuilder()
        try {
            if (!hasTools) {
                // 未启用工具也要拦 DSML（与 OpenAI 通道同一策略）：
                // hold-back + 命中标记即停 + 末尾统一清理
                val pending = StringBuilder()
                var streaming = true
                val hold = 64
                runBlocking {
                    upstreamChat(convId, prompt, contextTxt, thinkingEnabled, provider, forcedAccountId) { t ->
                        thinkDelta(t)
                    }.collect { chunk ->
                        full.append(chunk)
                        pending.append(chunk)
                        if (streaming) {
                            if (DSML_OPEN_RE.containsMatchIn(pending.toString())) {
                                streaming = false
                            } else if (pending.length > hold) {
                                val emitLen = pending.length - hold
                                textDelta(pending.substring(0, emitLen))
                                pending.delete(0, emitLen)
                            }
                        }
                    }
                }
                val cleanText = stripDsmlFromText(pending.toString())
                if (cleanText.isNotBlank()) textDelta(cleanText)
            } else {
                // 带工具：正文边收边吐，尾部 hold-back 防止把工具标记当正文吐出去
                val pending = StringBuilder()
                var streaming = true
                val hold = 48
                val TOOL_OPEN_RE = Regex(
                    "(?s)<tool_calls?>\\s*\\{|\\[Tool Call:\\s*[A-Za-z_]|```[ \\t]*tool_call|"
                        + "<[^<>{}]{0,26}?(?:invoke\\s+name|function_calls?|tool_calls?_begin)"
                )
                runBlocking {
                    upstreamChat(convId, prompt, contextTxt, thinkingEnabled, provider, forcedAccountId) { t ->
                        thinkDelta(t)
                    }.collect { chunk ->
                        full.append(chunk)
                        if (streaming) {
                            pending.append(chunk)
                            if (TOOL_OPEN_RE.containsMatchIn(pending.toString())) {
                                streaming = false
                            } else if (pending.length > hold) {
                                val emitLen = pending.length - hold
                                textDelta(pending.substring(0, emitLen))
                                pending.delete(0, emitLen)
                            }
                        }
                    }
                }
                if (streaming && pending.isNotEmpty()) textDelta(pending.toString())
            }
        } catch (e: Exception) {
            com.dslauncher.data.qwen.QwenWebClient.lastUpstreamMeta =
                "anthropic stream error: " + (e.message ?: e.javaClass.simpleName)
            stopBlock(thinkIdx)
            stopBlock(textIdx)
            // 已经吐过正文：按正常收尾（end_turn + message_stop），不再下发 error 事件，
            // 避免客户端把「半途的上游异常」显示成「响应中断」
            if (emittedChars > 0) {
                sse(
                    "message_delta",
                    JSONObject().put("type", "message_delta")
                        .put("delta", JSONObject().put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL))
                        .put("usage", JSONObject().put("output_tokens", emittedChars / 2))
                )
                sse("message_stop", JSONObject().put("type", "message_stop"))
                finishStream()
                return
            }
            sse(
                "error",
                JSONObject().put("type", "error")
                    .put("error", JSONObject().put("type", "api_error").put("message", e.message ?: "upstream error"))
            )
            finishStream()
            return
        }

        stopBlock(thinkIdx)
        stopBlock(textIdx)

        // 工具调用 → tool_use 块（Anthropic 形状）
        if (hasTools) {
            val (text, calls) = extractToolCalls(full.toString(), allowMultiple)
            if (calls != null) {
                if (textIdx < 0 && text.isNotBlank()) textDelta(text)
                for (i in 0 until calls.length()) {
                    val fn = calls.optJSONObject(i)?.optJSONObject("function") ?: continue
                    val ti = nextIndex++
                    sse(
                        "content_block_start",
                        JSONObject().put("type", "content_block_start").put("index", ti).put(
                            "content_block",
                            JSONObject().put("type", "tool_use")
                                .put("id", "toolu_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20))
                                .put("name", fn.optString("name"))
                                .put("input", JSONObject())
                        )
                    )
                    sse(
                        "content_block_delta",
                        JSONObject().put("type", "content_block_delta").put("index", ti).put(
                            "delta",
                            JSONObject().put("type", "input_json_delta")
                                .put("partial_json", fn.optString("arguments").ifBlank { "{}" })
                        )
                    )
                    sse("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", ti))
                }
                stopReason = "tool_use"
            }
        }

        val outputTokens = estimateTokens(full.toString())
        if (stopReason == "end_turn" && maxTokens > 0 && outputTokens >= maxTokens) stopReason = "max_tokens"
        sse(
            "message_delta",
            JSONObject().put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL))
                .put("usage", JSONObject().put("output_tokens", outputTokens))
        )
        sse("message_stop", JSONObject().put("type", "message_stop"))
        finishStream()
    }

    /** 非流式：按 Anthropic 形状返回 content 块数组。 */
    private fun collectAnthropic(
        out: BufferedOutputStream,
        id: String,
        model: String,
        prompt: String,
        hasTools: Boolean,
        convId: Long,
        contextTxt: String?,
        thinkingEnabled: Boolean,
        exposeThinking: Boolean,
        allowMultiple: Boolean,
        provider: Provider,
        forcedAccountId: String?,
        maxTokens: Int,
        inputTokens: Int
    ) {
        val sb = StringBuilder()
        val reasoningSb = StringBuilder()
        var error: String? = null
        runBlocking {
            runCatching {
                upstreamChat(convId, prompt, contextTxt, thinkingEnabled, provider, forcedAccountId) { t ->
                    reasoningSb.append(t)
                }.collect { sb.append(it) }
            }.onFailure { error = it.message }
        }
        if (error != null) {
            writeAnthropicError(out, 502, "api_error", error!!)
            return
        }

        val content = JSONArray()
        if (exposeThinking && reasoningSb.isNotEmpty()) {
            content.put(JSONObject().put("type", "thinking").put("thinking", reasoningSb.toString()))
        }
        var stop = "end_turn"
        if (hasTools) {
            val (text, calls) = extractToolCalls(sb.toString(), allowMultiple)
            if (text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", text))
            if (calls != null) {
                for (i in 0 until calls.length()) {
                    val fn = calls.optJSONObject(i)?.optJSONObject("function") ?: continue
                    val args = runCatching { JSONObject(fn.optString("arguments")) }.getOrElse { JSONObject() }
                    content.put(
                        JSONObject().put("type", "tool_use")
                            .put("id", "toolu_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20))
                            .put("name", fn.optString("name"))
                            .put("input", args)
                    )
                }
                stop = "tool_use"
            }
        } else {
            content.put(JSONObject().put("type", "text").put("text", truncateRepetition(stripDsmlFromText(sb.toString()))))
        }
        if (content.length() == 0) content.put(JSONObject().put("type", "text").put("text", ""))
        val outputTokens = estimateTokens(sb.toString())
        if (stop == "end_turn" && maxTokens > 0 && outputTokens >= maxTokens) stop = "max_tokens"

        writeJson(
            out, 200,
            JSONObject().put("id", id).put("type", "message").put("role", "assistant").put("model", model)
                .put("content", content).put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
                .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens))
                .toString()
        )
    }

    // ------------------------------------------------------------ 网页控制台后端

    /** 控制台全量状态：服务、设置、双池账号、模型、隧道、Qwen 续期概览。 */
    private fun adminStateJson(): String {
        val s = app.settings
        val now = System.currentTimeMillis()
        val port = s.port
        val lanIp = app.lanIp()

        val dsArr = JSONArray()
        app.accounts.list().forEach { dsArr.put(dsAccountJson(it, now)) }
        val qwArr = JSONArray()
        app.qwenAccounts.list().forEach { qwArr.put(qwenAccountJson(it, now)) }

        val server = JSONObject()
            .put("running", app.apiServer?.isRunning == true)
            .put("port", port)
            .put("allowLan", s.allowLan)
            .put("keySet", s.apiKey.isNotBlank())
            .put("keyMasked", s.apiKey)
            .put("localUrl", "http://127.0.0.1:$port/v1")
            .put("openaiUrl", "http://127.0.0.1:$port/v1")
            .put("anthropicUrl", "http://127.0.0.1:$port")
            .put("tunnelUrl", app.tunnel.publicUrl ?: s.tunnelUrl.takeIf { it.isNotBlank() } ?: "")
            .put("lastError", app.apiServer?.lastError ?: "")
        if (s.allowLan && !lanIp.isNullOrBlank()) {
            server.put("lanUrl", "http://$lanIp:$port/v1")
            server.put("lanOpenaiUrl", "http://$lanIp:$port/v1")
            server.put("lanAnthropicUrl", "http://$lanIp:$port")
        }

        val settings = JSONObject()
            .put("deepThink", s.deepThink)
            .put("antiRecall", s.antiRecall)
            .put("devMode", s.devMode)
            .put("floatingWindow", s.floatingWindow)
            .put("allowLan", s.allowLan)
            .put("autoRotateFingerprint", app.accounts.autoRotateFingerprint)
            .put("fingerprintRotateDays", app.accounts.fingerprintRotateDays)
            .put("qwenModel", s.qwenModel)
            .put("qwenUseBrowser", s.qwenUseBrowser)
            .put("qwenAutoRefresh", s.qwenAutoRefresh)
            .put("qwenRefreshLeadMin", s.qwenRefreshLeadMin)
            .put("tunnelEnabled", s.tunnelEnabled)

        val models = JSONObject()
            .put("ds", JSONArray(MODEL_IDS))
            .put("mixed", MIXED_MODEL)
            .put("qwen", JSONArray(qwenModelIds()))

        val ds = JSONObject()
            .put("mode", app.accounts.mode.name)
            .put("defaultQuota", app.accounts.defaultQuota)
            .put("quotaCooldownMs", app.accounts.quotaCooldownMs)
            .put("compact", app.accounts.compactAccounts)
            .put("diagnosis", app.accounts.diagnosis())
            .put("exportJson", app.accounts.exportJson())
            .put("accounts", dsArr)

        val qw = JSONObject()
            .put("mode", app.qwenAccounts.mode.name)
            .put("defaultQuota", app.qwenAccounts.defaultQuota)
            .put("quotaCooldownMs", app.qwenAccounts.quotaCooldownMs)
            .put("compact", app.qwenAccounts.compactAccounts)
            .put("diagnosis", app.qwenAccounts.diagnosis())
            .put("capable", app.qwenAccounts.autoRefreshCapableCount())
            .put("browserStatus", app.qwenClient.browserStatus())
            .put("exportJson", qwenExportJson())
            .put("accounts", qwArr)

        val tunnel = JSONObject()
            .put("running", app.tunnel.running)
            .put("publicUrl", app.tunnel.publicUrl ?: "")
            .put("lastError", app.tunnel.lastError ?: "")

        return JSONObject()
            .put("app", JSONObject().put("version", APP_VERSION).put("author", AUTHOR_NAME))
            .put("server", server)
            .put("settings", settings)
            .put("models", models)
            .put("ds", ds)
            .put("qwen", qw)
            .put("tunnel", tunnel)
            .toString()
    }

    private fun dsAccountJson(a: com.dslauncher.data.Account, now: Long): JSONObject {
        val cooling = a.cooldownUntil > now
        val quotaOut = a.quotaLeft() == 0
        val mutedNow = a.mutedNow(now)
        val status = when {
            a.tokenInvalid -> "Token失效"
            a.needRelogin -> "需重登"
            mutedNow -> "禁言中"
            !a.enabled -> "已停用"
            cooling -> "冷却中"
            quotaOut -> "配额尽"
            else -> "正常"
        }
        val line = buildString {
            append("已用 ${a.usedCount}")
            append(if (a.quota > 0) " / ${a.quota}" else " / 不限")
            if (cooling) {
                append("  ·  冷却中")
                val left = a.cooldownUntil - now
                if (left > 0) append("（剩余 ${com.dslauncher.data.formatRemaining(left)}）")
            }
            a.muteRemainingMs(now)?.let { append("  ·  禁言剩余 ${com.dslauncher.data.formatRemaining(it)}") }
            if (quotaOut) append("  ·  配额用尽")
            if (a.tokenInvalid) append("  ·  ✖ Token 已失效，请重新登录")
            else if (a.needRelogin) append("  ·  需重新登录")
            if (a.failCount > 0) append("  ·  失败 ${a.failCount} 次")
        }
        return JSONObject()
            .put("id", a.id).put("label", a.label).put("display", a.display)
            .put("email", a.email ?: "").put("phone", a.phone ?: "")
            .put("fp", a.deviceId.takeLast(8))
            .put("enabled", a.enabled)
            .put("mutedNow", mutedNow)
            .put("tokenInvalid", a.tokenInvalid)
            .put("needRelogin", a.needRelogin)
            .put("cooling", cooling)
            .put("quotaOut", quotaOut)
            .put("quota", a.quota).put("used", a.usedCount)
            .put("statusText", status + "  ·  " + line)
    }

    private fun qwenAccountJson(a: com.dslauncher.data.qwen.QwenAccount, now: Long): JSONObject {
        val cooling = a.cooldownUntil > now
        val quotaOut = a.quotaLeft() == 0
        val status = when {
            a.tokenInvalid -> "Token失效"
            a.needRelogin -> "需重登"
            !a.enabled -> "已停用"
            cooling -> "冷却中"
            quotaOut -> "配额尽"
            else -> "正常"
        }
        return JSONObject()
            .put("id", a.id).put("label", a.label).put("display", a.display)
            .put("email", a.email ?: "")
            .put("enabled", a.enabled)
            .put("hasCookie", a.canAutoRefresh)
            .put("refreshFail", a.refreshFail)
            .put("ttlText", a.ttlText(now))
            .put("tokenInvalid", a.tokenInvalid)
            .put("needRelogin", a.needRelogin)
            .put("cooling", cooling)
            .put("quotaOut", quotaOut)
            .put("quota", a.quota).put("used", a.usedCount)
            .put("statusText", status + "  ·  已用 ${a.usedCount}" +
                (if (a.quota > 0) " / ${a.quota}" else " / 不限") +
                "  ·  " + a.ttlText(now) +
                (if (a.canAutoRefresh) "  ·  Cookie 已存，自动续期生效" else "  ·  未存 Cookie（重新登录一次即可自动续期）"))
    }

    private fun qwenExportJson(): String {
        val arr = JSONArray()
        app.qwenAccounts.list().forEach { a ->
            arr.put(
                JSONObject()
                    .put("label", a.label)
                    .put("email", a.email ?: "")
                    .put("token", a.token)
                    .put("cookies", a.cookies)
                    .put("expiresAt", a.expiresAt)
            )
        }
        return arr.toString(2)
    }

    /** 控制台操作入口：一次 POST 一个动作，返回 {ok, message}。 */
    private fun adminAction(body: String): String {
        val req = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val action = req.optString("action")
        val pool = req.optString("pool", "ds")
        fun ok(m: String) = JSONObject().put("ok", true).put("message", m).toString()
        fun fail(m: String) = JSONObject().put("ok", false).put("message", m).toString()
        val qwenPool = pool == "qwen"
        val dsStore = app.accounts
        val qwStore = app.qwenAccounts

        return try {
            when (action) {
                "start_server" -> {
                    val port = req.optInt("port", app.settings.port)
                        .let { if (it in 1024..65535) it else app.settings.port }
                    if (req.has("key")) app.settings.apiKey = req.optString("key").trim()
                    val started = app.startServer(port, app.settings.apiKey.ifBlank { null }, app.settings.allowLan)
                    if (started) ok("服务已启动 · 端口 $port") else fail("启动失败：${app.apiServer?.lastError ?: "端口可能被占用"}")
                }

                "stop_server" -> {
                    app.stopServer()
                    ok("服务已停止")
                }

                "set_key" -> {
                    app.settings.apiKey = req.optString("key").trim()
                    ok(if (app.settings.apiKey.isBlank()) "已清空 API Key" else "API Key 已保存")
                }

                "toggle" -> {
                    val key = req.optString("key")
                    val v = req.optBoolean("value")
                    when (key) {
                        "deepThink" -> app.settings.deepThink = v
                        "antiRecall" -> app.settings.antiRecall = v
                        "devMode" -> app.settings.devMode = v
                        "allowLan" -> {
                            app.settings.allowLan = v
                            // 监听地址变了必须重开监听
                            if (app.apiServer?.isRunning == true) {
                                app.startServer(app.settings.port, app.settings.apiKey.ifBlank { null }, v)
                            }
                        }

                        "floatingWindow" -> {
                            if (v) {
                                if (android.provider.Settings.canDrawOverlays(app)) {
                                    app.settings.floatingWindow = true
                                    com.dslauncher.service.FloatingService.start(app)
                                } else {
                                    return fail("请先在 App 内授予「显示在其他应用上层」权限")
                                }
                            } else {
                                app.settings.floatingWindow = false
                                com.dslauncher.service.FloatingService.stop(app)
                            }
                        }

                        "autoRotateFingerprint" -> dsStore.autoRotateFingerprint = v
                        "qwenUseBrowser" -> app.settings.qwenUseBrowser = v
                        "qwenAutoRefresh" -> app.settings.qwenAutoRefresh = v
                        else -> return fail("未知开关：$key")
                    }
                    ok(if (v) "已开启" else "已关闭")
                }

                "set_qwen_model" -> {
                    val m = req.optString("value").trim()
                    if (m.isNotBlank()) app.settings.qwenModel = m
                    ok("模型已保存：${app.settings.qwenModel}")
                }

                "refresh_models" -> {
                    app.qwenRepo.refreshModels(force = true)
                    ok("已触发线上模型刷新（几秒后页面会自动更新）")
                }

                "set_qwen_lead" -> {
                    app.settings.qwenRefreshLeadMin = req.optInt("value", 5)
                    ok("提前续期：${app.settings.qwenRefreshLeadMin} 分钟")
                }

                "qwen_refresh_all" -> {
                    val out = runBlocking { app.qwenRepo.refresher.refreshDue(force = true) }
                    ok(out.summary)
                }

                "qwen_refresh_page" -> {
                    val r = runBlocking { app.qwenRepo.refresher.refreshPageSession() }
                    if (r) ok(app.qwenRepo.refresher.lastErrorText() ?: "页面会话已续期")
                    else fail("页面会话续期失败：需先在 App 内打开过 Qwen 浏览器登录页")
                }

                "tunnel_start" -> {
                    val started = app.tunnel.start(app.settings.port)
                    app.settings.tunnelEnabled = started
                    if (started) ok("已开启：${app.tunnel.publicUrl ?: "连接中…"}") else fail("开启失败：${app.tunnel.lastError ?: "未知"}")
                }

                "tunnel_stop" -> {
                    app.tunnel.stop()
                    app.settings.tunnelEnabled = false
                    ok("已停止内网穿透")
                }

                "set_mode" -> {
                    val m = runCatching { com.dslauncher.data.PickMode.valueOf(req.optString("value")) }
                        .getOrDefault(com.dslauncher.data.PickMode.ROUND_ROBIN)
                    if (qwenPool) qwStore.mode = m else dsStore.mode = m
                    ok("取号策略：" + if (m == com.dslauncher.data.PickMode.RANDOM) "随机" else "轮询")
                }

                "set_compact" -> {
                    val v = req.optBoolean("value", true)
                    if (qwenPool) qwStore.compactAccounts = v else dsStore.compactAccounts = v
                    ok(if (v) "列表：粗略" else "列表：详细")
                }

                "set_default_quota" -> {
                    val v = req.optInt("value", 0).coerceAtLeast(0)
                    if (qwenPool) qwStore.defaultQuota = v else dsStore.defaultQuota = v
                    ok("默认配额：${if (v == 0) "不限" else v}")
                }

                "set_cooldown" -> {
                    val min = req.optInt("value", 10).coerceIn(1, 1440)
                    val ms = min * 60_000L
                    if (qwenPool) qwStore.quotaCooldownMs = ms else dsStore.quotaCooldownMs = ms
                    ok("刷新时间：$min 分钟")
                }

                "quota_to_all" -> {
                    val v = req.optInt("value", 0).coerceAtLeast(0)
                    if (qwenPool) qwStore.applyQuotaToAll(v) else dsStore.applyQuotaToAll(v)
                    ok("配额已应用到全部账号（${if (v == 0) "不限" else v}）")
                }

                "reset_all_usage" -> {
                    if (qwenPool) qwStore.list().forEach { qwStore.resetUsage(it.id) }
                    else dsStore.list().forEach { dsStore.resetUsage(it.id) }
                    ok("已刷新全部账号配额")
                }

                "verify_all" -> {
                    if (!qwenPool) {
                        val r = runBlocking { app.webRepo.verifyAllStatuses() }
                        ok("校验完成：正常 ${r.ok} / 禁言 ${r.muted} / 失效 ${r.invalid}")
                    } else {
                        val renewed = runBlocking { app.qwenRepo.refresher.refreshDue(force = true) }.ok
                        var bad = 0
                        qwStore.list().forEach { acc ->
                            if (!acc.tokenInvalid && !acc.needRelogin) return@forEach
                            val valid = runBlocking {
                                runCatching { app.qwenClient.validateToken(acc.token) }.getOrDefault(false)
                            }
                            qwStore.setTokenInvalid(acc.id, !valid)
                            if (!valid) bad++
                        }
                        ok("续期完成：成功 $renewed / 仍失效 $bad")
                    }
                }

                "dsml_test" -> {
                    val text = req.optString("text")
                    val parsed = parseDsmlCalls(text)
                    val n = parsed.second?.length() ?: 0
                    ok(if (n > 0) "解析出 $n 个工具调用：" + (parsed.second?.toString() ?: "") else "没有检测到 DSML 工具调用")
                }

                "sweep" -> {
                    val a = dsStore.sweepExpired()
                    val b = qwStore.sweepExpired()
                    ok("已清理过期状态：DeepSeek $a 个 / Qwen $b 个")
                }

                "add_account" -> {
                    val text = req.optString("text")
                    if (text.isBlank()) return fail("请先粘贴 token 或 JSON")
                    if (qwenPool) {
                        val (added, updated) = importQwenTokens(text)
                        ok("导入完成：新增 $added · 更新 $updated")
                    } else {
                        val r = dsStore.importExport(text)
                        ok("导入完成：新增 ${r.added} · 更新 ${r.updated} · 无效 ${r.invalid}")
                    }
                }

                "acc" -> {
                    val op = req.optString("op")
                    val id = req.optString("id")
                    if (id.isBlank()) return fail("缺少账号 id")
                    if (qwenPool) {
                        val acc = qwStore.list().firstOrNull { it.id == id } ?: return fail("账号不存在")
                        when (op) {
                            "toggle" -> qwStore.setEnabled(id, !acc.enabled)
                            "del" -> qwStore.remove(id)
                            "setquota" -> qwStore.setQuota(id, req.optInt("value", 0).coerceAtLeast(0))
                            "reset" -> qwStore.resetUsage(id)
                            "verify" -> {
                                val renewed = runBlocking { app.qwenRepo.refresher.refresh(acc, force = true) }
                                if (renewed) ok("已自动续期：拿到新 Token（无需重新登录）")
                                else {
                                    val valid = runBlocking {
                                        runCatching { app.qwenClient.validateToken(acc.token) }.getOrDefault(false)
                                    }
                                    qwStore.setTokenInvalid(id, !valid)
                                    if (valid) ok("Token 仍然有效")
                                    else fail(app.qwenRepo.refresher.lastErrorText() ?: "Token 已失效：请重新登录一次以启用自动续期")
                                }
                            }

                            else -> return fail("未知账号操作：$op")
                        }
                        return ok("操作完成")
                    }
                    val acc = dsStore.list().firstOrNull { it.id == id } ?: return fail("账号不存在")
                    when (op) {
                        "toggle" -> dsStore.setEnabled(id, !acc.enabled)
                        "del" -> dsStore.remove(id)
                        "rotate" -> {
                            val fp = dsStore.rotateFingerprint(id)
                            return if (fp == null) fail("指纹更换失败") else ok("已更换设备指纹：…${fp.takeLast(8)}（不保证降低禁言概率）")
                        }

                        "setquota" -> dsStore.setQuota(id, req.optInt("value", 0).coerceAtLeast(0))
                        "reset" -> dsStore.resetUsage(id)
                        "verify" -> {
                            val r = runBlocking { app.webRepo.refreshIdentity(id, acc.token) }
                            return if (r.ok) ok(r.message) else fail(r.message)
                        }

                        else -> return fail("未知账号操作：$op")
                    }
                    ok("操作完成")
                }

                else -> fail("未知操作：$action")
            }
        } catch (e: Exception) {
            fail("执行失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 网页端导入 Qwen 账号：每行一个 token，或本 App 导出的 JSON 数组（带 cookies/expiresAt）。 */
    private fun importQwenTokens(text: String): Pair<Int, Int> {
        var added = 0
        var updated = 0
        val trimmed = text.trim()
        val entries = runCatching {
            val arr = JSONArray(trimmed)
            (0 until arr.length()).mapNotNull { i ->
                when (val o = arr.opt(i)) {
                    is String -> o.takeIf { it.isNotBlank() }?.let { Triple(it, "", 0L) }
                    is JSONObject -> o.optString("token").takeIf { it.isNotBlank() }
                        ?.let { Triple(it, o.optString("cookies", ""), o.optLong("expiresAt", 0L)) }

                    else -> null
                }
            }
        }.getOrElse { trimmed.lines().map { Triple(it.trim(), "", 0L) } }

        for ((tk, ck, exp) in entries) {
            if (tk.isBlank() || tk.length < 8) continue
            val existed = app.qwenAccounts.list().any { it.token == tk }
            app.qwenAccounts.add(tk, cookies = ck, expiresAt = exp)
            if (existed) updated++ else added++
        }
        return added to updated
    }

    /** `POST /debug/dsml`：{text} → {calls:[…], text:"清洗后的正文"}（也可从网页控制台调用）。 */
    private fun dsmlDebugJson(body: String): String {
        val req = runCatching { JSONObject(body.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val text = req.optString("text", req.optString("input", ""))
        val parsed = parseDsmlCalls(text)
        return JSONObject()
            .put("input_length", text.length)
            .put("calls", parsed.second ?: JSONArray())
            .put("count", parsed.second?.length() ?: 0)
            .put("text", parsed.first)
            .toString()
    }

    private fun writeHtml(out: BufferedOutputStream, html: String) {
        writeRaw(out, 200, html, "text/html; charset=utf-8")
    }

    private fun writeRaw(out: BufferedOutputStream, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else if (code == 204) "No Content" else "Error"}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }

    private fun writeJson(out: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-chat"
        /** 网页控制台展示用：版本与作者署名。 */
        const val APP_VERSION = "1.03"
        const val AUTHOR_NAME = "Gnuning"
        /** 混合模型名：请求体 model 用它时，从双池可用账号中随机抽一个服务本次请求。 */
        const val MIXED_MODEL = "mixed"
        // 兜底会话 id：请求体里既没有会话字段、也没有 system/首条 user 消息时才用它。
        // 注意：**不再是所有请求共用的固定会话**（那会导致「新对话接着上一个窗口」）。
        const val DEFAULT_CONV_ID = 999L
        val MODEL_IDS = listOf(
            "deepseek-chat",
            "deepseek-reasoner"
        )
    }
}
