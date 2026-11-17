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
 * through here: add one, move its day, change it, finish it, drop it. A to-do is due on a
 * day, given as the date that was meant — "明天" is worked out from today's date first.
 */
class MemoTool(
    private val store: MemoStore,
    private val emitter: EventSink,
    private val today: () -> LocalDate = { store.today() }
) : ChatTool {

    override val spec = ToolSpec(
        name = "todo",
description = "待办与提醒的唯一入口。用户说的待办登记在这里；待办精确到天，可以再带一个时段（早上、上午、下午、晚上），没有几点几分。有日期的待办在当天早上提醒一次，过期未完成每天提醒一次，直到完成或改期。完成只移出提醒，记录保留。" +
            "日期用 yyyy-MM-dd，“明天”“后天”这类相对说法先按今天的日期算成具体日期再填。",
        parameters = listOf(
            ToolParam("action", "add：登记一条；update：改内容或日期；done：标记完成或重新打开；delete：删掉一条；list：看现在的待办", choices = listOf("add", "update", "done", "delete", "list")),
            ToolParam("id", "update、done、delete 时的编号，例如 3；list 时可不填", required = false),
            ToolParam("text", "add 时必填，update 时可填：要做的事，一句话。用户说了几点，就把几点写在这句话里", required = false),
            ToolParam("date", "add、update 时填：哪一天，yyyy-MM-dd。用户没说日期就不填", required = false),
            ToolParam("part", "add、update 时可填：一天里的时段。用户说了才填，没说就不填，表示当天；update 时填 none 是去掉时段", required = false, choices = listOf("早上", "上午", "下午", "晚上", "none")),
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
        val memo = store.add(text, due, part(args))
        return "记下了 #${memo.id}：${memo.text}" + whenLabel(memo)
    }

    private fun update(args: JSONObject): String {
        val memo = find(args) ?: throw IllegalArgumentException("没有这条待办")
        val text = args.stringOrNull("text")?.trim()?.takeIf { it.isNotEmpty() }
        val changeDue = !args.stringOrNull("date").isNullOrBlank()
        val changePart = !args.stringOrNull("part").isNullOrBlank()
        if (text == null && !changeDue && !changePart) throw IllegalArgumentException("update 要给出新的 text、date 或 part")
        val due = if (changeDue) due(args) else memo.due
        store.update(memo.id, text, due, changeDue, part(args), changePart)
        return "改好了 #${memo.id}：${memo.text}" + whenLabel(memo)
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
        fun line(memo: MemoStore.Memo): String =
            "- #${memo.id} " + memo.text + " · " + (memo.due?.let { DueParser.describe(it, memo.part, day) } ?: (memo.part?.label ?: "没有标记时间")) +
                (if (memo.done) " · 已完成" else if (store.isOverdue(memo)) " · 已过期" else "")
        return buildString {
            appendLine("今天：")
            if (view.today.isEmpty()) appendLine("（没有）") else view.today.forEach { appendLine(line(it)) }
            appendLine("未来：")
            if (view.future.isEmpty()) appendLine("（没有）") else view.future.forEach { appendLine(line(it)) }
            appendLine("没日期：")
            if (view.undated.isEmpty()) appendLine("（没有）") else view.undated.forEach { appendLine(line(it)) }
        }.trimEnd()
    }

    /**
     * The day the call says; null when the date was left unfilled. What an unfilled date
     * means — its name, and how it is reminded — is the program's business; the assistant
     * is told none of it. A date that was given and cannot be read is an error, never
     * silently empty.
     */
    private fun due(args: JSONObject): LocalDate? {
        val date = args.stringOrNull("date")?.trim()
        if (date.isNullOrEmpty() || date.equals(DueParser.EMPTY, ignoreCase = true)) return null
        return DueParser.dayOf(date, today()) ?: throw IllegalArgumentException("日期读不出来（$date）")
    }

    /** The part of the day the call names; null when none was said, or it was taken away. An unknown one is an error. */
    private fun part(args: JSONObject): MemoStore.Part? {
        val said = args.stringOrNull("part")?.trim()
        if (said.isNullOrEmpty() || said == "none") return null
        return MemoStore.Part.of(said) ?: throw IllegalArgumentException("时段只能是早上、上午、下午、晚上（$said）")
    }

    private fun whenLabel(memo: MemoStore.Memo): String =
        memo.due?.let { "，${DueParser.describe(it, memo.part, today())}" }
            ?: ("，没有标记时间" + (memo.part?.let { "，${it.label}" } ?: ""))

    private fun find(args: JSONObject): MemoStore.Memo? {
        val id = args.stringOrNull("id")?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("要填编号 id")
        return store.find(id) ?: throw IllegalArgumentException("没有编号 $id 的待办")
    }
}
