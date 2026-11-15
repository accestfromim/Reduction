package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.chat.model.ToolSpec
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcProfile
import org.json.JSONObject

class OpenCodeTool(
    private val pcGateway: PcGateway,
    private val profile: PcProfile,
    private val project: String,
    private val sessionId: String
) : ChatTool {

    override val spec = ToolSpec(
        name = "ask_opencode",
        description = "把问题交给电脑上的 opencode 只读分析代码/方案。不会修改文件。",
        parameterName = "prompt",
        parameterDescription = "要问 opencode 的问题，包含必要上下文"
    )

    override fun execute(argumentsJson: String): String {
        val prompt = JSONObject(argumentsJson).stringOrNull("prompt")?.trim().orEmpty()
        if (prompt.isBlank()) return "opencode 调用失败：prompt 为空"
        if (profile.host.isBlank() || profile.user.isBlank()) {
            return "opencode 调用失败：请先配置电脑 SSH"
        }
        if (project.isBlank()) return "opencode 调用失败：项目目录为空"
        if (sessionId.isNotBlank() && !sessionId.matches(SESSION_ID)) {
            return "opencode 调用失败：会话 ID 格式无效"
        }

        val command = "timeout 180 /home/user/.opencode/bin/opencode run " +
            "--format json --agent plan --auto --dir ${quote(project)}" +
            (if (sessionId.isBlank()) "" else " --session ${quote(sessionId)}") +
            " " + quote(prompt)
        val output = pcGateway.exec(profile, command)
        val text = extractText(output)
        return (text.ifBlank { output }).take(12_000)
    }

    private fun extractText(output: String): String = output.lineSequence()
        .mapNotNull { line ->
            runCatching {
                JSONObject(line).optJSONObject("part")
                    ?.takeIf { it.optString("type") == "text" }
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        .joinToString("\n")

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        val SESSION_ID = Regex("^[A-Za-z0-9_-]+$")
    }
}
