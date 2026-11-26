package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.model.chat.Role
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.core.settings.AgentDirectory
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

    /** One registry for the whole test, as in the app: it outlives each tool. */
    private val runs = AgentRuns()

    private fun tool(launcher: FakeLauncher, allowWrite: Boolean = true, directories: List<AgentDirectory> = emptyList()) =
        RunAgentTool(
            harness = harness(launcher.script),
            runs = runs,
            project = "/home/u/project",
            agentsRoot = "/home/u/agents",
            allowWrite = allowWrite,
            directories = directories,
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
        val launcher = "printf '%s\\n' 'harness：这台电脑上找不到 Codex CLI'\n"
        val said = RunAgentTool(harness(launcher), AgentRuns(), "/home/u/p", "/home/u/a", true, emptyList())
            .execute(args("action" to "start", "task" to "查资料"))
        assertEquals("派发失败：harness：这台电脑上找不到 Codex CLI", said)
    }

    @Test
    fun statusIgnoresToolEventsAndMessages() {
        val progress = AgentHarness.parse("STATE running\n$toolEvent\n$textEvent\n")
        assertEquals(AgentHarness.State.RUNNING, progress.state)
        assertTrue(progress.resultFile.isEmpty())
        assertTrue(progress.errors.isEmpty())
    }

    @Test
    fun aFailedExitIsReportedWithoutReadingTheTranscript() {
        val error = """{"type":"error","error":{"name":"APIError","data":{"message":"Insufficient Balance (request_id: x)","statusCode":402}}}"""
        val progress = AgentHarness.parse("STATE done 1\n$error\n")
        assertFalse(progress.succeeded)
        assertEquals(1, progress.exitCode)
        assertTrue(progress.errors.isEmpty())
    }

    @Test
    fun aFinishedTaskReturnsWhatTheAgentSaid() {
        val launcher = FakeLauncher()
        launcher.status("STATE done 0\n=====RESULT FILE=====\n做完了。\n")
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
        assertTrue(during, during.startsWith("【进行中】任务 1：大任务") && !during.contains("ls -la"))

        // Asking later needs neither the number nor the folder: the app knows both.
        launcher.status("STATE done 0\n=====RESULT FILE=====\n做完了。\n")
        assertEquals("【完成】任务 1\n做完了。", tool(launcher).execute(args("action" to "status")))
        assertEquals("现在没有在跑的任务。", tool(launcher).execute(args("action" to "stop")))
    }

    @Test
    fun theDefaultWaitIsTooShortToHoldUpAReply() {
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), AgentRuns(), "/home/u/p", "/home/u/a", true, emptyList())
        val startedAt = System.currentTimeMillis()
        tool.execute(args("action" to "start", "task" to "大任务"))
        assertTrue(System.currentTimeMillis() - startedAt < 5_000)
    }

    @Test
    fun aProjectNeedsAStoredDirectoryGrant() {
        val launcher = FakeLauncher()
        launcher.status("STATE done 0\n=====RESULT FILE=====\n做完了。\n")
        val args = args("action" to "start", "task" to "改代码", "folder" to "project", "access" to "write")
        assertEquals("工作目录未获授权或名称无效：project", tool(launcher).execute(args))
        assertTrue(launcher.started().isEmpty())

        val allowed = listOf(AgentDirectory("/home/u/project", writable = true))
        tool(launcher, directories = allowed).execute(args)
        assertEquals(listOf("build", "/home/u/project"), launcher.started().take(2))
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
            assertTrue("$name -> $said", said.startsWith("工作目录未获授权或名称无效"))
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
        assertEquals(emptyList<RunAgentTool.Ended>(), tool(launcher).endedRuns())

        launcher.status("STATE done 0\n=====RESULT FILE=====\n做完了。\n")
        assertEquals(listOf(RunAgentTool.Ended(1, "【完成】任务 1\n做完了。")), tool(launcher).endedRuns())
        assertEquals(emptyList<RunAgentTool.Ended>(), tool(launcher).endedRuns())
    }

    @Test
    fun runNumbersAndRecordsSurviveARestart() {
        val file = File(Files.createTempDirectory("runs").toFile(), "runs.json")
        val first = AgentRuns(file)
        first.add("t1", "research", "/a/research", "查甲\n第二行")
        first.end(first.add("t2", "notes", "/a/notes", "查乙", write = true), AgentRuns.DONE, "乙的结果")

        val again = AgentRuns(file)
        assertEquals(listOf(1), again.unfinished().map { it.number })
        assertEquals("查甲", again.find(1)!!.title)
        assertEquals("查甲\n第二行", again.find(1)!!.task)
        assertEquals(listOf("/a/notes", "完成", "乙的结果"), again.find(2)!!.let { listOf(it.folder, it.outcome, it.result) })
        assertEquals(3, again.add("t3", "research", "/a/research", "查丙").number)
    }

    @Test
    fun theFirstEndingRecordedIsTheOneThatStays() {
        val runs = AgentRuns()
        val run = runs.add("t1", "research", "/a/research", "查甲")
        runs.end(run, AgentRuns.DONE, "结果")
        runs.end(run, AgentRuns.LOST, "别的")
        assertEquals(listOf("完成", "结果"), listOf(run.outcome, run.result))
    }

    @Test
    fun theDirectoryKeepsAllSessionsAndContainsOnlyMetadata() {
        val runs = AgentRuns()
        repeat(35) { i -> runs.add("t$i", "research", "/a/research", "查询 $i\n私有任务正文") }
        val run = runs.find(1)!!
        runs.card(run, "sub agent/任务 1")
        runs.end(run, AgentRuns.DONE, "私有结果")
        val directory = AgentLedger.render(runs.all())
        assertEquals(35, directory.lines().count { it.startsWith("- 任务") })
        assertTrue(directory.contains("任务 1 · 查询 0 · opencode · 完成 · 卡片「sub agent/任务 1」"))
        assertFalse(directory.contains("私有"))
    }

    @Test
    fun picturesLeftBesideTheResultComeBackAsPicturesAndTheWordsAreAsBefore() {
        val plain = "STATE done 0\n$textEvent\n\n=====RESULT FILE=====\n结果文件"
        val before = AgentHarness.parse(plain)
        val after = AgentHarness.parse(
            plain + "\n=====IMAGE chart.png image/png=====\nQUJD\nREVG\n=====IMAGE SKIPPED huge.png=====\n" +
                "\n=====IMAGE photo.jpg image/jpeg=====\nR0hJ"
        )
        // What came back in words is untouched by the pictures after it.
        assertEquals(before.resultFile, after.resultFile)
        assertEquals("结果文件", after.resultFile)
        assertTrue(before.images.isEmpty() && before.skippedImages.isEmpty())
        assertEquals(
            listOf(ChatImage("chart.png", "image/png", "QUJDREVG"), ChatImage("photo.jpg", "image/jpeg", "R0hJ")),
            after.images
        )
        assertEquals(listOf("huge.png"), after.skippedImages)
    }

    @Test
    fun aFinishedRunHandsItsPicturesOnWithItsReport() {
        val runs = AgentRuns()
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), runs, "/home/u/project", "/home/u/agents", true, emptyList(), 300, 20)
        tool.execute(args("action" to "start", "task" to "画一张图", "folder" to "notes"))
        launcher.status("STATE done 0\n=====RESULT FILE=====\n做完了。\n\n=====IMAGE chart.png image/png=====\nQUJD")
        val ended = tool.endedRuns().single()
        assertEquals(listOf(ChatImage("chart.png", "image/png", "QUJD")), ended.images)
        assertTrue(ended.report, ended.report.startsWith("【完成】任务 1\n做完了。") && ended.report.contains("附图 1 张") && ended.report.contains("chart.png"))
        // The record keeps the words; the picture is not stored in it.
        assertFalse(runs.find(1)!!.result.contains("QUJD"))
    }

    @Test
    fun picturesOfARunTheAssistantAskedAboutFollowInAMessageOfTheirOwn() {
        val runs = AgentRuns()
        val launcher = FakeLauncher()
        launcher.status("STATE running\n")
        val tool = RunAgentTool(harness(launcher.script), runs, "/home/u/project", "/home/u/agents", true, emptyList(), 300, 20)
        tool.execute(args("action" to "start", "task" to "画一张图", "folder" to "notes"))
        launcher.status("STATE done 0\n=====RESULT FILE=====\n做完了。\n\n=====IMAGE chart.png image/png=====\nQUJD")
        // A tool's answer is words: it says the picture is on its way, and does not claim to hold it.
        val answer = tool.execute(args("action" to "status"))
        assertTrue(answer, answer.contains("chart.png") && answer.contains("随后会单独发给你") && !answer.contains("就在这条消息里"))
        val ended = tool.endedRuns().single()
        assertEquals(listOf(ChatImage("chart.png", "image/png", "QUJD")), ended.images)
        assertTrue(ended.report, ended.report.contains("任务 1 的附图 1 张"))
        // Handed over once.
        assertTrue(tool.endedRuns().isEmpty())
    }

    @Test
    fun picturesFromThePageAreReadAndWhatIsNotAPictureIsLeftOut() {
        val read = ChatImage.listFromJson(
            """[{"name":"照片","mediaType":"image/jpeg","base64":"QUJD"},{"name":"x","mediaType":"text/html","base64":"QUJD"},
               {"mediaType":"image/png","base64":"REVG"},{"name":"空","mediaType":"image/png","base64":""}]"""
        )
        assertEquals(listOf(ChatImage("照片", "image/jpeg", "QUJD"), ChatImage("图片 3", "image/png", "REVG")), read)
        assertTrue(ChatImage.listFromJson("不是 JSON").isEmpty())
        val many = "[" + (1..9).joinToString(",") { """{"name":"p$it","mediaType":"image/png","base64":"QUJD"}""" } + "]"
        assertEquals(ChatImage.MAX_PER_MESSAGE, ChatImage.listFromJson(many).size)
    }

    @Test
    fun aMessageWithPicturesIsSentAsTextAndPicturesAndOneWithoutIsSentAsBefore() {
        val plain = ChatMessage(Role.USER, "看看").toJson()
        assertEquals("看看", plain.get("content"))
        val parts = ChatMessage(Role.USER, "看看", images = listOf(ChatImage("a.png", "image/png", "QUJD"))).toJson().getJSONArray("content")
        assertEquals(2, parts.length())
        assertEquals("看看", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun theLedgerShowsTheNewestRunFirst() {
        val runs = AgentRuns()
        runs.add("t1", "research", "/a/research", "查甲")
        runs.add("t2", "research", "/a/research", "查乙")
        val card = AgentLedger.render(runs.all())
        assertTrue(card.indexOf("- 任务 2") in 0 until card.indexOf("- 任务 1"))
    }
}
