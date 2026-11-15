package com.mindtravel.deepseekassistant.platform.audio

import kotlin.math.max
import kotlin.math.sqrt

class SpeechVad(
    private val minThreshold: Double = 450.0,
    private val noiseMultiplier: Double = 2.2,
    private val speechFramesToStart: Int = 2,
    private val waitTimeoutMs: Long = 12_000L
) {

    enum class Event {
        WAITING,
        SPEECH_START,
        SPEECH_CONTINUE,
        TIMEOUT
    }

    private var noise = 300.0
    private var speechStarted = false
    private var speechFrames = 0
    private var waitStartedAt = 0L

    @Volatile
    var lastFrameWasSpeech: Boolean = false
        private set

    fun reset(nowMs: Long = System.currentTimeMillis()) {
        noise = 300.0
        speechStarted = false
        speechFrames = 0
        waitStartedAt = nowMs
        lastFrameWasSpeech = false
    }

    fun threshold(): Double = max(minThreshold, noise * noiseMultiplier)

    fun process(frame: ShortArray, nowMs: Long = System.currentTimeMillis()): Event {
        val rms = rms(frame)
        val threshold = threshold()
        lastFrameWasSpeech = rms > threshold

        if (speechStarted) return Event.SPEECH_CONTINUE
        if (nowMs - waitStartedAt >= waitTimeoutMs) return Event.TIMEOUT

        if (lastFrameWasSpeech) {
            speechFrames++
        } else {
            speechFrames = 0
            noise = noise * 0.85 + rms * 0.15
        }

        if (speechFrames >= speechFramesToStart) {
            speechStarted = true
            return Event.SPEECH_START
        }
        return Event.WAITING
    }

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / frame.size)
    }
}
