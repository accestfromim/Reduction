package com.mindtravel.entropy.feature.chat.model.tools

import com.mindtravel.entropy.model.chat.ToolSpec

class ToolRegistry(tools: List<ChatTool>) {
    private val byName = tools.associateBy { it.spec.name }

    fun specs(): List<ToolSpec> = byName.values.map { it.spec }

    fun execute(name: String, argumentsJson: String): String {
        val tool = byName[name] ?: return "未知工具：$name"
        return runCatching { tool.execute(argumentsJson) }
            .getOrElse { "工具执行失败：${it.message ?: "未知错误"}" }
    }
}
