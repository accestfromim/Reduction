package com.mindtravel.entropy.feature.chat.model.tools

import com.mindtravel.entropy.model.chat.ToolSpec

interface ChatTool {
    val spec: ToolSpec
    fun execute(argumentsJson: String): String
}
