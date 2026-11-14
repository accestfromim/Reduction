package com.mindtravel.deepseekassistant.wake

object WakeWordBus {
    @Volatile
    var listener: (() -> Unit)? = null

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
