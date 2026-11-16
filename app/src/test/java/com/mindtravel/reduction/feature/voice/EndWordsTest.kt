package com.mindtravel.reduction.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndWordsTest {

    @Test
    fun aClosingEndWordIsTakenOff() {
        assertEquals("打开设置", EndWords.strip("打开设置，完毕。"))
        assertEquals("打开设置", EndWords.strip("打开设置 over"))
        assertEquals("Open settings", EndWords.strip("Open settings, O V E R."))
    }

    @Test
    fun anEndWordAloneLeavesNothing() {
        assertEquals("", EndWords.strip("over"))
        assertEquals("", EndWords.strip("完毕。"))
    }

    @Test
    fun textThatDoesNotEndWithOneIsLeftAlone() {
        assertNull(EndWords.strip("打开设置"))
        assertNull(EndWords.strip("完毕之后再说一句"))
        assertNull(EndWords.strip(""))
    }
}
