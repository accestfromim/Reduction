package com.mindtravel.entropy.feature.chat.model

interface ChatCallbacks {
    fun onToken(text: String)
    /** [summary] is the one line shown for this call; [announce] asks for it to be said aloud too. */
    fun onToolStarted(name: String, summary: String, arguments: String, announce: Boolean)
    fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String)
    fun onCompleted(text: String)
    fun onFailed(message: String)
}
