package com.mindtravel.deepseekassistant.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object Logs {
    private const val MAX_LINES = 120
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        val clean = message
            .replace(Regex("sk-[A-Za-z0-9_-]+"), "sk-***")
            .replace(Regex("Bearer\\s+\\S+"), "Bearer ***")
        lines.addLast("${formatter.format(Date())} $clean")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
