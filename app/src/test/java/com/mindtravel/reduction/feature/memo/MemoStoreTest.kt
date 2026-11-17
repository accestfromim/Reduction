package com.mindtravel.reduction.feature.memo

import com.mindtravel.reduction.feature.memo.data.DueParser
import com.mindtravel.reduction.feature.memo.data.MemoStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MemoStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Shanghai")

    /** Noon on 2026-11-17 in the test zone: today, for every test here. */
    private val noon: Long = LocalDateTime.of(2026, 11, 17, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun store(at: Long = noon): MemoStore {
        var clock = at
        return MemoStore(File(temporary.newFolder(), "memos.json"), now = { clock }, zone = zone).also { s ->
            // Every test reads the clock once; the store keeps its own reference to it.
        }
    }

    private fun moment(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ── what was said becomes a moment ──────────────────────────────────────

    @Test
    fun aDateAndATimeBecomeTheMomentTheyMean() {
        val today = LocalDate.parse("2026-11-17")
        val parsed = DueParser.parse("2026-11-18", "08:30", today)
        assertNotNull(parsed)
        assertEquals(LocalDate.parse("2026-11-18"), parsed!!.date)
        assertEquals(8, parsed.hour)
        assertEquals(30, parsed.minute)
        assertFalse(parsed.assumedTime)
    }

    @Test
    fun aDayWithoutAnHourIsTheMorning() {
        val parsed = DueParser.parse("2026-11-20", null, LocalDate.parse("2026-11-17"))
        assertNotNull(parsed)
        assertEquals(DueParser.DEFAULT_HOUR, parsed!!.hour)
        assertTrue(parsed.assumedTime)
    }

    @Test
    fun everydayWordsAreReadAsDates() {
        val today = LocalDate.parse("2026-11-17") // a Tuesday
        assertEquals(today, DueParser.dayOf("今天", today))
        assertEquals(today.plusDays(1), DueParser.dayOf("明天", today))
        assertEquals(today.plusDays(2), DueParser.dayOf("后天", today))
        assertEquals(today.plusDays(3), DueParser.dayOf("大后天", today))
        assertEquals(today.plusDays(5), DueParser.dayOf("5天后", today))
        assertEquals(today.plusDays(1), DueParser.dayOf("周三", today))
        assertEquals(today.plusDays(8), DueParser.dayOf("下周三", today))
        assertEquals(LocalDate.parse("2026-11-18"), DueParser.dayOf("11-18", today))
    }

    @Test
    fun anHourSaidInChineseIsRead() {
        assertEquals(8 * 60, DueParser.timeOf("早上八点")!!.hour * 60 + DueParser.timeOf("早上八点")!!.minute)
        assertEquals(15, DueParser.timeOf("下午三点")!!.hour)
        assertEquals(15 * 60 + 30, DueParser.timeOf("下午三点半")!!.hour * 60 + DueParser.timeOf("下午三点半")!!.minute)
        assertEquals(21, DueParser.timeOf("晚上九点")!!.hour)
        assertEquals(0, DueParser.timeOf("凌晨十二点")!!.hour)
        assertEquals(11, DueParser.timeOf("11:00")!!.hour)
    }

    @Test
    fun nothingUsableMeansNoMoment() {
        assertNull(DueParser.parse(null, null, LocalDate.parse("2026-11-17")))
        assertNull(DueParser.parse("改天吧", null, LocalDate.parse("2026-11-17")))
        // A time that cannot be read counts as none, not as a failure.
        val parsed = DueParser.parse("2026-11-18", "下午再说", LocalDate.parse("2026-11-17"))
        assertNotNull(parsed)
        assertTrue(parsed!!.assumedTime)
    }

    // ── the to-dos themselves ───────────────────────────────────────────────

    @Test
    fun aTodoKeepsItsMomentAndSurvivesARestart() {
        val file = File(temporary.newFolder(), "memos.json")
        val first = MemoStore(file, now = { noon }, zone = zone)
        val due = moment("2026-11-18", 9)
        val made = first.add("交周报", due)
        assertEquals("交周报", made.text)
        assertEquals(due, made.dueAt)

        val again = MemoStore(file, now = { noon }, zone = zone)
        val loaded = again.find(made.id)
        assertNotNull(loaded)
        assertEquals("交周报", loaded!!.text)
        assertEquals(due, loaded.dueAt)
    }

    @Test
    fun numbersAreGivenOutOnceAndNeverAgain() {
        val store = store()
        val a = store.add("一", null)
        val b = store.add("二", null)
        store.delete(a.id)
        val c = store.add("三", null)
        assertEquals(1, a.id)
        assertEquals(2, b.id)
        assertEquals(3, c.id)
    }

    @Test
    fun finishingTakesATodoOutOfTheRemindersAndTheRecordStays() {
        val store = store()
        val memo = store.add("回邮件", moment("2026-11-17", 15))
        assertTrue(store.plan().due.any { it.id == memo.id })
        store.setDone(memo.id, true)
        assertFalse(store.plan().due.any { it.id == memo.id })
        assertTrue(store.plan().overdue.none { it.id == memo.id })
        // Still in the record.
        assertNotNull(store.find(memo.id))
        assertTrue(store.find(memo.id)!!.done)
        // Opened again, it reminds again.
        store.setDone(memo.id, false)
        assertTrue(store.plan().due.any { it.id == memo.id })
    }

    @Test
    fun anOverdueTodoIsOverdueUntilFinishedOrMoved() {
        val store = store()
        val memo = store.add("交材料", moment("2026-11-16", 9))
        assertTrue(store.plan().overdue.any { it.id == memo.id })
        assertTrue(store.plan().due.none { it.id == memo.id })
        store.update(memo.id, dueAt = moment("2026-11-19", 9), changeDue = true)
        assertTrue(store.plan().overdue.none { it.id == memo.id })
        assertTrue(store.plan().due.any { it.id == memo.id })
    }

    @Test
    fun aNewTimeMeansTheRemindersStartOver() {
        val store = store()
        val memo = store.add("约医生", moment("2026-11-17", 15))
        store.markDueReminded(memo)
        assertTrue(store.plan().due.none { it.id == memo.id })
        store.update(memo.id, dueAt = moment("2026-11-20", 9), changeDue = true)
        assertTrue(store.plan().due.any { it.id == memo.id })
    }

    @Test
    fun theOverdueReminderIsGivenOnceADay() {
        val store = store()
        val a = store.add("甲", moment("2026-11-16", 9))
        val b = store.add("乙", moment("2026-11-15", 9))
        val overdue = store.plan().overdue
        assertEquals(2, overdue.size)
        store.markOverdueReminded(overdue)
        assertEquals("2026-11-17", store.find(a.id)!!.overdueRemindedOn)
        assertEquals("2026-11-17", store.find(b.id)!!.overdueRemindedOn)
    }

    @Test
    fun undatedTodosArePutToTheUserOnceADay() {
        val store = store()
        store.add("想想假期去哪", null)
        store.add("有日期的", moment("2026-11-20", 9))
        val first = store.askAboutUndated()
        assertEquals(1, first.size)
        assertEquals("想想假期去哪", first[0].text)
        // Asked already today.
        assertTrue(store.askAboutUndated().isEmpty())
    }

    // ── the card's three blocks ─────────────────────────────────────────────

    @Test
    fun todayHoldsWhatIsLateAndWhatIsDueToday() {
        val store = store()
        val late = store.add("昨天的", moment("2026-11-16", 9))
        val later = store.add("今晚的", moment("2026-11-17", 20))
        val tomorrow = store.add("明天的", moment("2026-11-18", 9))
        val undated = store.add("没日期的", null)
        val view = store.view()
        // Late first, then by time: what is overdue is the most urgent of today's work.
        assertEquals(listOf(late.id, later.id), view.today.map { it.id })
        assertEquals(listOf(tomorrow.id), view.future.map { it.id })
        assertEquals(listOf(undated.id), view.undated.map { it.id })
    }

    @Test
    fun theCardSaysWhatIsDoneAndWhatIsLate() {
        val store = store()
        val done = store.add("做完了", moment("2026-11-17", 10))
        store.setDone(done.id, true)
        store.add("还没做", moment("2026-11-18", 9))
        store.add("没日期", null)
        val text = MemoCard.render(store)
        assertTrue(text.contains("## 今天"))
        assertTrue(text.contains("## 未来"))
        assertTrue(text.contains("## 没日期"))
        assertTrue(text.contains("[x] #${done.id} 做完了"))
        assertTrue(text.contains("明天 09:00"))
        assertTrue(text.contains("没日期"))
    }

    @Test
    fun theCardNeverLooksIntoThePastForFinishedWork() {
        val store = store()
        repeat(12) { store.setDone(store.add("第 $it 件", moment("2026-11-17", 9)).id, true) }
        val text = MemoCard.render(store)
        // Today's finished ones are all listed, for reading the day back.
        assertTrue(text.contains("[x] #12 第 11 件"))
        assertTrue(text.contains("[x] #1 第 0 件"))
        assertFalse(text.contains("已完成 12 条"))
    }
}
