package com.mindtravel.reduction.hardware.audio

import android.media.MediaRecorder

/** One hardware stream while headset wake listening is enabled. Frames with no listener are discarded. */
internal object WarmHeadsetInput {
    private class Listener(val frame: (ShortArray) -> Unit, val error: (String) -> Unit)
    private val listeners = mutableMapOf<PcmCapture,Listener>()
    private var hardware: PcmCapture? = null
    @Volatile var available = false
        private set

    @Synchronized fun open(): Boolean {
        if (available && hardware?.isRunning() == true) return true
        val recorder = PcmCapture(allowShared = false)
        hardware = recorder
        available = recorder.start(MediaRecorder.AudioSource.VOICE_COMMUNICATION, { pcm ->
            synchronized(this) {
                if (hardware === recorder) listeners.values.toList().forEach { it.frame(pcm) }
            }
        }, { message ->
            val failed = synchronized(this) {
                if (hardware !== recorder) emptyList() else {
                    available = false
                    listeners.values.toList().also { listeners.clear() }
                }
            }
            failed.forEach { it.error(message) }
        })
        return available
    }

    @Synchronized fun attach(owner: PcmCapture, frame: (ShortArray)->Unit, error: (String)->Unit): Boolean {
        if (!available || hardware?.isRunning() != true) return false
        listeners[owner] = Listener(frame,error)
        return true
    }
    @Synchronized fun detach(owner: PcmCapture) { listeners.remove(owner) }

    fun close() {
        val (recorder, pending) = synchronized(this) {
            available = false
            val old = hardware
            hardware = null
            old to listeners.values.toList().also { listeners.clear() }
        }
        recorder?.stop()
        pending.forEach { it.error("耳机麦克风已断开") }
    }
}
