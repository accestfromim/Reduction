package com.mindtravel.deepseekassistant.feature.voice.model

interface TtsEngine {
    fun startSession(
        apiKey: String,
        voice: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    )

    fun append(text: String)
    fun finish()
    fun stop()
    fun clearCache(): String
    fun cacheSizeMb(): Double
}
