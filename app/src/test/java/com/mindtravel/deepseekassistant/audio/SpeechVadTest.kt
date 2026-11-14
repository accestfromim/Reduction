package com.mindtravel.deepseekassistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeechVadTest {

    @Test
    fun `speech ends only after one and a half seconds of silence`() {
        val vad = SpeechVad(waitTimeoutMs = 600_000L).apply { reset(0) }
        val loud = ShortArray(1_600) { 3_000 }
        val silence = ShortArray(1_600)

        assertEquals(SpeechVad.Event.WAITING, vad.process(loud, 100))
        assertEquals(SpeechVad.Event.SPEECH_START, vad.process(loud, 200))

        repeat(14) { index ->
            assertNotEquals(SpeechVad.Event.SPEECH_END, vad.process(silence, 300L + index * 100))
        }
        assertEquals(SpeechVad.Event.SPEECH_END, vad.process(silence, 300L + 14 * 100))
    }
}
