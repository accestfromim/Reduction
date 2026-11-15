package com.mindtravel.entropy.feature.voice

import com.mindtravel.entropy.core.events.EventSink
import com.mindtravel.entropy.core.host.PermissionHost

/** Push-to-talk recording, guarded by the microphone permission. */
class RecordingController(
    private val host: PermissionHost,
    private val pushToTalk: PushToTalkSession,
    private val emitter: EventSink
) {
    fun requestMicPermission() {
        host.requestMicPermission { granted ->
            emitter.emit("onMicPermission", granted)
        }
    }

    fun startRecording(id: String) {
        if (!host.hasMicPermission()) {
            host.requestMicPermission { granted ->
                if (granted) {
                    startRecording(id)
                } else {
                    emitter.emit("onMicPermission", false)
                    emitter.emit("onError", id, "需要麦克风权限")
                }
            }
            return
        }
        pushToTalk.start(id)
    }

    fun stopAndTranscribe(id: String) = pushToTalk.stopAndTranscribe(id)

    fun cancelRecording() = pushToTalk.cancel()

    fun cancelActiveRecording() = pushToTalk.cancelActive()

    fun dispose() = pushToTalk.dispose()
}
