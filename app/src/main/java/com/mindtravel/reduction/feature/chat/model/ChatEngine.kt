package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.feature.chat.model.tools.ToolRegistry
import com.mindtravel.reduction.model.chat.ChatRequest

interface ChatEngine {
    fun run(
        request: ChatRequest,
        tools: ToolRegistry,
        callbacks: ChatCallbacks
    )
}
