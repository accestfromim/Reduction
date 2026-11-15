package com.mindtravel.deepseekassistant.ui.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.mindtravel.deepseekassistant.BuildConfig
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import com.mindtravel.deepseekassistant.ui.activity.MainActivity
import org.json.JSONObject

class SystemBridge(
    private val activity: MainActivity,
    private val settings: SettingsRepository,
    private val voice: VoiceBridge,
    private val emitter: EventSink
) {
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

    fun requestBluetoothPermission() = activity.requestBluetoothPermission()

    fun hasHeadsetOutput(): Boolean = activity.hasHeadsetOutput()

    fun copyText(text: String) {
        activity.runOnUiThread {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DSAssist", text))
            android.widget.Toast.makeText(activity, "已复制", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun appInfo(): String {
        val current = settings.load()
        return JSONObject().apply {
            put("version", BuildConfig.VERSION_NAME)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("model", current.model)
            put("voice", current.voice)
            put("hasKey", current.apiKey.isNotBlank())
            put("pcHost", current.pcHost)
            put("pcPort", current.pcPort)
            put("pcUser", current.pcUser)
            put("cacheMb", voice.cacheSizeMb())
        }.toString()
    }

    fun shareDiagnostics() {
        val current = settings.load()
        val info = buildString {
            appendLine("DSAssist v${BuildConfig.VERSION_NAME}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Model: ${current.model}")
            appendLine("Voice: ${current.voice}")
            appendLine("Key set: ${current.apiKey.isNotBlank()}")
            appendLine("PC SSH: ${current.pcUser}@${current.pcHost}:${current.pcPort}")
            appendLine("TTS cache: %.1f MB".format(voice.cacheSizeMb()))
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

    fun pauseWakeWord() {
        activity.runOnUiThread { com.mindtravel.deepseekassistant.platform.wake.WakeWordService.pause(activity) }
    }

    fun resumeWakeWord() {
        activity.runOnUiThread { com.mindtravel.deepseekassistant.platform.wake.WakeWordService.resume(activity) }
    }

    fun consumeWakePending(): Boolean =
        com.mindtravel.deepseekassistant.platform.wake.WakeWordBus.consumePending()

    fun setWakeWordEnabled(enabled: Boolean): Boolean {
        settings.update { it.copy(wakeWordEnabled = enabled) }
        activity.runOnUiThread { activity.setWakeWordEnabled(enabled) }
        return true
    }

    fun onWakeWord() {
        emitter.emit("onWakeWord", "")
    }

    fun onPause() = voice.cancelActiveRecording()

    fun dispose() {
        voice.disposeRecording()
        voice.disposeTts()
    }
}
