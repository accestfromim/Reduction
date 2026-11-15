package com.mindtravel.deepseekassistant.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeechVadTest {

    @Test
    fun `reports a short pause before speech end`() {
        val vad = SpeechVad(waitTimeoutMs = 600_000L).apply { reset(0) }
        val loud = ShortArray(1_600) { 3_000 }
        val silence = ShortArray(1_600)

        assertEquals(SpeechVad.Event.WAITING, vad.process(loud, 100))
        assertEquals(SpeechVad.Event.SPEECH_START, vad.process(loud, 200))
        repeat(4) { index ->
            assertNotEquals(SpeechVad.Event.SPEECH_PAUSE, vad.process(silence, 300L + index * 100))
        }
        assertEquals(SpeechVad.Event.SPEECH_PAUSE, vad.process(silence, 700))
    }

    @Test
    fun `speech ends after one second of silence`() {
        val vad = SpeechVad(waitTimeoutMs = 600_000L).apply { reset(0) }
        val loud = ShortArray(1_600) { 3_000 }
        val silence = ShortArray(1_600)

        assertEquals(SpeechVad.Event.WAITING, vad.process(loud, 100))
        assertEquals(SpeechVad.Event.SPEECH_START, vad.process(loud, 200))

        repeat(9) { index ->
            assertNotEquals(SpeechVad.Event.SPEECH_END, vad.process(silence, 300L + index * 100))
        }
        assertEquals(SpeechVad.Event.SPEECH_END, vad.process(silence, 300L + 9 * 100))
    }
}
