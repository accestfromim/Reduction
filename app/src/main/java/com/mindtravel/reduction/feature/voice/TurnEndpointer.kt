package com.mindtravel.reduction.feature.voice

/**
 * Decides, frame by frame and on the device, where a spoken turn starts and ends.
 *
 * A turn ends the moment the end word is heard, or when the speaker has been silent
 * for a while. If nobody speaks at all, it gives up.
 */
class TurnEndpointer(
    private val noSpeechTimeoutMs: Long = 12_000,
    private val trailingSilenceMs: Long = 3_000
) {
    enum class Decision { CONTINUE, SPEECH_STARTED, NO_SPEECH, END_BY_WORD, END_BY_SILENCE }

    private var openedAt = 0L
    private var started = false

    /** When speech was last heard; 0 before any. */
    @Volatile
    var lastVoiceAt = 0L
        private set

    fun reset(nowMs: Long) {
        openedAt = nowMs
        started = false
        lastVoiceAt = 0L
    }

    fun onFrame(speech: Boolean, endWord: Boolean, nowMs: Long): Decision {
        if (endWord) {
            lastVoiceAt = nowMs
            return Decision.END_BY_WORD
        }
        if (speech) {
            lastVoiceAt = nowMs
            if (!started) {
                started = true
                return Decision.SPEECH_STARTED
            }
            return Decision.CONTINUE
        }
        if (!started) {
            return if (nowMs - openedAt >= noSpeechTimeoutMs) Decision.NO_SPEECH else Decision.CONTINUE
        }

        if (nowMs - lastVoiceAt >= trailingSilenceMs) return Decision.END_BY_SILENCE
        return Decision.CONTINUE
    }
}
