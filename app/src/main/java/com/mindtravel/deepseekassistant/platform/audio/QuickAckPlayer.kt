package com.mindtravel.deepseekassistant.platform.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.events.EventSink
import java.util.Locale

class QuickAckPlayer(
    context: Context,
    private val emitter: EventSink
) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)

    @Volatile
    private var ready = false

    @Volatile
    private var failed = false

    @Volatile
    private var pendingText: String? = null

    @Volatile
    private var notifyWakeDone = false

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()
            override fun onError(utteranceId: String?, errorCode: Int) = finish()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish()
        })
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            Logs.add("Quick ack TTS init failed status=$status")
            if (pendingText != null) finish()
            return
        }
        val language = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        if (language == TextToSpeech.LANG_MISSING_DATA ||
            language == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            failed = true
            Logs.add("Quick ack Chinese TTS unavailable language=$language")
            if (pendingText != null) finish()
            return
        }
        ready = true
        if (pendingText != null) speak()
    }

    fun playWakeAck(): Boolean = play("我在", notifyWake = true)

    fun playAck(text: String): Boolean = play(text, notifyWake = false)

    private fun play(text: String, notifyWake: Boolean): Boolean {
        if (failed) {
            if (notifyWake) emitter.emit("onWakeAckDone", "")
            return false
        }
        pendingText = text
        notifyWakeDone = notifyWake
        if (ready) speak()
        return true
    }

    fun stop() {
        pendingText = null
        runCatching { tts.stop() }
    }

    fun shutdown() {
        pendingText = null
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private fun speak() {
        val text = pendingText ?: return
        pendingText = null
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quick_ack")
        if (result != TextToSpeech.SUCCESS) finish()
    }

    private fun finish() {
        if (notifyWakeDone) {
            notifyWakeDone = false
            emitter.emit("onWakeAckDone", "")
        }
    }
}
