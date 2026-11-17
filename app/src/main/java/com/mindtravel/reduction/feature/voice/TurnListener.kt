package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.audio.AudioGateway
import com.mindtravel.reduction.hardware.audio.HeadsetMicController
import com.mindtravel.reduction.model.voice.RollingAsr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Captures one user turn. The recorder decides on the device, from the audio alone, when
 * the turn starts and ends. The growing audio is also transcribed every second, but only
 * for the live caption: a transcript never ends a turn. Events arrive on the main thread.
 */
class TurnListener(
    private val audioGateway: AudioGateway,
    private val recorder: TurnRecorder,
    private val headsetMic: HeadsetMicController,
    /** Keeps a recording, with what was heard in it, for later use. */
    private val keepRecording: (kind: String, wav: ByteArray, heard: String) -> Unit,
    private val rollingAsr: RollingAsr,
    private val scope: CoroutineScope
) : TurnCapture {
    interface Events {
        fun onSpeechStart()

        /** The microphone is still delivering audio. Arrives twice a second. */
        fun onAlive()
        fun onPartial(text: String)

        /** The turn is over; [onTurnEnd] follows once the recording has been closed. */
        fun onTurnEnding(heard: Boolean)
        fun onTurnEnd(turn: Turn)
        fun onNoSpeech()
        fun onError(message: String)
    }

    /** A finished turn. [text] is the last rolling transcript with the end word removed. */
    class Turn(
        val text: String,
        val wav: ByteArray?,
        internal val apiKey: String
    )

    /** The latest live caption: what is kept if the final transcription fails. */
    private var caption = ""
    private var token = 0
    private var apiKey = ""
    private var events: Events? = null
    private var settleJob: Job? = null

    @Volatile
    private var headsetActive = false
    private var speechStarted = false

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
            onSpeechStart = { onMain(current) { beginRolling(current, events) } },
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
        val text = finalText(turn)
        turn.wav?.let { wav -> withContext(Dispatchers.IO) { keepRecording("turn", wav, text) } }
        return text
    }

    private suspend fun finalText(turn: Turn): String {
        val wav = turn.wav ?: return turn.text
        return try {
            val raw = withContext(Dispatchers.IO) { audioGateway.transcribe(turn.apiKey, wav) }
            stripEndWord(raw).ifBlank { turn.text }
        } catch (t: Throwable) {
            Logs.add("Final ASR failed: ${t.message}")
            turn.text
        }
    }

    private fun beginRolling(current: Int, events: Events) {
        speechStarted = true
        events.onSpeechStart()
        rollingAsr.start(
            apiKey = apiKey,
            snapshot = { recorder.snapshotWav() },
            onResult = { text, _ ->
                if (current == token) onRollingResult(events, text)
            },
            onError = { message -> Logs.add("Rolling ASR error: $message") }
        )
    }

    private fun onRollingResult(events: Events, text: String?) {
        if (text.isNullOrBlank()) return
        caption = text
        events.onPartial(stripEndWord(text))
    }

    private fun end(events: Events) {
        val key = apiKey
        val heard = caption
        events.onTurnEnding(heard = speechStarted)
        reset(cancelRecorder = false)
        val wav = recorder.stop()
        events.onTurnEnd(Turn(stripEndWord(heard), wav, key))
    }

    private fun fail(events: Events, message: String) {
        reset(cancelRecorder = true)
        events.onError(message)
    }

    private fun reset(cancelRecorder: Boolean) {
        token++
        rollingAsr.stop()
        caption = ""
        speechStarted = false
        apiKey = ""
        events = null
        if (cancelRecorder) recorder.cancel()
    }

    private fun onMain(current: Int, block: () -> Unit) {
        scope.launch(Dispatchers.Main) { if (current == token) block() }
    }

    private fun stripEndWord(raw: String): String = (EndWords.strip(raw) ?: raw).trim()

    companion object {
        /** The longest to wait for a Bluetooth headset to start delivering microphone audio. */
        private const val ROUTE_SWITCH_LIMIT_MS = 1_500L

        /** How long sound already played takes to come out of a Bluetooth headset. */
        private const val ROUTE_HOLD_MS = 400L
    }
}
