package com.mindtravel.reduction.feature.voice

/** The phases of a hands-free conversation. The UI is told these by name. */
enum class VoiceState {
    /** Waiting for the wake word. */
    IDLE,

    /** Getting the microphone ready. */
    PREPARING,

    /** Recording the user's turn; the hum is sounding. */
    LISTENING,

    /** Recording is over: "好的" is sounding and the words are being worked out. */
    TURN_ACK,

    /** The reply is being generated and spoken. */
    REPLYING
}

/** Lets the voice runtime hand the microphone to, or take it back from, the wake word listener. */
interface WakeControl {
    fun pause()
    fun resume()
}
