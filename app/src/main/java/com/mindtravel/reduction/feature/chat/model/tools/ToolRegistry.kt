package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject

/**
 * The tools the assistant has in one reply. Every tool takes one more argument than it
 * declares: a summary of the call, written by the assistant, which is what the user sees.
 */
class ToolRegistry(tools: List<ChatTool>) {
    private val byName = tools.associateBy { it.spec.name }

    fun specs(): List<ToolSpec> = byName.values.map { tool ->
        tool.spec.copy(parameters = listOf(SUMMARY) + tool.spec.parameters)
    }

    fun execute(name: String, argumentsJson: String): String {
        val tool = byName[name] ?: return "未知工具：$name"
        return runCatching { tool.execute(argumentsJson) }
            .getOrElse { "工具执行失败：${it.message ?: "未知错误"}" }
    }

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
