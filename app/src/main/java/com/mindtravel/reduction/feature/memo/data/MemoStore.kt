package com.mindtravel.reduction.feature.memo.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The memo plugin: to-dos and their times, stored in [file] and nowhere else. The
 * assistant writes here through its tool, the review card reads from here, and the
 * reminders are worked out from here. Nothing else keeps to-dos.
 *
 * A to-do without a time is a note to oneself; one with a time is also a reminder. A
 * time is stored as the moment it falls on: what the user said in relative terms —
 * "明早", "三天后" — is turned into a date and a time when it is written, so a stored
 * to-do means the same thing however it is read later.
 *
 * A finished to-do leaves the reminder queue and stays in the record.
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
        /** When it is due, to the minute; null when the date is empty. */
        var dueAt: Long?,
        var done: Boolean,
        val createdAt: Long,
        var doneAt: Long = 0,
        /** Whether the reminder at the due time has gone out. A changed time clears it. */
        var dueReminded: Boolean = false,
        /** The last day the morning reminder was given for it — overdue, or with an empty date — as yyyy-MM-dd. */
        var overdueRemindedOn: String = ""
    )

    /** Told after every change, so that the card, the reminders and the page can follow. */
    @Volatile
    var onChange: () -> Unit = {}

    private val items = ArrayList<Memo>()
    private var nextId = 1

    /** The last day the undated to-dos were put to the user, as yyyy-MM-dd; "" when never. */
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
                    dueAt = if (item.isNull("due")) null else item.getLong("due"),
                    done = item.optBoolean("done"),
                    createdAt = item.optLong("createdAt"),
                    doneAt = item.optLong("doneAt"),
                    dueReminded = item.optBoolean("dueReminded"),
                    overdueRemindedOn = item.optString("overdueRemindedOn")
                )
            }
        }
    }

    @Synchronized
    fun all(): List<Memo> = items.toList()

    @Synchronized
    fun open(): List<Memo> = items.filter { !it.done }

    /** The to-do with this number, or null. */
    @Synchronized
    fun find(id: Int): Memo? = items.firstOrNull { it.id == id }

    /** Registers a to-do. [dueAt] is already an absolute moment; null means it has no date yet. */
    @Synchronized
    fun add(text: String, dueAt: Long?): Memo {
        val memo = Memo(nextId++, oneLine(text), dueAt, done = false, createdAt = now())
        items += memo
        save()
        return memo
    }

    /** Changes what a to-do says, when it is due, or both. A null [text] or [dueAt] leaves that part as it is. */
    @Synchronized
    fun update(id: Int, text: String? = null, dueAt: Long? = null, changeDue: Boolean = false): Memo? {
        val memo = find(id) ?: return null
        if (text != null && text.isNotBlank()) memo.text = oneLine(text)
        if (changeDue && memo.dueAt != dueAt) {
            memo.dueAt = dueAt
            // A new time is a new promise: the reminders for the old one no longer apply.
            memo.dueReminded = false
            memo.overdueRemindedOn = ""
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
        if (!done) {
            // Opened again: it goes back to reminding as it did before.
            memo.dueReminded = false
            memo.overdueRemindedOn = ""
        }
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

    /** Notes that the reminder for [memo]'s due time has gone out. */
    @Synchronized
    fun markDueReminded(memo: Memo) {
        memo.dueReminded = true
        save()
    }

    /** Notes that the overdue reminder went out today for [memos]. */
    @Synchronized
    fun markOverdueReminded(memos: List<Memo>) {
        val day = today().toString()
        for (memo in memos) memo.overdueRemindedOn = day
        save()
    }

    /**
     * The to-dos still waiting for a date, if the user has not been asked about them
     * today. Asks once a day: the answer is what the user says next.
     */
    @Synchronized
    fun askAboutUndated(): List<Memo> {
        val day = today().toString()
        if (askedOn == day) return emptyList()
        val undated = items.filter { !it.done && it.dueAt == null }
        if (undated.isEmpty()) {
            if (askedOn.isNotEmpty()) { askedOn = ""; save() }
            return emptyList()
        }
        askedOn = day
        save()
        return undated
    }

    /**
     * What the reminders should be, worked out afresh from the to-dos: the open ones with
     * a time ahead, the overdue ones, and the ones whose date is empty.
     */
    @Synchronized
    fun plan(moment: Long = now()): Plan {
        val ahead = items.filter { !it.done && it.dueAt != null && it.dueAt!! > moment && !it.dueReminded }
        val overdue = items.filter { !it.done && it.dueAt != null && it.dueAt!! <= moment }
        // An empty date counts as tomorrow, every day, until a date is filled in.
        val empty = items.filter { !it.done && it.dueAt == null }
        return Plan(ahead, overdue, empty)
    }

    class Plan(val due: List<Memo>, val overdue: List<Memo>, val empty: List<Memo>) {
        /** What the morning reminder brings up: the overdue ones, and those with an empty date. */
        val morning: List<Memo> get() = overdue + empty
    }

    /**
     * What the morning reminder still owes today: the overdue to-dos and the ones with an
     * empty date, less those already brought up today.
     */
    @Synchronized
    fun morningReminder(moment: Long = now()): List<Memo> {
        val day = today().toString()
        return plan(moment).morning.filter { it.overdueRemindedOn != day }
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
     * the open ones come first, then by time; finished ones stay at the end, greyed.
     */
    @Synchronized
    fun view(): View {
        val day = today()
        fun listed(memos: List<Memo>): List<Memo> = memos
            .sortedWith(compareBy<Memo> { it.done }.thenBy { it.dueAt ?: Long.MAX_VALUE })

        val todayBlock = listed(items.filter { it.dueAt != null && at(it.dueAt!!).toLocalDate() <= day })
        val future = listed(items.filter { it.dueAt != null && at(it.dueAt!!).toLocalDate() > day })
        val undated = listed(items.filter { it.dueAt == null })
        return View(todayBlock, future, undated)
    }

    fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()

    fun nowMillis(): Long = now()

    fun at(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    /** The moment [date] at [time] falls on, in this device's time zone. */
    fun moment(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    fun parseDate(raw: String): LocalDate? = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

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
                        if (memo.dueAt == null) put("due", JSONObject.NULL) else put("due", memo.dueAt)
                        put("done", memo.done)
                        put("createdAt", memo.createdAt)
                        put("doneAt", memo.doneAt)
                        put("dueReminded", memo.dueReminded)
                        put("overdueRemindedOn", memo.overdueRemindedOn)
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
