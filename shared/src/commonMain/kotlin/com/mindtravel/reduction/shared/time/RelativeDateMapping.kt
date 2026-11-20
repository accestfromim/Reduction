package com.mindtravel.reduction.shared.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

object RelativeDateMapping {
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

    fun dayOf(expression: String, referenceDate: String): String? {
        val reference = LocalDate.Formats.ISO.parseOrNull(referenceDate) ?: return null
        return resolveDay(expression, reference)?.toString()
    }

    fun context(text: String, referenceDate: String?): String {
        val reference = referenceDate?.let(LocalDate.Formats.ISO::parseOrNull)
            ?: return "［原消息日期缺失］\n$text"
        val resolved = expressions.replace(text) { match -> absolute(match.value, reference) }
        return "［原消息日期 $reference］\n$resolved"
    }

    fun display(date: String, referenceDate: String): String {
        val day = LocalDate.Formats.ISO.parseOrNull(date) ?: return date
        val reference = LocalDate.Formats.ISO.parseOrNull(referenceDate) ?: return date
        when (reference.daysUntil(day)) {
            -1 -> return "昨天"
            0 -> return "今天"
            1 -> return "明天"
            2 -> return "后天"
        }
        val start = monday(reference)
        val prefix = when (monday(day)) {
            start -> "这周"
            start.plus(7, DateTimeUnit.DAY) -> "下周"
            else -> null
        }
        if (prefix != null) return prefix + DAYS[day.dayOfWeek.isoDayNumber - 1]
        return if (day.year == reference.year) date.takeLast(5) else date
    }

    private fun resolveDay(expression: String, reference: LocalDate): LocalDate? {
        offsets[expression]?.let { return reference.plus(it, DateTimeUnit.DAY) }
        daysAway.matchEntire(expression)?.let { match ->
            val count = number(match.groupValues[1])
            return reference.plus(if (match.groupValues[2] == "前") -count else count, DateTimeUnit.DAY)
        }
        weekday.matchEntire(expression)?.let { match ->
            val day = DAYS.indexOf(match.groupValues[2].replace("天", "日")) + 1
            val delta = day - reference.dayOfWeek.isoDayNumber
            val weeks = periodOffsets[match.groupValues[1]]
            return reference.plus(if (weeks == null) delta.mod(7) else weeks * 7 + delta, DateTimeUnit.DAY)
        }
        monthDay.matchEntire(expression)?.let { match ->
            val month = LocalDate(reference.year, reference.monthNumber, 1)
                .plus(periodOffsets.getValue(match.groupValues[1]), DateTimeUnit.MONTH)
            val day = number(match.groupValues[2])
            val monthLength = month.daysUntil(month.plus(1, DateTimeUnit.MONTH))
            return if (day in 1..monthLength) month.plus(day - 1, DateTimeUnit.DAY) else null
        }
        return null
    }

    private fun absolute(phrase: String, reference: LocalDate): String {
        resolveDay(phrase, reference)?.let { day ->
            return when (phrase) {
                "今早", "明早" -> "$day 早上"
                "昨晚", "今晚", "明晚" -> "$day 晚上"
                else -> day.toString()
            }
        }
        val range = weekRange.matchEntire(phrase) ?: return phrase
        val start = monday(reference).plus(periodOffsets.getValue(range.groupValues[1]) * 7, DateTimeUnit.DAY)
        val first = if (range.groupValues[2] == "末") start.plus(5, DateTimeUnit.DAY) else start
        val last = start.plus(6, DateTimeUnit.DAY)
        return "$first 至 $last"
    }

    private fun monday(day: LocalDate): LocalDate = day.plus(1 - day.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)

    private fun number(raw: String): Int {
        raw.toIntOrNull()?.let { return it }
        val digits = "一二三四五六七八九"
        fun digit(value: String): Int = digits.indexOf(value.replace("两", "二")) + 1
        val parts = raw.split('十')
        if (parts.size == 1) return digit(raw)
        val tens = if (parts[0].isEmpty()) 1 else digit(parts[0])
        val units = if (parts[1].isEmpty()) 0 else digit(parts[1])
        return tens * 10 + units
    }
}
