package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject

/**
 * The tools the assistant has in one reply. Every tool takes one more argument than it
 * declares: a summary of the call, written by the assistant, which is what the user sees.
 */
class ToolRegistry(
    tools: List<ChatTool>,
    /** Where answers too long to hand over at once are kept whole; null hands every answer over as it is. */
    private val long: LongResults? = null
) {
    private val byName = (if (long == null) tools else tools + ReadMoreTool(long)).associateBy { it.spec.name }

    fun specs(): List<ToolSpec> = byName.values.map { tool ->
        tool.spec.copy(parameters = listOf(SUMMARY) + tool.spec.parameters)
    }

    fun execute(name: String, argumentsJson: String): String {
        val tool = byName[name] ?: return "未知工具：$name"
        val answer = runCatching { tool.execute(argumentsJson) }
            .getOrElse { "工具执行失败：${it.message ?: "未知错误"}" }
        // A long answer is handed over a piece at a time and kept whole; the tool that fetches pieces is not itself cut.
        return if (long == null || name == "card" || tool is ReadMoreTool) answer else long.first(name, answer)
    }

    /** The pictures that go with the answer the named tool has just given. */
    fun pictures(name: String): List<ChatImage> = runCatching { byName[name]?.pictures() }.getOrNull().orEmpty()

    /** One short line saying what this call does: the assistant's own words, or the tool's. */
    fun summary(name: String, argumentsJson: String): String {
        val written = runCatching { JSONObject(argumentsJson).stringOrNull(SUMMARY.name) }.getOrNull()
        val line = written?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            .ifBlank { runCatching { byName[name]?.describe(argumentsJson) }.getOrNull().orEmpty() }
            .ifBlank { name }
        return if (line.length <= SUMMARY_CHARS) line else line.take(SUMMARY_CHARS) + "…"
    }

    fun announces(name: String, argumentsJson: String): Boolean =
        runCatching { byName[name]?.announces(argumentsJson) }.getOrNull() == true

    private companion object {
        const val SUMMARY_CHARS = 40

        val SUMMARY = ToolParam(
            "summary",
            "用一句不超过二十个字的话说明这次调用在做什么，给用户看。写能读顺的话，不写代码、路径和网址。",
            required = true
        )
    }
}
