package com.mindtravel.reduction.ui.bridge

import android.webkit.JavascriptInterface

class AndroidBridge(
    private val settings: SettingsBridge,
    private val voice: VoiceBridge,
    private val chat: ChatBridge,
    private val cards: CardBridge,
    private val pc: PcBridge,
    private val update: UpdateBridge,
    private val system: SystemBridge
) {
    @JavascriptInterface
    fun loadSettings(): String = settings.loadSettings()

    @JavascriptInterface
    fun saveSettings(json: String): Boolean = settings.saveSettings(json)

    @JavascriptInterface
    fun loadHistory(): String = settings.loadHistory()

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
    fun voiceState(): String = voice.voiceState()

    @JavascriptInterface
    fun voiceFinish() = voice.voiceFinish()

    @JavascriptInterface
    fun voiceCancel() = voice.voiceCancel()

    @JavascriptInterface
    fun speak(id: String, text: String) = voice.speak(id, text)

    @JavascriptInterface
    fun stopSpeaking() = voice.stopSpeaking()

    @JavascriptInterface
    fun clearAudioCache(): String = voice.clearAudioCache()

    @JavascriptInterface
    fun listCards(): String = cards.list()

    @JavascriptInterface
    fun readCard(name: String): String = cards.read(name)

    @JavascriptInterface
    fun cardPurpose(name: String): String = cards.purpose(name)

    @JavascriptInterface
    fun saveCard(name: String, purpose: String, content: String): Boolean = cards.save(name, purpose, content)

    @JavascriptInterface
    fun deleteCard(name: String): Boolean = cards.delete(name)

    @JavascriptInterface
    fun submitText(text: String): Boolean = chat.submit(text)

    @JavascriptInterface
    fun pcConnect() = pc.connect()

    @JavascriptInterface
    fun pcConnected(): Boolean = pc.isConnected()

    @JavascriptInterface
    fun pcState(): String = pc.state()

    @JavascriptInterface
    fun pcInfo(id: String) = pc.info(id)

    @JavascriptInterface
    fun pcExec(id: String, command: String) = pc.exec(id, command)

    @JavascriptInterface
    fun pcPublicKey(): String = pc.publicKey()

    @JavascriptInterface
    fun checkUpdate() = update.check()

    @JavascriptInterface
    fun installUpdate() = update.install()

    @JavascriptInterface
    fun updateState(): String = update.state()

    @JavascriptInterface
    fun updateVersion(): String = update.version()

    @JavascriptInterface
    fun vibrate(ms: Int) = system.vibrate(ms)

    @JavascriptInterface
    fun requestBluetoothPermission() = system.requestBluetoothPermission()

    @JavascriptInterface
    fun copyText(text: String) = system.copyText(text)

    @JavascriptInterface
    fun getAppInfo(): String = system.appInfo()

    @JavascriptInterface
    fun shareDiagnostics() = system.shareDiagnostics()

    @JavascriptInterface
    fun setWakeWordEnabled(enabled: Boolean): Boolean = system.setWakeWordEnabled(enabled)

    fun onPause() = system.onPause()
}
