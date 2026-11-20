package com.mindtravel.reduction.core.time

import com.mindtravel.reduction.shared.time.RelativeDateMapping
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

object RelativeDates {
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

    fun dayOf(expression: String, reference: LocalDate): LocalDate? =
        RelativeDateMapping.dayOf(expression, reference.toString())?.let(LocalDate::parse)

    fun context(text: String, reference: LocalDate?): String =
        RelativeDateMapping.context(text, reference?.toString())

    fun display(day: LocalDate, reference: LocalDate): String =
        RelativeDateMapping.display(day.toString(), reference.toString())
}
