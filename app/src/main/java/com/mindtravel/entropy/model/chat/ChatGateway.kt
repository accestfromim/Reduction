package com.mindtravel.entropy.model.chat

interface ChatGateway {
    fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit)
}
