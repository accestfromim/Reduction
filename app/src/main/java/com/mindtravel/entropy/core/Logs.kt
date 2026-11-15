package com.mindtravel.entropy.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** The app's running log: kept in memory, numbered so a reader can ask for what is new. */
object Logs {
    private const val MAX_LINES = 2_000
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Number of the next line to be written. */
    private var next = 0L

    @Synchronized
    fun add(message: String) {
        val clean = message
            .replace(Regex("sk-[A-Za-z0-9_-]+"), "sk-***")
            .replace(Regex("Bearer\\s+\\S+"), "Bearer ***")
        lines.addLast("${formatter.format(Date())} $clean")
        next++
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.toList().takeLast(300).joinToString("\n")

    /**
     * The lines written since [seq], and the number to pass next time.
     * Start with 0; lines that have already scrolled out of memory are skipped.
     */
    @Synchronized
    fun since(seq: Long): Pair<List<String>, Long> {
        val first = next - lines.size
        val skip = (seq - first).coerceIn(0L, lines.size.toLong()).toInt()
        return lines.toList().drop(skip) to next
    }
}
