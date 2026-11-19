package com.mindtravel.reduction.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelativeDateMappingTest {
    @Test
    fun oldMessageKeepsItsDateWhileThePlanLabelChanges() {
        val sentOn = "2026-11-18"
        val viewedOn = "2026-11-19"
        val due = RelativeDateMapping.dayOf("明天", sentOn)

        assertEquals("2026-11-19", due)
        assertEquals(
            "［原消息日期 2026-11-18］\n2026-11-19早上归档，2026-11-24复盘",
            RelativeDateMapping.context("明天早上归档，下周二复盘", sentOn)
        )
        assertEquals("明天", RelativeDateMapping.display("2026-11-19", sentOn))
        assertEquals("今天", RelativeDateMapping.display("2026-11-19", viewedOn))
        assertEquals("2026-11-20", RelativeDateMapping.dayOf("明天", viewedOn))
    }

    @Test
    fun weeksAndWeekendsCanCrossTheYearBoundary() {
        val reference = "2026-12-31"

        assertEquals("2026-12-29", RelativeDateMapping.dayOf("这周二", reference))
        assertEquals("2027-01-05", RelativeDateMapping.dayOf("下周二", reference))
        assertEquals("下周二", RelativeDateMapping.display("2027-01-05", reference))
        assertEquals(
            "［原消息日期 2026-12-31］\n2026-12-28 至 2027-01-03整理，2027-01-09 至 2027-01-10休息",
            RelativeDateMapping.context("这周整理，下周末休息", reference)
        )
    }

    @Test
    fun monthsRespectTheCalendarAndResolveChineseCounts() {
        assertEquals("2028-02-29", RelativeDateMapping.dayOf("下个月二十九号", "2028-01-31"))
        assertNull(RelativeDateMapping.dayOf("下个月二十九号", "2026-01-31"))
        assertEquals("2026-12-01", RelativeDateMapping.dayOf("两天后", "2026-11-29"))
        assertEquals("2026-11-19", RelativeDateMapping.dayOf("星期四", "2026-11-19"))
    }

    @Test
    fun quotedDatesAndCodeStayVerbatim() {
        assertEquals(
            "［原消息日期 2026-11-18］\n“明天”和 `下周二`，2026-11-19 早上归档",
            RelativeDateMapping.context("“明天”和 `下周二`，明早归档", "2026-11-18")
        )
    }

    @Test
    fun unknownMessageDateDoesNotUseTodaysDate() {
        val text = "明天归档"
        assertEquals("［原消息日期缺失］\n$text", RelativeDateMapping.context(text, null))
        assertEquals("［原消息日期缺失］\n$text", RelativeDateMapping.context(text, ""))
        assertEquals("［原消息日期缺失］\n$text", RelativeDateMapping.context(text, "2026-02-30"))
    }
}
