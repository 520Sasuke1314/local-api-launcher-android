package com.dslauncher.data.repository

import android.content.Context
import com.dslauncher.data.AccountStore
import com.dslauncher.data.MessageStore
import com.dslauncher.data.SettingsStore
import com.dslauncher.data.remote.DeepSeekWebClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * DeepSeek 官网（网页版）对话仓库。
 * 直接使用官网账号 token 调用网页版接口，与官网网页端共享同一会话（会话连贯同步）。
 * 警告：使用官网接口可能违反 DeepSeek 服务条款，有封号风险，建议使用小号。
 *
 * 会话隔离：官网 session_id / parent_message_id 按 conversationId 分别存储
 * （key 带 `_<conversationId>` 后缀），避免多个本地对话共用一套官网会话导致串流；
 * 读取时回退到旧版全局 key，实现平滑迁移。
 */
class DeepSeekWebRepository(
    private val context: Context,
    private val client: DeepSeekWebClient
) {

    private val prefs by lazy {
        context.getSharedPreferences("deepseek_web", Context.MODE_PRIVATE)
    }

    /** 多账号仓库：token 统一从这里取，登录也统一写这里。 */
    private val accounts by lazy { AccountStore(context) }

    /** 本地消息快照（防撤回）。 */
    private val messages by lazy { MessageStore(context) }

    private val settings by lazy { SettingsStore(context) }

    /** 防撤回开关是否开启。 */
    val antiRecallEnabled: Boolean get() = settings.antiRecall

    /** 暴露消息快照，供 UI 显示被撤回的原文。 */
    fun messageStore(): MessageStore = messages

    /**
     * 与官网历史对账，标记被撤回/被改写的消息。
     * @return 本次新发现的撤回条数
     */
    suspend fun checkRecalls(conversationId: Long): Int {
        if (!settings.antiRecall) return 0
        val tk = token() ?: return 0
        val sid = runCatching { sessionIdFor(conversationId, tk) }.getOrNull() ?: return 0
        val hist = runCatching { client.fetchHistory(tk, sid) }.getOrDefault(emptyList())
        if (hist.isEmpty()) return 0
        return messages.reconcile(conversationId, hist.map { it.id to it.content })
    }

    /** 后台作用域：用于登录后异步拉取用户信息等轻量任务。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasToken(): Boolean = accounts.hasAny()

    /** token 唯一来源：账号池。删光账号 = 无凭证，必须重新登录。 */
    fun token(): String? = accounts.pickToken()

    /** 供 UI 展示账号池状态。 */
    fun accountStore(): AccountStore = accounts

    /**
     * 会话 / 父消息 **按「对话 + 账号」双键隔离**。
     *
     * 之前是「按对话单键」+ 额外记一个 owner 账号：一旦取号轮到别的账号，
     * 就重建官网会话并把 parent 链清掉。多账号轮询（A→B→A→B）下等于
     * **每一轮都换一个全新会话、parent 永远为 null**，官网侧上下文彻底失效 ——
     * 表现就是工具结果回传后模型「不记得自己已经调用过」，疯狂重复同一条命令。
     * 现在每个账号各自保有一条会话链，互不干扰。
     */
    private fun sessionKey(cid: Long, accId: String) =
        if (accId.isBlank()) "session_id_$cid" else "session_id_${cid}_$accId"

    private fun parentKey(cid: Long, accId: String) =
        if (accId.isBlank()) "parent_message_id_$cid" else "parent_message_id_${cid}_$accId"

    /** 记录某对话最近一次使用的账号。 */
    private fun ownerKey(cid: Long) = "session_owner_$cid"

    /** 该对话最近一次使用的账号（兼容旧的「按对话单键」调用点）。 */
    private fun convOwner(cid: Long): String = prefs.getString(ownerKey(cid), "").orEmpty()

    private fun sessionKey(cid: Long) = sessionKey(cid, convOwner(cid))
    private fun parentKey(cid: Long) = parentKey(cid, convOwner(cid))

    private fun sessionId(cid: Long): String? =
        prefs.getString(sessionKey(cid), null) ?: prefs.getString("session_id", null)

    /** 取该对话已绑定的官网会话（只读，不新建）。 */
    private fun sessionIdFor(cid: Long, @Suppress("UNUSED_PARAMETER") token: String): String? =
        prefs.getString(sessionKey(cid), null)

    private fun parentMessageId(cid: Long, accId: String): Long? =
        prefs.getLong(parentKey(cid, accId), -1L).takeIf { it > 0 }

    // 同理：parent 只认本账号那条链，避免新会话挂到别的账号的父消息上。
    private fun parentMessageId(cid: Long): Long? = parentMessageId(cid, convOwner(cid))

    /**
     * 邮箱 + 密码直登官网（返回 token）。
     *
     * 设备指纹「所见即所用」：优先复用该邮箱已绑定的指纹（[AccountStore.deviceIdForEmail]），
     * 没有就分配一枚池内未占用的。登录后把这枚指纹随账号一起入库 ——
     * 这样账号页显示的、以及被禁言/失效后轮换出来的，就是（下次）登录真正会发出去的那枚。
     *
     * 注意：更换指纹只能换一个「设备身份」，**不保证降低被禁言概率**。
     */
    suspend fun login(email: String, password: String): String {
        val fp = accounts.deviceIdForEmail(email)
            ?: com.dslauncher.data.DeviceFingerprints.newFingerprint(accounts.usedFingerprints())
        val token = client.login(email, password, fp)
        prefs.edit().putString("token", token).apply()
        val id = accounts.add(token, deviceId = fp)
        accounts.setIdentity(id, email.trim(), null)
        return token
    }

    /**
     * 直接保存登录态（WebView 登录用）。
     *
     * 与 login(email, password) 的区别：不再由客户端发账号密码，
     * 改为用户在官网页面自行完成登录（含验证码/短信等完整流程），客户端只接收结果。
     * 客户端直连 /users/login 缺少浏览器指纹与验证码环节，是风控里很显眼的异常特征。
     */
    fun saveToken(token: String) {
        // 登录成功即自动入池：同 token 原地更新，新 token 追加，实现多账号累积。
        val id = accounts.add(token)
        // 登录后异步拉取邮箱/手机号与禁言状态，写入账号供账号池列表展示。
        scope.launch {
            runCatching {
                val info = client.checkAccountStatus(token)
                accounts.setIdentity(id, info.email, info.phone)
                accounts.setMuted(id, info.muted, info.muteUntil ?: 0L)
            }
        }
    }

    /** 「刷新账号信息」的结果：ok 之外必须给出可读原因，UI 原样展示给用户。 */
    data class IdentityResult(val ok: Boolean, val message: String)

    /**
     * 主动刷新某账号的身份信息（邮箱/手机号）与状态（token 有效性 + 禁言）。
     *
     * 每种结果都有明确消息：Token 失效 / 官网报错 / 没有返回身份字段 /
     * 拿到（含「值是掩码」的标注）—— 旧版把失败全部静默吞掉，账号页永远显示
     * 「未知（点刷新重取）」，用户点刷新也看不到任何变化。
     */
    suspend fun refreshIdentity(id: String, token: String): IdentityResult {
        val valid = runCatching { client.validateToken(token) }.getOrDefault(false)
        if (!valid) {
            accounts.setTokenInvalid(id, true)
            maybeRotateFingerprint(id)
            return IdentityResult(false, "Token 已失效：已停用并自动更换设备指纹，请重新登录")
        }
        accounts.setTokenInvalid(id, false)
        val info = try {
            client.checkAccountStatus(token)
        } catch (t: Throwable) {
            return IdentityResult(false, "刷新失败：${t.message ?: t.javaClass.simpleName}")
        }
        accounts.setIdentity(id, info.email, info.phone)
        accounts.setMuted(id, info.muted, info.muteUntil ?: 0L)
        if (info.muted) maybeRotateFingerprint(id)
        val got = listOfNotNull(
            info.email?.let { "邮箱 ${describe(it)}" },
            info.phone?.let { "手机 ${describe(it)}" }
        )
        return IdentityResult(
            true,
            if (got.isEmpty()) "官网未返回邮箱/手机号（该账号可能未绑定或接口返回为空）"
            else "已获取 ${got.joinToString(" · ")}"
        )
    }

    /** 掩码值加标注，真实值原样显示。 */
    private fun describe(value: String): String =
        if (value.contains('*')) "$value（掩码）" else value

    /** 自动更换指纹总闸：开关（账号页设置）关闭时不换。 */
    private fun maybeRotateFingerprint(id: String) {
        if (accounts.autoRotateFingerprint) accounts.rotateFingerprint(id)
    }

    /** 「校验状态」的结果统计。 */
    data class VerifyResult(val total: Int, val muted: Int, val invalid: Int, val ok: Int)

    /**
     * 批量校验所有账号：**token 有效性** + 禁言/封禁。
     *
     * 之前只校验禁言，token 早已失效的号仍显示绿色可用、还会被取号发请求，
     * 真正发出去才报 invalid token。现在先实测 token 是否还能用，
     * 有效再拉身份与禁言状态；失效的直接标红停用，不再参与取号。
     */
    suspend fun verifyAllStatuses(): VerifyResult {
        var mutedCount = 0
        var invalidCount = 0
        var okCount = 0
        val all = accounts.list()
        all.forEach { acc ->
            val valid = runCatching { client.validateToken(acc.token) }.getOrDefault(false)
            if (!valid) {
                accounts.setTokenInvalid(acc.id, true)
                maybeRotateFingerprint(acc.id)
                invalidCount++
                return@forEach
            }
            accounts.setTokenInvalid(acc.id, false)
            runCatching {
                val info = client.checkAccountStatus(acc.token)
                accounts.setIdentity(acc.id, info.email, info.phone)
                accounts.setMuted(acc.id, info.muted, info.muteUntil ?: 0L)
                if (info.muted) {
                    mutedCount++
                    maybeRotateFingerprint(acc.id)
                }
            }
            okCount++
        }
        return VerifyResult(all.size, mutedCount, invalidCount, okCount)
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    /**
     * 流式对话：复用该对话对应的官网会话，用 parent_message_id 链接上下文，
     * 与官网网页端共享同一段对话记录。
     * @param conversationId 本地对话 id，官网会话状态按它隔离存储
     * @param parentMessageIdOverride 非空时强制使用该官方 message_id 作父消息
     * （重生成/编辑时用于把新回复接到官网对话的正确位置）；
     * null = 使用该对话保存的 parent_message_id。
     */
    fun chatStream(
        conversationId: Long,
        prompt: String,
        thinkingEnabled: Boolean = true,
        searchEnabled: Boolean = false,
        searchProvider: String? = null,
        parentMessageIdOverride: Long? = null,
        imageDataUrls: List<String> = emptyList(),
        // 上下文（此前各轮对话拼接成的 txt）：**分片**上传为官网文件后经 ref_file_ids 附带，
        // 单文件 455KB 上限，超长对话自动分割成多片；上传失败退回「尾部摘要拼进 prompt」。
        contextTxt: String? = null,
        attachmentText: String? = null,
        // 并行混合调度：强制使用指定账号（不走池内取号），null = 正常取号
        forcedAccountId: String? = null,
        onUploadError: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
        onSearchResults: (List<Pair<String, String>>) -> Unit = {}
    ): Flow<String> = flow {
        // 多账号取号：失败要能定位到具体账号，才能记失败/自动停用。
        // 并行混合调度传入 forcedAccountId 时绕过池内策略，直接用指定账号。
        val picked = if (forcedAccountId != null) {
            accounts.list().firstOrNull { it.id == forcedAccountId && it.enabled }
                ?: throw IllegalStateException("并行调度指定的账号已不可用（id=$forcedAccountId）")
        } else {
            accounts.pick()
        }
        val tk = picked?.token ?: token()
            ?: throw IllegalStateException(accounts.diagnosis())
        val accId = picked?.id ?: ""
        // 会话按账号隔离：session / parent 必须与账号绑定。
        // 否则多账号轮询时，账号 B 拿着账号 A 的 session+parent 去请求，
        // 官网直接回 biz_code=26 "invalid message id"。
        suspend fun ensureSession(forceNew: Boolean): String {
            // 每个账号各持一条官网会话：只查/只建「本账号」的那条，
            // 绝不因为取号换了个人就重建（旧实现正是这么把上下文清空的）。
            val stored = prefs.getString(sessionKey(conversationId, accId), null)
            if (!forceNew && !stored.isNullOrBlank()) return stored
            val fresh = client.createSession(tk)
            prefs.edit()
                .putString(sessionKey(conversationId, accId), fresh)
                .putString(ownerKey(conversationId), accId)
                .remove(parentKey(conversationId, accId))
                .remove("session_id")          // 清旧版全局 key，避免跨账号串流
                .remove("parent_message_id")
                .apply()
            return fresh
        }
        var sid = ensureSession(forceNew = false)
        // 图片上传：失败必须**可见**。
        val imageRefIds = if (imageDataUrls.isEmpty()) emptyList() else {
            runCatching { imageDataUrls.map { url -> client.uploadFile(tk, url) } }
                .onFailure { e -> runCatching { onUploadError("图片上传失败：${e.message}") } }
                .getOrDefault(emptyList())
        }
        // 上下文转文件（分片 + 缓存）：
        // 官网单文件上限约 455KB，长对话整段塞一个 txt 必然「放不下」，因此：
        //   1) 按 ~430KB 从头部锚定切分（换行优先），任何单片都不会超限；
        //   2) 已上传过的分片按内容哈希复用 file_id —— 对话只在尾部增长，
        //      前面封存的分片每轮都命中缓存，只有正在增长的最后一片需要重传；
        //   3) 分片数封顶（开头锚点 + 最近若干片），超长对话不无限膨胀。
        var ctxUploaded = false
        val ctxRefIds: List<String> = if (contextTxt.isNullOrBlank()) emptyList() else {
            runCatching { uploadContextChunks(tk, conversationId, accId, contextTxt) }
                .onFailure { e -> runCatching { onUploadError("上下文上传失败：${e.message}，本轮退回仅靠会话链携带历史") } }
                .getOrDefault(emptyList())
        }
        if (ctxRefIds.isNotEmpty()) ctxUploaded = true
        // 兜底：上下文文件没上去时，把「上一轮摘要」拼进本轮 prompt 尾部，
        // 避免整段历史彻底丢失（会话链多轮后本就易断）。
        var finalPrompt = prompt
        if (!contextTxt.isNullOrBlank() && !ctxUploaded) {
            val tail = contextTxt.takeLast(2000)
            finalPrompt = prompt + "\n\n【本轮未附带上下文文件，以下是此前对话的尾部记录，供参考】\n" + tail
        }
        val refFileIds = imageRefIds + ctxRefIds
        var parent = parentMessageIdOverride ?: parentMessageId(conversationId, accId)
        // 失败重试：拿到 biz_code=26（invalid message id）等会话失效错误时，
        // 重建会话、丢弃父消息，重试一次；仍失败才向上抛。
        var attempt = 0
        // 防撤回：记录官方 message_id，用于之后挂到本地快照上对账
        var userOfficialId: Long? = null
        var assistantOfficialId: Long? = null
        try {
            var emitted = 0
            while (true) {
                try {
                    // 手动 collect（而不是 emitAll）：需要知道「已往客户端吐过多少字」。
                    // 半途出错时如果已经吐过内容，就**不能重试**——重试会从头再流一遍，
                    // 客户端看到的正是「回答突然重复/重来」。
                    client.chatStream(
                            token = tk,
                            sessionId = sid,
                            prompt = finalPrompt,
                            thinkingEnabled = thinkingEnabled,
                            searchEnabled = searchEnabled,
                            searchProvider = searchProvider,
                            parentMessageId = parent,
                            refFileIds = refFileIds,
                            onMessageId = { id ->
                                if (id != null) {
                                    assistantOfficialId = id
                                    prefs.edit().putLong(parentKey(conversationId, accId), id).apply()
                                }
                            },
                            onRequestMessageId = { id ->
                                if (id != null) {
                                    userOfficialId = id
                                    prefs.edit().putLong("last_user_message_id_$conversationId", id).apply()
                                }
                            },
                            onThinking = onThinking,
                            onSearchResults = onSearchResults
                        )
                    .collect { chunk ->
                        emitted += chunk.length
                        emit(chunk)
                    }
                    break
                } catch (biz: DeepSeekWebClient.WebBizException) {
                    val recoverable = biz.bizCode == 26 || biz.bizCode == 24 ||
                        biz.bizMsg.contains("message id", ignoreCase = true) ||
                        biz.bizMsg.contains("session", ignoreCase = true)
                    // 已吐过内容就不重试（否则表现为「回答重复」）；会话链留待下一轮自然重建
                    if (!recoverable || attempt >= 1 || emitted > 0) {
                        if (emitted > 0) prefs.edit().remove(parentKey(conversationId, accId)).apply()
                        throw biz
                    }
                    attempt++
                    sid = ensureSession(forceNew = true)
                    parent = null
                }
            }
            // 防撤回：成功后把用户消息原文落盘快照（助手回复由 UI 侧补记）
            if (settings.antiRecall) {
                messages.add(conversationId, userOfficialId, "user", prompt)
            }
            // 流正常结束 → 记为一次成功：递增配额用量，达到上限自动进冷却。
            noteSuccess(picked?.id)
        } catch (e: Exception) {
            // 失败分类（对齐 上游实现 的 token_invalid / rate limited / 其他）：
            //  - token 失效（401 / is_token_invalid / INVALID_TOKEN / 未登录）→ 标记待重登，
            //    并通知上层拉起登录页做「自动刷新」；
            //  - 其余失败 → 记失败计数，连续超阈值自动停用。
            val msg = (e.message ?: "").lowercase()
            val isMuted = msg.contains("is_muted") || msg.contains("muted") ||
                msg.contains("禁言") || msg.contains("封禁")
            val isTokenInvalid = msg.contains("401") || msg.contains("invalid_token") ||
                msg.contains("is_token_invalid") || msg.contains("未登录") ||
                msg.contains("token") && (msg.contains("expire") || msg.contains("失效") || msg.contains("无效"))
            picked?.let {
                if (isMuted) {
                    // 账号被禁言：标记状态，UI 变红并停止参与轮询；同时更换设备指纹，
                    // 下次重新登录不再带着「被禁言那台设备」的身份
                    accounts.setMuted(it.id, true)
                    maybeRotateFingerprint(it.id)
                } else if (isTokenInvalid) {
                    accounts.markTokenInvalid(it.id)
                    maybeRotateFingerprint(it.id)
                    runCatching { onReloginNeeded?.invoke(it.id) }
                } else {
                    accounts.markFail(it.id)
                }
            }
            throw e
        }
    }

    /** token 失效时回调（UI 层据此自动跳到登录页重新抓 token）。 */
    var onReloginNeeded: ((String) -> Unit)? = null

    /** 本次请求用到的账号成功后回调（供 UI 展示），由 chatStream 内部调用。 */
    private fun noteSuccess(id: String?) {
        id?.let { accounts.markSuccess(it) }
    }


    fun lastUserMessageId(cid: Long): Long? =
        prefs.getLong("last_user_message_id_$cid", -1L).takeIf { it > 0 }
            ?: prefs.getLong("last_user_message_id", -1L).takeIf { it > 0 }

    /**
     * 官网重新生成：走专用端点 /api/v0/chat/regenerate，用 child_message_id 定位被重生成的回复。
     * 服务器复用原用户消息、直接替换该回复，不会像 completion 通道那样把用户消息重发一遍。
     * @param childMessageId 被重生成那条 AI 回复的官方 message_id
     */
    fun regenerateStream(
        conversationId: Long,
        childMessageId: Long,
        thinkingEnabled: Boolean = true,
        searchEnabled: Boolean = false,
        onThinking: (String) -> Unit = {}
    ): Flow<String> = flow {
        val tk = token() ?: throw IllegalStateException(accounts.diagnosis())
        var sid = sessionId(conversationId)
        if (sid.isNullOrBlank()) {
            sid = client.createSession(tk)
            prefs.edit().putString(sessionKey(conversationId), sid).apply()
        }
        emitAll(
            client.regenerateStream(
                token = tk,
                sessionId = sid,
                childMessageId = childMessageId,
                thinkingEnabled = thinkingEnabled,
                searchEnabled = searchEnabled,
                onMessageId = { id ->
                    if (id != null) {
                        prefs.edit().putLong(parentKey(conversationId), id).apply()
                    }
                },
                onRequestMessageId = { id ->
                    if (id != null) {
                        prefs.edit().putLong("last_user_message_id_$conversationId", id).apply()
                    }
                },
                onThinking = onThinking
            )
        )
    }

    /**
     * 官网编辑消息：走专用端点 /api/v0/chat/edit_message，用官方 message_id 定位被编辑的用户消息，
     * 服务端原地替换该消息并重新生成回复（这才是官网"修改输入"的正确机制，
     * completion + parent/current_message_id 只会把新内容追加到会话末尾）。
     * @param messageId 被编辑用户消息的官方 message_id
     */
    fun editMessageStream(
        conversationId: Long,
        messageId: Long,
        prompt: String,
        thinkingEnabled: Boolean = true,
        searchEnabled: Boolean = false,
        onThinking: (String) -> Unit = {}
    ): Flow<String> = flow {
        val tk = token() ?: throw IllegalStateException(accounts.diagnosis())
        var sid = sessionId(conversationId)
        if (sid.isNullOrBlank()) {
            sid = client.createSession(tk)
            prefs.edit().putString(sessionKey(conversationId), sid).apply()
        }
        emitAll(
            client.editMessageStream(
                token = tk,
                sessionId = sid,
                messageId = messageId,
                prompt = prompt,
                thinkingEnabled = thinkingEnabled,
                searchEnabled = searchEnabled,
                onMessageId = { id ->
                    if (id != null) {
                        prefs.edit().putLong(parentKey(conversationId), id).apply()
                    }
                },
                onRequestMessageId = { id ->
                    if (id != null) {
                        prefs.edit().putLong("last_user_message_id_$conversationId", id).apply()
                    }
                },
                onThinking = onThinking
            )
        )
    }

    /** 该对话最近一条 AI 回复的官方 message_id（chatStream 每次成功后由 onMessageId 记录）。 */
    fun lastAssistantReplyId(cid: Long): Long? = parentMessageId(cid)

    // ===================== 上下文分片上传 =====================

    /** 官网单文件上限约 455.07KB：留足余量（多字节字符/换行），单片目标 430,000 字节。 */
    private val CTX_CHUNK_BYTES = 430_000

    /** 超长单行的预切片大小：先切成小块，再装箱，避免「一行超限」卡死。 */
    private val CTX_PIECE_BYTES = 65_536

    /** 单轮最多携带的上下文分片数；超出时保留「开头锚点 + 最近若干片」。 */
    private val CTX_MAX_CHUNKS = 6

    /** 分片缓存 key：按「对话 + 账号」隔离（官网文件归属账号，轮换账号不能跨号引用）。 */
    private fun ctxCacheKey(cid: Long, accId: String) =
        "context_files_${cid}_${accId.ifBlank { "default" }}"

    /** UTF-8 字节数（按码点计算，避免为整串反复编码）。 */
    private fun utf8Len(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /** 把超长行按字符边界硬切成 ≤ maxBytes 的小片（不拆坏多字节字符）。 */
    private fun sliceByBytes(s: String, maxBytes: Int): List<String> {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(s)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val need = utf8Len(cp)
            if (bytes + need > maxBytes) {
                out.add(sb.toString())
                sb.setLength(0)
                bytes = 0
            }
            sb.appendCodePoint(cp)
            bytes += need
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * 把上下文文本分割成多个「每片不超过 CTX_CHUNK_BYTES」的分片。
     *
     * 切分从头部锚定（贪心装箱，优先在换行处断）：对话只在尾部增长时，
     * 前面分片的内容保持逐字节稳定 —— 这是缓存能命中的前提。
     */
    private fun splitContext(text: String): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= CTX_CHUNK_BYTES) return listOf(text)
        // 先按行拆，超长行先预切片，再贪心装箱。
        val pieces = ArrayList<String>()
        for (line in text.split('\n')) {
            if (line.toByteArray(Charsets.UTF_8).size > CTX_PIECE_BYTES) {
                pieces.addAll(sliceByBytes(line, CTX_PIECE_BYTES))
            } else {
                pieces.add(line)
            }
        }
        val chunks = ArrayList<String>()
        val cur = StringBuilder()
        var curBytes = 0
        for (piece in pieces) {
            val pb = piece.toByteArray(Charsets.UTF_8).size
            if (curBytes > 0 && curBytes + 1 + pb > CTX_CHUNK_BYTES) {
                chunks.add(cur.toString())
                cur.setLength(0)
                curBytes = 0
            }
            if (curBytes > 0) {
                cur.append('\n')
                curBytes += 1
            }
            cur.append(piece)
            curBytes += pb
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString())
        return chunks
    }

    /** 内容 SHA-256（十六进制），用作分片缓存键。 */
    private fun sha256Hex(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }

    /**
     * 分片上传上下文，返回按顺序排列的 ref_file_ids。
     *
     * - 每片都保证 ≤ CTX_CHUNK_BYTES，任意长的对话都能拆得下；
     * - 同哈希分片直接复用缓存里的 file_id（官网文件不会变化，可长期复用）；
     * - 缓存按「对话 + 账号」隔离，并随分片封存逐步落盘（后续片失败时
     *   前面成功的片仍留在缓存里）。
     */
    private suspend fun uploadContextChunks(
        token: String,
        cid: Long,
        accId: String,
        contextTxt: String
    ): List<String> {
        val chunks = splitContext(contextTxt)
        // 超长对话封顶：保留「开头锚点 + 最近若干片」，避免引用列表无限膨胀。
        val selected = if (chunks.size > CTX_MAX_CHUNKS) {
            listOf(chunks.first()) + chunks.takeLast(CTX_MAX_CHUNKS - 1)
        } else chunks
        val cacheKey = ctxCacheKey(cid, accId)
        val cache = runCatching {
            JSONObject(prefs.getString(cacheKey, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val ids = ArrayList<String>()
        val next = JSONObject()
        for (chunk in selected) {
            val h = sha256Hex(chunk)
            val id = cache.optString(h).takeIf { it.isNotBlank() }
                ?: client.uploadTextFile(token, "context.txt", chunk)
            ids.add(id)
            next.put(h, id)
            prefs.edit().putString(cacheKey, next.toString()).apply()
        }
        return ids
    }

    /** 重置某对话的官网会话（新对话 = 官网新会话）；同时清理旧版全局 key 完成迁移 */
    fun resetSession(conversationId: Long) {
        val edit = prefs.edit()
            .remove(ownerKey(conversationId))
            .remove("last_user_message_id_$conversationId")
            .remove("session_id")
            .remove("parent_message_id")
        // 会话 / 父消息现在按「对话 + 账号」双键存储，本对话所有账号的链都要清；
        // 上下文分片缓存同样是按账号隔离的，一并清掉。
        val prefixes = listOf(
            "session_id_${conversationId}_",
            "parent_message_id_${conversationId}_",
            "context_files_${conversationId}_"
        )
        prefs.all.keys.toList().forEach { k ->
            if (prefixes.any { k.startsWith(it) }) edit.remove(k)
        }
        // 旧版「按对话单键」的残留
        edit.remove("session_id_$conversationId")
        edit.remove("parent_message_id_$conversationId")
        edit.apply()
    }

    /**
     * 官网历史正文归一化：剥离思考块（半角/全角）、【reference:N】来源标注、折叠空白。
     * 本地消息与官网消息的正文在这三处常有差异，先归一化再比对才能命中。
     */
    private fun normalizeWebContent(s: String): String = s
        .replace(Regex("(\\[思考\\]|【思考】)([\\s\\S]*?)(\\[/思考\\]|【/思考】)"), "")
        .replace(Regex("【reference:\\d+】|\\[reference:\\d+\\]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * 在官网该对话对应的会话历史里按正文匹配消息，返回其 message_id（用于重生成/编辑/删除时定位官网消息）。
     * 先精确匹配，匹配不到再宽松匹配（官网用户消息可能带注入前缀，正文结尾才是原始输入）。
     * 匹配不到返回 null。
     * @param conversationId 本地对话 id，决定查哪个官网会话
     * @param role 限定匹配的消息角色；null = 匹配任意角色
     */
    suspend fun findMessageIdByContent(conversationId: Long, content: String, role: String? = "user"): Long? {
        if (content.isBlank()) return null
        val tk = token() ?: return null
        val sid = sessionId(conversationId) ?: return null
        return runCatching {
            val needle = normalizeWebContent(content)
            if (needle.isBlank()) return@runCatching null
            val history = client.fetchHistory(tk, sid).filter { role == null || it.role == role }
            // 取「最后一次」匹配（用户消息在官网上带注入前缀，原文在结尾；避免命中更早的重复消息）
            history.lastOrNull { normalizeWebContent(it.content) == needle }?.id
                ?: history.lastOrNull { normalizeWebContent(it.content).contains(needle) }?.id
        }.getOrNull()
    }

    /**
     * 返回某条官网消息的父消息 id（编辑/改写时新消息应接在父消息之下）。
     * 直接从官网历史里取 parent_id，比按正文匹配"上一条消息"更可靠。
     */
    suspend fun findParentIdOf(conversationId: Long, messageId: Long): Long? {
        if (messageId <= 0) return null
        val tk = token() ?: return null
        val sid = sessionId(conversationId) ?: return null
        return runCatching {
            client.fetchHistory(tk, sid).firstOrNull { it.id == messageId }?.parentId
        }.getOrNull()
    }

    /**
     * 按旧回复的正文在官网找到对应消息，返回其 parent_id（重生成时新回复应接在旧回复的父消息下，
     * 即用户消息的官方 id）。官网回复正文不含注入前缀，按精确匹配即可。
     */
    suspend fun findParentIdForReply(conversationId: Long, replyBody: String): Long? {
        if (replyBody.isBlank()) return null
        val tk = token() ?: return null
        val sid = sessionId(conversationId) ?: return null
        return runCatching {
            val needle = replyBody.trim()
            client.fetchHistory(tk, sid).firstOrNull { it.role == "assistant" && it.content.trim() == needle }?.parentId
        }.getOrNull()
    }

    /**
     * 编辑同步替换：把官网某条消息及其全部后续回复删除（best-effort）。
     * 编辑用户消息前调用，随后用该消息的「官网父消息 id」作 parent 重发编辑内容，
     * 官网界面表现为"修改输入"——原消息消失、编辑后的内容替换在原位置，
     * 而不是"新内容追加发出、原消息没变"。
     * @param targetId 被编辑消息的官方 message_id（由 findMessageIdByContent 解析）
     * @return 是否至少成功删除了目标消息
     */
    suspend fun deleteMessageBranchOnOfficial(conversationId: Long, targetId: Long): Boolean {
        val tk = token() ?: return false
        val sid = sessionId(conversationId) ?: return false
        if (targetId <= 0) return false
        return runCatching {
            val history = client.fetchHistory(tk, sid)
            // 按 parentId 建树，BFS 收集目标消息及其所有后代（不删祖先，新消息要接在其后）
            val children = history.groupBy { it.parentId }
            val toDelete = LinkedHashSet<Long>()
            val queue = ArrayDeque<Long>()
            toDelete.add(targetId)
            queue.add(targetId)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                children[id]?.forEach { node ->
                    node.id?.let { childId ->
                        if (toDelete.add(childId)) queue.add(childId)
                    }
                }
            }
            var ok = false
            toDelete.forEach { id -> ok = client.deleteMessage(tk, sid, id) || ok }
            ok
        }.getOrDefault(false)
    }

    /**
     * 在官网删除单条消息（best-effort）。官网消息级删除端点未公开确认，
     * 若接口不兼容返回 false（调用方应保留本地删除并提示用户）。
     */
    suspend fun deleteMessageOnOfficial(conversationId: Long, messageId: Long): Boolean {
        if (messageId <= 0) return false
        val tk = token() ?: return false
        val sid = sessionId(conversationId) ?: return false
        return runCatching { client.deleteMessage(tk, sid, messageId) }.getOrDefault(false)
    }

    /**
     * 从官网拉取消息记录（反向同步）并把该对话的官网会话切换到该会话。
     * 策略：先拉官网会话列表，找到与该本地对话最匹配的会话（按 title/时间启发式匹配）；
     * 如果找不到匹配的，则创建新会话（不串流）。
     * 关键：同步后把该对话的 session_id / parent_message_id 切到该会话，
     * 使该对话之后发出的下一条消息正确接在官网对话末尾。
     * 返回带 message_id 的消息列表（空 = 无记录或接口不兼容）。
     */
    suspend fun syncOfficialSession(conversationId: Long, localTitle: String? = null): List<DeepSeekWebClient.WebMessage> {
        val tk = token() ?: return emptyList()
        val sessions = client.fetchSessions(tk)
        
        // 尝试按标题匹配；如果没有标题或没匹配到，回退到按时间最近的会话
        // 但必须确保该会话还没被其他本地对话占用
        val usedSessions = getUsedSessionIds()
        val availableSessions = sessions.filter { it.id !in usedSessions || it.id == sessionId(conversationId) }
        
        var targetSession: String? = if (localTitle != null && localTitle.isNotBlank()) {
            availableSessions.firstOrNull { it.title.trim().lowercase() == localTitle.trim().lowercase() }?.id
        } else null
        if (targetSession == null) {
            targetSession = availableSessions.maxByOrNull { it.updatedAt }?.id
        }
        if (targetSession == null) {
            targetSession = sessionId(conversationId)  // 回退到当前对话已关联的会话
        }
        if (targetSession == null) {
            targetSession = client.createSession(tk).also { sid ->
                prefs.edit().putString(sessionKey(conversationId), sid).apply()
            }
        }
        
        val msgs = client.fetchHistory(tk, targetSession)
        if (msgs.isNotEmpty()) {
            val lastId = msgs.lastOrNull()?.id
            val edit = prefs.edit().putString(sessionKey(conversationId), targetSession)
            if (lastId != null) edit.putLong(parentKey(conversationId), lastId)
            edit.apply()
        }
        return msgs
    }
    
    /**
     * 获取已被其他本地对话占用的官网 sessionId 集合
     */
    private fun getUsedSessionIds(): Set<String> {
        val allKeys = prefs.all.keys
        return allKeys
            .filter { it.startsWith("session_id_") }
            .mapNotNull { prefs.getString(it, "")?.takeIf { it.isNotBlank() } }
            .toSet()
    }
}
