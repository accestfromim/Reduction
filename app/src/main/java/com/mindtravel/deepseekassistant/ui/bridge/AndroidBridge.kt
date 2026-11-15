package com.mindtravel.deepseekassistant.ui.bridge

import android.webkit.JavascriptInterface

class AndroidBridge(
    private val settings: SettingsBridge,
    private val voice: VoiceBridge,
    private val chat: ChatBridge,
    private val pc: PcBridge,
    private val system: SystemBridge
) {
    @JavascriptInterface
    fun loadSettings(): String = settings.loadSettings()

    @JavascriptInterface
    fun saveSettings(json: String): Boolean = settings.saveSettings(json)

    @JavascriptInterface
    fun loadHistory(): String = settings.loadHistory()

    @JavascriptInterface
    fun saveHistory(json: String): Boolean = settings.saveHistory(json)

    @JavascriptInterface
    fun clearHistory() = settings.clearHistory()

    @JavascriptInterface
    fun requestMicPermission() = voice.requestMicPermission()

    @JavascriptInterface
    fun startRecording(id: String) = voice.startRecording(id)

    @JavascriptInterface
    fun stopAndTranscribe(id: String) = voice.stopAndTranscribe(id)

    @JavascriptInterface
    fun cancelRecording() = voice.cancelRecording()

    @JavascriptInterface
    fun startHandsFree(id: String, followup: Boolean) = voice.startHandsFree(id, followup)

    @JavascriptInterface
    fun finishHandsFree() = voice.finishHandsFree()

    @JavascriptInterface
    fun cancelHandsFree() = voice.cancelHandsFree()

    @JavascriptInterface
    fun ttsStart(id: String) = voice.ttsStart(id)

    @JavascriptInterface
    fun ttsAppend(id: String, text: String) = voice.ttsAppend(id, text)

    @JavascriptInterface
    fun ttsFinish(id: String, text: String) = voice.ttsFinish(id, text)

    @JavascriptInterface
    fun stopSpeaking() = voice.stopSpeaking()

    @JavascriptInterface
    fun clearAudioCache(): String = voice.clearAudioCache()

    @JavascriptInterface
    fun sendChat(id: String, messagesJson: String): Boolean = chat.send(id, messagesJson)

    @JavascriptInterface
    fun pcInfo(id: String) = pc.info(id)

    @JavascriptInterface
    fun pcExec(id: String, command: String) = pc.exec(id, command)

    @JavascriptInterface
    fun pcPublicKey(): String = pc.publicKey()

    @JavascriptInterface
    fun vibrate(ms: Int) = system.vibrate(ms)

    @JavascriptInterface
    fun requestBluetoothPermission() = system.requestBluetoothPermission()

    @JavascriptInterface
    fun hasHeadsetOutput(): Boolean = system.hasHeadsetOutput()

    @JavascriptInterface
    fun copyText(text: String) = system.copyText(text)

    @JavascriptInterface
    fun getAppInfo(): String = system.appInfo()

    @JavascriptInterface
    fun shareDiagnostics() = system.shareDiagnostics()

    @JavascriptInterface
    fun pauseWakeWord() = system.pauseWakeWord()

    @JavascriptInterface
    fun resumeWakeWord() = system.resumeWakeWord()

    @JavascriptInterface
    fun consumeWakePending(): Boolean = system.consumeWakePending()

    @JavascriptInterface
    fun setWakeWordEnabled(enabled: Boolean): Boolean = system.setWakeWordEnabled(enabled)

    fun onWakeWord() = system.onWakeWord()

    fun onPause() = system.onPause()

    fun dispose() = system.dispose()
}
