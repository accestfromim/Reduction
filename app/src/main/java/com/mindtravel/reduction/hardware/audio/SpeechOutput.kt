package com.mindtravel.reduction.hardware.audio

/**
 * Where synthesised speech goes to be heard: the phone's audio queue, or a browser on
 * a computer. Speech arrives a piece at a time and is played in the order it arrives.
 */
interface SpeechOutput {
    /** Begins a stretch of speech. [onStart] runs when the first piece sounds, [onDone] when the last has been heard. */
    fun start(onStart: () -> Unit, onDone: () -> Unit)

    /** Hands over PCM, waiting while enough is already queued. Returns false once playback has stopped. */
    suspend fun send(pcm: ByteArray): Boolean

    /** No more PCM is coming; onDone follows when what was sent has been played. */
    fun finish()

    /** Silences the speech now. No callback follows. */
    fun stop()
}
