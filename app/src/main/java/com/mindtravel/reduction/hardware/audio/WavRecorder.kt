package com.mindtravel.reduction.hardware.audio

import android.media.MediaRecorder
import com.mindtravel.reduction.core.Logs
import java.io.ByteArrayOutputStream

class WavRecorder {

    companion object {
        private const val MAX_SECONDS = 60
    }

    private val capture = PcmCapture()
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var recording = false

    private var startedAt = 0L

    fun start(useCommunicationSource: Boolean = false, onLimit: () -> Unit): Boolean {
        if (recording) return true
        synchronized(pcm) { pcm.reset() }
        startedAt = System.currentTimeMillis()
        recording = true

        val started = capture.start(
            source = if (useCommunicationSource) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.MIC
            },
            onFrame = { frame ->
                if (recording) {
                    synchronized(pcm) { writeShortArray(pcm, frame) }
                    if ((System.currentTimeMillis() - startedAt) / 1000 >= MAX_SECONDS) {
                        recording = false
                        capture.requestStop()
                        onLimit()
                    }
                }
            },
            onError = { message ->
                recording = false
                Logs.add("Record error: $message")
            }
        )
        if (!started) recording = false
        return started
    }

    fun stop(): ByteArray? {
        if (!recording && !capture.isRunning()) return null
        recording = false
        capture.stop()
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    fun cancel() {
        recording = false
        capture.cancel()
        synchronized(pcm) { pcm.reset() }
    }

    fun isRecording(): Boolean = recording

    private fun writeShortArray(out: ByteArrayOutputStream, frame: ShortArray) {
        for (sample in frame) {
            val value = sample.toInt()
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
    }
}
