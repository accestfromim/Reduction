package com.mindtravel.reduction.feature.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeafWatchTest {

    private val zeros = ShortArray(1600)
    private val quietRoom = ShortArray(1600) { if (it % 7 == 0) 1 else 0 }

    @Test
    fun fiveSecondsOfExactZerosMeansDeaf() {
        val watch = DeafWatch()
        val changes = (1..60).filter { watch.onFrame(zeros) }
        assertEquals("the verdict changes once, on the fiftieth 100 ms frame", listOf(50), changes)
        assertTrue(watch.deaf)
    }

    @Test
    fun aVeryQuietRoomIsNotDeaf() {
        val watch = DeafWatch()
        repeat(600) { assertFalse(watch.onFrame(quietRoom)) }
        assertFalse(watch.deaf)
    }

    @Test
    fun aShortDropoutIsIgnored() {
        val watch = DeafWatch()
        repeat(49) { watch.onFrame(zeros) }
        watch.onFrame(quietRoom)
        repeat(49) { watch.onFrame(zeros) }
        assertFalse(watch.deaf)
    }

    @Test
    fun hearingAgainClearsIt() {
        val watch = DeafWatch()
        repeat(50) { watch.onFrame(zeros) }
        assertTrue("the first real frame changes the verdict back", watch.onFrame(quietRoom))
        assertFalse(watch.deaf)
    }
}
