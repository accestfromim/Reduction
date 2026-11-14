package com.mindtravel.deepseekassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mindtravel.deepseekassistant.core.Logs
import java.io.ByteArrayOutputStream

class WavRecorder {

    companion object {
        private const val MAX_SECONDS = 100
        private const val SAMPLE_RATE = WavEncoder.SAMPLE_RATE
    }

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var recording = false

    @Volatile
    private var startedAt = 0L

    @SuppressLint("MissingPermission")
    fun start(onLimit: () -> Unit): Boolean {
        if (recording) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
        } catch (t: Throwable) {
            Logs.add("AudioRecord init failed: ${t.message}")
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        synchronized(pcm) { pcm.reset() }
        startedAt = System.currentTimeMillis()
        audioRecord = record
        recording = true

        thread = Thread {
            try {
                record.startRecording()
                val buffer = ByteArray(bufferSize)
                while (recording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        synchronized(pcm) { pcm.write(buffer, 0, read) }
                        if ((System.currentTimeMillis() - startedAt) / 1000 >= MAX_SECONDS) {
                            recording = false
                            onLimit()
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (t: Throwable) {
                Logs.add("Record error: ${t.message}")
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }.also { it.start() }

        return true
    }

    fun stop(): ByteArray? {
        if (!recording && audioRecord == null) return null
        recording = false
        thread?.let { runCatching { it.join(1_800) } }
        thread = null
        audioRecord = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    fun cancel() {
        recording = false
        thread?.let { runCatching { it.join(800) } }
        thread = null
        audioRecord = null
        synchronized(pcm) { pcm.reset() }
    }

    fun isRecording(): Boolean = recording
}
