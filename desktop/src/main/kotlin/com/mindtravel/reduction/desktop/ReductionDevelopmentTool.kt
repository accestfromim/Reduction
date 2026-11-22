package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject
import java.io.File

class ReductionDevelopmentTool : ChatTool {
    override val spec = ToolSpec("develop_reduction","委托本机 Codex 开发 Reduction。在指定源码副本中编辑，返回真实会话编号、结果和待审查补丁。",listOf(
        ToolParam("prompt","具体改动与验收条件"),
        ToolParam("files","需要提供给 Codex 的仓库相对文件路径，以换行分隔")
    ))
    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val root = File(System.getenv("REDUCTION_ROOT") ?: System.getProperty("user.dir"))
        val python = System.getenv("REDUCTION_PYTHON") ?: if (System.getProperty("os.name").startsWith("Windows")) "python.exe" else "python3"
        val process = ProcessBuilder(python, "-X", "utf8", File(root, "tools/session/develop.py").path)
            .directory(root).redirectErrorStream(true).start()
        val input = JSONObject().put("prompt",args.getString("prompt"))
            .put("files",args.getString("files").lines().map(String::trim).filter(String::isNotEmpty))
        process.outputStream.bufferedWriter().use { it.write(input.toString()) }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor()==0) { output.takeLast(1600) }
        return output
    }
}
