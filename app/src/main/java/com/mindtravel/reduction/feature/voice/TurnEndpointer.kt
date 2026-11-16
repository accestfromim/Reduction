package com.mindtravel.reduction.feature.voice

/**
 * Decides, frame by frame and on the device, where a spoken turn starts and ends.
 *
 * A turn ends the moment the end word is heard, and only then: the speaker may pause for
 * as long as they like, and the hum that plays while recording reminds them to say it.
 * If nobody speaks at all, it gives up.
 */
class TurnEndpointer(
    private val noSpeechTimeoutMs: Long = 12_000
) {
    enum class Decision { CONTINUE, SPEECH_STARTED, NO_SPEECH, END_BY_WORD }

    private var openedAt = 0L
    private var started = false

    fun reset(nowMs: Long) {
        openedAt = nowMs
        started = false
    }

    fun onFrame(speech: Boolean, endWord: Boolean, nowMs: Long): Decision {
        if (endWord) return Decision.END_BY_WORD
        if (speech) {
            if (!started) {
                started = true
                return Decision.SPEECH_STARTED
            }
            return Decision.CONTINUE
        }
        if (!started && nowMs - openedAt >= noSpeechTimeoutMs) return Decision.NO_SPEECH
        return Decision.CONTINUE
    }
}
