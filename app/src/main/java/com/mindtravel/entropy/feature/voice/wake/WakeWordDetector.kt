package com.mindtravel.entropy.feature.voice.wake

import android.media.MediaRecorder
import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.hardware.audio.PcmCapture
import com.mindtravel.entropy.model.wake.KeywordSpotterModel

/** Feeds the microphone into the keyword spotter while the assistant is idle. */
class WakeWordDetector(
    private val model: KeywordSpotterModel,
    private val onWake: () -> Unit,
    private val onListening: () -> Unit,
    /** The microphone stopped, or started again, delivering real audio. */
    private val onDeaf: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val deafWatch = DeafWatch()

    private val capture = PcmCapture()

    @Volatile
    private var detected = false

    fun start() {
        if (capture.isRunning()) return
        detected = false
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
                    val keyword = model.accept(frame, PcmCapture.SAMPLE_RATE)
                    if (keyword != null) {
                        detected = true
                        Logs.add("Wake word detected: $keyword")
                        capture.requestStop()
                        onWake()
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

    fun stop() {
        capture.stop()
        model.close()
        Logs.add("Wake word listening stopped")
    }

    fun release() {
        stop()
        model.release()
    }
}
