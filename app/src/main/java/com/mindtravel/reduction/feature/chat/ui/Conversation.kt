package com.mindtravel.reduction.feature.chat.ui

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.data.HistoryRepository
import com.mindtravel.reduction.feature.chat.model.SystemPrompt
import com.mindtravel.reduction.model.chat.ChatImage
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
    /** To-dos without a date: said once a day, when the user first speaks. */
    private val todoNotices: () -> List<String> = { emptyList() },
    /** Where the assistant is running, as it should describe itself. */
    private val place: String = "安卓手机"
) {
    /** A line carries a name of its own and the time it was said: that is what lets two devices share one conversation. */
    private class Line(
        val id: String, val role: Role, val content: String, val at: Long,
        /**
         * Pictures that came with this line. Held while the app runs, so that the next
         * few replies can still look at them; the record on disk keeps the words only.
         */
        val images: List<ChatImage> = emptyList()
    )

    private val lines = ArrayList<Line>()

    /** Told whenever something was said here, by the user or the assistant, or the conversation was cleared. */
    @Volatile
    var onChange: () -> Unit = {}

    init {
        runCatching {
            val saved = JSONArray(history.load())
            var unnamed = false
            val now = System.currentTimeMillis()
            for (i in 0 until saved.length()) {
                val item = saved.optJSONObject(i) ?: continue
                val role = when (item.optString("role")) {
                    "user" -> Role.USER
                    "assistant" -> Role.ASSISTANT
                    else -> continue
                }
                val content = if (role == Role.ASSISTANT) said(item.optString("content")) else item.optString("content")
                if (content.isBlank()) continue
                val id = item.optString("id")
                if (id.isEmpty() || !item.has("at")) unnamed = true
                // A line written before lines had names: named now, and dated in the order it stands in.
                lines += Line(id.ifEmpty { UUID.randomUUID().toString() }, role, content, item.optLong("at", now - (saved.length() - i) * 1000L))
            }
            if (unnamed) history.save(historyJson())
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
    fun submit(raw: String, shown: String): Boolean = submit(raw, shown, emptyList())

    /**
     * As above, with pictures for the assistant to look at. They go to the model with the
     * message, and again with the replies that follow while they are among the latest
     * [PICTURES_IN_VIEW]; they are not written to the record.
     */
    @Synchronized
    fun submit(raw: String, shown: String, images: List<ChatImage>): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onError", "", "请先填写 DeepSeek API Key")
            return false
        }

        lines += Line(UUID.randomUUID().toString(), Role.USER, text, System.currentTimeMillis(), images)
        save()
        val id = UUID.randomUUID().toString()
        emitter.emit("onReplyBegin", id, shown.trim().ifEmpty { text })

        val directory = cards.list().map { card ->
            "- ${card.name}：${card.purpose.ifBlank { "（没有写用途）" }}（${card.chars} 字）" +
                if (cards.isLocked(card.name)) "【用户已审核并锁定的 prompt】" else ""
        }
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(
            role = Role.SYSTEM,
            content = SystemPrompt.build(
                settings, today(), directory,
                runCatching(deviceNotices).getOrDefault(emptyList()),
                runCatching(todoNotices).getOrDefault(emptyList()),
                place
            )
        )
        // The latest pictures stay in view; older ones have been spoken of and are let go.
        val shown = lines.takeLast(CONTEXT_LINES)
        var room = PICTURES_IN_VIEW
        val withPictures = HashSet<String>()
        for (line in shown.asReversed()) {
            if (line.images.isEmpty() || line.images.size > room) continue
            room -= line.images.size
            withPictures += line.id
        }
        shown.mapTo(messages) { line ->
            ChatMessage(role = line.role, content = line.content, images = if (line.id in withPictures) line.images else emptyList())
        }

        return chat.send(id, messages) { reply -> remember(reply) }
    }

    /**
     * What the user typed together with pictures picked, photographed or pasted. [picturesJson]
     * is a list of {name, mediaType, base64}. The words kept say that pictures came along.
     */
    @Synchronized
    fun submitWithPictures(raw: String, picturesJson: String): Boolean {
        val images = ChatImage.listFromJson(picturesJson)
        if (images.isEmpty()) return submit(raw)
        val said = raw.trim().ifEmpty { "请看图。" }
        return submit("$said\n［随这条消息发来 ${images.size} 张图片］", said, images)
    }

    /** Everything said so far, as the JSON the UI draws from. */
    @Synchronized
    fun historyJson(): String = JSONArray().apply {
        for (line in lines) {
            put(
                JSONObject().put("role", if (line.role == Role.USER) "user" else "assistant").put("content", line.content)
                    .put("id", line.id).put("at", line.at)
            )
        }
    }.toString()

    @Synchronized
    fun clear() {
        lines.clear()
        history.clear()
        history.clearedAt = System.currentTimeMillis()
        runCatching { onChange() }
    }

    // ── sharing the conversation with another device ────────────────────────

    /** The conversation as another device needs it to merge with its own. */
    @Synchronized
    fun export(): JSONObject = JSONObject().put("clearedAt", history.clearedAt).put("lines", JSONArray(historyJson()))

    /**
     * Takes in another device's conversation: what was said there joins what was said
     * here, in the order it was said, and what was cleared there is cleared here. Returns
     * whether anything changed.
     */
    @Synchronized
    fun merge(remote: JSONObject): Boolean {
        val before = lines.map { it.id }
        val cleared = maxOf(history.clearedAt, remote.optLong("clearedAt"))
        if (cleared > history.clearedAt) history.clearedAt = cleared
        lines.removeAll { it.at <= cleared }
        val known = lines.mapTo(HashSet()) { it.id }
        val theirs = remote.optJSONArray("lines") ?: JSONArray()
        for (i in 0 until theirs.length()) {
            val item = theirs.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val at = item.optLong("at")
            val role = when (item.optString("role")) {
                "user" -> Role.USER
                "assistant" -> Role.ASSISTANT
                else -> continue
            }
            val content = item.optString("content")
            if (id.isEmpty() || content.isBlank() || at <= cleared || !known.add(id)) continue
            lines += Line(id, role, content, at)
        }
        lines.sortBy { it.at }
        while (lines.size > KEPT_LINES) lines.removeAt(0)
        if (lines.map { it.id } == before) return false
        history.save(historyJson())
        emitter.emit("onHistoryChanged", "")
        return true
    }

    @Synchronized
    private fun remember(reply: String) {
        if (reply.isBlank()) return
        lines += Line(UUID.randomUUID().toString(), Role.ASSISTANT, reply, System.currentTimeMillis())
        save()
    }

    private fun save() {
        while (lines.size > KEPT_LINES) lines.removeAt(0)
        history.save(historyJson())
        runCatching { onChange() }
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

        /** How many of the latest pictures the assistant is still shown with each request. */
        const val PICTURES_IN_VIEW = 4
    }
}
