package com.mindtravel.reduction.feature.memo

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.feature.memo.data.DueParser
import com.mindtravel.reduction.feature.memo.data.MemoStore
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject
import java.time.LocalDate

/**
 * The one way the assistant touches to-dos. Everything the user says that is a to-do goes
 * through here: add one, move its time, change it, finish it, drop it. A time is given as
 * the day and hour that were meant — "明天" is worked out from today's date first — and
 * the store turns it into a moment the day it is written.
 */
class MemoTool(
    private val store: MemoStore,
    private val emitter: EventSink,
    private val today: () -> LocalDate = { store.today() }
) : ChatTool {

    override val spec = ToolSpec(
        name = "todo",
        description = "待办与提醒的唯一入口。用户说的待办登记在这里；带时间的成为提醒，到点提醒一次，过期未完成每天提醒一次，直到完成或改期。完成只移出提醒，记录保留。" +
            "日期可以不填，这时它是 empty：提醒时视同明天发生，每天早上提醒一次并请用户补全时间，直到补上日期。" +
            "日期用 yyyy-MM-dd，“明天”“后天”这类相对说法先按今天的日期算成具体日期再填；时间用 HH:mm；没说是几点就只填日期，程序按早上九点。",
        parameters = listOf(
            ToolParam("action", "add：登记一条；update：改内容或时间；done：标记完成或重新打开；delete：删掉一条；list：看现在的待办", choices = listOf("add", "update", "done", "delete", "list")),
            ToolParam("id", "update、done、delete 时的编号，例如 3；list 时可不填", required = false),
            ToolParam("text", "add 时必填，update 时可填：要做的事，一句话", required = false),
            ToolParam("date", "add、update 时填：哪一天，yyyy-MM-dd。不填或填 empty 表示日期为 empty；update 时填 empty 是把日期清空", required = false),
            ToolParam("time", "add、update 时填：几点，HH:mm。只填时间不填日期时按今天", required = false),
            ToolParam("done", "action=done 时：true 表示完成，false 表示重新打开", required = false, choices = listOf("true", "false"))
        )
    )

    override fun describe(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        return when (args.stringOrNull("action")?.trim()) {
            "add" -> "记待办：" + args.stringOrNull("text").orEmpty().trim().take(18)
            "update" -> "改待办 #" + args.stringOrNull("id").orEmpty().trim()
            "done" -> "待办 #" + args.stringOrNull("id").orEmpty().trim() + if (args.stringOrNull("done") == "false") " 重新打开" else " 完成"
            "delete" -> "删待办 #" + args.stringOrNull("id").orEmpty().trim()
            "list" -> "看待办"
            else -> spec.name
        }
    }

    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val action = args.stringOrNull("action")?.trim().orEmpty()
        val result = try {
            when (action) {
                "add" -> add(args)
                "update" -> update(args)
                "done" -> finish(args)
                "delete" -> drop(args)
                "list" -> list()
                else -> "action 只能是 add、update、done、delete 或 list"
            }
        } catch (e: IllegalArgumentException) {
            "没有生效：${e.message}"
        }
        if (!result.startsWith("没有生效")) {
            emitter.emit("onMemosChanged", "")
            store.onChange()
        }
        return result
    }

    private fun add(args: JSONObject): String {
        val text = args.stringOrNull("text").orEmpty().trim()
        if (text.isEmpty()) throw IllegalArgumentException("add 要填 text")
        val due = due(args)
        val memo = store.add(text, due)
        return "记下了 #${memo.id}：${memo.text}" + whenLabel(due)
    }

    private fun update(args: JSONObject): String {
        val memo = find(args) ?: throw IllegalArgumentException("没有这条待办")
        val text = args.stringOrNull("text")?.trim()?.takeIf { it.isNotEmpty() }
        val dateField = args.stringOrNull("date")
        val timeField = args.stringOrNull("time")
        val changeDue = !dateField.isNullOrBlank() || !timeField.isNullOrBlank()
        var due = memo.dueAt
        if (changeDue) {
            due = if (isEmpty(dateField) && timeField.isNullOrBlank()) {
                // The date is taken away again: the to-do goes back to empty.
                null
            } else if (dateField.isNullOrBlank() && !timeField.isNullOrBlank()) {
                // Only the hour was given: the day stays as it was, or today when there is none.
                val day = memo.dueAt?.let { store.at(it).toLocalDate() } ?: today()
                val clock = DueParser.timeOf(timeField) ?: throw IllegalArgumentException("时间读不出来（$timeField）")
                store.moment(day, clock.hour, clock.minute)
            } else {
                due(args)
            }
        }
        if (text == null && !changeDue) throw IllegalArgumentException("update 要给出新的 text、date 或 time")
        store.update(memo.id, text, due, changeDue)
        return "改好了 #${memo.id}：${memo.text}" + whenLabel(due)
    }

    private fun finish(args: JSONObject): String {
        val memo = find(args) ?: throw IllegalArgumentException("没有这条待办")
        val done = args.stringOrNull("done")?.trim() != "false"
        store.setDone(memo.id, done)
        return (if (done) "完成了" else "又打开了") + " #${memo.id}：${memo.text}"
    }

    private fun drop(args: JSONObject): String {
        val memo = find(args) ?: throw IllegalArgumentException("没有这条待办")
        store.delete(memo.id)
        return "删掉了 #${memo.id}：${memo.text}"
    }

    private fun list(): String {
        val view = store.view()
        val day = today()
        val now = store.nowMillis()
        fun line(memo: MemoStore.Memo): String {
            val at = memo.dueAt?.let { DueParser.describe(it, day) }
            return "- #${memo.id} " + memo.text + (if (at != null) " · $at" else " · ${DueParser.EMPTY}（按明天提醒，待补时间）") +
                (if (memo.done) " · 已完成" else if (at != null && memo.dueAt!! < now) " · 已过期" else "")
        }
        return buildString {
            appendLine("今天：")
            if (view.today.isEmpty()) appendLine("（没有）") else view.today.forEach { appendLine(line(it)) }
            appendLine("未来：")
            if (view.future.isEmpty()) appendLine("（没有）") else view.future.forEach { appendLine(line(it)) }
            appendLine("没日期：")
            if (view.undated.isEmpty()) appendLine("（没有）") else view.undated.forEach { appendLine(line(it)) }
        }.trimEnd()
    }

    /** Whether the date was left unfilled: not given at all, or given as empty. */
    private fun isEmpty(date: String?): Boolean =
        date.isNullOrBlank() || date.trim().equals(DueParser.EMPTY, ignoreCase = true)

    /**
     * The moment the call says; null when the date is empty and no time was given either.
     * A date that was given and cannot be read is an error, never silently empty.
     */
    private fun due(args: JSONObject): Long? {
        val dateField = args.stringOrNull("date")
        val timeField = args.stringOrNull("time")
        if (isEmpty(dateField) && timeField.isNullOrBlank()) return null
        val day = if (isEmpty(dateField)) today()
            else DueParser.dayOf(dateField!!, today()) ?: throw IllegalArgumentException("日期读不出来（$dateField）")
        val parsed = DueParser.parse(day.toString(), timeField, today())
            ?: throw IllegalArgumentException("日期读不出来（$dateField）")
        return store.moment(parsed.date, parsed.hour, parsed.minute)
    }

    private fun whenLabel(due: Long?): String =
        due?.let { "，${DueParser.describe(it, today())}" }
            ?: "，日期 ${DueParser.EMPTY}：按明天提醒，并会请用户补全时间"

    private fun find(args: JSONObject): MemoStore.Memo? {
        val id = args.stringOrNull("id")?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("要填编号 id")
        return store.find(id) ?: throw IllegalArgumentException("没有编号 $id 的待办")
    }
}
