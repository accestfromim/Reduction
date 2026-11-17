package com.mindtravel.reduction.feature.chat.model.tools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The record of sub agent runs as a card: for each run one line of what it was for and
 * one line of what came of it. The program writes it straight from [AgentRuns]; the
 * assistant has no hand in it and cannot change it.
 *
 * The whole of a run — the task as handed over, the result word for word — is kept by
 * [AgentRuns] and is not shown here.
 */
object AgentLedger {
    const val CARD = "sub agent 任务"
    const val PURPOSE = "程序直接写入的 sub agent 任务记录：每个任务一句目标、一句结果"

    fun render(runs: List<AgentRuns.Run>): String = buildString {
        appendLine("这张卡片由程序直接写入，助手不能修改。每个任务只记一句目标和一句结果。")
        if (runs.isEmpty()) {
            appendLine()
            appendLine("还没有派发过任务。")
        }
        for (run in runs) {
            appendLine()
            append("## 任务 ${run.number} · ${run.outcome.ifEmpty { "进行中" }} · ${clock(run.startedAt)}")
            if (run.finished) append(" · 用时 ${took(run.endedAt - run.startedAt)}")
            appendLine()
            appendLine()
            appendLine("- 目标：${line(run.task, GOAL_CHARS)}")
            appendLine("- 结果：" + if (!run.finished) "（还在做）" else line(run.result, RESULT_CHARS).ifEmpty { "（没有交回任何内容）" })
        }
    }.trimEnd() + "\n"

    /**
     * One line of [text]: its first line that says something, without the marks a line
     * of Markdown begins with, and no longer than [limit]. Taken from the text as it
     * stands — nothing is reworded.
     */
    fun line(text: String, limit: Int): String {
        val first = text.lineSequence()
            .map { it.replace("**", "").replace("`", "").trim().trimStart('#', '>', '-', '*', ' ', '\t').trim() }
            .firstOrNull { said -> said.any { it.isLetterOrDigit() } && said.trimEnd('：', ':') !in LABELS }
            .orEmpty()
        return if (first.length <= limit) first else first.take(limit - 1) + "…"
    }

    /** Lines that only announce what follows them. */
    private val LABELS = setOf("依据", "分析", "结果", "结论", "总结", "任务卡", "目标", "错误")

    private const val GOAL_CHARS = 60
    private const val RESULT_CHARS = 120

    private fun clock(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    private fun took(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return if (seconds < 90) "$seconds 秒" else "${seconds / 60} 分钟"
    }
}
