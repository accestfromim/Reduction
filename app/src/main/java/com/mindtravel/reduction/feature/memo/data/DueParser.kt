package com.mindtravel.reduction.feature.memo.data

import com.mindtravel.reduction.core.time.RelativeDates
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Turns what was said about when into a day, once, at the moment the to-do is written.
 * Afterwards the to-do carries a date, not a phrase: "明天" means the same thing the day
 * after as it did the day it was heard. A to-do is due on a day; it has no hour.
 *
 * Dates come as yyyy-MM-dd — the assistant is asked to work relative words like "后天"
 * out from the today it is given — or, as a fallback, in a few everyday forms.
 */
object DueParser {

    /** What a date is called when it was left unfilled. */
    const val EMPTY = "empty"

    private val FULL_FORMS = listOf(
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d")
    )

    /** A month and a day without a year: 11-18, 11/18. */
    private val MONTH_DAY = Regex("^(\\d{1,2})[-/](\\d{1,2})$")

    /** [raw] as yyyy-MM-dd or one of the everyday forms; null when it cannot be read. */
    fun dayOf(raw: String?, today: LocalDate): LocalDate? {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return null
        for (form in FULL_FORMS) {
            runCatching { return LocalDate.parse(text, form) }
        }
        // A day without a year is the next time that day comes round: this year, or the next.
        MONTH_DAY.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return null
            val day = match.groupValues[2].toIntOrNull() ?: return null
            runCatching { LocalDate.of(today.year, month, day) }.onSuccess { date ->
                return if (date.isBefore(today)) date.plusYears(1) else date
            }
        }
        return RelativeDates.dayOf(text, today)
    }

    /** 日期和可选时段。传入查看日期时换算为界面标签。 */
    fun describe(day: LocalDate, part: MemoStore.Part?, today: LocalDate? = null): String {
        val label = today?.let { describe(day, it) } ?: day.toString()
        return label + (part?.let { " " + it.label } ?: "")
    }

    /** 随查看日期生成卡片与提醒中的自然日期。 */
    fun describe(day: LocalDate, today: LocalDate): String = RelativeDates.display(day, today)
}
