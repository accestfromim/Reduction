package com.mindtravel.reduction.hardware.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LoudnessTest {

    private fun tone(amplitude: Int, samples: Int = 4_800): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (amplitude * sin(2 * PI * 440 * i / 24_000)).toInt()
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun silence(samples: Int) = ByteArray(samples * 2)

    private fun peak(pcm: ByteArray): Int =
        (0 until pcm.size / 2).maxOf { abs((pcm[it * 2].toInt() and 0xff) or (pcm[it * 2 + 1].toInt() shl 8)) }

    @Test
    fun aQuietPieceAndALoudPieceComeOutEquallyLoud() {
        val quiet = Loudness.level(Loudness.leveled(tone(1_500)))
        val loud = Loudness.level(Loudness.leveled(tone(12_000)))
        assertEquals(Loudness.TARGET, quiet, Loudness.TARGET * 0.03)
        assertEquals(Loudness.TARGET, loud, Loudness.TARGET * 0.03)
    }

    @Test
    fun pausesDoNotMakeAPieceCountAsQuieter() {
        val spoken = tone(4_000)
        val withPauses = silence(9_600) + spoken + silence(9_600)
        assertEquals(Loudness.level(spoken), Loudness.level(withPauses), 1.0)
    }

    @Test
    fun aVeryQuietPieceIsRaisedOnlySoFarAndNeverClips() {
        assertEquals(4.0, Loudness.level(Loudness.leveled(tone(300))) / Loudness.level(tone(300)), 0.05)
        // Mostly quiet with one loud click: the click sets the limit.
        val spiky = tone(700) + tone(30_000, samples = 480) + tone(700)
        assertTrue(peak(Loudness.leveled(spiky)) <= 31_000)
    }

    @Test
    fun silenceIsLeftAsItIs() {
        val quiet = silence(2_400)
        assertArrayEquals(quiet, Loudness.leveled(quiet))
        assertEquals(0, Loudness.trimmed(quiet, 24_000).size)
    }

    @Test
    fun trimmingKeepsTheSoundAndAShortMargin() {
        val sound = tone(6_000, samples = 7_200)
        val trimmed = Loudness.trimmed(silence(12_000) + sound + silence(24_000), 24_000)
        val samples = trimmed.size / 2
        assertTrue("kept $samples samples", samples in 7_200..(7_200 + 480 + 2_000 + 100))
        assertEquals(Loudness.level(sound), Loudness.level(trimmed), 60.0)
    }
}
