package com.mindtravel.reduction.feature.memo

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.time.RelativeDates
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.tools.ReadCardTool
import com.mindtravel.reduction.feature.chat.model.tools.SearchCardsTool
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
    private val today = LocalDate.parse("2026-11-17") // a Tuesday

    private fun at(day: LocalDate, hour: Int = 12): Long =
        LocalDateTime.of(day.year, day.month, day.dayOfMonth, hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** The clock the store under test reads; a test moves it to see another day. */
    private var clock = at(today)

    private fun store(file: File = File(temporary.newFolder(), "memos.json")) =
        MemoStore(file, now = { clock }, zone = zone)

    private fun day(date: String): LocalDate = LocalDate.parse(date)

    private class Quiet : EventSink {
        override fun emit(function: String, vararg args: Any?) {}
    }

    private fun tool(store: MemoStore) = MemoTool(store, Quiet())

    // ── what was said becomes a day ─────────────────────────────────────────

    @Test
    fun everydayWordsAreReadAsDates() {
        assertEquals(day("2026-11-18"), DueParser.dayOf("2026-11-18", today))
        assertEquals(today, DueParser.dayOf("今天", today))
        assertEquals(today.plusDays(1), DueParser.dayOf("明天", today))
        assertEquals(today.plusDays(2), DueParser.dayOf("后天", today))
        assertEquals(today.plusDays(3), DueParser.dayOf("大后天", today))
        assertEquals(today.plusDays(5), DueParser.dayOf("5天后", today))
        assertEquals(today.plusDays(1), DueParser.dayOf("周三", today))
        assertEquals(today.plusDays(8), DueParser.dayOf("下周三", today))
        assertEquals(day("2026-11-18"), DueParser.dayOf("11-18", today))
        assertNull(DueParser.dayOf("改天吧", today))
        assertNull(DueParser.dayOf(null, today))
    }

    @Test
    fun aDayIsSaidWithoutAnHour() {
        assertEquals("今天", DueParser.describe(today, today))
        assertEquals("明天", DueParser.describe(today.plusDays(1), today))
        assertEquals("这周六", DueParser.describe(day("2026-11-21"), today))
        assertEquals("下周二", DueParser.describe(day("2026-11-24"), today))
        assertEquals("2027-01-03", DueParser.describe(day("2027-01-03"), today))
    }

    @Test
    fun historicalDatesAreResolvedInModelContext() {
        val reference = day("2026-11-18")
        val text = "明天档案归档，下周二核对。这周整理资料。用「明天」当标签。"
        assertEquals(
            "［原消息日期 2026-11-18］\n2026-11-19档案归档，2026-11-24核对。2026-11-16 至 2026-11-22整理资料。用「明天」当标签。",
            RelativeDates.context(text, reference)
        )
        assertEquals(day("2026-11-24"), DueParser.dayOf("下周二", day("2026-11-19")))
        assertEquals(day("2026-11-17"), DueParser.dayOf("这周二", reference))
        assertEquals(day("2026-11-20"), DueParser.dayOf("两天后", reference))
        assertEquals(day("2026-12-03"), DueParser.dayOf("下个月3号", reference))
        assertEquals(day("2027-01-05"), DueParser.dayOf("下周二", day("2026-12-31")))
    }

    @Test
    fun theToolAndPlanReadUseTheStoredDateAcrossMidnight() {
        val store = store()
        val tool = MemoTool(store, Quiet()) { day("2026-11-18") }
        clock = at(day("2026-11-19"), hour = 0)
        val saved = tool.execute("""{"action":"add","text":"档案归档","date":"明天","part":"早上"}""")
        assertTrue(saved.contains("2026-11-19 早上"))
        assertEquals(day("2026-11-19"), store.find(1)!!.due)
        val cards = CardRepository(temporary.newFolder())
        cards.writeProgramCard(MemoCard.CARD, MemoCard.render(store), MemoCard.PURPOSE)
        assertTrue(cards.read(MemoCard.CARD)!!.contains("档案归档 · 今天 早上"))
        assertFalse(MemoCard.render(store).contains("## 没日期"))
        assertTrue(ReadCardTool(cards, store).execute("""{"name":"计划"}""").contains("档案归档 · 2026-11-19 早上"))
        assertTrue(SearchCardsTool(cards, store).execute("""{"query":"档案归档"}""").contains("档案归档 · 2026-11-19 早上"))

        clock = at(day("2026-11-20"), hour = 0)
        assertTrue(MemoView.json(store, exact = true, notify = true).contains("\"due\":\"昨天 早上\""))
        assertTrue(tool.execute("""{"action":"list"}""").contains("档案归档 · 2026-11-19 早上"))
        store.add("整理归档清单", null)
        assertTrue(MemoCard.render(store).contains("## 没日期\n\n- [ ] #2 整理归档清单"))
    }

    // ── the to-dos themselves ───────────────────────────────────────────────

    @Test
    fun aTodoKeepsItsDayAndSurvivesARestart() {
        val file = File(temporary.newFolder(), "memos.json")
        val made = store(file).add("交周报", day("2026-11-18"))
        val loaded = store(file).find(made.id)
        assertNotNull(loaded)
        assertEquals("交周报", loaded!!.text)
        assertEquals(day("2026-11-18"), loaded.due)
        assertTrue(file.readText().contains("\"due\":\"2026-11-18\""))
    }

    @Test
    fun aTodoStoredWithAnHourIsReadAsItsDay() {
        val file = File(temporary.newFolder(), "memos.json")
        val moment = LocalDateTime.of(2026, 11, 19, 15, 30).atZone(zone).toInstant().toEpochMilli()
        file.writeText("""{"next":2,"items":[{"id":1,"text":"约医生","due":$moment,"done":false}]}""")
        assertEquals(day("2026-11-19"), store(file).find(1)!!.due)
    }

    @Test
    fun numbersAreGivenOutOnceAndNeverAgain() {
        val store = store()
        val a = store.add("一", null)
        val b = store.add("二", null)
        store.delete(a.id)
        val c = store.add("三", null)
        assertEquals(listOf(1, 2, 3), listOf(a.id, b.id, c.id))
    }

    @Test
    fun theToolWritesOnlyADay() {
        val store = store()
        val tool = tool(store)
        // An hour, if the assistant sends one anyway, is not part of the protocol.
        val said = tool.execute("""{"action":"add","text":"下午三点开会","date":"2026-11-18","time":"15:00"}""")
        assertEquals("记下了 #1：下午三点开会，2026-11-18", said)
        assertEquals(day("2026-11-18"), store.find(1)!!.due)
        assertTrue(tool.spec.parameters.none { it.name == "time" })
        assertEquals("改好了 #1：下午三点开会，2026-11-19", tool.execute("""{"action":"update","id":"1","date":"后天"}"""))
    }

    @Test
    fun aPartOfTheDayIsKeptBesideTheDateAndNoHourStandsBehindIt() {
        val file = File(temporary.newFolder(), "memos.json")
        val store = store(file)
        val tool = tool(store)
        assertEquals("记下了 #1：交报告，2026-11-18 下午", tool.execute("""{"action":"add","text":"交报告","date":"2026-11-18","part":"下午"}"""))
        assertEquals("记下了 #2：买菜，2026-11-18", tool.execute("""{"action":"add","text":"买菜","date":"2026-11-18"}"""))
        assertEquals(MemoStore.Part.AFTERNOON, store.find(1)!!.part)
        assertNull(store.find(2)!!.part)
        // Kept as a date and a part, nothing finer; and read back the same.
        assertTrue(file.readText().contains("\"due\":\"2026-11-18\",\"part\":\"AFTERNOON\""))
        assertEquals(MemoStore.Part.AFTERNOON, store(file).find(1)!!.part)
        // It shows wherever the to-do does.
        assertTrue(MemoCard.render(store).contains("#1 交报告 · 明天 下午\n"))
        assertTrue(MemoCard.render(store).contains("#2 买菜 · 明天\n"))
        assertTrue(tool.execute("""{"action":"list"}""").contains("#1 交报告 · 2026-11-18 下午"))
        assertTrue(MemoView.json(store, exact = true, notify = true).contains("\"due\":\"明天 下午\""))
        assertFalse(Regex("\\d\\d:\\d\\d").containsMatchIn(MemoCard.render(store)))
        // Within a day, the whole-day ones first, then by part.
        tool.execute("""{"action":"add","text":"晨跑","date":"2026-11-18","part":"早上"}""")
        assertEquals(listOf("买菜", "晨跑", "交报告"), store.view().future.map { it.text })
        // Changed alone, taken away, and refused when it is not one of the four.
        assertEquals("改好了 #1：交报告，2026-11-18 晚上", tool.execute("""{"action":"update","id":"1","part":"晚上"}"""))
        assertEquals("改好了 #1：交报告，2026-11-18", tool.execute("""{"action":"update","id":"1","part":"none"}"""))
        assertTrue(tool.execute("""{"action":"update","id":"1","part":"半夜"}""").startsWith("没有生效"))
    }

    // ── the day's reminder ──────────────────────────────────────────────────

    @Test
    fun theReminderIsAboutTheDayItIsGivenOn() {
        val store = store()
        val todays = store.add("今天的", today)
        val tomorrows = store.add("明天的", today.plusDays(1))
        assertEquals(listOf(todays.id), store.morningReminder().map { it.id })
        // The next day it is tomorrow's turn, and yesterday's unfinished one comes along as overdue.
        clock = at(today.plusDays(1))
        assertEquals(listOf(todays.id, tomorrows.id), store.morningReminder().map { it.id })
        assertEquals(listOf(todays.id), store.plan().overdue.map { it.id })
    }

    @Test
    fun aTodoIsBroughtUpOnceADay() {
        val store = store()
        store.add("甲", today)
        store.add("乙", today.minusDays(2))
        val owed = store.morningReminder()
        assertEquals(2, owed.size)
        store.markReminded(owed)
        assertTrue(store.morningReminder().isEmpty())
        clock = at(today.plusDays(1))
        // Both are overdue now, and overdue is brought up every day.
        assertEquals(2, store.morningReminder().size)
    }

    @Test
    fun finishingTakesATodoOutOfTheRemindersAndTheRecordStays() {
        val store = store()
        val memo = store.add("回邮件", today)
        store.setDone(memo.id, true)
        assertTrue(store.morningReminder().isEmpty())
        assertTrue(store.find(memo.id)!!.done)
        store.setDone(memo.id, false)
        assertEquals(1, store.morningReminder().size)
    }

    @Test
    fun anOverdueTodoIsOverdueUntilFinishedOrMoved() {
        val store = store()
        val memo = store.add("交材料", today.minusDays(1))
        assertTrue(store.isOverdue(memo))
        store.markReminded(store.morningReminder())
        store.update(memo.id, due = today.plusDays(2), changeDue = true)
        assertFalse(store.isOverdue(memo))
        assertTrue(store.morningReminder().isEmpty())
        // On its new day it is reminded afresh.
        clock = at(today.plusDays(2))
        assertEquals(1, store.morningReminder().size)
    }

    // ── a date left empty ───────────────────────────────────────────────────

    @Test
    fun aTodoMayBeWrittenWithoutADateAndItsDateIsEmpty() {
        val store = store()
        val tool = tool(store)
        val unsaid = tool.execute("""{"action":"add","text":"换灯泡"}""")
        val named = tool.execute("""{"action":"add","text":"买花","date":"empty"}""")
        assertTrue(unsaid, unsaid.startsWith("记下了 #1"))
        assertTrue(named, named.startsWith("记下了 #2"))
        // What empty means stays in the program: the assistant only hears there is no time.
        assertFalse(unsaid.contains("empty") || unsaid.contains("明天"))
        assertFalse(tool.spec.description.contains("empty") || tool.spec.parameters.any { it.description.contains("empty") })
        assertNull(store.find(1)!!.due)
        assertNull(store.find(2)!!.due)
        assertFalse(MemoCard.render(store).contains("empty"))
        assertTrue(tool.execute("""{"action":"list"}""").contains("#2 买花 · 没有标记时间"))
        assertTrue(MemoView.json(store, exact = true, notify = true).contains("\"due\":\"empty\""))
    }

    @Test
    fun aDateThatCannotBeReadIsRefusedRatherThanTakenAsEmpty() {
        val store = store()
        val said = tool(store).execute("""{"action":"add","text":"换灯泡","date":"改天吧"}""")
        assertTrue(said, said.startsWith("没有生效"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun anEmptyDateIsBroughtUpEveryMorningUntilItIsFilled() {
        val store = store()
        val memo = store.add("换灯泡", null)
        assertEquals(listOf(memo.id), store.plan().empty.map { it.id })
        store.markReminded(store.morningReminder())
        assertTrue(store.morningReminder().isEmpty())
        clock = at(today.plusDays(1))
        assertEquals(1, store.morningReminder().size)
        // A date ends it: the to-do waits for its own day.
        tool(store).execute("""{"action":"update","id":"${memo.id}","date":"2026-11-25"}""")
        assertTrue(store.plan().empty.isEmpty())
        assertTrue(store.morningReminder().isEmpty())
        // And taking the date away makes it empty again.
        tool(store).execute("""{"action":"update","id":"${memo.id}","date":"empty"}""")
        assertNull(store.find(memo.id)!!.due)
        assertEquals(1, store.morningReminder().size)
    }

    @Test
    fun undatedTodosArePutToTheUserInTalkOnceADay() {
        val store = store()
        store.add("想想假期去哪", null)
        store.add("有日期的", today.plusDays(3))
        val first = store.askAboutUndated()
        assertEquals(listOf("想想假期去哪"), first.map { it.text })
        assertTrue(store.askAboutUndated().isEmpty())
        clock = at(today.plusDays(1))
        assertEquals(1, store.askAboutUndated().size)
    }

    // ── the card's three blocks ─────────────────────────────────────────────

    @Test
    fun todayHoldsWhatIsLateAndWhatIsDueToday() {
        val store = store()
        val todays = store.add("今天的", today)
        val late = store.add("昨天的", today.minusDays(1))
        val tomorrow = store.add("明天的", today.plusDays(1))
        val undated = store.add("没日期的", null)
        val view = store.view()
        // Late first: what is overdue is the most urgent of today's work.
        assertEquals(listOf(late.id, todays.id), view.today.map { it.id })
        assertEquals(listOf(tomorrow.id), view.future.map { it.id })
        assertEquals(listOf(undated.id), view.undated.map { it.id })
    }

    @Test
    fun theCardSaysWhatIsDoneAndWhatIsLate() {
        val store = store()
        val done = store.add("做完了", today)
        store.setDone(done.id, true)
        store.add("还没做", today.plusDays(1))
        store.add("拖着的", today.minusDays(1))
        store.add("没日期", null)
        val text = MemoCard.render(store)
        assertTrue(text.contains("## 今天"))
        assertTrue(text.contains("## 未来"))
        assertTrue(text.contains("## 没日期"))
        assertTrue(text.contains("[x] #${done.id} 做完了 · 今天\n"))
        assertTrue(text.contains("还没做 · 明天\n"))
        assertTrue(text.contains("拖着的 · 昨天 · 已过期"))
        assertFalse(Regex("\\d\\d:\\d\\d").containsMatchIn(text))
    }

    @Test
    fun todaysFinishedWorkIsAllListed() {
        val store = store()
        repeat(12) { store.setDone(store.add("第 $it 件", today).id, true) }
        val text = MemoCard.render(store)
        assertTrue(text.contains("[x] #12 第 11 件"))
        assertTrue(text.contains("[x] #1 第 0 件"))
        assertFalse(text.contains("已完成 12 条"))
    }
}
