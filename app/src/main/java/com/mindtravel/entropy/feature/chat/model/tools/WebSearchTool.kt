package com.mindtravel.entropy.feature.chat.model.tools

import com.mindtravel.entropy.core.stringOrNull
import com.mindtravel.entropy.model.chat.SearchGateway
import com.mindtravel.entropy.model.chat.ToolSpec
import org.json.JSONObject

class WebSearchTool(
    private val searchApi: SearchGateway,
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
            appendLine("搜索结果（引用时使用 [简短标题](URL)）：")
            results.forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.title}")
                appendLine(item.url)
                appendLine(item.summary)
                appendLine()
            }
        }.take(8_000)
    }
}
