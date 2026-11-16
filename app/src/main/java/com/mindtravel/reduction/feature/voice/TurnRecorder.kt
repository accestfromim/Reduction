package com.mindtravel.reduction.feature.voice

import android.media.MediaRecorder
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.PcmCapture
import com.mindtravel.reduction.hardware.audio.WavEncoder
import com.mindtravel.reduction.model.voice.VoiceDetector
import com.mindtravel.reduction.model.wake.KeywordSpotterModel
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Records one spoken turn.
 *
 * The microphone is warmed up ahead of time — opened, with its audio thrown away — so
 * that [start] begins recording on the very next frame instead of waiting for the
 * device. From then on everything is kept, and each frame is judged on the device: is
 * someone speaking, was the end word said, is the turn over.
 */
class TurnRecorder(
    private val voice: VoiceDetector,
    private val endWordSpotter: KeywordSpotterModel
) {
    private class Turn(
        val onSpeechStart: () -> Unit,
        val onAlive: () -> Unit,
        val onEnd: (reason: String) -> Unit,
        val onTimeout: () -> Unit,
        val onError: (String) -> Unit,
        val spotting: Boolean
    ) {
        var frames = 0
        var over = false
    }

    private val capture = PcmCapture()
    private val pcm = ByteArrayOutputStream()
    private val endpointer = TurnEndpointer()

    /** The turn being recorded. Null while warming up: frames are discarded. */
    @Volatile
    private var turn: Turn? = null

    @Volatile
    private var speechStarted = false
    private var spotting = false
    private var warmedAt = 0L
    private var firstAudio = CountDownLatch(1)

    /**
     * Opens the microphone without recording yet and gets the detectors ready.
     * Safe to call when already warm.
     */
    @Synchronized
    fun warmUp(useCommunicationSource: Boolean): Boolean {
        if (capture.isRunning()) return true
        turn = null
        voice.reset()
        spotting = try {
            endWordSpotter.open(END_WORDS)
            true
        } catch (t: Throwable) {
            Logs.add("End word spotter unavailable: ${t.message}")
            false
        }
        firstAudio = CountDownLatch(1)
        warmedAt = System.currentTimeMillis()
        val arrived = firstAudio
        val started = capture.start(
            source = if (useCommunicationSource) {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            },
            onFrame = { frame ->
                if (arrived.count > 0) {
                    Logs.add("Microphone: first audio ${System.currentTimeMillis() - warmedAt}ms after opening")
                    arrived.countDown()
                }
                turn?.let { record(it, frame) }
            },
            onError = { message ->
                val current = turn
                if (current != null && !current.over) current.onError(message) else Logs.add("Microphone error: $message")
            }
        )
        if (!started) Logs.add("Microphone could not be opened")
        return started
    }

    /** Waits until the microphone has delivered audio. False when it has not within [timeoutMs]. */
    fun awaitAudio(timeoutMs: Long): Boolean = firstAudio.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Starts recording a turn with the next frame. Warms up first if that has not been done. */
    fun start(
        onSpeechStart: () -> Unit,
        onAlive: () -> Unit,
        /** The turn ended by itself: the end word, or a long silence. */
        onEnd: (reason: String) -> Unit,
        /** Nobody spoke. */
        onTimeout: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val warm = capture.isRunning()
        if (!warm && !warmUp(useCommunicationSource = false)) {
            onError("无法启动录音")
            return false
        }
        speechStarted = false
        synchronized(pcm) { pcm.reset() }
        endpointer.reset(System.currentTimeMillis())
        Logs.add("Turn recording start warm=$warm endWordSpotter=$spotting")
        turn = Turn(onSpeechStart, onAlive, onEnd, onTimeout, onError, spotting)
        return true
    }

    /** The audio so far, once someone has spoken. */
    fun snapshotWav(): ByteArray? {
        if (!speechStarted) return null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    /** Stops and returns the recording, or null when nobody spoke. */
    fun stop(): ByteArray? {
        turn = null
        capture.stop()
        endWordSpotter.close()
        val data = synchronized(pcm) { pcm.toByteArray() }
        val hadSpeech = speechStarted
        speechStarted = false
        if (!hadSpeech || data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    fun hasVoiceSince(timestampMs: Long): Boolean = endpointer.lastVoiceAt > timestampMs

    fun cancel() {
        turn = null
        capture.cancel()
        endWordSpotter.close()
        synchronized(pcm) { pcm.reset() }
        speechStarted = false
    }

    /** Runs on the capture thread, once per 100 ms frame. */
    private fun record(turn: Turn, frame: ShortArray) {
        if (turn.over) return
        writeFrame(frame)
        // Frames arriving is the proof that recording is alive.
        if (turn.frames++ % ALIVE_EVERY_FRAMES == 0) turn.onAlive()

        val speech = voice.isSpeech(frame)
        val endWord = turn.spotting && endWordSpotter.accept(frame, PcmCapture.SAMPLE_RATE) != null
        when (endpointer.onFrame(speech, endWord, System.currentTimeMillis())) {
            TurnEndpointer.Decision.CONTINUE -> Unit
            TurnEndpointer.Decision.SPEECH_STARTED -> {
                speechStarted = true
                Logs.add("Turn: speech started")
                turn.onSpeechStart()
            }
            TurnEndpointer.Decision.NO_SPEECH -> {
                turn.over = true
                Logs.add("Turn: nobody spoke")
                capture.requestStop()
                turn.onTimeout()
            }
            TurnEndpointer.Decision.END_BY_WORD -> {
                turn.over = true
                Logs.add("Turn: end word heard")
                turn.onEnd("end word")
            }
            TurnEndpointer.Decision.END_BY_SILENCE -> {
                turn.over = true
                Logs.add("Turn: long silence")
                turn.onEnd("silence")
            }
        }
    }

    private fun writeFrame(frame: ShortArray) {
        synchronized(pcm) {
            for (sample in frame) {
                val value = sample.toInt()
                pcm.write(value and 0xff)
                pcm.write((value shr 8) and 0xff)
            }
        }
    }

    private companion object {
        /** Frames are 100 ms: an alive signal with the first frame, then twice a second. */
        const val ALIVE_EVERY_FRAMES = 5

        /** Keyword spotter syntax: the syllables of "完毕". */
        const val END_WORDS = "w án b ì @完毕"
    }
}
