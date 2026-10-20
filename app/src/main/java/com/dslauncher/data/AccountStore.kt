package com.dslauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单个账号条目。字段对齐账号池导出格式（credential / consecutive_failures /
 * cooldown / lease / quota / auto_refresh_token）。
 */
data class Account(
    val id: String,
    val label: String,
    val token: String,
    /** credential.device_id —— 官网登录时生成的设备标识，刷新/复用要用 */
    val deviceId: String = "",
    /** 账号邮箱（登录后自动拉取，用于列表展示） */
    val email: String? = null,
    /** 账号手机号（登录后自动拉取，用于列表展示） */
    val phone: String? = null,
    val enabled: Boolean = true,
    val failCount: Int = 0,
    /** 本号已发出的请求数（用于配额限制） */
    val usedCount: Int = 0,
    /** 冷却截止时间戳（毫秒）；< now 表示可用 */
    val cooldownUntil: Long = 0L,
    /** 是否需要在下次使用时重新登录（token 失效后置位，配合自动刷新） */    val needRelogin: Boolean = false,
    /** 累计请求上限；<=0 表示不限 */
    val quota: Int = 0,
    /** 是否被官网禁言/封禁（校验账号状态后置位） */
    val muted: Boolean = false,
    /** 禁言截止时间戳（毫秒）；0 = 未知/永久 */
    val muteUntil: Long = 0L,
    /**
     * token 是否已失效（「校验状态」时调官网接口实测得出）。
     *
     * 之前校验只看「禁言」，token 早就过期了却仍显示绿色可用、还被取号发请求，
     * 真正发出去才报 invalid token。现在把 token 有效性一并纳入校验：
     * 失效即标红、停用、不再参与取号，重新登录后自动清除。
     */
    val tokenInvalid: Boolean = false,
    /** 设备指纹上次更换的时间戳（毫秒）；0 = 老数据，下次清理时补齐并纳入轮换。 */
    val fpUpdatedAt: Long = 0L
) {
    /** 列表展示名：优先邮箱 → 手机号 → 昵称 → 兜底 label。 */
    val display: String
        get() = email?.takeIf { it.isNotBlank() }
            ?: phone?.takeIf { it.isNotBlank() }
            ?: label

    /**
     * 是否可用。
     *
     * 注意：这里**不能**写 `!cooldownExpired(now)`。
     * cooldownExpired = 「冷却已经过去」，`!cooldownExpired` 恰好把「刚过期」的号判成不可用，
     * 与 `cooldownUntil <= now` 自相矛盾 —— 表现就是「冷却早过去了还一直显示冷却」。
     * 判定只看 `cooldownUntil <= now`（0 或已过期都放行）。
     */
    fun available(now: Long): Boolean =
        enabled && token.isNotBlank() && !tokenInvalid && cooldownUntil <= now &&
            quotaLeft() != 0 && !mutedNow(now)

    /** 剩余配额；quota<=0 时返回 Int.MAX_VALUE（不限） */
    fun quotaLeft(): Int = if (quota <= 0) Int.MAX_VALUE else (quota - usedCount).coerceAtLeast(0)

    /**
     * 当前是否真的处于禁言。
     * 关键：禁言有截止时间，到期即自动失效 —— 之前只看 muted 布尔值，
     * 禁言早就结束了还永久排除该号，最终全池「无可用账号」。
     */
    fun mutedNow(now: Long): Boolean = muted && (muteUntil <= 0L || muteUntil > now)

    /**
     * 剩余禁言毫秒数；未禁言、已到期或「永久禁言（无截止时间）」返回 null。
     * 用于列表上显示「禁言中 · 剩余 2 小时 13 分」。
     */
    fun muteRemainingMs(now: Long): Long? =
        if (mutedNow(now) && muteUntil > now) muteUntil - now else null

    /** 冷却是否已到期（可自动恢复）。 */
    fun cooldownExpired(now: Long): Boolean = cooldownUntil in 1..now
}

/** 把一段剩余时长（毫秒）格式化成人类可读文本，如「2 小时 13 分」。 */
fun formatRemaining(ms: Long): String {
    if (ms <= 0L) return "0 秒"
    val totalSec = ms / 1000L
    val day = totalSec / 86400L
    val hour = (totalSec % 86400L) / 3600L
    val min = (totalSec % 3600L) / 60L
    val sec = totalSec % 60L
    return when {
        day > 0L -> "$day 天 $hour 小时"
        hour > 0L -> "$hour 小时 $min 分"
        min > 0L -> "$min 分 $sec 秒"
        else -> "$sec 秒"
    }
}

/** 取号策略（已移除「固定」策略）。 */
enum class PickMode { ROUND_ROBIN, RANDOM }

/**
 * 多账号池。
 *
 * 与上一版的区别：加入**请求配额**（每个号请求 N 次后自动冷却）、**冷却时间**、
 * **token 失效标记 needRelogin**（配合上层自动重新登录）、**失败分类计数**。
 */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("ds_accounts", Context.MODE_PRIVATE)
    private val rrCounter = AtomicInteger(0)

    /** 读全部账号（保序）。 */
    fun list(): List<Account> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val tk = o.optString("token")
                if (id.isBlank() || tk.isBlank()) null
                else Account(
                    id = id,
                    label = o.optString("label", "账号"),
                    token = tk,
                    deviceId = o.optString("deviceId", ""),
                    email = o.optString("email").takeIf { it.isNotBlank() },
                    phone = o.optString("phone").takeIf { it.isNotBlank() },
                    enabled = o.optBoolean("enabled", true),
                    failCount = o.optInt("failCount", 0),
                    usedCount = o.optInt("usedCount", 0),
                    cooldownUntil = o.optLong("cooldownUntil", 0L),
                    needRelogin = o.optBoolean("needRelogin", false),
                    quota = o.optInt("quota", 0),
                    muted = o.optBoolean("muted", false),
                    muteUntil = o.optLong("muteUntil", 0L),
                    tokenInvalid = o.optBoolean("tokenInvalid", false),
                    fpUpdatedAt = o.optLong("fpUpdatedAt", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("token", a.token)
                    .put("deviceId", a.deviceId)
                    .put("email", a.email ?: "")
                    .put("phone", a.phone ?: "")
                    .put("enabled", a.enabled)
                    .put("failCount", a.failCount)
                    .put("usedCount", a.usedCount)
                    .put("cooldownUntil", a.cooldownUntil)
                    .put("needRelogin", a.needRelogin)
                    .put("quota", a.quota)
                    .put("muted", a.muted)
                    .put("muteUntil", a.muteUntil)
                    .put("tokenInvalid", a.tokenInvalid)
                    .put("fpUpdatedAt", a.fpUpdatedAt)
            )
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** 同 token 原地更新（重新登录不产生重复项），否则新增。返回账号 id。 */
    fun add(token: String, label: String? = null, deviceId: String = ""): String {
        val list = list().toMutableList()
        // 已占用指纹 = 池内其他账号的指纹；新账号绝不与他人共用设备
        val taken = list.map { it.deviceId }.filter { it.isNotBlank() }.toSet()
        val idx = list.indexOfFirst { it.token == token }
        if (idx >= 0) {
            val old = list[idx]
            val fp = deviceId.ifBlank { old.deviceId.ifBlank { DeviceFingerprints.newFingerprint(taken) } }
            list[idx] = old.copy(
                enabled = true, failCount = 0, usedCount = 0,
                cooldownUntil = 0L, needRelogin = false, tokenInvalid = false,
                deviceId = fp,
                fpUpdatedAt = if (old.deviceId == fp) old.fpUpdatedAt else System.currentTimeMillis(),
                label = label?.takeIf { it.isNotBlank() } ?: old.label
            )
            save(list)
            return old.id
        }
        val id = UUID.randomUUID().toString().take(8)
        val name = label?.takeIf { it.isNotBlank() } ?: "账号 ${list.size + 1}"
        list.add(
            Account(
                id = id, label = name, token = token,
                deviceId = deviceId.ifBlank { DeviceFingerprints.newFingerprint(taken) },
                fpUpdatedAt = System.currentTimeMillis(),
                quota = defaultQuota          // 新账号套用默认配额
            )
        )
        save(list)
        return id
    }

    /** 导入结果统计。 */
    data class ImportResult(val added: Int, val updated: Int, val invalid: Int) {
        val total: Int get() = added + updated + invalid
    }

    /**
     * 批量导入账号（兼容本 App 的导出格式）。
     *
     * 支持：
     *  1) {"format":"deekseep_account_export","version":1,"accounts":[{"name":..,"credential":{...}}]}
     *  2) {"accounts":[...]}（任意带 accounts 数组的顶层对象）
     *  3) [{...},{...}]（裸数组；元素可为 credential 本身，或带 credential 的对象）
     *  4) 单个账号对象（含 token）
     *
     * credential 字段：token（必需）/ id / email / mobile_number / device_id /
     * chat_status.is_muted / chat_status.mute_until。
     * 去重：同 token（或同 credential.id）原地更新，不产生重复项。
     * 导出里的邮箱/手机号常带掩码（ddd8********nsn@gmail.com），带 * 的值不会
     * 覆盖本地已有的真实值。
     */
    fun importExport(text: String): ImportResult {
        val entries = parseImportEntries(text)
        var added = 0
        var updated = 0
        var invalid = 0
        val list = list().toMutableList()
        val taken = list.map { it.deviceId }.filter { it.isNotBlank() }.toSet()
        for (e in entries) {
            val token = e.optString("token").trim()
            if (token.isBlank()) { invalid++; continue }
            val credId = e.optString("credId").trim()
            val email = e.optString("email").trim().takeIf { it.isNotBlank() && !it.contains('*') }
            val phone = e.optString("phone").trim().takeIf { it.isNotBlank() && !it.contains('*') }
            val deviceId = e.optString("deviceId").trim()
            val muted = e.optBoolean("muted", false)
            val muteUntil = e.optLong("muteUntil", 0L)
            val label = e.optString("name").trim().takeIf { it.isNotBlank() }
            val idx = list.indexOfFirst { it.token == token || (credId.isNotBlank() && it.id == credId) }
            if (idx >= 0) {
                // 已存在：更新 token/身份/状态，保留本地的用量与配额
                val old = list[idx]
                list[idx] = old.copy(
                    token = token,
                    email = email ?: old.email,
                    phone = phone ?: old.phone,
                    deviceId = deviceId.ifBlank { old.deviceId },
                    label = label ?: old.label,
                    muted = muted || old.muted,
                    muteUntil = if (muteUntil > 0) muteUntil else old.muteUntil,
                    enabled = true,
                    needRelogin = false
                )
                updated++
            } else {
                list.add(
                    Account(
                        // 官方 credential.id 存在就沿用（稳定、可复现），否则生成短随机 id
                        id = credId.ifBlank { UUID.randomUUID().toString().take(8) },
                        label = label ?: email ?: phone ?: "导入账号",
                        token = token,
                        deviceId = deviceId.ifBlank { DeviceFingerprints.newFingerprint(taken) },
                        fpUpdatedAt = System.currentTimeMillis(),
                        email = email,
                        phone = phone,
                        muted = muted,
                        muteUntil = muteUntil,
                        quota = defaultQuota
                    )
                )
                added++
            }
        }
        if (added > 0 || updated > 0) save(list)
        return ImportResult(added, updated, invalid)
    }

    /** 解析导入文本为若干扁平账号对象。 */
    private fun parseImportEntries(text: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return out
        val rootObj = runCatching { JSONObject(trimmed) }.getOrNull()
        val rootArr = runCatching { JSONArray(trimmed) }.getOrNull()
        val arr = rootObj?.optJSONArray("accounts") ?: rootObj?.optJSONArray("credentials") ?: rootArr
        if (arr == null) {
            rootObj?.let { out.add(normalizeEntry(it)) }
            return out
        }
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.add(normalizeEntry(item))
        }
        return out
    }

    /**
     * 把导出的一个条目归一化成扁平结构：
     * token / credId / name / email / phone / deviceId / muted / muteUntil。
     * 兼容 {name, credential:{...}}、credential 平铺、以及 credential 自身三种形态。
     */
    private fun normalizeEntry(item: JSONObject): JSONObject {
        val outerName = item.optString("name").trim()
        val cred = item.optJSONObject("credential")
            ?: item.optJSONObject("chat")
            ?: item
        val chat = cred.optJSONObject("chat_status")
        val muted = cred.optInt("is_muted", 0) != 0 || (chat?.optInt("is_muted", 0) ?: 0) != 0
        val rawMute = when {
            chat != null && chat.has("mute_until") && !chat.isNull("mute_until") -> chat.optLong("mute_until", 0L)
            cred.has("mute_until") && !cred.isNull("mute_until") -> cred.optLong("mute_until", 0L)
            else -> 0L
        }
        // mute_until 单位不固定：< 1e12 视为「秒」，否则视为「毫秒」
        val muteUntil = if (rawMute <= 0L) 0L
        else if (rawMute < 1_000_000_000_000L) rawMute * 1000 else rawMute
        val phone = cred.optString("mobile_number").trim()
            .ifBlank { cred.optString("phone_number").trim() }
            .ifBlank { cred.optString("phone").trim() }
        return JSONObject()
            .put("token", cred.optString("token").trim())
            .put("credId", cred.optString("id").trim())
            .put("name", outerName)
            .put("email", cred.optString("email").trim())
            .put("phone", phone)
            .put("deviceId", cred.optString("device_id").trim())
            .put("muted", muted)
            .put("muteUntil", muteUntil)
    }

    /**
     * 导出账号池为可再次导入的 JSON。
     *
     * 格式与 [importExport] 支持的格式 1 对齐：
     *   {"format":"deekseep_account_export","version":1,"accounts":[{"name":..,"credential":{...}}]}
     * 含 token / id / email / mobile_number / device_id / chat_status，
     * 本 App 自身或兼容该格式的工具都能原样导回。
     */
    fun exportJson(): String {
        val arr = JSONArray()
        list().forEach { a ->
            val chat = JSONObject()
                .put("is_muted", if (a.muted) 1 else 0)
                .put("mute_until", a.muteUntil)
            val cred = JSONObject()
                .put("token", a.token)
                .put("id", a.id)
                .put("email", a.email ?: "")
                .put("mobile_number", a.phone ?: "")
                .put("device_id", a.deviceId)
                .put("is_muted", if (a.muted) 1 else 0)
                .put("mute_until", a.muteUntil)
                .put("chat_status", chat)
            arr.put(JSONObject().put("name", a.label).put("credential", cred))
        }
        return JSONObject()
            .put("format", "deekseep_account_export")
            .put("version", 1)
            .put("exported_at", System.currentTimeMillis())
            .put("accounts", arr)
            .toString(2)
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun update(id: String, transform: (Account) -> Account) {
        save(list().map { if (it.id == id) transform(it) else it })
    }

    fun setEnabled(id: String, enabled: Boolean) =
        update(id) { it.copy(enabled = enabled, failCount = 0) }

    /** 手动设某个号的配额上限（0 = 不限）。
     *  关键：同时把已用次数清零 —— 否则「已用 20 次、配额设成 5」会立刻判定
     *  超配额而进入冷却，表现就是「一加配额就冷却」。 */
    fun setQuota(id: String, quota: Int) =
        update(id) {
            it.copy(
                quota = quota.coerceAtLeast(0),
                usedCount = 0,
                cooldownUntil = 0L
            )
        }

    /** 校验后写入禁言状态。 */
    fun setMuted(id: String, muted: Boolean, muteUntil: Long = 0L) =
        update(id) { it.copy(muted = muted, muteUntil = muteUntil) }

    /**
     * 「校验状态」写入 token 有效性。
     *
     * invalid = true  → 标记失效并停用（重新登录后由 [add] 清除）；
     * invalid = false → 清除失效标记，恢复正常可被取号。
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

    /** token 失效：标记待重登 + 停用（自动刷新流程据此拉起登录）。 */
    fun markTokenInvalid(id: String) =
        update(id) { it.copy(needRelogin = true, enabled = false) }

    /** 需要重新登录的账号（供自动刷新流程处理）。 */
    fun pendingRelogin(): List<Account> = list().filter { it.needRelogin }

    fun hasAny(): Boolean =
        list().any { it.enabled && it.token.isNotBlank() && !it.needRelogin && !it.tokenInvalid }

    /** 至少有一个可立即使用的账号。 */
    fun hasUsable(): Boolean {
        // 先清理到期冷却/禁言；否则「刚过期的号」会被漏判为不可用。
        sweepExpired()
        val now = System.currentTimeMillis()
        return list().any { it.available(now) && !it.needRelogin }
    }

    // ---- 取号策略 ----

    var mode: PickMode
        get() {
            val raw = prefs.getString(KEY_MODE, PickMode.ROUND_ROBIN.name) ?: PickMode.ROUND_ROBIN.name
            // 兼容历史存储值：旧的 FIXED 策略已删除，统一回落到轮询
            if (raw.equals("FIXED", ignoreCase = true)) return PickMode.ROUND_ROBIN
            return runCatching { PickMode.valueOf(raw) }.getOrDefault(PickMode.ROUND_ROBIN)
        }
        set(v) = prefs.edit().putString(KEY_MODE, v.name).apply()

    /** 默认配额：新账号的请求上限（0 = 不限）。 */
    var defaultQuota: Int
        get() = prefs.getInt(KEY_DEFAULT_QUOTA, 0)
        set(v) = prefs.edit().putInt(KEY_DEFAULT_QUOTA, v.coerceAtLeast(0)).apply()

    /**
     * 配额刷新间隔（毫秒）。
     *
     * 语义：某个号配额用尽后，等待这段时间，**自动刷新配额**
     * （`sweepExpired()` 会把 usedCount 清零、账号重新可用）。
     * 历史字段名是「冷却时长」，现在统一叫「刷新时间」。
     */
    var quotaCooldownMs: Long
        get() = prefs.getLong(KEY_QUOTA_COOLDOWN, DEFAULT_QUOTA_COOLDOWN)
        set(v) = prefs.edit().putLong(KEY_QUOTA_COOLDOWN, v).apply()

    /** 配额刷新间隔的别名（新名字，与 quotaCooldownMs 同一存储）。 */
    var quotaRefreshMs: Long
        get() = quotaCooldownMs
        set(v) { quotaCooldownMs = v }

    /** 手动立刻刷新某号配额（等价于等待刷新时间到期后的自动行为）。 */
    fun refreshQuota(id: String) = resetUsage(id)

    /** 立刻刷新全部账号配额（把已用次数清零、恢复可用）。 */
    fun refreshQuotaAll() =
        save(list().map { it.copy(usedCount = 0, cooldownUntil = 0L) })

    /** 清理已过期状态：冷却到期、禁言到期。顺带执行指纹周期轮换。返回被恢复的账号数。 */
    fun sweepExpired(): Int {
        val fpChanged = sweepFingerprints()
        val now = System.currentTimeMillis()
        var changed = 0
        val updated = list().map { a ->
            var n = a
            if (n.cooldownExpired(now)) {
                // 冷却到期 = 新配额窗口开始：同时把已用次数清零。
                // 否则 quotaLeft() 仍为 0，账号被「永久排除」——
                // 表现就是「冷却早过去了，还一直显示冷却 / 配额用尽」。
                n = n.copy(cooldownUntil = 0L, usedCount = 0)
                changed++
            }
            if (n.muted && n.muteUntil in 1..now) {
                n = n.copy(muted = false, muteUntil = 0L); changed++
            }
            n
        }
        if (changed > 0) save(updated)
        return changed + fpChanged
    }

    /** 诊断：为什么没有可用账号。用于给出可操作的报错。 */
    fun diagnosis(): String {
        val now = System.currentTimeMillis()
        val all = list()
        if (all.isEmpty()) return "账号池为空，请先在「账号池」里登录添加账号"
        val disabled = all.count { !it.enabled }
        val cooling = all.count { it.enabled && it.cooldownUntil > now }
        val quotaOut = all.count { it.enabled && it.quota > 0 && it.quotaLeft() == 0 }
        val muted = all.count { it.enabled && it.mutedNow(now) }
        val relogin = all.count { it.enabled && it.needRelogin }
        val invalid = all.count { it.tokenInvalid }
        return "账号池暂无可用账号（共 ${all.size} 个：停用 $disabled / 冷却中 $cooling / " +
            "配额用尽 $quotaOut / 禁言 $muted / 待重登 $relogin / Token 失效 $invalid）"
    }

    /** 返回本次该用的账号（跳过停用/冷却/配额耗尽/禁言）；无可用返回 null。 */
    fun pick(): Account? {
        // 先清理到期的冷却与禁言，避免「早就恢复的号被永久排除」
        sweepExpired()
        val now = System.currentTimeMillis()
        val usable = list().filter { it.available(now) }
        if (usable.isEmpty()) return null
        // 优先选不需要重登的号；needRelogin 的只作兜底（不能因为一个标记就把池子掐死）
        val all = usable.filter { !it.needRelogin }.ifEmpty { usable }
        return when (mode) {
            PickMode.ROUND_ROBIN -> all[rrCounter.getAndIncrement().mod(all.size)]
            PickMode.RANDOM -> all.random()
        }
    }

    /** 取号得到的 token —— 唯一来源就是账号池，没有其它回退。
     *  之前这里会回退到 legacy_token，导致「账号删完了还能发消息」。
     *  现在删光账号 = 彻底没有凭证，必须重新登录。 */
    fun pickToken(): String? = pick()?.token

    /**
     * 写入账号身份信息（邮箱/手机号），用于列表展示。
     *
     * 掩码语义分两种（掩码 = 含 `*` 的串，例如 `ddd8********nsn@gmail.com`）：
     *  - 真实值：始终覆盖 —— 官网返回了真值就要更新到最新；
     *  - 掩码值：只在本地**还没有**该字段时补位 —— 掩码至少能认出账号绑的是哪个
     *    邮箱/号段，但绝不能拿它覆盖已拿到的真实值（旧实现的 bug：真邮箱被掩码
     *    覆盖成星号串且再也回不来）。
     */
    fun setIdentity(id: String, email: String?, phone: String?) =
        update(id) { a ->
            fun merge(old: String?, new: String?): String? = when {
                new.isNullOrBlank() -> old
                new.contains('*') -> old ?: new
                else -> new
            }
            a.copy(email = merge(a.email, email), phone = merge(a.phone, phone))
        }

    // ---- 设备指纹（自动更换） ----

    /** 自动更换指纹总闸：禁言 / Token 失效 / 到期时是否自动轮换。默认开启。 */
    var autoRotateFingerprint: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROTATE_FP, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_ROTATE_FP, v).apply()

    /** 指纹自动更换间隔（天）；过期由 [sweepFingerprints] 轮换。 */
    var fingerprintRotateDays: Int
        get() = prefs.getInt(KEY_FP_ROTATE_DAYS, DEFAULT_FP_ROTATE_DAYS)
        set(v) = prefs.edit().putInt(KEY_FP_ROTATE_DAYS, v.coerceIn(1, 365)).apply()

    /**
     * 给账号换一枚未占用的设备指纹。返回新指纹；账号不存在或指纹没变化返回 null。
     * 手动「更换指纹」与自动轮换（禁言 / Token 失效 / 到期）都走这里。
     */
    fun rotateFingerprint(id: String): String? {
        val taken = list().map { it.deviceId }.filter { it.isNotBlank() }.toSet()
        var assigned: String? = null
        update(id) { a ->
            val fp = DeviceFingerprints.newFingerprint(taken + a.deviceId)
            assigned = fp
            a.copy(deviceId = fp, fpUpdatedAt = System.currentTimeMillis())
        }
        return assigned
    }

    /**
     * 周期轮换：把「从没记录过更换时间（老数据，按 0 处理）」或「上次更换超过
     * [fingerprintRotateDays] 天」的账号换上新指纹。返回更换数量。
     * 开关关闭时直接跳过；由 [sweepExpired] 携带执行（它本来就高频运行）。
     */
    fun sweepFingerprints(): Int {
        if (!autoRotateFingerprint) return 0
        val intervalMs = fingerprintRotateDays * 86_400_000L
        val now = System.currentTimeMillis()
        val due = list().filter { now - it.fpUpdatedAt > intervalMs }.map { it.id }
        var changed = 0
        for (id in due) if (rotateFingerprint(id) != null) changed++
        return changed
    }

    /** token → 该账号当前设备指纹（登录请求体用）。无对应账号返回 null。 */
    fun deviceIdFor(token: String): String? =
        list().firstOrNull { it.token == token }?.deviceId?.takeIf { it.isNotBlank() }

    /**
     * 邮箱 → 该账号已绑定的设备指纹。
     *
     * 密码登录（`/users/login`）前用它取回指纹，保证「账号页显示的那枚 = 下次登录真正发出去的那枚」；
     * 取不到（新号/邮箱未回填）时由调用方分配一枚未占用的。
     */
    fun deviceIdForEmail(email: String): String? {
        val key = email.trim().lowercase()
        if (key.isBlank()) return null
        return list().firstOrNull { it.email?.trim()?.lowercase() == key }
            ?.deviceId?.takeIf { it.isNotBlank() }
    }

    /** 池内已被占用的全部设备指纹（新账号/新登录分配指纹时要排除它们）。 */
    fun usedFingerprints(): Set<String> =
        list().map { it.deviceId }.filter { it.isNotBlank() }.toSet()


    /** 账号池列表显示模式：true = 粗略（每行两个小卡），false = 详细（整行大卡）。 */
    var compactAccounts: Boolean
        get() = prefs.getBoolean("compact_accounts", true)
        set(v) = prefs.edit().putBoolean("compact_accounts", v).apply()

    companion object {
        private const val KEY_LIST = "accounts_json"
        private const val KEY_MODE = "pick_mode"
        private const val KEY_FAIL_LIMIT = "fail_limit"
        private const val KEY_DEFAULT_QUOTA = "default_quota"
        private const val KEY_QUOTA_COOLDOWN = "quota_cooldown"
        private const val KEY_AUTO_ROTATE_FP = "auto_rotate_fingerprint"
        private const val KEY_FP_ROTATE_DAYS = "fingerprint_rotate_days"
        const val DEFAULT_FAIL_LIMIT = 3
        /** 配额耗尽默认冷却 10 分钟 */
        const val DEFAULT_QUOTA_COOLDOWN = 10 * 60 * 1000L
        /** 设备指纹默认每 7 天自动更换 */
        const val DEFAULT_FP_ROTATE_DAYS = 7
    }
}
