package com.mindtravel.entropy.feature.chat.model

interface ChatCallbacks {
    fun onToken(text: String)
    fun onToolStarted(name: String, arguments: String)
    fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String)
    fun onCompleted(text: String)
    fun onFailed(message: String)
}
