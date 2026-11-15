package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.chat.model.ToolSpec
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcProfile
import org.json.JSONObject

class OpenCodeVerifyTool(
    private val pcGateway: PcGateway,
    private val profile: PcProfile,
    private val project: String
) : ChatTool {

    override val spec = ToolSpec(
        name = "verify_opencode",
        description = "独立验收 sub agent 的成果。只读检查任务卡、diff 和验证结果；不修改文件，不执行写操作。输出 PASS 或 FAIL。",
        parameterName = "prompt",
        parameterDescription = "任务卡全文 + 执行结果/diff，要求核验目标、约束、验收标准"
    )

    override fun execute(argumentsJson: String): String {
        val prompt = JSONObject(argumentsJson).stringOrNull("prompt")?.trim().orEmpty()
        if (prompt.isBlank()) return "opencode 验收失败：prompt 为空"
        if (profile.host.isBlank() || profile.user.isBlank()) {
            return "opencode 验收失败：请先配置电脑 SSH"
        }
        if (project.isBlank()) return "opencode 验收失败：项目目录为空"

        val command = "timeout 300 /home/user/.opencode/bin/opencode run " +
            "--format json --agent plan --auto --dir ${quote(project)} " +
            quote(
                "你是独立验收 agent，不是执行者。只读检查，禁止修改文件。" +
                    "逐条对照任务卡的目标、约束、验收标准；检查实际 diff 与验证命令结果。" +
                    "输出第一行只能是 PASS 或 FAIL，后续最多三条原因。\n\n$prompt"
            )
        val output = pcGateway.exec(profile, command)
        val text = extractText(output)
        return (text.ifBlank { output }).take(8_000)
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
}
