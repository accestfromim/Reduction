package com.mindtravel.reduction.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun firstPieceIsCutAtTheFirstClauseBoundary() {
        val splitter = SentenceSplitter()
        assertEquals(listOf("今天北京天气不错，"), splitter.push("今天北京天气不错，气温二十度"))
        assertEquals(emptyList<String>(), splitter.push("左右"))
        assertEquals(listOf("气温二十度左右。"), splitter.push("。"))
    }

    @Test
    fun aClauseShorterThanTheMinimumIsNotCut() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.push("好的，我"))
        assertEquals(listOf("好的，我来查一下。"), splitter.push("来查一下。"))
    }

    @Test
    fun aShortFirstSentenceIsEmittedWhole() {
        assertEquals(listOf("好的。"), SentenceSplitter().push("好的。"))
    }

    @Test
    fun laterPiecesAreWholeSentences() {
        val splitter = SentenceSplitter()
        splitter.push("第一句话在这里。")
        assertEquals(emptyList<String>(), splitter.push("第二句，带一个逗号"))
        assertEquals(listOf("第二句，带一个逗号。", "第三句！"), splitter.push("。第三句！第四"))
        assertEquals(listOf("第四"), splitter.finish())
    }

    @Test
    fun firstPieceWithoutPunctuationIsCutAtTheMaximum() {
        val pieces = SentenceSplitter().push("一".repeat(40))
        assertEquals(1, pieces.size)
        assertEquals(32, pieces[0].length)
    }

    @Test
    fun noPieceExceedsTheMaximumLength() {
        val splitter = SentenceSplitter()
        splitter.push("开头。")
        val long = "这是一个很长的分句，".repeat(60) + "结束。"
        val pieces = splitter.push(long)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 200 })
        assertEquals(long, pieces.joinToString(""))
    }

    @Test
    fun finishFlushesTheRemainder() {
        val splitter = SentenceSplitter()
        assertEquals(emptyList<String>(), splitter.push("没有标点"))
        assertEquals(listOf("没有标点"), splitter.finish())
        assertEquals(emptyList<String>(), splitter.finish())
    }
}
