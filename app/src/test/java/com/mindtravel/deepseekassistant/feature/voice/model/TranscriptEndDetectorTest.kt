package com.mindtravel.deepseekassistant.feature.voice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEndDetectorTest {

    @Test
    fun `ends on over and strips it`() {
        val detector = TranscriptEndDetector()
        val decision = detector.evaluate("打开设置 over", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("打开设置", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `over is case insensitive and allows spaces`() {
        val detector = TranscriptEndDetector()
        val decision = detector.evaluate("Open settings, O V E R.", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("Open settings", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `ends on Chinese end word`() {
        val detector = TranscriptEndDetector()
        val decision = detector.evaluate("打开设置，完毕。", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("打开设置", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `only end word produces empty text`() {
        val detector = TranscriptEndDetector()
        val decision = detector.evaluate("over", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `same text without end word does not end`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", false) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开设置", false) is TranscriptEndDetector.Decision.Continue)
    }

    @Test
    fun `stale result with new voice does not end`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开设置 over", true) is TranscriptEndDetector.Decision.Continue)
    }

    @Test
    fun `two empty results without voice end the utterance`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", false) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("", false) is TranscriptEndDetector.Decision.Continue)
        val decision = detector.evaluate("", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("打开设置", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `empty result with voice does not end`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", false) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("", true) is TranscriptEndDetector.Decision.Continue)
    }
}
