package com.mindtravel.deepseekassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mindtravel.deepseekassistant.core.Logs

class BargeInDetector(
    private val onBargeIn: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = WavEncoder.SAMPLE_RATE
        private const val FRAME_SAMPLES = SAMPLE_RATE / 10
    }

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        worker = Thread { loop() }.also { it.start() }
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(800) } }
        worker = null
        audioRecord?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioRecord = null
        Logs.add("Barge-in stopped")
    }

    fun isRunning(): Boolean = running

    @SuppressLint("MissingPermission")
    private fun loop() {
        var recorder: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, FRAME_SAMPLES * 4)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                onError("打断检测麦克风初始化失败")
                return
            }
            audioRecord = recorder
            recorder.startRecording()
            val vad = SpeechVad(waitTimeoutMs = 10 * 60_000L).apply { reset() }
            val buffer = ShortArray(FRAME_SAMPLES)
            Logs.add("Barge-in listening threshold=${vad.threshold().toInt()}")

            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val event = vad.process(buffer.copyOf(read))
                if (event == SpeechVad.Event.SPEECH_START) {
                    Logs.add("Barge-in detected threshold=${vad.threshold().toInt()}")
                    running = false
                    onBargeIn()
                    return
                }
            }
        } catch (t: Throwable) {
            Logs.add("Barge-in error: ${t.message}")
            onError(t.message ?: "打断检测失败")
        } finally {
            recorder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            if (audioRecord === recorder) audioRecord = null
            running = false
        }
    }
}
