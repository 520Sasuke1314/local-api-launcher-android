package com.dslauncher.data.qwen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Qwen 网页端（chat.qwen.ai）客户端。协议严格对齐**官方前端** qwen-chat-fe 0.3.12
 * （从线上 main.js 反解）+ SuperAPI 的「网页登录转 API」实现。
 *
 * ## 两条传输通道
 * 1. **OkHttp 直连**（[chatStream]）：请求头/体逐字段对齐官方前端。
 *    必要但不充分 —— 阿里云 WAF 按客户端指纹分级，Java/Conscrypt TLS 会被判爬虫，
 *    表现为 `FAIL_SYS_USER_VALIDATE` punish、直接 tarpit，或滑块验证页。
 * 2. **WebView 真浏览器栈**（[browserFlow]）：见 [QwenBrowser]。
 *    真 Chromium 指纹 + 页面自身带上的 WAF 通行 cookie，是能真正过风控的那条路。
 *
 * ## 协议要点
 * - 登录：`POST /api/v1/auths/signin {email,password}` → `{token}`
 * - 模型：`GET /api/v2/models`（**免鉴权**，返回真实可用 id）
 * - 对话：`POST /api/v2/chat/completions?chat_id=<uuid>`，头带
 *   `Version: 0.3.12` / `source: web` / `X-Request-Id` / `X-Accel-Buffering: no` / `Timezone`；
 *   体带 `version:"2.1"` / `chatId` / `parent_id` / `parentId` / `chat_mode` / `timestamp`
 *   / `incremental_output`，`messages[i]` 是完整网页消息对象，
 *   其中 `feature_config.output_schema="phase"` 是让上游回传 `phase` 的开关。
 * - 流：SSE（`data: {...}` + `data: [DONE]`），`phase` 在 `choices[0].delta` 内，
 *   会话链 id 在 `response.created`，错误在 `error.code`/`error.details`。
 *
 * 注意：使用网页端接口有封号风险，仅建议小号使用。
 */
class QwenWebClient(
    private val okHttpClient: OkHttpClient,
    appContext: Context? = null
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaTypeOrNull()

    /**
     * 真浏览器栈通道。仅当传入 Context 时可用；是否启用由 [useBrowser] 控制
     * （仓储层按设置同步，缺省关闭 —— 未在页面登录前走它只会报「未就绪」）。
     */
    val browser: QwenBrowser? = appContext?.let { QwenBrowser(it) }

    /** 是否优先走浏览器通道（由仓储层按 SettingsStore.qwenUseBrowser 同步）。 */
    @Volatile
    var useBrowser: Boolean = false

    /**
     * 内存 CookieJar：阿里云 WAF（acw_tc 等）靠首访下发的 cookie 识别会话，
     * 没有它 signin/completion 都可能被拦。按 URL host 隔离存放。
     */
    private val cookieJar = object : okhttp3.CookieJar {
        private val store = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            store[url.host] = cookies
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store[url.host].orEmpty()
    }

    /** 常规（非流式）请求 client：与流式 client 共用同一 CookieJar（WAF 会话）。 */
    private val apiClient: OkHttpClient = okHttpClient.newBuilder()
        .cookieJar(cookieJar)
        .build()

    /**
     * 续期专用 client：**不挂全局 CookieJar**。
     * 每个账号的会话 Cookie 由调用方按账号注入，避免多账号共用一个 jar 时互相串味。
     */
    private val refreshClient: OkHttpClient = okHttpClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 流式读取专用 client：思考模型可能长时间不吐字，读超时放宽到 300s（对齐 DS 通道）。
    private val streamingClient: OkHttpClient = okHttpClient.newBuilder()
        .cookieJar(cookieJar)
        .readTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Accept-Encoding", "identity")
                .build()
            chain.proceed(req)
        }
        .build()

    companion object {
        val BASE = "https://chat.qwen.ai"
        /** v2 API 根（官方前端 rn = origin + /api/v2）。 */
        val API_V2 = "$BASE/api/v2"
        /** 当前登录用户信息（网页端登录态校验同源端点；无 token 时 401）。 */
        val AUTHS = "$BASE/api/v1/auths/"
        /**
         * 会话续期接口（**网页端自动续期的关键**）：
         * `GET https://auth.qwen.ai/api/v2/auths/refresh`
         *
         * 从线上前端 0.3.16 的 `TokenManager.performHttpRefresh()` 反解 + 实测确认：
         * - 基址不是 chat.qwen.ai 而是 `auth.qwen.ai`（前端 `loginBaseUrl`）；
         * - 必须带 `x-request-origin: https://chat.qwen.ai`（缺了 → `Bad_Request/Missing origin`）；
         * - 鉴权靠 **Cookie**（实测 `token=<...>` 被识别；无 Cookie → `Unauthorized`），
         *   所以登录时必须把 WebView 的 Cookie 一并存进账号池；
         * - 返回 `{"success":true,"data":{"access_token":"...","expires_at":<秒>}}`。
         */
        val AUTH_BASE = "https://auth.qwen.ai"
        val AUTH_BASE_COM = "https://auth.qwen.com"
        val AUTH_REFRESH = "$AUTH_BASE/api/v2/auths/refresh"
        /** 邮箱密码登录（FastAPI 遗留接口，仍在服役）。 */
        val SIGNIN = "$BASE/api/v2/auths/signin"
        /** 网页端对话端点（chat_id 走 query，见官方前端 fetch 调用）。 */
        val CHAT_COMPLETIONS = "$API_V2/chat/completions"
        /** 网页端模型清单（**免鉴权**，SuperAPI 同款动态拉取来源）。 */
        val MODELS = "$API_V2/models"

        /** 官方前端版本号，作为 `Version` 请求头随每个请求发出（网关会校验；当前线上 0.3.16）。 */
        const val FE_VERSION = "0.3.16"

        /** 对话协议版本（官方前端 completionVersion 默认值）。 */
        const val COMPLETION_VERSION = "2.1"

        /** 网页端 UA：模拟桌面 Chrome（source=web）。 */
        const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** 官方前端的 source 头取值（桌面浏览器 = web，移动端 = h5）。 */
        const val SOURCE = "web"

        /**
         * 静态兜底模型清单（/api/v2/models 拉取失败时用）。
         * 2026-10 实测线上仅这三个是 active + preset 的真实 id。
         */
        val MODEL_IDS = listOf(
            "qwen3.8-max",
            "qwen3.7-plus",
            "qwen3.8-omni-flash"
        )

        /** 最近一次上游原始返回（诊断用）。 */
        @Volatile
        var lastUpstreamRaw: String = ""

        /** 最近一次上游响应元信息（诊断用：状态码/内容类型）。 */
        @Volatile
        var lastUpstreamMeta: String = ""

        /** 模型清单内存缓存（免鉴权接口，TTL 内不重复拉）。 */
        @Volatile
        private var modelsCache: List<String> = emptyList()

        @Volatile
        private var modelsFetchedAt: Long = 0L

        /** 模型清单缓存 TTL：6 小时（对齐 SuperAPI 的 qwen_models_ttl_hours）。 */
        private const val MODELS_TTL_MS = 6 * 60 * 60 * 1000L

        @Synchronized
        fun cachedModels(): List<String> = modelsCache

        @Synchronized
        private fun storeModels(list: List<String>) {
            if (list.isNotEmpty()) {
                modelsCache = list
                modelsFetchedAt = System.currentTimeMillis()
            }
        }

        /** 供外部直接注入缓存。 */
        @Synchronized
        fun seedModels(list: List<String>) = storeModels(list)

        /** `Timezone` 请求头：官方前端用 `new Date().toString()` 抹掉时区名。 */
        fun timezoneHeader(): String =
            SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US).format(Date())

        /**
         * 静态版 JWT `exp` 解析（毫秒）；解析失败/无 exp 返回 0。
         * 供登录入库时补录过期时间（不依赖实例状态）。
         */
        fun parseJwtExpiryMsStatic(token: String): Long = runCatching {
            val parts = token.split(".")
            if (parts.size < 2) return@runCatching 0L
            val payload = String(
                android.util.Base64.decode(
                    parts[1].replace('-', '+').replace('_', '/'),
                    android.util.Base64.DEFAULT
                )
            )
            val exp = JSONObject(payload).optLong("exp", 0L)
            if (exp > 0L) exp * 1000L else 0L
        }.getOrDefault(0L)
    }

    /** 用户信息（账号列表显示用）。 */
    data class UserInfo(
        val email: String?,
        val name: String?
    )

    // ------------------------------------------------------------------ 请求头

    /**
     * 官方前端统一请求头（WV 拦截器）：
     * Accept / Content-Type / Version / source / Authorization / X-Request-Id / Timezone
     * （/auths/ 等接口额外带 x-request-origin）。
     */
    private fun webRequest(
        url: String,
        token: String?,
        accept: String = "application/json"
    ): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("User-Agent", WEB_UA)
            .header("Version", FE_VERSION)
            .header("source", SOURCE)
            .header("Timezone", timezoneHeader())
            .header("x-request-id", UUID.randomUUID().toString())
        if (!token.isNullOrBlank()) b.header("authorization", "Bearer $token")
        return b
    }

    /** 浏览器同源 POST 会带 Origin/Referer，一并补上更贴近真实前端。 */
    private fun withOrigin(b: Request.Builder): Request.Builder = b
        .header("Origin", BASE)
        .header("Referer", "$BASE/")

    // ------------------------------------------------------------------ 登录

    /**
     * 邮箱 + 密码直接登录（不走网页）：
     * POST /api/v1/auths/signin {"email","password"} → 200 {"token": "..."}。
     * 成功返回 Bearer token；失败抛 IOException（含服务端 detail 消息）。
     */
    suspend fun signin(email: String, password: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .toString()
        val req = withOrigin(webRequest(SIGNIN, null))
            .header("Content-Type", "application/json")
            .header("x-request-origin", BASE)
            .post(body.toRequestBody(jsonMedia))
            .build()
        apiClient.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            lastUpstreamRaw = raw
            lastUpstreamMeta = "signin HTTP ${r.code} ${r.header("Content-Type").orEmpty()}"
            if (r.code == 401 || r.code == 403) {
                throw IOException("邮箱或密码错误（HTTP ${r.code}）")
            }
            classifyQwenBlock(r.code, r.header("Content-Type").orEmpty(), raw)?.let { throw it }
            if (!r.isSuccessful) throw IOException("登录失败: HTTP ${r.code} ${raw.take(200)}")
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw IOException("登录响应不是 JSON（原始返回：${raw.take(200)}）")
            }
            // FastAPI 错误形态：{"detail": ...}
            val detail = json.opt("detail")
            if (detail != null && json.optString("token").isBlank()) {
                throw IOException("登录被拒：${jsonDetailText(detail).take(200)}")
            }
            // Open WebUI 系返回形态：顶层 token / {user:{token}} / {data:{token}} / {access_token}
            val token = json.optString("token").takeIf { it.isNotBlank() }
                ?: json.optJSONObject("user")?.optString("token")?.takeIf { it.isNotBlank() }
                ?: json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotBlank() }
                ?: json.optString("access_token").takeIf { it.isNotBlank() }
            token ?: throw IOException("登录成功但未返回 token（原始返回：${raw.take(200)}）")
        }
    }

    // ------------------------------------------------------------------ Token 续期

    /** 续期结果：新 token + 过期时间 + 上游原始返回（诊断用）+ 轮换后的 Cookie 串。 */
    data class RefreshedToken(
        val token: String,
        val expiresAt: Long,
        val cookies: String? = null,
        val raw: String = ""
    )

    /**
     * 用**账号自己的 Cookie** 换一个新的 access_token（网页端同款续期）。
     *
     * @param cookies 该账号登录时抓到的 Cookie 串（必须包含 auth 域的 `token` cookie）
     * @return 新 token 与过期时间；失败抛 [IOException]，消息里带服务端 code/details
     */
    suspend fun refreshToken(cookies: String, authBase: String = AUTH_BASE): RefreshedToken =
        withContext(Dispatchers.IO) {
            require(cookies.isNotBlank()) { "该账号没有存 Cookie，无法自动续期（请重新登录一次）" }
            val url = "$authBase/api/v2/auths/refresh"
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("User-Agent", WEB_UA)
                .header("Version", FE_VERSION)
                .header("source", SOURCE)
                .header("Timezone", timezoneHeader())
                .header("x-request-id", UUID.randomUUID().toString())
                // 这一条是硬要求：缺了会被回 Bad_Request / Missing origin
                .header("x-request-origin", BASE)
                .header("Origin", BASE)
                .header("Referer", "$BASE/")
                .header("Cookie", cookies)
                .get()
                .build()
            refreshClient.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                lastUpstreamRaw = raw
                lastUpstreamMeta = "refresh HTTP ${r.code} (${authBase})"
                if (r.code == 404) throw IOException("续期接口不可用（HTTP 404，host=$authBase）")
                val json = runCatching { JSONObject(raw) }.getOrElse {
                    throw IOException("续期响应不是 JSON（HTTP ${r.code}）；原始：${raw.take(200)}")
                }
                if (!json.optBoolean("success", false)) {
                    val d = json.optJSONObject("data")
                    val code = d?.optString("code").orEmpty().ifBlank { "ERR_REFRESH_FAILED" }
                    val msg = d?.optString("details").orEmpty().ifBlank { "续期失败" }
                    throw IOException("续期失败：$msg（$code）")
                }
                val data = json.optJSONObject("data") ?: throw IOException("续期响应缺少 data")
                val token = data.optString("access_token")
                    .takeIf { it.isNotBlank() }
                    ?: data.optString("token").takeIf { it.isNotBlank() }
                    ?: throw IOException("续期成功但没拿到 access_token（原始：${raw.take(200)}）")
                // expires_at 是秒；也兼容毫秒写法
                val expRaw = data.optLong("expires_at", 0L)
                val expiresAt = when {
                    expRaw > 10_000_000_000L -> expRaw
                    expRaw > 0L -> expRaw * 1000L
                    else -> parseJwtExpiryMs(token)
                }
                RefreshedToken(
                    token = token,
                    expiresAt = expiresAt,
                    cookies = mergeCookies(cookies, r.headers("Set-Cookie")),
                    raw = raw.take(300)
                )
            }
        }

    /** 先试 `auth.qwen.ai`，失败退 `auth.qwen.com`（海外站点）。 */
    suspend fun refreshTokenAuto(cookies: String): RefreshedToken {
        val first = runCatching { refreshToken(cookies, AUTH_BASE) }
        first.getOrNull()?.let { return it }
        val e1 = first.exceptionOrNull()
        val second = runCatching { refreshToken(cookies, AUTH_BASE_COM) }
        return second.getOrElse { throw (e1 ?: it) }
    }

    /** 从 JWT 的 `exp` 声明解析过期时间（毫秒）；解析失败按 15 分钟兜底。 */
    fun parseJwtExpiryMs(token: String): Long =
        parseJwtExpiryMsStatic(token).takeIf { it > 0L }
            ?: (System.currentTimeMillis() + 15 * 60_000L)

    /** 把响应 Set-Cookie 合并进原 Cookie 串（同名覆盖）。 */
    private fun mergeCookies(old: String, setCookies: List<String>): String? {
        if (setCookies.isEmpty()) return null
        val map = LinkedHashMap<String, String>()
        old.split(';').forEach { seg ->
            val s = seg.trim()
            val i = s.indexOf('=')
            if (i > 0) map[s.substring(0, i)] = s.substring(i + 1)
        }
        setCookies.forEach { sc ->
            val pair = sc.substringBefore(';').trim()
            val i = pair.indexOf('=')
            if (i > 0) map[pair.substring(0, i)] = pair.substring(i + 1)
        }
        return if (map.isEmpty()) null else map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 把 FastAPI 的 detail（字符串或数组）转成可读文案。 */
    private fun jsonDetailText(detail: Any?): String = when (detail) {
        null -> "未知错误"
        is String -> detail
        is JSONArray -> (0 until detail.length()).joinToString("；") { i ->
            val o = detail.optJSONObject(i)
            o?.optString("msg").orEmpty().ifBlank { o?.toString().orEmpty() }
        }

        else -> detail.toString()
    }

    /** 校验登录态：/api/v1/auths/ 返回 200 视为有效（无 token 时该端点 401）。 */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = webRequest(AUTHS, token).get().build()
            apiClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** 拉取当前用户信息（邮箱/昵称），用于账号列表展示。 */
    suspend fun fetchUserInfo(token: String): UserInfo = withContext(Dispatchers.IO) {
        val req = webRequest(AUTHS, token).get().build()
        apiClient.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            lastUpstreamRaw = raw
            if (!r.isSuccessful) throw IOException("校验登录态失败: HTTP ${r.code}")
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw IOException("响应不是 JSON（原始返回：${raw.take(200)}）")
            }
            // 兼容 {user:{...}} / {data:{...}} / 直接是用户对象
            val user = json.optJSONObject("user")
                ?: json.optJSONObject("data")?.optJSONObject("user")
                ?: json.optJSONObject("data")
                ?: json
            UserInfo(
                email = user.optString("email").takeIf { it.isNotBlank() },
                name = (user.optString("name").takeIf { it.isNotBlank() }
                    ?: user.optString("nickname").takeIf { it.isNotBlank() })
            )
        }
    }

    // ------------------------------------------------------------------ 模型清单

    /**
     * 拉取网页端真实可用模型清单（GET /api/v2/models，**免鉴权**）。
     * 返回值形如 ["qwen3.8-max","qwen3.7-plus","qwen3.8-omni-flash"]。
     */
    suspend fun fetchModels(): List<String> = withContext(Dispatchers.IO) {
        val req = webRequest(MODELS, null).get().build()
        apiClient.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            lastUpstreamRaw = raw
            if (!r.isSuccessful) throw IOException("拉取模型清单失败: HTTP ${r.code}")
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw IOException("模型清单不是 JSON（原始返回：${raw.take(200)}）")
            }
            val arr = json.optJSONObject("data")?.optJSONArray("data")
                ?: json.optJSONArray("data")
                ?: json.optJSONArray("models")
            val out = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val info = o.optJSONObject("info")
                    if (info != null && !info.optBoolean("is_active", true)) continue
                    if (!out.contains(id)) out.add(id)
                }
            }
            if (out.isEmpty()) throw IOException("模型清单为空（原始返回：${raw.take(200)}）")
            storeModels(out)
            out
        }
    }

    /** 带 TTL 缓存的模型清单：优先内存缓存，过期或强制时重新拉，失败回退静态清单。 */
    suspend fun models(forceRefresh: Boolean = false): List<String> {
        val fresh = !forceRefresh &&
            modelsCache.isNotEmpty() &&
            System.currentTimeMillis() - modelsFetchedAt < MODELS_TTL_MS
        if (fresh) return modelsCache
        return runCatching { fetchModels() }.getOrElse { modelsCache.ifEmpty { MODEL_IDS } }
    }

    // ------------------------------------------------------------------ 建会话

    /**
     * 创建网页会话（官方前端 `POST /api/v2/chats/new`）。
     *
     * **不能省这一步**：对话接口不会自动建会话，直接拿随机 UUID 去补全必定被回
     * `CHAT_NOT_FOUND（此对话已删除）`。官方前端拿到 `data.id` 后才用它调补全。
     * 失败返回 null（调用方退回随机 UUID 兜底）。
     */
    suspend fun createChat(token: String, model: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("chatId", "")
                .put("models", JSONArray().put(model))
                .put("timestamp", System.currentTimeMillis())
                .put("chat_type", "t2t")
                .put("chat_mode", "normal")
                .toString()
            val req = withOrigin(webRequest("$API_V2/chats/new", token))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .post(body.toRequestBody(jsonMedia))
                .build()
            apiClient.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                lastUpstreamRaw = raw
                lastUpstreamMeta = "chats/new HTTP ${r.code}"
                JSONObject(raw).optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 浏览器通道

    /**
     * 尝试走 WebView 真浏览器栈（[QwenBrowser]）。
     * 返回 null = 通道不可用（开关关闭 / 页面未登录 / WebView 未就绪），
     * 调用方应回退到 [chatStream] 的 OkHttp 通道。
     */
    suspend fun browserFlow(
        token: String,
        model: String,
        prompt: String,
        chatId: String,
        parentId: String?,
        thinkingEnabled: Boolean,
        contextTail: String?,
        onChatId: (String) -> Unit = {},
        onResponseId: (String?) -> Unit = {},
        onThinking: (String) -> Unit = {}
    ): Flow<String>? {
        if (!useBrowser) return null
        val b = browser ?: return null
        // 页面从未加载过 → 直接交给调用方回退，不在这里干等
        if (!b.isLoaded()) return null
        if (!b.ensureReady(8_000)) return null
        // 用调用方给的 token（页面登录态 或 号池账号皆可）——
        // 页面只负责提供「真指纹 + WAF 通行 cookie」，业务身份不必等于页面登录身份。
        return b.chatStream(
            token = token,
            model = model,
            prompt = prompt,
            chatId = chatId,
            parentId = parentId,
            thinkingEnabled = thinkingEnabled,
            contextTail = contextTail,
            onChatId = onChatId,
            onResponseId = onResponseId,
            onThinking = onThinking
        )
    }

    /** 浏览器通道是否已就绪（开关打开 + 页面已登录）。 */
    fun browserReady(): Boolean = useBrowser && !browser?.cachedToken().isNullOrBlank()

    /** 浏览器通道状态文案（UI 展示）。 */
    fun browserStatus(): String {
        val b = browser ?: return "不可用"
        val err = b.errorText()
        return when {
            b.isStuck() -> "渲染异常：可点「重载」或「诊断」" +
                if (!err.isNullOrBlank()) "（${err.take(60)}）" else ""
            !b.isLoaded() -> "加载中" +
                if (b.pageProgress() in 0..99) "（${b.pageProgress()}%）" else "…"
            b.cachedToken().isNullOrBlank() -> "页面已加载，等待登录"
            else -> "就绪（已登录，Token 可用）"
        }
    }

    // ------------------------------------------------------------------ 对话（OkHttp）

    /**
     * 流式对话（OkHttp 直连通道）。
     *
     * @param chatId Qwen 网页会话 id；null/空 = 新建（客户端生成 UUID，上游会自动建会话）
     * @param parentId 上一条回复的 response_id（会话分支链），null/空 = 从头开始
     * @param onChatId 回调会话 id（`response.created` 首帧）
     * @param onResponseId 回调本条回复的 response_id（下一条消息作 parent）
     * @param onThinking 思考内容（phase=think/thinking_summary）单独回调，不进正文
     */
    suspend fun chatStream(
        token: String,
        model: String,
        prompt: String,
        chatId: String?,
        parentId: String?,
        thinkingEnabled: Boolean,
        contextTail: String? = null,
        onChatId: (String) -> Unit = {},
        onResponseId: (String?) -> Unit = {},
        onThinking: (String) -> Unit = {}
    ): Flow<String> {
        val cid = chatId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        var body = qwenChatBody(model, prompt, cid, parentId, thinkingEnabled, contextTail, slim = false)
        var response = withContext(Dispatchers.IO) {
            streamingClient.newCall(buildChatRequest(token, cid, body)).execute()
        }
        if (!response.isSuccessful && response.code == 400) {
            // 400 回退：去掉非必需字段（research_mode / auto_search）重试一次。
            response.close()
            body = qwenChatBody(model, prompt, cid, parentId, thinkingEnabled, contextTail, slim = true)
            response = withContext(Dispatchers.IO) {
                streamingClient.newCall(buildChatRequest(token, cid, body)).execute()
            }
        }
        val ctype = response.header("Content-Type").orEmpty()
        lastUpstreamMeta = "HTTP ${response.code} ${ctype.ifBlank { "(无)" }}"
        if (!response.isSuccessful) {
            val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            lastUpstreamRaw = raw
            val code = response.code
            response.close()
            throw classifyQwenBlock(code, ctype, raw) ?: upstreamError(code, raw)
        }
        // 关键：上游被风控/网关接管时会返回 **HTTP 200 + 非 SSE 正文**。
        // 旧实现只在流里找 `data:` 行 → 找不到就静默吐出空回复（「返回空气」）。
        if (!ctype.contains("text/event-stream", ignoreCase = true)) {
            val raw = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            lastUpstreamRaw = raw
            response.close()
            throw classifyQwenBlock(response.code, ctype, raw)
                ?: IOException("Qwen 上游未返回流式响应（HTTP ${response.code}, Content-Type: $ctype）：${raw.take(300)}")
        }
        val resp = response
        return flow { emitAll(parseStream(resp, onChatId, onResponseId, onThinking)) }
            .flowOn(Dispatchers.IO)
    }

    /** 官方前端 URL：`/api/v2/chat/completions?chat_id=<id>`；chat_id 同时放在 body。 */
    private fun buildChatRequest(token: String, cid: String, body: JSONObject): Request =
        withOrigin(webRequest(qwenChatUrl(cid), token))
            .header("Content-Type", "application/json")
            .header("X-Request-Id", UUID.randomUUID().toString())
            .header("X-Accel-Buffering", "no")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

    // ------------------------------------------------------------------ 流解析

    /**
     * 解析 SSE 流。切帧逻辑（`\n\n` 分组 + 多行 `data:` 拼接）与官方前端 `aK()` 一致，
     * 帧语义交给 [QwenStreamParser]（与浏览器通道共用）。
     */
    private fun parseStream(
        response: okhttp3.Response,
        onChatId: (String) -> Unit,
        onResponseId: (String?) -> Unit,
        onThinking: (String) -> Unit
    ): Flow<String> = flow {
        val parser = QwenStreamParser(onChatId, onResponseId, onThinking)
        val body = response.body ?: throw IOException("Qwen 上游返回空响应体")
        body.byteStream().use { input ->
            val reader = input.bufferedReader(Charsets.UTF_8)
            val dataBuf = StringBuilder()

            suspend fun flush() {
                val payload = dataBuf.toString().trim()
                dataBuf.setLength(0)
                if (payload.isEmpty()) return
                parser.feed(payload).forEach { emit(it) }
            }

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    flush()
                    if (parser.stopped) break
                    continue
                }
                if (line.startsWith(":")) continue          // SSE 注释/心跳
                if (!line.startsWith("data:")) continue
                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                dataBuf.append(line.removePrefix("data:").trimStart())
            }
            if (!parser.stopped) flush()                     // 末帧无空行收尾
        }
        parser.assertProduced()
    }

    private fun upstreamError(code: Int, raw: String): IOException {
        val detail = runCatching {
            val o = JSONObject(raw)
            o.optString("message").ifBlank { o.optString("error") }
                .ifBlank { o.optJSONObject("error")?.optString("details").orEmpty() }
                .ifBlank { o.optJSONObject("error")?.optString("code").orEmpty() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val msg = when {
            code == 401 -> "Qwen 登录态已失效（HTTP 401），请重新登录该账号"
            code == 403 -> "Qwen 拒绝访问（HTTP 403），账号可能被封禁或需要重新登录"
            code == 429 -> "Qwen 上游限流（HTTP 429）${detail?.let { "：$it" } ?: ""}"
            else -> "Qwen 上游错误 HTTP $code${detail?.let { "：$it" } ?: " ${raw.take(200)}"}"
        }
        return IOException(msg)
    }
}
