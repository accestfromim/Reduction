package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcProfile
import org.json.JSONObject
import java.util.Base64

/**
 * Runs sub agents on the computer, in the background. Every run goes through the
 * launcher script, which confines the agent's file system to one folder; inside that
 * folder it may do anything. A run can be asked how far it has got at any time.
 */
class AgentHarness(
    private val pcGateway: PcGateway,
    private val profile: PcProfile,
    /** The model key and name the agents run on: the same ones the phone itself uses. */
    private val apiKey: String,
    private val model: String,
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

        /** Claude Code, on the computer's own login. Given two hours. */
        CLAUDE("claude", 7_200)
    }

    enum class State { RUNNING, DONE, LOST }

    /** What a run has done so far. */
    class Progress(
        val state: State,
        val exitCode: Int?,
        /** Everything the agent has said, in order. */
        val said: String,
        /** One line per tool call the agent has made. */
        val steps: List<String>,
        /** Errors reported by the run itself, such as the model refusing the request. */
        val errors: List<String>,
        /** What the agent returned as its answer, when its engine says so separately from what it said along the way. */
        val returned: String = "",
        /** What the agent left in its result file. */
        val resultFile: String = ""
    ) {
        val succeeded: Boolean get() = state == State.DONE && exitCode == 0 && errors.isEmpty()

        /** The run was ended for taking too long. */
        val timedOut: Boolean get() = exitCode == TIMEOUT_EXIT

        /**
         * The result, as content only: what the process returned first, then the result file.
         * What was said along the way is the last resort, for engines that return nothing separately.
         */
        val content: String get() = returned.ifBlank { resultFile }.ifBlank { said }.trim()
    }

    val isConfigured: Boolean get() = profile.host.isNotBlank() && profile.user.isNotBlank()

    /** Starts a run and returns its id. Throws [IllegalStateException] with the launcher's message when it cannot start. */
    fun start(agent: Agent, folder: String, prompt: String, engine: Engine = Engine.OPENCODE): String {
        val output = launcher("start", agent.wire, folder, prompt, withModel = true, engine = engine).trim()
        val id = output.lineSequence().firstOrNull { it.startsWith("RUN ") }?.removePrefix("RUN ")?.trim()
        return id?.takeIf { it.isNotBlank() } ?: throw IllegalStateException(output.ifBlank { "harness 没有响应" })
    }

    fun progress(folder: String, id: String): Progress = parse(launcher("status", folder, id))

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

    private fun launcher(vararg args: String, withModel: Boolean = false, engine: Engine = Engine.OPENCODE): String {
        val environment = if (withModel) {
            "AGENT_KEY=${quote(apiKey)} AGENT_MODEL=${quote("deepseek/$model")} " +
                "AGENT_ENGINE=${engine.wire} AGENT_TIMEOUT=${engine.timeoutSeconds} "
        } else {
            ""
        }
        return runScript("sh -s --", launcherScript(), *args, environment = environment)
    }

    companion object {
        /** What the launcher's `timeout` exits with. */
        const val TIMEOUT_EXIT = 124
        private const val RESULT_FILE_MARK = "\n=====RESULT FILE=====\n"

        /** A path as the computer's shell should read it: "~/" at its start is the home directory there. */
        fun path(value: String): String = if (value.startsWith("~/")) "\"\$HOME\"/" + quote(value.removePrefix("~/")) else quote(value)

        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /** Reads the launcher's status output: a STATE line, then opencode's JSON events. */
        fun parse(output: String): Progress {
            val (events, resultFile) = output.split(RESULT_FILE_MARK, limit = 2).let { it[0] to it.getOrElse(1) { "" }.trim() }
            val lines = events.lines()
            var returned = ""
            val header = lines.firstOrNull { it.startsWith("STATE ") }?.removePrefix("STATE ")?.trim().orEmpty()
            val state = when {
                header.startsWith("running") -> State.RUNNING
                header.startsWith("done") -> State.DONE
                else -> State.LOST
            }
            val exitCode = header.removePrefix("done").trim().toIntOrNull()

            val said = ArrayList<String>()
            val steps = LinkedHashMap<String, String>()
            val errors = ArrayList<String>()
            for (line in lines) {
                val event = runCatching { JSONObject(line) }.getOrNull()
                if (event == null) {
                    // The launcher's own messages are plain text.
                    if (line.startsWith("harness：")) errors += line
                    continue
                }
                if (event.optString("type") == "error") {
                    val error = event.optJSONObject("error")
                    val message = error?.optJSONObject("data")?.optString("message").orEmpty()
                        .ifBlank { error?.optString("name").orEmpty() }
                    errors += message.ifBlank { "未知错误" }
                    continue
                }
                // Claude Code: each turn is a message of blocks, and the last event carries what it returns.
                when (event.optString("type")) {
                    "assistant" -> {
                        val blocks = event.optJSONObject("message")?.optJSONArray("content") ?: continue
                        for (i in 0 until blocks.length()) {
                            val block = blocks.optJSONObject(i) ?: continue
                            when (block.optString("type")) {
                                "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let { said += it }
                                "tool_use" -> {
                                    val input = block.optJSONObject("input")
                                    val what = input?.let { args ->
                                        args.optString("command").ifBlank { args.optString("file_path") }
                                            .ifBlank { args.optString("url") }.ifBlank { args.optString("pattern") }
                                            .ifBlank { args.toString() }
                                    }.orEmpty().replace('\n', ' ').take(120)
                                    steps[block.optString("id").ifBlank { "${steps.size}" }] = "${block.optString("name")}: $what"
                                }
                            }
                        }
                        continue
                    }
                    "result" -> {
                        returned = event.optString("result")
                        if (event.optBoolean("is_error")) errors += returned.ifBlank { event.optString("subtype") }.take(400)
                        continue
                    }
                }
                val part = event.optJSONObject("part") ?: continue
                when (part.optString("type")) {
                    "text" -> part.optString("text").takeIf { it.isNotBlank() }?.let { said += it }
                    "tool" -> {
                        val toolState = part.optJSONObject("state")
                        val input = toolState?.optJSONObject("input")
                        val what = input?.let { args ->
                            args.optString("command").ifBlank { args.optString("filePath") }
                                .ifBlank { args.optString("url") }.ifBlank { args.optString("pattern") }
                                .ifBlank { args.toString() }
                        }.orEmpty().replace('\n', ' ').take(120)
                        val key = part.optString("callID").ifBlank { part.optString("id") }.ifBlank { "${steps.size}" }
                        steps[key] = "${part.optString("tool")}: $what（${toolState?.optString("status").orEmpty()}）"
                    }
                }
            }
            return Progress(state, exitCode, said.joinToString("\n"), steps.values.toList(), errors, returned, resultFile)
        }
    }
}
