package com.mindtravel.reduction.feature.chat.data

import com.mindtravel.reduction.feature.chat.model.tools.AgentRuns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The last server timestamp and any unsent edit are persisted together, before attempting SSH. */
class TaskCards(
    private val dir: File,
    private val runs: AgentRuns,
    private val request: (AgentRuns.Run, JSONObject) -> JSONObject
) {
    class Offline(message: String, cause: Exception) : IllegalStateException(message, cause)
    class Conflict(message: String) : IllegalStateException(message)

    fun binding(name: String) = runs.byCard(name)
    private val dirty: MutableSet<Int> by lazy {
        dir.listFiles().orEmpty().mapNotNullTo(mutableSetOf()) { file ->
            val number = file.name.removeSuffix(".json").toIntOrNull()
            number?.takeIf { JSONObject(file.readText()).has("pending") }
        }
    }
    fun rename(run: AgentRuns.Run, name: String) = runs.card(run, name)
    private fun stateFile(run: AgentRuns.Run) = File(dir, "${run.number}.json")
    private fun state(run: AgentRuns.Run): JSONObject {
        val file = stateFile(run)
        return if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }
    private fun persist(run: AgentRuns.Run, state: JSONObject) {
        dir.mkdirs()
        val file = stateFile(run)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(state.toString())
        check(tmp.renameTo(file)) { "无法保存任务卡的同步状态" }
        if (state.has("pending")) dirty.add(run.number) else dirty.remove(run.number)
    }

    private fun call(run: AgentRuns.Run, req: JSONObject): JSONObject = try {
        request(run, req)
    } catch (e: Exception) {
        throw Offline("任务 ${run.number} 未能与服务器核对：${e.message}", e)
    }

    private fun install(run: AgentRuns.Run, s: JSONObject, reply: JSONObject, cards: CardRepository) {
        val name = run.card
        if (reply.optBoolean("deleted")) {
            cards.removeCached(name)
            runs.card(run, "")
        } else if (reply.has("text")) {
            cards.cache(name, reply.getString("text"))
        }
        if (!reply.optBoolean("deleted") && reply.has("locked")) cards.cacheLock(name, reply.getBoolean("locked"))
        s.put("rev", reply.getLong("rev"))
        persist(run, s)
    }

    fun read(run: AgentRuns.Run, cards: CardRepository): String? {
        val s = state(run)
        if (s.has("pending")) {
            try { flush(run, s, cards) } catch (_: Conflict) { /* Draft and remote copy are both on disk. */ }
        }
        if (run.card.isEmpty()) return null
        val base = if (cards.hasCached(run.card)) s.optLong("rev", -1) else -1L
        install(run, s, call(run, JSONObject().put("op", "read").put("base", base)), cards)
        return cards.cached(run.card)
    }

    fun lock(run: AgentRuns.Run, locked: Boolean, cards: CardRepository): Boolean {
        val s = state(run)
        val result = call(run, JSONObject().put("op", "lock").put("base", s.optLong("rev", -1)).put("locked", locked))
        install(run, s, result, cards)
        return !result.optBoolean("conflict") && !result.optBoolean("deleted")
    }

    fun change(run: AgentRuns.Run, body: String?, cards: CardRepository) {
        val s = state(run)
        // Multiple offline saves replace the same draft and keep its original base.
        s.put("pending", body ?: JSONObject.NULL)
        if (!s.has("edit")) s.put("edit", UUID.randomUUID().toString())
        persist(run, s)
        flush(run, s, cards)
    }

    private fun flush(run: AgentRuns.Run, s: JSONObject, cards: CardRepository) {
        val body = if (s.isNull("pending")) null else s.getString("pending")
        val req = JSONObject().put("op", if (body == null) "delete" else "save").put("base", s.optLong("rev", -1))
        if (body != null) req.put("text", body)
        val reply = call(run, req)
        val conflict = reply.optBoolean("conflict") || (body != null && reply.optBoolean("deleted"))
        val draft = if (conflict && body != null) {
            val name = "同步草稿/任务 ${run.number}-${s.getString("edit")}"
            cards.cache(name, body)
            name
        } else null
        // Cache the accepted body before advancing the timestamp, including after an SSH retry.
        if (!conflict && body != null) cards.cache(run.card, body)
        s.remove("pending")
        s.remove("edit")
        install(run, s, reply, cards)
        if (conflict) throw Conflict("服务器正文已变更。" + (draft?.let { "手机编辑已保存在「$it」。" } ?: "删除未执行。"))
    }

    /** A patch can touch several tasks. Check their bases under one set of server locks. */
    fun patch(edits: Map<AgentRuns.Run, String?>, patch: String, cards: CardRepository) {
        if (edits.isEmpty()) return
        val first = edits.keys.first()
        if (edits.size == 1) {
            change(first, edits[first], cards)
            return
        }
        check(edits.keys.all { it.host == first.host }) { "补丁跨越不同服务器" }
        val states = edits.keys.associateWith(::state)
        val payload = JSONArray().apply {
            for ((run, body) in edits) put(JSONObject().put("folder", run.folder).put("id", run.launcherId)
                .put("base", states.getValue(run).optLong("rev", -1)).put("text", body ?: JSONObject.NULL))
        }
        val result = try {
            call(first, JSONObject().put("op", "batch").put("edits", payload))
        } catch (e: Offline) {
            val draft = "同步草稿/补丁-${UUID.randomUUID()}"
            cards.cache(draft, patch)
            throw Offline("整份补丁保存在「$draft」。${e.message}", e)
        }
        val conflicts = result.getBoolean("conflict")
        val replies = result.getJSONArray("replies")
        for ((i, entry) in edits.entries.withIndex()) {
            val (run, body) = entry
            if (!conflicts && body != null) cards.cache(run.card, body)
            install(run, states.getValue(run), replies.getJSONObject(i), cards)
        }
        if (conflicts) {
            val draft = "同步草稿/补丁-${UUID.randomUUID()}"
            cards.cache(draft, patch)
            throw Conflict("任务卡已被更新，整份补丁保存在「$draft」。")
        }
    }

    fun retry(cards: CardRepository) {
        for (number in dirty.toList()) {
            val run = runs.find(number) ?: continue
            val s = state(run)
            if (s.has("pending")) runCatching { flush(run, s, cards) }
        }
    }

    fun forget(run: AgentRuns.Run, cards: CardRepository) {
        if (run.card.isNotEmpty()) cards.removeCached(run.card)
        dirty.remove(run.number)
        stateFile(run).delete()
    }
}
