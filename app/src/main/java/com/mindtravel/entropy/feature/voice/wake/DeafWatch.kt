package com.mindtravel.entropy.feature.voice.wake

/**
 * Notices when the microphone has gone deaf. A service the system restarted in the
 * background, or one that lost the microphone to another app, is still handed audio —
 * but every sample is zero. A real microphone is never that quiet.
 */
class DeafWatch(private val framesToDecide: Int = 50) {

    private var silentFrames = 0

    var deaf = false
        private set

    /** Feeds one frame. Returns true when this frame changed the verdict. */
    fun onFrame(frame: ShortArray): Boolean {
        if (frame.all { it == 0.toShort() }) {
            silentFrames++
            if (!deaf && silentFrames >= framesToDecide) {
                deaf = true
                return true
            }
        } else {
            silentFrames = 0
            if (deaf) {
                deaf = false
                return true
            }
        }
        return false
    }

    fun reset() {
        silentFrames = 0
        deaf = false
    }
}
