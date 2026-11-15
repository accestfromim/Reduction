package com.mindtravel.deepseekassistant.feature.voice.ui

import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.host.PermissionHost

class RecordingController(
    private val host: PermissionHost,
    private val pushToTalk: PushToTalkSession,
    private val handsFree: HandsFreeSession,
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
        handsFree.cancel()
        pushToTalk.start(id)
    }

    fun stopAndTranscribe(id: String) = pushToTalk.stopAndTranscribe(id)

    fun cancelRecording() = pushToTalk.cancel()

    fun cancelActiveRecording() = pushToTalk.cancelActive()

    fun startHandsFree(id: String, followup: Boolean) {
        if (!host.hasMicPermission()) {
            host.requestMicPermission { granted ->
                if (granted) {
                    startHandsFree(id, followup)
                } else {
                    emitter.emit("onHandsFreeError", id, "需要麦克风权限")
                }
            }
            return
        }
        pushToTalk.cancel()
        handsFree.start(id, followup)
    }

    fun finishHandsFreeNow() = handsFree.finishNow()

    fun cancelHandsFree() = handsFree.cancel()

    fun dispose() {
        pushToTalk.dispose()
        handsFree.dispose()
    }
}
