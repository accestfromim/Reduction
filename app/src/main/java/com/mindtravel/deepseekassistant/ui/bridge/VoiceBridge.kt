package com.mindtravel.deepseekassistant.ui.bridge

import com.mindtravel.deepseekassistant.feature.voice.ui.RecordingController
import com.mindtravel.deepseekassistant.feature.voice.ui.TtsController

class VoiceBridge(
    private val recording: RecordingController,
    private val tts: TtsController
) {
    fun requestMicPermission() = recording.requestMicPermission()
    fun startRecording(id: String) = recording.startRecording(id)
    fun stopAndTranscribe(id: String) = recording.stopAndTranscribe(id)
    fun cancelRecording() = recording.cancelRecording()
    fun startHandsFree(id: String, followup: Boolean) = recording.startHandsFree(id, followup)
    fun finishHandsFree() = recording.finishHandsFreeNow()
    fun cancelHandsFree() = recording.cancelHandsFree()
    fun ttsStart(id: String) = tts.ttsStart(id)
    fun ttsAppend(id: String, text: String) = tts.ttsAppend(id, text)
    fun ttsFinish(id: String, text: String) = tts.ttsFinish(id, text)
    fun stopSpeaking() = tts.stopSpeaking()
    fun clearAudioCache(): String = tts.clearAudioCache()
    fun cacheSizeMb(): Double = tts.cacheSizeMb()
    fun cancelActiveRecording() = recording.cancelActiveRecording()
    fun disposeRecording() = recording.dispose()
    fun disposeTts() = tts.dispose()
}
