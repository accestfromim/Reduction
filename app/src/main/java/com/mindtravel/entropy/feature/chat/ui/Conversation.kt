package com.mindtravel.entropy.feature.chat.ui

import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.core.events.EventSink
import com.mindtravel.entropy.core.settings.SettingsGateway
import com.mindtravel.entropy.feature.chat.data.CardRepository
import com.mindtravel.entropy.feature.chat.data.HistoryRepository
import com.mindtravel.entropy.feature.chat.model.SystemPrompt
import com.mindtravel.entropy.model.chat.ChatMessage
import com.mindtravel.entropy.model.chat.Role
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The conversation with the assistant: what has been said, and the one way to say
 * something more. It needs no screen — a turn spoken with the display off goes through
 * [submit] exactly as a typed one does. A UI, when there is one, is told of each new
 * exchange and can read the history.
 */
class Conversation(
    private val settingsRepository: SettingsGateway,
    private val history: HistoryRepository,
    private val cards: CardRepository,
    private val chat: ChatController,
    private val emitter: EventSink,
    /** Today's date as yyyy-MM-dd. */
    private val today: () -> String
) {
    private class Line(val role: Role, val content: String)

    private val lines = ArrayList<Line>()

    init {
        runCatching {
            val saved = JSONArray(history.load())
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val role = when (item.optString("role")) {
                    "user" -> Role.USER
                    "assistant" -> Role.ASSISTANT
                    else -> continue
                }
                val content = if (role == Role.ASSISTANT) said(item.optString("content")) else item.optString("content")
                if (content.isNotBlank()) lines += Line(role, content)
            }
        }.onFailure { Logs.add("History unreadable: ${it.message}") }
    }

    /**
     * Says [raw] to the assistant and starts its reply. Returns false, having told the UI
     * why, when it cannot be sent.
     */
    @Synchronized
    fun submit(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onError", "", "请先填写 DeepSeek API Key")
            return false
        }

        lines += Line(Role.USER, text)
        save()
        val id = UUID.randomUUID().toString()
        emitter.emit("onReplyBegin", id, text)

        val directory = cards.list().map { card ->
            "- ${card.name}：${card.purpose.ifBlank { "（没有写用途）" }}（${card.chars} 字）"
        }
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(role = Role.SYSTEM, content = SystemPrompt.build(settings, today(), directory))
        lines.takeLast(CONTEXT_LINES).mapTo(messages) { ChatMessage(role = it.role, content = it.content) }

        return chat.send(id, messages) { reply -> remember(reply) }
    }

    /** Everything said so far, as the JSON the UI draws from. */
    @Synchronized
    fun historyJson(): String = JSONArray().apply {
        for (line in lines) {
            put(JSONObject().put("role", if (line.role == Role.USER) "user" else "assistant").put("content", line.content))
        }
    }.toString()

    @Synchronized
    fun clear() {
        lines.clear()
        history.clear()
    }

    @Synchronized
    private fun remember(reply: String) {
        if (reply.isBlank()) return
        lines += Line(Role.ASSISTANT, reply)
        save()
    }

    private fun save() {
        while (lines.size > KEPT_LINES) lines.removeAt(0)
        history.save(historyJson())
    }

    private companion object {
        /**
         * A stored reply may be written in two tagged sections, the second one never read
         * aloud. Only what was said is kept, so the assistant sees one way of replying.
         */
        fun said(reply: String): String = reply.substringBefore("【详情】").replace("【朗读】", "").trim()

        /** How much of the conversation the assistant is shown. */
        const val CONTEXT_LINES = 20
        const val KEPT_LINES = 100
    }
}
