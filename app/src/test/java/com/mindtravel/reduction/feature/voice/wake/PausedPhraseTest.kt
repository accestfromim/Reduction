package com.mindtravel.reduction.feature.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedPhraseTest {

    /** One character per 100 ms frame: '#' is speech, '.' is quiet. The last character is now. */
    private fun shape(text: String) = PausedPhrase.matches(text.map { it == '#' })

    @Test
    fun aShortWordAPauseAndTheNameIsThePhrase() {
        assertTrue(shape("....##.....#######.."))
        assertTrue(shape("....###...#########...."))
    }

    @Test
    fun thePauseCanBeAsLongAsThreeSeconds() {
        assertTrue(shape("..##" + ".".repeat(30) + "########.."))
        assertFalse(shape("..##" + ".".repeat(31) + "########.."))
    }

    @Test
    fun theNameAtTheStartOfWhatIsRememberedStillCounts() {
        assertTrue(shape("##....########..."))
    }

    @Test
    fun aDipInsideTheNameDoesNotSplitIt() {
        assertTrue(shape("...##....####.####.."))
    }

    @Test
    fun theNameAloneIsNotThePhrase() {
        assertFalse(shape("............########.."))
        assertFalse(shape("########.."))
    }

    @Test
    fun theNameInsideASentenceIsNotThePhrase() {
        assertFalse(shape("..####################.."))
        assertFalse(shape("..##########....########.."))
    }

    @Test
    fun aWordThatEndsASentenceBeforeThePauseIsNotThePhrase() {
        assertFalse(shape("#########.###....########.."))
    }

    @Test
    fun aSentenceAfterAShortWordIsNotTheName() {
        assertFalse(shape("...##....####################.."))
    }

    @Test
    fun theJoinedPhraseIsLeftToTheKeywordModel() {
        assertFalse(shape("....##.########.."))
    }

    @Test
    fun nothingSaidOrStillTalkingIsNotThePhrase() {
        assertFalse(shape(""))
        assertFalse(shape("...................."))
        assertFalse(shape("..##....######" + ".".repeat(11)))
    }

    @Test
    fun speechIsJudgedAgainstTheBackground() {
        val quietRoom = listOf(20.0, 30.0, 25.0, 900.0, 40.0)
        assertEquals(listOf(false, false, false, true, false), PausedPhrase.speechFlags(quietRoom))
        // A steady hum is background, not speech; only what stands well above it counts.
        val noisyRoom = listOf(600.0, 620.0, 590.0, 2500.0, 610.0, 1500.0)
        assertEquals(listOf(false, false, false, true, false, false), PausedPhrase.speechFlags(noisyRoom))
    }

    @Test
    fun framesOfAudioGiveTheSameAnswerAsTheirShape() {
        val phrase = PausedPhrase()
        val quiet = ShortArray(1600) { ((it % 7) - 3).toShort() }
        val loud = ShortArray(1600) { (if (it % 2 == 0) 4000 else -4000).toShort() }
        for (c in "......##.....#######..") phrase.onFrame(if (c == '#') loud else quiet)
        assertTrue(phrase.matches())
        phrase.reset()
        for (c in "..........#######..") phrase.onFrame(if (c == '#') loud else quiet)
        assertFalse(phrase.matches())
    }
}
