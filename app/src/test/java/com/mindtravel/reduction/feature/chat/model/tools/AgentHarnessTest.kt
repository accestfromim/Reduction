package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.host.ToolApprovalHost
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcInfo
import com.mindtravel.reduction.hardware.pc.PcProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentHarnessTest {

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

    /** One registry for the whole test, as in the app: it outlives each tool. */
    private val runs = AgentRuns()

    private fun tool(launcher: FakeLauncher, allowWrite: Boolean = true, approval: Approval = Approval(true)) =
        RunAgentTool(
            harness = harness(launcher.script),
            runs = runs,
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
        val said = RunAgentTool(harness(launcher), AgentRuns(), "/home/u/p", "/home/u/a", true, Approval(true))
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
        assertEquals("【完成】任务 1\n做完了。", said)
        assertEquals(listOf("plan", "/home/u/agents/research"), launcher.started().take(2))
    }

    @Test
    fun startReturnsAtOnceAndTheResultComesWithStatus() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n$toolEvent\n")
        val startedAt = System.currentTimeMillis()
        val said = tool(launcher).execute(args("action" to "start", "task" to "大任务", "folder" to "notes", "access" to "write"))
        assertTrue("start held the reply for ${System.currentTimeMillis() - startedAt}ms", System.currentTimeMillis() - startedAt < 2_000)
        assertTrue(said, said.startsWith("【已派发】任务 1：大任务"))
        assertEquals(listOf("build", "/home/u/agents/notes"), launcher.started().take(2))

        val during = tool(launcher).execute(args("action" to "status"))
        assertTrue(during, during.startsWith("【进行中】任务 1：大任务") && during.contains("- bash: ls -la（completed）"))

        // Asking later needs neither the number nor the folder: the app knows both.
        launcher.status("STATE done 0\n$toolEvent\n$textEvent\n")
        assertEquals("【完成】任务 1\n做完了。", tool(launcher).execute(args("action" to "status")))
        assertEquals("现在没有在跑的任务。", tool(launcher).execute(args("action" to "stop")))
    }

    @Test
    fun theDefaultWaitIsTooShortToHoldUpAReply() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), AgentRuns(), "/home/u/p", "/home/u/a", true, Approval(true))
        val startedAt = System.currentTimeMillis()
        tool.execute(args("action" to "start", "task" to "大任务"))
        assertTrue(System.currentTimeMillis() - startedAt < 5_000)
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
    fun stopEndsTheOnlyRunWithoutBeingToldWhich() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        tool(launcher).execute(args("action" to "start", "task" to "大任务"))
        assertEquals("已结束任务 1", tool(launcher).execute(args("action" to "stop")))
        assertEquals("t1", launcher.stopped())
    }

    @Test
    fun withSeveralRunsTheNumberSaysWhich() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        tool(launcher).execute(args("action" to "start", "task" to "查甲"))
        tool(launcher).execute(args("action" to "start", "task" to "查乙", "folder" to "notes"))

        val all = tool(launcher).execute(args("action" to "status"))
        assertTrue(all, all.contains("任务 1：查甲") && all.contains("任务 2：查乙"))
        val one = tool(launcher).execute(args("action" to "status", "run" to "任务 2"))
        assertTrue(one, one.contains("任务 2：查乙") && !one.contains("查甲"))

        val unclear = tool(launcher).execute(args("action" to "stop"))
        assertTrue(unclear, unclear.startsWith("有 2 个任务在跑，请说明结束哪一个。"))
        assertEquals("已结束任务 2", tool(launcher).execute(args("action" to "stop", "run" to "2")))
        val wrong = tool(launcher).execute(args("action" to "status", "run" to "9"))
        assertTrue(wrong, wrong.startsWith("没有任务 9。在跑的任务：任务 1（查甲）"))
    }

    @Test
    fun aRunThatHasEndedIsReportedOnceWithoutBeingAskedFor() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        tool(launcher).execute(args("action" to "start", "task" to "查甲"))
        assertEquals(emptyList<Pair<Int, String>>(), tool(launcher).endedRuns())

        launcher.status("STATE done 0\n$textEvent\n")
        assertEquals(listOf(1 to "【完成】任务 1\n做完了。"), tool(launcher).endedRuns())
        assertEquals(emptyList<Pair<Int, String>>(), tool(launcher).endedRuns())
    }

    @Test
    fun runNumbersSurviveARestart() {
        val file = File(Files.createTempDirectory("runs").toFile(), "runs.json")
        val first = AgentRuns(file)
        first.add("t1", "research", "/a/research", "查甲\n第二行")
        first.finish(first.add("t2", "notes", "/a/notes", "查乙"))

        val again = AgentRuns(file)
        assertEquals(listOf(1), again.unfinished().map { it.number })
        assertEquals("查甲", again.find(1)!!.title)
        assertEquals("/a/notes", again.find(2)!!.folder)
        assertEquals(3, again.add("t3", "research", "/a/research", "查丙").number)
    }
}
