package com.mindtravel.entropy.model.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVoiceDetectorTest {

    private fun frame(amplitude: Int) = ShortArray(1600) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    @Test
    fun quietFramesAreNotSpeechAndLoudOnesAre() {
        val detector = EnergyVoiceDetector()
        repeat(5) { assertFalse(detector.isSpeech(frame(100))) }
        assertTrue(detector.isSpeech(frame(4000)))
    }

    @Test
    fun aSteadyBackgroundRaisesTheFloor() {
        val detector = EnergyVoiceDetector()
        repeat(40) { detector.isSpeech(frame(400)) }
        assertFalse("a level just above the steady background is not speech", detector.isSpeech(frame(600)))
        assertTrue(detector.isSpeech(frame(4000)))
    }
}
