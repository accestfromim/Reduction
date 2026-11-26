package com.mindtravel.reduction.feature.chat.model.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs single-shot Linux tasks with an installed bwrap that records any invocation. */
class DirectAgentTest {
    private lateinit var home: File
    private lateinit var script: File
    private lateinit var folder: File

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("linux"))
        assumeTrue("needs setsid", available("setsid"))
        assumeTrue("needs timeout", available("timeout"))

        val harness = File("src/main/assets/harness/run-agent.sh")
        assumeTrue("harness script not found at ${harness.absolutePath}", harness.isFile)
        script = harness

        home = Files.createTempDirectory("reduction-plan-test").toFile().canonicalFile
        folder = File(home, "reduction-agents/research").apply { mkdirs() }

        val bin = File(home, "bin").apply { mkdirs() }
        executable(File(bin, "bwrap"), "#!/bin/sh\ntouch \"\$HOME/bwrap-called\"\nexit 97")
        val fake = """
            #!/bin/sh
            command -v bwrap >/dev/null || exit 1
            test -r "${'$'}HOME/host-file" || exit 2
            pwd -P > "${'$'}REDUCTION_RESULT_DIR/cwd"
            printf '%s\n' "${'$'}@" > "${'$'}REDUCTION_RESULT_DIR/args"
        """.trimIndent()
        for (name in listOf("opencode", "codex", "claude", "mimo")) executable(File(bin, name), fake)
        File(home, "host-file").writeText("readable by this account")
        for (path in listOf(".local/share/reduction-codex/auth.json", ".local/share/mimocode/auth.json")) {
            File(home, path).apply { requireNotNull(parentFile).mkdirs(); writeText("{}") }
        }
    }

    @After
    fun tearDown() {
        if (::home.isInitialized) home.deleteRecursively()
    }

    @Test fun allEnginesRunInTheSelectedFolderWithHostAccess() {
        for (engine in listOf("opencode", "codex", "claude", "mimocode")) {
            for (agent in listOf("plan", "build")) {
                val args = start(engine, agent)
                assertTrue(args.none { it in setOf("--sandbox", "--dangerously-bypass-approvals-and-sandbox",
                                                   "--permission-mode", "--auto") })
                if (engine in listOf("opencode", "mimocode")) {
                    assertEquals(agent, args[args.indexOf("--agent") + 1])
                }
            }
        }
        assertFalse(File(home, "bwrap-called").exists())
        assertFalse(File(home, ".reduction-result").exists())
    }

    private fun start(engine: String, agent: String): List<String> {
        val process = ProcessBuilder("sh", "-s", "--", "start", agent, folder.absolutePath, "查资料")
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", home.absolutePath)
                    put("PATH", listOf(File(home, "bin").absolutePath, "/usr/local/bin", "/usr/bin", "/bin").joinToString(":"))
                    put("AGENT_KEY", "sk-test")
                    put("AGENT_MODEL", "deepseek/deepseek-flash")
                    put("AGENT_ENGINE", engine)
                    put("AGENT_TIMEOUT", "5")
                }
            }
            .start()
        process.outputStream.use { it.write(script.readBytes()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("harness did not finish: $output", process.waitFor(10, TimeUnit.SECONDS))

        assertTrue(output, output.startsWith("RUN "))
        val id = output.trim().removePrefix("RUN ")
        val run = File(home, ".local/share/reduction-agents/reduction-agents_research/runs/$id")
        val exit = File(run, "exit")
        val deadline = System.currentTimeMillis() + 5_000
        while (!exit.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("$engine/$agent did not finish", exit.exists())
        assertEquals("$engine/$agent", "0", exit.readText().trim())
        assertEquals(folder.absolutePath, File(run, "result/cwd").readText().trim())
        return File(run, "result/args").readLines()
    }

    private fun executable(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text + "\n")
        check(file.setExecutable(true))
    }

    private fun available(command: String): Boolean = runCatching {
        ProcessBuilder("sh", "-c", "command -v $command").start().waitFor() == 0
    }.getOrDefault(false)
}
