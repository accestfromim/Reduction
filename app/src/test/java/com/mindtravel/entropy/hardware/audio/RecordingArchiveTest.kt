package com.mindtravel.entropy.hardware.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecordingArchiveTest {

    private val dir = Files.createTempDirectory("recordings").toFile()
    private var clock = 1_700_000_000_000L
    private fun archive(maxBytes: Long = 1_000_000) = RecordingArchive(dir, maxBytes) { clock.also { clock += 1_000 } }

    @Test
    fun aRecordingWaitsWithWhatWasHeardInIt() {
        val archive = archive()
        archive.keep("turn", byteArrayOf(1, 2, 3), "明天提醒我开会")
        val item = archive.pending().single()
        assertTrue(item.audio.name.endsWith("-turn.wav"))
        assertEquals(listOf<Byte>(1, 2, 3), item.audio.readBytes().toList())
        assertEquals("明天提醒我开会\n", item.note!!.readText())
        assertEquals(item.audio.nameWithoutExtension, item.note!!.nameWithoutExtension)
    }

    @Test
    fun recordingsComeOutOldestFirstAndLeaveWhenRemoved() {
        val archive = archive()
        archive.keep("wake", byteArrayOf(1))
        archive.keep("turn", byteArrayOf(2), "好")
        val waiting = archive.pending()
        assertEquals(listOf("wake", "turn"), waiting.map { it.audio.nameWithoutExtension.substringAfterLast('-') })
        assertNull(waiting[0].note)
        waiting.forEach(archive::remove)
        assertTrue(archive.pending().isEmpty())
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun whenFullTheOldestGoFirst() {
        val archive = archive(maxBytes = 250)
        archive.keep("turn", ByteArray(100), "一")
        archive.keep("turn", ByteArray(100), "二")
        archive.keep("turn", ByteArray(100), "三")
        val notes = archive.pending().map { it.note!!.readText().trim() }
        assertEquals(listOf("二", "三"), notes)
        assertEquals(4, dir.listFiles().orEmpty().size)
    }

    @Test
    fun anEmptyRecordingIsNotKept() {
        val archive = archive()
        archive.keep("turn", ByteArray(0), "x")
        assertTrue(archive.pending().isEmpty())
    }
}
