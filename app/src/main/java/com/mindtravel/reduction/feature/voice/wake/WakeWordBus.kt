package com.mindtravel.reduction.feature.voice.wake

object WakeWordBus {
    @Volatile
    var listener: (() -> Unit)? = null

    /** Given the few seconds of audio that led to each detection, as a WAV file. */
    @Volatile
    var clipSink: ((wav: ByteArray, keyword: String) -> Unit)? = null

    @Volatile
    private var pending = false

    @Synchronized
    fun onWake() {
        pending = true
        listener?.invoke()
    }

    @Synchronized
    fun consumePending(): Boolean {
        val value = pending
        pending = false
        return value
    }

    fun reset() {
        pending = false
    }
}
