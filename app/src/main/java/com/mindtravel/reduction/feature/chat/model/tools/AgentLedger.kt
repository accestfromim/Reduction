package com.mindtravel.reduction.feature.chat.model.tools

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The record of sub agent runs as a card, in one fixed layout. The program writes it
 * straight from [AgentRuns]: the task as handed over, the times, the state, and what
 * came back, all word for word. The assistant has no hand in it and cannot change it,
 * so what the card says is what happened.
 */
object AgentLedger {
    const val CARD = "sub agent 任务"
    const val PURPOSE = "程序直接写入的 sub agent 任务记录：任务卡、状态、时间、交回的结果原文"

    /** How many runs the card shows, newest first. */
    private const val SHOWN = 8

    fun render(runs: List<AgentRuns.Run>): String = buildString {
        appendLine("这张卡片由程序直接写入，助手不能修改。任务卡和结果都是原文。")
        if (runs.isEmpty()) {
            appendLine()
            appendLine("还没有派发过任务。")
        }
        for (run in runs.take(SHOWN)) {
            appendLine()
            appendLine("## 任务 ${run.number} · ${run.outcome.ifEmpty { "进行中" }}")
            appendLine()
            appendLine("- 派发：${clock(run.startedAt)}")
            if (run.finished) appendLine("- 结束：${clock(run.endedAt)}（用时 ${took(run.endedAt - run.startedAt)}）")
            appendLine("- 文件夹：${run.folderName} · ${if (run.write) "可写" else "只读"}")
            appendLine()
            appendLine("### 任务卡")
            appendLine()
            appendLine(quoted(run.task))
            appendLine()
            appendLine("### 交回的结果")
            appendLine()
            appendLine(if (run.result.isBlank()) (if (run.finished) "（没有交回任何内容）" else "（还在做）") else quoted(run.result))
            if (run.diff.isNotBlank()) {
                appendLine()
                appendLine("### 改动的文件")
                appendLine()
                appendLine("```")
                appendLine(run.diff)
                appendLine("```")
            }
        }
    }.trimEnd() + "\n"

    /** Marks every line as quoted, so that nothing in it can pass for the card's own headings. */
    private fun quoted(text: String): String = text.trim().lines().joinToString("\n") { "> $it".trimEnd() }

    private fun clock(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    private fun took(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return if (seconds < 90) "$seconds 秒" else "${seconds / 60} 分钟"
    }
}
