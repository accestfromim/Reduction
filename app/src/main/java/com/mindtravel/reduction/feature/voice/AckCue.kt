package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.AudioQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The sounds of the recording state, played through the audio queue: the hum while
 * recording, then "好的". "好的" always ends: if playback never reports completion,
 * the timeout ends it. Call from the main thread.
 */
class AckCue(
    private val queue: AudioQueue,
    private val sounds: Sounds,
    private val scope: CoroutineScope,
    /** Where sound is going and how loud, in words, for the log. */
    private val describeOutput: () -> String
) : CueOutput {

    /** 16-bit mono PCM at the audio queue's rate. */
    class Sounds(val acknowledge: ByteArray, val hum: ByteArray)

    private var finishPending: (() -> Unit)? = null
    private var timeout: Job? = null
    private var humWatchdog: Job? = null
    private var humming = false

    /**
     * Keeps the recording hum going. The hum is only as alive as the calls to this:
     * when they stop coming for [HUM_SILENCE_AFTER_MS], it falls silent on its own.
     */
    override fun alive() {
        if (!humming) {
            humming = true
            Logs.add("Hum on, output: ${describeOutput()}")
            queue.setLoop(sounds.hum)
            // The log should be able to say whether sound actually came out, not just that it was asked for.
            scope.launch {
                val before = queue.playedFrames()
                delay(HUM_CHECK_MS)
                if (humming) Logs.add("Hum sounding: ${queue.playedFrames() - before} frames played in ${HUM_CHECK_MS}ms")
            }
        }
        humWatchdog?.cancel()
        humWatchdog = scope.launch {
            delay(HUM_SILENCE_AFTER_MS)
            Logs.add("Hum stopped: no audio from the microphone")
            stopHum()
        }
    }

    override fun acknowledge(onDone: () -> Unit) {
        // "好的" takes over the output: the hum and anything else sounding stop here.
        finishPending?.invoke()
        stopHum()
        queue.clear()

        var done = false
        val startedAt = System.currentTimeMillis()
        val finish = {
            if (!done) {
                done = true
                timeout?.cancel()
                finishPending = null
                Logs.add("Cue 好的 done after=${System.currentTimeMillis() - startedAt}ms")
                onDone()
            }
        }
        finishPending = finish
        timeout = scope.launch {
            delay(TIMEOUT_MS)
            if (!done) {
                Logs.add("Cue 好的 timed out")
                finish()
            }
        }
        Logs.add("Cue 好的")
        queue.play(sounds.acknowledge, owner = this, onDone = { finish() })
    }

    /** Silences everything. If "好的" was playing it is over, so its onDone runs. */
    override fun stop() {
        stopHum()
        queue.drop(this)
        finishPending?.invoke()
    }

    private fun stopHum() {
        humWatchdog?.cancel()
        humWatchdog = null
        if (!humming) return
        humming = false
        Logs.add("Hum off")
        queue.setLoop(null)
    }

    companion object {
        const val TIMEOUT_MS = 1_500L

        /** The hum outlives the last sign of life from the microphone by this long. */
        private const val HUM_SILENCE_AFTER_MS = 1_500L
        private const val HUM_CHECK_MS = 1_000L
    }
}
