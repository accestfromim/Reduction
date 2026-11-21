package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Start a fresh read-only session in the Windows CLI's existing account. */
class CodexSessionTool(private val data: File) : ChatTool {
    override val spec = ToolSpec(
        name = "start_codex_session",
        description = "用户明确要求新开 Windows Codex 会话时使用。传入第一条消息。创建后自动指定为 Reduction 当前的 Windows Codex 会话，返回编号。",
        parameters = listOf(ToolParam("prompt", "新会话的第一条消息", required = true))
    )

    private val pending = File(data, "codex-binding-pending.json")
    private companion object { val lock = Any() }

    private fun bind(id: String): String {
        val record = JSONObject().put("session_id", id).put("binding", "assigned")
        val temp = File.createTempFile("codex-binding-", ".tmp", data)
        try {
            temp.writeText(record.toString())
            java.nio.file.Files.move(temp.toPath(), File(data, "codex-binding.json").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        return record.toString()
    }

    override fun execute(argumentsJson: String): String = synchronized(lock) { start(argumentsJson) }

    private fun start(argumentsJson: String): String {
        val prompt = JSONObject(argumentsJson).getString("prompt")
        require(prompt.isNotBlank() && prompt.length <= 8000)
        if (pending.exists()) {
            val record = JSONObject(pending.readText())
            val migrated = bind(record.getString("session_id"))
            check(pending.delete()) { "旧绑定记录未能迁移" }
            if (record.optString("prompt") == prompt) return migrated
        }
        val process = ProcessBuilder("codex.exe", "exec", "--json", "--skip-git-repo-check",
            "--sandbox", "read-only", "-").directory(File("/mnt/c")).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val started = CompletableFuture<String>()
        thread(isDaemon = true) {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (started.isDone) continue
                        val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (event.optString("type") == "thread.started") {
                            event.optString("thread_id").takeIf { it.isNotBlank() }?.let { started.complete(it) }
                        }
                    }
                }
            } finally {
                started.completeExceptionally(IllegalStateException("Windows Codex 未返回会话编号"))
            }
        }
        thread(isDaemon = true) {
            if (!process.waitFor(90, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        val id = try {
            process.outputStream.bufferedWriter().use { it.write(prompt) }
            started.get(90, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            process.destroyForcibly()
            throw failure
        }
        return bind(id)
    }
}
