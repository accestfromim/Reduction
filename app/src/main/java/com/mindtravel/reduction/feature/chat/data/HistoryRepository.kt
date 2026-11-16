package com.mindtravel.reduction.feature.chat.data

import java.io.File

/** The conversation so far, kept in [file]. */
class HistoryRepository(private val file: File) {

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
