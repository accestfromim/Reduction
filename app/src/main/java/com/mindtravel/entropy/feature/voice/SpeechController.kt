package com.mindtravel.entropy.feature.voice

import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.core.events.EventSink
import com.mindtravel.entropy.core.settings.SettingsGateway
import com.mindtravel.entropy.model.voice.TtsCache

/**
 * Owns what is being spoken. One utterance at a time, identified by an id that the
 * UI sees in onTtsStart / onTtsDone / onTtsError / onTtsStopped.
 */
class SpeechController(
    private val settingsRepository: SettingsGateway,
    private val streamer: ReplyStreamer,
    private val cache: TtsCache,
    private val emitter: EventSink
) {
    private val lock = Any()
    private var activeId: String? = null
    private var onEnd: (() -> Unit)? = null
    private var streaming = false

    /**
     * Reserves speech for a streamed reply. Text follows through [push]; [close] marks the end.
     * [onEnd] runs once when this reply has nothing more to say, whether it was spoken or not.
     */
    fun open(id: String, onEnd: () -> Unit) {
        stop(notify = false)
        synchronized(lock) {
            activeId = id
            this.onEnd = onEnd
            streaming = false
        }
    }

    fun push(id: String, text: String) {
        synchronized(lock) {
            if (id != activeId) return
            if (!streaming) {
                val settings = settingsRepository.load()
                if (!settings.autoSpeak || settings.apiKey.isBlank()) return
                begin(id, settings.apiKey, settings.voice)
            }
            streamer.push(text)
        }
    }

    /** Drops what was said so far in this reply; text pushed afterwards is spoken afresh. */
    fun restart(id: String) {
        synchronized(lock) {
            if (id != activeId || !streaming) return
            streamer.stop()
            streaming = false
        }
    }

    fun close(id: String) {
        val spoken = synchronized(lock) {
            if (id != activeId) return
            if (streaming) streamer.finish()
            streaming
        }
        if (!spoken) end(id)
    }

    /** Reads [text] aloud regardless of the auto-speak setting. */
    fun speak(id: String, text: String) {
        stop(notify = false)
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onTtsError", id, "请先在设置里填写 API Key")
            return
        }
        synchronized(lock) {
            activeId = id
            onEnd = null
            begin(id, settings.apiKey, settings.voice)
            streamer.push(text)
            streamer.finish()
        }
    }

    /** Silences the current utterance. [notify] tells the UI the user asked for it. */
    fun stop(notify: Boolean) {
        if (activeId != null) Logs.add("Speech stop requested notify=$notify")
        val id = synchronized(lock) {
            streamer.stop()
            streaming = false
            activeId
        }
        if (id != null) end(id)
        if (notify) emitter.emit("onTtsStopped", id.orEmpty())
    }

    fun clearCache(): String {
        val bytes = cache.clear()
        Logs.add("TTS cache cleared bytes=$bytes")
        return "已清理 %.1f MB 音频缓存".format(bytes / 1024.0 / 1024.0)
    }

    fun cacheSizeMb(): Double = cache.size() / 1024.0 / 1024.0

    fun dispose() = stop(notify = false)

    private fun begin(id: String, apiKey: String, voice: String) {
        Logs.add("TTS stream start voice=$voice")
        streaming = true
        streamer.start(apiKey, voice, object : ReplyStreamer.Events {
            override fun onStart() {
                if (isActive(id)) emitter.emit("onTtsStart", id)
            }

            override fun onDone() {
                if (isActive(id)) emitter.emit("onTtsDone", id)
                end(id)
            }

            override fun onError(message: String) {
                if (isActive(id)) emitter.emit("onTtsError", id, message)
                end(id)
            }
        })
    }

    private fun isActive(id: String): Boolean = synchronized(lock) { id == activeId }

    private fun end(id: String) {
        val callback = synchronized(lock) {
            if (id != activeId) return
            activeId = null
            streaming = false
            onEnd.also { onEnd = null }
        }
        callback?.invoke()
    }
}
