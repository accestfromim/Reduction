package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.core.settings.AppSettings
import com.mindtravel.deepseekassistant.feature.chat.model.SearchGateway

class ChatToolFactory(
    private val searchGateway: SearchGateway
) {
    fun create(settings: AppSettings): ToolRegistry {
        val tools = buildList {
            if (settings.webSearchEnabled) {
                add(WebSearchTool(searchGateway, settings.apiKey))
            }
            if (settings.calculatorEnabled) {
                add(CalculatorTool())
            }
        }
        return ToolRegistry(tools)
    }
}
