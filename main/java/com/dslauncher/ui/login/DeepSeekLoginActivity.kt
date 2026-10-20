package com.dslauncher.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dslauncher.App
import com.dslauncher.data.DsLoginJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

/**
 * DeepSeek 登录页 —— **独立原生 Activity**，结构逐项对齐 base-2.apk 的 `dev.multigw.ui.LoginActivity`。
 *
 * ## 为什么又开一个 Activity
 * Qwen 当初「加载一会儿就黑屏」最后就是靠「把 WebView 从 Compose 的 AndroidView 里搬进普通
 * Activity + 原生 LinearLayout」解决的；DeepSeek 的验证码同样在 Compose 宿主里出不来
 *（页面能画、验证码控件不出来），所以这里按 base-2 的宿主结构原样搬：
 *
 * - 原生 Activity + LinearLayout 直接挂 WebView（不经 Compose、不经 AndroidView）；
 * - 窗口背景 base-2 同款浅色 `#F4F6FA`；
 * - WebSettings / 焦点 / 触摸 / 新窗口 / STEALTH_JS 全部照搬 base-2（见 [DsLoginJs] 注释里两处修正）；
 * - 顶部按钮：返回 / 重载 / UA（默认→移动版→电脑版）/ 提取并保存 / 粘贴 Token / 清登录态 / 诊断；
 * - 登录成功后提取 token → 服务端校验 → 写入账号池 → 自动关闭。
 */
@Suppress("DEPRECATION")
class DeepSeekLoginActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: App

    private var wv: WebView? = null
    private var statusTv: TextView? = null
    private var uaBtn: Button? = null

    private var grabbed = false
    private var baseline: String? = null   // 打开页面时已有的登录态（有则不自动抢登、只提示）
    private var rejected: String? = null
    private var uaMode = 0 // 0=默认（WebView 自带） 1=移动版 2=电脑版
    private var stealthOn = true   // base-2 默认注入反探测脚本；关掉可对比原始环境

    private val consoleLogs = StringBuilder()
    private val netLogs = StringBuilder()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as App
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#F4F6FA")))
        setContentView(buildUi())
        refreshUaLabel()
        loadLogin()
        startWatch()
    }

    // ---------------------------------------------------------------- UI（纯原生，对齐 base-2）

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun button(text: String, onClick: (Button) -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 13.5f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        // 触摸目标 ≥ 44dp：之前 12sp + 4dp 内边距，部分手机上按不准
        minHeight = dp(44)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(Color.parseColor("#334155"))
        setBackgroundColor(Color.parseColor("#F1F5F9"))
        setOnClickListener { onClick(this) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        bar.addView(button("← 返回") { finish() })
        bar.addView(button("重载") { wv?.reload() })
        uaBtn = button("UA：默认") { toggleUa() }
        bar.addView(uaBtn)
        bar.addView(button("反探测：开") { b ->
            stealthOn = !stealthOn
            b.text = if (stealthOn) "反探测：开" else "反探测：关"
            inject(wv)
            wv?.reload()
            setStatus(if (stealthOn) "已开启反探测（= base-2 行为），重新加载中…" else "已关闭反探测（原始环境），重新加载中…")
        })
        bar.addView(button("换号") {
            // 之前登录进去直接是对话页（旧会话还在），导致「加不了号」：
            // 这里清掉登录态并直接打开登录页 /sign_in
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
            runCatching { CookieManager.getInstance().flush() }
            runCatching { WebStorage.getInstance().deleteAllData() }
            wv?.clearCache(true)
            baseline = null
            wv?.loadUrl(DsLoginJs.LOGIN_URL + "/sign_in")
            setStatus("已清除登录态并打开登录页；登录完成后会自动保存")
        })
        bar.addView(button("提取并保存") { grabNow(verbose = true) })
        bar.addView(button("粘贴 Token") { showManualDialog() })
        bar.addView(button("清登录态") {
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
            runCatching { CookieManager.getInstance().flush() }
            runCatching { WebStorage.getInstance().deleteAllData() }
            wv?.clearCache(true)
            wv?.reload()
            setStatus("已清除登录态，重新加载中…")
        })
        bar.addView(button("诊断") { diagnose() })
        scroll.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        statusTv = TextView(this).apply {
            text = "正在加载 chat.deepseek.com …"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        }
        root.addView(
            statusTv,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(4), dp(10), 0) }
        )

        val web = WebView(this)
        wv = web
        setupWebView(web)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun setStatus(text: String) {
        statusTv?.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- WebView：逐项对齐 base-2

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(web: WebView) {
        web.setBackgroundColor(Color.WHITE)

        // base-2：焦点/触摸（保证点击落进页面、验证码滑块能拖动）
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.isClickable = true
        web.requestFocus(View.FOCUS_DOWN)
        web.setOnTouchListener { v, event ->
            val a = event.action
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP) v.requestFocus()
            false
        }

        // base-2 LoginActivity onCreate 第 65~81 行的 WebSettings，一项不落
        val st = web.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.databaseEnabled = true
        st.useWideViewPort = true
        st.loadWithOverviewMode = true
        st.setSupportZoom(true)
        st.builtInZoomControls = true
        st.displayZoomControls = false
        st.setSupportMultipleWindows(true)
        st.javaScriptCanOpenWindowsAutomatically = true
        st.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        st.allowFileAccess = true
        st.allowContentAccess = true
        st.textZoom = 100
        st.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        // 注意：base-2 **不覆盖 UA**（用 WebView 默认 UA）——照搬，不设置 userAgentString

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                inject(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                inject(view)
                val u = url.orEmpty()
                val onLoginPage = u.contains("sign_in") || u.contains("signin") || u.contains("login")
                wv?.evaluateJavascript(DsLoginJs.DUMP_JS) { raw ->
                    val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    val t = inner?.let { DsLoginJs.extractToken(it) }
                    if (!t.isNullOrBlank() && baseline == null) baseline = t
                    setStatus(
                        when {
                            onLoginPage -> "登录页已就绪：完成登录后自动保存 Token（卡验证码就点「诊断」）"
                            !t.isNullOrBlank() -> "检测到已登录的旧会话：点「提取并保存」直接入库；要换号点「换号」"
                            else -> "页面已加载（" + u.take(50) + "）：如需登录请点「换号」打开登录页"
                        }
                    )
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val u = request?.url?.toString().orEmpty()
                val main = request?.isForMainFrame == true
                logNet((if (main) "主框架 " else "子资源 ") + (error?.errorCode?.toString() ?: "?") + " " +
                    (error?.description ?: "") + " ← " + u.take(140))
                if (main) setStatus("页面加载失败：" + (error?.description ?: "未知错误"))
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                val code = errorResponse?.statusCode ?: 0
                if (code >= 400) logNet("HTTP $code ← " + request?.url?.toString().orEmpty().take(140))
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            /** base-2 同款：需要新窗口时，把 popup 塞回**同一个 WebView**。 */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                runCatching { request?.grant(request.resources) }
            }

            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val line = msg?.message().orEmpty()
                if (line.isNotBlank()) {
                    val lvl = runCatching { msg?.messageLevel()?.name }.getOrNull() ?: "LOG"
                    val src = runCatching { msg?.sourceId().orEmpty() }.getOrDefault("")
                    val ln = runCatching { msg?.lineNumber() ?: 0 }.getOrDefault(0)
                    val where = if (src.isNotBlank()) "(" + src.substringAfterLast('/').take(30) + ":" + ln + ") " else ""
                    consoleLogs.append("\n[").append(lvl).append("] ").append(where).append(line.take(170))
                    if (consoleLogs.length > 3000) consoleLogs.delete(0, consoleLogs.length - 3000)
                }
                return true
            }
        }
    }

    private fun logNet(line: String) {
        netLogs.append("\n").append(line)
        if (netLogs.length > 2000) netLogs.delete(0, netLogs.length - 2000)
    }

    /** 反探测（base-2 的 STEALTH_JS）+ 输入法补丁（防倒序）。 */
    private fun inject(view: WebView?) {
        val v = view ?: return
        // 输入法补丁始终注入（防「输入倒序」）
        runCatching { v.evaluateJavascript(DsLoginJs.INPUT_COMPAT_JS, null) }
        if (!stealthOn) return
        runCatching { v.evaluateJavascript(DsLoginJs.STEALTH_JS, null) }
    }

    private fun loadLogin() {
        val v = wv ?: return
        v.loadUrl(DsLoginJs.LOGIN_URL)
    }

    private fun toggleUa() {
        uaMode = (uaMode + 1) % 3
        wv?.let { v ->
            when (uaMode) {
                1 -> v.settings.userAgentString = DsLoginJs.UA_MOBILE
                2 -> v.settings.userAgentString = DsLoginJs.UA_DESKTOP
                else -> v.settings.userAgentString = null
            }
            v.reload()
        }
        refreshUaLabel()
        setStatus("UA 已切换，重新加载中…")
    }

    private fun refreshUaLabel() {
        uaBtn?.text = when (uaMode) {
            1 -> "UA：移动版"
            2 -> "UA：电脑版"
            else -> "UA：默认"
        }
    }

    // ---------------------------------------------------------------- 登录态抓取

    /** base-2 的「提取并保存当前站点凭据」：dump 全量 → 嗅 token → 校验入池。 */
    private fun grabNow(verbose: Boolean) {
        val v = wv ?: return
        v.evaluateJavascript(DsLoginJs.DUMP_JS) { raw ->
            val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return@evaluateJavascript
            val token = DsLoginJs.extractToken(inner)
            when {
                token.isNullOrBlank() ->
                    if (verbose) setStatus("页面里还没有登录态：先完成登录（人机验证过不去就点「诊断」看现场）")

                // 自动轮询只在「出现新登录态」时入库；旧会话需手动点「提取并保存」
                !verbose && token == baseline -> Unit
                else -> acceptToken(token)
            }
        }
    }

    private fun startWatch() {
        scope.launch {
            // 等页面起来
            var i = 0
            while (isActive && i < 60) {
                delay(500)
                i++
                if (wv?.progress ?: 0 >= 100) break
            }
            setStatus("请在页面完成登录；检测到登录态后会自动保存 Token")
            while (isActive && !grabbed) {
                grabNow(verbose = false)
                delay(2500)
            }
        }
    }

    private fun acceptToken(raw: String?) {
        val token = raw?.takeIf { it.isNotBlank() } ?: return
        if (grabbed || token == rejected) return
        scope.launch {
            setStatus("正在校验登录态…")
            val ok = runCatching { app.webClient.validateToken(token) }.getOrDefault(false)
            if (ok) {
                grabbed = true
                app.webRepo.saveToken(token)
                toast("DeepSeek 登录成功，Token 已保存")
                finish()
            } else {
                rejected = token
                setStatus("检测到无效登录态，已忽略。请在页面完成登录")
            }
        }
    }

    /** 手动粘贴 token（WebView 里过不去时的兜底）。 */
    private fun showManualDialog() {
        val input = EditText(this).apply {
            hint = "粘贴 userToken / token"
            setTextColor(Color.parseColor("#0F172A"))
            setMinLines(3)
        }
        AlertDialog.Builder(this)
            .setTitle("手动粘贴 Token")
            .setMessage("电脑浏览器登录 chat.deepseek.com → F12 → Application → Local Storage → 复制 userToken 的值")
            .setView(input)
            .setPositiveButton("校验并保存") { _, _ ->
                val t = input.text?.toString()?.trim().orEmpty()
                if (t.isBlank()) setStatus("没有内容：请先复制 token") else acceptToken(t)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- 诊断

    private fun diagnose() {
        val v = wv ?: return
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val head = "WebView 引擎：" + (pkg?.packageName ?: "未知") + " " + (pkg?.versionName ?: "")
        v.evaluateJavascript(com.dslauncher.data.WebStealth.DIAG_JS) { raw ->
            val inner = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
            val o = runCatching { JSONObject(inner) }.getOrNull()
            val sb = StringBuilder(head).append('\n')
            if (o == null) {
                sb.append("诊断失败：").append(inner.take(200))
            } else {
                sb.append("UA：").append(o.optString("ua").take(110)).append('\n')
                sb.append("状态：").append(o.optString("ready"))
                    .append(" · 正文 ").append(o.optInt("bodyLen")).append(" 字")
                    .append(" · 密码框 ").append(if (o.optBoolean("hasPwd")) "有" else "无").append('\n')
                sb.append("plugins：").append(o.optInt("pluginsLen"))
                    .append(" · plugins[0]=").append(o.optString("plugin0"))
                    .append(" · indexOf 可用=").append(o.optString("plugin0IndexOfOk")).append('\n')
                sb.append("验证码节点：").append(o.optInt("captchaNodes"))
                    .append(" · iframe：").append(o.optJSONArray("iframes")?.length() ?: 0)
                    .append(" · chrome：").append(o.optBoolean("chrome")).append('\n')
                o.optJSONArray("iframes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        sb.append("  iframe[").append(i).append("]：").append(arr.optString(i).take(110)).append('\n')
                    }
                }
                o.optJSONArray("captchaRects")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        sb.append("  验证码节点[").append(i).append("]：").append(arr.optString(i).take(150)).append('\n')
                    }
                }
                if (o.optString("errText").isNotBlank()) {
                    sb.append("页面提示：").append(o.optString("errText")).append('\n')
                }
            }
            val logs = consoleLogs.toString().trim()
            if (logs.isNotBlank()) sb.append("控制台：").append(logs.takeLast(600)).append('\n')
            val nets = netLogs.toString().trim()
            if (nets.isNotBlank()) sb.append("网络失败：").append(nets.takeLast(600))
            setStatus(sb.toString().trim())
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching {
            (wv?.parent as? ViewGroup)?.removeView(wv)
            wv?.stopLoading()
            wv?.destroy()
        }
        wv = null
    }
}
