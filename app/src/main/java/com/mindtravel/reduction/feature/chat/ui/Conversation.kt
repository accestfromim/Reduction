package com.mindtravel.reduction.feature.chat.ui

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.data.HistoryRepository
import com.mindtravel.reduction.feature.chat.model.SystemPrompt
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.Role
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
    private val today: () -> String,
    /** Things about the user's devices worth a sentence in this reply, such as a battery running low. */
    private val deviceNotices: () -> List<String> = { emptyList() },
    /** Where the assistant is running, as it should describe itself. */
    private val place: String = "安卓手机"
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
    fun submit(raw: String): Boolean = submit(raw, raw)

    /**
     * As [submit], for a message that does not come from the user's mouth, such as the
     * report of a finished sub agent: the assistant is given [raw], the screen shows [shown].
     */
    @Synchronized
    fun submit(raw: String, shown: String): Boolean {
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
        emitter.emit("onReplyBegin", id, shown.trim().ifEmpty { text })

        val directory = cards.list().map { card ->
            "- ${card.name}：${card.purpose.ifBlank { "（没有写用途）" }}（${card.chars} 字）" +
                if (cards.isLocked(card.name)) "【用户已审核并锁定的 prompt】" else ""
        }
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(role = Role.SYSTEM, content = SystemPrompt.build(settings, today(), directory, runCatching(deviceNotices).getOrDefault(emptyList()), place))
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
