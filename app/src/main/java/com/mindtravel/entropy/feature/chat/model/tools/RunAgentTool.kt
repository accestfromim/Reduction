package com.mindtravel.entropy.feature.chat.model.tools

import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.core.host.ToolApprovalHost
import com.mindtravel.entropy.core.stringOrNull
import com.mindtravel.entropy.model.chat.ToolParam
import com.mindtravel.entropy.model.chat.ToolSpec
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one way to dispatch a sub agent. A run happens in the background on the computer:
 * "start" hands over a task and waits a short while for it, "status" reports how far a
 * run has got, "stop" ends it.
 */
class RunAgentTool(
    private val harness: AgentHarness,
    private val project: String,
    private val agentsRoot: String,
    private val allowWrite: Boolean,
    private val approvalHost: ToolApprovalHost,
    private val waitMillis: Long = 45_000,
    private val pollMillis: Long = 3_000
) : ChatTool {

    override val spec = ToolSpec(
        name = "run_agent",
        description = "派发 sub agent 并查询它的进度。sub agent 在电脑上后台运行，能力不设限，但只能写它所属的文件夹。" +
            "start 交出任务卡并等一小会儿：做完就返回结果，没做完就返回运行编号和已有进度；" +
            "status 查询某次运行做到哪了；stop 结束它。",
        parameters = listOf(
            ToolParam("action", "start：派发新任务；status：查询进度；stop：结束运行", choices = listOf("start", "status", "stop")),
            ToolParam("task", "start 时必填。任务卡：目标、约束、验收标准，以及希望交回什么", required = false),
            ToolParam(
                "folder",
                "sub agent 所属的文件夹。project 表示项目代码目录；其它值是它自己的独立文件夹名，比如 research。" +
                    "status 和 stop 要填与 start 时相同的值。默认 research",
                required = false
            ),
            ToolParam(
                "access",
                "start 时可选。read：只读，用于分析代码和独立验收；write：可以修改文件、落盘保存。默认 read",
                required = false,
                choices = listOf("read", "write")
            ),
            ToolParam("id", "status 和 stop 时必填：start 返回的运行编号", required = false)
        )
    )

    override fun describe(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        return when (args.stringOrNull("action")?.trim()) {
            "start" -> "派发：" + args.stringOrNull("task").orEmpty().trim().lineSequence().firstOrNull().orEmpty()
            "status" -> "查询 sub agent 进度"
            "stop" -> "结束 sub agent 运行"
            else -> spec.name
        }
    }

    /** Handing a task to a sub agent is the costly call: the user hears that it happened. */
    override fun announces(argumentsJson: String): Boolean =
        JSONObject(argumentsJson).stringOrNull("action")?.trim() == "start"

    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        if (!harness.isConfigured) return "派发失败：请先配置电脑 SSH"
        val folderName = args.stringOrNull("folder")?.trim().orEmpty().ifBlank { DEFAULT_FOLDER }
        val folder = folderPath(folderName) ?: return "文件夹名无效：$folderName（只能用字母、数字、点、横线、下划线）"

        return when (args.stringOrNull("action")?.trim()) {
            "start" -> start(args, folderName, folder)
            "status" -> {
                val id = args.stringOrNull("id")?.trim().orEmpty()
                if (id.isBlank()) "查询失败：缺少运行编号 id" else report(folderName, id, harness.progress(folder, id))
            }
            "stop" -> {
                val id = args.stringOrNull("id")?.trim().orEmpty()
                if (id.isBlank()) return "结束失败：缺少运行编号 id"
                harness.stop(folder, id)
                "已结束运行 $id"
            }
            else -> "未知的 action，只能是 start、status 或 stop"
        }
    }

    private fun start(args: JSONObject, folderName: String, folder: String): String {
        val task = args.stringOrNull("task")?.trim().orEmpty()
        if (task.isBlank()) return "派发失败：task 为空"
        val write = args.stringOrNull("access")?.trim() == "write"
        if (write && !allowWrite) return "派发失败：设置里没有允许 sub agent 修改文件"
        val onProject = folderName == PROJECT
        // Changing the project is the one thing the phone is asked about first.
        if (write && onProject && !approve(task)) return "已取消：用户拒绝执行"

        val brief = if (write) {
            "你是执行任务的 sub agent。当前目录是你唯一能写的文件夹。" +
                "交回结果时分两块：依据（只放原文、出处、链接、文件路径、实际 diff、命令输出）和分析（只放推断和判断）。" +
                "你只做执行、预筛和整理，不替人下结论。\n\n$task"
        } else {
            "你是只读的 sub agent，禁止修改任何文件。" +
                "交回结果时分两块：依据（只放原文、出处、文件路径和行号、命令输出）和分析（只放推断和判断）。" +
                "如果任务是验收：你不是执行者，逐条对照任务卡；第一行只写 PASS 或 FAIL，随后列直接证据，最后指出最值得人亲自看的一个判决点。\n\n$task"
        }

        val id = try {
            harness.start(if (write) AgentHarness.Agent.BUILD else AgentHarness.Agent.PLAN, folder, brief)
        } catch (t: IllegalStateException) {
            return "派发失败：${t.message}"
        }
        Logs.add("Agent run started id=$id folder=$folderName write=$write")

        val deadline = System.currentTimeMillis() + waitMillis
        var progress = harness.progress(folder, id)
        while (progress.state == AgentHarness.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMillis)
            progress = harness.progress(folder, id)
        }
        val text = report(folderName, id, progress)
        if (!(write && onProject && progress.state == AgentHarness.State.DONE)) return text
        val diff = runCatching { harness.exec("git -C ${AgentHarness.quote(folder)} diff --stat") }
            .getOrDefault("").trim()
        return if (diff.isBlank()) text else "$text\n\ngit diff --stat:\n${diff.take(4_000)}"
    }

    private fun report(folderName: String, id: String, progress: AgentHarness.Progress): String = buildString {
        when (progress.state) {
            AgentHarness.State.RUNNING -> {
                appendLine("【进行中】运行编号 $id，文件夹 $folderName。稍后用 action=status 再查。")
                appendLine("已执行 ${progress.steps.size} 步：")
                progress.steps.takeLast(8).forEach { appendLine("- $it") }
                if (progress.said.isNotBlank()) {
                    appendLine("目前说到：")
                    appendLine(progress.said.takeLast(1_500))
                }
            }
            AgentHarness.State.DONE -> {
                appendLine(if (progress.succeeded) "【完成】运行编号 $id" else "【失败】运行编号 $id，退出码 ${progress.exitCode}")
                progress.errors.forEach { appendLine("错误：$it") }
                if (progress.said.isNotBlank()) appendLine(progress.said.take(12_000))
                if (progress.said.isBlank() && progress.errors.isEmpty()) appendLine("sub agent 没有留下任何输出。")
            }
            AgentHarness.State.LOST -> {
                appendLine("【找不到】运行编号 $id 不存在，或者它已经意外中断。")
                progress.errors.forEach { appendLine("错误：$it") }
            }
        }
    }.trim()

    private fun folderPath(name: String): String? = when {
        name == PROJECT -> project
        FOLDER_NAME.matches(name) -> "$agentsRoot/$name"
        else -> null
    }

    private fun approve(task: String): Boolean {
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        approvalHost.requestToolApproval("sub agent 修改项目代码", "项目：$project\n\n$task") { allowed ->
            result.set(allowed)
            latch.countDown()
        }
        return runCatching { latch.await(5, TimeUnit.MINUTES) && result.get() }.getOrDefault(false)
    }

    private companion object {
        const val PROJECT = "project"
        const val DEFAULT_FOLDER = "research"
        val FOLDER_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")
    }
}
