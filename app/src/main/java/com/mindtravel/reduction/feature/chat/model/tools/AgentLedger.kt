package com.mindtravel.reduction.feature.chat.model.tools

/** One directory entry per registered session. Bodies and messages live in ordinary task cards. */
object AgentLedger {
    const val CARD = "sub agent 任务"
    const val PURPOSE = "sub agent 总目录，记录编号、名称、执行器、状态和任务卡名称"

    fun render(runs: List<AgentRuns.Run>): String = if (runs.isEmpty()) "还没有派发过任务。\n" else
        runs.joinToString("\n", postfix = "\n") { run ->
            val state = run.outcome.ifEmpty { when (run.phase) { "idle" -> "可续聊"; "waiting" -> "待答"; else -> "进行中" } }
            val link = if (run.card.isEmpty()) "" else {
                val encoded = java.net.URLEncoder.encode(run.card, "UTF-8").replace("+", "%20")
                " · 卡片「${run.card}」 [打开](#card=$encoded)"
            }
            "- 任务 ${run.number} · ${run.title} · ${run.engine} · $state$link"
        }
}
