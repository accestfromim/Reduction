package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.hardware.pc.PcProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Runs the real script that ships with the app against a made-up home directory. */
class SessionStatusToolTest {

    private val script = File("src/main/assets/harness/session-status.py").readText()
    private val home: File = Files.createTempDirectory("session-status").toFile()

    // The independent iOS session browser still ships this script.
    private fun report(hours: Int = 24) =
        AgentHarness(LocalShell(home), PcProfile("host", 22, "user"), "", "") { "" }
            .runScript("python3 -", script, "--hours", hours.toString(), "--limit", "8")

    private fun hasPython(): Boolean =
        runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun claudeSession(name: String, vararg lines: String) {
        val dir = File(home, ".claude/projects/-work-demo").apply { mkdirs() }
        File(dir, "$name.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun userSays(text: String, at: Instant) =
        """{"type":"user","timestamp":"$at","cwd":"/work/demo","message":{"role":"user","content":"$text"}}"""

    private fun assistantSays(text: String, at: Instant, stop: String = "end_turn") =
        """{"type":"assistant","timestamp":"$at","cwd":"/work/demo","message":{"role":"assistant","stop_reason":"$stop","content":[{"type":"text","text":"$text"}]}}"""

    private fun assistantCalls(tool: String, at: Instant) =
        """{"type":"assistant","timestamp":"$at","cwd":"/work/demo","message":{"role":"assistant","stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"$tool","input":{}}]}}"""

    @Test
    fun aFinishedReplyIsWaitingForTheUser() {
        assumeTrue(hasPython())
        val now = Instant.now()
        claudeSession("a", userSays("把测试跑一遍", now.minusSeconds(120)), assistantSays("跑完了，全部通过。", now.minusSeconds(60)))
        val report = report()
        assertTrue(report, report.contains("[claude] /work/demo"))
        assertTrue(report, report.contains("状态：等用户输入"))
        assertTrue(report, report.contains("用户最后说：把测试跑一遍"))
        assertTrue(report, report.contains("助手最后说：跑完了，全部通过。"))
    }

    @Test
    fun aToolThatHasNotMovedForLongIsFlagged() {
        assumeTrue(hasPython())
        val now = Instant.now()
        claudeSession("b", userSays("构建一下", now.minusSeconds(4000)), assistantCalls("Bash", now.minusSeconds(3600)))
        val report = report()
        assertTrue(report, report.contains("工具 Bash 已运行"))
        assertTrue(report, report.contains("可能卡住，或在等用户批准"))
    }

    @Test
    fun secretsInTheLastWordsAreBlanked() {
        assumeTrue(hasPython())
        val now = Instant.now()
        claudeSession("c", userSays("密钥是 sk-abcdef0123456789abcdef 记一下", now.minusSeconds(30)), assistantSays("好的。", now.minusSeconds(20)))
        val report = report()
        assertFalse(report, report.contains("sk-abcdef0123456789abcdef"))
        assertTrue(report, report.contains("[已隐去]"))
    }

    @Test
    fun nothingOutsideTheSessionRecordsIsReported() {
        assumeTrue(hasPython())
        File(home, ".ssh").mkdirs()
        File(home, ".ssh/id_ed25519").writeText("PRIVATE-KEY-MATERIAL")
        File(home, ".claude").mkdirs()
        File(home, ".claude/.credentials.json").writeText("""{"token":"CREDENTIAL-MATERIAL"}""")
        claudeSession("d", userSays("你好", Instant.now().minusSeconds(10)))
        val report = report()
        assertFalse(report, report.contains("PRIVATE-KEY-MATERIAL") || report.contains("CREDENTIAL-MATERIAL"))
        assertTrue(report, report.contains("模型处理中"))
    }

    @Test
    fun anEmptyComputerSaysSo() {
        assumeTrue(hasPython())
        assertTrue(report(2).contains("最近 2 小时内没有活动的会话"))
    }
}
