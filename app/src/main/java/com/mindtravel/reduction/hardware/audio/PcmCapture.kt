package com.mindtravel.reduction.hardware.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import com.mindtravel.reduction.core.Logs

class PcmCapture {

    companion object {
        const val SAMPLE_RATE = WavEncoder.SAMPLE_RATE
        const val FRAME_SAMPLES = SAMPLE_RATE / 10
    }

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    fun isRunning(): Boolean = running

    @SuppressLint("MissingPermission")
    fun start(
        source: Int,
        onFrame: (ShortArray) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (worker?.isAlive == true) return false
        val current = ++generation
        running = true
        worker = Thread { loop(current, source, onFrame, onError) }.also { it.start() }
        return true
    }

    fun requestStop() {
        running = false
    }

    fun stop() {
        running = false
        if (Thread.currentThread() === worker) return
        record?.let { runCatching { it.stop() } }
        worker?.let { runCatching { it.join(1_500) } }
        worker = null
    }

    fun cancel() {
        generation++
        running = false
        record?.let { runCatching { it.stop() } }
        if (Thread.currentThread() !== worker) {
            worker?.let { runCatching { it.join(1_500) } }
            worker = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop(
        current: Int,
        source: Int,
        onFrame: (ShortArray) -> Unit,
        onError: (String) -> Unit
    ) {
        var recorder: AudioRecord? = null
        var errorMessage: String? = null

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            recorder = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, FRAME_SAMPLES * 4)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                errorMessage = "无法初始化麦克风"
            } else {
                record = recorder
                recorder.startRecording()
                val buffer = ShortArray(FRAME_SAMPLES)
                var named = false
                while (running && current == generation) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) {
                        if (read < 0) break
                        continue
                    }
                    if (!named) {
                        // Which microphone this really is: the log must be able to tell headset from phone.
                        named = true
                        val device = runCatching { recorder.routedDevice }.getOrNull()
                        Logs.add("Microphone input: type=${device?.type} ${device?.productName ?: "unknown"}")
                    }
                    onFrame(buffer.copyOf(read))
                }
            }
        } catch (t: Throwable) {
            errorMessage = t.message ?: "录音失败"
            Logs.add("PCM capture error: $errorMessage")
        } finally {
            if (record === recorder) record = null
            running = false
            recorder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            if (Thread.currentThread() === worker) worker = null
        }

        if (errorMessage != null && current == generation) {
            onError(errorMessage)
        }
    }
}
