package com.mindtravel.reduction.feature.memo.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The memo plugin: to-dos and their days, stored in [file] and nowhere else. The
 * assistant writes here through its tool, the review card reads from here, and the
 * reminders are worked out from here. Nothing else keeps to-dos.
 *
 * A to-do is due on a day; it has no hour. What the user said in relative terms —
 * "明天", "三天后" — is turned into a date when it is written, so a stored to-do means
 * the same thing however it is read later. A to-do may be written without a date; its
 * date is then empty.
 *
 * The reminder is one a day, in the morning, and it is about that day: what is due
 * today, and what was due earlier and is still open. To-dos with an empty date count as
 * tomorrow's, and are brought up with the question whether to fill their date in.
 *
 * A finished to-do leaves the reminders and stays in the record.
 */
class MemoStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    class Memo(
        val id: Int,
        /** What to do, in the user's words. One line. */
        var text: String,
        /** The day it is due; null when the date is empty. */
        var due: LocalDate?,
        var done: Boolean,
        val createdAt: Long,
        var doneAt: Long = 0,
        /** The last day the morning reminder brought it up, as yyyy-MM-dd; "" when never. */
        var remindedOn: String = ""
    )

    /** Told after every change, so that the card, the reminders and the page can follow. */
    @Volatile
    var onChange: () -> Unit = {}

    private val items = ArrayList<Memo>()
    private var nextId = 1

    /** The last day the to-dos with an empty date were put to the user in talk, as yyyy-MM-dd; "" when never. */
    private var askedOn = ""

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val saved = JSONObject(file.readText())
            nextId = saved.optInt("next", 1)
            askedOn = saved.optString("askedOn")
            val list = saved.optJSONArray("items") ?: JSONArray()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                items += Memo(
                    id = item.getInt("id"),
                    text = item.getString("text"),
                    due = dayOf(item.opt("due")),
                    done = item.optBoolean("done"),
                    createdAt = item.optLong("createdAt"),
                    doneAt = item.optLong("doneAt"),
                    remindedOn = item.optString("remindedOn")
                )
            }
        }
    }

    /** A stored day: yyyy-MM-dd, or a moment from a file written when to-dos still had hours. */
    private fun dayOf(stored: Any?): LocalDate? = when (stored) {
        is String -> runCatching { LocalDate.parse(stored) }.getOrNull()
        is Number -> Instant.ofEpochMilli(stored.toLong()).atZone(zone).toLocalDate()
        else -> null
    }

    @Synchronized
    fun all(): List<Memo> = items.toList()

    @Synchronized
    fun open(): List<Memo> = items.filter { !it.done }

    /** The to-do with this number, or null. */
    @Synchronized
    fun find(id: Int): Memo? = items.firstOrNull { it.id == id }

    /** Registers a to-do. A null [due] means its date is empty. */
    @Synchronized
    fun add(text: String, due: LocalDate?): Memo {
        val memo = Memo(nextId++, oneLine(text), due, done = false, createdAt = now())
        items += memo
        save()
        return memo
    }

    /** Changes what a to-do says, the day it is due, or both. The day changes only when [changeDue] is set. */
    @Synchronized
    fun update(id: Int, text: String? = null, due: LocalDate? = null, changeDue: Boolean = false): Memo? {
        val memo = find(id) ?: return null
        if (text != null && text.isNotBlank()) memo.text = oneLine(text)
        if (changeDue && memo.due != due) {
            memo.due = due
            // A new day is a new promise: what was reminded of the old one no longer counts.
            memo.remindedOn = ""
        }
        save()
        return memo
    }

    /** Marks a to-do finished, or open again. Finishing takes it out of the reminders; the record stays. */
    @Synchronized
    fun setDone(id: Int, done: Boolean): Memo? {
        val memo = find(id) ?: return null
        if (memo.done == done) return memo
        memo.done = done
        memo.doneAt = if (done) now() else 0
        if (!done) memo.remindedOn = ""
        save()
        return memo
    }

    @Synchronized
    fun delete(id: Int): Boolean {
        val memo = find(id) ?: return false
        items -= memo
        save()
        return true
    }

    /** Notes that today's reminder has brought [memos] up. */
    @Synchronized
    fun markReminded(memos: List<Memo>) {
        val day = today().toString()
        for (memo in memos) memo.remindedOn = day
        save()
    }

    /**
     * The open to-dos with an empty date, if the user has not been asked about them in
     * talk today. Asks once a day: the answer is what the user says next.
     */
    @Synchronized
    fun askAboutUndated(): List<Memo> {
        val day = today().toString()
        if (askedOn == day) return emptyList()
        val undated = items.filter { !it.done && it.due == null }
        if (undated.isEmpty()) {
            if (askedOn.isNotEmpty()) { askedOn = ""; save() }
            return emptyList()
        }
        askedOn = day
        save()
        return undated
    }

    /**
     * What a day's reminder is about, worked out afresh from the open to-dos: those due
     * that day, those overdue, and those whose date is empty.
     */
    class Plan(val today: List<Memo>, val overdue: List<Memo>, val empty: List<Memo>) {
        val all: List<Memo> get() = overdue + today + empty
    }

    @Synchronized
    fun plan(): Plan {
        val day = today()
        val open = items.filter { !it.done }
        return Plan(
            today = open.filter { it.due == day },
            overdue = open.filter { it.due != null && it.due!! < day }.sortedBy { it.due },
            // An empty date counts as tomorrow, every day, until a date is filled in.
            empty = open.filter { it.due == null }
        )
    }

    /** What today's reminder still owes: the plan, less what has been brought up today already. */
    @Synchronized
    fun morningReminder(): List<Memo> {
        val day = today().toString()
        return plan().all.filter { it.remindedOn != day }
    }

    // ── the three blocks of the review card ─────────────────────────────────

    class View(
        val today: List<Memo>,
        val future: List<Memo>,
        val undated: List<Memo>
    )

    /**
     * The to-dos as the card shows them: those due today (overdue ones among them, as
     * they are today's work), those due later, and those without a date. In each block
     * the open ones come first, then by day; finished ones stay at the end, greyed.
     */
    @Synchronized
    fun view(): View {
        val day = today()
        fun listed(memos: List<Memo>): List<Memo> = memos
            .sortedWith(compareBy<Memo> { it.done }.thenBy { it.due ?: LocalDate.MAX })

        return View(
            today = listed(items.filter { it.due != null && it.due!! <= day }),
            future = listed(items.filter { it.due != null && it.due!! > day }),
            undated = listed(items.filter { it.due == null })
        )
    }

    fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()

    /** Whether [memo] is open and its day has passed. */
    fun isOverdue(memo: Memo): Boolean = !memo.done && memo.due != null && memo.due!! < today()

    private fun oneLine(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)

    @Synchronized
    private fun save() {
        runCatching {
            val json = JSONObject().apply {
                put("next", nextId)
                put("askedOn", askedOn)
                put("items", JSONArray().apply {
                    for (memo in items) put(JSONObject().apply {
                        put("id", memo.id)
                        put("text", memo.text)
                        put("due", memo.due?.toString() ?: JSONObject.NULL)
                        put("done", memo.done)
                        put("createdAt", memo.createdAt)
                        put("doneAt", memo.doneAt)
                        put("remindedOn", memo.remindedOn)
                    })
                })
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
        runCatching { onChange() }
    }

    companion object {
        const val MAX_TEXT = 200
    }
}
