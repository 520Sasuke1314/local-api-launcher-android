package com.dslauncher.data.remote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 官网（chat.deepseek.com）网页版 API 客户端。
 * 协议逆向参考：github.com/CJackHwang/ds2api
 * 注意：使用官网接口有封号风险，仅建议小号使用。
 */
class DeepSeekWebClient(private val okHttpClient: OkHttpClient) {

    private val gson = com.google.gson.Gson()
    private val jsonMedia = "application/json".toMediaTypeOrNull()
    // fragment 索引提取：response/fragments/-1 或 fragments/0 等形式
    private val FRAG_IDX_RE = Regex("(?:response/)?fragments/(-?\\d+)(?:/.*)?")
    // 流式读取专用 client：空闲读超时 300s。
    //
    // 之前是 60s —— 对思考模型（deepseek-reasoner）来说太短：它深度思考时
    // 可能长时间一个字节都不吐，60 秒一到就 SocketTimeoutException，
    // 整轮被掐断。这是「老是中断」的第一根因。提到 300s 后仍保留兜底
    // （接口彻底失联时不会永久挂起），但正常的长思考不会再被误杀。
    private val streamingClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        // 流式必须关掉 OkHttp 的连接级缓冲，否则响应体会被攒到一定量才交给上层，
        // 表现就是「流式但不出字 / 憋一批才吐」。
        .retryOnConnectionFailure(true)
        // 禁用 gzip 协商：部分中转/官网在 gzip 下会整块压缩再吐，破坏逐帧语义
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Accept-Encoding", "identity")
                .build()
            chain.proceed(req)
        }
        .build()

    /** 官网业务错误（HTTP 200，但 data.biz_code != 0），如 biz_code=26 invalid message id。 */
    class WebBizException(val bizCode: Int, val bizMsg: String) :
        IOException("官网业务错误 biz_code=$bizCode: $bizMsg")

    companion object {
        val BASE = "https://chat.deepseek.com/api/v0"
        val LOGIN = "https://chat.deepseek.com/api/v0/users/login"
        val CREATE_SESSION = "https://chat.deepseek.com/api/v0/chat_session/create"
        val CREATE_POW = "https://chat.deepseek.com/api/v0/chat/create_pow_challenge"
        val COMPLETION = "https://chat.deepseek.com/api/v0/chat/completion"
        val CONTINUE = "https://chat.deepseek.com/api/v0/chat/continue"
        val REGENERATE = "https://chat.deepseek.com/api/v0/chat/regenerate"
        val EDIT_MESSAGE = "https://chat.deepseek.com/api/v0/chat/edit_message"
        // 注意：**不是** $BASE/upload_file —— 那条路径不存在，
        // 会被 SPA 的通配路由兜成一段 HTML（实测返回 <!doctype html>…<title>DeepSeek</title>），
        // 于是解析 JSON 必然失败，表现就是「文件/图片一直上传失败」。
        // 正确路径多一层 file/：实测返回 {"code":40003,"msg":"INVALID_TOKEN"}，说明端点存在。
        val UPLOAD_FILE = "https://chat.deepseek.com/api/v0/file/upload_file"

        /** 伪装用的官网 App 版本号。改这里即可整体同步 UA 与 x-client-version，避免两处不一致被服务端识别 */
        // 官网接口对客户端版本有校验。之前把它从 2.0.3 改成 2.41 之后，官网免费对话开始报
        // 「client version too low」—— 说明服务端并不认这个版本号（大概率只对已知版本放行，
        // 认不出来的一律按「过低」拒绝）。这里先退回改版前实测可用的 2.0.3。
        const val APP_VERSION = "2.0.3"

        val DEFAULT_HEADERS = mapOf(
            // 注意：不能设置 Host 头，OkHttp 禁止手动覆盖（会抛异常）
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "accept-charset" to "UTF-8",
            "User-Agent" to "DeepSeek/$APP_VERSION Android/35",
            "x-client-platform" to "android",
            "x-client-version" to APP_VERSION,
            "x-client-locale" to "zh_CN"
        )

        /** 最近一次上游原始返回（诊断用，供本地 API 的 /debug/last-upstream 查看）。 */
        @Volatile
        var lastUpstreamRaw: String = ""
    }

    /** 从上游原始返回里解析业务错误（biz_code != 0）。 */
    private fun parseBizError(raw: String): Pair<Int, String>? = runCatching {
        val o = JSONObject(raw)
        val d = o.optJSONObject("data") ?: return@runCatching null
        val code = d.optInt("biz_code", 0)
        if (code == 0) return@runCatching null
        code to d.optString("biz_msg")
    }.getOrNull()

    /**
     * 账号密码登录，返回 token。
     *
     * @param deviceId 登录体里的设备指纹。调用方（仓储层）会把**该账号已绑定/轮换后的**
     * 那一枚传进来，保证「账号页显示的 = 真正发出去的」；null = 这里临时随机取一枚未占用指纹。
     * 注意更换指纹不保证降低被禁言概率（见 [com.dslauncher.data.DeviceFingerprints] 的说明）。
     */
    suspend fun login(email: String, password: String, deviceId: String? = null): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("email", email.trim())
                .put("password", password)
                .put("device_id", deviceId ?: com.dslauncher.data.DeviceFingerprints.newFingerprint())
                .put("os", "android")
                .toString()
        val resp = post(LOGIN, body, null)
        val json = JSONObject(resp)
        if (json.optInt("code") != 0) throw IOException("登录失败: ${json.optString("msg")}")
        val data = json.optJSONObject("data") ?: throw IOException("登录失败: 响应格式错误")
        if (data.optInt("biz_code") != 0) throw IOException("登录失败: ${data.optString("biz_msg")}")
        val bizData = data.optJSONObject("biz_data") ?: throw IOException("登录失败: 无数据")
        val user = bizData.optJSONObject("user") ?: throw IOException("登录失败: 无用户")
        val token = user.optString("token")
        if (token.isBlank()) throw IOException("登录失败: 未返回 token")
        token
    }

    /** 用户信息（账号列表显示 + 状态校验用）。 */
    data class UserInfo(
        val email: String?,
        val phone: String?,
        val name: String?,
        /** 是否被禁言（chat.is_muted / user_is_muted） */
        val muted: Boolean = false,
        /** 禁言结束时间戳（毫秒）；null = 未知/永久 */
        val muteUntil: Long? = null
    ) {
        /** 有没有拿到任何有效信息。 */
        val hasAny: Boolean get() = email != null || phone != null || name != null || muted
    }

    /**
     * 拉取当前用户信息（email / 手机号 / 禁言状态）。
     *
     * 端点只有 `/users/current` 是真实存在的（2026-10-06 实测：无 token 时返回
     * `{"code":40002,"msg":"Missing Token"}`；`/users/info`、`/users/me` 等
     * 相邻路径都会被 SPA 兜底路由包成 HTML，是假端点，不再尝试）。
     *
     * 端点可达但内容不符合预期时抛 [IOException]，消息里带原始特征（HTTP 码 /
     * biz code / 缺哪个字段），调用方（账号页刷新）会把原因直接展示给用户；
     * 原始响应同时记录在 [lastUserInfoRaw] 供诊断。
     */
    suspend fun fetchUserInfo(token: String): UserInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$BASE/users/current").get().apply {
            for ((k, v) in DEFAULT_HEADERS) header(k, v)
            header("authorization", "Bearer $token")
        }.build()
        okHttpClient.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            lastUserInfoRaw = raw
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            parseUser(raw)
        }
    }

    /**
     * 校验账号状态：禁言 / 封禁。
     * 依据 上游实现 的字段：chat.is_muted、user_is_muted、biz_data.mute_until。
     */
    suspend fun checkAccountStatus(token: String): UserInfo = fetchUserInfo(token)

    /** 最近一次 /users/current 的原始返回（诊断用；与对话链路的 [lastUpstreamRaw] 分开）。 */
    @Volatile
    var lastUserInfoRaw: String = ""
        private set

    /**
     * 解析 /users/current 响应。
     *
     * 已知返回形态 `data.biz_data.user`；这里同时兼容 `data.user` 与
     * `data` 直接挂字段两种变体，字段名对齐 mobile_number / phone_number / phone。
     * `code != 0` 或找不到用户对象时抛 [IOException]（携带可读原因），不静默回 null。
     */
    private fun parseUser(raw: String): UserInfo {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        if (code != 0) {
            throw IOException("官网返回 code=$code ${root.optString("msg")}")
        }
        val data = root.optJSONObject("data")
            ?: throw IOException("响应缺少 data 字段（疑似非 JSON 接口）")
        val biz = data.optJSONObject("biz_data") ?: data
        val user = biz.optJSONObject("user")
            ?: data.optJSONObject("user")
            ?: biz.takeIf { it.has("email") || it.has("mobile_number") || it.has("phone_number") }
            ?: throw IOException("响应缺少 user 字段")
        val chat = user.optJSONObject("chat") ?: biz.optJSONObject("chat")
        val muted = chat?.optBoolean("is_muted", false) == true ||
            user.optBoolean("is_muted", false) ||
            biz.optBoolean("user_is_muted", false)
        // mute_until 单位不固定：小于 1e12 视为「秒」，否则视为「毫秒」
        val rawMute = when {
            biz.has("mute_until") && !biz.isNull("mute_until") -> biz.optLong("mute_until", 0L)
            chat?.has("mute_until") == true && !chat.isNull("mute_until") -> chat.optLong("mute_until", 0L)
            else -> 0L
        }
        val muteUntil = if (rawMute <= 0L) null
        else if (rawMute < 1_000_000_000_000L) rawMute * 1000 else rawMute
        // 禁言到期即失效：不能因为历史 mute_until 就把号永久判成禁言
        val now = System.currentTimeMillis()
        val muteActive = muted || (muteUntil != null && muteUntil > now)
        return UserInfo(
            email = user.optString("email").takeIf { it.isNotBlank() },
            phone = (user.optString("mobile_number").takeIf { it.isNotBlank() }
                ?: user.optString("phone_number").takeIf { it.isNotBlank() }
                ?: user.optString("phone").takeIf { it.isNotBlank() }),
            name = (user.optString("name").takeIf { it.isNotBlank() }
                ?: user.optString("nickname").takeIf { it.isNotBlank() }),
            muted = muteActive,
            muteUntil = if (muteActive) muteUntil else null
        )
    }

    /** 创建会话，返回 session_id */
    suspend fun createSession(token: String): String = withContext(Dispatchers.IO) {
        val resp = post(CREATE_SESSION, "{\"agent\":\"chat\"}", token)
        val json = JSONObject(resp)
        checkBiz(json, "创建会话失败")
        val bizData = json.getJSONObject("data").optJSONObject("biz_data")
        val id = bizData?.optString("id")
        if (!id.isNullOrBlank()) return@withContext id
        val chatSession = bizData?.optJSONObject("chat_session")
        val sid = chatSession?.optString("id")
        if (!sid.isNullOrBlank()) return@withContext sid
        throw IOException("创建会话失败: 未返回会话 ID")
    }

    /** 获取 PoW 挑战并求解，返回 x-ds-pow-response 头值 */
    suspend fun solvePow(token: String, targetPath: String = "/api/v0/chat/completion"): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("target_path", targetPath).toString()
        val resp = post(CREATE_POW, body, token)
        val json = JSONObject(resp)
        checkBiz(json, "获取 PoW 失败")
        val bizData = json.getJSONObject("data").optJSONObject("biz_data")
        val challenge = bizData?.optJSONObject("challenge")
            ?: throw IOException("获取 PoW 失败: 无 challenge")
        val algorithm = challenge.optString("algorithm")
        if (algorithm != "DeepSeekHashV1") throw IOException("不支持的 PoW 算法: $algorithm")
        val challengeStr = challenge.optString("challenge")
        val salt = challenge.optString("salt")
        val expireAt = challenge.optLong("expire_at", 1680000000)
        val difficulty = challenge.optLong("difficulty", 144000)
        val signature = challenge.optString("signature")
        val target = challenge.optString("target_path")

        val answer = DeepSeekPow.solve(challengeStr, salt, expireAt, difficulty)
        val headerJson = JSONObject()
            .put("algorithm", algorithm)
            .put("challenge", challengeStr)
            .put("salt", salt)
            .put("answer", answer)
            .put("signature", signature)
            .put("target_path", target)
            .toString()
        android.util.Base64.encodeToString(headerJson.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /**
     * 流式对话。返回的 Flow 逐段发出正文文本。
     * @param parentMessageId 上一条回复的 message_id（用于同一会话内连贯上下文），null = 新话题
     * @param searchProvider 搜索引擎（bing/sogou/360/baidu/google/duckduckgo 等，官网可能忽略）
     * @param onMessageId 回调返回本次回复的 message_id（供下一条消息作 parent）
     * @param onFinished 结束回调，参数为是否风控
     */
    suspend fun chatStream(
        token: String,
        sessionId: String,
        prompt: String,
        thinkingEnabled: Boolean = true,
        searchEnabled: Boolean = false,
        searchProvider: String? = null,
        parentMessageId: Long? = null,
        pow: String? = null,
        refFileIds: List<String> = emptyList(),
        onMessageId: (Long?) -> Unit = {},
        onRequestMessageId: (Long?) -> Unit = {},
        onThinking: (String) -> Unit = {},
        onSearchResults: (List<Pair<String, String>>) -> Unit = {},
        onFinished: (contentFilter: Boolean) -> Unit = {}
    ): Flow<String> = flow {
        val solvedPow = pow ?: solvePow(token)

        // 新版协议：除 thinking_enabled 外同时下发 thinking:{"type":...}。
        // 官网对未知字段会 400（如 search_provider），故首次带新字段请求，
        // 若 400 则回退为仅旧字段，保证兼容。
        var body = completionBody(sessionId, parentMessageId, prompt, thinkingEnabled, searchEnabled, includeThinking = true, refFileIds = refFileIds, searchProvider = searchProvider)
        var bodyStr = body.toString()
        var req = buildRequest(COMPLETION, bodyStr, token, solvedPow)
        var response = withContext(Dispatchers.IO) {
            streamingClient.newCall(req).execute()
        }
        if (!response.isSuccessful && response.code == 400) {
            response.close()
            // 400 重试：同时去掉可能不被识别的 thinking 对象与 search_provider，
            // 并重新求解 PoW —— 它是按请求签发的一次性凭证，复用同一个会继续 400。
            body = completionBody(sessionId, parentMessageId, prompt, thinkingEnabled, searchEnabled, includeThinking = false, refFileIds = refFileIds, searchProvider = null)
            bodyStr = body.toString()
            val retryPow = runCatching { solvePow(token) }.getOrElse { solvedPow }
            req = buildRequest(COMPLETION, bodyStr, token, retryPow)
            response = withContext(Dispatchers.IO) {
                streamingClient.newCall(req).execute()
            }
        }
        if (!response.isSuccessful) {
            throw IOException("API error: ${response.code} ${response.message}")
        }
        emitAll(parseWebStream(response, thinkingEnabled, onMessageId, onRequestMessageId, onThinking, onSearchResults, onFinished))
    }

    /** 官网对话请求体：新版 thinking 对象可选（部分旧协议接口不识别该字段） */
    private fun completionBody(
        sessionId: String,
        parentMessageId: Long?,
        prompt: String,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        includeThinking: Boolean,
        refFileIds: List<String> = emptyList(),
        searchProvider: String? = null
    ): JSONObject {
        val b = JSONObject()
            .put("chat_session_id", sessionId)
            .put("model_type", "default")
            .put("parent_message_id", parentMessageId ?: JSONObject.NULL)
            .put("prompt", prompt)
            .put("ref_file_ids", org.json.JSONArray(refFileIds))
            .put("thinking_enabled", thinkingEnabled)
            .put("search_enabled", searchEnabled)
        // search_provider 属于「新字段」，部分接口会 400；只在首选请求里带，重试时去掉。
        if (searchProvider != null) b.put("search_provider", searchProvider)
        if (includeThinking) {
            b.put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
        }
        return b
    }

    /**
     * 上传图片到官网文件接口，返回 ref_file_id（随 completion 请求的 ref_file_ids 下发）。
     * @param imageDataUrl data:image/xxx;base64,... 格式
     */
    suspend fun uploadFile(token: String, imageDataUrl: String): String {
        val comma = imageDataUrl.indexOf(',')
        val header = if (comma > 0) imageDataUrl.substring(0, comma) else ""
        val mime = header.removePrefix("data:").substringBefore(";")
        val b64 = if (comma >= 0) imageDataUrl.substring(comma + 1) else imageDataUrl
        val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        if (bytes.isEmpty()) throw IOException("图片数据为空")
        val ext = mime.substringAfter("/", "jpg").ifBlank { "jpg" }
        return uploadBinary(token, "image.$ext", mime.ifBlank { "image/jpeg" }, bytes).first
    }

    /**
     * 上传一个纯文本文件到官网文件接口，返回 ref_file_id。
     *
     * 用途：把「当前对话的完整上下文」转成 txt 一起发过去。
     *
     * 为什么需要它：官网通道的上下文靠 parent_message_id 链维持，这条链很脆 ——
     * 工具调用几轮之后 server 就可能丢掉前面的内容（表现就是「AI 失忆」、
     * 「执行两条命令就停了」）。每轮附带一份完整上下文 txt，等于绕开这条链，
     * 让模型无论如何都看得到全部历史。
     */
    suspend fun uploadTextFile(token: String, fileName: String, content: String): String {
        if (content.isBlank()) throw IOException("上下文为空")
        val bytes = content.toByteArray(Charsets.UTF_8)
        return uploadBinary(token, fileName, "text/plain; charset=utf-8", bytes).first
    }

    /**
     * 上传二进制到官网文件接口（图片 / 上下文 txt 共用），返回 (file_id, 状态)。
     *
     * 与 ds2api 逆向实现逐项对齐（「上下文上传还是不行」就栽在这几处）：
     *  1) 必须带 x-file-size（文件字节数）与 x-thinking-enabled: 1，缺了会被服务端拒；
     *  2) multipart 的 Content-Type 必须由 OkHttp 自动生成，不能套 DEFAULT_HEADERS 的 application/json；
     *  3) PoW 的 target_path 必须指向 /api/v0/file/upload_file；
     *  4) 文件 id 藏在 data.biz_data.file.file_id —— 旧实现只看 data.id / data.file_id，
     *     导致上传成功也报「响应里没有文件 id」。
     */
    private suspend fun uploadBinary(token: String, fileName: String, contentType: String, bytes: ByteArray): Pair<String, String?> {
        val media = contentType.toMediaTypeOrNull()
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(media))
            .build()
        val builder = Request.Builder().url(UPLOAD_FILE).post(body)
        // 关键：**不要**套用 DEFAULT_HEADERS 里的 Content-Type: application/json。
        // 它会覆盖 OkHttp 为 multipart 自动生成的
        // `multipart/form-data; boundary=...`，服务端解析不出表单 ——
        // 返回的正是那句 "missing header"。
        for ((k, v) in DEFAULT_HEADERS) {
            if (!k.equals("Content-Type", ignoreCase = true)) builder.header(k, v)
        }
        builder.header("authorization", "Bearer $token")
        builder.header("x-file-size", bytes.size.toString())
        builder.header("x-thinking-enabled", "1")
        // PoW 的 target_path 必须指向**上传端点本身**（/api/v0/file/upload_file）。
        // 指向别处时服务端签不出该端点的 PoW，upload 会被直接拒绝。
        builder.header("x-ds-pow-response", solvePow(token, "/api/v0/file/upload_file"))
        val response = withContext(Dispatchers.IO) { okHttpClient.newCall(builder.build()).execute() }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} ${resp.message}（原始返回：${text.take(300)}）")
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IOException("响应不是 JSON（原始返回：${text.take(300)}）")
            }
            if (json.optInt("code") != 0) {
                throw IOException(
                    json.optString("msg").ifBlank { json.optString("message") }
                        .ifBlank { "上传失败（code=${json.optInt("code")}）" }
                )
            }
            val id = extractFileId(json)
                ?: throw IOException("响应里没有文件 id（原始返回：${text.take(300)}）")
            // 文件解析是异步的：状态非就绪（如 PENDING）时轮询 fetch_files 等一等，
            // 避免 completion 引用一份尚未解析完成的上下文文件。
            val status = extractFileStatus(json)
            waitFileReady(token, id, status)
            return id to status
        }
    }

    /** 上传响应里可能携带文件信息的层级（对齐 ds2api 的检索顺序，兼容多种服务端版本）。 */
    private fun uploadSearchMaps(json: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val data = json.optJSONObject("data")
        val biz = data?.optJSONObject("biz_data")
        for (base in listOf(json, data, biz)) {
            if (base == null) continue
            out.add(base)
            for (key in listOf("file", "biz_data", "data")) {
                base.optJSONObject(key)?.let { out.add(it) }
            }
        }
        return out
    }

    /** 取字符串字段；JSON null / 空白视作缺失。 */
    private fun strOf(m: JSONObject, key: String): String? {
        val v = m.opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v.toString().trim().ifBlank { null }
    }

    /** 文件 id：兼容 data.id / data.file_id / data.biz_data.file.file_id 等结构。 */
    private fun extractFileId(json: JSONObject): String? {
        for (m in uploadSearchMaps(json)) {
            (strOf(m, "id") ?: strOf(m, "file_id"))?.let { return it }
        }
        return (json.opt("data") as? String)?.takeIf { it.isNotBlank() }
    }

    /** 文件状态：同一批层级里找 status / file_status。 */
    private fun extractFileStatus(json: JSONObject): String? {
        for (m in uploadSearchMaps(json)) {
            (strOf(m, "status") ?: strOf(m, "file_status"))?.let { return it }
        }
        return null
    }

    /** 已就绪（可用于 completion）的文件状态集合（对齐 ds2api）。 */
    private val readyFileStatuses = setOf(
        "processed", "ready", "done", "available", "success", "completed", "finished"
    )

    /**
     * 等文件进入就绪状态（best-effort）。
     *
     * - 响应没有状态字段：视作可用，直接放行（不多等）；
     * - 已就绪：直接放行；
     * - 未就绪（PENDING 等）：轮询 /file/fetch_files，最多约 20 秒。
     *
     * 轮询失败 / 超时都不抛错：上传本身已经成功，不能因为「等就绪」把整轮请求判死。
     */
    private suspend fun waitFileReady(token: String, fileId: String, initialStatus: String?) {
        fun isReady(s: String?) = s != null && s.trim().lowercase() in readyFileStatuses
        if (initialStatus == null || isReady(initialStatus)) return
        repeat(20) {
            val st = runCatching { fetchFileStatus(token, fileId) }.getOrNull() ?: return
            if (isReady(st)) return
            kotlinx.coroutines.delay(1000)
        }
    }

    /** 查询已上传文件在官网侧的状态：GET /file/fetch_files?file_ids=<id>。 */
    private suspend fun fetchFileStatus(token: String, fileId: String): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/file/fetch_files?file_ids=" + java.net.URLEncoder.encode(fileId, "UTF-8")
        runCatching { findFileStatusIn(JSONObject(get(url, token)), fileId) }.getOrNull()
    }

    /** 在响应树里递归找 id/file_id 匹配的对象，取其 status。 */
    private fun findFileStatusIn(node: Any?, fileId: String): String? {
        when (node) {
            is JSONObject -> {
                val id = strOf(node, "id") ?: strOf(node, "file_id")
                if (id == fileId) {
                    return strOf(node, "status") ?: strOf(node, "file_status")
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findFileStatusIn(node.opt(keys.next()), fileId)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findFileStatusIn(node.opt(i), fileId)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * 解析 DeepSeek 官网 SSE 流（completion / regenerate 共用同一协议）。
     * 网络请求与读行在 IO 线程执行，解析与 emit 留在 flow 上下文（与 collect 同一线程），
     * 从机制上保证 emit 与 collect 上下文一致，彻底避免跨线程 emit 违规。
     */
    private fun parseWebStream(
        response: okhttp3.Response,
        thinkingEnabled: Boolean,
        onMessageId: (Long?) -> Unit,
        onRequestMessageId: (Long?) -> Unit,
        onThinking: (String) -> Unit,
        onSearchResults: (List<Pair<String, String>>) -> Unit,
        onFinished: (contentFilter: Boolean) -> Unit
    ): Flow<String> = flow {
        var finished = false
        var lastMessageId: Long? = null
        var lastRequestId: Long? = null
        // 是否向正文 emit 过任何非空内容。用于结尾兜底：
        // 官网有时把正文只放在 fragments 里而所有路径补丁都没命中，
        // 结果是「请求立即返回空气」——上层只收到 role 帧就 [DONE]。
        // 流结束时若此标志仍为 false，就从 fragments 里把内容补发出去。
        var anyEmitted = false
        // 联网搜索来源：官网把结果放在 SEARCH / SEARCH_RESULT 类片段或 response/search_results 路径里，
        // 内容形如 [{"title":"..","url":".."}]。累积下来，流结束后回传上层写进 attachmentsJson，
        // 供消息气泡下方的「访问的网页」面板 —— 之前完全没采集，搜了也看不到来源。
        val searchResults = LinkedHashMap<String, String>()
        fun collectSearchJson(raw: String) {
            if (raw.isBlank()) return
            runCatching {
                val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val t = o.get("title")?.asString ?: o.get("name")?.asString ?: ""
                    val u = o.get("url")?.asString ?: o.get("link")?.asString ?: ""
                    if (t.isNotBlank() && u.isNotBlank()) searchResults[u] = t
                }
            }
            // 搜到就立刻回传。
            //
            // 之前只在流结束时统一回传（见函数末尾），可联网搜索这一轮恰恰
            // 极容易被服务端掐断 —— 一断，来源就整批丢失，
            // 表现就是「搜索没有正常返回 / 来源面板是空的」。
            if (searchResults.isNotEmpty()) {
                runCatching { onSearchResults(searchResults.entries.map { it.value to it.key }) }
            }
        }
        val source = response.body?.source() ?: throw IOException("Empty body")
        // —— 解析状态（参考 ds2api internal/sse/parser.go 的状态机）——
        val fragments = ArrayList<JsonObject>()         // 片段对象列表（保序）
        var currentType = if (thinkingEnabled) "thinking" else "text" // 当前默认内容类型
        val thinkingBuf = StringBuilder()               // 用于探测 </think> 切换点
        val thinkClose = Regex("(?i)</\\s*think\\w*\\s*>")

        fun resolveIdx(raw: Int): Int = if (raw < 0) fragments.size + raw else raw

        fun fragType(f: JsonObject?): String =
            f?.takeIf { it.has("type") && it.get("type").isJsonPrimitive }?.get("type")?.asString?.uppercase() ?: ""

        /** 输出一段内容：THINK 进思考回调，其余进正文；发现 </think> 后强制切正文 */
        /**
         * 是否为「思考」类型。
         *
         * 官网的思考类型名不止 THINK / THINKING —— 实测还出现过
         * REASONING / THINKING_CONTENT 之类。之前只认前两个，
         * 于是**后半段思考被当成正文**：思考链只截到开头一句，
         * 其余全部落进正文（就是「不联网也解析不到全部思考内容」）。
         */
        fun isThinkType(t: String): Boolean {
            val u = t.uppercase()
            // 官网的思考类型名变化很多：THINK / THINKING / REASONING / THOUGHT / ANALYSIS
            // 之外还出现过 COT / INTERNAL / SUMMARY 之类。这里把已知的都收进来，
            // 并用「包含」而不是全等 —— 类型名带前后缀（如 THINKING_CONTENT）也能命中。
            return u.contains("THINK") || u.contains("REASON") ||
                u.contains("THOUGHT") || u.contains("ANALYSIS") ||
                u.contains("COT") || u.contains("INTERNAL")
        }

        suspend fun emitContent(text: String, type: String) {
            if (text.isEmpty()) return
            if (isThinkType(type)) {
                onThinking(text)
                thinkingBuf.append(text)
                if (currentType != "text" && thinkClose.containsMatchIn(thinkingBuf)) currentType = "text"
            } else {
                anyEmitted = true
                emit(text)
            }
        }

        /** 追加 fragment 列表（APPEND 追加 / SET 全量替换），按其类型更新 currentType、输出内联内容 */
        suspend fun handleFragmentAppend(arr: JsonArray) {
            for (e in arr) {
                if (e !is JsonObject) continue
                fragments.add(e)
                val t = fragType(e)
                val c = if (e.has("content") && e.get("content").isJsonPrimitive) e.get("content").asString else ""
                if (t.contains("SEARCH") || t == "REFERENCE") {
                    collectSearchJson(c)
                } else if (isThinkType(t)) {
                    currentType = "thinking"
                    emitContent(c, "THINK")
                } else {
                    // 其余（RESPONSE / ANSWER / CONTENT / 未知类型）一律当作正文。
                    // currentType 也一并切到 text，否则后续 content 补丁仍会被
                    // 按「思考」路由，正文整段丢失。
                    currentType = "text"
                    emitContent(c, "text")
                }
            }
        }

        /**
         * 路径补丁分派（参考 ds2api ParseSSEChunkForContentDetailed）。
         * 关键：content 增量路径索引为「相对末尾」（-1 = 最后一个 fragment），
         * 且内容按「当前类型」而非片段类型路由，避免上游思考结束后仍从 fragments 路径下发正文。
         */
        suspend fun applyFragmentOp(p: String, value: JsonElement) {
            // 0) 联网搜索来源（独立路径下发）
            if (p == "response/search_results" || p == "search_results") {
                collectSearchJson(value.toString())
                return
            }
            // 1) fragments 列表（APPEND 追加 / SET 全量替换，内容输出逻辑一致）
            if ((p == "response/fragments" || p == "fragments") && value.isJsonArray) {
                handleFragmentAppend(value.asJsonArray)
                return
            }
            // 2) 显式内容路径：response/content → 正文；response/thinking_content → 思考
            if (p == "response/content" || p == "response/thinking_content") {
                val isThinking = p == "response/thinking_content"
                if (isThinking) currentType = "thinking" else currentType = "text"
                val text = when {
                    value.isJsonPrimitive -> value.asString
                    value.isJsonObject -> {
                        val obj = value.asJsonObject
                        if (obj.has("text") && obj.get("text").isJsonPrimitive) obj.get("text").asString
                        else if (obj.has("content") && obj.get("content").isJsonPrimitive) obj.get("content").asString
                        else ""
                    }
                    else -> ""
                }
                emitContent(text, if (isThinking) "THINK" else "text")
                return
            }
            // 3) 片段内容增量：fragments/<idx>/content（负数 = 相对末尾；o 字段可能缺失，不做 op 限制）
            if (value.isJsonPrimitive && p.contains("fragments/") && p.endsWith("/content")) {
                val rel = fragmentIndexFromPath(p) ?: return
                val abs = resolveIdx(rel)
                val t = fragType(fragments.getOrNull(abs))
                // 只有明确是「思考」类型的片段才进思考；其余（含 RESPONSE / 未知类型）一律进正文。
                // 之前未知类型沿用 currentType，若 currentType 还停在 thinking，正文就会被整段
                // 路由进思考链，正文为空 —— 表现就是「官网搜索并回复了，App 里却没有」。
                if (isThinkType(t)) {
                    currentType = "thinking"
                    emitContent(value.asString, "THINK")
                } else {
                    currentType = "text"
                    emitContent(value.asString, "text")
                }
                return
            }
            // 4) 单个片段新建/替换：fragments/<idx> SET（对象），内联 content 直接输出
            if (value.isJsonObject && p.contains("fragments/") && !p.endsWith("/content") && !p.endsWith("/status") && !p.endsWith("/thinking_content")) {
                val rel = fragmentIndexFromPath(p) ?: return
                val abs = resolveIdx(rel)
                val obj = value.asJsonObject
                val t = fragType(obj)
                if (isThinkType(t)) currentType = "thinking"
                if (t == "RESPONSE") currentType = "text"
                if (abs in fragments.indices) {
                    fragments[abs] = obj
                } else if (abs == fragments.size) {
                    fragments.add(obj)
                }
                if (obj.has("content") && obj.get("content").isJsonPrimitive) {
                    emitContent(obj.get("content").asString, if (isThinkType(t)) "THINK" else "text")
                }
                return
            }
        }

        /** 处理一组相对 response 的 patch 子操作（顶层 v 数组 / BATCH） */
        suspend fun processSubPatches(arr: JsonArray) {
            for (e in arr) {
                if (e !is JsonObject) continue
                if (!e.has("p") || !e.get("p").isJsonPrimitive) continue
                if (!e.has("v")) continue
                val subP = e.get("p").asString
                val subV = e.get("v")
                applyFragmentOp(subP, subV)
                if (isFinished(subP, subV)) finished = true
            }
        }

        // 单个 SSE data 帧 → 解析成 patch 并应用到状态机（原逐行循环里的逻辑原样搬进来）
        suspend fun handleChunk(data: String) {
            val chunk = try {
                JsonParser.parseString(data).asJsonObject
            } catch (e: Exception) {
                return
            }
            // 提取 message_id（会话连贯用）与 request_message_id（用户消息官方 id）
            // 关键：拿到 message_id 就**立刻**回传，不要等到流结束。
            extractMessageId(chunk)?.let {
                if (it != lastMessageId) {
                    lastMessageId = it
                    onMessageId(it)
                }
            }
            extractRequestMessageId(chunk)?.let { lastRequestId = it }

            when {
                // 顶层 v 数组（终态/批量收口，无 o 字段）：{"v":[{"p":...}]}
                !chunk.has("p") && chunk.has("v") && chunk.get("v").isJsonArray ->
                    processSubPatches(chunk.getAsJsonArray("v"))
                // 路径补丁：{"p":"response/...","o":"APPEND/SET/BATCH","v":...}
                chunk.has("p") && chunk.get("p").isJsonPrimitive && chunk.has("v") -> {
                    val p = chunk.get("p").asString
                    val o = if (chunk.has("o") && chunk.get("o").isJsonPrimitive) chunk.get("o").asString else "SET"
                    val v = chunk.get("v")
                    if (o == "BATCH" && v.isJsonArray) {
                        processSubPatches(v.asJsonArray)
                    } else {
                        applyFragmentOp(p, v)
                        if (isFinished(p, v)) finished = true
                    }
                }
            }
            // 初始 envelope：{"v":{"response":{"fragments":[...]}}}（首个片段内联下发）
            val initialFrags = extractInitialFragments(chunk)
            if (initialFrags != null) handleFragmentAppend(initialFrags)
            // 无路径 v：字符串按 currentType 路由；对象取 text/content 字段；数组已在上面处理
            if (chunk.has("v") && !chunk.has("p")) {
                val v = chunk.get("v")
                when {
                    v.isJsonPrimitive -> emitContent(v.asString, currentType)
                    v.isJsonObject -> {
                        val obj = v.asJsonObject
                        val text = if (obj.has("text") && obj.get("text").isJsonPrimitive) obj.get("text").asString
                        else if (obj.has("content") && obj.get("content").isJsonPrimitive) obj.get("content").asString
                        else null
                        if (!text.isNullOrEmpty()) {
                            emitContent(text, currentType)
                        } else if (obj.has("response") && obj.get("response").isJsonObject) {
                            val resp = obj.getAsJsonObject("response")
                            if (resp.has("fragments") && resp.get("fragments").isJsonArray) {
                                handleFragmentAppend(resp.getAsJsonArray("fragments"))
                            }
                        }
                    }
                }
            }
            // 兼容字段：thinking / thinking_content / delta.reasoning_content
            extractLegacyThinking(chunk)?.let { onThinking(it) }
        }

        try {
            try {
                // 字节级累积：绝不能逐字节 toChar()（Kotlin Byte.toInt() 符号扩展会把
                // 多字节 UTF-8 打碎，中文全变乱码→解析失败→静默返回空）。这里累积原始
                // 字节、按空行切出完整事件后再整体 UTF-8 解码，保证多字节边界永远落在事件内。
                val acc = java.io.ByteArrayOutputStream()
                val rawAcc = java.io.ByteArrayOutputStream()
                var anyUpstreamEvent = false

                fun findEventEnd(buf: ByteArray): Pair<Int, Int> {
                    var j = 0
                    while (j < buf.size) {
                        if (j + 1 < buf.size && buf[j] == 10.toByte() && buf[j + 1] == 10.toByte()) return j to 2
                        if (j + 3 < buf.size && buf[j] == 13.toByte() && buf[j + 1] == 10.toByte() &&
                            buf[j + 2] == 13.toByte() && buf[j + 3] == 10.toByte()
                        ) return j to 4
                        j++
                    }
                    return -1 to 0
                }

                suspend fun processEvents() {
                    while (true) {
                        val arr = acc.toByteArray()
                        val (cut, sep) = findEventEnd(arr)
                        if (cut < 0) return
                        val evt = String(arr, 0, cut, Charsets.UTF_8)
                        acc.reset()
                        acc.write(arr, cut + sep, arr.size - (cut + sep))
                        for (ln in evt.split('\n')) {
                            val trimmed = ln.trim()
                            if (!trimmed.startsWith("data:")) continue
                            val data = trimmed.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            if (data == "[DONE]") { finished = true; return }
                            anyUpstreamEvent = true
                            handleChunk(data)
                        }
                    }
                }

                while (!finished) {
                    // 读「当前已到达」的所有字节：至少 1 个，但不等待整行。
                    // 这样上游一到数据就立刻被处理并 emit，不再被行缓冲攒住。
                    val incoming: ByteArray = withContext(Dispatchers.IO) {
                        if (source.exhausted()) ByteArray(0) else {
                            val first = source.readByteArray(1L)
                            val avail = source.buffer.size
                            if (avail > 0) first + source.readByteArray(avail) else first
                        }
                    }
                    if (incoming.isEmpty()) break
                    acc.write(incoming)
                    if (rawAcc.size() < 64 * 1024) rawAcc.write(incoming)
                    processEvents()
                }
                // 结尾：处理残留（最后一帧可能没以空行收尾）
                if (!finished) {
                    val tail = String(acc.toByteArray(), Charsets.UTF_8)
                    for (ln in tail.split('\n')) {
                        val trimmed = ln.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val data = trimmed.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") continue
                        anyUpstreamEvent = true
                        handleChunk(data)
                    }
                }
                // 诊断采样：记录本轮上游原始返回，供 /debug/last-upstream 查看
                lastUpstreamRaw = String(rawAcc.toByteArray(), Charsets.UTF_8)
                // 非 SSE 兜底：一个事件都没解析出来、但上游确实回了内容 ——
                // 说明返回的是纯 JSON（多为错误体，如 {"code":40003,"msg":"INVALID_TOKEN"}），
                // 直接把它抛出去，而不是静默变成「空气」。
                if (!anyUpstreamEvent && lastUpstreamRaw.isNotBlank()) {
                    // 上游回的是纯 JSON：多为业务错误（HTTP 200 + biz_code != 0），
                    // 例如 {"code":0,"data":{"biz_code":26,"biz_msg":"invalid message id"}}。
                    // 解析成结构化异常，交给上层做「重建会话重试」。
                    parseBizError(lastUpstreamRaw)?.let { (code, msg) ->
                        throw WebBizException(code, msg)
                    }
                    throw IOException(
                        "官网未返回可解析的 SSE 数据，上游原始返回：\n" +
                            lastUpstreamRaw.take(800)
                    )
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 超时不再无条件抛 —— 这是「老是中断」的第二根因。
                //
                // 之前这里直接 throw，上层 catch 把它归类为失败、记账号失败，
                // **已经吐出来的正文和思考全丢**，体感就是「说到一半突然断」。
                // 现在分两种情况：
                //   1) 已经 emit 过正文 → 视为「上游正常结束」（长回复尾部超时很常见），
                //      补发 fragments 兜底后正常收尾，不抛异常、不记失败。
                //   2) 一个正文字都没 emit 过 → 才是真超时，抛出让人排查。
                if (!anyEmitted) {
                    throw IOException("官网响应超时（300 秒内未收到任何正文），请检查网络后重试。")
                }
            }
        } finally {
            response.close()
        }
        // 空响应兜底：一个正文字都没发过，就从 fragments 里把内容补发，
        // 避免「请求立即返回空气」。
        if (!anyEmitted && fragments.isNotEmpty()) {
            val fallback = StringBuilder()
            for (f in fragments) {
                val t = fragType(f)
                if (isThinkType(t)) continue
                if (f.has("content") && f.get("content").isJsonPrimitive) {
                    fallback.append(f.get("content").asString)
                }
            }
            if (fallback.isNotEmpty()) {
                anyEmitted = true
                emit(fallback.toString())
            }
        }
        onMessageId(lastMessageId)
        onRequestMessageId(lastRequestId)
        if (searchResults.isNotEmpty()) {
            onSearchResults(searchResults.entries.map { it.value to it.key })
        }
        onFinished(false)
    }

    /**
     * 官网重新生成（POST /api/v0/chat/regenerate）。
     * 用 child_message_id 定位被重新生成的那条 AI 回复：服务器复用原用户消息、直接替换该回复，
     * 不会像 completion 通道那样把用户消息再发一遍。
     * @param childMessageId 被重生成那条 AI 回复的官方 message_id
     */
    suspend fun regenerateStream(
        token: String,
        sessionId: String,
        childMessageId: Long,
        thinkingEnabled: Boolean = true,
        searchEnabled: Boolean = false,
        onMessageId: (Long?) -> Unit = {},
        onRequestMessageId: (Long?) -> Unit = {},
        onThinking: (String) -> Unit = {},
        onSearchResults: (List<Pair<String, String>>) -> Unit = {},
        onFinished: (contentFilter: Boolean) -> Unit = {}
    ): Flow<String> = flow {
        val solvedPow = solvePow(token)

        // 同 completion 通道：优先带新版 thinking 对象，400 时回退仅旧字段
        var body = regenerateBody(sessionId, childMessageId, thinkingEnabled, searchEnabled, includeThinking = true)
        var req = buildRequest(REGENERATE, body.toString(), token, solvedPow)
        var response = withContext(Dispatchers.IO) {
            streamingClient.newCall(req).execute()
        }
        if (!response.isSuccessful && response.code == 400) {
            response.close()
            body = regenerateBody(sessionId, childMessageId, thinkingEnabled, searchEnabled, includeThinking = false)
            req = buildRequest(REGENERATE, body.toString(), token, solvedPow)
            response = withContext(Dispatchers.IO) {
                streamingClient.newCall(req).execute()
            }
        }
        if (!response.isSuccessful) {
            throw IOException("API error: ${response.code} ${response.message}")
        }
        emitAll(parseWebStream(response, thinkingEnabled, onMessageId, onRequestMessageId, onThinking, onSearchResults, onFinished))
    }

    /** 官网重新生成请求体：新版 thinking 对象可选（400 时回退） */
    private fun regenerateBody(
        sessionId: String,
        childMessageId: Long,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        includeThinking: Boolean
    ): JSONObject {
        val b = JSONObject()
            .put("chat_session_id", sessionId)
            .put("child_message_id", childMessageId)
            .put("thinking_enabled", thinkingEnabled)
            .put("search_enabled", searchEnabled)
            .put("user_options", JSONObject.NULL)
        if (includeThinking) {
            b.put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
        }
        return b
    }

    /**
     * 官网编辑消息（POST /api/v0/chat/edit_message）。
     * 用 message_id 定位被编辑的那条用户消息，服务端**原地替换**该消息并重新生成回复，
     * 不会像 completion 通道那样把新内容当作追加发送到会话末尾。
     * 请求体只有 chat_session_id / message_id / prompt / 开关，不需要 parent_message_id。
     * @param messageId 被编辑用户消息的官方 message_id
     */
    suspend fun editMessageStream(
        token: String,
        sessionId: String,
        messageId: Long,
        prompt: String,
        thinkingEnabled: Boolean = true,
        searchEnabled: Boolean = false,
        onMessageId: (Long?) -> Unit = {},
        onRequestMessageId: (Long?) -> Unit = {},
        onThinking: (String) -> Unit = {},
        onSearchResults: (List<Pair<String, String>>) -> Unit = {},
        onFinished: (contentFilter: Boolean) -> Unit = {}
    ): Flow<String> = flow {
        val solvedPow = solvePow(token)

        // 同 completion 通道：优先带新版 thinking 对象，400 时回退仅旧字段
        var body = editMessageBody(sessionId, messageId, prompt, thinkingEnabled, searchEnabled, includeThinking = true)
        var req = buildRequest(EDIT_MESSAGE, body.toString(), token, solvedPow)
        var response = withContext(Dispatchers.IO) {
            streamingClient.newCall(req).execute()
        }
        if (!response.isSuccessful && response.code == 400) {
            response.close()
            body = editMessageBody(sessionId, messageId, prompt, thinkingEnabled, searchEnabled, includeThinking = false)
            req = buildRequest(EDIT_MESSAGE, body.toString(), token, solvedPow)
            response = withContext(Dispatchers.IO) {
                streamingClient.newCall(req).execute()
            }
        }
        if (!response.isSuccessful) {
            throw IOException("API error: ${response.code} ${response.message}")
        }
        emitAll(parseWebStream(response, thinkingEnabled, onMessageId, onRequestMessageId, onThinking, onSearchResults, onFinished))
    }

    /** 官网编辑消息请求体：新版 thinking 对象可选（400 时回退） */
    private fun editMessageBody(
        sessionId: String,
        messageId: Long,
        prompt: String,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        includeThinking: Boolean
    ): JSONObject {
        val b = JSONObject()
            .put("chat_session_id", sessionId)
            .put("message_id", messageId)
            .put("prompt", prompt)
            .put("search_enabled", searchEnabled)
            .put("thinking_enabled", thinkingEnabled)
        if (includeThinking) {
            b.put("thinking", JSONObject().put("type", if (thinkingEnabled) "enabled" else "disabled"))
        }
        return b
    }

    /** 从 SSE chunk 提取回复 message_id（供 parent_message_id 链接） */
    private fun extractMessageId(chunk: JsonObject): Long? {
        // 顶层 {"response_message_id": 123}
        if (chunk.has("response_message_id") && chunk.get("response_message_id").isJsonPrimitive) {
            return runCatching { chunk.get("response_message_id").asLong }.getOrNull()
        }
        // {"v":{"response":{"message_id":123}}}
        if (chunk.has("v") && chunk.get("v").isJsonObject) {
            val v = chunk.getAsJsonObject("v")
            if (v.has("response") && v.get("response").isJsonObject) {
                val resp = v.getAsJsonObject("response")
                if (resp.has("message_id") && resp.get("message_id").isJsonPrimitive) {
                    return runCatching { resp.get("message_id").asLong }.getOrNull()
                }
            }
        }
        return null
    }

    /** 提取初始快照内联片段：{"v":{"response":{"fragments":[...]}}}（首个 chunk 附带完整消息骨架） */
    private fun extractInitialFragments(chunk: JsonObject): JsonArray? {
        if (chunk.has("v") && chunk.get("v").isJsonObject) {
            val v = chunk.getAsJsonObject("v")
            if (v.has("response") && v.get("response").isJsonObject) {
                val resp = v.getAsJsonObject("response")
                if (resp.has("fragments") && resp.get("fragments").isJsonArray) {
                    return resp.getAsJsonArray("fragments")
                }
            }
        }
        return null
    }

    /** 从 ready 事件提取用户消息官方 id：{"request_message_id": 123}（重生成/编辑时作为正确父消息） */
    private fun extractRequestMessageId(chunk: JsonObject): Long? {
        if (chunk.has("request_message_id") && chunk.get("request_message_id").isJsonPrimitive) {
            return runCatching { chunk.get("request_message_id").asLong }.getOrNull()
        }
        return null
    }

    /** 从 path 中提取 fragment 索引（支持 response/fragments/-1、fragments/0 等形式） */
    private fun fragmentIndexFromPath(p: String): Int? {
        return FRAG_IDX_RE.find(p)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 判断 path+value 是否为整条流结束。
     * 只认 response/status 与 status 两个精确路径（以及顶层裸 "FINISHED"）。
     * 注意：绝不能按「contains status」判断——response/fragments/-1/status=FINISHED
     * 只是思考片段自身的结束状态，误判会把整条流提前终止，导致正文永远收不到（一直三个点）。
     */
    private fun isFinished(p: String, v: JsonElement): Boolean {
        if (!v.isJsonPrimitive) return false
        val s = v.asString
        if (p != "response/status" && p != "status" && p != "") return false
        return s == "FINISHED" || s == "CONTENT_FILTER"
    }

    /** 兼容非 fragments 通道的思考字段（其他协议的 delta 等） */
    private fun extractLegacyThinking(chunk: JsonObject): String? {
        if (chunk.has("thinking") && chunk.get("thinking").isJsonPrimitive) {
            return chunk.get("thinking").asString
        }
        if (chunk.has("thinking_content") && chunk.get("thinking_content").isJsonPrimitive) {
            return chunk.get("thinking_content").asString
        }
        if (chunk.has("v") && chunk.get("v").isJsonObject) {
            val v = chunk.getAsJsonObject("v")
            if (v.has("delta") && v.get("delta").isJsonObject) {
                val delta = v.getAsJsonObject("delta")
                if (delta.has("reasoning_content") && delta.get("reasoning_content").isJsonPrimitive) {
                    return delta.get("reasoning_content").asString
                }
                if (delta.has("reasoning") && delta.get("reasoning").isJsonPrimitive) {
                    return delta.get("reasoning").asString
                }
            }
        }
        // OpenAI 风格：顶层 choices[0].delta.reasoning_content
        chunk.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.let { ch ->
            ch.getAsJsonObject("delta")?.let { d ->
                for (k in listOf("reasoning_content", "reasoning", "thinking")) {
                    d.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.let { return it }
                }
            }
        }
        return null
    }

    private fun post(url: String, body: String, token: String?): String {
        val req = buildRequest(url, body, token, null)
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${resp.message}")
            }
            return resp.body?.string() ?: throw IOException("Empty response")
        }
    }

    private fun get(url: String, token: String?): String {
        val builder = Request.Builder().url(url).get()
        for ((k, v) in DEFAULT_HEADERS) builder.header(k, v)
        token?.let { builder.header("authorization", "Bearer $it") }
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${resp.message}")
            }
            return resp.body?.string() ?: throw IOException("Empty response")
        }
    }

    /** 官网消息（含 message_id / parent_id，用于接续上下文） */
    data class WebMessage(
        val id: Long?,
        val parentId: Long?,
        val role: String,
        val content: String
    )

    /**
     * 拉取会话历史消息（官网网页版历史接口）。
     * 正确接口为 GET /chat/history_messages?chat_session_id=xxx；
     * 旧代码用的 POST /chat/history 不存在，会导致同步永远拿不到数据。
     */
    suspend fun fetchHistory(token: String, sessionId: String): List<WebMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE${"/chat/history_messages?chat_session_id="}" +
                java.net.URLEncoder.encode(sessionId, "UTF-8")
            parseHistory(get(url, token))
        }.getOrDefault(emptyList())
    }

    private fun parseHistory(jsonText: String): List<WebMessage> {
        val out = ArrayList<WebMessage>()
        val root = JSONObject(jsonText)
        val data = root.optJSONObject("data")
        val bizData = data?.optJSONObject("biz_data")
        // 官网 history_messages 的数组键是 chat_messages（fragments 拆内容、role 为大写）；
        // 兼容旧结构 chat_history / messages / history
        val arr = bizData?.optJSONArray("chat_messages")
            ?: data?.optJSONArray("chat_messages")
            ?: bizData?.optJSONArray("messages")
            ?: bizData?.optJSONArray("chat_history")
            ?: bizData?.optJSONArray("history")
            ?: data?.optJSONArray("messages")
            ?: data?.optJSONArray("chat_history")
            ?: data?.optJSONArray("history")
            ?: root.optJSONArray("chat_messages")
            ?: root.optJSONArray("messages")
            ?: root.optJSONArray("chat_history")
        if (arr == null) return out

        // 解析出带 id / parent_id 的消息树节点；没有 id 的按原顺序直接输出
        data class Node(val id: Long, val parentId: Long, val role: String, val content: String, val ts: Long)
        val nodes = ArrayList<Node>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val rawRole = item.optString("role").ifBlank {
                if (item.optString("type") == "user") "USER" else "ASSISTANT"
            }
            val role = rawRole.trim().lowercase().let {
                when (it) { "user" -> "user"; "system" -> "system"; else -> "assistant" }
            }
            var content = item.optString("content").ifBlank { item.optString("text") }
            if (content.isBlank()) {
                // 助手正文在 fragments 里：type=REQUEST/RESPONSE/TEXT 拼接，跳过 THINK/TIP
                item.optJSONArray("fragments")?.let { frags ->
                    val sb = StringBuilder()
                    for (j in 0 until frags.length()) {
                        val f = frags.optJSONObject(j) ?: continue
                        val ft = f.optString("type")
                        if (ft == "THINK" || ft == "TIP" || ft == "thinking") continue
                        sb.append(f.optString("content"))
                    }
                    if (sb.isNotEmpty()) content = sb.toString()
                }
            }
            if (content.isBlank()) continue
            val id = item.optString("message_id").toLongOrNull()
                ?: item.optLong("message_id", item.optLong("id", 0L))
            val parentId = item.optString("parent_id").toLongOrNull()
                ?: item.optLong("parent_id", -1L)
            val ts = item.optLong("inserted_at", item.optLong("created_at", 0L))
            if (id == 0L || parentId < 0) {
                out.add(WebMessage(id.takeIf { it != 0L }, parentId.takeIf { it >= 0 }, role, content))
                continue
            }
            nodes.add(Node(id, parentId, role, content, ts))
        }
        if (nodes.isEmpty()) return out

        // 优先按官网「当前路径」回溯（编辑/分支只保留当前看到的版本）
        val byId = nodes.associateBy { it.id }
        val currentId = (bizData ?: data)
            ?.optJSONObject("chat_session")
            ?.optLong("current_message_id", -1L)
        val ordered = ArrayList<Node>()
        if (currentId != null && byId.containsKey(currentId)) {
            val seen = HashSet<Long>()
            var cur: Node? = byId[currentId]
            while (cur != null && seen.add(cur.id)) {
                ordered.add(cur)
                cur = byId[cur.parentId]
            }
            ordered.reverse()
        } else {
            // 回退：从根出发，每个分支点取最新 child
            val ids = nodes.map { it.id }.toHashSet()
            val roots = nodes.filter { it.parentId !in ids }.sortedBy { it.ts }
            val children = HashMap<Long, MutableList<Node>>()
            for (n in nodes) if (n.parentId in ids) children.getOrPut(n.parentId) { ArrayList() }.add(n)
            for (kids in children.values) kids.sortBy { it.ts }
            val visited = HashSet<Long>()
            for (r in roots) {
                var cur: Node? = r
                while (cur != null && visited.add(cur.id)) {
                    ordered.add(cur)
                    cur = children[cur.id]?.lastOrNull()
                }
            }
        }
        for (n in ordered) out.add(WebMessage(n.id, n.parentId, n.role, n.content))
        return out
    }

    /**
     * 在官网删除单条消息（best-effort）。
     * 官网消息级删除端点未公开确认，采用 chat/message/delete 尝试；
     * 删除成功后返回 true，接口不兼容/失败返回 false（不抛异常）。
     */
    suspend fun deleteMessage(token: String, sessionId: String, messageId: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("chat_session_id", sessionId)
                    .put("message_id", messageId)
                    .toString()
                val resp = post("$BASE${"/chat/message/delete"}", body, token)
                val json = JSONObject(resp)
                json.optInt("code", -1) == 0
            }.getOrDefault(false)
        }

    /**
     * 在官网重命名会话标题（best-effort）。用于官网对话标题与本地一致。
     */
    suspend fun renameSession(token: String, sessionId: String, title: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("chat_session_id", sessionId)
                    .put("title", title)
                    .toString()
                val resp = post("$BASE${"/chat_session/save"}", body, token)
                val json = JSONObject(resp)
                json.optInt("code", -1) == 0
            }.getOrDefault(false)
        }

    /** 官网网页端的会话信息（列表页用） */
    data class SessionInfo(
        val id: String,
        val title: String,
        val updatedAt: Double
    )

    /**
     * 拉取官网会话列表（网页版 / ds2api 同款接口）。
     * GET /chat_session/fetch_page?lte_cursor.pinned=false
     * 返回按服务端顺序排列的会话，含 id / title / updated_at。
     */
    suspend fun fetchSessions(token: String): List<SessionInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE${"/chat_session/fetch_page?lte_cursor.pinned=false"}"
            val resp = get(url, token)
            val root = JSONObject(resp)
            val bizData = root.optJSONObject("data")?.optJSONObject("biz_data")
            val arr = bizData?.optJSONArray("chat_sessions") ?: return@runCatching emptyList()
            val list = ArrayList<SessionInfo>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isNotBlank()) {
                    list.add(
                        SessionInfo(
                            id = id,
                            title = item.optString("title"),
                            updatedAt = item.optDouble("updated_at", 0.0)
                        )
                    )
                }
            }
            list
        }.getOrDefault(emptyList())
    }

    /**
     * 校验官网 token 是否仍然有效。
     *
     * 用途：WebView「重新登录」时会读页面里的 localStorage，而那里可能残留着
     * 上一次登录的旧 token（已过期，或仍是旧账号）。若不校验，就把它当成
     * 新登录态保存下来，表现就是「重新登录看似成功、一发消息就报错/换不了账号」。
     *
     * 走只读接口 /chat_session/fetch_page：code == 0 且 biz_code == 0 才算有效。
     */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext false
        runCatching {
            val resp = get("$BASE${"/chat_session/fetch_page?lte_cursor.pinned=false"}", token)
            val root = JSONObject(resp)
            if (root.optInt("code", -1) != 0) return@runCatching false
            val data = root.optJSONObject("data")
            if (data != null && data.has("biz_code") && data.optInt("biz_code", -1) != 0) {
                return@runCatching false
            }
            true
        }.getOrDefault(false)
    }

    private fun buildRequest(url: String, body: String, token: String?, pow: String?): Request {
        val builder = Request.Builder().url(url).post(body.toRequestBody(jsonMedia))
        for ((k, v) in DEFAULT_HEADERS) builder.header(k, v)
        token?.let { builder.header("authorization", "Bearer $it") }
        pow?.let { builder.header("x-ds-pow-response", it) }
        return builder.build()
    }

    private fun checkBiz(json: JSONObject, errPrefix: String) {
        if (json.optInt("code") != 0) throw IOException("$errPrefix: ${json.optString("msg")}")
        val data = json.optJSONObject("data")
        if (data != null && data.optInt("biz_code") != 0) {
            throw IOException("$errPrefix: ${data.optString("biz_msg")}")
        }
    }
}
