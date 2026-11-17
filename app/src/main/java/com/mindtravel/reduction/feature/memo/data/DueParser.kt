package com.mindtravel.reduction.feature.memo.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns what was said about when into a date and a time, once, at the moment the to-do
 * is written. Afterwards the to-do carries a moment, not a phrase: "明早" means the same
 * thing the day after as it did the day it was heard.
 *
 * Dates come as yyyy-MM-dd — the assistant is asked to work relative words like "后天"
 * out from the today it is given — or, as a fallback, in a few everyday forms.
 * Times come as HH:mm; without one, nine in the morning, when a day's reminders are
 * worth giving.
 */
object DueParser {

    class Parsed(val date: LocalDate, val hour: Int, val minute: Int, val assumedTime: Boolean)

    /** The time given to a to-do whose day was said but not its hour. */
    /** What a date is called when it was left unfilled. */
    const val EMPTY = "empty"

    const val DEFAULT_HOUR = 9
    const val DEFAULT_MINUTE = 0

    private val HHMM = DateTimeFormatter.ofPattern("H:mm")
    private val FULL_FORMS = listOf(
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d")
    )

    /** A month and a day without a year: 11-18, 11/18. */
    private val MONTH_DAY = Regex("^(\\d{1,2})[-/](\\d{1,2})$")

    /**
     * [date] as yyyy-MM-dd or one of the everyday forms; [time] as HH:mm or 早上八点 and
     * the like. Null when the date cannot be read. An unreadable time counts as none.
     */
    fun parse(date: String?, time: String?, today: LocalDate): Parsed? {
        val day = dayOf(date, today) ?: return null
        val clock = timeOf(time)
        return if (clock == null) {
            Parsed(day, DEFAULT_HOUR, DEFAULT_MINUTE, assumedTime = true)
        } else {
            Parsed(day, clock.hour, clock.minute, assumedTime = false)
        }
    }

    fun dayOf(raw: String?, today: LocalDate): LocalDate? {
        val text = raw?.trim()?.replace("：", ":")
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

    /** A time of day, or null: 8:30, 08:30, 早上八点, 下午三点半, 晚上九点一刻. */
    fun timeOf(raw: String?): LocalTime? {
        val text = raw?.trim()?.replace("：", ":")?.replace(" ", "")
        if (text.isNullOrEmpty()) return null
        runCatching { return LocalTime.parse(text, HHMM) }

        val half = text.contains("半")
        val quarter = text.contains("一刻")
        val afternoon = Regex("下午|傍晚|晚上|夜里").containsMatchIn(text)
        val noon = text.startsWith("中午")
        val morning = Regex("早上|早晨|上午|凌晨").containsMatchIn(text)

        var hour: Int? = null
        var minute = if (half) 30 else if (quarter) 15 else 0
        val digits = Regex("(\\d{1,2})\\s*[:点时]\\s*(\\d{1,2})?").find(text)
        if (digits != null) {
            hour = digits.groupValues[1].toIntOrNull()
            minute = digits.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: minute
        } else {
            // The first run of Chinese numerals is the hour: 八, 十一, 二十三.
            var at = 0
            while (at < text.length) {
                val first = numeral(text[at])
                if (first == null) {
                    at++
                    continue
                }
                var number: Int = first
                var step = 1
                while (at + step < text.length && step < 3) {
                    val next = numeral(text[at + step]) ?: break
                    number = when {
                        next == 10 && number < 10 -> number * 10
                        number == 10 -> 10 + next
                        else -> number * 10 + next
                    }
                    step++
                }
                hour = number
                break
            }
        }
        var value = hour ?: return null
        if ((afternoon || noon) && value in 1..11) value += 12
        if (morning && value == 12) value = 0
        if (value !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(value, minute)
    }

    private fun numeral(c: Char): Int? = when (c) {
        '零' -> 0
        '一' -> 1
        '二', '两' -> 2
        '三' -> 3
        '四' -> 4
        '五' -> 5
        '六' -> 6
        '七' -> 7
        '八' -> 8
        '九' -> 9
        '十' -> 10
        else -> null
    }

    /** A moment as the card and the reminder say it: 今天 09:00, 11-18 09:00, 2027-01-03 09:00. */
    fun describe(moment: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(moment).atZone(zone).toLocalDateTime()
        val clock = "%02d:%02d".format(at.hour, at.minute)
        return when (at.toLocalDate()) {
            today -> "今天 $clock"
            today.plusDays(1) -> "明天 $clock"
            today.plusDays(2) -> "后天 $clock"
            else -> if (at.year == today.year) {
                "%02d-%02d %s".format(at.monthValue, at.dayOfMonth, clock)
            } else {
                "%04d-%02d-%02d %s".format(at.year, at.monthValue, at.dayOfMonth, clock)
            }
        }
    }
}
