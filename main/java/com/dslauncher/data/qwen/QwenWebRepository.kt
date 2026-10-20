package com.dslauncher.data.qwen


import android.content.Context
import com.dslauncher.data.MessageStore
import com.dslauncher.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** 本次请求的上游模型覆盖（由 LocalApiServer 按客户端请求的 model 设置）。\n * 不设置时回落到设置页里的默认模型。 */
internal var qwenModelOverride: String? = null

internal fun resolveQwenModel(fallback: String): String =
    qwenModelOverride?.takeIf { it.isNotBlank() } ?: fallback

/**
 * Qwen 网页端对话仓库。编排：传输选择 → 账号池取号 → 按「对话 + 账号」隔离的会话链
 * （chat_id + parent_id）→ 流式对话 → 成败回写账号池。
 *
 * ## 传输选择
 * - **浏览器通道**（默认开）：登录态在 App 内 WebView 里，用真 Chromium 栈发请求，
 *   能过阿里云 WAF 的指纹风控。生效前提是「页面已加载 + 已登录」，否则自动回退。
 * - **OkHttp 通道**：请求头/体与官方前端逐字段一致，但 TLS 指纹会被 WAF 判爬虫。
 *   池内多账号轮换仍走这条。
 *
 * 防撤回开启时把用户消息原文落盘快照（与 DeepSeek 通道共用 MessageStore）。
 */
class QwenWebRepository(
    private val context: Context,
    private val client: QwenWebClient
) {

    private val prefs by lazy {
        context.getSharedPreferences("qwen_web", Context.MODE_PRIVATE)
    }

    private val accounts by lazy { QwenAccountStore(context) }
    private val messages by lazy { MessageStore(context) }
    private val settings by lazy { SettingsStore(context) }

    /**
     * Token 自动续期器：后台每 60s 把「临近过期」的账号续一遍（网页端 token 只有 ~15 分钟寿命），
     * 并在每次取号后做一次「保新鲜」检查。见 [QwenTokenRefresher] 的协议注释。
     */
    val refresher: QwenTokenRefresher by lazy {
        QwenTokenRefresher(context, client, accounts, settings)
    }

    /** 后台作用域：登录后异步拉取用户信息等轻量任务。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 当前可用的 Qwen 模型清单（/api/v2/models 免鉴权拉取，TTL 6h）。
     * 供 LocalApiServer 的 /v1/models 直接读取；为空时回退静态清单。
     */
    @Volatile
    var modelCache: List<String> = emptyList()
        private set

    fun models(): List<String> = modelCache.ifEmpty { QwenWebClient.MODEL_IDS }

    /** 刷新模型清单（失败静默保留旧值；force=true 时强制重拉）。 */
    fun refreshModels(force: Boolean = false, onDone: ((List<String>) -> Unit)? = null) {
        scope.launch {
            val list = runCatching { client.models(force) }
                .getOrElse { modelCache.ifEmpty { QwenWebClient.MODEL_IDS } }
            if (list.isNotEmpty()) modelCache = list
            onDone?.invoke(modelCache)
        }
    }

    init {
        // 启动即异步预热一次，UI/接口层无需等待。
        refreshModels()
        // 后台定时续期 Token（网页端 access_token ~15 分钟就过期，官方前端靠 Cookie 换新）
        refresher.start(scope)
        // 页面 token 实时变化（新登录 / access_token 续期）→ 同步回账号池里同一条账号，
        // 避免池里 token 过期后还要手动重登；旧 token 找不到时不动池（由登录 UI 负责首登入池）。
        client.browser?.onTokenChanged = { old, new ->
            if (!old.isNullOrBlank() && new.isNotBlank() && old != new) {
                scope.launch {
                    runCatching {
                        accounts.list().firstOrNull { it.token == old }?.let { acc ->
                            // 页面自己续期 / 我们页面内续期成功时，顺手把 Cookie 和过期时间一起更新，
                            // 保证 pool 里的这条账号始终「可续期」。
                            val cookies = client.browser?.cookieString().orEmpty()
                            val exp = runCatching { client.browser?.readExpiry() }.getOrNull() ?: 0L
                            accounts.update(acc.id) {
                                it.copy(
                                    token = new,
                                    tokenInvalid = false,
                                    needRelogin = false,
                                    enabled = true,
                                    refreshFail = 0,
                                    cookies = cookies.ifBlank { it.cookies },
                                    expiresAt = if (exp > 0L) exp else it.expiresAt
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun accountStore(): QwenAccountStore = accounts
    fun messageStore(): MessageStore = messages

    fun hasUsable(): Boolean = accounts.hasUsable() || browserToken() != null

    /** token 失效时回调（UI 层据此提示重新登录）。 */
    var onReloginNeeded: ((String) -> Unit)? = null

    /**
     * 会话链按「对话 + 账号」双键隔离（对齐 DeepSeekWebRepository）：
     * 多账号轮询时各账号持有各自的 chat_id/parent_id 链，互不串流。
     */
    private fun chatIdKey(cid: Long, accId: String) = "chat_id_${cid}_$accId"
    private fun parentIdKey(cid: Long, accId: String) = "parent_id_${cid}_$accId"

    private fun storedChatId(cid: Long, accId: String): String? =
        prefs.getString(chatIdKey(cid, accId), null)

    private fun storedParentId(cid: Long, accId: String): String? =
        prefs.getString(parentIdKey(cid, accId), null)?.takeIf { it.isNotBlank() }

    /** 重置某对话的 Qwen 会话链（新对话 = 官网新会话）。 */
    fun resetSession(conversationId: Long) {
        val prefixes = listOf("chat_id_${conversationId}_", "parent_id_${conversationId}_")
        val edit = prefs.edit()
        prefs.all.keys.toList().forEach { k ->
            if (prefixes.any { k.startsWith(it) }) edit.remove(k)
        }
        edit.apply()
    }

    // ------------------------------------------------------------------ 传输选择

    /** 浏览器通道当前登录的 token（未就绪/未登录返回 null = 回退 OkHttp）。 */
    fun browserToken(): String? {
        if (!settings.qwenUseBrowser) return null
        val b = client.browser ?: return null
        b.cachedToken()?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /** 首轮请求前给浏览器页面一点时间（已加载但 token 未读到时）。 */
    private suspend fun browserTokenWithWait(): String? {
        if (!settings.qwenUseBrowser) return null
        val b = client.browser ?: return null
        if (!b.isLoaded()) return null
        b.cachedToken()?.takeIf { it.isNotBlank() }?.let { return it }
        val ok = runCatching { b.ensureReady(6_000) }.getOrDefault(false)
        return if (ok) b.cachedToken()?.takeIf { it.isNotBlank() } else null
    }

    /**
     * 浏览器通道要用的 (token, 账号key)。
     *
     * 优先用页面里的登录态；**页面没有登录态时退回池里取一个号** ——
     * 浏览器负责的是「真指纹 + WAF 通行 cookie」，业务身份用哪个号都行。
     * 这样即使页面登录入口不可用（老 WebView、引导页等），
     * 只要手动粘一个 token 进池，照样能走浏览器通道过风控。
     */
    private suspend fun browserTarget(): Pair<String, String>? {
        if (!settings.qwenUseBrowser) return null
        val b = client.browser ?: return null
        if (!b.isLoaded()) return null
        b.cachedToken()?.takeIf { it.isNotBlank() }?.let { return it to browserAccKey(it) }
        runCatching { if (b.ensureReady(4_000)) b.cachedToken() else null }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it to browserAccKey(it) }
        val acc = accounts.pick() ?: return null
        return acc.token to acc.id
    }

    /** 浏览器登录的号若也在池里，就复用它的 id，让两条通道共用同一条会话链。 */
    private fun browserAccKey(token: String): String =
        accounts.list().firstOrNull { it.token == token }?.id ?: ("browser-" + token.takeLast(8))

    // ------------------------------------------------------------------ 对话

    /**
     * 流式对话。
     * @param forcedAccountId 并行混合调度时强制使用的账号（不走池内取号），null = 正常取号
     */
    fun chatStream(
        conversationId: Long,
        prompt: String,
        thinkingEnabled: Boolean = true,
        contextTxt: String? = null,
        forcedAccountId: String? = null,
        onThinking: (String) -> Unit = {}
    ): Flow<String> = flow {
        client.useBrowser = settings.qwenUseBrowser
        // 浏览器通道优先：它才过得了 WAF 指纹风控。
        // forcedAccountId 指定了具体账号时说明是池内轮换调度，用 OkHttp 通道。
        val target = if (forcedAccountId == null) browserTarget() else null
        if (target != null) {
            emitAll(
                browserChat(
                    conversationId, prompt, thinkingEnabled, contextTxt,
                    target.first, target.second, onThinking
                )
            )
        } else {
            emitAll(httpChat(conversationId, prompt, thinkingEnabled, contextTxt, forcedAccountId, onThinking))
        }
    }

    /** 浏览器通道：账号即 WebView 页面里的登录态。 */
    private fun browserChat(
        conversationId: Long,
        prompt: String,
        thinkingEnabled: Boolean,
        contextTxt: String?,
        token: String,
        accId: String,
        onThinking: (String) -> Unit
    ): Flow<String> = flow {
        // 会话链可能已失效（上游说 CHAT_NOT_FOUND / 对话已删除）：清链 + 用新会话自动重试一次。
        var attempt = 0
        while (true) {
            val existingChatId = storedChatId(conversationId, accId)
            val parentId = if (attempt == 0) storedParentId(conversationId, accId) else null
            // 新会话必须**先让上游建会话**（官方前端 `POST /api/v2/chats/new`）：
            // 对话接口不会自动建会话，直接拿随机 UUID 去补全必定被回 CHAT_NOT_FOUND。
            val chatId = existingChatId ?: (
                runCatching { client.browser?.createChat(token, listOf(resolveQwenModel(settings.qwenModel))) }.getOrNull()
                    ?: java.util.UUID.randomUUID().toString()
                ).also { fresh ->
                prefs.edit().putString(chatIdKey(conversationId, accId), fresh).apply()
            }
            val contextTail = if (attempt > 0 || existingChatId == null) {
                contextTxt?.takeLast(CONTEXT_TAIL_CHARS)?.takeIf { it.isNotBlank() }
            } else null
            var emittedQ = 0
            try {
                val flow = client.browserFlow(
                    token = token,
                    model = resolveQwenModel(settings.qwenModel),
                    prompt = prompt,
                    chatId = chatId,
                    parentId = parentId,
                    thinkingEnabled = thinkingEnabled,
                    contextTail = contextTail,
                    onChatId = { cid ->
                        if (cid != chatId) {
                            prefs.edit().putString(chatIdKey(conversationId, accId), cid).apply()
                        }
                    },
                    onResponseId = { rid ->
                        if (rid != null) {
                            prefs.edit().putString(parentIdKey(conversationId, accId), rid).apply()
                        }
                    },
                    onThinking = onThinking
                ) ?: throw IllegalStateException("Qwen 浏览器通道未就绪：" + browserNotReadyReason())

                flow.collect { chunk ->
                    emittedQ += chunk.length
                    emit(chunk)
                }
                if (settings.antiRecall) messages.add(conversationId, null, "user", prompt)
                // 池里有同 token 的账号则记账（浏览器登录的号可能不在池里）
                accounts.list().firstOrNull { it.token == token }?.let { accounts.markSuccess(it.id) }
                return@flow
            } catch (e: Exception) {
                // 已吐过内容就不再重试（重试会重复一遍回答）；清链留给下一轮
                if (attempt == 0 && emittedQ == 0 && isChainBroken(e)) {
                    clearChain(conversationId, accId)
                    attempt++
                    continue
                }
                if (emittedQ > 0 && isChainBroken(e)) clearChain(conversationId, accId)
                handleChatFailure(e, conversationId, accId, ranOnBrowser = true)
                throw e
            }
        }
    }

    /** OkHttp 通道：池内多账号轮换。 */
    private fun httpChat(
        conversationId: Long,
        prompt: String,
        thinkingEnabled: Boolean,
        contextTxt: String?,
        forcedAccountId: String?,
        onThinking: (String) -> Unit
    ): Flow<String> = flow {
        val picked0 = if (forcedAccountId != null) {
            accounts.list().firstOrNull { it.id == forcedAccountId && it.enabled }
                ?: throw IllegalStateException("并行调度指定的账号已不可用（id=$forcedAccountId）")
        } else {
            accounts.pick() ?: throw IllegalStateException(accounts.diagnosis())
        }
        // 取号后先「保新鲜」：临近过期（默认提前 5 分钟）就地续期，避免请求中途 401。
        val picked = runCatching { refresher.ensureFresh(picked0) }.getOrDefault(picked0)
        val accId = picked.id
        // 会话链可能已失效（上游说 CHAT_NOT_FOUND / 对话已删除）：清链 + 用新会话自动重试一次。
        var attempt = 0
        while (true) {
            val existingChatId = storedChatId(conversationId, accId)
            val parentId = if (attempt == 0) storedParentId(conversationId, accId) else null
            // 新会话必须先让上游建会话（官方前端 `POST /api/v2/chats/new`），
            // 否则补全必被回 CHAT_NOT_FOUND；建会话失败才退回随机 UUID 兜底。
            val chatId = existingChatId ?: (
                runCatching { client.createChat(picked.token, resolveQwenModel(settings.qwenModel)) }.getOrNull()
                    ?: java.util.UUID.randomUUID().toString()
                ).also { fresh ->
                prefs.edit().putString(chatIdKey(conversationId, accId), fresh).apply()
            }
            // 新会话没有服务端历史：把上下文尾部摘录随首条消息兜底；
            // 续聊时靠 chat_id + parent_id 链维持，不再重复携带。
            val contextTail = if (attempt > 0 || existingChatId == null) {
                contextTxt?.takeLast(CONTEXT_TAIL_CHARS)?.takeIf { it.isNotBlank() }
            } else null
            var emittedQ = 0
            try {
                client.chatStream(
                    token = picked.token,
                    model = resolveQwenModel(settings.qwenModel),
                    prompt = prompt,
                    chatId = chatId,
                    parentId = parentId,
                    thinkingEnabled = thinkingEnabled,
                    contextTail = contextTail,
                    onChatId = { cid ->
                        if (cid != chatId) {
                            prefs.edit().putString(chatIdKey(conversationId, accId), cid).apply()
                        }
                    },
                    onResponseId = { rid ->
                        if (rid != null) {
                            prefs.edit().putString(parentIdKey(conversationId, accId), rid).apply()
                        }
                    },
                    onThinking = onThinking
                ).collect { chunk ->
                    emittedQ += chunk.length
                    emit(chunk)
                }
                if (settings.antiRecall) messages.add(conversationId, null, "user", prompt)
                accounts.markSuccess(accId)
                return@flow
            } catch (e: Exception) {
                if (attempt == 0 && isChainBroken(e)) {
                    clearChain(conversationId, accId)
                    attempt++
                    continue
                }
                handleChatFailure(e, conversationId, accId, ranOnBrowser = false)
                throw e
            }
        }
    }

    /** 浏览器通道不可用的**具体**原因（别再让用户猜）。 */
    private fun browserNotReadyReason(): String {
        if (!settings.qwenUseBrowser) return "开关已关闭（Qwen 账号页 → 内置浏览器通道）"
        val b = client.browser ?: return "浏览器组件不可用（未初始化）"
        if (!b.isLoaded()) return "页面还没加载过 —— 请到 Qwen 账号页打开一次「浏览器登录」面板"
        return b.errorText() ?: "页面已加载但通道初始化失败，请重开面板或点「诊断」看详情"
    }

    /** 上游提示「会话不存在/已删除」——本地的 chat_id/parent_id 链已失效，清链用新会话重试一次。 */
    private fun isChainBroken(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("chat_not_found") || m.contains("conversation_not_found") ||
            m.contains("chat not found") || m.contains("对话已删除") ||
            m.contains("chat_id") || m.contains("会话链") ||
            (m.contains("not_found") && (m.contains("chat") || m.contains("会话")))
    }

    /** 清掉某对话在某账号下的会话链（下轮自动新建会话）。 */
    private fun clearChain(conversationId: Long, accId: String) {
        prefs.edit()
            .remove(chatIdKey(conversationId, accId))
            .remove(parentIdKey(conversationId, accId))
            .apply()
    }

    /**
     * 成败回写：token 失效 → 先尝试自动续期，续不动才标记待重登；会话链失效 → 清链下轮重建。
     * 浏览器通道失败时不做 markFail —— 那是页面风控/验证码问题，不是账号的错。
     */
    private suspend fun handleChatFailure(
        e: Exception,
        conversationId: Long,
        accId: String,
        ranOnBrowser: Boolean
    ) {
        val msg = (e.message ?: "").lowercase()
        val isTokenInvalid = msg.contains("401") || msg.contains("登录态") ||
            (msg.contains("token") && (msg.contains("expire") || msg.contains("失效") || msg.contains("无效")))
        if (isTokenInvalid) {
            // 先给它一次自动续期的机会（这就是「不用每 15 分钟重登」的核心）
            val acc = accounts.list().firstOrNull { it.id == accId }
            val recovered = acc != null &&
                runCatching { refresher.refresh(acc, force = true) }.getOrDefault(false)
            if (!recovered) {
                accounts.markTokenInvalid(accId)
                runCatching { onReloginNeeded?.invoke(accId) }
            }
        } else if (!ranOnBrowser) {
            accounts.markFail(accId)
        }
        // 会话链失效：清掉本账号该对话的链（下轮自动重建）。
        if (isChainBroken(e)) clearChain(conversationId, accId)
    }

    /**
     * 登录后保存 token（自动入池）并异步拉取邮箱展示。
     *
     * [cookies] / [expiresAt] 由登录界面从 WebView 抓取 —— 存下来才能**自动续期**
     * （网页端 access_token 只有 ~15 分钟寿命，续期靠 `token` cookie）。
     */
    fun saveToken(token: String, cookies: String = "", expiresAt: Long = 0L) {
        val id = accounts.add(token, cookies = cookies, expiresAt = expiresAt)
        scope.launch {
            runCatching {
                val info = client.fetchUserInfo(token)
                accounts.setIdentity(id, info.email)
            }
            // 顺手补一次过期时间（接口不返回时用 JWT exp 兜底）
            if (expiresAt <= 0L) {
                val exp = runCatching { QwenWebClient.parseJwtExpiryMsStatic(token) }.getOrDefault(0L)
                if (exp > 0L) accounts.update(id) { it.copy(expiresAt = exp) }
            }
        }
    }

    companion object {
        /** 首条消息兜底携带的历史尾部字符数。 */
        const val CONTEXT_TAIL_CHARS = 6000
    }
}
