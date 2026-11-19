package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.audio.AudioGateway
import com.mindtravel.reduction.hardware.audio.HeadsetMicController
import com.mindtravel.reduction.hardware.audio.HeadsetMicController.User.TURN
import com.mindtravel.reduction.hardware.audio.HeadsetMicController.User.WAKE
import com.mindtravel.reduction.hardware.audio.WavEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Captures one user turn. The recorder decides on the device, from the audio alone, when
 * the turn starts and ends. Only a finished recording is sent for transcription.
 */
class TurnListener(
    private val audioGateway: AudioGateway,
    private val recorder: TurnRecorder,
    private val headsetMic: HeadsetMicController,
    /** Keeps a recording, with what was heard in it, for later use. */
    private val keepRecording: (kind: String, wav: ByteArray, heard: String) -> Unit,
    private val scope: CoroutineScope
) : TurnCapture {
    interface Events {
        /** The microphone is still delivering audio. Arrives twice a second. */
        fun onAlive()

        /** The turn is over; [onTurnEnd] follows once the recording has been closed. */
        fun onTurnEnding()
        fun onTurnEnd(turn: Turn)
        fun onNoSpeech()
        fun onError(message: String)
    }

    class Turn(val wav: ByteArray?, internal val apiKey: String)

    private var token = 0
    private var apiKey = ""
    private var events: Events? = null
    private var settleJob: Job? = null

    private var prepareJob: Job? = null

    /** A wake listener on the headset already established the communication route. */
    override fun prepare(useHeadsetMic: Boolean, onReady: () -> Unit) {
        settleJob?.cancel()
        settleJob = null
        val startedAt = System.currentTimeMillis()
        prepareJob?.cancel()
        val requested = useHeadsetMic && headsetMic.request(TURN)
        prepareJob = scope.launch {
            val headset = requested && runInterruptible(Dispatchers.IO) { headsetMic.awaitReady(TURN) }
            if (!headset) headsetMic.release(TURN)
            val warmRoute = headset && headsetMic.ready(WAKE)
            val live = runInterruptible(Dispatchers.IO) {
                recorder.warmUp(useCommunicationSource = headset)
                !headset || warmRoute || recorder.awaitAudio(ROUTE_SWITCH_LIMIT_MS)
            }
            Logs.add(
                "Turn prepared in ${System.currentTimeMillis() - startedAt}ms headsetMic=$headset reusedWakeRoute=$warmRoute" +
                    if (live) "" else " (no audio yet)"
            )
            onReady()
        }
    }

    override fun start(apiKey: String, events: Events) {
        // The recorder is already warm from prepare(); only this listener's own state is reset.
        reset(cancelRecorder = false)
        this.apiKey = apiKey
        this.events = events
        val current = token
        val started = recorder.start(
            onAlive = { onMain(current) { events.onAlive() } },
            onEnd = { reason ->
                onMain(current) {
                    Logs.add("Turn ended locally: $reason")
                    end(events)
                }
            },
            onTimeout = {
                onMain(current) {
                    reset(cancelRecorder = false)
                    events.onNoSpeech()
                }
            },
            onError = { message -> onMain(current) { fail(events, message) } }
        )
        if (!started && current == token) fail(events, "无法启动录音")
    }

    /** Ends the turn now and reports it through [Events.onTurnEnd]. */
    override fun finishNow() {
        val current = events ?: return
        end(current)
    }

    override fun cancel() {
        prepareJob?.cancel()
        reset(cancelRecorder = true)
    }

    /** Release this turn's share after the closing cue has reached the ears. Wake listening keeps its own share. */
    override fun settle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(ROUTE_HOLD_MS)
            headsetMic.release(TURN)
        }
    }

    /** The final transcript of [turn]; blank when nothing intelligible was said. */
    override suspend fun transcribe(turn: Turn): String {
        val wav = turn.wav ?: return ""
        return withContext(Dispatchers.IO) {
            var text = ""
            try {
                require(wav.size <= MAX_ASR_WAV_BYTES) { "单次语音识别最长 120 秒，请缩短这段话再说一次" }
                text = stripEndWord(audioGateway.transcribe(turn.apiKey, wav))
                text
            } finally {
                keepRecording("turn", wav, text)
            }
        }
    }

    private fun end(events: Events) {
        val key = apiKey
        events.onTurnEnding()
        reset(cancelRecorder = false)
        events.onTurnEnd(Turn(recorder.stop(), key))
    }

    private fun fail(events: Events, message: String) {
        reset(cancelRecorder = true)
        events.onError(message)
    }

    private fun reset(cancelRecorder: Boolean) {
        token++
        apiKey = ""
        events = null
        if (cancelRecorder) recorder.cancel()
    }

    private fun onMain(current: Int, block: () -> Unit) {
        scope.launch(Dispatchers.Main) { if (current == token) block() }
    }

    private fun stripEndWord(raw: String): String = (EndWords.strip(raw) ?: raw).trim()

    companion object {
        // TurnRecorder produces mono 16-bit PCM with WavEncoder's 44-byte WAV header.
        private const val MAX_ASR_WAV_BYTES = 44 + 120 * WavEncoder.SAMPLE_RATE * Short.SIZE_BYTES

        /** The longest to wait for a Bluetooth headset to start delivering microphone audio. */
        private const val ROUTE_SWITCH_LIMIT_MS = 1_500L

        /** How long sound already played takes to come out of a Bluetooth headset. */
        private const val ROUTE_HOLD_MS = 400L
    }
}
