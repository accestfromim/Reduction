package com.mindtravel.entropy.feature.voice

import com.mindtravel.entropy.feature.voice.VoiceMachine.Effect
import com.mindtravel.entropy.feature.voice.VoiceMachine.Event
import com.mindtravel.entropy.feature.voice.VoiceMachine.Pending
import com.mindtravel.entropy.feature.voice.VoiceMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VoiceMachineTest {

    /** Feeds events in order and returns the final state and every effect asked for. */
    private fun run(vararg events: Event, from: State = State()): Pair<State, List<Effect>> {
        var state = from
        val effects = ArrayList<Effect>()
        for (event in events) {
            val step = VoiceMachine.next(state, event)
            state = step.state
            effects += step.effects
        }
        return state to effects
    }

    private val listening = run(Event.WakeWord, Event.MicReady(1)).first

    // ── one conversation, step by step ──────────────────────────────────────

    @Test
    fun theWakeWordTakesTheMicrophoneAndPreparesIt() {
        val (state, effects) = run(Event.WakeWord)
        assertEquals(VoiceState.PREPARING, state.phase)
        assertEquals(1, state.turn)
        assertEquals(listOf(Effect.AbortReply, Effect.PauseWake, Effect.PrepareMic(1, false)), effects)
    }

    @Test
    fun recordingStartsWhenTheMicrophoneIsReady() {
        val step = VoiceMachine.next(run(Event.WakeWord).first, Event.MicReady(1))
        assertEquals(VoiceState.LISTENING, step.state.phase)
        assertEquals(listOf(Effect.StartRecording(1, false)), step.effects)
    }

    @Test
    fun aSpokenTurnIsAcknowledgedAndTurnedIntoWordsAtTheSameTime() {
        val step = VoiceMachine.next(listening, Event.TurnSpoken(1))
        assertEquals(VoiceState.TURN_ACK, step.state.phase)
        assertEquals(listOf(Effect.SayOk(1), Effect.Transcribe(1)), step.effects)
    }

    @Test
    fun theWordsAreSentWithoutWaitingForOkToFinish() {
        val (state, effects) = run(Event.TurnSpoken(1), Event.Transcribed(1, "明天天气"), from = listening)
        assertEquals(Pending.REPLY, state.pending)
        assertTrue(state.okSounding)
        assertEquals(Effect.Submit(1, "明天天气"), effects.last())
    }

    @Test
    fun theWakeWordIsArmedForBargeInOnlyAfterOkHasBeenHeard() {
        val sent = run(Event.TurnSpoken(1), Event.Transcribed(1, "你好"), from = listening).first

        val replyFirst = run(Event.ReplyStarted("r"), Event.OkDone(1), from = sent)
        assertEquals(VoiceState.REPLYING, replyFirst.first.phase)
        assertEquals(listOf(Effect.ReleaseRoute, Effect.ArmBargeIn), replyFirst.second)

        val okFirst = run(Event.OkDone(1), Event.ReplyStarted("r"), from = sent)
        assertEquals(VoiceState.REPLYING, okFirst.first.phase)
        assertEquals(listOf(Effect.ReleaseRoute, Effect.ArmBargeIn), okFirst.second)
    }

    @Test
    fun whenTheReplyHasBeenSpokenTheNextTurnBegins() {
        val replying = run(
            Event.TurnSpoken(1), Event.Transcribed(1, "你好"), Event.OkDone(1), Event.ReplyStarted("r"),
            from = listening
        ).first
        val step = VoiceMachine.next(replying, Event.ReplyEnded("r", ok = true))
        assertEquals(VoiceState.PREPARING, step.state.phase)
        assertEquals(2, step.state.turn)
        assertEquals(listOf(Effect.PauseWake, Effect.PrepareMic(2, true)), step.effects)
    }

    // ── the other ways a turn can go ────────────────────────────────────────

    @Test
    fun nobodySpeakingEndsTheConversationAfterOk() {
        val (state, effects) = run(Event.TurnSilent(1), from = listening)
        assertEquals(VoiceState.TURN_ACK, state.phase)
        assertEquals(listOf<Effect>(Effect.SayOk(1)), effects)

        val done = VoiceMachine.next(state, Event.OkDone(1))
        assertEquals(VoiceState.IDLE, done.state.phase)
        assertEquals(listOf(Effect.ReleaseRoute, Effect.ResumeWake), done.effects)
    }

    @Test
    fun nothingUnderstoodRecordsAgainOnceAndThenGivesUp() {
        val first = run(Event.TurnSpoken(1), Event.Transcribed(1, ""), Event.OkDone(1), from = listening)
        assertEquals(VoiceState.PREPARING, first.first.phase)
        assertEquals(2, first.first.turn)
        assertEquals(Effect.PrepareMic(2, true), first.second.last())

        val second = run(
            Event.MicReady(2), Event.TurnSpoken(2), Event.Transcribed(2, " "), Event.OkDone(2),
            from = first.first
        )
        assertEquals(VoiceState.IDLE, second.first.phase)
        assertEquals(Effect.ResumeWake, second.second.last())
    }

    @Test
    fun cancellingWhileListeningStillSaysOk() {
        val (state, effects) = run(Event.UserCancel, from = listening)
        assertEquals(VoiceState.TURN_ACK, state.phase)
        assertEquals(listOf(Effect.SayOk(1), Effect.DiscardRecording(1)), effects)
        assertEquals(VoiceState.IDLE, VoiceMachine.next(state, Event.OkDone(1)).state.phase)
    }

    @Test
    fun cancellingWhileStillPreparingMakesNoSound() {
        val (state, effects) = run(Event.WakeWord, Event.UserCancel)
        assertEquals(VoiceState.IDLE, state.phase)
        assertTrue(effects.none { it is Effect.SayOk })
        assertEquals(listOf(Effect.DiscardRecording(1), Effect.ReleaseRoute, Effect.ResumeWake), effects.takeLast(3))
    }

    @Test
    fun aMicrophoneThatCannotOpenReportsWhyAndGoesIdle() {
        val (state, effects) = run(Event.WakeWord, Event.MicFailed(1, "需要麦克风权限"))
        assertEquals(VoiceState.IDLE, state.phase)
        assertTrue(Effect.ShowError("需要麦克风权限") in effects)
        assertTrue(effects.none { it is Effect.SayOk || it is Effect.StartRecording })
    }

    @Test
    fun pressingFinishEndsTheRecordingButKeepsWhatWasSaid() {
        val step = VoiceMachine.next(listening, Event.UserFinish)
        assertEquals(VoiceState.LISTENING, step.state.phase)
        assertEquals(listOf(Effect.FinishRecording(1)), step.effects)
    }

    @Test
    fun typingWhileListeningDropsTheRecordingAndLetsTheReplyTakeOver() {
        val (state, effects) = run(Event.ReplyStarted("typed"), from = listening)
        assertEquals(VoiceState.REPLYING, state.phase)
        assertEquals(listOf(Effect.SayOk(1), Effect.DiscardRecording(1)), effects)
    }

    @Test
    fun theWakeWordDuringAReplyStopsItAndStartsANewTurn() {
        val replying = run(
            Event.TurnSpoken(1), Event.Transcribed(1, "你好"), Event.OkDone(1), Event.ReplyStarted("r"),
            from = listening
        ).first
        val step = VoiceMachine.next(replying, Event.WakeWord)
        assertEquals(VoiceState.PREPARING, step.state.phase)
        assertEquals(listOf(Effect.AbortReply, Effect.PauseWake, Effect.PrepareMic(2, false)), step.effects)
    }

    @Test
    fun aReplyThatFailsOrNeverStartsEndsTheConversation() {
        val sent = run(Event.TurnSpoken(1), Event.Transcribed(1, "你好"), Event.OkDone(1), from = listening).first
        assertEquals(VoiceState.IDLE, VoiceMachine.next(sent, Event.ReplyOverdue(1)).state.phase)

        val replying = VoiceMachine.next(sent, Event.ReplyStarted("r")).state
        val failed = VoiceMachine.next(replying, Event.ReplyEnded("r", ok = false))
        assertEquals(VoiceState.IDLE, failed.state.phase)
        assertEquals(listOf<Effect>(Effect.ResumeWake), failed.effects)
    }

    @Test
    fun aTypedConversationLeavesTheMachineIdle() {
        val (state, effects) = run(Event.ReplyStarted("typed"), Event.ReplyEnded("typed", true))
        assertEquals(State(), state)
        assertEquals(emptyList<Effect>(), effects)
    }

    // ── what must never happen ──────────────────────────────────────────────

    @Test
    fun eventsAboutAnEarlierRecordingChangeNothing() {
        val second = run(
            Event.TurnSpoken(1), Event.Transcribed(1, ""), Event.OkDone(1), Event.MicReady(2),
            from = listening
        ).first
        for (stale in listOf(
            Event.MicReady(1), Event.MicFailed(1, "x"), Event.TurnSpoken(1), Event.TurnSilent(1),
            Event.Transcribed(1, "迟到的话"), Event.OkDone(1), Event.ReplyOverdue(1)
        )) {
            val step = VoiceMachine.next(second, stale)
            assertEquals("$stale", second, step.state)
            assertEquals("$stale", emptyList<Effect>(), step.effects)
        }
    }

    @Test
    fun theWakeWordIsIgnoredWhileATurnIsUnderWay() {
        val turnAck = VoiceMachine.next(listening, Event.TurnSpoken(1)).state
        for (state in listOf(run(Event.WakeWord).first, listening, turnAck)) {
            val step = VoiceMachine.next(state, Event.WakeWord)
            assertEquals(state, step.state)
            assertEquals(emptyList<Effect>(), step.effects)
        }
    }

    /**
     * Whatever happens, in whatever order: every recording that starts is ended by exactly
     * one "好的" or, if it never got that far, one discard; "好的" is never said for a
     * recording that did not start; and the wake word is listened for again exactly when
     * the machine is idle.
     */
    @Test
    fun theGuaranteesHoldForAnyOrderOfEvents() {
        for (seed in 0L until 500L) {
            val random = Random(seed)
            var state = State()
            val started = HashSet<Int>()
            val acknowledged = HashMap<Int, Int>()
            var wakePaused = false
            var replies = 0
            val trace = ArrayList<String>()

            repeat(200) {
                val turn = if (random.nextInt(8) == 0) (state.turn - 1).coerceAtLeast(0) else state.turn
                val event: Event = when (random.nextInt(13)) {
                    0, 1 -> Event.WakeWord
                    2, 3 -> Event.MicReady(turn)
                    4 -> Event.MicFailed(turn, "boom")
                    5 -> Event.TurnSpoken(turn)
                    6 -> Event.TurnSilent(turn)
                    7 -> Event.Transcribed(turn, if (random.nextBoolean()) "话" else "")
                    8 -> Event.OkDone(turn)
                    9 -> Event.ReplyStarted("r${++replies}")
                    10 -> Event.ReplyEnded(state.replyId ?: "none", random.nextBoolean())
                    11 -> if (random.nextBoolean()) Event.UserFinish else Event.UserCancel
                    else -> Event.ReplyOverdue(turn)
                }
                val step = VoiceMachine.next(state, event)
                trace += "${state.phase} + $event -> ${step.state.phase} ${step.effects}"
                for (effect in step.effects) {
                    when (effect) {
                        is Effect.StartRecording -> {
                            assertTrue("seed $seed: recording ${effect.turn} started twice\n$trace", started.add(effect.turn))
                            assertTrue("seed $seed: recording without the microphone\n$trace", wakePaused)
                        }
                        is Effect.SayOk -> {
                            assertTrue("seed $seed: 好的 for a recording that never started\n$trace", effect.turn in started)
                            acknowledged.merge(effect.turn, 1, Int::plus)
                        }
                        Effect.PauseWake -> wakePaused = true
                        Effect.ResumeWake -> wakePaused = false
                        Effect.ArmBargeIn -> wakePaused = false
                        else -> Unit
                    }
                }
                state = step.state
                if (state.phase == VoiceState.IDLE) {
                    assertTrue("seed $seed: idle but not listening for the wake word\n${trace.takeLast(4)}", !wakePaused)
                }
                if (state.phase == VoiceState.LISTENING) {
                    assertTrue("seed $seed: listening to a recording that was not started", state.turn in started)
                }
                assertTrue("seed $seed: 好的 said twice for one recording\n${trace.takeLast(6)}", acknowledged.values.all { it == 1 })
            }

            // Bring whatever is under way to its end.
            for (event in listOf(
                Event.MicReady(state.turn), Event.TurnSilent(state.turn), Event.OkDone(state.turn), Event.UserCancel, Event.OkDone(state.turn)
            )) {
                val step = VoiceMachine.next(state, event)
                step.effects.filterIsInstance<Effect.StartRecording>().forEach { started += it.turn }
                step.effects.filterIsInstance<Effect.SayOk>().forEach { acknowledged.merge(it.turn, 1, Int::plus) }
                state = step.state
            }
            assertEquals("seed $seed", VoiceState.IDLE, state.phase)
            assertEquals("seed $seed: every recording that started got its 好的", started, acknowledged.keys)
        }
    }
}
