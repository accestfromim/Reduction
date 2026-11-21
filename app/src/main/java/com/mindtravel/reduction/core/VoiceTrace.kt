package com.mindtravel.reduction.core

import java.io.File

/** Local, bounded timing journal. IDs are opaque and no utterance enters the journal. */
object VoiceTrace {
    private data class Turn(var stage: String, var nanos: Long)
    private val turns = LinkedHashMap<String, Turn>()
    private val safeId = Regex("[A-Za-z0-9_-]{1,80}")
    lateinit var directory: File

    @Synchronized fun start(id: String) {
        if (!safeId.matches(id)) return
        if (turns.size >= 128) turns.remove(turns.keys.first())
        turns[id] = Turn("record", System.nanoTime())
    }

    @Synchronized fun mark(id: String, stage: String) {
        val turn = turns[id] ?: return
        val expected = when (stage) {
            "release" -> "record"
            "asrstart" -> "release"
            "asrend" -> "asrstart"
            "token" -> "asrend"
            "audio" -> "token"
            else -> null
        }
        if (expected != null && turn.stage != expected) return
        val now = System.nanoTime()
        val line = "id=$id stage=$stage ts_ms=${System.currentTimeMillis()} elapsed_ms=${(now-turn.nanos)/1_000_000}"
        turn.stage = stage
        turn.nanos = now
        if (stage == "audio" || stage.endsWith("failed")) turns.remove(id)
        try {
            val file = File(directory, "voice-first-response.log")
            if (file.length() >= 256 * 1024) {
                for (i in 3 downTo 1) {
                    val source = if (i == 1) file else File(directory, "${file.name}.${i-1}")
                    if (source.exists()) check(source.renameTo(File(directory, "${file.name}.$i")))
                }
            }
            file.appendText(line + "\n")
        } catch (e: Exception) {
            System.err.println("voice journal unavailable: " + e.javaClass.simpleName)
        }
    }
}
