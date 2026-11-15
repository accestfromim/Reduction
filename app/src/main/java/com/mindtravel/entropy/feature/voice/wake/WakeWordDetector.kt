package com.mindtravel.entropy.feature.voice.wake

import android.media.MediaRecorder
import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.hardware.audio.PcmCapture
import com.mindtravel.entropy.hardware.audio.WavEncoder
import com.mindtravel.entropy.model.wake.KeywordSpotterModel

/** Feeds the microphone into the keyword spotter while the assistant is idle. */
class WakeWordDetector(
    private val model: KeywordSpotterModel,
    private val onWake: () -> Unit,
    /** The audio that led to a detection, so detections can be checked against what was really said. */
    private val onClip: (wav: ByteArray, keyword: String) -> Unit,
    private val onListening: () -> Unit,
    /** The microphone stopped, or started again, delivering real audio. */
    private val onDeaf: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val deafWatch = DeafWatch()

    private val capture = PcmCapture()

    /** The last few seconds heard, oldest first. Touched only on the capture thread. */
    private val recent = ArrayDeque<ShortArray>()

    @Volatile
    private var detected = false

    fun start() {
        if (capture.isRunning()) return
        detected = false
        recent.clear()
        try {
            model.open()
        } catch (t: Throwable) {
            Logs.add("Wake word init error: ${t.message}")
            onError(t.message ?: "唤醒词初始化失败")
            return
        }
        onListening()
        deafWatch.reset()
        Logs.add("Wake word listening started")

        val started = capture.start(
            source = MediaRecorder.AudioSource.MIC,
            onFrame = { frame ->
                if (deafWatch.onFrame(frame)) {
                    Logs.add(if (deafWatch.deaf) "Wake word: microphone is delivering silence" else "Wake word: microphone is back")
                    onDeaf(deafWatch.deaf)
                }
                if (!detected) {
                    recent.addLast(frame.copyOf())
                    if (recent.size > CLIP_FRAMES) recent.removeFirst()
                    val keyword = model.accept(frame, PcmCapture.SAMPLE_RATE)
                    if (keyword != null) {
                        detected = true
                        Logs.add("Wake word detected: $keyword")
                        capture.requestStop()
                        onWake()
                        runCatching { onClip(clip(), keyword) }
                        recent.clear()
                    }
                }
            },
            onError = { message ->
                Logs.add("Wake word loop error: $message")
                if (!detected) onError(message)
            }
        )
        if (!started) onError("无法启动唤醒词麦克风")
    }

    private fun clip(): ByteArray {
        val pcm = ByteArray(recent.sumOf { it.size } * 2)
        var at = 0
        for (frame in recent) {
            for (sample in frame) {
                pcm[at++] = (sample.toInt() and 0xff).toByte()
                pcm[at++] = ((sample.toInt() shr 8) and 0xff).toByte()
            }
        }
        return WavEncoder.pcm16ToWav(pcm)
    }

    fun stop() {
        capture.stop()
        model.close()
        Logs.add("Wake word listening stopped")
    }

    fun release() {
        stop()
        model.release()
    }

    private companion object {
        /** Frames are 100 ms: three seconds, enough for the phrase and what came before it. */
        const val CLIP_FRAMES = 30
    }
}
