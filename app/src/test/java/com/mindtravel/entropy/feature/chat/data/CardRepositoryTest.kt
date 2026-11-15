package com.mindtravel.entropy.feature.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CardRepositoryTest {

    private fun cards() = CardRepository(Files.createTempDirectory("cards").toFile())

    @Test
    fun severalCardsLiveSideBySide() {
        val cards = cards()
        cards.save("科研想法·检索", "a", purpose = "检索")
        cards.save("科研想法·评测", "b", purpose = "评测")
        cards.save("备忘", "c", purpose = "零碎待办")
        assertEquals(setOf("科研想法·检索", "科研想法·评测", "备忘"), cards.list().map { it.name }.toSet())
        assertEquals("b", cards.read("科研想法·评测"))
        assertEquals("评测", cards.list().first { it.name == "科研想法·评测" }.purpose)
    }

    @Test
    fun savingReplacesTheWholeCardAndCanChangeItsPurpose() {
        val cards = cards()
        cards.save("任务", "旧", purpose = "当前任务卡")
        cards.save("任务", "新")
        assertEquals("新", cards.read("任务"))
        assertEquals("a save without a purpose keeps it", "当前任务卡", cards.purpose("任务"))
        cards.save("任务", "新", purpose = "改了用途")
        assertEquals("改了用途", cards.purpose("任务"))
    }

    @Test
    fun theDirectoryCarriesPurposesNotText() {
        val cards = cards()
        cards.save("想法", "很长的正文……", purpose = "科研想法")
        val json = cards.directoryJson()
        assertTrue(json, json.contains("科研想法"))
        assertFalse(json.contains("很长的正文"))
    }

    @Test
    fun deletingRemovesOnlyThatCardAndItsPurpose() {
        val cards = cards()
        cards.save("a", "1", purpose = "甲")
        cards.save("b", "2", purpose = "乙")
        assertTrue(cards.delete("a"))
        assertFalse(cards.delete("a"))
        assertNull(cards.read("a"))
        assertEquals("", cards.purpose("a"))
        assertEquals(listOf("b"), cards.list().map { it.name })
    }

    @Test
    fun unusableNamesAreRefused() {
        val cards = cards()
        assertFalse(cards.save("", "x"))
        assertFalse(cards.save("两\n行", "x"))
        assertFalse(cards.save("长".repeat(41), "x"))
        assertTrue("path characters are just part of the name", cards.save("../../etc/passwd", "x"))
        assertEquals(listOf("../../etc/passwd"), cards.list().map { it.name })
    }
}
