package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.settings.AgentDirectory
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.hardware.pc.PcProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Runs sub agents on the computer, in the background. Every run goes through the
 * launcher script, which validates the selected workspace.
 * A run can be asked how far it has got at any time.
 */
class AgentHarness(
    private val pcGateway: PcGateway,
    private val profile: PcProfile,
    /** OpenCode 使用 Reduction 的 DeepSeek 设置。 */
    private val apiKey: String,
    private val model: String,
    private val directories: List<AgentDirectory> = emptyList(),
    private val macLauncherScript: (() -> String)? = null,
    private val taskDriverScript: (() -> String)? = null,
    val defaultEngine: Engine = Engine.OPENCODE,
    private val launcherScript: () -> String
) {
    enum class Agent(val wire: String) {
        /** May change files. */
        BUILD("build"),

        /** Reads only. */
        PLAN("plan")
    }

    /** Who does the work. */
    enum class Engine(val wire: String, val timeoutSeconds: Int) {
        OPENCODE("opencode", 900),
        CODEX("codex", 900),
        MIMOCODE("mimocode", 900),

        /** Claude Code, on the computer's own login. Given two hours. */
        CLAUDE("claude", 7_200)
    }

    enum class State { RUNNING, DONE, LOST }

    /** What a run has done so far. */
    class Progress(
        val state: State,
        val exitCode: Int?,
        val errors: List<String>,
        /** What the agent left in its result file. */
        val resultFile: String = "",
        /** The pictures the agent left beside its result file, to be looked at. */
        val images: List<ChatImage> = emptyList(),
        /** Pictures it left that were not brought back: too many, or too large. */
        val skippedImages: List<String> = emptyList()
    ) {
        val succeeded: Boolean get() = state == State.DONE && exitCode == 0 && errors.isEmpty()

    }

    val isConfigured: Boolean get() = profile.host.isNotBlank() && profile.user.isNotBlank()

    /** Starts a run and returns its id. Throws [IllegalStateException] with the launcher's message when it cannot start. */
    fun start(agent: Agent, folder: String, prompt: String, engine: Engine = defaultEngine): String {
        val output = launcher("start", agent.wire, folder, prompt, engine = engine).trim()
        val id = output.lineSequence().firstOrNull { it.startsWith("RUN ") }?.removePrefix("RUN ")?.trim()
        return id?.takeIf { it.isNotBlank() } ?: throw IllegalStateException(output.ifBlank { "harness 没有响应" })
    }

    val hostId: String get() = "${profile.user}@${profile.host}:${profile.port}"
    val managed: Boolean get() = taskDriverScript != null

    data class Started(val id: String, val directory: String)

    fun startTask(agent: Agent, folder: String, prompt: String, body: String): Started {
        val input = JSONObject().put("prompt", prompt).put("body", body).toString().toByteArray(Charsets.UTF_8)
        val output = launcher("start", agent.wire, folder, "--task-stdin", engine = defaultEngine, payload = input)
        val id = output.lineSequence().firstOrNull { it.startsWith("RUN ") }?.removePrefix("RUN ")?.trim()
            ?: throw IllegalStateException(output.trim().ifEmpty { "服务器未返回任务编号" })
        require(Regex("[A-Za-z0-9-]{1,80}").matches(id)) { "无效的任务编号" }
        val directory = output.lineSequence().firstOrNull { it.startsWith("TASK_PATH ") }?.removePrefix("TASK_PATH ").orEmpty()
        require(directory.startsWith("/") && directory.endsWith("/runs/$id")) { "服务器未返回任务目录" }
        return Started(id, directory)
    }

    fun task(run: AgentRuns.Run, request: JSONObject): JSONObject {
        check(run.host == hostId) { "任务属于 ${run.host}，当前连接是 $hostId" }
        val input = request.toString().toByteArray(Charsets.UTF_8)
        val out = if (run.remoteDir.isEmpty()) {
            runScript("sh -s --", launcherScript(), "task", run.folder, run.launcherId, input = input)
        } else {
            val driver = quote(run.remoteDir + "/bridge.py")
            val missing = if (request.optString("op") == "remove") "{\"detached\":true}" else "{\"error\":\"找不到服务器任务\"}"
            pcGateway.send(profile, "if [ -f $driver ]; then python3 $driver rpc ${quote(run.remoteDir)}; else echo ${quote(missing)}; fi", input)
        }
        val answer = runCatching { JSONObject(out.trim()) }.getOrNull()
            ?: error("任务服务未响应：${out.take(180)}")
        check(!answer.has("error")) { answer.optString("error") }
        return answer
    }

    fun progress(folder: String, id: String): Progress = parse(launcher("status", folder, id))

    fun pictures(run: AgentRuns.Run): List<ChatImage> = parse(launcher("pictures", run.folder, run.launcherId)).images

    fun stop(folder: String, id: String) {
        launcher("stop", folder, id)
    }

    /** Runs a plain command on the computer, outside any agent. */
    fun exec(command: String): String = pcGateway.exec(profile, command)

    /** Runs [script] on the computer through [interpreter], which reads it from standard input. */
    fun runScript(interpreter: String, script: String, vararg args: String, environment: String = ""): String {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val command = "echo $encoded | base64 -d | $environment$interpreter " + args.joinToString(" ") { quote(it) }
        return pcGateway.exec(profile, command)
    }

    /**
     * Runs [script] on the computer with [input] as what it reads. The script is written to a file
     * of its own first, since its standard input is taken by [input].
     */
    fun runScript(interpreter: String, script: String, vararg args: String, input: ByteArray, environment: String = ""): String {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val command = "f=\$(mktemp) && echo $encoded | base64 -d > \"\$f\" && " +
            "$environment${interpreter.replace(" -s --", "")} \"\$f\" " + args.joinToString(" ") { quote(it) } + "; s=\$?; rm -f \"\$f\"; exit \$s"
        return pcGateway.send(profile, command, input)
    }

    private fun launcher(vararg args: String, engine: Engine? = null, payload: ByteArray? = null): String {
        val environment = buildString {
            if (engine != null) {
                val rules = JSONArray(directories.map { it.toJson() }).toString()
                append("AGENT_DIRECTORIES=${quote(rules)} AGENT_ENGINE=${engine.wire} AGENT_TIMEOUT=${engine.timeoutSeconds} ")
            }
            if (engine == Engine.OPENCODE) {
                append("AGENT_KEY=${quote(apiKey)} AGENT_MODEL=${quote("deepseek/$model")} ")
            }
        }
        val driver = if (payload != null) taskDriverScript?.invoke() else null
        val shell = if (driver == null) launcherScript() else
            "export REDUCTION_TASK_DRIVER=" + quote(Base64.getEncoder().encodeToString(driver.toByteArray())) + "\n" + launcherScript()
        val mac = if (engine != null) macLauncherScript?.invoke() else null
        val script = if (mac.isNullOrEmpty()) shell else {
            val encoded = Base64.getEncoder().encodeToString(mac.toByteArray(Charsets.UTF_8))
            "REDUCTION_MAC_AGENT_SCRIPT=${quote(encoded)}\n$shell"
        }
        return if (payload == null) runScript("sh -s --", script, *args, environment = environment)
        else runScript("sh -s --", script, *args, input = payload, environment = environment)
    }

    companion object {
        private const val RESULT_FILE_MARK = "\n=====RESULT FILE=====\n"

        /** "=====IMAGE name type=====" before a picture in base64; "=====IMAGE SKIPPED name=====" for one left behind. */
        private val IMAGE_MARK = Regex("\\n=====IMAGE (SKIPPED )?([A-Za-z0-9._-]+)(?: (image/[a-z]+))?=====\\n")
        const val MAX_IMAGES = 4

        /** Where a run leaves its result and its pictures, as the agent sees it. */
        const val RESULT_FOLDER = "\$REDUCTION_RESULT_DIR"

        /** A path as the computer's shell should read it: "~/" at its start is the home directory there. */
        fun path(value: String): String = if (value.startsWith("~/")) "\"\$HOME\"/" + quote(value.removePrefix("~/")) else quote(value)

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /** 读取生命周期、显式交付的结果文件和附件。 */
        fun parse(output: String): Progress {
            // Pictures come last, each under a line of its own; what is before them is as it always was.
            val images = ArrayList<ChatImage>()
            val skipped = ArrayList<String>()
            val marks = IMAGE_MARK.findAll(output).toList()
            for ((index, mark) in marks.withIndex()) {
                val name = mark.groupValues[2]
                if (mark.groupValues[1].isNotEmpty()) { skipped += name; continue }
                val end = marks.getOrNull(index + 1)?.range?.first ?: output.length
                val data = output.substring(mark.range.last + 1, end).filterNot { it.isWhitespace() }
                if (data.isNotEmpty() && images.size < MAX_IMAGES) images += ChatImage(name, mark.groupValues[3], data) else skipped += name
            }
            val text = if (marks.isEmpty()) output else output.substring(0, marks.first().range.first)
            val (events, resultFile) = text.split(RESULT_FILE_MARK, limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() }
            val lines = events.lines()
            val header = lines.firstOrNull { it.startsWith("STATE ") }?.removePrefix("STATE ")?.trim().orEmpty()
            val state = when {
                header.startsWith("running") -> State.RUNNING
                header.startsWith("done") -> State.DONE
                else -> State.LOST
            }
            val exitCode = header.removePrefix("done").trim().toIntOrNull()

            val errors = lines.filter { it.startsWith("harness：") }
            return Progress(state, exitCode, errors, resultFile, images, skipped)
        }
    }
}
