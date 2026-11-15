package com.mindtravel.deepseekassistant.feature.voice.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEndDetectorTest {

    @Test
    fun `ends on end word and strips it`() {
        val detector = TranscriptEndDetector()
        val decision = detector.evaluate("打开设置，完毕。", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("打开设置", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `only end word produces empty text`() {
        val detector = TranscriptEndDetector()
        val decision = detector.evaluate("完毕", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `ends when no new characters appear`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", true) is TranscriptEndDetector.Decision.Continue)
        val decision = detector.evaluate("打开设置。", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("打开设置", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `revision does not end immediately`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开系统设置", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开系统设置", false) is TranscriptEndDetector.Decision.End)
    }

    @Test
    fun `two empty results without voice end the utterance`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("", false) is TranscriptEndDetector.Decision.Continue)
        val decision = detector.evaluate("", false)
        assertTrue(decision is TranscriptEndDetector.Decision.End)
        assertEquals("打开设置", (decision as TranscriptEndDetector.Decision.End).text)
    }

    @Test
    fun `stale result with new voice does not end`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开设置", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("打开设置，完毕", true) is TranscriptEndDetector.Decision.Continue)
    }

    @Test
    fun `empty result with voice does not end`() {
        val detector = TranscriptEndDetector()
        assertTrue(detector.evaluate("打开设置", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("", true) is TranscriptEndDetector.Decision.Continue)
        assertTrue(detector.evaluate("", true) is TranscriptEndDetector.Decision.Continue)
    }
}
