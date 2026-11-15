package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.chat.model.ToolSpec
import org.json.JSONObject

class BriefTool(private val emitter: EventSink) : ChatTool {

    override val spec = ToolSpec(
        name = "update_brief",
        description = "更新手机上的指令卡。每次传入完整、精简、分段的版本并整份覆盖，不要传增量；聊天里不要复述卡片全文。",
        parameterName = "brief",
        parameterDescription = "完整任务卡：目标、约束、验收标准，必要时补要求；保持精简、分段、可整段复制"
    )

    override fun execute(argumentsJson: String): String {
        val brief = JSONObject(argumentsJson).stringOrNull("brief")?.trim().orEmpty()
        if (brief.isBlank()) return "指令整理失败：brief 为空"
        val bounded = brief.take(8_000)
        emitter.emit("onBriefUpdated", bounded)
        return "指令框已更新（${bounded.length} 字）"
    }
}
