package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.settings.AgentDirectory
import com.mindtravel.reduction.feature.chat.model.tools.AgentHarness
import com.mindtravel.reduction.feature.chat.model.tools.NativeAgentLauncher
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The Windows worker owns the Codex process and exit receipt even across UI/backend restarts. */
class WindowsAgentLauncher(private val records: File) : NativeAgentLauncher {
    private val home = File(System.getProperty("user.home")).canonicalFile

    private fun resolve(raw: String): File {
        val path = raw.replace('\\', '/')
        val file = when {
            path.startsWith("~/") -> File(home, path.drop(2))
            Regex("^/mnt/[a-zA-Z](/.*)?").matches(path) -> File(path[5] + ":/" + path.drop(7))
            path == "/home/mindtravel/Reduction" || path.startsWith("/home/mindtravel/Reduction/") ->
                File(home, "source/Reduction" + path.removePrefix("/home/mindtravel/Reduction"))
            Regex("^[a-zA-Z]:/.*").matches(path) -> File(path)
            else -> error("未映射为 Windows 本机目录：$raw")
        }
        check(path.split('/').none { it == ".." || it == "." }) { "目录需要使用实际路径：$raw" }
        check(file.toPath().normalize() == file.canonicalFile.toPath()) { "目录需要使用实际路径：$raw" }
        return file.canonicalFile
    }

    override fun start(agent: AgentHarness.Agent, folder: String, prompt: String, directories: List<AgentDirectory>): String {
        try {
            val cwd = resolve(folder)
            val grants = linkedMapOf<File, Boolean>()
            val build = agent == AgentHarness.Agent.BUILD
            for (entry in directories) {
                val dir = resolve(entry.path)
                check(dir.isDirectory) { "授权目录不存在：$dir" }
                if (dir.parentFile == null) continue // Already covered by the read-only :root permission.
                check(grants.put(dir, build && entry.writable) == null) { "重复授权：$dir" }
            }
            if (Regex("^~/reduction-agents/[A-Za-z0-9_-][A-Za-z0-9._-]*$").matches(folder)) {
                Files.createDirectories(cwd.toPath())
                grants.putIfAbsent(cwd, build)
            }
            check(cwd in grants) { "工作目录未授权：$cwd" }
            for ((dir, writable) in grants) {
                if (writable) {
                    val protected = listOf(records, File(home, ".codex"), File(home, ".config/reduction"), File(home, ".local/share/reduction"))
                    check(protected.none {
                        val path = it.canonicalFile.toPath()
                        path.startsWith(dir.toPath()) || dir.toPath().startsWith(path)
                    }) { "授权区域包含应用内部目录：$dir" }
                    check(!home.toPath().startsWith(dir.toPath())) { "整个用户目录不可写：$dir" }
                }
            }
            val executable = System.getenv("REDUCTION_CODEX_EXE")?.let(::File)
                ?: System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
                    .map { File(it, "codex.exe") }.firstOrNull { it.isFile }
                ?: File(System.getenv("LOCALAPPDATA"), "OpenAI/Codex/bin").listFiles().orEmpty()
                    .map { File(it, "codex.exe") }.filter { it.isFile }.maxByOrNull { it.lastModified() }
            check(executable?.isFile == true) { "找不到 Windows codex.exe" }
            val id = "win-" + UUID.randomUUID()
            val run = File(records, id)
            val config = File(run, "codex")
            val result = File(run, "result")
            Files.createDirectories(config.toPath())
            Files.createDirectory(result.toPath())
            val endpoint = System.getenv("REDUCTION_DOT_RESPONSES_URL") ?: "http://127.0.0.1:8788/v1/responses"
            check(endpoint.endsWith("/responses")) { "Dot 地址须以 /responses 结尾" }
            File(config, "config.toml").writeText(buildString {
                appendLine("default_permissions=\"task\"")
                appendLine("model_provider=\"dots\"")
                appendLine("model=\"gpt-6.2-astra-dot\"")
                appendLine("approval_policy=\"never\"")
                appendLine("[windows]")
                appendLine("sandbox=\"elevated\"")
                appendLine("[permissions.task.filesystem]")
                appendLine("\":root\"=\"read\"")
                for ((dir, writable) in grants) {
                    appendLine(JSONObject.quote(dir.path) + "=" + JSONObject.quote(if (writable) "write" else "read"))
                }
                appendLine(JSONObject.quote(result.absolutePath) + "=\"write\"")
                appendLine("[model_providers.dots]")
                appendLine("name=\"Dot Astra\"")
                appendLine("base_url=" + JSONObject.quote(endpoint.removeSuffix("/responses")))
                appendLine("wire_api=\"responses\"")
            })
            val instructions = if (build) "完成后写入 ${File(result, "result.md")}，首行 DONE，随后附实际验证依据。受阻时首行 BLOCKED。" else ""
            File(run, "mode").writeText(agent.wire)
            File(run, "prompt").writeText(prompt.replace(AgentHarness.RESULT_FOLDER, result.absolutePath) + "\n" + instructions)
            File(run, "folder").writeText(folder)
            val java = File(System.getProperty("java.home"), "bin/java.exe")
            val worker = ProcessBuilder(java.absolutePath, "-cp", System.getProperty("java.class.path"),
                WindowsAgentWorker::class.java.name, run.absolutePath, cwd.absolutePath, executable!!.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(run, "worker.log")).start()
            worker.outputStream.close()
            File(run, "worker").writeText("${worker.pid()}\n${worker.info().startInstant().orElseThrow()}")
            return id
        } catch (e: Exception) {
            throw IllegalStateException("Windows 子任务启动失败：${e.message}", e)
        }
    }

    private fun record(folder: String, id: String): File? {
        if (!Regex("win-[a-f0-9-]{36}").matches(id)) return null
        return File(records, id).takeIf { File(it, "folder").isFile && File(it, "folder").readText() == folder }
    }

    override fun progress(folder: String, id: String): AgentHarness.Progress {
        val run = record(folder, id) ?: return AgentHarness.parse("STATE interrupted\nharness：本机没有此任务的执行记录")
        val exit = File(run, "exit").takeIf { it.isFile }?.readText()?.trim()?.toInt()
        val identity = File(run, "worker").readLines()
        val running = ProcessHandle.of(identity.first().toLong()).map {
            it.isAlive && it.info().startInstant().map { start -> start.toString() == identity.last() }.orElse(false)
        }.orElse(false)
        val header = when {
            exit == 143 -> "interrupted"
            exit != null -> "done $exit"
            running -> "running"
            else -> "interrupted"
        }
        return AgentHarness.parse(buildString {
            appendLine("STATE $header")
            File(run, "events").takeIf { it.isFile }?.let { appendLine(it.readText()) }
            if (exit != null && exit != 0) {
                for (name in listOf("stderr", "worker.log")) File(run, name).takeIf { it.isFile }?.let { appendLine(it.readText()) }
            }
            val result = File(run, "result")
            val receipt = File(result, "result.md")
            fun safe(f: File) = f.isFile && !Files.isSymbolicLink(f.toPath()) && f.canonicalFile.parentFile == result.canonicalFile
            if (exit == 0 && File(run, "mode").readText() == "build") {
                val lines = if (safe(receipt)) receipt.readLines() else emptyList()
                if (lines.firstOrNull()?.trim() != "DONE" || lines.drop(1).none { it.isNotBlank() }) {
                    appendLine("harness：写任务缺少 DONE 回执及实际验证依据")
                }
            }
            if (exit != null && safe(receipt)) {
                appendLine("=====RESULT FILE=====")
                appendLine(receipt.readText())
            }
            if (exit != null) {
                var images = 0
                for (f in result.listFiles().orEmpty()) {
                    if (!safe(f) || !f.name.matches(Regex("[A-Za-z0-9._-]+"))) continue
                    val type = when (f.extension.lowercase()) {
                        "png", "webp", "gif" -> f.extension.lowercase()
                        "jpg", "jpeg" -> "jpeg"
                        else -> continue
                    }
                    if (images >= AgentHarness.MAX_IMAGES || f.length() > 3145728) {
                        appendLine("=====IMAGE SKIPPED ${f.name}=====")
                    } else {
                        images++
                        appendLine("=====IMAGE ${f.name} image/$type=====")
                        appendLine(Base64.getEncoder().encodeToString(f.readBytes()))
                    }
                }
            }
        })
    }

    override fun stop(folder: String, id: String) {
        val run = record(folder, id) ?: error("Windows 子任务记录不存在")
        File(run, "stop").createNewFile()
        repeat(100) {
            if (progress(folder, id).state != AgentHarness.State.RUNNING) return
            Thread.sleep(100)
        }
        error("Windows 执行器尚未确认停止")
    }
}

object WindowsAgentWorker {
    @JvmStatic fun main(args: Array<String>) {
        val run = File(args[0])
        var code = 1
        try {
            val builder = ProcessBuilder(args[2], "exec", "--ephemeral", "--skip-git-repo-check", "--json", "-C", args[1], "-")
                .redirectInput(File(run, "prompt")).redirectOutput(File(run, "events")).redirectError(File(run, "stderr"))
            builder.environment()["CODEX_HOME"] = File(run, "codex").absolutePath
            val child = builder.start()
            while (!child.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (File(run, "stop").exists()) {
                    ProcessBuilder("taskkill.exe", "/PID", child.pid().toString(), "/T", "/F").inheritIO().start().waitFor()
                    child.waitFor()
                    break
                }
            }
            code = if (File(run, "stop").exists()) 143 else child.exitValue()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            val tmp = File(run, "exit.tmp")
            tmp.writeText(code.toString())
            Files.move(tmp.toPath(), File(run, "exit").toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
