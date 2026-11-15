package com.mindtravel.deepseekassistant.feature.chat.model

import com.mindtravel.deepseekassistant.feature.chat.model.tools.ToolRegistry

interface ChatEngine {
    fun run(
        request: ChatRequest,
        tools: ToolRegistry,
        callbacks: ChatCallbacks
    )
}
