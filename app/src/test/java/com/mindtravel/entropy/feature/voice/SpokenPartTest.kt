package com.mindtravel.entropy.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenPartTest {

    private fun speak(vararg tokens: String): String {
        val part = SpokenPart()
        return tokens.joinToString("") { part.push(it) } + part.finish()
    }

    @Test
    fun aReplyWithoutTheMarkerIsSpokenWhole() {
        assertEquals("明天有雨，记得带伞。", speak("明天有雨，", "记得带伞。"))
    }

    @Test
    fun nothingAfterTheMarkerIsSpoken() {
        assertEquals("结论是可以。\n", speak("结论是可以。\n【详情】\n```kotlin\nval x = 1\n```"))
    }

    @Test
    fun theMarkerIsFoundWhenItArrivesInPieces() {
        assertEquals("结论。", speak("结论。【", "详", "情】依据：原文……", "更多"))
    }

    @Test
    fun aBracketThatIsNotTheMarkerIsStillSpoken() {
        assertEquals("见【附录】第一节。", speak("见【", "附录】第一节。"))
    }

    @Test
    fun theSpokenTagItselfIsNotSpoken() {
        assertEquals("\n结论是可以。\n", speak("【朗读】\n结论是可以。\n【详情】\n依据：……"))
        assertEquals("好的。", speak("【", "朗", "读】好的。"))
        assertEquals("\n已更新", speak("【朗读】\n已更新"))
    }

    @Test
    fun aReplyEndingInAPartialMarkerSpeaksIt() {
        assertEquals("结束【详", speak("结束【详"))
    }
}
