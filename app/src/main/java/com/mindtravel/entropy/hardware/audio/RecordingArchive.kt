package com.mindtravel.entropy.hardware.audio

import com.mindtravel.entropy.core.Logs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps what the microphone recorded until it has been handed over to the computer.
 * Each recording is a WAV file named after the moment it was kept and its kind; what
 * was heard in it, when known, sits beside it in a text file of the same name.
 * When the phone stays away from the computer for long, the oldest recordings go first.
 */
class RecordingArchive(
    private val directory: File,
    private val maxBytes: Long = 300L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** A recording waiting to be handed over: the audio and, possibly, its transcript. */
    class Item(val audio: File, val note: File?)

    @Synchronized
    fun keep(kind: String, wav: ByteArray, note: String = "") {
        if (wav.isEmpty()) return
        try {
            directory.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now()))
            val name = "$stamp-${kind.filter { it.isLetterOrDigit() }.ifEmpty { "audio" }}"
            // Written under another name first: a file with the final name is always whole.
            val partial = File(directory, "$name.tmp")
            partial.writeBytes(wav)
            if (note.isNotBlank()) File(directory, "$name.txt").writeText(note.trim() + "\n")
            partial.renameTo(File(directory, "$name.wav"))
            Logs.add("Recording kept: $name.wav ${wav.size / 1024} KB")
            trim()
        } catch (t: Throwable) {
            Logs.add("Recording not kept: ${t.message}")
        }
    }

    /** What is waiting, oldest first. */
    @Synchronized
    fun pending(): List<Item> = audioFiles().map { audio ->
        Item(audio, File(directory, audio.nameWithoutExtension + ".txt").takeIf { it.isFile })
    }

    @Synchronized
    fun remove(item: Item) {
        item.audio.delete()
        item.note?.delete()
    }

    private fun audioFiles(): List<File> =
        directory.listFiles { file -> file.isFile && file.extension == "wav" }.orEmpty().sortedBy { it.name }

    private fun trim() {
        val files = audioFiles().toMutableList()
        var total = files.sumOf { it.length() }
        while (total > maxBytes && files.size > 1) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
            File(directory, oldest.nameWithoutExtension + ".txt").delete()
        }
    }
}
