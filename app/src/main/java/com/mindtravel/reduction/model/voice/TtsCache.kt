package com.mindtravel.reduction.model.voice

import java.io.File
import java.security.MessageDigest

/** Synthesised speech kept in [dir], so the same words are not paid for twice. */
class TtsCache(private val dir: File) {

    companion object {
        private const val MAX_BYTES = 50L * 1024 * 1024
        private const val TRIM_TO_BYTES = 40L * 1024 * 1024
    }

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun get(voice: String, text: String): ByteArray? {
        val file = File(dir, key(voice, text))
        if (!file.exists()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes != null) file.setLastModified(System.currentTimeMillis())
        return bytes
    }

    @Synchronized
    fun put(voice: String, text: String, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val file = File(dir, key(voice, text))
        val tmp = File(dir, file.name + ".tmp")
        runCatching {
            tmp.writeBytes(pcm)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
        trim()
    }

    @Synchronized
    fun clear(): Long {
        val before = size()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        return before
    }

    @Synchronized
    fun size(): Long {
        return dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun trim() {
        var total = size()
        if (total <= MAX_BYTES) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (file in files) {
            if (total <= TRIM_TO_BYTES) break
            val length = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= length
            }
        }
    }

    private fun key(voice: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(voice.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".pcm"
    }
}
