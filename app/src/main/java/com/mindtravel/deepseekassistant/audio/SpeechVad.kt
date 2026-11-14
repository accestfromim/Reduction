package com.mindtravel.deepseekassistant.audio

import kotlin.math.max
import kotlin.math.sqrt

class SpeechVad(
    private val minThreshold: Double = 450.0,
    private val noiseMultiplier: Double = 2.2,
    private val speechFramesToStart: Int = 2,
    private val silenceFramesToEnd: Int = 7,
    private val maxSpeechMs: Long = 30_000L,
    private val waitTimeoutMs: Long = 12_000L
) {

    enum class Event {
        WAITING,
        SPEECH_START,
        SPEECH_CONTINUE,
        SPEECH_END,
        TIMEOUT
    }

    private var noise = 300.0
    private var speechStarted = false
    private var speechFrames = 0
    private var silenceFrames = 0
    private var waitStartedAt = 0L
    private var speechStartedAt = 0L

    fun reset(nowMs: Long = System.currentTimeMillis()) {
        noise = 300.0
        speechStarted = false
        speechFrames = 0
        silenceFrames = 0
        waitStartedAt = nowMs
        speechStartedAt = 0L
    }

    fun threshold(): Double = max(minThreshold, noise * noiseMultiplier)

    fun process(frame: ShortArray, nowMs: Long = System.currentTimeMillis()): Event {
        val rms = rms(frame)
        val threshold = threshold()

        if (!speechStarted) {
            if (nowMs - waitStartedAt >= waitTimeoutMs) return Event.TIMEOUT

            if (rms > threshold) {
                speechFrames++
            } else {
                speechFrames = 0
                noise = noise * 0.85 + rms * 0.15
            }

            if (speechFrames >= speechFramesToStart) {
                speechStarted = true
                speechStartedAt = nowMs
                return Event.SPEECH_START
            }
            return Event.WAITING
        }

        if (rms < threshold) {
            silenceFrames++
        } else {
            silenceFrames = 0
        }

        if (silenceFrames >= silenceFramesToEnd) return Event.SPEECH_END
        if (nowMs - speechStartedAt > maxSpeechMs) return Event.SPEECH_END
        return Event.SPEECH_CONTINUE
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
