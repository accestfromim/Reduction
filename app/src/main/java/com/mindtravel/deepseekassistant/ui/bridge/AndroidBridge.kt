package com.mindtravel.deepseekassistant.ui.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import com.mindtravel.deepseekassistant.BuildConfig
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.feature.settings.model.AppSettings
import com.mindtravel.deepseekassistant.feature.chat.data.HistoryRepository
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import com.mindtravel.deepseekassistant.ui.activity.MainActivity
import com.mindtravel.deepseekassistant.feature.voice.ui.RecordingController
import com.mindtravel.deepseekassistant.feature.voice.ui.TtsController
import com.mindtravel.deepseekassistant.feature.chat.ui.ChatController
import com.mindtravel.deepseekassistant.feature.pc.ui.PcController
import com.mindtravel.deepseekassistant.feature.pc.ui.UpdateController
import com.mindtravel.deepseekassistant.platform.wake.WakeWordBus
import com.mindtravel.deepseekassistant.platform.wake.WakeWordService
import org.json.JSONArray
import org.json.JSONObject

class AndroidBridge(
    private val activity: MainActivity,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val chatController: ChatController,
    private val recordingController: RecordingController,
    private val ttsController: TtsController,
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
    fun requestMicPermission() = recordingController.requestMicPermission()

    @JavascriptInterface
    fun startRecording(id: String) = recordingController.startRecording(id)

    @JavascriptInterface
    fun stopAndTranscribe(id: String) = recordingController.stopAndTranscribe(id)

    @JavascriptInterface
    fun cancelRecording() = recordingController.cancelRecording()

    @JavascriptInterface
    fun startHandsFree(id: String, followup: Boolean) = recordingController.startHandsFree(id, followup)

    @JavascriptInterface
    fun cancelHandsFree() = recordingController.cancelHandsFree()

    @JavascriptInterface
    fun ttsStart(id: String) = ttsController.ttsStart(id)

    @JavascriptInterface
    fun ttsAppend(id: String, text: String) = ttsController.ttsAppend(id, text)

    @JavascriptInterface
    fun ttsFinish(id: String, text: String) = ttsController.ttsFinish(id, text)

    @JavascriptInterface
    fun speak(id: String, text: String) = ttsController.speak(id, text)

    @JavascriptInterface
    fun stopSpeaking() = ttsController.stopSpeaking()

    @JavascriptInterface
    fun clearAudioCache(): String = ttsController.clearAudioCache()

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
            put("cacheMb", ttsController.cacheSizeMb())
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
            appendLine("TTS cache: %.1f MB".format(ttsController.cacheSizeMb()))
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
    fun pauseWakeWord() {
        activity.runOnUiThread { WakeWordService.pause(activity) }
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
        recordingController.cancelActiveRecording()
    }

    fun dispose() {
        recordingController.dispose()
        ttsController.dispose()
    }
}
