package com.mindtravel.entropy.feature.voice

/**
 * The hands-free conversation as a pure state machine.
 *
 *     next(state, event) -> (state, effects)
 *
 * [next] touches nothing: no microphone, no sound, no clock, no threads. It only says
 * what the new state is and what should now be done. Something else carries the effects
 * out and reports what happened as further events. Every rule can therefore be read
 * here, tested by calling one function, and followed in the log one line at a time.
 *
 * The phases:
 *
 *     IDLE ──wake word──▶ PREPARING ──microphone ready──▶ LISTENING (hum)
 *     LISTENING ──turn ends──▶ TURN_ACK ("好的") ──words──▶ REPLYING ──reply spoken──▶ IDLE
 *
 * Only the wake phrase starts a recording: once in IDLE, and again while a reply is
 * playing, which it interrupts. Nothing else opens the microphone.
 *
 * Each recording has a number, [State.turn]. Events about a recording carry its number,
 * and one that arrives for an earlier recording is ignored.
 */
object VoiceMachine {

    data class State(
        val phase: VoiceState = VoiceState.IDLE,
        /** Number of the current or most recent recording. */
        val turn: Int = 0,
        /** The reply being generated and spoken. */
        val replyId: String? = null,
        /** "好的" has not finished sounding. */
        val okSounding: Boolean = false,
        /** In TURN_ACK: what the recording came to, once that is known. */
        val pending: Pending = Pending.NOTHING
    )

    /** What TURN_ACK is waiting to do once "好的" has been heard. */
    enum class Pending {
        NOTHING,

        /** Waiting for the words that were said. */
        WORDS,

        /** The words were sent; waiting for the reply to begin. */
        REPLY,

        /** There is nothing to answer: back to waiting for the wake phrase. */
        GO_IDLE
    }

    sealed interface Event {
        /** The wake phrase was heard. */
        data object WakeWord : Event

        /** The microphone is open and delivering audio. */
        data class MicReady(val turn: Int) : Event

        /** The microphone could not be opened, or failed while recording. */
        data class MicFailed(val turn: Int, val message: String) : Event

        /** Recording ended by itself — end word, silence, time limit — with something said. */
        data class TurnSpoken(val turn: Int) : Event

        /** Recording ended because nobody spoke. */
        data class TurnSilent(val turn: Int) : Event

        /** The words of a finished recording; blank when nothing was understood. */
        data class Transcribed(val turn: Int, val text: String) : Event

        /** "好的" has been heard to the end. */
        data class OkDone(val turn: Int) : Event

        /** No reply began after the words were sent. */
        data class ReplyOverdue(val turn: Int) : Event

        data class ReplyStarted(val id: String) : Event

        /** A reply is over: generated and spoken ([ok]), or failed. */
        data class ReplyEnded(val id: String, val ok: Boolean) : Event

        /** The user pressed "finish": this turn is complete. */
        data object UserFinish : Event

        /** The user left the conversation. */
        data object UserCancel : Event
    }

    sealed interface Effect {
        /** Stop listening for the wake phrase: the microphone is needed. */
        data object PauseWake : Effect

        /** Listen for the wake phrase again. */
        data object ResumeWake : Effect

        /** Listen for the wake phrase while a reply plays, where that is possible. */
        data object ArmBargeIn : Effect

        /** Abandon the reply in progress, if any. */
        data object AbortReply : Effect

        /** Get the microphone ready. Answers with MicReady or MicFailed. */
        data class PrepareMic(val turn: Int) : Effect

        /** Begin recording and start the hum. Answers with TurnSpoken, TurnSilent or MicFailed. */
        data class StartRecording(val turn: Int) : Effect

        /** End the recording now, keeping what was said. Answers with TurnSpoken. */
        data class FinishRecording(val turn: Int) : Effect

        /** Stop the microphone and throw the recording away. */
        data class DiscardRecording(val turn: Int) : Effect

        /** Stop the hum and say "好的". Answers with OkDone. */
        data class SayOk(val turn: Int) : Effect

        /** Give the audio route back, now that nothing is sounding. */
        data object ReleaseRoute : Effect

        /** Turn the recording into words. Answers with Transcribed. */
        data class Transcribe(val turn: Int) : Effect

        /** Send what was said to the assistant. Answers with ReplyStarted, or ReplyOverdue. */
        data class Submit(val turn: Int, val text: String) : Effect

        data class ShowError(val message: String) : Effect
    }

    class Step(val state: State, val effects: List<Effect>)

    fun next(state: State, event: Event): Step = when (state.phase) {
        VoiceState.IDLE -> idle(state, event)
        VoiceState.PREPARING -> preparing(state, event)
        VoiceState.LISTENING -> listening(state, event)
        VoiceState.TURN_ACK -> turnAck(state, event)
        VoiceState.REPLYING -> replying(state, event)
    }

    private fun idle(s: State, e: Event): Step = when (e) {
        Event.WakeWord -> record(s, Effect.AbortReply)
        else -> stay(s)
    }

    private fun preparing(s: State, e: Event): Step = when {
        e is Event.MicReady && e.turn == s.turn ->
            Step(s.copy(phase = VoiceState.LISTENING), listOf(Effect.StartRecording(s.turn)))

        e is Event.MicFailed && e.turn == s.turn ->
            toIdle(s, Effect.ShowError(e.message), Effect.DiscardRecording(s.turn), Effect.ReleaseRoute)

        // Nothing has been heard yet, so leaving makes no sound.
        e == Event.UserCancel || e == Event.UserFinish ->
            toIdle(s, Effect.DiscardRecording(s.turn), Effect.ReleaseRoute)

        // The user typed instead of speaking.
        e is Event.ReplyStarted ->
            Step(
                s.copy(phase = VoiceState.REPLYING, replyId = e.id, okSounding = false),
                listOf(Effect.DiscardRecording(s.turn), Effect.ReleaseRoute, Effect.ArmBargeIn)
            )

        else -> stay(s)
    }

    private fun listening(s: State, e: Event): Step = when {
        e is Event.TurnSpoken && e.turn == s.turn ->
            acknowledge(s, Pending.WORDS, Effect.SayOk(s.turn), Effect.Transcribe(s.turn))

        e is Event.TurnSilent && e.turn == s.turn ->
            acknowledge(s, Pending.GO_IDLE, Effect.SayOk(s.turn))

        e is Event.MicFailed && e.turn == s.turn ->
            acknowledge(s, Pending.GO_IDLE, Effect.ShowError(e.message), Effect.SayOk(s.turn))

        e == Event.UserFinish -> Step(s, listOf(Effect.FinishRecording(s.turn)))

        e == Event.UserCancel ->
            // "好的" first: closing the microphone can take a moment and must not delay it.
            acknowledge(s, Pending.GO_IDLE, Effect.SayOk(s.turn), Effect.DiscardRecording(s.turn))

        // The user typed instead of speaking: the recording is dropped and the reply takes over.
        e is Event.ReplyStarted ->
            Step(
                s.copy(phase = VoiceState.REPLYING, replyId = e.id, okSounding = true, pending = Pending.NOTHING),
                listOf(Effect.SayOk(s.turn), Effect.DiscardRecording(s.turn))
            )

        else -> stay(s)
    }

    private fun turnAck(s: State, e: Event): Step = when {
        e is Event.OkDone && e.turn == s.turn -> settle(s.copy(okSounding = false), Effect.ReleaseRoute)

        e is Event.Transcribed && e.turn == s.turn && s.pending == Pending.WORDS ->
            if (e.text.isBlank()) {
                // Nothing was understood. The user says the wake phrase to try again.
                settle(s.copy(pending = Pending.GO_IDLE))
            } else {
                // Sent at once: the reply is being prepared while "好的" is still sounding.
                Step(s.copy(pending = Pending.REPLY), listOf(Effect.Submit(s.turn, e.text)))
            }

        e is Event.ReplyStarted ->
            Step(
                s.copy(phase = VoiceState.REPLYING, replyId = e.id, pending = Pending.NOTHING),
                if (s.okSounding) emptyList() else listOf(Effect.ArmBargeIn)
            )

        e is Event.ReplyOverdue && e.turn == s.turn && s.pending == Pending.REPLY ->
            settle(s.copy(pending = Pending.GO_IDLE))

        e == Event.UserCancel -> settle(s.copy(pending = Pending.GO_IDLE))

        else -> stay(s)
    }

    private fun replying(s: State, e: Event): Step = when {
        e is Event.OkDone && e.turn == s.turn && s.okSounding ->
            Step(s.copy(okSounding = false), listOf(Effect.ReleaseRoute, Effect.ArmBargeIn))

        e is Event.ReplyStarted -> Step(s.copy(replyId = e.id), emptyList())

        // The reply is over, spoken or failed: the microphone stays closed until the wake phrase.
        e is Event.ReplyEnded && e.id == s.replyId -> toIdle(s)

        // Barge-in: the reply stops and a new turn begins.
        e == Event.WakeWord -> record(s.copy(replyId = null), Effect.AbortReply)

        // The reply carries on as an ordinary one; only the conversation ends.
        e == Event.UserCancel -> toIdle(s)

        else -> stay(s)
    }

    // ── shared moves ────────────────────────────────────────────────────────

    private fun stay(s: State) = Step(s, emptyList())

    /** Begins a new recording: the next turn number, the microphone taken from the wake word. */
    private fun record(s: State, vararg first: Effect): Step {
        val turn = s.turn + 1
        return Step(
            s.copy(phase = VoiceState.PREPARING, turn = turn, okSounding = false, pending = Pending.NOTHING),
            first.toList() + Effect.PauseWake + Effect.PrepareMic(turn)
        )
    }

    /** Leaves LISTENING: from here "好的" is sounding. */
    private fun acknowledge(s: State, pending: Pending, vararg effects: Effect) =
        Step(s.copy(phase = VoiceState.TURN_ACK, okSounding = true, pending = pending), effects.toList())

    /** In TURN_ACK, acts on what is pending once "好的" has been heard. */
    private fun settle(s: State, vararg first: Effect): Step {
        if (s.okSounding) return Step(s, first.toList())
        return when (s.pending) {
            Pending.GO_IDLE -> toIdle(s, *first)
            else -> Step(s, first.toList())
        }
    }

    private fun toIdle(s: State, vararg first: Effect) = Step(
        State(turn = s.turn),
        first.toList() + Effect.ResumeWake
    )
}
