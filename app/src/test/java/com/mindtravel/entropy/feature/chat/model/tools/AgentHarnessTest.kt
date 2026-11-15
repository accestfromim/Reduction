package com.mindtravel.entropy.feature.chat.model.tools

import com.mindtravel.entropy.core.host.ToolApprovalHost
import com.mindtravel.entropy.hardware.pc.PcGateway
import com.mindtravel.entropy.hardware.pc.PcInfo
import com.mindtravel.entropy.hardware.pc.PcProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentHarnessTest {

    /** Runs the command with a real shell, the way the SSH session on the computer would. */
    private class LocalShell : PcGateway {
        override fun exec(profile: PcProfile, command: String): String {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output
        }

        override fun connect(profile: PcProfile) = Unit
        override fun isConnected() = true
        override fun info(profile: PcProfile): PcInfo = throw UnsupportedOperationException()
        override fun watchUpdates(profile: PcProfile, manifestUrl: String, onLine: (String) -> Unit) =
            AutoCloseable { }
        override fun publicKey() = ""
        override fun close() = Unit
    }

    private fun harness(script: String) =
        AgentHarness(LocalShell(), PcProfile("host", 22, "user"), "sk-test_KEY-1", "deepseek-flash") { script }

    /**
     * A stand-in launcher: "start" records its arguments and answers RUN t1; "status"
     * prints whatever is in the status file; "stop" records that it was asked.
     */
    private class FakeLauncher {
        val dir: File = Files.createTempDirectory("harness-test").toFile()
        val script = """
            d='${dir.absolutePath}'
            case "${'$'}1" in
              start) printf '%s\n' "${'$'}2" "${'$'}3" "${'$'}4" > "${'$'}d/started"; echo "RUN t1" ;;
              status) cat "${'$'}d/status" ;;
              stop) echo "${'$'}3" > "${'$'}d/stopped"; echo STOPPED ;;
            esac
        """.trimIndent() + "\n"

        fun status(text: String) = File(dir, "status").writeText(text)
        fun started(): List<String> = File(dir, "started").takeIf { it.isFile }?.readLines().orEmpty()
        fun stopped(): String = File(dir, "stopped").takeIf { it.isFile }?.readText()?.trim().orEmpty()
    }

    private class Approval(private val answer: Boolean) : ToolApprovalHost {
        var asked = 0
        override fun requestToolApproval(title: String, message: String, callback: (Boolean) -> Unit) {
            asked++
            callback(answer)
        }
    }

    private fun tool(launcher: FakeLauncher, allowWrite: Boolean = true, approval: Approval = Approval(true)) =
        RunAgentTool(
            harness = harness(launcher.script),
            project = "/home/u/project",
            agentsRoot = "/home/u/agents",
            allowWrite = allowWrite,
            approvalHost = approval,
            waitMillis = 300,
            pollMillis = 20
        )

    private fun args(vararg pairs: Pair<String, String>) = JSONObject(mapOf(*pairs)).toString()

    private val textEvent = """{"type":"text","part":{"type":"text","text":"做完了。"}}"""
    private val toolEvent =
        """{"type":"tool","part":{"type":"tool","tool":"bash","callID":"c1","state":{"status":"completed","input":{"command":"ls -la"}}}}"""

    @Test
    fun argumentsReachTheLauncherIntact() {
        val received = File(Files.createTempDirectory("harness-args").toFile(), "args")
        val record = "printf '%s|%s|%s|%s' \"\$1\" \"\$2\" \"\$3\" \"\$4\" > '${received.absolutePath}'; echo 'RUN t9'\n"
        val prompt = "改一下 `main`：把 \$HOME 换成 'x'，\n第二行 \"引号\"；rm -rf /"

        val id = harness(record).start(AgentHarness.Agent.BUILD, "/home/someone/My Project", prompt)

        assertEquals("t9", id)
        assertEquals("start|build|/home/someone/My Project|$prompt", received.readText())
    }

    @Test
    fun aRunStartsOnThePhonesKeyAndModel() {
        val received = File(Files.createTempDirectory("harness-env").toFile(), "env")
        val record = "printf '%s|%s' \"\$AGENT_KEY\" \"\$AGENT_MODEL\" > '${received.absolutePath}'; echo 'RUN t1'\n"
        harness(record).start(AgentHarness.Agent.PLAN, "/home/u/p", "问")
        assertEquals("sk-test_KEY-1|deepseek/deepseek-flash", received.readText())
    }

    @Test
    fun aRunThatCannotStartReportsTheLaunchersMessage() {
        val launcher = "printf '%s\\n' 'harness：电脑上没有 bwrap，无法隔离运行'\n"
        val said = RunAgentTool(harness(launcher), "/home/u/p", "/home/u/a", true, Approval(true))
            .execute(args("action" to "start", "task" to "查资料"))
        assertEquals("派发失败：harness：电脑上没有 bwrap，无法隔离运行", said)
    }

    @Test
    fun progressIsReadFromTheEvents() {
        val progress = AgentHarness.parse("STATE running\n$toolEvent\n$textEvent\n")
        assertEquals(AgentHarness.State.RUNNING, progress.state)
        assertEquals(listOf("bash: ls -la（completed）"), progress.steps)
        assertEquals("做完了。", progress.said)
    }

    @Test
    fun aModelErrorIsReportedNotSwallowed() {
        val error = """{"type":"error","error":{"name":"APIError","data":{"message":"Insufficient Balance (request_id: x)","statusCode":402}}}"""
        val progress = AgentHarness.parse("STATE done 1\n$error\n")
        assertFalse(progress.succeeded)
        assertEquals(listOf("Insufficient Balance (request_id: x)"), progress.errors)
    }

    @Test
    fun aFinishedTaskReturnsWhatTheAgentSaid() {
        val launcher = FakeLauncher()
        launcher.status("STATE done 0\n$toolEvent\n$textEvent\n")
        val said = tool(launcher).execute(args("action" to "start", "task" to "查资料"))
        assertEquals("【完成】运行编号 t1\n做完了。", said)
        assertEquals(listOf("plan", "/home/u/agents/research"), launcher.started().take(2))
    }

    @Test
    fun aLongTaskReturnsItsIdAndProgressInsteadOfBlocking() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n$toolEvent\n")
        val said = tool(launcher).execute(args("action" to "start", "task" to "大任务", "folder" to "notes", "access" to "write"))
        assertTrue(said, said.startsWith("【进行中】运行编号 t1，文件夹 notes。"))
        assertTrue(said, said.contains("- bash: ls -la（completed）"))
        assertEquals(listOf("build", "/home/u/agents/notes"), launcher.started().take(2))

        launcher.status("STATE done 0\n$toolEvent\n$textEvent\n")
        val later = tool(launcher).execute(args("action" to "status", "id" to "t1", "folder" to "notes"))
        assertEquals("【完成】运行编号 t1\n做完了。", later)
    }

    @Test
    fun changingTheProjectAsksOnThePhoneFirst() {
        val launcher = FakeLauncher()
        launcher.status("STATE done 0\n$textEvent\n")
        val refused = Approval(false)
        val said = tool(launcher, approval = refused)
            .execute(args("action" to "start", "task" to "改代码", "folder" to "project", "access" to "write"))
        assertEquals("已取消：用户拒绝执行", said)
        assertEquals(1, refused.asked)
        assertEquals("nothing was started", emptyList<String>(), launcher.started())

        val reading = Approval(false)
        tool(launcher, approval = reading).execute(args("action" to "start", "task" to "看代码", "folder" to "project"))
        assertEquals("reading the project needs no approval", 0, reading.asked)
        assertEquals(listOf("plan", "/home/u/project"), launcher.started().take(2))
    }

    @Test
    fun writingIsRefusedWhenTheSettingIsOff() {
        val launcher = FakeLauncher()
        val said = tool(launcher, allowWrite = false)
            .execute(args("action" to "start", "task" to "存资料", "access" to "write"))
        assertEquals("派发失败：设置里没有允许 sub agent 修改文件", said)
        assertEquals(emptyList<String>(), launcher.started())
    }

    @Test
    fun aFolderNameCannotEscapeTheAgentsRoot() {
        val launcher = FakeLauncher()
        for (name in listOf("../secret", "a/b", "/etc", "..", ".hidden")) {
            val said = tool(launcher).execute(args("action" to "start", "task" to "x", "folder" to name))
            assertTrue("$name -> $said", said.startsWith("文件夹名无效"))
        }
        assertEquals(emptyList<String>(), launcher.started())
    }

    @Test
    fun stopReachesTheLauncher() {
        val launcher = FakeLauncher()
        assertEquals("已结束运行 t1", tool(launcher).execute(args("action" to "stop", "id" to "t1")))
        assertEquals("t1", launcher.stopped())
    }
}
