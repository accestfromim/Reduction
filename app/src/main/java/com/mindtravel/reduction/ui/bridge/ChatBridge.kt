package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.feature.chat.ui.Conversation

class ChatBridge(private val conversation: Conversation) {
    fun submit(text: String): Boolean = conversation.submit(text)
    fun submit(text: String, picturesJson: String): Boolean = conversation.submitWithPictures(text, picturesJson)
}
