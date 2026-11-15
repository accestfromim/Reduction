package com.mindtravel.deepseekassistant.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechVadTest {

    @Test
    fun `speech start fires after two loud frames`() {
        val vad = SpeechVad(waitTimeoutMs = 600_000L).apply { reset(0) }
        val loud = ShortArray(1_600) { 3_000 }

        assertEquals(SpeechVad.Event.WAITING, vad.process(loud, 100))
        assertEquals(SpeechVad.Event.SPEECH_START, vad.process(loud, 200))
        assertEquals(SpeechVad.Event.SPEECH_CONTINUE, vad.process(loud, 300))
    }

    @Test
    fun `wait timeout fires when nobody speaks`() {
        val vad = SpeechVad(waitTimeoutMs = 1_000L).apply { reset(0) }
        val silence = ShortArray(1_600)
        assertEquals(SpeechVad.Event.WAITING, vad.process(silence, 100))
        assertEquals(SpeechVad.Event.TIMEOUT, vad.process(silence, 1_100))
    }
}
