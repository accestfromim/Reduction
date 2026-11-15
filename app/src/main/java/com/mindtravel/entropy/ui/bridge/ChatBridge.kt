package com.mindtravel.entropy.ui.bridge

import com.mindtravel.entropy.feature.chat.ui.Conversation

class ChatBridge(private val conversation: Conversation) {
    fun submit(text: String): Boolean = conversation.submit(text)
}
