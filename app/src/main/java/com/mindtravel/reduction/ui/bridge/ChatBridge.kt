package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.feature.chat.ui.Conversation

class ChatBridge(private val conversation: Conversation) {
    fun submit(text: String): Boolean = conversation.submit(text)
}
