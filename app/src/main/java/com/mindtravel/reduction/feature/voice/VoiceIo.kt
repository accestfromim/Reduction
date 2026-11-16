package com.mindtravel.reduction.feature.voice

/** Where the recording state's sounds go. */
interface CueOutput {
    /** The microphone is delivering audio: keep the recording hum going. */
    fun alive()

    /**
     * Ends the hum and says "好的". [onDone] runs exactly once, when it has been heard
     * or when [stop] silenced it.
     */
    fun acknowledge(onDone: () -> Unit = {})

    fun stop()
}

/** Captures one spoken turn from the microphone. */
interface TurnCapture {
    /**
     * Gets everything slow out of the way: the audio route for a headset microphone,
     * the microphone itself, the detectors. [onReady] runs on the main thread.
     */
    fun prepare(useHeadsetMic: Boolean, onReady: () -> Unit)

    fun start(apiKey: String, events: TurnListener.Events)
    fun finishNow()
    fun cancel()

    /** Recording is over and its closing cue has been heard: give the audio route back. */
    fun settle()

    /** The words of a finished turn; blank when nothing intelligible was said. */
    suspend fun transcribe(turn: TurnListener.Turn): String
}
