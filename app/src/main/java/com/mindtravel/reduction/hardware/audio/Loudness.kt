package com.mindtravel.reduction.hardware.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Brings pieces of sound to one loudness, so that speech and cues can follow each other
 * without the volume having to be touched. Works on 16-bit mono PCM.
 *
 * Loudness here is the level of the sounding parts only: pauses do not make a piece
 * count as quieter than it is.
 */
object Loudness {
    /** What everything is brought to: about -20 dB below full scale. */
    const val TARGET = 3_277.0

    private const val FRAME_SAMPLES = 480
    private const val SILENCE = 200.0
    private const val MAX_GAIN = 4.0
    private const val MIN_GAIN = 0.25
    private const val PEAK_LIMIT = 31_000.0

    /** The level of the sounding parts of [pcm]; 0 when there are none. */
    fun level(pcm: ByteArray): Double {
        var sum = 0.0
        var count = 0L
        var at = 0
        val samples = pcm.size / 2
        while (at < samples) {
            val end = minOf(at + FRAME_SAMPLES, samples)
            var frame = 0.0
            for (i in at until end) {
                val value = sample(pcm, i).toDouble()
                frame += value * value
            }
            if (sqrt(frame / (end - at)) > SILENCE) {
                sum += frame
                count += end - at
            }
            at = end
        }
        return if (count == 0L) 0.0 else sqrt(sum / count)
    }

    /** [pcm] at the [target] loudness, never so loud that it clips. Silence is returned as it is. */
    fun leveled(pcm: ByteArray, target: Double = TARGET): ByteArray {
        val level = level(pcm)
        if (level <= 0.0) return pcm
        var peak = 1
        for (i in 0 until pcm.size / 2) peak = maxOf(peak, abs(sample(pcm, i)))
        val gain = (target / level).coerceIn(MIN_GAIN, MAX_GAIN).coerceAtMost(PEAK_LIMIT / peak)
        if (abs(gain - 1.0) < 0.03) return pcm
        val out = ByteArray(pcm.size)
        for (i in 0 until pcm.size / 2) {
            val value = (sample(pcm, i) * gain).toInt().coerceIn(-32_768, 32_767)
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        if (pcm.size % 2 == 1) out[pcm.size - 1] = pcm[pcm.size - 1]
        return out
    }

    /** [pcm] without the quiet before its first sound and after its last, keeping a short margin. */
    fun trimmed(pcm: ByteArray, sampleRate: Int): ByteArray {
        val samples = pcm.size / 2
        var first = -1
        var last = -1
        for (i in 0 until samples) {
            if (abs(sample(pcm, i)) > SILENCE * 2) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return ByteArray(0)
        val from = maxOf(0, first - sampleRate / 50)
        val to = minOf(samples, last + sampleRate / 12)
        return pcm.copyOfRange(from * 2, to * 2)
    }

    private fun sample(pcm: ByteArray, index: Int): Int =
        (pcm[index * 2].toInt() and 0xff) or (pcm[index * 2 + 1].toInt() shl 8)
}
