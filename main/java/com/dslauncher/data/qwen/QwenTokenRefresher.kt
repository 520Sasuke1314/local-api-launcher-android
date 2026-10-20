package com.dslauncher.data.qwen

import android.content.Context
import com.dslauncher.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Qwen Token 自动续期器 —— 解决「网页端 access_token 只有 ~15 分钟寿命，
 * 过期就要重新登录」的问题。
 *
 * ## 原理（从线上前端 qwen-chat-fe 0.3.16 反解 + 实测验证）
 *
 * 官方前端的 `TokenManager.shouldRefresh()` 会在
 * `expiresAt - now < 150s(H5)/180s(Web)` 时调
 * `GET https://auth.qwen.ai/api/v2/auths/refresh` 换新 token，
 * **鉴权靠 Cookie（`token` cookie）而不是 Bearer**，返回
 * `{"success":true,"data":{"access_token":"…","expires_at":<秒>}}`。
 *
 * 所以只要在登录时把 WebView 的 Cookie 一起入库，就能在服务端侧
 * 无限续期 —— 不需要保存账号密码（对比 SuperAPI 用「auto sign-in」重登的做法，
 * 这里完全不碰密码，更安全）。
 *
 * ## 两条续期通道
 * 1. **页面内续期**（[QwenBrowser.refreshInPage]）：真 Chromium 指纹 + credentials:include，
 *    成功率最高；仅对「浏览器当前登录的那个账号」有效。
 * 2. **OkHttp + 账号 Cookie**（[QwenWebClient.refreshTokenAuto]）：池里任意账号都能用，
 *    靠登录时抓到的 Cookie 鉴权。
 *
 * 失败不立刻判死：连续失败 3 次（[QwenAccountStore.REFRESH_FAIL_LIMIT]）才标记
 * 「需要重新登录」，避免一次网络抖动就把号停用。
 */
class QwenTokenRefresher(
    private val context: Context,
    private val client: QwenWebClient,
    private val accounts: QwenAccountStore,
    private val settings: SettingsStore
) {

    /** 一轮续期的结果。 */
    data class Outcome(
        val ok: Int,
        val failed: Int,
        val noCookie: Int,
        val messages: List<String>
    ) {
        val summary: String
            get() = buildString {
                append("续期结果：成功 $ok")
                if (failed > 0) append(" · 失败 $failed")
                if (noCookie > 0) append(" · 未存 Cookie $noCookie（重新登录一次即可自动续期）")
                messages.take(3).forEach { append("\n· ").append(it) }
            }
    }

    /** 最近一次错误/提示（UI 展示）。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 最近一次续期轮询时间戳。 */
    @Volatile
    var lastRunAt: Long = 0L
        private set

    /** 页面会话最近一次续期时间戳（页面 token 拿不到过期时间时用来兜底定时）。 */
    @Volatile
    private var lastPageRefreshAt: Long = 0L

    private var loop: Job? = null

    /** 提前多久续期（分钟 → 毫秒）。 */
    private fun leadMs(): Long = settings.qwenRefreshLeadMin.coerceIn(1, 60) * 60_000L

    // ---------------------------------------------------------------- 对外 API

    /**
     * 拿到一个「token 一定新鲜」的账号对象（临近过期就先续期）。
     * 供请求链路在取号后调用，返回对象可能已是换过 token 的新版本。
     */
    suspend fun ensureFresh(acc: QwenAccount): QwenAccount {
        if (!settings.qwenAutoRefresh || !acc.canAutoRefresh) return acc
        val now = System.currentTimeMillis()
        if (!acc.nearExpiry(now, leadMs()) && !acc.needRelogin) return acc
        return if (refresh(acc, force = true)) {
            accounts.list().firstOrNull { it.id == acc.id } ?: acc
        } else {
            acc
        }
    }

    /** 续期单个账号；[force] = true 时忽略「自动续期」总开关（手动点击用）。 */
    suspend fun refresh(acc: QwenAccount, force: Boolean = false): Boolean {
        if (!settings.qwenAutoRefresh && !force) return false
        if (!acc.canAutoRefresh) {
            lastError = "「${acc.display}」没存 Cookie，无法自动续期 —— 重新登录一次即可（只此一次）"
            return false
        }
        // 通道①：浏览器当前登录的正是这个号 → 页面内续期（真指纹，最稳）
        if (acc.token == client.browser?.cachedToken()) {
            val r = runCatching { client.browser?.refreshInPage() }.getOrNull()
            if (r != null) {
                accounts.updateToken(acc.id, r.token, r.expiresAt, r.cookies)
                lastError = null
                lastPageRefreshAt = System.currentTimeMillis()
                return true
            }
        }
        // 通道②：OkHttp + 该账号自己的 Cookie
        val r = runCatching { client.refreshTokenAuto(acc.cookies) }
        val got = r.getOrNull()
        if (got != null) {
            accounts.updateToken(acc.id, got.token, got.expiresAt, got.cookies)
            lastError = null
            return true
        }
        val e = r.exceptionOrNull()
        accounts.markRefreshFail(acc.id)
        lastError = "「${acc.display}」续期失败：${e?.message ?: "未知错误"}"
        return false
    }

    /**
     * 续期所有「临近过期 / 已过期 / 待重登」的账号（每轮最多 [limit] 个，避免长时间占用）。
     * [force] = true 时无视有效期，池里所有存了 Cookie 的号都刷一遍。
     */
    suspend fun refreshDue(force: Boolean = false, limit: Int = 8): Outcome {
        val now = System.currentTimeMillis()
        val due = accounts.dueForRefresh(now, leadMs(), force)
        val noCookie = if (force) accounts.list().count { !it.canAutoRefresh } else 0
        var ok = 0
        var failed = 0
        val msgs = ArrayList<String>()
        due.take(limit).forEach { acc ->
            if (refresh(acc, force = force)) ok++ else {
                failed++
                lastError?.let { if (msgs.size < 3) msgs.add(it) }
            }
        }
        lastRunAt = System.currentTimeMillis()
        return Outcome(ok, failed, noCookie, msgs)
    }

    /** 手动刷新「浏览器页面自己的会话」（浏览器通道靠它续命）。 */
    suspend fun refreshPageSession(): Boolean {
        val b = client.browser ?: return false
        if (!b.isLoaded()) return false
        val r = runCatching { b.refreshInPage() }.getOrNull() ?: return false
        lastPageRefreshAt = System.currentTimeMillis()
        // 页面 token 变化已由 QwenBrowser.onTokenChanged 同步给仓储层 → 账号池自动跟着更新
        lastError = if (r.expiresAt > 0L) {
            "页面会话已续期，剩余 ${(r.expiresAt - System.currentTimeMillis()) / 60_000} 分钟"
        } else "页面会话已续期"
        return true
    }

    /** 启动后台定时续期（幂等）。[intervalMs] 默认 60s 轮询一次。 */
    fun start(scope: CoroutineScope, intervalMs: Long = 60_000L) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            delay(15_000) // 别和首屏/登录抢资源
            while (isActive) {
                runCatching { tick() }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun tick() {
        if (!settings.qwenAutoRefresh) return
        refreshDue()
        // 页面会话兜底续期：能读到过期时间就按阈值，读不到就每 10 分钟续一次。
        val b = client.browser ?: return
        if (!settings.qwenUseBrowser || !b.isLoaded()) return
        if (b.cachedToken().isNullOrBlank()) return
        val exp = runCatching { b.readExpiry() }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        val near = exp > 0L && exp - now <= leadMs()
        val stale = exp <= 0L && now - lastPageRefreshAt > 10 * 60_000L
        if (near || stale) runCatching { b.refreshInPage() }.onSuccess { lastPageRefreshAt = now }
    }

    /** 让提示信息可以带到 UI（例如 snackbar）。 */
    fun lastErrorText(): String? = lastError
}
