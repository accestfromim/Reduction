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
        val errors: List<String>
    ) {
        val succeeded: Boolean get() = state == State.DONE && exitCode == 0 && errors.isEmpty()
    }

    val isConfigured: Boolean get() = profile.host.isNotBlank() && profile.user.isNotBlank()

    /** Starts a run and returns its id. Throws [IllegalStateException] with the launcher's message when it cannot start. */
    fun start(agent: Agent, folder: String, prompt: String): String {
        val output = launcher("start", agent.wire, folder, prompt, withModel = true).trim()
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

    private fun launcher(vararg args: String, withModel: Boolean = false): String {
        val environment = if (withModel) {
            "AGENT_KEY=${quote(apiKey)} AGENT_MODEL=${quote("deepseek/$model")} "
        } else {
            ""
        }
        return runScript("sh -s --", launcherScript(), *args, environment = environment)
    }

    companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /** Reads the launcher's status output: a STATE line, then opencode's JSON events. */
        fun parse(output: String): Progress {
            val lines = output.lines()
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
            return Progress(state, exitCode, said.joinToString("\n"), steps.values.toList(), errors)
        }
    }
}
