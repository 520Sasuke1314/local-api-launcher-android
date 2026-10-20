package com.dslauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 本地留存的单条消息（防撤回用）。 */
data class StoredMessage(
    /** 本地自增序号（主键） */
    val seq: Long,
    val conversationId: Long,
    /** 官网 message_id（可能拿不到） */
    val officialId: Long?,
    val role: String,
    val content: String,
    val createdAt: Long,
    /** 官网侧该消息已消失 → 被撤回 */
    val recalled: Boolean = false,
    val recalledAt: Long = 0L,
    /** 官网侧内容被改写 */
    val edited: Boolean = false
)

/**
 * 本地消息快照 —— 防撤回核心。
 *
 * 结构对齐 上游实现 的 `entries.jsonl` 持久化（append 日志 + 状态标记）：
 *  1) 每轮对话结束，把「用户消息 + 助手回复」原文快照落盘；
 *  2) 之后拉官网历史对账：官网侧消失 = 被撤回，内容变了 = 被改写；
 *  3) 本地原文**永不删除**，只打标记 —— 官网撤回后本地照旧能看到全文。
 */
class MessageStore(context: Context) {

    private val file = File(context.filesDir, "messages.jsonl")
    private val lock = Any()

    // ---- 持久化原语 ----

    private fun load(): MutableList<StoredMessage> = synchronized(lock) {
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                StoredMessage(
                    seq = o.optLong("seq"),
                    conversationId = o.optLong("cid"),
                    officialId = o.optLong("oid", -1L).takeIf { it > 0 },
                    role = o.optString("role"),
                    content = o.optString("content"),
                    createdAt = o.optLong("ts"),
                    recalled = o.optBoolean("recalled", false),
                    recalledAt = o.optLong("recalledAt", 0L),
                    edited = o.optBoolean("edited", false)
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun save(list: List<StoredMessage>) = synchronized(lock) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(
                JSONObject()
                    .put("seq", m.seq)
                    .put("cid", m.conversationId)
                    .put("oid", m.officialId ?: -1L)
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("ts", m.createdAt)
                    .put("recalled", m.recalled)
                    .put("recalledAt", m.recalledAt)
                    .put("edited", m.edited)
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }

    // ---- 写入 ----

    /** 追加一条快照。同 (cid, officialId) 已存在则更新内容，不重复。 */
    fun add(
        conversationId: Long,
        officialId: Long?,
        role: String,
        content: String
    ): StoredMessage {
        val list = load()
        if (content.isBlank()) return StoredMessage(0, conversationId, officialId, role, content, 0L)
        // 已存在：按 officialId 精确匹配，或按 role+内容 兜底去重
        val idx = if (officialId != null) list.indexOfFirst {
            it.conversationId == conversationId && it.officialId == officialId
        } else -1
        if (idx >= 0) {
            val old = list[idx]
            if (old.content != content) list[idx] = old.copy(content = content, edited = true)
            save(list)
            return list[idx]
        }
        val dup = list.indexOfFirst {
            it.conversationId == conversationId && it.role == role && it.content == content
        }
        if (dup >= 0) return list[dup]

        val seq = (list.maxOfOrNull { it.seq } ?: 0L) + 1L
        val msg = StoredMessage(
            seq = seq,
            conversationId = conversationId,
            officialId = officialId,
            role = role,
            content = content,
            createdAt = System.currentTimeMillis()
        )
        list.add(msg)
        save(list)
        return msg
    }

    /** 重新挂上官网 message_id（快照时还没有 id 的情况）。 */
    fun attachOfficialId(conversationId: Long, role: String, content: String, officialId: Long) {
        val list = load()
        val idx = list.indexOfLast {
            it.conversationId == conversationId && it.role == role &&
                it.content == content && it.officialId == null
        }
        if (idx >= 0) {
            list[idx] = list[idx].copy(officialId = officialId)
            save(list)
        }
    }

    // ---- 读取 ----

    fun list(conversationId: Long? = null): List<StoredMessage> =
        load().filter { conversationId == null || it.conversationId == conversationId }
            .sortedBy { it.seq }

    fun recalledCount(conversationId: Long? = null): Int =
        list(conversationId).count { it.recalled }

    fun clear(conversationId: Long? = null) = synchronized(lock) {
        if (conversationId == null) file.delete()
        else save(load().filterNot { it.conversationId == conversationId })
    }

    // ---- 对账：把本地快照与官网现况比对 ----

    /**
     * 与官网历史对账。
     * @param official 官网现存消息 (officialId?, content) 列表
     * @return 本次新标记为「已撤回」的条数
     */
    fun reconcile(conversationId: Long, official: List<Pair<Long?, String>>): Int {
        val list = load()
        val officialIds = official.mapNotNull { it.first }.toSet()
        val officialContent = official.map { normalize(it.second) }.toSet()
        var newlyRecalled = 0
        val now = System.currentTimeMillis()
        val updated = list.map { m ->
            if (m.conversationId != conversationId || m.recalled) return@map m
            // 官网侧消失了：有 id 时按 id 判定，否则按内容判定
            val gone = if (m.officialId != null) {
                m.officialId !in officialIds
            } else {
                normalize(m.content) !in officialContent
            }
            if (gone && official.isNotEmpty()) {
                newlyRecalled++
                m.copy(recalled = true, recalledAt = now)
            } else m
        }
        save(updated)
        return newlyRecalled
    }

    /** 归一化：去思考块、折叠空白 —— 让本地与官网正文可比。 */
    private fun normalize(s: String): String = s
        .replace(Regex("(\\[思考\\]|【思考】)([\\s\\S]*?)(\\[/思考\\]|【/思考】)"), "")
        .replace(Regex("【reference:\\d+】|\\[reference:\\d+\\]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        const val FILE_NAME = "messages.jsonl"
    }
}
