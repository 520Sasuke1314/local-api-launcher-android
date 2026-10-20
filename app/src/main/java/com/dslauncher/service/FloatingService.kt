package com.dslauncher.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.dslauncher.App
import com.dslauncher.ui.theme.ThemeMode

/**
 * 悬浮小窗服务（SYSTEM_ALERT_WINDOW）。
 *
 * 常态：半透明「DSL」悬浮球半藏在屏幕边缘，可拖动；单击展开快捷控制面板。
 * 面板配色随应用深浅色（读 [ThemeMode]，与应用内 UI 同一套色板），
 * 支持改端口与密钥、启停本地服务；展开/收起带缩放 + 透明度动画。
 * 面板需要输入焦点，展开时切换窗口 flags；收起后恢复 NOT_FOCUSABLE。
 */
class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var ball: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelShowing = false
    private var panelAnimating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wm == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            addBall()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { panel?.let { wm?.removeView(it) } }
        runCatching { ball?.let { wm?.removeView(it) } }
        panel = null
        ball = null
        panelShowing = false
        panelAnimating = false
        super.onDestroy()
    }

    // ---- 配色：与应用内同一套色板（按当前深浅色取值） ----

    private data class Palette(
        val bgTop: Int, val bgBottom: Int, val cardBg: Int, val cardBorder: Int,
        val primary: Int, val primaryLight: Int, val accent: Int,
        val textPrimary: Int, val textSecondary: Int, val fieldBg: Int
    )

    private fun palette(): Palette = if (ThemeMode.isDark) Palette(
        bgTop = 0xFF0B1727.toInt(), bgBottom = 0xFF0A111C.toInt(),
        cardBg = 0xF21E2A3D.toInt(), cardBorder = 0x2E58A6FF.toInt(),
        primary = 0xFF2563EB.toInt(), primaryLight = 0xFF58A6FF.toInt(), accent = 0xFF10B981.toInt(),
        textPrimary = 0xFFE6EDF7.toInt(), textSecondary = 0xFF9BB0C9.toInt(),
        fieldBg = 0xFF0F1826.toInt()
    ) else Palette(
        bgTop = 0xFFE4EDFA.toInt(), bgBottom = 0xFFF5F8FE.toInt(),
        cardBg = 0xF7FFFFFF.toInt(), cardBorder = 0x292563EB.toInt(),
        primary = 0xFF2563EB.toInt(), primaryLight = 0xFF1D6FE8.toInt(), accent = 0xFF059669.toInt(),
        textPrimary = 0xFF17233A.toInt(), textSecondary = 0xFF5B6C85.toInt(),
        fieldBg = 0xFFF2F6FC.toInt()
    )

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(color)
            strokeColor?.let { setStroke((1 * resources.displayMetrics.density).toInt(), it) }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBall() {
        val manager = wm ?: return
        val p = palette()
        val dp = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * dp).toInt()

        val view = TextView(this).apply {
            text = BALL_TEXT
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = BALL_ALPHA
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(p.primary)
                setStroke((1.5f * dp).toInt(), p.primaryLight)
            }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 初始位置：右缘、垂直居中
            x = resources.displayMetrics.widthPixels - size / 2
            y = resources.displayMetrics.heightPixels / 2
        }

        // 拖动跟手；位移小于 touchSlop 视为点击（展开/收起面板）
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - downRawX) > slop ||
                        kotlin.math.abs(e.rawY - downRawY) > slop
                    ) moved = true
                    params.x = startX + (e.rawX - downRawX).toInt()
                    params.y = startY + (e.rawY - downRawY).toInt()
                    runCatching { manager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        runCatching { manager.addView(view, params) }
        ball = view
        ballParams = params
    }

    private fun togglePanel() {
        if (panelAnimating) return
        if (panelShowing) hidePanel() else showPanel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        val manager = wm ?: return
        if (panelShowing || panelAnimating) return
        val app = App.from(this)
        val settings = app.settings
        val p = palette()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(this).apply {
                this.text = s
                textSize = sizeSp
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        fun field(hint: String, value: String, numeric: Boolean = false): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                setTextColor(p.textPrimary)
                setHintTextColor(p.textSecondary)
                textSize = 13f
                background = rounded(p.fieldBg, 12f, p.cardBorder)
                setPadding(px(12), px(9), px(12), px(9))
            }

        fun button(label: String, bg: Int, weight: Float, onClick: () -> Unit): Button =
            Button(this).apply {
                this.text = label
                textSize = 13f
                isAllCaps = false
                stateListAnimator = null
                setTextColor(Color.WHITE)
                background = rounded(bg, 14f)
                setOnClickListener { onClick() }
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(16))
            background = rounded(p.cardBg, 24f, p.cardBorder)
        }

        val title = text("DSL 快捷控制", 15f, p.textPrimary, bold = true)
        val subtitle = text("悬浮小窗 · 点悬浮球收起", 11f, p.textSecondary)

        val portEt = field("端口", settings.port.toString(), numeric = true)
        val keyEt = field("API 密钥（可空）", settings.apiKey)

        val toggleBtn = button("启动服务", p.primary, 1f) {}
        fun relabelToggle() {
            toggleBtn.text = if (app.apiServer?.isRunning == true) "停止服务" else "启动服务"
            toggleBtn.background = rounded(
                if (app.apiServer?.isRunning == true) 0xFFB3261E.toInt() else p.primary, 14f
            )
        }
        toggleBtn.setOnClickListener {
            if (app.apiServer?.isRunning == true) {
                app.stopServer()
                runCatching { ApiService.stop(this@FloatingService) }
            } else {
                val port = portEt.text.toString().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    portEt.hint = "端口无效"
                    return@setOnClickListener
                }
                val key = keyEt.text.toString().trim().ifBlank { null }
                val ok = app.startServer(port, key)
                if (ok) {
                    settings.port = port
                    settings.apiKey = key.orEmpty()
                    // 应用退后台时从这里启动前台服务会被系统拒绝
                    //（ForegroundServiceStartNotAllowedException），本地服务本身已起，
                    // 通知/保活等回到应用里再补 —— 不能让它把整个小窗进程炸掉。
                    runCatching { ApiService.start(this@FloatingService, port, key) }
                }
            }
            relabelToggle()
        }
        relabelToggle()

        val closeBtn = button("收起", 0xFF3A4A5F.toInt(), 0.6f) {
            portEt.text.toString().toIntOrNull()?.let { if (it in 1..65535) settings.port = it }
            settings.apiKey = keyEt.text.toString().trim()
            hidePanel()
        }

        // ---------- IP / Key 信息（可复制 / 可保存） ----------
        fun localIp(): String = runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { java.util.Collections.list(it.inetAddresses) }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: "未连接"

        val infoTv = text("", 11.5f, p.textSecondary)
        val infoHandler = android.os.Handler(android.os.Looper.getMainLooper())
        fun refresh() {
            val key = keyEt.text.toString().trim().ifBlank { settings.apiKey }
            val turl = settings.tunnelUrl.takeIf { it.isNotBlank() } ?: "未开启"
            infoTv.text = "IP  http://${localIp()}:${settings.port}/v1\n" +
                "KEY  ${key.ifBlank { "（空）" }}\n" +
                "穿透  $turl"
        }
        refresh()

        fun copyToClipboard(label: String, s: String) = runCatching {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("dsl", s))
            android.widget.Toast.makeText(this, label, android.widget.Toast.LENGTH_SHORT).show()
        }

        val copyBtn = button("复制", 0xFF3A4A5F.toInt(), 1f) {
            refresh()
            val key = keyEt.text.toString().trim()
            val port = portEt.text.toString().toIntOrNull() ?: settings.port
            val sb = StringBuilder()
            sb.append("http://").append(localIp()).append(':').append(port).append("/v1")
            if (key.isNotBlank()) sb.append("\nKey: ").append(key)
            settings.tunnelUrl.takeIf { it.isNotBlank() }?.let { sb.append("\n穿透: ").append(it) }
            copyToClipboard("已复制", sb.toString())
        }
        val saveBtn = button("保存", p.primary, 1f) {
            portEt.text.toString().toIntOrNull()?.let { if (it in 1..65535) settings.port = it }
            settings.apiKey = keyEt.text.toString().trim()
            refresh()
            android.widget.Toast.makeText(this, "已保存", android.widget.Toast.LENGTH_SHORT).show()
        }

        // 紧凑开关卡：左侧文字 + 右侧 Switch。
        // 视觉对齐应用内「功能」页的开关卡片，尺寸整体缩小一圈。
        fun toggle(
            label: String,
            checked: Boolean,
            onChange: (Boolean) -> Unit
        ): LinearLayout {
            val tv = text(label, 12f, p.textPrimary)
            val ac = android.content.res.ColorStateList.valueOf(p.primary)
            val off = android.content.res.ColorStateList.valueOf(0xFF8A94A6.toInt())
            val sw = android.widget.Switch(this).apply {
                text = ""
                showText = false
                minimumWidth = 0
                minimumHeight = 0
                isChecked = checked
                runCatching { thumbTintList = if (checked) ac else off }
                runCatching { buttonTintList = if (checked) ac else off }
                setOnCheckedChangeListener { _, v ->
                    runCatching { thumbTintList = if (v) ac else off }
                    runCatching { buttonTintList = if (v) ac else off }
                    onChange(v)
                }
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(11), px(3), px(5), px(3))
                background = rounded(p.fieldBg, 12f, p.cardBorder)
                addView(
                    tv,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    sw,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        val tgThink = toggle("深度思考", settings.deepThink) { settings.deepThink = it }
        val tgAnti = toggle("防撤回", settings.antiRecall) { settings.antiRecall = it }
        val tgTools = toggle("工具调用", settings.devMode) { settings.devMode = it }
        val tgLan = toggle("局域网IP", settings.allowLan) { settings.allowLan = it }

        fun toggleRow(vararg v: View): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            v.forEach {
                addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        fun lp(top: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(top) }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(portEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(this@FloatingService), LinearLayout.LayoutParams(px(10), 1))
            addView(keyEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toggleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(this@FloatingService), LinearLayout.LayoutParams(px(8), 1))
            addView(closeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f))
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(infoTv, lp(12))
        root.addView(toggleRow(tgThink, tgAnti), lp(10))
        root.addView(toggleRow(tgTools, tgLan), lp(2))
        root.addView(toggleRow(saveBtn, copyBtn), lp(8))
        root.addView(inputRow, lp(12))
        root.addView(btnRow, lp(12))

        val params = WindowManager.LayoutParams(
            px(PANEL_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 面板要接输入焦点（EditText），不带 NOT_FOCUSABLE
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        // 拖动面板：按住标题条整窗移动
        var dRawX = 0f
        var dRawY = 0f
        var dPosX = 0
        var dPosY = 0
        title.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dRawX = e.rawX; dRawY = e.rawY
                    dPosX = params.x; dPosY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = dPosX + (e.rawX - dRawX).toInt()
                    params.y = dPosY + (e.rawY - dRawY).toInt()
                    runCatching { manager.updateViewLayout(root, params) }
                    true
                }

                else -> false
            }
        }

        panelAnimating = true
        runCatching { manager.addView(root, params) }
        panel = root
        panelParams = params
        panelShowing = true

        // 球淡到很淡（**不改 visibility**：INVISIBLE/VISIBLE 切换会触发布局与合成层重建，
        // 收起时表现为闪一下；只动 alpha 就没有这个问题）
        ball?.animate()?.alpha(0.12f)?.setDuration(150)?.start()

        // 展开动画：中心缩放 + 淡入（轻微回弹），收起时反向
        root.scaleX = 0.86f
        root.scaleY = 0.86f
        root.alpha = 0f
        root.translationY = px(24).toFloat()
        root.animate()
            .scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(0.7f))
            .withEndAction { panelAnimating = false }
            .start()
    }

    private fun hidePanel() {
        val manager = wm ?: return
        val view = panel ?: return
        if (panelAnimating) return
        panelAnimating = true
        // 收起动画：中心缩回 + 淡出，结束后才真正移除视图
        view.animate()
            .scaleX(0.86f).scaleY(0.86f).alpha(0f)
            .translationY((24 * resources.displayMetrics.density))
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator(0.8f))
            .withEndAction {
                runCatching { manager.removeView(view) }
                panel = null
                panelParams = null
                panelShowing = false
                panelAnimating = false
                // 只恢复透明度，不碰 visibility（见 showPanel 里的说明）
                ball?.animate()?.alpha(BALL_ALPHA)?.setDuration(160)?.start()
            }
            .start()
    }

    companion object {
        private const val BALL_TEXT = "DSL"
        private const val BALL_SIZE_DP = 46
        private const val BALL_ALPHA = 0.85f
        private const val PANEL_WIDTH_DP = 320

        fun start(context: android.content.Context) {
            runCatching { context.startService(Intent(context, FloatingService::class.java)) }
        }

        fun stop(context: android.content.Context) {
            runCatching { context.stopService(Intent(context, FloatingService::class.java)) }
        }
    }
}
