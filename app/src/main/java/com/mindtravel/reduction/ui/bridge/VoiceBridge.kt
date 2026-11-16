package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.feature.voice.RecordingController
import com.mindtravel.reduction.feature.voice.SpeechController
import com.mindtravel.reduction.feature.voice.VoiceRuntime

class VoiceBridge(
    private val recording: RecordingController,
    private val speech: SpeechController,
    private val voice: VoiceRuntime
) {
    fun requestMicPermission() = recording.requestMicPermission()
    fun startRecording(id: String) = recording.startRecording(id)
    fun stopAndTranscribe(id: String) = recording.stopAndTranscribe(id)
    fun cancelRecording() = recording.cancelRecording()
    fun cancelActiveRecording() = recording.cancelActiveRecording()

    fun voiceState(): String = voice.snapshot()
    fun voiceFinish() = voice.finishTurn()
    fun voiceCancel() = voice.cancel()

    fun speak(id: String, text: String) = speech.speak(id, text)
    fun stopSpeaking() = speech.stop(notify = true)
    fun clearAudioCache(): String = speech.clearCache()
    fun cacheSizeMb(): Double = speech.cacheSizeMb()
}
