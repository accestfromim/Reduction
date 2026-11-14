package com.mindtravel.deepseekassistant.feature.chat.model.tools

import com.mindtravel.deepseekassistant.feature.chat.model.ToolSpec

interface ChatTool {
    val spec: ToolSpec
    fun execute(argumentsJson: String): String
}
