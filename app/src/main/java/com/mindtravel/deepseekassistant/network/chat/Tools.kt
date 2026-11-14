package com.mindtravel.deepseekassistant.network.chat

import com.mindtravel.deepseekassistant.calculator.Calculator
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.network.search.SearchApi
import org.json.JSONObject

interface ChatTool {
    val spec: ToolSpec
    fun execute(argumentsJson: String): String
}

class ToolRegistry(tools: List<ChatTool>) {
    private val byName = tools.associateBy { it.spec.name }

    fun specs(): List<ToolSpec> = byName.values.map { it.spec }

    fun execute(name: String, argumentsJson: String): String {
        val tool = byName[name] ?: return "未知工具：$name"
        return runCatching { tool.execute(argumentsJson) }
            .getOrElse { "工具执行失败：${it.message ?: "未知错误"}" }
    }
}

class WebSearchTool(
    private val searchApi: SearchApi,
    private val apiKey: String
) : ChatTool {
    override val spec = ToolSpec(
        name = "web_search",
        description = "搜索实时网络信息。用于新闻、价格、天气、最新事实等可能变化的信息。",
        parameterName = "query",
        parameterDescription = "搜索关键词，1 到 128 个字符"
    )

    override fun execute(argumentsJson: String): String {
        val query = JSONObject(argumentsJson).stringOrNull("query")?.trim().orEmpty()
        if (query.isBlank()) return "搜索失败：query 为空"
        val results = searchApi.search(apiKey, query)
        if (results.isEmpty()) return "没有找到相关结果"
        return buildString {
            appendLine("搜索结果（回答时请保留来源网址）：")
            results.forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.title}")
                appendLine(item.url)
                appendLine(item.summary)
                appendLine()
            }
        }.take(8_000)
    }
}

class CalculatorTool : ChatTool {
    override val spec = ToolSpec(
        name = "calculator",
        description = "精确计算数学表达式。支持 + - * / % ^、括号、sqrt、sin、cos、tan、log、ln、abs、pi、e；三角函数使用弧度。",
        parameterName = "expression",
        parameterDescription = "要计算的数学表达式"
    )

    override fun execute(argumentsJson: String): String {
        val expression = JSONObject(argumentsJson).stringOrNull("expression")?.trim().orEmpty()
        if (expression.isBlank()) return "计算失败：expression 为空"
        return Calculator.evaluate(expression)
    }
}
