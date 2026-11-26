package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject

class RemoveSubAgentTool(
    private val harness: AgentHarness,
    private val runs: AgentRuns,
    private val cards: CardRepository
) : ChatTool {
    override val spec = ToolSpec("remove_sub_agent", "从总目录移除指定 sub agent，同时删除两端任务卡及提问绑定。运行中的进程可继续。",
        listOf(ToolParam("run", "要移除的任务编号")))

    override fun execute(argumentsJson: String): String {
        val number = JSONObject(argumentsJson).optString("run").toIntOrNull() ?: return "请填写任务编号"
        val run = runs.find(number) ?: return "任务 $number 已移除或不存在"
        if (run.card.isNotEmpty() && cards.isLocked(run.card)) return "请先解锁任务卡"
        if (run.host.isNotEmpty()) {
            harness.task(run, JSONObject().put("op", "remove"))
            cards.forgetTask(run)
        }
        runs.remove(number)
        return "已移除任务 $number"
    }
}
