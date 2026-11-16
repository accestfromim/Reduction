package com.mindtravel.reduction.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsTest {

    @Test
    fun sinceReturnsOnlyWhatIsNew() {
        val (_, start) = Logs.since(0)
        Logs.add("first")
        Logs.add("second")

        val (lines, next) = Logs.since(start)
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(" first") && lines[1].endsWith(" second"))

        assertEquals(emptyList<String>(), Logs.since(next).first)
        Logs.add("third")
        assertEquals(1, Logs.since(next).first.size)
    }

    @Test
    fun keysAreMasked() {
        val (_, start) = Logs.since(0)
        Logs.add("key=sk-abcDEF123 Authorization: Bearer xyz.123")
        val line = Logs.since(start).first.single()
        assertTrue(line, line.contains("sk-***") && line.contains("Bearer ***") && !line.contains("abcDEF123"))
    }
}
