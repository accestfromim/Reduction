package com.mindtravel.deepseekassistant.platform.audio

import android.media.MediaRecorder
import com.mindtravel.deepseekassistant.core.Logs

class BargeInDetector(
    private val onBargeIn: () -> Unit,
    private val onError: (String) -> Unit
) {

    private val capture = PcmCapture()
    private var detected = false

    fun start() {
        if (capture.isRunning()) return
        detected = false
        val vad = SpeechVad(waitTimeoutMs = 10 * 60_000L).apply { reset() }
        Logs.add("Barge-in listening threshold=${vad.threshold().toInt()}")

        capture.start(
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            onFrame = { frame ->
                if (!detected && vad.process(frame) == SpeechVad.Event.SPEECH_START) {
                    detected = true
                    Logs.add("Barge-in detected threshold=${vad.threshold().toInt()}")
                    capture.requestStop()
                    onBargeIn()
                }
            },
            onError = { message ->
                if (!detected) onError(message)
            }
        )
    }

    fun stop() {
        capture.stop()
    }

    fun isRunning(): Boolean = capture.isRunning()
}
