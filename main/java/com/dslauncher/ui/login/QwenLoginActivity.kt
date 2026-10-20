package com.dslauncher.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dslauncher.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 独立的 Qwen 浏览器登录页（结构逐项对齐 base-2.apk 的 LoginActivity）。
 *
 * ## 为什么必须单独开 Activity
 * 之前把 WebView 嵌在 Compose 的 `AndroidView` 里，同一台设备上会出现
 * 「页面加载一会儿后就不再绘制」的现象 —— 内容层丢失，只剩窗口背景（我们主题里是纯黑），
 * 用户看到的就是黑屏；而 base-2 用普通 Activity + 原生 LinearLayout 直接挂 WebView 完全正常。
 * 这里照它的结构来，彻底绕开 Compose 宿主这一层：
 *
 * - 普通原生 Activity + LinearLayout 挂 WebView（不经 AndroidView / 不经 Compose 重组）；
 * - 窗口背景换成 base-2 同款浅色 `#F4F6FA`：即使偶发停绘也只是浅灰，不会再呈现成黑屏；
 * - WebView 用**本 Activity 的 Context 重建**（见 [QwenBrowser.resetForActivity]），
 *   不再跨窗口复用旧实例；
 * - 顶部工具条与 base-2 同款：返回 / 重载 / 移动版·桌面版切换 / 清除登录态 / 读取 / 诊断。
 *
 * 登录态抓取仍走 QwenBrowser：页面出现新 token 后自动校验、写入账号池并关闭本页。
 */
@Suppress("DEPRECATION")
class QwenLoginActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: App
    private var wv: WebView? = null
    private var statusTv: TextView? = null
    private var uaBtn: Button? = null

    private var grabbed = false
    private var rejectedToken: String? = null
    private var baseline: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as App
        // base-2 同款浅色窗口背景：WebView 偶发停绘时是浅灰，而不是黑
        window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#F4F6FA")))

        val browser = app.qwenClient.browser
        // 绑定本 Activity 窗口重建 WebView；Cookie/localStorage 在全局数据目录，登录态自动延续
        browser?.resetForActivity(this)
        wv = browser?.view()
        if (wv == null) {
            Toast.makeText(this, "WebView 初始化失败", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(buildUi())
        refreshUaLabel()
        startAutoWatch()
    }

    // ---------------------------------------------------------------- UI（纯原生，对齐 base-2）

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6FA"))
        }

        // ---- 工具条（横向可滚动，防止小屏挤爆）----
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#F1F5F9"))
            setOnClickListener { onClick() }
        }

        bar.addView(btn("← 返回") { finish() })
        bar.addView(btn("重载") { app.qwenClient.browser?.reload(); setStatus("重新加载中…") })
        uaBtn = btn("移动版") { toggleUa() }
        bar.addView(uaBtn)
        bar.addView(btn("清除登录态") {
            app.qwenClient.browser?.clearLoginState()
            grabbed = false
            rejectedToken = null
            setStatus("已清除页面登录态，重新加载中…")
        })
        bar.addView(btn("读取") { manualRead() })
        bar.addView(btn("续期") { manualRefresh() })
        bar.addView(btn("诊断") { diagnose() })
        scroll.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        // ---- 状态条 ----
        statusTv = TextView(this).apply {
            text = "正在加载 chat.qwen.ai …"
            textSize = 11f
            maxLines = 6
            setTextColor(Color.parseColor("#64748B"))
        }
        root.addView(
            statusTv,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(10), dp(6), dp(10), 0) }
        )

        // ---- WebView（base-2 同款：weight=1 撑满剩余空间）----
        val view = wv
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return root
    }

    private fun refreshUaLabel() {
        val b = app.qwenClient.browser
        uaBtn?.text = if (b?.isDesktopUa() == true) "桌面版" else "移动版"
    }

    private fun toggleUa() {
        val b = app.qwenClient.browser ?: return
        b.setDesktopUa(!b.isDesktopUa())
        refreshUaLabel()
        setStatus("已切换 UA，重新加载中…")
    }

    private fun setStatus(text: String) {
        statusTv?.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 登录态抓取

    /** 实时监听：页面出现「相对打开时有变化」的新 token → 校验 → 入池 → 关闭。 */
    private fun startAutoWatch() {
        scope.launch {
            val b = app.qwenClient.browser ?: return@launch
            var i = 0
            while (isActive && !b.isLoaded() && i < 120) {
                delay(500)
                i++
            }
            if (!isActive || !b.isLoaded()) return@launch
            baseline = b.readStoredToken()
            setStatus(
                if (baseline.isNullOrBlank()) "请在页面完成登录；检测到登录态后会自动保存 Token"
                else "页面已有登录态；如需换号请先点「清除登录态」"
            )
            var lastSeen = baseline
            while (isActive && !grabbed) {
                val t = runCatching { b.snapshot() }.getOrNull()?.token
                if (!t.isNullOrBlank() && t != baseline && t != lastSeen && t != rejectedToken) {
                    lastSeen = t
                    setStatus("检测到新的登录态，正在校验…")
                    val ok = runCatching { app.qwenClient.validateToken(t) }.getOrDefault(false)
                    if (ok) {
                        grabbed = true
                        val cookies = app.qwenClient.browser?.cookieString().orEmpty()
                        val exp = runCatching { app.qwenClient.browser?.readExpiry() }.getOrNull() ?: 0L
                        app.qwenRepo.saveToken(t, cookies, exp)
                        toast(
                            if (cookies.isNotBlank()) "登录成功，Token 已保存（已存 Cookie，可自动续期）"
                            else "登录成功，Token 已保存"
                        )
                        finish()
                        return@launch
                    } else {
                        rejectedToken = t
                        setStatus("新登录态校验失败，请稍后重试或换号")
                    }
                }
                delay(2500)
            }
        }
    }

    /** 手动兜底：立刻读一次页面 token 并校验入池。 */
    private fun manualRead() {
        scope.launch {
            setStatus("正在读取页面登录态…")
            val token = app.qwenClient.browser?.readStoredToken()?.takeIf { it.isNotBlank() }
            if (token == null) {
                setStatus("还没检测到登录态：请先在页面完成登录（有验证码就滑一下）")
                return@launch
            }
            val ok = runCatching { app.qwenClient.validateToken(token) }.getOrDefault(false)
            if (ok) {
                grabbed = true
                val cookies = app.qwenClient.browser?.cookieString().orEmpty()
                val exp = runCatching { app.qwenClient.browser?.readExpiry() }.getOrNull() ?: 0L
                app.qwenRepo.saveToken(token, cookies, exp)
                toast(
                    if (cookies.isNotBlank()) "登录成功，Token + Cookie 已保存（可自动续期）"
                    else "登录成功，Token 已保存"
                )
                finish()
            } else {
                setStatus("登录态校验失败：token 无效或已过期")
            }
        }
    }

    /** 手动续期：调官方 /auths/refresh（真实浏览器栈 + 页面 Cookie），新 token 直接入库。 */
    private fun manualRefresh() {
        scope.launch {
            setStatus("正在续期页面会话（auth.qwen.ai/api/v2/auths/refresh）…")
            val ok = runCatching { app.qwenRepo.refresher.refreshPageSession() }.getOrDefault(false)
            setStatus(
                if (ok) {
                    val t = app.qwenClient.browser?.readStoredToken().orEmpty()
                    val tag = if (t.isNotBlank()) "（…${t.takeLast(8)}）" else ""
                    "续期成功：新 Token 已入库$tag；之后到期前会自动换新，不用再重新登录"
                } else {
                    "续期失败：" + (app.qwenClient.browser?.errorText() ?: "页面未加载或尚未登录")
                }
            )
        }
    }

    private fun diagnose() {
        scope.launch {
            setStatus("诊断中…")
            val p = runCatching { app.qwenClient.browser?.probe() }.getOrNull() ?: "浏览器不可用"
            val b = app.qwenClient.browser
            val cookieLen = runCatching { b?.cookieString()?.length ?: 0 }.getOrDefault(0)
            val exp = runCatching { b?.readExpiry() }.getOrNull() ?: 0L
            val expTxt = if (exp > 0L) {
                val min = (exp - System.currentTimeMillis()) / 60_000
                "约 $min 分钟后过期"
            } else "未知（未取到 expiresAt）"
            setStatus(
                "$p\n续期 Cookie：${if (cookieLen > 0) "已就绪（$cookieLen 字符）" else "缺失（登录后才会写入）"}" +
                    " · Token 过期：$expTxt"
            )
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // 只从视图树摘下，不销毁：App 后台的浏览器通道（真 Chromium fetch）还要继续用它
        val view = wv
        if (view != null) (view.parent as? ViewGroup)?.removeView(view)
    }
}
