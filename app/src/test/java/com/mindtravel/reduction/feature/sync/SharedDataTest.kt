package com.mindtravel.reduction.feature.sync

import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.memo.data.MemoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Two devices, each with its own cards and to-dos, brought together the way the phone and the computer are. */
class SharedDataTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private var clock = 1_800_000_000_000L
    private fun memos() = MemoStore(File(temporary.newFolder(), "memos.json"), now = { clock++ })
    private fun cards() = CardRepository(temporary.newFolder())

    /** One exchange: the phone sends, the computer merges and answers, the phone merges the answer. */
    private fun exchange(phone: MemoStore, computer: MemoStore) {
        computer.merge(phone.export(), MemoStore.Numbers.MINE)
        phone.merge(computer.export(), MemoStore.Numbers.THEIRS)
    }

    private fun exchange(phone: CardRepository, computer: CardRepository) {
        computer.merge(phone.export())
        phone.merge(computer.export())
    }

    private fun MemoStore.said() = all().sortedBy { it.id }.map { "#${it.id} ${it.text} ${it.due} ${it.done}" }

    // ── to-dos ──────────────────────────────────────────────────────────────

    @Test
    fun aTodoWrittenOnEitherDeviceReachesTheOther() {
        val phone = memos()
        val computer = memos()
        phone.add("买花", LocalDate.parse("2026-11-20"))
        exchange(phone, computer)
        computer.add("交周报", null)
        exchange(phone, computer)
        assertEquals(listOf("#1 买花 2026-11-20 false", "#2 交周报 null false"), phone.said())
        assertEquals(phone.said(), computer.said())
    }

    @Test
    fun theLaterChangeStandsAndDeletingTravels() {
        val phone = memos()
        val computer = memos()
        val memo = phone.add("买花", null)
        val other = phone.add("换灯泡", null)
        exchange(phone, computer)
        computer.setDone(memo.id, true)
        phone.update(memo.id, text = "买两束花")           // earlier than the tick on the computer? no: later
        computer.delete(other.id)
        exchange(phone, computer)
        assertEquals(listOf("#1 买两束花 null false"), phone.said())
        assertEquals(phone.said(), computer.said())
        // And it stays deleted on the next exchange.
        exchange(phone, computer)
        assertEquals(1, phone.all().size)
    }

    @Test
    fun twoTodosGivenTheSameNumberEndUpWithDifferentOnesTheSameOnBothDevices() {
        val phone = memos()
        val computer = memos()
        phone.add("手机上的", null)
        computer.add("电脑上的", null)
        exchange(phone, computer)
        assertEquals(listOf("#1 电脑上的 null false", "#2 手机上的 null false"), computer.said())
        assertEquals(computer.said(), phone.said())
        // A number given out afterwards is new on both.
        assertEquals(3, phone.add("再一条", null).id)
        exchange(phone, computer)
        assertEquals(phone.said(), computer.said())
    }

    @Test
    fun anExchangeWithNothingNewChangesNothing() {
        val phone = memos()
        val computer = memos()
        phone.add("买花", null)
        exchange(phone, computer)
        assertFalse(computer.merge(phone.export(), MemoStore.Numbers.MINE))
        assertFalse(phone.merge(computer.export(), MemoStore.Numbers.THEIRS))
    }

    // ── cards ───────────────────────────────────────────────────────────────

    @Test
    fun cardsTravelWithTheirPurposeAndLockAndTheProgramsOwnStayHome() {
        val phone = cards()
        val computer = cards()
        phone.save("想法/周末", "去爬山", "周末的打算")
        phone.setLocked("想法/周末", true)
        phone.writeProgramCard("计划", "手机的", "程序写的")
        computer.writeProgramCard("计划", "电脑的", "程序写的")
        exchange(phone, computer)
        assertEquals("去爬山", computer.read("想法/周末"))
        assertEquals("周末的打算", computer.purpose("想法/周末"))
        assertTrue(computer.isLocked("想法/周末"))
        assertEquals("电脑的", computer.read("计划"))
        assertEquals("手机的", phone.read("计划"))
        assertFalse(computer.merge(phone.export()))
    }

    @Test
    fun theLaterEditOfACardStandsOnBoth() {
        val phone = cards()
        val computer = cards()
        phone.save("备忘", "第一版")
        exchange(phone, computer)
        computer.save("备忘", "电脑改的")
        File(temporary.root, "x").let { }   // nothing: the files' own times order the edits
        Thread.sleep(20)
        phone.save("备忘", "手机后改的")
        exchange(phone, computer)
        assertEquals("手机后改的", computer.read("备忘"))
        assertEquals("手机后改的", phone.read("备忘"))
    }

    @Test
    fun aCardDeletedOnOneDeviceIsDeletedOnTheOtherAndCanBeWrittenAgain() {
        val phone = cards()
        val computer = cards()
        phone.save("备忘", "内容")
        exchange(phone, computer)
        Thread.sleep(20)
        computer.delete("备忘")
        exchange(phone, computer)
        assertNull(phone.read("备忘"))
        exchange(phone, computer)
        assertNull(computer.read("备忘"))
        Thread.sleep(20)
        phone.save("备忘", "又写了")
        exchange(phone, computer)
        assertEquals("又写了", computer.read("备忘"))
    }

    @Test
    fun aPictureInACardArrivesUnderTheSameId() {
        val phone = cards()
        val computer = cards()
        val id = phone.saveMedia(byteArrayOf(1, 2, 3), "png")!!
        phone.save("相册", "![图](media:$id)")
        assertEquals(setOf(id), phone.heldMedia())
        assertTrue(computer.putMedia(id, phone.mediaFile(id)!!.readBytes()))
        exchange(phone, computer)
        assertEquals(setOf(id), computer.heldMedia())
        assertFalse(computer.putMedia("../evil.png", byteArrayOf(1)))
    }
}
