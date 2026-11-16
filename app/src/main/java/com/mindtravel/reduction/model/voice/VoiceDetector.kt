package com.mindtravel.reduction.model.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.model.NativeModels
import kotlin.math.max
import kotlin.math.sqrt

/** Says, frame by frame, whether someone is speaking. Feed it 16 kHz mono PCM from one thread. */
interface VoiceDetector {
    fun reset()
    fun isSpeech(frame: ShortArray): Boolean
    fun release()

    companion object {
        /** The neural detector when its model loads, otherwise the loudness-based one. */
        fun create(context: Context): VoiceDetector = try {
            check(NativeModels.available) { "this device is not arm64" }
            SileroVoiceDetector(context).also { Logs.add("Voice detector: silero") }
        } catch (t: Throwable) {
            Logs.add("Voice detector: energy (silero unavailable: ${t.message})")
            EnergyVoiceDetector()
        }
    }
}

/** Silero VAD: a small neural network that tells speech from noise, music and hum. */
class SileroVoiceDetector(context: Context) : VoiceDetector {

    private val vad = Vad(
        assetManager = context.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.2f,
                windowSize = 512,
                maxSpeechDuration = 120f
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )
    )

    override fun reset() {
        vad.reset()
        vad.clear()
    }

    override fun isSpeech(frame: ShortArray): Boolean {
        vad.acceptWaveform(FloatArray(frame.size) { frame[it] / 32768.0f })
        // Finished segments are not needed; only the live verdict is.
        while (!vad.empty()) vad.pop()
        return vad.isSpeechDetected()
    }

    override fun release() {
        runCatching { vad.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** Loudness against an adaptive noise floor. Used only when the neural detector cannot load. */
class EnergyVoiceDetector(
    private val minThreshold: Double = 450.0,
    private val noiseMultiplier: Double = 2.2
) : VoiceDetector {

    private var noise = INITIAL_NOISE

    override fun reset() {
        noise = INITIAL_NOISE
    }

    override fun isSpeech(frame: ShortArray): Boolean {
        val level = rms(frame)
        val speech = level > max(minThreshold, noise * noiseMultiplier)
        if (!speech) noise = noise * 0.85 + level * 0.15
        return speech
    }

    override fun release() = Unit

    private fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in frame) {
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / frame.size)
    }

    private companion object {
        const val INITIAL_NOISE = 300.0
    }
}
