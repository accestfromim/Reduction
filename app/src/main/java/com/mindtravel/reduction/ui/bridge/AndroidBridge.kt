package com.mindtravel.reduction.ui.bridge

import android.webkit.JavascriptInterface
import com.mindtravel.reduction.feature.memo.MemoView
import com.mindtravel.reduction.feature.memo.alarm.MemoAlarms
import com.mindtravel.reduction.feature.memo.data.MemoStore

class AndroidBridge(
    private val settings: SettingsBridge,
    private val voice: VoiceBridge,
    private val chat: ChatBridge,
    private val cards: CardBridge,
    private val pc: PcBridge,
    private val update: UpdateBridge,
    private val system: SystemBridge,
    private val memos: MemoStore,
    private val alarms: MemoAlarms
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
    fun autosaveCard(name: String, purpose: String, content: String, creating: Boolean, oldPurpose: String, oldText: String) =
        cards.autosave(name, purpose, content, creating, oldPurpose, oldText)

    @JavascriptInterface
    fun deleteCard(name: String): Boolean = cards.delete(name)

    @JavascriptInterface
    fun renameCard(from: String, to: String): Boolean = cards.rename(from, to)

    @JavascriptInterface
    fun lockCard(name: String, locked: Boolean): Boolean = cards.setLocked(name, locked)

    @JavascriptInterface
    fun saveCardMedia(base64: String, extension: String): String = cards.saveMedia(base64, extension)

    /** The article template and stylesheet in use: {template, style, problem}. */
    @JavascriptInterface
    fun articleTemplate(): String = cards.articleTemplate()

    /** One page of a PDF kept in a card, as the page drew it: a JPEG in base64. */
    @JavascriptInterface
    fun saveCardPage(pdfId: String, page: Int, base64: String): Boolean = cards.savePage(pdfId, page, base64)

    /** How many pages of a PDF have been kept as pictures; 0 when it has not been drawn yet. */
    @JavascriptInterface
    fun cardPages(pdfId: String): Int = cards.pages(pdfId)

    /** The to-dos in the review card's three blocks, and how well the reminders can work. */
    @JavascriptInterface
    fun memoView(): String = runCatching {
        val status = alarms.status()
        MemoView.json(memos, exact = status.exact, notify = status.notify)
    }.getOrDefault("{}")

    /** Ticking a to-do off on the card: a finished one leaves the reminders and stays in the record. */
    @JavascriptInterface
    fun setMemoDone(id: Int, done: Boolean): Boolean = runCatching { memos.setDone(id, done) != null }.getOrDefault(false)

    /** The system page where exact alarms are allowed, for a user who wants them to the minute. */
    @JavascriptInterface
    fun askForExactAlarms(): Boolean = system.askForExactAlarms()

    /** The runtime permission for notifications. */
    @JavascriptInterface
    fun askForNotifications(): Boolean = system.askForNotifications()

    @JavascriptInterface
    fun floatingCard(): String = cards.floating()

    @JavascriptInterface
    fun floatCard(name: String): Boolean = cards.float(name)

    @JavascriptInterface
    fun submitText(text: String): Boolean = chat.submit(text)

    /** What was typed, with pictures: a JSON list of {name, mediaType, base64}. */
    @JavascriptInterface
    fun submitPictures(text: String, picturesJson: String): Boolean = chat.submit(text, picturesJson)

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
