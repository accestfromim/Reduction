package com.mindtravel.entropy.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnDetectorTest {

    @Test
    fun `ends on over and strips it`() {
        val detector = TurnDetector()
        val decision = detector.evaluate("打开设置 over", false)
        assertTrue(decision is TurnDetector.Decision.End)
        assertEquals("打开设置", (decision as TurnDetector.Decision.End).text)
    }

    @Test
    fun `over is case insensitive and allows spaces`() {
        val detector = TurnDetector()
        val decision = detector.evaluate("Open settings, O V E R.", false)
        assertTrue(decision is TurnDetector.Decision.End)
        assertEquals("Open settings", (decision as TurnDetector.Decision.End).text)
    }

    @Test
    fun `ends on Chinese end word`() {
        val detector = TurnDetector()
        val decision = detector.evaluate("打开设置，完毕。", false)
        assertTrue(decision is TurnDetector.Decision.End)
        assertEquals("打开设置", (decision as TurnDetector.Decision.End).text)
    }

    @Test
    fun `only end word produces empty text`() {
        val detector = TurnDetector()
        val decision = detector.evaluate("over", false)
        assertTrue(decision is TurnDetector.Decision.End)
        assertEquals("", (decision as TurnDetector.Decision.End).text)
    }

    @Test
    fun `same text without end word does not end`() {
        val detector = TurnDetector()
        assertTrue(detector.evaluate("打开设置", false) is TurnDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开设置", false) is TurnDetector.Decision.Continue)
    }

    @Test
    fun `stale result with new voice does not end`() {
        val detector = TurnDetector()
        assertTrue(detector.evaluate("打开设置", true) is TurnDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开设置 over", true) is TurnDetector.Decision.Continue)
    }

    @Test
    fun `two empty results without voice end the utterance`() {
        val detector = TurnDetector()
        assertTrue(detector.evaluate("打开设置", false) is TurnDetector.Decision.Continue)
        assertTrue(detector.evaluate("", false) is TurnDetector.Decision.Continue)
        val decision = detector.evaluate("", false)
        assertTrue(decision is TurnDetector.Decision.End)
        assertEquals("打开设置", (decision as TurnDetector.Decision.End).text)
    }

    @Test
    fun `empty result with voice does not end`() {
        val detector = TurnDetector()
        assertTrue(detector.evaluate("打开设置", false) is TurnDetector.Decision.Continue)
        assertTrue(detector.evaluate("", true) is TurnDetector.Decision.Continue)
        assertTrue(detector.evaluate("", true) is TurnDetector.Decision.Continue)
    }
}
