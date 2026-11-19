package com.mindtravel.reduction.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

object RelativeDates {
    private val offsets = mapOf(
        "大前天" to -3, "前天" to -2, "昨天" to -1, "昨日" to -1, "昨晚" to -1,
        "今天" to 0, "今日" to 0, "今早" to 0, "今晚" to 0,
        "明天" to 1, "明日" to 1, "明早" to 1, "明晚" to 1, "后天" to 2, "大后天" to 3
    )
    private val periodOffsets = mapOf("上上" to -2, "上" to -1, "本" to 0, "这" to 0, "下" to 1, "下下" to 2)
    private const val PERIOD = "(上上|下下|上|下|本|这)"
    private const val WEEKDAY = "(?:周|星期|礼拜)([一二三四五六日天])"
    private const val COUNT = "[0-9]{1,4}|[一二两三四五六七八九]?十[一二三四五六七八九]?|[一二两三四五六七八九]"
    private val daysAway = Regex("(?<![0-9.零〇一二两三四五六七八九十百千万第几])($COUNT)[ \t]*天(?:以|之)?([前后])")
    private val weekday = Regex("(?:$PERIOD(?:个)?)?$WEEKDAY")
    private val monthDay = Regex("$PERIOD(?:个)?月[ \t]*($COUNT)[日号]")
    private val weekRange = Regex("$PERIOD(?:个)?(?:周|星期|礼拜)(末)?(?![一二三四五六日天几末])")
    private const val VERBATIM = "```[\\s\\S]*?```|`[^`\\n]*`|“[^”]*”|‘[^’]*’|「[^」]*」|『[^』]*』|\"[^\"\\n]*\""
    private const val DAYS = "一二三四五六日"
    private val expressions = Regex(
        (listOf(VERBATIM) + offsets.keys.sortedByDescending { it.length } +
            listOf("$PERIOD(?:个)?$WEEKDAY", daysAway.pattern, monthDay.pattern, weekRange.pattern)).joinToString("|")
    )

    fun localDay(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

    // 缺少字段的旧消息有时间戳可用。空字段表示旧记录没有实际发送时间。
    fun messageDay(referenceDate: String?, at: Long): LocalDate? {
        if (referenceDate == null) return if (at > 0) localDay(at) else null
        if (referenceDate.isEmpty()) return null
        return try {
            LocalDate.parse(referenceDate)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun dayOf(expression: String, reference: LocalDate): LocalDate? {
        offsets[expression]?.let { return reference.plusDays(it.toLong()) }
        daysAway.matchEntire(expression)?.let { match ->
            val count = number(match.groupValues[1])
            return reference.plusDays(if (match.groupValues[2] == "前") -count else count)
        }
        weekday.matchEntire(expression)?.let { match ->
            val day = DAYS.indexOf(match.groupValues[2].replace("天", "日")) + 1
            val delta = day - reference.dayOfWeek.value
            val weeks = periodOffsets[match.groupValues[1]]
            return reference.plusDays(if (weeks == null) Math.floorMod(delta, 7).toLong() else (weeks * 7 + delta).toLong())
        }
        monthDay.matchEntire(expression)?.let { match ->
            val month = reference.withDayOfMonth(1).plusMonths(periodOffsets.getValue(match.groupValues[1]).toLong())
            val day = number(match.groupValues[2])
            return if (day in 1L..month.lengthOfMonth().toLong()) month.withDayOfMonth(day.toInt()) else null
        }
        return null
    }

    fun context(text: String, reference: LocalDate?): String {
        if (reference == null) return "［原消息日期缺失］\n$text"
        val resolved = expressions.replace(text) { match -> absolute(match.value, reference) }
        return "［原消息日期 $reference］\n$resolved"
    }

    fun display(day: LocalDate, reference: LocalDate): String {
        when (ChronoUnit.DAYS.between(reference, day)) {
            -1L -> return "昨天"
            0L -> return "今天"
            1L -> return "明天"
            2L -> return "后天"
        }
        val prefix = when (ChronoUnit.WEEKS.between(monday(reference), monday(day))) {
            0L -> "这周"
            1L -> "下周"
            else -> null
        }
        if (prefix != null) return prefix + DAYS[day.dayOfWeek.value - 1]
        return if (day.year == reference.year) day.toString().substring(5) else day.toString()
    }

    private fun absolute(phrase: String, reference: LocalDate): String {
        dayOf(phrase, reference)?.let { day ->
            return when (phrase) {
                "今早", "明早" -> "$day 早上"
                "昨晚", "今晚", "明晚" -> "$day 晚上"
                else -> day.toString()
            }
        }
        val range = weekRange.matchEntire(phrase) ?: return phrase
        val start = monday(reference).plusWeeks(periodOffsets.getValue(range.groupValues[1]).toLong())
        val first = if (range.groupValues[2] == "末") start.plusDays(5) else start
        return "$first 至 ${start.plusDays(6)}"
    }

    private fun monday(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    private fun number(raw: String): Long {
        raw.toLongOrNull()?.let { return it }
        val digits = "一二三四五六七八九"
        fun digit(value: String): Long = (digits.indexOf(value.replace("两", "二")) + 1).toLong()
        val parts = raw.split('十')
        if (parts.size == 1) return digit(raw)
        val tens = if (parts[0].isEmpty()) 1 else digit(parts[0])
        val units = if (parts[1].isEmpty()) 0 else digit(parts[1])
        return tens * 10 + units
    }
}
