package com.dslauncher.data.qwen

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单个 Qwen 账号条目。token = chat.qwen.ai 网页登录后的 Bearer token
 * （localStorage `token`），由 QwenLoginScreen 抓取并校验后写入。
 */
data class QwenAccount(
    val id: String,
    val label: String,
    val token: String,
    /** 账号邮箱（登录后自动拉取，用于列表展示） */
    val email: String? = null,
    val enabled: Boolean = true,
    val failCount: Int = 0,
    /** 本号已发出的请求数（用于配额限制） */
    val usedCount: Int = 0,
    /** 冷却截止时间戳（毫秒）；< now 表示可用 */
    val cooldownUntil: Long = 0L,
    /** token 失效后置位，重新登录后由 [QwenAccountStore.add] 清除 */
    val needRelogin: Boolean = false,
    /** 累计请求上限；<=0 表示不限 */
    val quota: Int = 0,
    /** token 是否已失效（校验状态时实测得出） */
    val tokenInvalid: Boolean = false,
    /**
     * chat.qwen.ai / auth.qwen.ai 域的 Cookie 串（登录时从 WebView 的 CookieManager 捕获）。
     *
     * **自动续期的钥匙**：网页端 access_token 只有 ~15 分钟寿命，官方前端靠
     * `GET https://auth.qwen.ai/api/v2/auths/refresh`（凭 `token` cookie）换新 token。
     * 没有 Cookie 的账号只能重新登录一次。
     */
    val cookies: String = "",
    /** access_token 过期时间戳（毫秒）；0 = 未知（视为长期有效，不做预续期）。 */
    val expiresAt: Long = 0L,
    /** 最近一次续期成功的时间戳（毫秒），仅用于 UI 展示。 */
    val lastRefreshAt: Long = 0L,
    /** 连续续期失败次数；达到阈值才判定「需要重新登录」。 */
    val refreshFail: Int = 0
) {
    /** 列表展示名：优先邮箱 → 兜底 label。 */
    val display: String
        get() = email?.takeIf { it.isNotBlank() } ?: label

    /** 是否可用。判定只看 `cooldownUntil <= now`（0 或已过期都放行）。 */
    fun available(now: Long): Boolean =
        enabled && token.isNotBlank() && !tokenInvalid && cooldownUntil <= now && quotaLeft() != 0

    /** 剩余配额；quota<=0 时返回 Int.MAX_VALUE（不限） */
    fun quotaLeft(): Int = if (quota <= 0) Int.MAX_VALUE else (quota - usedCount).coerceAtLeast(0)

    /** 冷却是否已到期（可自动恢复）。 */
    fun cooldownExpired(now: Long): Boolean = cooldownUntil in 1..now

    /** token 剩余有效期（毫秒）；过期时间未知时返回 Long.MAX_VALUE。 */
    fun ttlMs(now: Long): Long = if (expiresAt <= 0L) Long.MAX_VALUE else expiresAt - now

    /** 是否已过期（有过期时间且已过）。 */
    fun expired(now: Long): Boolean = expiresAt in 1..now

    /** 是否临近过期（距离过期不足 [leadMs]）。未知过期时间视为不需要续期。 */
    fun nearExpiry(now: Long, leadMs: Long): Boolean =
        expiresAt > 0L && expiresAt - now <= leadMs

    /** 是否具备自动续期条件（存了 Cookie）。 */
    val canAutoRefresh: Boolean get() = cookies.isNotBlank()

    /** 有效期文案（UI 展示）：如 `剩余 12:34` / `已过期` / `未记录`。 */
    fun ttlText(now: Long): String {
        if (expiresAt <= 0L) return "未记录有效期"
        val left = expiresAt - now
        if (left <= 0L) return "已过期"
        val totalSec = left / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "剩余 ${h}h${m}m" else "剩余 %d:%02d".format(m, s)
    }
}

/**
 * Qwen 多账号池。机制镜像 DeepSeek 的 [com.dslauncher.data.AccountStore]：
 * 请求配额 + 冷却 + 失效停用 + 失败计数 + 轮流/随机取号；
 * 不含 DS 特有的设备指纹与禁言逻辑（chat.qwen.ai 无对应信号）。
 */
class QwenAccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("qwen_accounts", Context.MODE_PRIVATE)
    private val rrCounter = AtomicInteger(0)

    /** 读全部账号（保序）。 */
    fun list(): List<QwenAccount> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val tk = o.optString("token")
                if (id.isBlank() || tk.isBlank()) null
                else QwenAccount(
                    id = id,
                    label = o.optString("label", "账号"),
                    token = tk,
                    email = o.optString("email").takeIf { it.isNotBlank() },
                    enabled = o.optBoolean("enabled", true),
                    failCount = o.optInt("failCount", 0),
                    usedCount = o.optInt("usedCount", 0),
                    cooldownUntil = o.optLong("cooldownUntil", 0L),
                    needRelogin = o.optBoolean("needRelogin", false),
                    quota = o.optInt("quota", 0),
                    tokenInvalid = o.optBoolean("tokenInvalid", false),
                    cookies = o.optString("cookies", ""),
                    expiresAt = o.optLong("expiresAt", 0L),
                    lastRefreshAt = o.optLong("lastRefreshAt", 0L),
                    refreshFail = o.optInt("refreshFail", 0)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<QwenAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("token", a.token)
                    .put("email", a.email ?: "")
                    .put("enabled", a.enabled)
                    .put("failCount", a.failCount)
                    .put("usedCount", a.usedCount)
                    .put("cooldownUntil", a.cooldownUntil)
                    .put("needRelogin", a.needRelogin)
                    .put("quota", a.quota)
                    .put("tokenInvalid", a.tokenInvalid)
                    .put("cookies", a.cookies)
                    .put("expiresAt", a.expiresAt)
                    .put("lastRefreshAt", a.lastRefreshAt)
                    .put("refreshFail", a.refreshFail)
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /**
     * 同 token 原地更新（重新登录不产生重复项），否则新增。返回账号 id。
     *
     * [cookies] / [expiresAt] 来自登录时的 WebView 抓取 —— 这是后续自动续期能跑起来的前提，
     * 重新登录时若抓到新 Cookie 会一并覆盖，并把续期失败计数清零。
     */
    fun add(
        token: String,
        label: String? = null,
        cookies: String = "",
        expiresAt: Long = 0L
    ): String {
        val list = list().toMutableList()
        val idx = list.indexOfFirst { it.token == token }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(
                enabled = true, failCount = 0, usedCount = 0,
                cooldownUntil = 0L, needRelogin = false, tokenInvalid = false,
                refreshFail = 0,
                cookies = cookies.ifBlank { old.cookies },
                expiresAt = if (expiresAt > 0L) expiresAt else old.expiresAt,
                label = label?.takeIf { it.isNotBlank() } ?: old.label
            )
            save(list)
            return old.id
        }
        val id = UUID.randomUUID().toString().take(8)
        val name = label?.takeIf { it.isNotBlank() } ?: "Qwen 账号 ${list.size + 1}"
        list.add(
            QwenAccount(
                id = id, label = name, token = token, quota = defaultQuota,
                cookies = cookies, expiresAt = expiresAt
            )
        )
        save(list)
        return id
    }

    /** 按 token 找账号。 */
    fun findByToken(token: String): QwenAccount? =
        token.takeIf { it.isNotBlank() }?.let { t -> list().firstOrNull { it.token == t } }

    /**
     * 续期成功：换上新 token / 过期时间 / （可能轮换过的）Cookie，并清掉一切失效标记。
     */
    fun updateToken(id: String, token: String, expiresAt: Long, cookies: String? = null) =
        update(id) { a ->
            a.copy(
                token = token,
                expiresAt = if (expiresAt > 0L) expiresAt else a.expiresAt,
                cookies = cookies?.takeIf { it.isNotBlank() } ?: a.cookies,
                lastRefreshAt = System.currentTimeMillis(),
                refreshFail = 0,
                tokenInvalid = false,
                needRelogin = false,
                enabled = true
            )
        }

    /** 记一次续期失败；连续失败到阈值才判定「需要重新登录」并停用该号。 */
    fun markRefreshFail(id: String, limit: Int = REFRESH_FAIL_LIMIT) =
        update(id) { a ->
            val n = a.refreshFail + 1
            if (n >= limit) {
                a.copy(refreshFail = n, tokenInvalid = true, needRelogin = true, enabled = false)
            } else {
                a.copy(refreshFail = n)
            }
        }

    /** 只补录 Cookie（老账号重新登录一次后即可开启自动续期）。 */
    fun setCookies(id: String, cookies: String, expiresAt: Long = 0L) =
        update(id) { a ->
            a.copy(
                cookies = cookies.ifBlank { a.cookies },
                expiresAt = if (expiresAt > 0L) expiresAt else a.expiresAt,
                refreshFail = 0
            )
        }

    /** 找出「需要/值得续期」的账号：有 Cookie，且（临近过期 或 已标记失效 或 force）。 */
    fun dueForRefresh(now: Long, leadMs: Long, force: Boolean = false): List<QwenAccount> =
        list().filter { a ->
            a.canAutoRefresh && (force || a.nearExpiry(now, leadMs) || a.expired(now) || a.needRelogin)
        }

    /** 统计：具备自动续期条件的账号数。 */
    fun autoRefreshCapableCount(): Int = list().count { it.canAutoRefresh }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun update(id: String, transform: (QwenAccount) -> QwenAccount) {
        save(list().map { if (it.id == id) transform(it) else it })
    }

    fun setEnabled(id: String, enabled: Boolean) =
        update(id) { it.copy(enabled = enabled, failCount = 0) }

    /** 手动设某个号的配额上限（0 = 不限），同时清零已用次数避免立刻进冷却。 */
    fun setQuota(id: String, quota: Int) =
        update(id) { it.copy(quota = quota.coerceAtLeast(0), usedCount = 0, cooldownUntil = 0L) }

    /**
     * 「校验状态」写入 token 有效性。
     * invalid = true → 标记失效并停用；invalid = false → 清除失效标记。
     * 不改动 enabled，避免把用户手动停用的号又自动打开。
     */
    fun setTokenInvalid(id: String, invalid: Boolean) =
        update(id) { a ->
            if (invalid) a.copy(tokenInvalid = true, needRelogin = true, enabled = false)
            else a.copy(tokenInvalid = false, needRelogin = false)
        }

    /** 应用到全部账号的配额（0 = 不限），同时清零已用次数。 */
    fun applyQuotaToAll(quota: Int) =
        save(list().map { it.copy(quota = quota.coerceAtLeast(0), usedCount = 0, cooldownUntil = 0L) })

    /** 清空某号已用次数（重置配额计数）。 */
    fun resetUsage(id: String) = update(id) { it.copy(usedCount = 0, cooldownUntil = 0L) }

    fun clearFail(id: String) = update(id) { it.copy(failCount = 0) }

    /**
     * 记一次成功：清零失败计数，递增已用次数；
     * 若已用次数达到配额上限，自动进入冷却（暂时禁止被请求）。
     */
    fun markSuccess(id: String) {
        val coolMs = prefs.getLong(KEY_QUOTA_COOLDOWN, DEFAULT_QUOTA_COOLDOWN)
        update(id) { a ->
            val used = a.usedCount + 1
            val hitQuota = a.quota > 0 && used >= a.quota
            a.copy(
                failCount = 0,
                usedCount = used,
                cooldownUntil = if (hitQuota) System.currentTimeMillis() + coolMs else a.cooldownUntil
            )
        }
    }

    /** 记一次失败；连续失败到阈值自动停用。 */
    fun markFail(id: String) {
        val limit = prefs.getInt(KEY_FAIL_LIMIT, DEFAULT_FAIL_LIMIT)
        update(id) { a ->
            val n = a.failCount + 1
            a.copy(failCount = n, enabled = if (n >= limit) false else a.enabled)
        }
    }

    /** token 失效：标记待重登 + 停用。 */
    fun markTokenInvalid(id: String) =
        update(id) { it.copy(needRelogin = true, enabled = false) }

    /** 至少有一个可立即使用的账号。 */
    fun hasUsable(): Boolean {
        sweepExpired()
        val now = System.currentTimeMillis()
        return list().any { it.available(now) && !it.needRelogin }
    }

    // ---- 取号策略 ----

    var mode: com.dslauncher.data.PickMode
        get() {
            val raw = prefs.getString(KEY_MODE, null)
                ?: com.dslauncher.data.PickMode.ROUND_ROBIN.name
            return runCatching { com.dslauncher.data.PickMode.valueOf(raw) }
                .getOrDefault(com.dslauncher.data.PickMode.ROUND_ROBIN)
        }
        set(v) = prefs.edit().putString(KEY_MODE, v.name).apply()

    /** 默认配额：新账号的请求上限（0 = 不限）。 */
    var defaultQuota: Int
        get() = prefs.getInt(KEY_DEFAULT_QUOTA, 0)
        set(v) = prefs.edit().putInt(KEY_DEFAULT_QUOTA, v.coerceAtLeast(0)).apply()

    /** 配额刷新间隔（毫秒）：配额用尽后等待这段时间自动恢复。 */
    var quotaCooldownMs: Long
        get() = prefs.getLong(KEY_QUOTA_COOLDOWN, DEFAULT_QUOTA_COOLDOWN)
        set(v) = prefs.edit().putLong(KEY_QUOTA_COOLDOWN, v).apply()

    /** 清理已过期冷却。返回被恢复的账号数。 */
    fun sweepExpired(): Int {
        val now = System.currentTimeMillis()
        var changed = 0
        val updated = list().map { a ->
            if (a.cooldownExpired(now)) {
                changed++
                // 冷却到期 = 新配额窗口开始：同时把已用次数清零
                a.copy(cooldownUntil = 0L, usedCount = 0)
            } else a
        }
        if (changed > 0) save(updated)
        return changed
    }

    /** 诊断：为什么没有可用账号。用于给出可操作的报错。 */
    fun diagnosis(): String {
        val now = System.currentTimeMillis()
        val all = list()
        if (all.isEmpty()) return "Qwen 账号池为空，请先在「账号池」里登录添加 Qwen 账号"
        val disabled = all.count { !it.enabled }
        val cooling = all.count { it.enabled && it.cooldownUntil > now }
        val quotaOut = all.count { it.enabled && it.quota > 0 && it.quotaLeft() == 0 }
        val relogin = all.count { it.enabled && it.needRelogin }
        val invalid = all.count { it.tokenInvalid }
        val noCookie = all.count { it.cookies.isBlank() }
        return "Qwen 账号池暂无可用账号（共 ${all.size} 个：停用 $disabled / 冷却中 $cooling / " +
            "配额用尽 $quotaOut / 待重登 $relogin / Token 失效 $invalid）" +
            if (noCookie > 0) "；另有 $noCookie 个账号未存 Cookie（无法自动续期，重新登录一次即可）" else ""
    }

    /** 返回本次该用的账号（跳过停用/冷却/配额耗尽）；无可用返回 null。 */
    fun pick(): QwenAccount? {
        sweepExpired()
        val now = System.currentTimeMillis()
        val usable = list().filter { it.available(now) }
        if (usable.isEmpty()) return null
        // 优先选不需要重登的号；needRelogin 的只作兜底
        val all = usable.filter { !it.needRelogin }.ifEmpty { usable }
        return when (mode) {
            com.dslauncher.data.PickMode.ROUND_ROBIN ->
                all[rrCounter.getAndIncrement().mod(all.size)]
            com.dslauncher.data.PickMode.RANDOM -> all.random()
        }
    }

    /** 写入账号身份信息（邮箱），用于列表展示。掩码值不覆盖真实值。 */
    fun setIdentity(id: String, email: String?) =
        update(id) { a ->
            when {
                email.isNullOrBlank() -> a
                email.contains('*') -> a.copy(email = a.email ?: email)
                else -> a.copy(email = email)
            }
        }

    /** 账号池列表显示模式：与 DS 池共用同一偏好，两个池的列表形态保持一致。 */
    var compactAccounts: Boolean
        get() = prefs.getBoolean("compact_accounts", true)
        set(v) = prefs.edit().putBoolean("compact_accounts", v).apply()

    companion object {
        private const val KEY_LIST = "accounts_json"
        private const val KEY_MODE = "pick_mode"
        private const val KEY_FAIL_LIMIT = "fail_limit"
        private const val KEY_DEFAULT_QUOTA = "default_quota"
        private const val KEY_QUOTA_COOLDOWN = "quota_cooldown"
        const val DEFAULT_FAIL_LIMIT = 3
        /** 配额耗尽默认冷却 10 分钟 */
        const val DEFAULT_QUOTA_COOLDOWN = 10 * 60 * 1000L
        /** 连续续期失败多少次才判定「Token 失效，需要重新登录」 */
        const val REFRESH_FAIL_LIMIT = 3
    }
}
