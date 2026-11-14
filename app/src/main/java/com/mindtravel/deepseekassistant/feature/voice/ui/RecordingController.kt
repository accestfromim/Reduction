package com.mindtravel.deepseekassistant.feature.voice.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import com.mindtravel.deepseekassistant.platform.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.platform.audio.HeadsetMicController
import com.mindtravel.deepseekassistant.platform.audio.WavRecorder
import com.mindtravel.deepseekassistant.ui.activity.MainActivity
import com.mindtravel.deepseekassistant.ui.bridge.JsEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class RecordingController(
    private val activity: MainActivity,
    private val settingsRepository: SettingsRepository,
    private val audioGateway: AudioGateway,
    private val wavRecorder: WavRecorder,
    private val handsFreeRecorder: HandsFreeRecorder,
    private val headsetMic: HeadsetMicController,
    private val emitter: JsEmitter,
    private val scope: CoroutineScope
) {

    private val finishingPushToTalk = AtomicBoolean(false)

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

        val useHeadset = settingsRepository.load().headsetMicEnabled && headsetMic.start()
        val started = wavRecorder.start(useCommunicationSource = useHeadset) {
            emitter.emit("onRecordingLimit", id)
            scope.launch { finishRecording(id) }
        }
        if (!started) {
            headsetMic.stop()
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
        headsetMic.stop()
        finishingPushToTalk.set(false)
    }

    fun cancelActiveRecording() {
        wavRecorder.cancel()
        headsetMic.stop()
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
        val useHeadset = settings.headsetMicEnabled && headsetMic.start()
        handsFreeRecorder.start(
            followup = followup,
            useCommunicationSource = useHeadset,
            onSpeechStart = { emitter.emit("onHandsFreeStart", id) },
            onSpeechEnd = { wav ->
                headsetMic.stop()
                scope.launch {
                    try {
                        transcribeWav(id, wav)
                    } catch (t: Throwable) {
                        Logs.add("Hands-free ASR failed: ${t.message}")
                        emitter.emit("onAsrResult", id, false, t.message ?: "识别失败")
                    }
                }
            },
            onTimeout = {
                headsetMic.stop()
                emitter.emit("onHandsFreeTimeout", id)
            },
            onError = { message ->
                headsetMic.stop()
                emitter.emit("onHandsFreeError", id, message)
            }
        )
    }

    fun cancelHandsFree() {
        handsFreeRecorder.cancel()
        headsetMic.stop()
    }

    fun dispose() {
        wavRecorder.cancel()
        handsFreeRecorder.cancel()
        headsetMic.stop()
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
            headsetMic.stop()
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
        val text = withContext(Dispatchers.IO) { audioGateway.transcribe(settings.apiKey, wav) }
        emitter.emit("onAsrResult", id, true, text)
    }
}
