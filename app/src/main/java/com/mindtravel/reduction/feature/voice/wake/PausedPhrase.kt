package com.mindtravel.reduction.feature.voice.wake

import kotlin.math.sqrt

/**
 * Tells whether the name was just said as the end of the wake phrase spoken with a
 * pause in it: a short word, a pause, then the name on its own.
 *
 * The keyword model hears the phrase as one piece only when its words follow each other
 * closely. Said with a pause, it still hears the name — but the name alone must not wake
 * the assistant, since it also turns up inside ordinary sentences. What sets the paused
 * phrase apart is its shape in time, and that is what is checked here, on the loudness
 * of the last few seconds:
 *
 *     quiet · short word · pause · name · (now)
 *
 * Loudness is judged against the quietest part of those seconds, so a steady background
 * does not count as speech.
 */
class PausedPhrase {
    /** Loudness of the most recent frames, oldest first. One frame is 100 ms. */
    private val recent = ArrayDeque<Double>()

    fun reset() = recent.clear()

    fun onFrame(frame: ShortArray) {
        var sum = 0.0
        for (sample in frame) sum += sample.toDouble() * sample
        recent.addLast(if (frame.isEmpty()) 0.0 else sqrt(sum / frame.size))
        if (recent.size > WINDOW_FRAMES) recent.removeFirst()
    }

    /** Call at the moment the name is detected. */
    fun matches(): Boolean = matches(speechFlags(recent.toList()))

    companion object {
        /** Five seconds: the phrase with the longest pause allowed, and some quiet before it. */
        const val WINDOW_FRAMES = 50

        /** Below this a frame is silence whatever the background (16-bit samples). */
        private const val QUIETEST_SPEECH = 150.0

        /** Speech is this many times louder than the quietest fifth of the window. */
        private const val ABOVE_BACKGROUND = 3.0

        private const val AFTER_NAME_MAX = 10
        private val NAME_FRAMES = 3..14
        private val PAUSE_FRAMES = 3..30
        private val WORD_FRAMES = 1..7
        private const val QUIET_BEFORE = 2

        fun speechFlags(loudness: List<Double>): List<Boolean> {
            if (loudness.isEmpty()) return emptyList()
            val background = loudness.sorted()[loudness.size / 5]
            val limit = maxOf(QUIETEST_SPEECH, background * ABOVE_BACKGROUND)
            return loudness.map { it > limit }
        }

        /** [speech] holds one flag per frame, oldest first, ending now. */
        fun matches(speech: List<Boolean>): Boolean {
            var i = speech.size - 1

            // The quiet since the name ended: the model reports a keyword a moment after it.
            var after = 0
            while (i >= 0 && !speech[i] && after < AFTER_NAME_MAX) { i--; after++ }
            if (i < 0 || !speech[i]) return false

            // The name, allowing a dip of one frame inside it.
            val nameEnd = i
            while (i >= 0 && (speech[i] || (i > 0 && speech[i - 1]))) i--
            if (nameEnd - i !in NAME_FRAMES) return false

            var pause = 0
            while (i >= 0 && !speech[i]) { i--; pause++ }
            if (pause !in PAUSE_FRAMES || i < 0) return false

            var word = 0
            while (i >= 0 && speech[i]) { i--; word++ }
            if (word !in WORD_FRAMES) return false

            // The word started from quiet: it was not the tail of a sentence.
            var before = 0
            while (i >= 0 && !speech[i] && before < QUIET_BEFORE) { i--; before++ }
            return before >= QUIET_BEFORE || i < 0
        }
    }
}
