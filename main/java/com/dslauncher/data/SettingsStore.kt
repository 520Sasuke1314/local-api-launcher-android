package com.dslauncher.data

import android.content.Context

/** 端口 / 密钥等设置的持久化存储。 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", DEFAULT_PORT)
        set(v) = prefs.edit().putInt("port", v).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v).apply()

    /** 是否曾经成功启动过服务。 */
    var serviceStarted: Boolean
        get() = prefs.getBoolean("service_started", false)
        set(v) = prefs.edit().putBoolean("service_started", v).apply()

    /** 服务是否应当处于运行态（用于进程重启后自动恢复）。 */
    var serviceRunning: Boolean
        get() = prefs.getBoolean("service_running", false)
        set(v) = prefs.edit().putBoolean("service_running", v).apply()

    /** 是否开放局域网访问（绑定 0.0.0.0）。 */
    var allowLan: Boolean
        get() = prefs.getBoolean("allow_lan", false)
        set(v) = prefs.edit().putBoolean("allow_lan", v).apply()

    /** 配额耗尽后的自定义冷却时长（毫秒）。 */
    var quotaCooldownMs: Long
        get() = prefs.getLong("quota_cooldown_ms", 10 * 60 * 1000L)
        set(v) = prefs.edit().putLong("quota_cooldown_ms", v.coerceAtLeast(0L)).apply()

    /** 防撤回开关：开启后本地快照消息原文，官网撤回/改写后本地仍可查看。 */
    var antiRecall: Boolean
        get() = prefs.getBoolean("anti_recall", false)
        set(v) = prefs.edit().putBoolean("anti_recall", v).apply()

    /**
     * 深度思考开关。默认开启。
     *
     * 关闭时随 completion 下发 thinking_enabled=false 与 thinking:{"type":"disabled"}，
     * 官网走非思考链路，首字延迟明显更低。对 deepseek-reasoner 无影响（按模型定义走）。
     */
    var deepThink: Boolean
        get() = prefs.getBoolean("deep_think", true)
        set(v) = prefs.edit().putBoolean("deep_think", v).apply()

    /**
     * 开发者模式开关。默认关闭。
     *
     * 关闭（默认）：纯聊天。**不注入任何工具调用提示词**，即使客户端请求里带了 tools
     * 也完全忽略——避免「只想聊天的人」被塞一整套工具协议，模型不再自吐 DSML/工具块。
     * 开启：按 tools 定义注入工具调用协议，走「一次一个、等结果」的完整工具链路。
     */
    var devMode: Boolean
        get() = prefs.getBoolean("dev_mode", false)
        set(v) = prefs.edit().putBoolean("dev_mode", v).apply()

    /** Qwen 网页端使用的模型名（chat.qwen.ai）。 */
    var qwenModel: String
        get() {
            val saved = prefs.getString("qwen_model", DEFAULT_QWEN_MODEL) ?: DEFAULT_QWEN_MODEL
            // 迁移：这些 id 在 chat.qwen.ai 线上已不存在（选到只会拿到空回复/报错），
            // 自动纠正为当前旗舰模型，避免升级后仍然"返回空气"。
            if (saved.lowercase() in DEAD_QWEN_MODELS) {
                prefs.edit().putString("qwen_model", DEFAULT_QWEN_MODEL).apply()
                return DEFAULT_QWEN_MODEL
            }
            return saved
        }
        set(v) = prefs.edit().putString("qwen_model", v).apply()

    /**
     * Qwen 对话是否走「内置浏览器通道」（WebView 真 Chromium 栈）。
     *
     * 默认开启：chat.qwen.ai 的对话 POST 挂在阿里云 WAF 后面，风控按客户端指纹分级 ——
     * OkHttp(Java/Conscrypt TLS) 会被直接 punish/挂死，只有真浏览器栈能过。
     * 页面未登录时会自动回退到 OkHttp 通道，所以开着不会更差。
     */
    var qwenUseBrowser: Boolean
        get() = prefs.getBoolean("qwen_use_browser", true)
        set(v) = prefs.edit().putBoolean("qwen_use_browser", v).apply()

    /**
     * Qwen Token 自动续期（默认开）。
     *
     * chat.qwen.ai 的网页端 access_token 只有 ~15 分钟寿命，官方前端靠
     * `GET https://auth.qwen.ai/api/v2/auths/refresh`（凭登录 Cookie）换新 token。
     * 开启后 App 会在到期前自动续期，不再需要每 15 分钟重新登录。
     */
    var qwenAutoRefresh: Boolean
        get() = prefs.getBoolean("qwen_auto_refresh", true)
        set(v) = prefs.edit().putBoolean("qwen_auto_refresh", v).apply()

    /** 提前多少分钟续期（默认 5 分钟，官方前端是 2.5~3 分钟）。 */
    var qwenRefreshLeadMin: Int
        get() = prefs.getInt("qwen_refresh_lead_min", 5)
        set(v) = prefs.edit().putInt("qwen_refresh_lead_min", v.coerceIn(1, 30)).apply()

    /** 内网穿透是否处于开启状态（用于重启后自动恢复）。 */
    var tunnelEnabled: Boolean
        get() = prefs.getBoolean("tunnel_enabled", false)
        set(v) = prefs.edit().putBoolean("tunnel_enabled", v).apply()

    /** 上一次分配到的公网地址（仅用于界面回显，隧道重启后地址会变）。 */
    var tunnelUrl: String
        get() = prefs.getString("tunnel_url", "") ?: ""
        set(v) = prefs.edit().putString("tunnel_url", v).apply()

    /** 悬浮小窗是否开启（授权「显示在其他应用上层」后生效，启动应用时自动恢复）。 */
    var floatingWindow: Boolean
        get() = prefs.getBoolean("floating_window", false)
        set(v) = prefs.edit().putBoolean("floating_window", v).apply()

    /** 用户是否已同意免责声明（首次启动弹窗，同意后不再出现）。 */
    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean("disclaimer_accepted", false)
        set(v) = prefs.edit().putBoolean("disclaimer_accepted", v).apply()

    companion object {
        const val DEFAULT_PORT = 7891
        const val DEFAULT_QWEN_MODEL = "qwen3.8-max"

        /**
         * 已下线的 Qwen 网页端模型 id（2026-10 实测 /api/v2/models 不再返回）。
         * 命中后自动迁移到 [DEFAULT_QWEN_MODEL]。
         */
        val DEAD_QWEN_MODELS = setOf(
            "qwen3.7-max",
            "qwen3.6-plus",
            "qwen3.6-max",
            "qwen3-max",
            "qwen3-max-preview",
            "qwen3-coder-plus",
            "qwen3-235b",
            "qwen3-32b",
            "qwen3-vl-plus"
        )
    }
}
