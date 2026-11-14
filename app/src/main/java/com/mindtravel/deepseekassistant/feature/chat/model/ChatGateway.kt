package com.mindtravel.deepseekassistant.feature.chat.model

interface ChatGateway {
    fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit)
}
