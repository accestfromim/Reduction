package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.feature.chat.data.CardRepository
import java.util.UUID
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.settings.AgentDirectory
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import com.mindtravel.reduction.model.chat.ChatImage
import org.json.JSONObject

/**
 * The one way to dispatch a sub agent. A run happens in the background on the computer:
 * "start" hands over a task and returns at once, so the reply to the user is not held up;
 * "status" reports how far the runs have got, and what a finished one came to; "stop" ends one. Runs are numbered by [runs]; the assistant never has
 * to carry an identifier or a folder from one call to the next.
 */
class RunAgentTool(
    private val harness: AgentHarness,
    private val runs: AgentRuns,
    private val project: String,
    private val agentsRoot: String,
    private val allowWrite: Boolean,
    private val directories: List<AgentDirectory>,
    /** How long "start" lingers: only long enough to notice a run that dies at once. */
    private val waitMillis: Long = 2_500,
    private val pollMillis: Long = 500,
    private val cards: CardRepository? = null,
    private val answerQuestion: ((AgentRuns.Run, String) -> String)? = null
) : ChatTool {

    override val spec = ToolSpec(
        name = "run_agent",
        description = "派发 sub agent 并查询它们的进度。sub agent 在电脑上后台运行，按设置中各路径的权限访问文件。" +
            "start 交出任务卡后立刻返回任务编号，不等它做完；" +
            "status 查登记状态；record 读任务卡全文；send 向已有会话补充消息；stop 停止执行。",
        parameters = listOf(
            ToolParam("action", "要执行的操作", choices = listOf("start", "status", "send", "stop", "record")),
            ToolParam("text", "send 要送给执行器的消息", required = false),
            ToolParam("task", "start 时必填。任务卡：目标、约束、验收标准，以及希望交回什么", required = false),
            ToolParam(
                "folder",
                "start 时可选。工作目录：已授权目录的完整路径，或独立工作区的名称。project 指设置的项目路径，须事先授权。默认 research",
                required = false
            ),
            ToolParam(
                "access",
                "start 时可选。read：只读，用于分析代码和独立验收；write：可以修改文件、落盘保存。默认 read",
                required = false,
                choices = listOf("read", "write")
            ),
            ToolParam("run", "record 时必填；status 和 stop 时可选：任务编号，比如 2。不填时，status 报告所有没结束的任务，stop 结束唯一在跑的那个", required = false)
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
        return when (args.stringOrNull("action")?.trim()) {
            "start" -> {
                val folderName = args.stringOrNull("folder")?.trim().orEmpty().ifBlank { DEFAULT_FOLDER }
                val folder = folderPath(folderName) ?: return "工作目录未获授权或名称无效：$folderName"
                start(args, folderName, folder)
            }
            "status" -> status(args)
            "stop" -> stop(args)
            "record" -> record(args)
            "send" -> {
                val run = named(args).getOrElse { return it.message.orEmpty() } ?: return "send 要填任务编号 run"
                if (run.host.isEmpty()) return "此旧任务没有可续聊的会话通道"
                val text = args.optString("text").trim().ifEmpty { return "send 要填消息 text" }
                val reply = harness.task(run, JSONObject().put("op", "send").put("text", text)
                    .put("id", UUID.randomUUID().toString()))
                check(!reply.optBoolean("detached")) { "此任务已移除" }
                runs.phase(run, "running")
                val receipt = when (reply.optString("delivery")) {
                    "current" -> "已送入当前轮"
                    "next" -> "已送入原会话的新一轮"
                    else -> "已排队，等待原会话接收"
                }
                "任务 ${run.number}：$receipt"
            }
            else -> "未知的 action，只能是 start、status、stop 或 record"
        }
    }

    /**
     * Looks at every run not yet known to be over and returns the report of each one that
     * has ended, with its number. A run is reported this way once.
     */
    fun endedRuns(): List<Ended> {
        if (!harness.isConfigured) return emptyList()
        // Pictures of a run the assistant asked about itself: its answer then was words, the pictures follow now.
        val held = runs.takePictures().map { (number, report, images) -> Ended(number, report, images) }
        return held + runs.unfinished().mapNotNull { run ->
            if (run.host.isNotEmpty()) {
                if (run.host != harness.hostId) return@mapNotNull null
                if (run.phase == "idle") return@mapNotNull null
                return@mapNotNull try {
                    watchManaged(run)
                } catch (e: Exception) {
                    Logs.add("任务 ${run.number} 暂未收到状态：${e.message}")
                    null
                }
            }
            val progress = harness.progress(run.folder, run.launcherId)
            if (progress.state == AgentHarness.State.RUNNING) null else Ended(run.number, report(run, progress, attached = true), progress.images)
        }
    }

    private fun watchManaged(run: AgentRuns.Run): Ended? {
        val status = harness.task(run, JSONObject().put("op", "status"))
        if (status.optBoolean("detached")) {
            cards?.forgetTask(run)
            runs.remove(run.number)
            return null
        }
        val question = status.optJSONObject("question")
        if (question != null && run.card.isNotEmpty() && answerQuestion != null) {
            val id = question.getString("id")
            val text = if (run.replyId == id) run.reply else {
                val reply = answerQuestion.invoke(run, question.getString("text"))
                runs.reply(run, id, reply)
                reply
            }
            harness.task(run, JSONObject().put("op", "send").put("id", "reply:$id")
                .put("question", id).put("text", text))
            runs.reply(run, "", "")
            runs.phase(run, "running")
            return null
        }
        runs.phase(run, status.getString("status"))
        if (!run.finished) return null
        val pictures = if (run.outcome == AgentRuns.DONE) harness.pictures(run) else emptyList()
        return Ended(run.number, "任务 ${run.number} · ${run.outcome}。${status.optString("detail")} 任务卡：${run.card.ifEmpty { "已删除" }}", pictures)
    }

    /** A run that has ended: its number, its report in words, and the pictures it handed back with it. */
    data class Ended(val number: Int, val report: String, val images: List<ChatImage> = emptyList())

    /** The run a call is about: the one it names, or null when it names none. A wrong number is an error. */
    private fun named(args: JSONObject): Result<AgentRuns.Run?> {
        val raw = args.stringOrNull("run")?.trim().orEmpty().removePrefix("任务").trim()
        if (raw.isEmpty()) return Result.success(null)
        val run = raw.toIntOrNull()?.let(runs::find)
        return if (run != null) Result.success(run) else Result.failure(IllegalArgumentException("没有任务 $raw。${known()}"))
    }

    /** The whole of one run as it is kept: the task as handed over and what came back, word for word. */
    private fun record(args: JSONObject): String {
        val run = named(args).getOrElse { return it.message.orEmpty() } ?: return "record 要填任务编号 run"
        if (run.host.isNotEmpty()) return if (run.card.isEmpty()) "任务卡已删除" else
            cards?.read(run.card) ?: "任务卡已删除"
        return buildString {
            appendLine("任务 ${run.number} · ${run.outcome.ifEmpty { "进行中" }} · 文件夹 ${run.folderName}")
            appendLine()
            appendLine("【任务卡原文】")
            appendLine(run.task)
            appendLine()
            appendLine("【交回的结果原文】")
            appendLine(run.result.ifBlank { if (run.finished) "（没有交回任何内容）" else "（还在做）" })
            if (run.diff.isNotBlank()) {
                appendLine()
                appendLine("【改动的文件】")
                appendLine(run.diff)
            }
        }.trim()
    }

    private fun known(): String {
        val open = runs.unfinished()
        return if (open.isEmpty()) "现在没有在跑的任务。" else "在跑的任务：" + open.joinToString("；") { "任务 ${it.number}（${it.title}）" }
    }

    private fun status(args: JSONObject): String {
        val one = named(args).getOrElse { return it.message.orEmpty() }
        val asked = if (one != null) listOf(one) else runs.unfinished().ifEmpty { listOfNotNull(runs.latest()) }
        if (asked.isEmpty()) return "还没有派发过任务。"
        return asked.joinToString("\n\n") { run ->
            if (run.host.isEmpty()) report(run, harness.progress(run.folder, run.launcherId))
            else {
                val snapshot = harness.task(run, JSONObject().put("op", "status"))
                if (snapshot.optBoolean("detached")) "任务已移除" else {
                    runs.phase(run, if (snapshot.optJSONObject("question") != null) "waiting" else snapshot.getString("status"))
                    AgentLedger.render(listOf(run)).trim()
                }
            }
        }
    }

    private fun stop(args: JSONObject): String {
        val one = named(args).getOrElse { return it.message.orEmpty() }
        val open = runs.unfinished()
        val run = one ?: open.singleOrNull()
            ?: return if (open.isEmpty()) "现在没有在跑的任务。" else "有 ${open.size} 个任务在跑，请说明结束哪一个。${known()}"
        if (run.host.isNotEmpty() && run.host != harness.hostId) return "请连接此任务所在的服务器"
        harness.stop(run.folder, run.launcherId)
        if (run.host.isEmpty()) runs.end(run, AgentRuns.STOPPED) else runs.phase(run, "stopped")
        return "已结束任务 ${run.number}"
    }

    private fun start(args: JSONObject, folderName: String, folder: String): String {
        val task = args.stringOrNull("task")?.trim().orEmpty()
        if (task.isBlank()) return "派发失败：task 为空"
        val write = args.stringOrNull("access")?.trim() == "write"
        if (write && !allowWrite) return "派发失败：设置里没有允许 sub agent 修改文件"
        val onProject = folder == project && directories.any { it.path == project }

        val access = directories.joinToString("\n") {
            "- ${it.path}：${if (write && it.writable) "读写" else "只读"}"
        }.ifBlank { "没有授权的外部目录。" }
        val brief = if (write) {
            "你是执行任务的 sub agent。只在系统允许写入的路径工作。" +
                "交回结果时分两块：依据（只放原文、出处、链接、文件路径、实际 diff、命令输出）和分析（只放推断和判断）。" +
                "你只做执行、预筛和整理，不替人下结论。$PICTURES\n\n授权目录，子路径的规则优先：\n$access\n\n$task"
        } else {
            "你是只读的 sub agent，只能读取项目文件，可以更新自己的任务卡和交付目录。" +
                "交回结果时分两块：依据（只放原文、出处、文件路径和行号、命令输出）和分析（只放推断和判断）。" +
                "如果任务是验收：你不是执行者，逐条对照任务卡；第一行只写 PASS 或 FAIL，随后列直接证据，最后指出最值得人亲自看的一个判决点。$PICTURES\n\n授权目录，子路径的规则优先：\n$access\n\n$task"
        }

        if (harness.managed && cards != null) {
            require(task.length <= CardRepository.MAX_CHARS - 100) { "初始任务卡过长" }
            val body = "## 目标与约束\n\n$task\n\n## 当前进展\n\n## 双方往来\n"
            val instructions = """
                共享任务卡全文位于 ~/.reduction-task/task.md。阶段进展、证据和阻塞点直接更新这张卡。
                python3 ~/.reduction-task/card.py read 返回全文与 rev。把完整新正文通过标准输入交给
                python3 ~/.reduction-task/card.py save REV。版本冲突时重新读取，再应用你的修改。
                需要手机助手决策时用 MiMo Code 的 question、Codex 的 request_user_input 或 python3 ~/.reduction-task/card.py ask '问题'。
                文件式提问后可结束这一轮等原会话续聊。答复尚未确定时，继续能独立做的部分。
                确认完成整个任务时调用 python3 ~/.reduction-task/card.py done。普通一轮结束表示可续聊。
            """.trimIndent()
            val started = harness.startTask(if (write) AgentHarness.Agent.BUILD else AgentHarness.Agent.PLAN,
                folder, "$brief\n\n$instructions", body)
            val run = runs.add(started.id, folderName, folder, task.lineSequence().first(), write = write,
                changesProject = write && onProject, engine = harness.defaultEngine.wire, host = harness.hostId, remoteDir = started.directory)
            var name = "sub agent/任务 ${run.number}"
            while (cards.hasCached(name)) name += "·"
            cards.registerTask(name, body)
            runs.card(run, name)
            // Fetch its first server timestamp. A temporary disconnection leaves the binding intact.
            runCatching { cards?.read(name) }
            return "【已派发】任务 ${run.number}：${run.title}。任务卡「$name」"
        }
        val id = try {
            harness.start(if (write) AgentHarness.Agent.BUILD else AgentHarness.Agent.PLAN, folder, brief)
        } catch (t: IllegalStateException) {
            return "派发失败：${t.message}"
        }
        val run = runs.add(id, folderName, folder, task, write = write, changesProject = write && onProject)
        Logs.add("Agent run ${run.number} started id=$id folder=$folderName write=$write")

        val deadline = System.currentTimeMillis() + waitMillis
        var progress = harness.progress(folder, id)
        while (progress.state == AgentHarness.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMillis)
            progress = harness.progress(folder, id)
        }
        if (progress.state != AgentHarness.State.RUNNING) return report(run, progress)
        return "【已派发】任务 ${run.number}：${run.title}\n它在电脑上后台运行。现在就回复用户，不要等它；之后用 action=status 查进度和结果。"
    }

    /**
     * What a run has come to, in words. [attached] says the pictures travel with these words,
     * as they do when the watch delivers them; a tool's answer cannot carry pictures, so
     * there they are held back and follow in a message of their own.
     */
    private fun report(run: AgentRuns.Run, progress: AgentHarness.Progress, attached: Boolean = false): String = buildString {
        val wasOpen = !run.finished
        val name = "任务 ${run.number}"
        when (progress.state) {
            AgentHarness.State.RUNNING -> {
                appendLine("【进行中】$name：${run.title}")
            }
            AgentHarness.State.DONE -> {
                runs.end(run, if (progress.succeeded) AgentRuns.DONE else AgentRuns.FAILED, progress.resultFile)
                appendLine(if (progress.succeeded) "【完成】$name" else "【失败】$name，退出码 ${progress.exitCode}")
                progress.errors.forEach { appendLine("错误：$it") }
                if (progress.resultFile.isNotBlank()) appendLine(progress.resultFile)
                if (progress.images.isNotEmpty()) {
                    appendLine()
                    val names = progress.images.joinToString("、") { it.name }
                    if (attached) {
                        appendLine("附图 ${progress.images.size} 张，图片本身就在这条消息里：$names")
                    } else {
                        appendLine("附图 ${progress.images.size} 张（$names）：这次查询的回答里带不了图，图片随后会单独发给你，那时再看图说话。")
                        if (wasOpen) {
                            runs.holdPictures(
                                run.number,
                                "任务 ${run.number} 的附图 ${progress.images.size} 张，图片本身就在这条消息里：$names",
                                progress.images
                            )
                        }
                    }
                }
                if (progress.skippedImages.isNotEmpty()) {
                    appendLine("没有带回来的图（太多或太大）：" + progress.skippedImages.joinToString("、"))
                }
            }
            AgentHarness.State.LOST -> {
                runs.end(run, AgentRuns.LOST, progress.errors.joinToString("\n") { "错误：$it" })
                appendLine("【找不到】$name 已经不在电脑上，可能意外中断了。")
                progress.errors.forEach { appendLine("错误：$it") }
            }
        }
    }.trim()

    private fun folderPath(name: String): String? = when {
        name == PROJECT -> project.takeIf { directory -> directories.any { it.path == directory } }
        directories.any { it.path == name } -> name
        FOLDER_NAME.matches(name) -> "$agentsRoot/$name"
        else -> null
    }

    private companion object {
        const val PROJECT = "project"
        const val DEFAULT_FOLDER = "research"

        /** How a sub agent hands back pictures to be looked at. */
        const val PICTURES = "需要让对方亲眼看的图（图表、截图、生成的图片），把图片文件放进 ${AgentHarness.RESULT_FOLDER}/，" +
            "png、jpg、webp 或 gif，文件名只用字母、数字、点、横线、下划线，最多 ${AgentHarness.MAX_IMAGES} 张、每张不超过 3 MB；" +
            "对方收到的是图片本身。这个文件夹任何时候都可写。"
        val FOLDER_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")
    }
}
