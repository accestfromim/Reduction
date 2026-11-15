package com.mindtravel.deepseekassistant.feature.voice.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.feature.voice.model.TtsEngine
import com.mindtravel.deepseekassistant.core.events.EventSink

class TtsController(
    private val settingsRepository: SettingsGateway,
    private val ttsEngine: TtsEngine,
    private val emitter: EventSink
) {

    @Volatile
    private var activeTtsId: String? = null

    fun ttsStart(id: String) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onTtsError", id, "请先在设置里填写 API Key")
            return
        }
        Logs.add("TTS stream start voice=${settings.voice}")
        activeTtsId = id
        ttsEngine.startSession(
            apiKey = settings.apiKey,
            voice = settings.voice,
            onStart = { emitter.emit("onTtsStart", id) },
            onDone = { emitter.emit("onTtsDone", id) },
            onError = { message -> emitter.emit("onTtsError", id, message) }
        )
    }

    fun ttsAppend(id: String, text: String) {
        if (id != activeTtsId || text.isBlank()) return
        ttsEngine.append(text)
    }

    fun ttsFinish(id: String, text: String) {
        if (id != activeTtsId) return
        if (text.isNotBlank()) ttsEngine.append(text)
        ttsEngine.finish()
        activeTtsId = null
    }

    fun stopSpeaking() {
        activeTtsId = null
        ttsEngine.stop()
        emitter.emit("onTtsStopped", "")
    }

    fun clearAudioCache(): String = ttsEngine.clearCache()

    fun cacheSizeMb(): Double = ttsEngine.cacheSizeMb()

    fun dispose() {
        ttsEngine.stop()
    }
}
