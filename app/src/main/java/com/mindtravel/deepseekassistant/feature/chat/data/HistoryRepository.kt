package com.mindtravel.deepseekassistant.feature.chat.data

import android.content.Context
import java.io.File

class HistoryRepository(context: Context) {
    private val file = java.io.File(context.filesDir, "chat_history.json")

    fun load(): String = if (file.exists()) {
        runCatching { file.readText() }.getOrDefault("[]")
    } else {
        "[]"
    }

    @Synchronized
    fun save(json: String): Boolean = runCatching {
        val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }.isSuccess

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }
}
