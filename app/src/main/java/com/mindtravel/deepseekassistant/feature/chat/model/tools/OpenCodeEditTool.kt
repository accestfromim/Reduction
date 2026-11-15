package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.host.ToolApprovalHost
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.chat.model.ToolSpec
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcProfile
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenCodeEditTool(
    private val pcGateway: PcGateway,
    private val profile: PcProfile,
    private val project: String,
    private val sessionId: String,
    private val approvalHost: ToolApprovalHost
) : ChatTool {

    override val spec = ToolSpec(
        name = "edit_opencode",
        description = "在项目 worktree 里让 opencode 修改代码。高风险，每次执行前需要手机确认；不要用于项目外目录。",
        parameterName = "prompt",
        parameterDescription = "要 opencode 完成的代码修改，包含目标、约束和验证要求"
    )

    override fun execute(argumentsJson: String): String {
        val prompt = JSONObject(argumentsJson).stringOrNull("prompt")?.trim().orEmpty()
        if (prompt.isBlank()) return "opencode 修改失败：prompt 为空"
        if (profile.host.isBlank() || profile.user.isBlank()) {
            return "opencode 修改失败：请先配置电脑 SSH"
        }
        if (!project.startsWith("/home/user/")) {
            return "opencode 修改失败：项目目录不在允许范围"
        }
        if (sessionId.isNotBlank() && !sessionId.matches(SESSION_ID)) {
            return "opencode 修改失败：会话 ID 格式无效"
        }
        if (!approve(prompt)) {
            return "opencode 修改已取消：用户拒绝执行"
        }

        val command = "timeout 600 /home/user/.opencode/bin/opencode run " +
            "--format json --agent build --auto --dir ${quote(project)}" +
            sessionArg() + " " + quote(prompt)
        val output = pcGateway.exec(profile, command)
        val text = extractText(output)
        val diff = runCatching { pcGateway.exec(profile, "git -C ${quote(project)} diff --stat") }
            .getOrDefault("")
            .trim()
        return buildString {
            appendLine(text.ifBlank { output }.take(10_000))
            if (diff.isNotBlank()) {
                appendLine()
                appendLine("git diff --stat:")
                appendLine(diff.take(4_000))
            }
        }.trim().take(14_000)
    }

    private fun approve(prompt: String): Boolean {
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        approvalHost.requestToolApproval(
            "opencode 修改代码",
            "项目：$project\n\n$prompt",
        ) { allowed ->
            result.set(allowed)
            latch.countDown()
        }
        return runCatching {
            latch.await(5, TimeUnit.MINUTES) && result.get()
        }.getOrDefault(false)
    }

    private fun sessionArg(): String =
        if (sessionId.isBlank()) "" else " --session ${quote(sessionId)}"

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
