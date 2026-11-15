package com.mindtravel.deepseekassistant.ui.bridge

import com.mindtravel.deepseekassistant.feature.chat.ui.ChatController

class ChatBridge(private val chat: ChatController) {
    fun send(id: String, messagesJson: String): Boolean = chat.send(id, messagesJson)
}
