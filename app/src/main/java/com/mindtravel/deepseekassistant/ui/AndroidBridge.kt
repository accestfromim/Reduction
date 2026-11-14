package com.mindtravel.deepseekassistant.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import com.mindtravel.deepseekassistant.BuildConfig
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.data.AppSettings
import com.mindtravel.deepseekassistant.data.HistoryRepository
import com.mindtravel.deepseekassistant.data.SettingsRepository
import com.mindtravel.deepseekassistant.ui.controller.AudioController
import com.mindtravel.deepseekassistant.ui.controller.ChatController
import com.mindtravel.deepseekassistant.ui.controller.PcController
import com.mindtravel.deepseekassistant.ui.controller.UpdateController
import com.mindtravel.deepseekassistant.wake.WakeWordBus
import com.mindtravel.deepseekassistant.wake.WakeWordService
import org.json.JSONArray
import org.json.JSONObject

class AndroidBridge(
    private val activity: MainActivity,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val chatController: ChatController,
    private val audioController: AudioController,
    private val pcController: PcController,
    private val updateController: UpdateController,
    private val emitter: JsEmitter
) {

    @JavascriptInterface
    fun loadSettings(): String = settingsRepository.load().toJson().toString()

    @JavascriptInterface
    fun saveSettings(json: String): Boolean = runCatching {
        settingsRepository.save(AppSettings.fromJson(json))
        true
    }.getOrElse {
        Logs.add("saveSettings failed: ${it.message}")
        false
    }

    @JavascriptInterface
    fun loadHistory(): String = historyRepository.load()

    @JavascriptInterface
    fun saveHistory(json: String): Boolean = runCatching {
        JSONArray(json)
        historyRepository.save(json)
    }.getOrDefault(false)

    @JavascriptInterface
    fun clearHistory() {
        historyRepository.clear()
    }

    @JavascriptInterface
    fun requestMicPermission() = audioController.requestMicPermission()

    @JavascriptInterface
    fun startRecording(id: String) = audioController.startRecording(id)

    @JavascriptInterface
    fun stopAndTranscribe(id: String) = audioController.stopAndTranscribe(id)

    @JavascriptInterface
    fun cancelRecording() = audioController.cancelRecording()

    @JavascriptInterface
    fun startHandsFree(id: String, followup: Boolean) = audioController.startHandsFree(id, followup)

    @JavascriptInterface
    fun cancelHandsFree() = audioController.cancelHandsFree()

    @JavascriptInterface
    fun ttsStart(id: String) = audioController.ttsStart(id)

    @JavascriptInterface
    fun ttsAppend(id: String, text: String) = audioController.ttsAppend(id, text)

    @JavascriptInterface
    fun ttsFinish(id: String, text: String) = audioController.ttsFinish(id, text)

    @JavascriptInterface
    fun speak(id: String, text: String) = audioController.speak(id, text)

    @JavascriptInterface
    fun stopSpeaking() = audioController.stopSpeaking()

    @JavascriptInterface
    fun clearAudioCache(): String = audioController.clearAudioCache()

    @JavascriptInterface
    fun sendChat(id: String, messagesJson: String): Boolean = chatController.send(id, messagesJson)

    @JavascriptInterface
    fun pcInfo(id: String) = pcController.info(id)

    @JavascriptInterface
    fun pcExec(id: String, command: String) = pcController.exec(id, command)

    @JavascriptInterface
    fun pcCheckUpdate(id: String) = updateController.check(id)

    @JavascriptInterface
    fun vibrate(ms: Int) {
        activity.runOnUiThread {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return@runOnUiThread
            val duration = ms.coerceIn(5, 100).toLong()
            vibrator.vibrate(
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        val settings = settingsRepository.load()
        return JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("model", settings.model)
            put("voice", settings.voice)
            put("hasKey", settings.apiKey.isNotBlank())
            put("pcUrl", settings.pcUrl)
            put("hasPcToken", settings.pcToken.isNotBlank())
            put("cacheMb", audioController.cacheSizeMb())
        }.toString()
    }

    @JavascriptInterface
    fun shareDiagnostics() {
        val settings = settingsRepository.load()
        val info = buildString {
            appendLine("DeepSeek 助手 v${BuildConfig.VERSION_NAME}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Model: ${settings.model}")
            appendLine("Voice: ${settings.voice}")
            appendLine("Key set: ${settings.apiKey.isNotBlank()}")
            appendLine("PC: ${settings.pcUrl} (token ${if (settings.pcToken.isNotBlank()) "set" else "empty"})")
            appendLine("TTS cache: %.1f MB".format(audioController.cacheSizeMb()))
            appendLine("--- logs ---")
            append(Logs.dump())
        }
        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, info)
            }
            activity.startActivity(Intent.createChooser(intent, "分享诊断信息"))
        }
    }

    @JavascriptInterface
    fun resumeWakeWord() {
        activity.runOnUiThread { WakeWordService.resume(activity) }
    }

    @JavascriptInterface
    fun consumeWakePending(): Boolean = WakeWordBus.consumePending()

    @JavascriptInterface
    fun setWakeWordEnabled(enabled: Boolean): Boolean {
        settingsRepository.update { it.copy(wakeWordEnabled = enabled) }
        activity.runOnUiThread { activity.setWakeWordEnabled(enabled) }
        return true
    }

    fun onWakeWord() {
        emitter.emit("onWakeWord", "")
    }

    fun onPause() {
        audioController.cancelActiveRecording()
    }

    fun dispose() {
        audioController.dispose()
    }
}
