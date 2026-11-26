package com.mindtravel.reduction.feature.sync

import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.ui.Conversation
import com.mindtravel.reduction.feature.memo.data.MemoStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * What the user's devices share: the conversation, the cards and the to-dos. Each device
 * keeps its own copy and works without the other; when they meet, each takes in what
 * the other has, and for anything changed on both the later change stands.
 *
 * The computer is the meeting point. The phone sends what it has; the computer merges
 * that into its own and answers with the result, which the phone merges in turn. After
 * one such exchange both hold the same. Settings are each device's own and do not travel.
 */
class DataSync(
    private val cards: CardRepository,
    private val memos: MemoStore,
    private val conversation: Conversation,
    /** True on the computer: its numbering of to-dos is the one that stands. */
    private val hub: Boolean
) {
    /** Everything this device holds, and the media it has, for the other to merge. */
    fun export(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("cards", cards.export(includeTaskCards = false))
        .put("memos", memos.export())
        .put("history", conversation.export())
        .put("media", JSONArray(cards.heldMedia().sorted()))

    /** Takes in what the other device holds. Returns whether anything here changed. */
    fun merge(remote: JSONObject): Boolean {
        if (remote.optInt("version") != VERSION) return false
        var changed = false
        remote.optJSONObject("files")?.let { files ->
            for (id in files.keys()) {
                val bytes = runCatching { Base64.getDecoder().decode(files.getString(id)) }.getOrNull() ?: continue
                if (cards.putMedia(id, bytes)) changed = true
            }
        }
        remote.optJSONObject("cards")?.let { if (cards.merge(it)) changed = true }
        remote.optJSONObject("memos")?.let {
            if (memos.merge(it, if (hub) MemoStore.Numbers.MINE else MemoStore.Numbers.THEIRS)) changed = true
        }
        remote.optJSONObject("history")?.let { if (conversation.merge(it)) changed = true }
        return changed
    }

    /**
     * The pictures and sound the other device lacks, given what it says it has in
     * [remote], as much as fits one exchange; the rest goes with the next.
     */
    fun filesFor(remote: JSONObject): JSONObject {
        val theirs = HashSet<String>()
        remote.optJSONArray("media")?.let { for (i in 0 until it.length()) theirs += it.optString(i) }
        val files = JSONObject()
        var room = FILES_PER_EXCHANGE
        for (id in cards.heldMedia().sorted()) {
            if (id in theirs) continue
            val file = cards.heldFile(id) ?: continue
            if (file.length() > room) continue
            room -= file.length()
            files.put(id, Base64.getEncoder().encodeToString(file.readBytes()))
        }
        return files
    }

    companion object {
        const val VERSION = 1

        /** How many bytes of pictures and sound travel in one exchange. */
        const val FILES_PER_EXCHANGE = 6L * 1024 * 1024

        /** Where the computer's service listens, as seen from the computer itself. */
        const val HUB_URL = "http://127.0.0.1:8780/sync"
    }
}
