package com.mindtravel.deepseekassistant.platform.audio

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
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    @Volatile
    private var canceled = false

    @Volatile
    private var speechStarted = false

    @Volatile
    private var lastVoiceAt = 0L

    fun start(
        followup: Boolean,
        useCommunicationSource: Boolean = false,
        onSpeechStart: () -> Unit,
        onTimeout: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        running = true
        canceled = false
        speechStarted = false
        lastVoiceAt = 0L
        synchronized(pcm) { pcm.reset() }
        Logs.add("Hands-free start followup=$followup")

        worker = Thread {
            val preRoll = ArrayDeque<ShortArray>()
            val vad = SpeechVad().apply { reset() }
            var recorder: AudioRecord? = null
            var timedOut = false
            var errorMessage: String? = null

            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                recorder = createRecorder(minBuffer, useCommunicationSource)
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
                        val event = vad.process(frame)

                        if (!speechStarted) {
                            preRoll.addLast(frame)
                            while (preRoll.size > 3) preRoll.removeFirst()
                            when (event) {
                                SpeechVad.Event.SPEECH_START -> {
                                    speechStarted = true
                                    lastVoiceAt = System.currentTimeMillis()
                                    Logs.add("VAD speech start threshold=${vad.threshold().toInt()}")
                                    onSpeechStart()
                                    for (item in preRoll) writeShortArray(pcm, item)
                                    preRoll.clear()
                                }
                                SpeechVad.Event.TIMEOUT -> {
                                    Logs.add("VAD timeout")
                                    timedOut = true
                                    break
                                }
                                else -> Unit
                            }
                        } else {
                            // Continuous capture: silence frames stay in the WAV for rolling ASR.
                            writeShortArray(pcm, frame)
                            if (vad.lastFrameWasSpeech) lastVoiceAt = System.currentTimeMillis()
                        }
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
                errorMessage != null -> onError(errorMessage)
                timedOut -> onTimeout()
            }
            Logs.add("Hands-free stopped")
        }.also { it.start() }
    }

    fun snapshotWav(): ByteArray? {
        if (!speechStarted) return null
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    fun stop(): ByteArray? {
        running = false
        audioRecord?.let { runCatching { it.stop() } }
        worker?.let { runCatching { it.join(1_500) } }
        worker = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        val hadSpeech = speechStarted
        speechStarted = false
        lastVoiceAt = 0L
        if (!hadSpeech || data.isEmpty()) return null
        return WavEncoder.pcm16ToWav(data)
    }

    fun hasVoiceSince(timestampMs: Long): Boolean = lastVoiceAt > timestampMs

    fun cancel() {
        canceled = true
        running = false
        audioRecord?.let { runCatching { it.stop() } }
        worker?.let { runCatching { it.join(1_000) } }
        worker = null
        audioRecord?.let {
            runCatching { it.release() }
        }
        audioRecord = null
        synchronized(pcm) { pcm.reset() }
        speechStarted = false
        lastVoiceAt = 0L
    }

    private fun writeShortArray(out: ByteArrayOutputStream, frame: ShortArray) {
        synchronized(pcm) {
            for (sample in frame) {
                val value = sample.toInt()
                out.write(value and 0xff)
                out.write((value shr 8) and 0xff)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(minBuffer: Int, useCommunicationSource: Boolean): AudioRecord = AudioRecord(
        if (useCommunicationSource) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBuffer * 2, FRAME_SAMPLES * 4)
    )
}
