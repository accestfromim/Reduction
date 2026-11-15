package com.mindtravel.deepseekassistant.platform.audio

import android.media.MediaRecorder
import com.mindtravel.deepseekassistant.core.Logs
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

class HandsFreeRecorder {

    private val capture = PcmCapture()
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var speechStarted = false

    @Volatile
    private var lastVoiceAt = 0L

    fun start(
        followup: Boolean,
        useCommunicationSource: Boolean = false,
        onSpeechStart: () -> Unit,
        onTimeout: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (capture.isRunning()) return false
        speechStarted = false
        lastVoiceAt = 0L
        synchronized(pcm) { pcm.reset() }
        Logs.add("Hands-free start followup=$followup")

        val preRoll = ArrayDeque<ShortArray>()
        val vad = SpeechVad().apply { reset() }
        var timedOut = false

        val started = capture.start(
            source = if (useCommunicationSource) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            },
            onFrame = { frame ->
                val event = vad.process(frame)
                if (!speechStarted) {
                    preRoll.addLast(frame)
                    while (preRoll.size > 3) preRoll.removeFirst()
                    when (event) {
                        SpeechVad.Event.SPEECH_START -> {
                            speechStarted = true
                            lastVoiceAt = System.currentTimeMillis()
                            Logs.add("VAD speech start threshold=${vad.threshold().toInt()}")
                            onSpeechStart()
                            for (item in preRoll) writeShortArray(pcm, item)
                            preRoll.clear()
                        }
                        SpeechVad.Event.TIMEOUT -> {
                            Logs.add("VAD timeout")
                            timedOut = true
                            capture.requestStop()
                            onTimeout()
                        }
                        else -> Unit
                    }
                } else {
                    writeShortArray(pcm, frame)
                    if (vad.lastFrameWasSpeech) lastVoiceAt = System.currentTimeMillis()
                }
            },
            onError = { message ->
                if (!timedOut) onError(message)
            }
        )
        if (!started) {
            Logs.add("Hands-free capture start failed")
            onError("无法启动录音")
        }
        return started
    }

    fun snapshotWav(): ByteArray? {
        if (!speechStarted) return null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    fun stop(): ByteArray? {
        capture.stop()
        val data = synchronized(pcm) { pcm.toByteArray() }
        val hadSpeech = speechStarted
        speechStarted = false
        lastVoiceAt = 0L
        if (!hadSpeech || data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    fun hasVoiceSince(timestampMs: Long): Boolean = lastVoiceAt > timestampMs

    fun cancel() {
        capture.cancel()
        synchronized(pcm) { pcm.reset() }
        speechStarted = false
        lastVoiceAt = 0L
    }

    private fun writeShortArray(out: ByteArrayOutputStream, frame: ShortArray) {
        synchronized(pcm) {
            for (sample in frame) {
                val value = sample.toInt()
                out.write(value and 0xff)
                out.write((value shr 8) and 0xff)
            }
        }
    }
}
