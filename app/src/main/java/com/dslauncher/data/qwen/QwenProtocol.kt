package com.dslauncher.data.qwen

import org.json.JSONObject
import java.io.IOException

/**
 * Qwen 网页端协议层：帧解析 + 错误识别。
 *
 * 关键点：**两个传输层（OkHttp 直连 / WebView 真浏览器栈）必须共用同一套解析**，
 * 否则「换通道后行为不一致」会变成新的排查噩梦。
 */

/** 官方前端 URL：`/api/v2/chat/completions?chat_id=<id>`；chat_id 同时放在 body。 */
internal fun qwenChatUrl(chatId: String): String =
    QwenWebClient.CHAT_COMPLETIONS + "?chat_id=" + java.net.URLEncoder.encode(chatId, "UTF-8")

/**
 * 网页端对话请求体 —— 逐字段对齐官方前端 `ChatSender.sendPromptOpenAI`：
 *
 * ```
 * {stream, version:"2.1", incremental_output, chatId, parentId, chat_id,
 *  chat_mode:"normal", model, parent_id, messages:[网页消息对象], timestamp}
 * ```
 *
 * `messages[i]` 走 `ni.createUserMessage`：
 * ```
 * {id:null, fid, parentId, childrenIds:[], role:"user", content, user_action:"chat",
 *  timestamp, models:[model], model:"", chat_type:"t2t",
 *  feature_config:{thinking_enabled, output_schema:"phase", research_mode, auto_search},
 *  sub_chat_type:"t2t"}
 * ```
 * `output_schema:"phase"` 是让上游在 delta 里回传 `phase`（think/answer）的开关。
 *
 * @param slim 400 回退：去掉非必需字段（research_mode / auto_search）重试一次
 */
internal fun qwenChatBody(
    model: String,
    prompt: String,
    cid: String,
    parentId: String?,
    thinkingEnabled: Boolean,
    contextTail: String?,
    slim: Boolean
): JSONObject {
    var content = prompt
    if (!contextTail.isNullOrBlank()) {
        // 网页端无文件上传通道：历史以「尾部摘录」拼进本轮消息兜底，
        // 常规连续性由 chat_id + parent_id 会话链维持。
        content = prompt + "\n\n【此前对话尾部记录，供参考】\n" + contextTail
    }
    val parent = parentId?.takeIf { it.isNotBlank() }
    val nowSec = System.currentTimeMillis() / 1000

    val featureConfig = JSONObject()
        .put("thinking_enabled", thinkingEnabled)
        .put("output_schema", "phase")
    if (!slim) {
        featureConfig.put("research_mode", "normal")
        // 不自动联网检索：检索失败/无结果时只回流元信息，也是「空回复」来源之一。
        featureConfig.put("auto_search", false)
    }

    val msg = JSONObject()
        .put("id", JSONObject.NULL)
        .put("fid", java.util.UUID.randomUUID().toString())
        .put("parentId", parent ?: JSONObject.NULL)
        .put("childrenIds", org.json.JSONArray())
        .put("role", "user")
        .put("content", content)
        .put("user_action", "chat")
        .put("timestamp", nowSec)
        .put("models", org.json.JSONArray().put(model))
        .put("model", "")
        .put("chat_type", "t2t")
        .put("feature_config", featureConfig)
        .put("sub_chat_type", "t2t")

    return JSONObject()
        .put("stream", true)
        .put("version", QwenWebClient.COMPLETION_VERSION)
        .put("incremental_output", true)
        .put("chat_id", cid)
        .put("chatId", cid)
        .put("parent_id", parent ?: JSONObject.NULL)
        .put("parentId", parent ?: "")
        .put("chat_mode", "normal")
        .put("model", model)
        .put("messages", org.json.JSONArray().put(msg))
        .put("timestamp", nowSec)
}

/** delta.phase 里属于「思考/推理」的取值；其余一律计入正文，避免丢内容。 */
internal val QWEN_REASONING_PHASES = setOf(    "think", "thinking_summary", "deepthinking",
    "researchplanning", "reasoning", "reportgeneration", "pdfmdgen"
)

/** 上游工具调用可能出现的键名（数组或单对象都算）。 */
internal val TOOL_KEYS = arrayOf("tool_calls", "tool_use", "tool_call", "function_call")

/**
 * 最近一条流的原始帧（诊断用，最多留 300 帧）。
 *
 * 「工具调用只剩思考」这类问题靠猜键名已经试错太多次了 ——
 * 有了它就能直接看上游到底发了什么，而不是继续赌。
 */
internal val lastStreamFrames = ArrayDeque<String>()

internal fun recordStreamFrame(p: String) {
    if (lastStreamFrames.size >= 300) lastStreamFrames.removeFirst()
    lastStreamFrames.addLast(p)
}

/**
 * Qwen v2 信封里的 `error.code` → 可读文案。
 * 文案里保留 code 原文，供仓储层判定「token 失效 / 限流」。
 */
internal fun qwenErrorText(codeIn: String, detailIn: String, raw: String): String {
    val code = codeIn.ifBlank { "unknown" }
    val detail = detailIn.take(200)
    val head = when (code.lowercase()) {
        "ratelimited", "parallellimited", "too_many_requests" -> "Qwen 上游限流"
        "quotalimited" -> "Qwen 账号配额用尽"
        "unauthorized", "invalid token", "forbidden" -> "Qwen 登录态已失效（$code），请重新登录该账号"
        "data_inspection_failed" -> "Qwen 内容安全拦截（$code）"
        "waf_blocked" -> "Qwen 被风控拦截（WAF_BLOCKED）"
        "expert_busy", "server busy" -> "Qwen 上游繁忙（$code）"
        // 会话链失效：本地存的 chat_id/parent_id 在上游已不存在（对话被删/过期）。
        // 仓储层看到这个码会自动清链并用新会话重试一次。
        "chat_not_found", "conversation_not_found", "not_found" -> "会话已失效（$code）"
        else -> "Qwen 上游错误（$code）"
    }
    return if (detail.isNotBlank()) "$head：$detail" else "$head：${raw.take(200)}"
}

/**
 * 识别「不是正常业务响应」的上游返回。返回 null = 不匹配（按普通 HTTP 错误处理）。
 *
 * 实测阿里云 WAF 对本通道的接管形态：
 *   1. 验证码风控：`{"ret":["FAIL_SYS_USER_VALIDATE","RGV587_ERROR::SM::哎哟喂,被挤爆啦..."]}`
 *      或 `text/html` 里 `.../_____tmd_____/punish?x5secdata=...` 的跳转脚本；
 *   2. 滑块验证页：`<meta name="aliyun_waf_aa" ...>` + `AliyunCaptcha.js`；
 *   3. 路径级阻断页：「很抱歉，由于您访问的URL有可能对网站造成安全威胁，您的访问被阻断」；
 *   4. v2 统一信封 `{"success":false,"data":{"code":..,"details":..}}`。
 */
internal fun classifyQwenBlock(code: Int, contentType: String, raw: String): IOException? {
    val low = raw.lowercase()
    if (raw.contains("FAIL_SYS_USER_VALIDATE") || raw.contains("RGV587_ERROR") ||
        low.contains("x5secdata") || low.contains("x5sectag") ||
        low.contains("_____tmd_____") || low.contains("punish")
    ) {
        return IOException(
            "Qwen 被风控拦截（HTTP $code，要求人机验证）。" +
                "当前请求指纹被阿里云 WAF 判成了爬虫：请改用「内置浏览器通道」" +
                "（Qwen 账号页 → 浏览器登录），或切换网络后重试"
        )
    }
    if (low.contains("aliyun_waf_aa") || low.contains("aliyuncaptcha") ||
        low.contains("访问被阻断") || low.contains("has been blocked")
    ) {
        return IOException(
            "Qwen 被阿里云 WAF 阻断（HTTP $code）：当前客户端指纹不被接受。" +
                "请在 Qwen 账号页用「内置浏览器通道」登录并过一次滑块"
        )
    }
    if (low.contains("\"success\":false") || low.contains("\"success\": false")) {
        val data = runCatching { JSONObject(raw) }.getOrNull()?.optJSONObject("data")
        val ec = data?.optString("code").orEmpty()
        val detail = data?.optString("details").orEmpty().ifBlank { data?.optString("message").orEmpty() }
        return IOException(qwenErrorText(ec, detail, raw))
    }
    if (contentType.contains("text/html", ignoreCase = true) ||
        low.trimStart().startsWith("<!doctype") || low.trimStart().startsWith("<html")
    ) {
        return IOException("Qwen 上游返回 HTML 页面（疑似风控/登录页跳转，HTTP $code）：${raw.take(200)}")
    }
    return null
}

/**
 * Qwen SSE 帧解析器（官方前端 qwen-chat-fe 0.3.12 语义）。
 *
 * ```
 * {"choices":[{"delta":{"role":..,"content":..,"phase":"think|answer|..","status":..}}],
 *  "response.created":{"chat_id":..,"parent_id":..,"response_id":..},
 *  "response_id":..,"usage":..}
 * {"error":{"code":..,"details":..}}
 * data: [DONE]
 * ```
 * `phase` 在 **delta 内部**；会话链 id 在 `response.created`；错误在 `error.code`。
 */
class QwenStreamParser(
    private val onChatId: (String) -> Unit = {},
    private val onResponseId: (String?) -> Unit = {},
    private val onThinking: (String) -> Unit = {}
) {
    /** 思考片段计数（参与「整条流是否真的产出过内容」的判定）。 */
    var thinks = 0
        private set

    /** 正文片段计数。 */
    var answers = 0
        private set

    /** 是否见到过任意可解析帧。 */
    var sawFrame = false
        private set

    /** 是否收到 [DONE] / response.stopped / response.completed。 */
    var stopped = false
        private set

    /** 最近一帧原文（诊断用）。 */
    var lastRaw = ""
        private set

    /**
     * 吃一帧 `data:` 负载，返回本次要吐给客户端的正文片段（可能为空）。
     * 业务错误帧直接抛 IOException。
     */
    fun feed(payloadIn: String): List<String> {
        recordStreamFrame(payloadIn)
        val payload = payloadIn.trim()
        if (payload.isEmpty()) return emptyList()
        if (payload == "[DONE]") {
            stopped = true
            return emptyList()
        }
        lastRaw = payload
        QwenWebClient.lastUpstreamRaw = payload
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return emptyList()
        sawFrame = true

        // 1) 会话链：response.created
        json.optJSONObject("response.created")?.let { c ->
            c.optString("chat_id").takeIf { it.isNotBlank() }?.let { onChatId(it) }
            c.optString("response_id").takeIf { it.isNotBlank() }?.let { onResponseId(it) }
        }
        // response.completed / status=finished 一律**不作为截流信号**。
        // 上游在「思考段结束」时就会推这两个，工具轮里正文往往只吐了一句，
        // 一旦据此收尾，后面的工具调用帧就全丢了（表现为「思考完调工具 → 只有思考」）。
        // 真正的收尾交给连接正常结束 / [DONE]；只有明确 stop 才提前收。
        if (json.has("response.stopped")) {
            stopped = true
            return emptyList()
        }

        // 2) 业务错误帧 {"error":{"code":..,"details":..}}
        json.optJSONObject("error")?.let { e ->
            val ec = e.optString("code")
            if (ec.isNotBlank()) {
                throw IOException(
                    qwenErrorText(
                        ec,
                        e.optString("details").ifBlank { e.optString("message") },
                        payload
                    )
                )
            }
        }
        if (!json.isNull("error")) {
            val s = json.optString("error")
            if (s.isNotBlank()) throw IOException("Qwen 上游错误：${s.take(200)}")
        }

        // 3) 顶层 response_id（下一轮的 parent）
        json.optString("response_id").takeIf { it.isNotBlank() }?.let { onResponseId(it) }

        val out = ArrayList<String>(1)

        // 4) 官方 0.3.12 形态：choices[0].delta.{content,phase}
        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        if (delta != null) {
            val reasoning = delta.optString("reasoning_content")
            if (reasoning.isNotEmpty()) {
                onThinking(reasoning); thinks++
            }
            // 正文可能挂 content，也可能挂 delta_content / text（旧版网页帧）。
            // 注意：本节是提前 return 的，之前只在**顶层**查 delta_content，
            // 于是 {"choices":[{"delta":{"delta_content":"..."}}]} 这种帧被整条丢掉
            // —— 表现正是「有思考、没正文、也不报错」。
            val ph = delta.optString("phase")
            val content = delta.optString("content").ifEmpty {
                delta.optString("delta_content").ifEmpty { delta.optString("text") }
            }
            if (content.isNotEmpty()) route(ph, content, out)

            // （不再做原生工具调用归一化：Qwen 只走提示词协议，正文原样透传。）
            // 同理：status=finished 也可能是「思考段结束」，一律不收流
            return out
        }

        // 5) 兼容旧版网页帧：顶层 phase + delta_content
        if (json.has("phase") || json.has("delta_content")) {
            val dc = json.optString("delta_content")
            if (dc.isNotEmpty()) route(json.optString("phase"), dc, out)
            json.optString("response_id").takeIf { it.isNotBlank() }?.let { onResponseId(it) }
        }
        return out
    }

    /**
     * 把各种形态的工具调用归一化成 `<tool_call>{json}</tool_call>` 文本，
     * 交给 LocalApiServer 现有的 extractToolCalls 统一抽取。
     *
     * 上游在不同阶段/版本用过三个键名：tool_calls / tool_use / function_call，
     * 值可能是数组也可能是单对象，名称与参数可能包在 function / tool / call 层里。
     * 旧实现只认 delta.tool_calls，所以「思考完调工具」那一轮仍然只剩思考。
     */
    private fun appendToolCalls(src: JSONObject?, out: MutableList<String>): Int {
        if (src == null) return 0
        var n = 0
        for (key in TOOL_KEYS) {
            val items = ArrayList<JSONObject>()
            src.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
            }
            if (items.isEmpty()) src.optJSONObject(key)?.let { items.add(it) }
            if (items.isEmpty()) continue
            for (it in items) {
                val fn = it.optJSONObject("function")
                    ?: it.optJSONObject("tool")
                    ?: it.optJSONObject("call")
                    ?: it
                val name = fn.optString("name").ifBlank { it.optString("name") }
                if (name.isBlank()) continue
                val args = fn.optString("arguments").ifBlank {
                    fn.optString("input").ifBlank { it.optString("input") }
                }.ifBlank { "{}" }
                out.add("<tool_call>\n{\"name\":\"$name\",\"arguments\":$args}\n</tool_call>")
                n++
            }
        }
        return n
    }

    private fun route(phase: String, content: String, out: MutableList<String>) {        if (phase.lowercase() in QWEN_REASONING_PHASES) {
            onThinking(content); thinks++
        } else {
            out.add(content); answers++
        }
    }

    /**
     * 流结束后调用：整条流一个字都没产出就抛错，
     * 不再像旧实现那样静默返回空白（「返回空气」的直接成因）。
     */
    fun assertProduced() {
        if (thinks > 0 || answers > 0) return
        val hint = when {
            !sawFrame -> "上游没有返回任何 SSE 帧（通常是被风控拦截或登录态失效）"
            stopped -> "上游在产出任何内容前就结束了（会话链可能已失效，下轮将自动重建）"
            else -> "上游只回传了元信息帧，没有正文"
        }
        throw IOException("Qwen 空回复：$hint。原始末帧：${lastRaw.take(300)}")
    }
}
