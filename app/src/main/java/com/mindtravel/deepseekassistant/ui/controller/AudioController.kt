package com.mindtravel.deepseekassistant.ui.controller

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.mindtravel.deepseekassistant.audio.BargeInDetector
import com.mindtravel.deepseekassistant.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.audio.TtsCache
import com.mindtravel.deepseekassistant.audio.TtsPlayer
import com.mindtravel.deepseekassistant.audio.WavRecorder
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.data.SettingsRepository
import com.mindtravel.deepseekassistant.network.audio.AudioApi
import com.mindtravel.deepseekassistant.ui.JsEmitter
import com.mindtravel.deepseekassistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AudioController(
    private val activity: MainActivity,
    private val settingsRepository: SettingsRepository,
    private val audioApi: AudioApi,
    private val ttsPlayer: TtsPlayer,
    private val ttsCache: TtsCache,
    private val wavRecorder: WavRecorder,
    private val handsFreeRecorder: HandsFreeRecorder,
    private val emitter: JsEmitter,
    private val scope: CoroutineScope
) {

    private val finishingPushToTalk = AtomicBoolean(false)

    @Volatile
    private var activeTtsId: String? = null

    private var bargeInDetector: BargeInDetector? = null

    fun requestMicPermission() {
        activity.requestMicPermission { granted ->
            emitter.emit("onMicPermission", granted)
        }
    }

    fun startRecording(id: String) {
        if (wavRecorder.isRecording() || finishingPushToTalk.get()) {
            emitter.emit("onError", id, "正在处理上一段语音")
            return
        }
        if (!activity.hasMicPermission()) {
            activity.requestMicPermission { granted ->
                if (granted) {
                    startRecording(id)
                } else {
                    emitter.emit("onMicPermission", false)
                    emitter.emit("onError", id, "需要麦克风权限")
                }
            }
            return
        }

        val started = wavRecorder.start {
            emitter.emit("onRecordingLimit", id)
            scope.launch { finishRecording(id) }
        }
        if (!started) {
            emitter.emit("onError", id, "无法启动录音")
            return
        }
        emitter.emit("onRecordingStart", id)
    }

    fun stopAndTranscribe(id: String) {
        scope.launch { finishRecording(id) }
    }

    fun cancelRecording() {
        wavRecorder.cancel()
        finishingPushToTalk.set(false)
    }

    fun cancelActiveRecording() {
        wavRecorder.cancel()
    }

    fun startHandsFree(id: String, followup: Boolean) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onHandsFreeError", id, "请先在设置里填写 API Key")
            return
        }
        if (!activity.hasMicPermission()) {
            activity.requestMicPermission { granted ->
                if (granted) {
                    startHandsFree(id, followup)
                } else {
                    emitter.emit("onHandsFreeError", id, "需要麦克风权限")
                }
            }
            return
        }

        Logs.add("Hands-free requested followup=$followup")
        handsFreeRecorder.start(
            followup = followup,
            onSpeechStart = { emitter.emit("onHandsFreeStart", id) },
            onSpeechEnd = { wav ->
                scope.launch {
                    try {
                        transcribeWav(id, wav)
                    } catch (t: Throwable) {
                        Logs.add("Hands-free ASR failed: ${t.message}")
                        emitter.emit("onAsrResult", id, false, t.message ?: "识别失败")
                    }
                }
            },
            onTimeout = { emitter.emit("onHandsFreeTimeout", id) },
            onError = { message -> emitter.emit("onHandsFreeError", id, message) }
        )
    }

    fun cancelHandsFree() {
        handsFreeRecorder.cancel()
    }

    fun ttsStart(id: String) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onTtsError", id, "请先在设置里填写 API Key")
            return
        }
        Logs.add("TTS stream start voice=${settings.voice}")
        stopBargeIn()
        activeTtsId = id
        ttsPlayer.startSession(
            apiKey = settings.apiKey,
            voice = settings.voice,
            onStart = {
                emitter.emit("onTtsStart", id)
                maybeStartBargeIn(id)
            },
            onDone = {
                stopBargeIn()
                emitter.emit("onTtsDone", id)
            },
            onError = { message ->
                stopBargeIn()
                emitter.emit("onTtsError", id, message)
            }
        )
    }

    fun ttsAppend(id: String, text: String) {
        if (id != activeTtsId || text.isBlank()) return
        ttsPlayer.append(text)
    }

    fun ttsFinish(id: String, text: String) {
        if (id != activeTtsId) return
        if (text.isNotBlank()) ttsPlayer.append(text)
        ttsPlayer.finish()
        activeTtsId = null
    }

    fun speak(id: String, text: String) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onError", id, "请先在设置里填写 API Key")
            return
        }
        if (text.isBlank()) return
        Logs.add("speak requested chars=${text.length} voice=${settings.voice}")
        stopBargeIn()
        activeTtsId = id
        ttsPlayer.speak(
            apiKey = settings.apiKey,
            text = text,
            voice = settings.voice,
            onStart = {
                emitter.emit("onTtsStart", id)
                maybeStartBargeIn(id)
            },
            onDone = {
                stopBargeIn()
                emitter.emit("onTtsDone", id)
            },
            onError = { message ->
                stopBargeIn()
                emitter.emit("onTtsError", id, message)
            }
        )
    }

    fun stopSpeaking() {
        stopBargeIn()
        activeTtsId = null
        ttsPlayer.stop()
        emitter.emit("onTtsStopped", "")
    }

    fun clearAudioCache(): String {
        val bytes = ttsCache.clear()
        val mb = bytes / 1024.0 / 1024.0
        Logs.add("TTS cache cleared bytes=$bytes")
        return "已清理 %.1f MB 音频缓存".format(mb)
    }

    fun cacheSizeMb(): Double = ttsCache.size() / 1024.0 / 1024.0

    fun dispose() {
        stopBargeIn()
        ttsPlayer.stop()
        wavRecorder.cancel()
        handsFreeRecorder.cancel()
    }

    private fun maybeStartBargeIn(id: String) {
        val settings = settingsRepository.load()
        if (!settings.bargeInEnabled || !activity.hasMicPermission()) return
        if (!hasHeadsetOutput()) {
            Logs.add("Barge-in skipped: no headset output")
            return
        }
        if (bargeInDetector?.isRunning() == true) return

        val detector = BargeInDetector(
            onBargeIn = {
                bargeInDetector = null
                activeTtsId = null
                ttsPlayer.stop()
                emitter.emit("onBargeIn", id)
            },
            onError = { message -> Logs.add("Barge-in error: $message") }
        )
        bargeInDetector = detector
        detector.start()
    }

    private fun stopBargeIn() {
        bargeInDetector?.stop()
        bargeInDetector = null
    }

    private fun hasHeadsetOutput(): Boolean {
        val manager = activity.getSystemService(AudioManager::class.java) ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> true
                else -> false
            }
        }
    }

    private suspend fun finishRecording(id: String) {
        if (!finishingPushToTalk.compareAndSet(false, true)) return
        try {
            val wav = wavRecorder.stop()
            if (wav == null || wav.size < 1_000) {
                emitter.emit("onAsrResult", id, false, "没有录到声音，请再试一次")
                return
            }
            transcribeWav(id, wav)
        } catch (t: Throwable) {
            Logs.add("ASR failed: ${t.message}")
            emitter.emit("onAsrResult", id, false, t.message ?: "识别失败")
        } finally {
            finishingPushToTalk.set(false)
        }
    }

    private suspend fun transcribeWav(id: String, wav: ByteArray) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onAsrResult", id, false, "请先在设置里填写 API Key")
            return
        }
        emitter.emit("onStatus", id, "识别中")
        val text = withContext(Dispatchers.IO) { audioApi.transcribe(settings.apiKey, wav) }
        emitter.emit("onAsrResult", id, true, text)
    }
}
