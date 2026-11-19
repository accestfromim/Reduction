package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.audio.AudioGateway
import com.mindtravel.reduction.hardware.audio.HeadsetMicController
import com.mindtravel.reduction.hardware.audio.WavEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    @Volatile
    private var headsetActive = false

    /**
     * Opens the microphone ahead of the opening cue, so recording can begin the instant
     * the cue ends. With a headset microphone the Bluetooth headset first has to switch
     * to its call mode, which interrupts sound for a moment: that is waited out here,
     * until the microphone is delivering audio, before anything is played.
     */
    override fun prepare(useHeadsetMic: Boolean, onReady: () -> Unit) {
        settleJob?.cancel()
        settleJob = null
        val startedAt = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val switched = useHeadsetMic && !headsetActive && headsetMic.start()
            if (switched) headsetActive = true
            recorder.warmUp(useCommunicationSource = headsetActive)
            val live = if (headsetActive) recorder.awaitAudio(ROUTE_SWITCH_LIMIT_MS) else true
            Logs.add(
                "Turn prepared in ${System.currentTimeMillis() - startedAt}ms headsetMic=$headsetActive" +
                    if (live) "" else " (no audio yet)"
            )
            withContext(Dispatchers.Main) { onReady() }
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
        reset(cancelRecorder = true)
    }

    /**
     * Gives a Bluetooth headset back to its music mode. Held back a moment: what has just
     * been played is still on its way to the ears, and the switch would cut it off.
     */
    override fun settle() {
        if (!headsetActive) return
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(ROUTE_HOLD_MS)
            headsetMic.stop()
            headsetActive = false
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
