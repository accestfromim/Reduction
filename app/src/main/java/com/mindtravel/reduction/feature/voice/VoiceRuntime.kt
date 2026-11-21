package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.VoiceTrace
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.host.PermissionHost
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.feature.chat.ui.ReplyObserver
import com.mindtravel.reduction.feature.voice.VoiceMachine.Effect
import com.mindtravel.reduction.feature.voice.VoiceMachine.Event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Runs the hands-free conversation: feeds events to [VoiceMachine], carries out the
 * effects it asks for, and reports what came of them as further events.
 *
 * All decisions are in the machine. This class only does what it is told, so it holds
 * no rules of its own — and every step is one line in the log:
 *
 *     Voice LISTENING + TurnSpoken(1) -> TURN_ACK [SayOk(1), Transcribe(1)]
 *
 * Events may come from any thread; they are handled one at a time on the main thread.
 */
class VoiceRuntime(
    private val settingsRepository: SettingsGateway,
    private val permissions: PermissionHost,
    private val hasHeadsetOutput: () -> Boolean,
    private val wake: WakeControl,
    private val cue: CueOutput,
    private val capture: TurnCapture,
    private val isPushToTalkActive: () -> Boolean,
    /** Sends what was said to the assistant. False when it could not be sent. */
    private val submit: (String, String) -> Boolean,
    private val abortReply: () -> Unit,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) : ReplyObserver {

    @Volatile
    var state = VoiceMachine.State()
        private set

    private val inbox = ArrayDeque<Event>()
    private var handling = false

    /** The audio of each recording, available once the microphone has closed. */
    private val recordings = HashMap<Int, CompletableDeferred<TurnListener.Turn>>()

    // ── what can happen ─────────────────────────────────────────────────────

    fun dispatch(event: Event) {
        scope.launch(Dispatchers.Main.immediate) { accept(event) }
    }

    fun onWakeWord() = dispatch(Event.WakeWord)

    fun onHeadsetGesture() = dispatch(Event.HeadsetGesture)

    fun finishTurn() = dispatch(Event.UserFinish)

    fun cancel() = dispatch(Event.UserCancel)

    override fun onReplyStarted(id: String) = dispatch(Event.ReplyStarted(id))

    override fun onReplyEnded(id: String, ok: Boolean) = dispatch(Event.ReplyEnded(id, ok))

    /** Current state for a UI that has just loaded. */
    fun snapshot(): String = JSONObject().put("state", state.phase.name).toString()

    fun dispose() {
        capture.cancel()
        capture.settle()
        cue.stop()
    }

    // ── one event at a time ─────────────────────────────────────────────────

    /** An effect may report back at once; that event waits its turn instead of cutting in. */
    private fun accept(event: Event) {
        inbox.addLast(event)
        if (handling) return
        handling = true
        try {
            while (inbox.isNotEmpty()) handle(inbox.removeFirst())
        } finally {
            handling = false
        }
    }

    private fun handle(event: Event) {
        if (event == Event.WakeWord && !wakeWordWanted()) return
        if (event == Event.HeadsetGesture && isPushToTalkActive()) return
        val before = state
        val step = VoiceMachine.next(before, event)
        state = step.state
        if (step.state != before || step.effects.isNotEmpty()) {
            Logs.add("Voice ${before.phase} + ${describe(event)} -> ${step.state.phase} ${step.effects.map(::describe)}")
        }
        if (step.state.phase != before.phase) emitter.emit("onVoiceState", step.state.phase.name)
        step.effects.forEach(::perform)
    }

    private fun wakeWordWanted(): Boolean =
        settingsRepository.load().wakeWordEnabled && !isPushToTalkActive()

    // ── carrying out effects ────────────────────────────────────────────────

    private fun perform(effect: Effect) {
        when (effect) {
            Effect.PauseWake -> wake.pause()
            Effect.ResumeWake -> if (settingsRepository.load().wakeWordEnabled) wake.resume()
            Effect.ArmBargeIn -> armBargeIn()
            Effect.AbortReply -> abortReply()
            is Effect.PrepareMic -> prepare(effect.turn, effect.headsetMic)
            is Effect.StartRecording -> record(effect.turn)
            is Effect.FinishRecording -> capture.finishNow()
            is Effect.DiscardRecording -> capture.cancel()
            is Effect.SayOk -> cue.acknowledge { dispatch(Event.OkDone(effect.turn)) }
            Effect.ReleaseRoute -> capture.settle()
            is Effect.Transcribe -> transcribe(effect.turn)
            is Effect.Submit -> send(effect.turn, effect.text)
            is Effect.Say -> cue.say(effect.cue)
            is Effect.ShowError -> emitter.emit("onVoiceError", effect.message)
        }
    }

    /** With headphones the wake word can interrupt a reply; on the speaker it would hear itself. */
    private fun armBargeIn() {
        val settings = settingsRepository.load()
        if (settings.bargeInEnabled && settings.wakeWordEnabled && hasHeadsetOutput()) wake.resume()
    }

    private fun prepare(turn: Int, headsetMic: Boolean) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            dispatch(Event.MicFailed(turn, "请先在设置里填写 API Key"))
            return
        }
        if (!permissions.hasMicPermission()) {
            permissions.requestMicPermission { granted ->
                if (granted) prepare(turn, headsetMic) else dispatch(Event.MicFailed(turn, "需要麦克风权限"))
            }
            return
        }
        capture.prepare(headsetMic || settings.headsetMicEnabled) { dispatch(Event.MicReady(turn)) }
    }

    private val tracePrefix = java.util.UUID.randomUUID().toString()
    private fun trace(turn: Int) = "$tracePrefix-$turn"

    private fun record(turn: Int) {
        val id = trace(turn)
        VoiceTrace.start(id)
        val recording = CompletableDeferred<TurnListener.Turn>()
        recordings.clear()
        recordings[turn] = recording
        capture.start(settingsRepository.load().apiKey, object : TurnListener.Events {
            private fun current() = state.turn == turn && state.phase == VoiceState.LISTENING

            override fun onSpeechStart() {
                if (current()) emitter.emit("onVoicePartial", "")
            }

            override fun onPartial(text: String) {
                if (current()) emitter.emit("onVoicePartial", text)
            }

            override fun onAlive() {
                if (current()) cue.alive()
            }

            // Reported the moment the turn is over, before the microphone is closed, so
            // that "好的" does not wait for it. The audio follows in onTurnEnd.
            override fun onTurnEnding(heard: Boolean) {
                VoiceTrace.mark(id, "release")
                dispatch(Event.TurnSpoken(turn))
            }

            override fun onTurnEnd(turn: TurnListener.Turn) {
                recording.complete(turn)
            }

            override fun onNoSpeech() = dispatch(Event.TurnSilent(turn))

            override fun onError(message: String) = dispatch(Event.MicFailed(turn, message))
        })
        // The hum starts with the recording. It is on trust until the first audio arrives:
        // if the microphone delivers nothing, the hum stops by itself.
        cue.alive()
    }

    private fun transcribe(turn: Int) {
        val recording = recordings[turn]
        scope.launch {
            val audio = recording?.await()
            val id = trace(turn)
            VoiceTrace.mark(id, "asrstart")
            val text = try {
                if (audio == null) "" else capture.transcribe(audio)
            } catch (e: Exception) {
                VoiceTrace.mark(id, "asrfailed")
                throw e
            }
            VoiceTrace.mark(id, if (text.isBlank()) "asrfailed" else "asrend")
            dispatch(Event.Transcribed(turn, text))
        }
    }

    private fun send(turn: Int, text: String) {
        if (!submit(trace(turn), text)) {
            dispatch(Event.ReplyOverdue(turn))
            return
        }
        scope.launch {
            delay(REPLY_START_TIMEOUT_MS)
            dispatch(Event.ReplyOverdue(turn))
        }
    }

    // ── for the log ─────────────────────────────────────────────────────────

    private fun describe(event: Event): String = when (event) {
        is Event.Transcribed -> "Transcribed(${event.turn}, ${event.text.length} chars)"
        else -> event.toString()
    }

    private fun describe(effect: Effect): String = when (effect) {
        is Effect.Submit -> "Submit(${effect.turn}, ${effect.text.length} chars)"
        else -> effect.toString()
    }

    private companion object {
        const val REPLY_START_TIMEOUT_MS = 5_000L
    }
}
