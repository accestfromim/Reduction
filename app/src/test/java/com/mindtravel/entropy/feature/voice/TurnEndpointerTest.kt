package com.mindtravel.entropy.feature.voice

import com.mindtravel.entropy.feature.voice.TurnEndpointer.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnEndpointerTest {

    /** Feeds 100 ms frames. Each char: 's' speech, '.' silence, 'W' the end word. Returns every non-CONTINUE decision with its time. */
    private fun run(frames: String, endpointer: TurnEndpointer = TurnEndpointer()): List<Pair<Long, Decision>> {
        endpointer.reset(0)
        val out = ArrayList<Pair<Long, Decision>>()
        frames.forEachIndexed { index, c ->
            val now = (index + 1) * 100L
            val decision = endpointer.onFrame(speech = c == 's', endWord = c == 'W', nowMs = now)
            if (decision != Decision.CONTINUE) out += now to decision
            // The recorder stops feeding frames once the turn is over.
            if (decision != Decision.CONTINUE && decision != Decision.SPEECH_STARTED) return out
        }
        return out
    }

    @Test
    fun speechIsNoticedOnItsFirstFrame() {
        assertEquals(listOf(600L to Decision.SPEECH_STARTED), run(".....sss"))
    }

    @Test
    fun theEndWordEndsTheTurnAtOnce() {
        val decisions = run("ssssW" + "s".repeat(10))
        assertEquals(500L to Decision.END_BY_WORD, decisions.last())
    }

    @Test
    fun aThinkingPauseDoesNotEndTheTurn() {
        val decisions = run("sss" + ".".repeat(25) + "sss")
        assertEquals(listOf(Decision.SPEECH_STARTED), decisions.map { it.second })
    }

    @Test
    fun aLongSilenceAfterSpeechEndsTheTurn() {
        val decisions = run("sss" + ".".repeat(30))
        assertEquals(3300L to Decision.END_BY_SILENCE, decisions.last())
    }

    @Test
    fun nobodySpeakingGivesUp() {
        val decisions = run(".".repeat(120))
        assertEquals(listOf(12_000L to Decision.NO_SPEECH), decisions.take(1))
    }

    @Test
    fun theEndWordAloneEndsATurn() {
        assertEquals(listOf(300L to Decision.END_BY_WORD), run("..W" + ".".repeat(5)))
    }
}
