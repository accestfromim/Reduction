package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject

/**
 * How the coding-agent sessions on the computer are doing. A script on the computer
 * reads the session records and hands back a status summary; the records themselves
 * stay there.
 */
class SessionStatusTool(
    private val harness: AgentHarness,
    private val script: () -> String
) : ChatTool {

    override val spec = ToolSpec(
        name = "session_status",
        description = "查看电脑上 Claude、codex、opencode 这几类编程会话各自的状态：最后活动时间、正在做什么、是否卡住或在等用户，" +
            "以及最后一句话的开头。返回的是电脑上脚本提取的摘要，不是会话原文。",
        parameters = listOf(
            ToolParam("hours", "只看最近多少小时内有活动的会话，默认 24", required = false),
            ToolParam("host", "windows 查 Windows 用户目录，local 查运行脚本的系统", required = false)
        )
    )

    override fun describe(argumentsJson: String): String = "查看电脑上的会话状态"

    override fun execute(argumentsJson: String): String {
        if (!harness.isConfigured) return "查询失败：请先配置电脑 SSH"
        val args = JSONObject(argumentsJson)
        val host = args.optString("host").ifBlank {
            if (args.optString("summary").contains("Windows", ignoreCase = true)) "windows" else "local"
        }
        if (host !in setOf("windows", "local")) return "查询失败：host 仅支持 windows 或 local"
        val hours = args.optString("hours").trim().toDoubleOrNull()
            ?.coerceIn(0.5, 720.0) ?: DEFAULT_HOURS
        val output = harness.runScript("python3 -", script(), "--hours", hours.toString(), "--limit", LIMIT.toString(), "--host", host)
        return output.trim().ifBlank { "脚本没有输出。电脑上需要有 python3。" }
    }

    private companion object {
        const val DEFAULT_HOURS = 24.0
        const val LIMIT = 8
    }
}
