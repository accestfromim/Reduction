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
 * A to-do is due on a day; it has no hour. It may also say which part of the day —
 * early morning, morning, afternoon, evening — and that is all the finer it gets. What the user said in relative terms —
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
        /** The number it is called by. It may change once, when two devices gave out the same number. */
        var id: Int,
        /** What to do, in the user's words. One line. */
        var text: String,
        /** The day it is due; null when the date is empty. */
        var due: LocalDate?,
        /** The part of the day it belongs to, when one was said; null means the day as a whole. No hour stands behind it. */
        var part: Part? = null,
        var done: Boolean,
        val createdAt: Long,
        var doneAt: Long = 0,
        /** The last day the morning reminder brought it up, as yyyy-MM-dd; "" when never. */
        var remindedOn: String = "",
        /** Its name across devices; the number is only what it is called by on the card. */
        val uid: String = java.util.UUID.randomUUID().toString(),
        /** When what it says, its day, or whether it is done last changed. */
        var updatedAt: Long = createdAt
    )

    /** The parts of a day a to-do may belong to, in the order they come. */
    enum class Part(val label: String) {
        EARLY("早上"), MORNING("上午"), AFTERNOON("下午"), EVENING("晚上");

        companion object {
            fun of(said: String?): Part? = entries.firstOrNull { it.label == said?.trim() || it.name.equals(said?.trim(), ignoreCase = true) }
        }
    }

    /** The to-dos deleted here and when, by uid: so that another device deletes them too. */
    private val deleted = HashMap<String, Long>()

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
            saved.optJSONObject("deleted")?.let { gone -> for (uid in gone.keys()) deleted[uid] = gone.getLong(uid) }
            val list = saved.optJSONArray("items") ?: JSONArray()
            var unnamed = false
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (!item.has("uid")) unnamed = true
                items += read(item)
            }
            // To-dos written before they had names of their own are given them now, once.
            if (unnamed) save()
        }
    }

    private fun read(item: JSONObject): Memo = Memo(
        id = item.getInt("id"),
        text = item.getString("text"),
        due = dayOf(item.opt("due")),
        part = Part.of(item.optString("part")),
        done = item.optBoolean("done"),
        createdAt = item.optLong("createdAt"),
        doneAt = item.optLong("doneAt"),
        remindedOn = item.optString("remindedOn"),
        uid = item.optString("uid").ifEmpty { java.util.UUID.randomUUID().toString() },
        updatedAt = item.optLong("updatedAt", item.optLong("createdAt"))
    )

    private fun write(memo: Memo): JSONObject = JSONObject().apply {
        put("id", memo.id)
        put("text", memo.text)
        put("due", memo.due?.toString() ?: JSONObject.NULL)
        put("part", memo.part?.name ?: JSONObject.NULL)
        put("done", memo.done)
        put("createdAt", memo.createdAt)
        put("doneAt", memo.doneAt)
        put("remindedOn", memo.remindedOn)
        put("uid", memo.uid)
        put("updatedAt", memo.updatedAt)
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
    fun add(text: String, due: LocalDate?, part: Part? = null): Memo {
        val memo = Memo(nextId++, oneLine(text), due, part, done = false, createdAt = now())
        items += memo
        save()
        return memo
    }

    /** Changes what a to-do says, the day it is due, or both. The day changes only when [changeDue] is set. */
    @Synchronized
    fun update(
        id: Int, text: String? = null, due: LocalDate? = null, changeDue: Boolean = false,
        part: Part? = null, changePart: Boolean = false
    ): Memo? {
        val memo = find(id) ?: return null
        if (changePart) memo.part = part
        if (text != null && text.isNotBlank()) memo.text = oneLine(text)
        memo.updatedAt = now()
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
        memo.updatedAt = now()
        if (!done) memo.remindedOn = ""
        save()
        return memo
    }

    @Synchronized
    fun delete(id: Int): Boolean {
        val memo = find(id) ?: return false
        items -= memo
        deleted[memo.uid] = now()
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

    // ── sharing the to-dos with another device ──────────────────────────────

    /** The to-dos as another device needs them to merge with its own. */
    @Synchronized
    fun export(): JSONObject = JSONObject().apply {
        put("next", nextId)
        put("items", JSONArray().apply { for (memo in items) put(write(memo)) })
        put("deleted", JSONObject().apply { for ((uid, at) in deleted) put(uid, at) })
    }

    /**
     * Takes in another device's to-dos. For each the later change stands; one deleted
     * there after it last changed here is deleted here. What was reminded stays each
     * device's own. Two to-dos may have been given the same number on the two devices:
     * the numbers of the device that [numbers] are right. Returns whether anything changed.
     */
    @Synchronized
    fun merge(remote: JSONObject, numbers: Numbers): Boolean {
        var changed = false
        nextId = maxOf(nextId, remote.optInt("next", 1))
        val gone = remote.optJSONObject("deleted") ?: JSONObject()
        for (uid in gone.keys()) {
            val at = gone.optLong(uid)
            val mine = items.firstOrNull { it.uid == uid }
            if (mine != null && mine.updatedAt > at) continue
            if (mine != null) { items -= mine; changed = true }
            if ((deleted[uid] ?: 0L) < at) deleted[uid] = at
        }
        val theirs = remote.optJSONArray("items") ?: JSONArray()
        if (numbers == Numbers.THEIRS) {
            // Their numbers stand: the to-dos both devices know take theirs, and one known only here
            // that holds a number of theirs takes a fresh one.
            val numbered = HashMap<String, Int>()
            for (i in 0 until theirs.length()) {
                val item = theirs.optJSONObject(i) ?: continue
                if (item.has("uid") && item.has("id")) numbered[item.getString("uid")] = item.getInt("id")
            }
            for (memo in items) {
                val id = numbered[memo.uid]
                if (id != null && memo.id != id) { memo.id = id; changed = true }
            }
            val taken = numbered.values.toHashSet()
            for (memo in items) {
                if (memo.uid !in numbered && memo.id in taken) { memo.id = nextId++; changed = true }
            }
        }
        for (i in 0 until theirs.length()) {
            val item = theirs.optJSONObject(i) ?: continue
            val theirsNow = runCatching { read(item) }.getOrNull() ?: continue
            if (!item.has("uid") || (deleted[theirsNow.uid] ?: 0L) >= theirsNow.updatedAt) continue
            val mine = items.firstOrNull { it.uid == theirsNow.uid }
            if (mine == null) {
                theirsNow.remindedOn = ""
                // Where this device's numbers stand, an arriving to-do whose number is taken gets a fresh one.
                if (items.any { it.id == theirsNow.id }) theirsNow.id = nextId++
                items += theirsNow
                changed = true
                continue
            }
            if (theirsNow.updatedAt > mine.updatedAt) {
                if (mine.due != theirsNow.due) mine.remindedOn = ""
                mine.text = theirsNow.text; mine.due = theirsNow.due; mine.part = theirsNow.part; mine.done = theirsNow.done
                mine.doneAt = theirsNow.doneAt; mine.updatedAt = theirsNow.updatedAt
                changed = true
            }
        }
        nextId = maxOf(nextId, (items.maxOfOrNull { it.id } ?: 0) + 1)
        if (changed) {
            val cutoff = now() - DELETED_KEPT_MS
            deleted.values.removeAll { it < cutoff }
            save()
        }
        return changed
    }

    /** Whose numbers stand when the two devices gave the same number to different to-dos. */
    enum class Numbers { MINE, THEIRS }

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
    fun view(day: LocalDate = today()): View {
        fun listed(memos: List<Memo>): List<Memo> = memos
            .sortedWith(compareBy<Memo> { it.done }.thenBy { it.due ?: LocalDate.MAX }.thenBy { it.part?.ordinal ?: -1 })

        return View(
            today = listed(items.filter { it.due != null && it.due!! <= day }),
            future = listed(items.filter { it.due != null && it.due!! > day }),
            undated = listed(items.filter { it.due == null })
        )
    }

    fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()

    /** Whether [memo] is open and its day has passed. */
    fun isOverdue(memo: Memo, day: LocalDate = today()): Boolean = !memo.done && (memo.due?.isBefore(day) == true)

    private fun oneLine(text: String): String =
        text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)

    @Synchronized
    private fun save() {
        runCatching {
            val json = JSONObject().apply {
                put("next", nextId)
                put("askedOn", askedOn)
                put("items", JSONArray().apply { for (memo in items) put(write(memo)) })
                put("deleted", JSONObject().apply { for ((uid, at) in deleted) put(uid, at) })
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
        private const val DELETED_KEPT_MS = 90L * 24 * 3_600_000
    }
}
