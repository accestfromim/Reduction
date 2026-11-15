package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.chat.model.ToolSpec
import org.json.JSONObject

class BriefTool(private val emitter: EventSink) : ChatTool {

    override val spec = ToolSpec(
        name = "update_brief",
        description = "把用户的想法整理成完整、精简、可执行的指令全文，并更新手机上的指令框。每次调用都传入更新后的完整版本，不要传增量。",
        parameterName = "brief",
        parameterDescription = "整理后的完整指令，包含目标、要求、约束和验收标准；保持精简"
    )

    override fun execute(argumentsJson: String): String {
        val brief = JSONObject(argumentsJson).stringOrNull("brief")?.trim().orEmpty()
        if (brief.isBlank()) return "指令整理失败：brief 为空"
        val bounded = brief.take(8_000)
        emitter.emit("onBriefUpdated", bounded)
        return "指令框已更新（${bounded.length} 字）"
    }
}
