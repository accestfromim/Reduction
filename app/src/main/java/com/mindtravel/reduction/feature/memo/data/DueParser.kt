package com.mindtravel.reduction.feature.memo.data

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
        when (text) {
            "今天", "今日" -> return today
            "明天", "明日" -> return today.plusDays(1)
            "后天" -> return today.plusDays(2)
            "大后天" -> return today.plusDays(3)
        }
        Regex("^(\\d{1,3})\\s*天(?:以)?后$").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let {
            return today.plusDays(it)
        }
        val weekday = Regex("^(下|这|本)?(?:周|星期|礼拜)([一二三四五六日天])$").find(text)
        if (weekday != null) {
            val target = "一二三四五六日".indexOf(weekday.groupValues[2].replace("天", "日")) + 1
            if (target == 0) return null
            var ahead = (target - today.dayOfWeek.value + 7) % 7
            // "周三" said on a Wednesday is today; "下周三" never is.
            if (weekday.groupValues[1] == "下") {
                if (ahead == 0) ahead = 7 else ahead += 7
            }
            return today.plusDays(ahead.toLong())
        }
        return null
    }

    /** A day and, when there is one, the part of it: 明天 下午, 11-18. */
    fun describe(day: LocalDate, part: MemoStore.Part?, today: LocalDate): String =
        describe(day, today) + (part?.let { " " + it.label } ?: "")

    /** A day as the card and the reminder say it: 今天, 明天, 11-18, 2027-01-03. */
    fun describe(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> if (day.year == today.year) {
            "%02d-%02d".format(day.monthValue, day.dayOfMonth)
        } else {
            "%04d-%02d-%02d".format(day.year, day.monthValue, day.dayOfMonth)
        }
    }
}
