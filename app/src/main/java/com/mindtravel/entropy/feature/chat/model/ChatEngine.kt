package com.mindtravel.entropy.feature.chat.model

import com.mindtravel.entropy.feature.chat.model.tools.ToolRegistry
import com.mindtravel.entropy.model.chat.ChatRequest

interface ChatEngine {
    fun run(
        request: ChatRequest,
        tools: ToolRegistry,
        callbacks: ChatCallbacks
    )
}
