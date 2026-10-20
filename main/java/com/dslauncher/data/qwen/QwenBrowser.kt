package com.dslauncher.data.qwen

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Qwen 网页端「真浏览器栈」传输层（SuperAPI 同款思路：真的用浏览器发请求）。
 *
 * ## 为什么需要它
 * chat.qwen.ai 的对话 POST 后面挂着阿里云 WAF，风控**按客户端指纹分级**（同一出口 IP 实测）：
 * ```
 * 裸 curl / OkHttp(Conscrypt TLS)  -> 200 + punish JSON(FAIL_SYS_USER_VALIDATE) 或直接 tarpit 挂死
 * Chrome TLS+HTTP2 指纹            -> 200 + 滑块验证页(aliyun_waf_aa / AliyunCaptcha.js)
 * 真浏览器(过验证码后带通行 cookie) -> 正常 SSE
 * ```
 * 所以模拟请求头/体（[QwenWebClient] 的 OkHttp 通道）**是必要但不充分的**：
 * WAF 要的是真浏览器。APK 里开不了 Chrome，但 **WebView 就是真 Chromium 栈**。
 *
 * ## 做法
 * 1. WebView 载入 `chat.qwen.ai`，用户登录一次（弹滑块就滑一下）→ WAF 通行 cookie 落地；
 * 2. 之后每轮对话都由**页面内的 `fetch()`** 发出（真指纹 + `credentials:'include'` 自动带 cookie）；
 * 3. SSE 帧经 `@JavascriptInterface` 回流，交给 [QwenStreamParser] 解析（与 OkHttp 通道同一套）。
 *
 * 全程不读页面 DOM、不依赖前端内部结构，只借它的网络栈，所以前端改版不会失效。
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class QwenBrowser(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Channel<String>>()
    private val ensureMutex = Mutex()

    /** 非流式 JSON 调用的等待位（建会话等）。 */
    private val jsonCalls = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Pair<Int, String>>>()

    @Volatile
    private var webRef: WebView? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var token: String? = null

    @Volatile
    private var lastError: String? = null

    /** 页面加载进度（0-100，-1 = 未知），UI 展示用。 */
    @Volatile
    private var progressValue: Int = -1

    /** 页面是否卡在启动图/未渲染成功（黑屏）。 */
    @Volatile
    private var pageStuck = false

    /** 页面 token 变化回调（old -> new）：登录/续期后同步回账号池。 */
    @Volatile
    var onTokenChanged: ((String?, String) -> Unit)? = null

    /** 状态变化回调（加载完成/出错/卡住时触发，UI 据此刷新）。 */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tokenWatchJob: Job? = null

    /** 最近一次创建 WebView 用的 Context；渲染进程崩溃后自动重建靠它。 */
    @Volatile
    private var lastCtx: Context? = null

    /** 页面 UA 形态：false = 移动版（默认，对齐 base-2.apk 的可加载配置），true = 桌面版。 */
    @Volatile
    // base-2.apk 同设备实测：移动版 UA + STEALTH_JS 注入可正常渲染登录页。
    // 桌面版 UA 作为备选，仍可通过登录面板右上角切换。
    private var desktopUa = false

    /** 当前是否桌面版 UA。 */
    fun isDesktopUa(): Boolean = desktopUa

    /**
     * 切换页面 UA 并重载。
     * 站点把页面降级成「打开 Qwen 官方应用程序」引导页时，切桌面版通常能拿到完整网页；
     * 反之桌面版在部分设备上输入框有 IME 兼容问题，可再切回移动版。
     */
    fun setDesktopUa(desktop: Boolean) {
        if (desktopUa == desktop) return
        desktopUa = desktop
        runOnMain {
            val wv = webRef ?: return@runOnMain
            runCatching {
                wv.settings.userAgentString = if (desktop) UA_DESKTOP else UA_MOBILE
                loaded = false
                pageStuck = false
                progressValue = -1
                token = null
                wv.loadUrl(QwenWebClient.BASE + "/")
            }
        }
    }

    /** 供登录 UI 嵌入的 WebView（与运行时同一个实例，登录态互通）。 */
    fun view(): WebView? = webRef

    /** 页面是否已载入过（首次载入后一直为 true）。 */
    fun isLoaded(): Boolean = loaded

    /** 缓存的登录 token（页面登录态；新版前端在 localStorage `qwen_access_token_state`）。 */
    fun cachedToken(): String? = token

    /** 最近一次错误（UI 展示）。 */
    fun errorText(): String? = lastError

    /** 当前页面加载进度（0-100，-1 未知）。 */
    fun pageProgress(): Int = progressValue

    /** 页面是否卡在启动图/未渲染成功（黑屏兜底用）。 */
    fun isStuck(): Boolean = pageStuck

    /** 系统 WebView 引擎版本（诊断用，黑屏问题多半与它有关）。 */
    fun webViewEngine(): String = runCatching {
        WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "未知"
    }.getOrDefault("未知")

    /** 主线程上创建 WebView（幂等）。渲染进程崩溃后 webRef 会置空，可再次调用重建。 */
    fun ensureCreated(ctx: Context? = null) {
        ctx?.let { lastCtx = it }
        if (webRef != null) return
        val use = ctx ?: lastCtx ?: appContext
        runOnMain { if (webRef == null) createLocked(use) }
    }

    /**
     * 换宿主（独立登录 Activity）时重建 WebView：
     * 旧实例绑定的是旧 Activity 的窗口令牌，跨窗口复用正是「加载后不再绘制」的高发原因；
     * Cookie / localStorage 落在 WebView 的全局数据目录里，重建后登录态自动延续。
     */
    fun resetForActivity(ctx: Context) {
        lastCtx = ctx
        runOnMain {
            val old = webRef
            if (old != null) {
                runCatching { (old.parent as? android.view.ViewGroup)?.removeView(old) }
                runCatching { old.stopLoading() }
                runCatching { old.destroy() }
            }
            webRef = null
            loaded = false
            pageStuck = false
            progressValue = -1
            token = null
            createLocked(ctx)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun createLocked(ctx: Context) {
        val wv = runCatching { WebView(ctx) }.getOrElse {
            lastError = "WebView 初始化失败：${it.message}"
            return
        }
        // 登录/WAF 通行 cookie 必须持久化；第三方 cookie 也放行（登录跳转链路会用到）。
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
        // WebView 默认背景可能透明；外层 windowBackground 是纯黑，首帧前会显示成「黑屏」。
        wv.setBackgroundColor(android.graphics.Color.WHITE)
        // 与 base-2.apk 一致：可聚焦 + 触摸抢焦点（否则输入框/滑块交互异常）。
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.isClickable = true
        wv.requestFocus(130)
        wv.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                event.action == android.view.MotionEvent.ACTION_UP
            ) {
                v.requestFocus()
            }
            false
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            // ↓ 与 base-2.apk 的可用配置对齐：支持弹窗、混合内容、文件/内容访问、默认缓存策略。
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            textZoom = 100
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // 覆写 UA：WebView 默认 UA 里带 "; wv)" 标记，chat.qwen.ai 会据此判定
            // 「App 内嵌浏览器」，只回一个「打开 Qwen 官方应用程序」的引导页，不给登录入口。
            userAgentString = if (desktopUa) UA_DESKTOP else UA_MOBILE
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                // 新文档开始：重置进度与卡死标记
                progressValue = 0
                pageStuck = false
                // 尽早补上现代 JS 特性...
                injectPolyfill(view)
                // 反 WebView 特征探测（base-2.apk 同款）：漏了它 Qwen 前端可能拒绝渲染。
                injectStealth(view)
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // 主文档首个可见帧：比 onPageFinished 早 —— 页面一旦开始画，就算「已加载」，
                // 绝不再用任何遮罩盖住它（低配机上首屏可能要 10-20s，遮罩就是「黑屏」元凶）。
                loaded = true
                progressValue = 100
                injectPolyfill(view)
                injectStealth(view)
                injectDsq(view)
                runCatching {
                    view?.requestLayout()
                    view?.invalidate()
                }
                onStateChanged?.invoke()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loaded = true
                progressValue = 100
                injectPolyfill(view)
                injectStealth(view)
                injectDsq(view)
                readTokenNow()
                // 强制重绘：GPU 合成层丢失时内容不刷新（现象就是整块黑屏）
                runCatching {
                    view?.requestLayout()
                    view?.invalidate()
                }
                onStateChanged?.invoke()
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                // 渲染进程崩溃：默认行为会连带杀掉 App。这里自己兜住并销毁；
                // 登录面板里的状态轮询会再次 ensureCreated() 自动重建一个 WebView。
                lastError = "WebView 渲染进程崩溃（didCrash=${detail?.didCrash()}），已准备重建"
                pageStuck = true
                runOnMain {
                    runCatching { (view?.parent as? android.view.ViewGroup)?.removeView(view) }
                    runCatching { view?.destroy() }
                    if (webRef === view) {
                        webRef = null
                        loaded = false
                        token = null
                    }
                    onStateChanged?.invoke()
                }
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // 只记主文档错误，子资源失败忽略
                if (request?.isForMainFrame == true) {
                    lastError = "页面加载失败：${error?.description ?: error?.errorCode}"
                    pageStuck = true
                    onStateChanged?.invoke()
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // 与 base-2.apk 一致：目标 _blank 的登录/验证弹窗仍复用同一个 WebView，
                // 否则弹出窗口没有承载者，登录跳转直接停住。
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressValue = newProgress
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                // 前端启动失败时控制台错误是唯一线索：把 error 记进 lastError 供 UI 诊断。
                if (msg?.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR &&
                    !msg.message().isNullOrBlank()
                ) {
                    lastError = "前端控制台错误：" + msg.message().take(300)
                }
                return true
            }
        }
        wv.addJavascriptInterface(Bridge(), "DSLQ")
        webRef = wv
        applyRenderMode(wv)
        startTokenWatch()
        runCatching {
            wv.loadUrl(QwenWebClient.BASE + "/")
            wv.onResume()
            wv.resumeTimers()
        }
    }

    /** 应用渲染模式：软渲用 SOFTWARE；关掉时用 NONE（回到系统默认硬件路径，不再强制离屏层）。 */
    private fun applyRenderMode(wv: WebView) {
        runCatching {
            wv.setLayerType(
                if (softwareRender) android.view.View.LAYER_TYPE_SOFTWARE
                else android.view.View.LAYER_TYPE_NONE,
                null
            )
        }
    }

    // ---------------------------------------------------------------- 注入脚本

    private fun injectDsq(view: WebView?) {
        if (view == null) return
        runCatching { view.evaluateJavascript(DSQ_JS, null) }
    }

    /** 注入现代 JS 特性垫片（幂等、无副作用）。 */
    private fun injectPolyfill(view: WebView?) {
        if (view == null) return
        runCatching { view.evaluateJavascript(POLYFILL_JS, null) }
    }

    /** 反 WebView 特征探测（对齐 base-2.apk 的 Humanizer.STEALTH_JS，幂等）。 */
    private fun injectStealth(view: WebView?) {
        if (view == null) return
        runCatching { view.evaluateJavascript(STEALTH_JS, null) }
    }

    // ---------------------------------------------------------------- 非流式 JSON

    /**
     * 用页面内的 fetch 发一个「要完整响应」的 JSON 请求（非流式）。
     * 返回 (HTTP 状态码, 响应正文)；-1 表示失败/超时。
     */
    suspend fun postJson(path: String, bodyJson: String, token: String?): Pair<Int, String> {
        ensureCreated()
        val id = UUID.randomUUID().toString()
        val slot = kotlinx.coroutines.CompletableDeferred<Pair<Int, String>>()
        jsonCalls[id] = slot
        try {
            injectDsq(webRef)
            val headers = JSONObject()
                .put("Version", QwenWebClient.FE_VERSION)
                .put("source", QwenWebClient.SOURCE)
                .put("X-Request-Id", UUID.randomUUID().toString())
                .put("Origin", QwenWebClient.BASE)
                .put("Referer", QwenWebClient.BASE + "/")
                .apply { if (!token.isNullOrBlank()) put("Authorization", "Bearer $token") }
                .toString()
            val script = "window.__dsq && window.__dsq.fetchJson(" +
                JSONObject.quote(id) + "," +
                JSONObject.quote(QwenWebClient.BASE + path) + "," +
                JSONObject.quote(bodyJson) + "," +
                JSONObject.quote(headers) + "); window.__dsq ? 'ok' : 'missing'"
            val res = evalJs(script, 15_000)
            if (res == null || res.contains("missing")) return -2 to "页面未就绪"
            return withTimeoutOrNull(30_000) { slot.await() } ?: (-1 to "timeout")
        } finally {
            jsonCalls.remove(id)
        }
    }

    /**
     * 让页面替我们**创建会话**（官方前端 `POST /api/v2/chats/new`）。
     *
     * 这一步不能省：对话接口不会自动建会话，直接拿随机 UUID 去补全必定被回
     * `CHAT_NOT_FOUND（此对话已删除）`。
     *
     * 请求体取自官方前端 `ChatSender.createChat`：
     * `{chatId:"", models:[..], timestamp:Date.now(), chat_type:"t2t", chat_mode:"normal"}`
     * 返回体为 v2 信封：`{"success":true,"data":{"id":"<chat_id>"}}`
     */
    suspend fun createChat(token: String?, models: List<String>, chatType: String = "t2t"): String? {
        val body = JSONObject()
            .put("chatId", "")
            .put("models", org.json.JSONArray().apply { models.forEach { put(it) } })
            .put("timestamp", System.currentTimeMillis())
            .put("chat_type", chatType)
            .put("chat_mode", "normal")
            .toString()
        val (code, text) = postJson("/api/v2/chats/new", body, token)
        if (code !in 200..299) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return o.optJSONObject("data")?.optString("id")?.takeIf { it.isNotBlank() }
    }

    /**
     * 软件渲染开关。
     *
     * 默认 **关闭**（走系统默认硬件渲染）：部分 MTK/GPU 驱动上强制 SOFTWARE 层反而会
     * 让合成层丢失、整块黑屏；之前默认开软渲仍未解决黑屏，说明问题多半不在 GPU 路径。
     * 真遇到「页面正常一会后整块变黑」的设备，可在登录面板点「软渲」切回来。
     */
    @Volatile
    private var softwareRender = false

    /** 当前是否软件渲染。 */
    fun isSoftwareRender(): Boolean = softwareRender

    /**
     * 切换 WebView 渲染模式并重载。
     * 现象：页面短暂正常后整块变黑（DOM 在、JS 活着，就是画不出来）——
     * 常见于 MediaTek GPU 驱动 + 硬件合成层丢失，切软件渲染能绕过。
     */
    fun setSoftwareRender(on: Boolean) {
        softwareRender = on
        runOnMain {
            val wv = webRef ?: return@runOnMain
            runCatching {
                applyRenderMode(wv)
                loaded = false
                pageStuck = false
                progressValue = -1
                wv.reload()
            }
        }
    }

    /** 重新加载页面（黑屏兜底按钮）。 */
    fun reload() {
        runOnMain {
            val wv = webRef ?: return@runOnMain
            runCatching {
                loaded = false
                pageStuck = false
                progressValue = -1
                wv.reload()
            }
        }
    }

    /** 清除页面登录态（换号用）：清 localStorage/WebStorage + cookie 后重载。 */
    fun clearLoginState() {
        runOnMain {
            val wv = webRef ?: return@runOnMain
            runCatching {
                wv.evaluateJavascript(
                    "try{localStorage.removeItem('token');" +
                        "localStorage.removeItem('qwen_access_token_state');" +
                        "localStorage.removeItem('at_expire_time');" +
                        "sessionStorage.clear();}catch(e){}",
                    null
                )
            }
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
            runCatching { CookieManager.getInstance().flush() }
            runCatching { WebStorage.getInstance().deleteAllData() }
            token = null
            loaded = false
            pageStuck = false
            progressValue = -1
            wv.loadUrl(QwenWebClient.BASE + "/")
        }
    }

    /** 页面登录态快照。 */
    data class TokenSnapshot(
        val token: String?,
        val source: String = "",
        val editing: Boolean = false,
        val keys: List<String> = emptyList(),
        val href: String = "",
        /** access_token 过期时间戳（毫秒）；0 = 未知。 */
        val expiresAt: Long = 0L
    )

    /** 读取页面当前 token 快照（兼容新版 `qwen_access_token_state` 与旧版 `token`）。 */
    suspend fun snapshot(): TokenSnapshot? {
        val raw = evalJs(READ_TOKEN_JS, 5_000) ?: return null
        val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        val o = runCatching { JSONObject(inner) }.getOrNull() ?: return null
        val keys = runCatching {
            val arr = o.optJSONArray("keys")
            (0 until (arr?.length() ?: 0)).map { i -> arr!!.optString(i) }
        }.getOrDefault(emptyList())
        return TokenSnapshot(
            token = o.optString("token").takeIf { it.isNotBlank() },
            source = o.optString("source"),
            editing = o.optBoolean("editing", false),
            keys = keys,
            href = o.optString("href"),
            expiresAt = o.optLong("expiresAt", 0L)
        )
    }

    /**
     * 当前 WebView 里 chat.qwen.ai / auth.qwen.ai 域的 Cookie 串。
     *
     * 自动续期的钥匙：web 端 `/auths/refresh` 靠 `token` cookie 鉴权。
     * 登录成功时把它随账号一起入库，之后就能在 token 到期前自动换新。
     *
     * 注意：部分 WebView 实现要求 CookieManager 在**有 Looper 的线程**上调用，
     * 所以这里若在后台线程会切到主线程执行（带超时，避免死等）。
     */
    fun cookieString(): String = runCatching {
        val task = java.util.concurrent.FutureTask {
            val cm = CookieManager.getInstance()
            listOf(
                QwenWebClient.BASE,
                QwenWebClient.AUTH_BASE,
                QwenWebClient.AUTH_BASE_COM,
                "https://qwen.ai"
            ).mapNotNull { cm.getCookie(it) }
                .flatMap { it.split(';') }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.substringBefore('=') }
                .joinToString("; ")
        }
        if (Looper.myLooper() == null) {
            main.post(task)
            task.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } else {
            task.run()
            task.get()
        }
    }.getOrDefault("")

    /** 页面里读一次 token 的过期时间（localStorage `qwen_access_token_state.expiresAt`）。 */
    suspend fun readExpiry(): Long = snapshot()?.expiresAt ?: 0L

    /**
     * 在页面里调一次官方续期接口（真 Chromium 栈 + 页面自己的 Cookie），
     * 成功则把新 token **写回 localStorage**（前端下次刷新页面也是登录态）。
     *
     * 为什么要走页面而不是 OkHttp：
     * 1. WAF 只放行真浏览器指纹，OkHttp（Java TLS）可能被直接 tarpit/punish；
     * 2. 页面里 `credentials:'include'` 天然带上 httpOnly 的 `token` cookie，
     *    不需要我们去碰 HttpOnly 拿不到的那部分。
     */
    suspend fun refreshInPage(timeoutMs: Long = 20_000): QwenWebClient.RefreshedToken? {
        ensureCreated()
        if (webRef == null || !loaded) return null
        if (evalJs(REFRESH_JS, 8_000) == null) {
            lastError = "页面续期脚本注入失败"
            return null
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val raw = evalJs("window.__qwenRefresh || ''", 5_000) ?: continue
            val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: continue
            if (inner.isBlank()) continue
            val o = runCatching { JSONObject(inner) }.getOrNull() ?: continue
            if (!o.optBoolean("ok", false)) {
                val why = o.optString("details")
                    .ifBlank { o.optString("code").ifBlank { o.optString("error").ifBlank { "未知" } } }
                lastError = "页面续期被拒：$why"
                return null
            }
            val t = o.optString("token").takeIf { it.isNotBlank() } ?: return null
            val old = token
            token = t
            runCatching { if (old != t) onTokenChanged?.invoke(old, t) }
            return QwenWebClient.RefreshedToken(
                token = t,
                expiresAt = o.optLong("expiresAt", 0L),
                cookies = cookieString(),
                raw = o.optString("raw")
            )
        }
        lastError = "页面续期超时"
        return null
    }

    /** 读取页面当前 token（并刷新缓存），供实时监听与手动读取共用。 */
    suspend fun readStoredToken(): String? =
        snapshot()?.token?.also { token = it }

    /**
     * 页面 token 实时监听（登录/续期都覆盖）：
     * - 登录成功后，token 一旦出现就回写 [token] 并通知 [onTokenChanged] → 账号池可实时收录；
     * - 前端 access_token 续期（`/auths/refresh`）后，页面存储里的 token 会变化，同样会被同步。
     */
    private fun startTokenWatch() {
        if (tokenWatchJob?.isActive == true) return
        tokenWatchJob = scope.launch {
            while (isActive) {
                // 5s 一次即可：低配机上高频 evaluateJavascript 反而拖慢首屏渲染。
                delay(5_000)
                if (webRef == null || !loaded) continue
                val t = runCatching { snapshot() }.getOrNull()?.token ?: continue
                if (t.isNotBlank() && t != token) {
                    val old = token
                    token = t
                    runCatching { onTokenChanged?.invoke(old, t) }
                    onStateChanged?.invoke()
                }
            }
        }
    }

    /**
     * 页面自检：UA / 现代特性 / 前端是否愿意加载 SPA / 是否命中「低版本浏览器」引导页。
     * 专治「浏览器里只显示『打开 Qwen 官方应用程序』」这类问题 —— 直接看页面自己怎么判的。
     */
    suspend fun probe(): String {
        ensureCreated()
        val raw = evalJs("""
(function () {
  try {
    var lvb = document.getElementById('low-version-browser');
    var root = document.getElementById('root');
    var sp = document.getElementById('splash-screen');
    var token = localStorage.getItem('token') || '';
    try {
      var st = localStorage.getItem('qwen_access_token_state');
      if (!token && st) { var j = JSON.parse(st); token = j.token || ''; }
    } catch (e) {}
    return JSON.stringify({
      ua: navigator.userAgent,
      vite: window.shouldLoadViteApp,
      lowVer: lvb ? (getComputedStyle(lvb).display || '') : 'no-elem',
      splash: sp ? (getComputedStyle(sp).display || '') : 'no-elem',
      rootHtmlLen: root && root.innerHTML ? String(root.innerHTML.length) : '0',
      fromEntries: typeof Object.fromEntries,
      symbol: typeof Symbol,
      promise: typeof Promise,
      hasToken: !!token
    });
  } catch (e) { return 'ERR ' + e; }
})()
""", 8_000) ?: return "探测失败（页面未就绪）"
        val body = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
        return "WebView：${webViewEngine()}\n$body"
    }

    /** 立刻读一次页面里的 token（onPageFinished 与 UI 按钮共用）。 */
    fun readTokenNow() {
        val wv = webRef ?: return
        runOnMain {
            runCatching {
                wv.evaluateJavascript(READ_TOKEN_JS) { res ->
                    parseToken(res)?.let { token = it }
                }
            }
        }
    }

    private fun parseToken(jsResult: String?): String? {
        if (jsResult.isNullOrBlank() || jsResult == "null") return null
        // evaluateJavascript 返回的是「JSON 编码后的值」，字符串会被再包一层引号
        val inner = runCatching { JSONTokener(jsResult).nextValue() as? String }.getOrNull() ?: return null
        val o = runCatching { JSONObject(inner) }.getOrNull() ?: return null
        return o.optString("token").takeIf { it.isNotBlank() }
    }

    /** 主线程执行 JS 并等返回值（带超时，避免 WebView 已销毁时永久挂起）。 */
    private suspend fun evalJs(script: String, timeoutMs: Long = 10_000): String? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                runOnMain {
                    val wv = webRef
                    if (wv == null) {
                        if (cont.isActive) cont.resume(null)
                        return@runOnMain
                    }
                    runCatching {
                        wv.evaluateJavascript(script) { res -> if (cont.isActive) cont.resume(res) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }

    /**
     * 确保「页面已载入 + token 可读」。成功后可安全使用 [chatStream]。
     * 页面里的登录态由用户在 UI 里完成（含滑块验证）。
     */
    suspend fun ensureReady(timeoutMs: Long = 25_000): Boolean = ensureMutex.withLock {
        ensureCreated()
        val wv = webRef ?: run {
            lastError = "WebView 未创建"
            return@withLock false
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!loaded && System.currentTimeMillis() < deadline) delay(300)
        if (!loaded) {
            lastError = "页面尚未加载完成（需要网络）"
            return@withLock false
        }
        injectDsq(wv)
        runCatching { wv.resumeTimers() }
        // 页面登录态是**可选**的：有就直接用，没有也无所谓 ——
        // 业务身份可以从号池取，页面只负责提供「真指纹 + WAF 通行 cookie」。
        if (token.isNullOrBlank()) {
            parseToken(evalJs(READ_TOKEN_JS, 8_000))?.let { token = it }
        }
        true
    }

    // ---------------------------------------------------------------- 对话

    /**
     * 用页面内 `fetch()` 发起一轮对话，SSE 帧回流后按官方语义解析。
     * 调用前应确保 [ensureReady] 已成功。
     */
    fun chatStream(
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
    ): Flow<String> = flow {
        ensureCreated()
        val parser = QwenStreamParser(onChatId, onResponseId, onThinking)
        val id = UUID.randomUUID().toString()
        val ch = Channel<String>(Channel.UNLIMITED)
        sessions[id] = ch
        try {
            runCatching { webRef?.resumeTimers() }
            injectDsq(webRef)
            val body = qwenChatBody(
                model, prompt, chatId, parentId, thinkingEnabled, contextTail, slim = false
            ).toString()
            val headers = JSONObject()
                .put("Authorization", "Bearer $token")
                .put("Version", QwenWebClient.FE_VERSION)
                .put("source", QwenWebClient.SOURCE)
                .put("X-Request-Id", UUID.randomUUID().toString())
                .put("Timezone", SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US).format(Date()))
                .put("Origin", QwenWebClient.BASE)
                .put("Referer", QwenWebClient.BASE + "/")
                .toString()
            val script = "window.__dsq && window.__dsq.start(" +
                JSONObject.quote(id) + "," +
                JSONObject.quote(qwenChatUrl(chatId)) + "," +
                JSONObject.quote(body) + "," +
                JSONObject.quote(headers) + "); window.__dsq ? 'started' : 'missing'"
            val res = evalJs(script, 15_000)
            if (res == null || res.contains("missing")) {
                throw IOException("Qwen 浏览器通道未就绪（页面未加载完成或被回收），请到 Qwen 账号页打开一次浏览器通道")
            }
            for (payload in ch) parser.feed(payload).forEach { emit(it) }
            parser.assertProduced()
        } finally {
            sessions.remove(id)
            runCatching {
                evalJs("window.__dsq && window.__dsq.cancel(" + JSONObject.quote(id) + "); 'x'", 5_000)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** JS -> Kotlin 回调桥（运行在 WebView 的 JavaBridge 线程，Channel 线程安全）。 */
    inner class Bridge {
        @JavascriptInterface
        fun onFrame(id: String, payload: String) {
            sessions[id]?.trySend(payload)
        }

        @JavascriptInterface
        fun onDone(id: String) {
            sessions[id]?.close()
        }

        @JavascriptInterface
        fun onError(id: String, msg: String) {
            lastError = msg
            jsonCalls[id]?.complete(-1 to msg)
            sessions[id]?.close(IOException(msg.take(800)))
        }

        @JavascriptInterface
        fun onJson(id: String, status: String, text: String) {
            jsonCalls[id]?.complete((status.toIntOrNull() ?: -1) to text)
        }
    }

    /** 释放 WebView（切换通道/退出时调用）。 */
    fun destroy() {
        tokenWatchJob?.cancel()
        tokenWatchJob = null
        runOnMain {
            runCatching { webRef?.removeJavascriptInterface("DSLQ") }
            runCatching { webRef?.stopLoading() }
            runCatching { webRef?.destroy() }
            webRef = null
            loaded = false
            pageStuck = false
            token = null
        }
    }

    companion object {
        /** 移动版页面 UA（base-2.apk Humanizer 同款：Pixel 8 Pro / Chrome 126 Mobile）。 */
        const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** 桌面版页面 UA（base-2.apk Humanizer.DESKTOP_CHROME_UA 同款：Mac Chrome 145）。 */
        const val UA_DESKTOP =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"

        /** 页面内注入的网络助手：借真浏览器的 fetch/流读取发请求，帧回推给 Kotlin。 */
        private const val DSQ_JS = """
window.__dsq = window.__dsq || {};
window.__dsq.sessions = window.__dsq.sessions || {};
window.__dsq.start = function (id, url, body, headers) {
  var S = window.__dsq.sessions;
  var ctrl = new AbortController();
  S[id] = { abort: function () { try { ctrl.abort(); } catch (e) {} } };
  var h = { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-Accel-Buffering': 'no' };
  try {
    var extra = JSON.parse(headers || '{}');
    for (var k in extra) { if (extra[k]) { h[k] = extra[k]; } }
  } catch (e) {}
  fetch(url, { method: 'POST', headers: h, body: body, credentials: 'include', signal: ctrl.signal })
    .then(function (r) {
      var ct = (r.headers.get('content-type') || '');
      if (!r.ok || ct.indexOf('text/event-stream') < 0) {
        return r.text().then(function (t) {
          DSLQ.onError(id, 'HTTP ' + r.status + ' [' + ct + '] ' + t.substring(0, 800));
        });
      }
      var reader = r.body.getReader();
      var dec = new TextDecoder();
      var buf = '';
      function pump() {
        reader.read().then(function (res) {
          if (res.done) { DSLQ.onDone(id); return; }
          buf += dec.decode(res.value, { stream: true });
          var parts = buf.split(/\r?\n\r?\n/);
          buf = parts.pop();
          for (var i = 0; i < parts.length; i++) {
            var lines = parts[i].split(/\r?\n/);
            var payload = '';
            for (var j = 0; j < lines.length; j++) {
              var ln = lines[j];
              if (ln.indexOf('data:') === 0) {
                payload += (payload ? '\n' : '') + ln.slice(5).replace(/^\s+/, '');
              }
            }
            payload = payload.trim();
            if (!payload) { continue; }
            if (payload === '[DONE]') { DSLQ.onDone(id); return; }
            DSLQ.onFrame(id, payload);
          }
          pump();
        }).catch(function (e) { DSLQ.onError(id, 'stream error: ' + e); });
      }
      pump();
    })
    .catch(function (e) { DSLQ.onError(id, 'fetch error: ' + (e && e.message ? e.message : e)); });
};
window.__dsq.cancel = function (id) {
  var s = window.__dsq.sessions[id];
  if (s && s.abort) { try { s.abort(); } catch (e) {} }
  delete window.__dsq.sessions[id];
};
window.__dsq.fetchJson = function (id, url, body, headers) {
  var h = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
  try {
    var ex = JSON.parse(headers || '{}');
    for (var k in ex) { if (ex[k]) { h[k] = ex[k]; } }
  } catch (e) {}
  fetch(url, { method: 'POST', headers: h, body: body, credentials: 'include' })
    .then(function (r) {
      return r.text().then(function (t) { DSLQ.onJson(id, String(r.status), t); });
    })
    .catch(function (e) { DSLQ.onError(id, 'json error: ' + (e && e.message ? e.message : e)); });
};
window.__dsq.ok = 1;
"""

        /**
         * 现代 JS 特性垫片。index.html 的门禁脚本要求 `Object.fromEntries` 等，
         * 老 WebView 缺一个就会被判「低版本浏览器」，于是只显示「打开 Qwen 官方应用程序」
         * 引导页、不给登录入口。这里在页面开始加载时就补上。
         */
        private const val POLYFILL_JS = """
(function () {
  try {
    if (typeof Object.fromEntries !== 'function') {
      Object.fromEntries = function (it) {
        var o = {}, a = (typeof Array.from === 'function') ? Array.from(it) : it;
        for (var i = 0; i < a.length; i++) { o[a[i][0]] = a[i][1]; }
        return o;
      };
    }
    if (typeof Object.hasOwn !== 'function') {
      Object.hasOwn = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };
    }
    if (typeof queueMicrotask !== 'function') {
      queueMicrotask = function (f) { Promise.resolve().then(f); };
    }
    if (!Array.prototype.flat) {
      Array.prototype.flat = function (d) {
        d = (d === undefined) ? 1 : d;
        return d < 1 ? Array.prototype.slice.call(this)
          : Array.prototype.reduce.call(this, function (acc, v) {
              return acc.concat(Array.isArray(v) ? Array.prototype.flat.call(v, d - 1) : v);
            }, []);
      };
    }
    if (!Array.prototype.flatMap) {
      Array.prototype.flatMap = function (f, t) {
        return Array.prototype.flat.call(Array.prototype.map.call(this, f, t), 1);
      };
    }
    if (!String.prototype.replaceAll) {
      String.prototype.replaceAll = function (p, r) {
        if (Object.prototype.toString.call(p) === '[object RegExp]') return this.replace(p, r);
        return this.split(String(p)).join(r);
      };
    }
    if (!Promise.allSettled) {
      Promise.allSettled = function (ps) {
        return Promise.all(Array.prototype.map.call(ps, function (p) {
          return Promise.resolve(p).then(
            function (v) { return { status: 'fulfilled', value: v }; },
            function (e) { return { status: 'rejected', reason: e }; }
          );
        }));
      };
    }
    window.__dsqPolyfilled = 1;
  } catch (e) {}
})()
"""

        /**
         * 反 WebView 特征探测（逐字符照搬 base-2.apk Humanizer.STEALTH_JS）。
         *
         * chat.qwen.ai 的入口/风控脚本会探测 WebView 特征；缺失这些伪装时页面可能
         * 拒绝渲染（停在启动图 = 黑屏）或只给引导页。onPageStarted / onPageFinished 各注入一次。
         */
        private const val STEALTH_JS = """
(function() {
    try {
        // 1. 隐藏 webdriver
        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
        // 2. 伪装 Chrome 运行时
        if (!window.chrome) {
            window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {}, app: {} };
        }
        // 3. 补全 plugins 伪装
        Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
        // 4. 补全 languages
        Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'] });
        // 5. 阻止特征探测 toString
        const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
        if (originalQuery) {
            window.navigator.permissions.query = (parameters) => (
                parameters.name === 'notifications' ?
                    Promise.resolve({ state: Notification.permission }) :
                    originalQuery(parameters)
            );
        }
    } catch(e) {}
})();
"""

        /**
         * 页面内续期脚本：调用与官方前端 `TokenManager.performHttpRefresh()` 完全相同的接口，
         * 成功后把新 token 按前端 `qwen_access_token_state` 的结构写回 localStorage，
         * 结果放到 `window.__qwenRefresh`（Kotlin 侧轮询读取，绕开 WebView 异步限制）。
         */
        private const val REFRESH_JS = """
(function () {
  window.__qwenRefresh = '';
  try {
    var headers = {
      'Accept': 'application/json',
      'Version': '0.3.16',
      'source': 'web',
      'x-request-origin': location.origin,
      'x-request-id': (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now()),
      'Timezone': new Date().toString()
    };
    fetch('https://auth.qwen.ai/api/v2/auths/refresh', {
      method: 'GET', credentials: 'include', headers: headers
    }).then(function (r) { return r.text(); }).then(function (txt) {
      var out = { ok: false, raw: String(txt).slice(0, 300) };
      try {
        var j = JSON.parse(txt);
        var d = (j && j.data) || {};
        var tk = d.access_token || d.token || '';
        if (j && j.success && tk) {
          var exp = 0;
          if (d.expires_at) { exp = Number(d.expires_at); if (exp < 1e11) exp = exp * 1000; }
          if (!exp) {
            try {
              var p = tk.split('.');
              var s = JSON.parse(atob(p[1].replace(/-/g, '+').replace(/_/g, '/')));
              if (s && s.exp) exp = s.exp * 1000;
            } catch (e) {}
          }
          if (!exp) exp = Date.now() + 900000;
          out.ok = true; out.token = tk; out.expiresAt = exp;
          var st = {
            version: 1,
            stateId: String(Date.now()) + '-' + Math.random().toString(36).slice(2),
            token: tk, expiresAt: exp, updatedAt: Date.now(), sourceId: 'app-refresh'
          };
          try {
            localStorage.setItem('qwen_access_token_state', JSON.stringify(st));
            localStorage.setItem('token', tk);
            localStorage.setItem('at_expire_time', String(exp));
          } catch (e) {}
        } else {
          out.code = d.code || 'ERR_REFRESH_FAILED';
          out.details = d.details || '';
        }
      } catch (e) { out.error = String(e); }
      window.__qwenRefresh = JSON.stringify(out);
    }).catch(function (e) {
      window.__qwenRefresh = JSON.stringify({ ok: false, error: String(e) });
    });
  } catch (e) {
    window.__qwenRefresh = JSON.stringify({ ok: false, error: String(e) });
  }
  return 'started';
})()
"""

        /**
         * 从页面 localStorage 取登录 token。
         *
         * Qwen 新版前端（0.3.16）把 access_token 持久化在
         * `qwen_access_token_state`（JSON：{version,stateId,token,expiresAt,...}），
         * 旧版才是裸 `token` key。只读 `token` 会漏掉新版登录态 —— 这里两种都读，
         * 再加 JWT 扫描与 cookie 兜底，并返回是否处于输入状态（登录表单里不误抓）。
         */
        private const val READ_TOKEN_JS = """
(function () {
  try {
    function jwt(v) {
      return typeof v === 'string' && v.length > 40 && v.indexOf('eyJ') === 0 &&
        v.split('.').length >= 3;
    }
    function fromJson(v) {
      try {
        var o = JSON.parse(v);
        if (!o || typeof o !== 'object') return '';
        return o.token || o.accessToken || o.access_token ||
          (o.state && (o.state.token || o.state.accessToken)) || '';
      } catch (e) { return ''; }
    }
    var t = '', src = '';
    var st = localStorage.getItem('qwen_access_token_state');
    if (st) { var x = fromJson(st); if (x) { t = x; src = 'qwen_access_token_state'; } }
    if (!t) { var lg = localStorage.getItem('token'); if (lg) { t = lg; src = 'token'; } }
    if (!t) { var at = localStorage.getItem('access_token'); if (at) { t = at; src = 'access_token'; } }
    if (!t) {
      try {
        var qc = localStorage.getItem('QwenChat');
        if (qc) {
          var j = JSON.parse(qc);
          t = j.token || (j.storeInfo && j.storeInfo.token) || '';
          if (t) src = 'QwenChat';
        }
      } catch (e) {}
    }
    if (!t) {
      var ks = Object.keys(localStorage);
      for (var i = 0; i < ks.length; i++) {
        var v = localStorage.getItem(ks[i]) || '';
        if (jwt(v)) { t = v; src = ks[i]; break; }
        if (v.indexOf('{') === 0) {
          var x2 = fromJson(v);
          if (jwt(x2)) { t = x2; src = ks[i]; break; }
        }
      }
    }
    if (!t) {
      var c = document.cookie || '';
      var m = c.match(/(?:^|;\s*)(?:token|qwen_token|access_token)=([^;]+)/);
      if (m) { t = decodeURIComponent(m[1]); src = 'cookie'; }
    }
    var ae = document.activeElement;
    var editing = !!(ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' || ae.isContentEditable));
    try { if (!editing && document.querySelector('input[type="password"]')) editing = true; } catch (e) {}
    var exp = 0;
    try {
      if (st) {
        var sj = JSON.parse(st);
        if (sj && sj.expiresAt) exp = Number(sj.expiresAt) || 0;
      }
      if (!exp) {
        var ae2 = Number(localStorage.getItem('at_expire_time') || 0);
        if (ae2 > 0) exp = ae2 > 1e12 ? ae2 : ae2 * 1000;
      }
      if (!exp && t.indexOf('eyJ') === 0) {
        var pp = t.split('.');
        var sj2 = JSON.parse(atob(pp[1].replace(/-/g, '+').replace(/_/g, '/')));
        if (sj2 && sj2.exp) exp = sj2.exp * 1000;
      }
    } catch (e) {}
    return JSON.stringify({
      token: t || '',
      source: src,
      editing: editing,
      expiresAt: exp || 0,
      keys: Object.keys(localStorage).slice(0, 40),
      href: location.href
    });
  } catch (e) {
    return JSON.stringify({ token: '', source: '', editing: false, keys: [], error: String(e) });
  }
})()
"""
    }
}
