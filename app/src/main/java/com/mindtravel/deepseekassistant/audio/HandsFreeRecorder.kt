package com.mindtravel.deepseekassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mindtravel.deepseekassistant.core.Logs
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

class HandsFreeRecorder {

    companion object {
        private const val SAMPLE_RATE = WavEncoder.SAMPLE_RATE
        private const val FRAME_SAMPLES = SAMPLE_RATE / 10
    }

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var canceled = false

    fun start(
        followup: Boolean,
        onSpeechStart: () -> Unit,
        onSpeechEnd: (ByteArray) -> Unit,
        onTimeout: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        running = true
        canceled = false
        Logs.add("Hands-free start followup=$followup")

        worker = Thread {
            val pcm = ByteArrayOutputStream()
            val preRoll = ArrayDeque<ShortArray>()
            val vad = SpeechVad().apply { reset() }
            var recorder: AudioRecord? = null
            var speechStarted = false
            var timedOut = false
            var errorMessage: String? = null
            var resultWav: ByteArray? = null

            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = createRecorder(minBuffer)
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    errorMessage = "无法初始化麦克风"
                } else {
                    audioRecord = recorder
                    recorder.startRecording()

                    val buffer = ShortArray(FRAME_SAMPLES)
                    while (running) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read <= 0) continue
                        val frame = buffer.copyOf(read)

                        if (!speechStarted) {
                            preRoll.addLast(frame)
                            while (preRoll.size > 3) preRoll.removeFirst()
                        }

                        when (vad.process(frame)) {
                            SpeechVad.Event.WAITING -> Unit
                            SpeechVad.Event.SPEECH_START -> {
                                speechStarted = true
                                Logs.add("VAD speech start threshold=${vad.threshold().toInt()}")
                                onSpeechStart()
                                for (item in preRoll) writeShortArray(pcm, item)
                                preRoll.clear()
                            }
                            SpeechVad.Event.SPEECH_CONTINUE -> writeShortArray(pcm, frame)
                            SpeechVad.Event.SPEECH_END -> {
                                Logs.add("VAD speech end")
                                break
                            }
                            SpeechVad.Event.TIMEOUT -> {
                                Logs.add("VAD timeout")
                                timedOut = true
                                break
                            }
                        }
                    }

                    val data = pcm.toByteArray()
                    if (speechStarted && data.isNotEmpty()) {
                        resultWav = WavEncoder.pcm16ToWav(data)
                    }
                }
            } catch (t: Throwable) {
                Logs.add("Hands-free error: ${t.message}")
                errorMessage = t.message ?: "录音失败"
            } finally {
                recorder?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
                if (audioRecord === recorder) audioRecord = null
                running = false
                Logs.add("Hands-free recording released")
            }

            if (canceled) return@Thread

            when {
                resultWav != null -> onSpeechEnd(resultWav)
                errorMessage != null -> onError(errorMessage)
                timedOut -> onTimeout()
            }
            Logs.add("Hands-free stopped")
        }.also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(minBuffer: Int): AudioRecord = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuffer * 2, FRAME_SAMPLES * 4)
    )

    fun cancel() {
        canceled = true
        running = false
        worker?.let { runCatching { it.join(1_000) } }
        worker = null
        audioRecord?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioRecord = null
    }

    private fun writeShortArray(out: ByteArrayOutputStream, frame: ShortArray) {
        for (sample in frame) {
            val value = sample.toInt()
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
    }
}
