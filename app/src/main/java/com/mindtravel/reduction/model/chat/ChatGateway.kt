package com.mindtravel.reduction.model.chat

interface ChatGateway {
    fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit)
}
