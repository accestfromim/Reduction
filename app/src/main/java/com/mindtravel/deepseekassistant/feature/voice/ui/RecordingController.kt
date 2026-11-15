package com.mindtravel.deepseekassistant.feature.voice.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import com.mindtravel.deepseekassistant.feature.voice.model.RollingAsr
import com.mindtravel.deepseekassistant.feature.voice.model.TranscriptEndDetector
import com.mindtravel.deepseekassistant.platform.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.platform.audio.HeadsetMicController
import com.mindtravel.deepseekassistant.platform.audio.WavRecorder
import com.mindtravel.deepseekassistant.core.host.PermissionHost
import com.mindtravel.deepseekassistant.core.events.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class RecordingController(
    private val host: PermissionHost,
    private val settingsRepository: SettingsGateway,
    private val audioGateway: AudioGateway,
    private val wavRecorder: WavRecorder,
    private val handsFreeRecorder: HandsFreeRecorder,
    private val headsetMic: HeadsetMicController,
    private val rollingAsr: RollingAsr,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {

    private val finishingPushToTalk = AtomicBoolean(false)
    private val endDetector = TranscriptEndDetector()

    @Volatile
    private var captureToken = 0

    @Volatile
    private var activeApiKey = ""

    @Volatile
    private var activeHandsFreeId: String? = null

    private var maxJob: Job? = null

    fun requestMicPermission() {
        host.requestMicPermission { granted ->
            emitter.emit("onMicPermission", granted)
        }
    }

    fun startRecording(id: String) {
        resetHandsFreeState()
        if (wavRecorder.isRecording() || finishingPushToTalk.get()) {
            emitter.emit("onError", id, "正在处理上一段语音")
            return
        }
        if (!host.hasMicPermission()) {
            host.requestMicPermission { granted ->
                if (granted) {
                    startRecording(id)
                } else {
                    emitter.emit("onMicPermission", false)
                    emitter.emit("onError", id, "需要麦克风权限")
                }
            }
            return
        }

        endDetector.reset()
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
        if (!host.hasMicPermission()) {
            host.requestMicPermission { granted ->
                if (granted) {
                    startHandsFree(id, followup)
                } else {
                    emitter.emit("onHandsFreeError", id, "需要麦克风权限")
                }
            }
            return
        }

        Logs.add("Hands-free requested followup=$followup")
        resetHandsFreeState()
        activeApiKey = settings.apiKey
        activeHandsFreeId = id
        val token = captureToken
        val useHeadset = settings.headsetMicEnabled && headsetMic.start()
        handsFreeRecorder.start(
            followup = followup,
            useCommunicationSource = useHeadset,
            onSpeechStart = {
                if (token == captureToken) {
                    emitter.emit("onHandsFreeStart", id)
                    startRollingAsr(id, token, settings.apiKey)
                }
            },
            onTimeout = {
                if (token == captureToken) {
                    resetHandsFreeState(stopRecorder = false)
                    emitter.emit("onHandsFreeTimeout", id)
                }
            },
            onError = { message ->
                if (token == captureToken) {
                    resetHandsFreeState(stopRecorder = false)
                    emitter.emit("onHandsFreeError", id, message)
                }
            }
        )
    }

    fun finishHandsFreeNow() {
        val id = activeHandsFreeId ?: return
        val token = captureToken
        if (token == 0) return
        finishHandsFree(id, token, endDetector.currentText(), preferText = false)
    }

    fun cancelHandsFree() {
        resetHandsFreeState()
        headsetMic.stop()
    }

    fun dispose() {
        resetHandsFreeState()
        wavRecorder.cancel()
        headsetMic.stop()
    }

    private fun startRollingAsr(id: String, token: Int, apiKey: String) {
        rollingAsr.start(
            apiKey = apiKey,
            snapshot = { handsFreeRecorder.snapshotWav() },
            onResult = { text, snapshotAt ->
                if (token == captureToken) handleRollingResult(id, token, text, snapshotAt)
            },
            onError = { message ->
                if (token == captureToken) Logs.add("Rolling ASR error: $message")
            }
        )

        maxJob?.cancel()
        maxJob = scope.launch {
            delay(20_000L)
            if (token == captureToken) {
                Logs.add("Hands-free max duration reached")
                finishHandsFree(id, token, endDetector.currentText(), preferText = false)
            }
        }
    }

    private fun handleRollingResult(id: String, token: Int, text: String?, snapshotAt: Long) {
        if (token != captureToken) return
        val hasVoice = handsFreeRecorder.hasVoiceSince(snapshotAt)
        when (val decision = endDetector.evaluate(text, hasVoice)) {
            TranscriptEndDetector.Decision.Continue -> Unit
            is TranscriptEndDetector.Decision.End -> finishHandsFree(id, token, decision.text)
        }
    }

    private fun finishHandsFree(
        id: String,
        token: Int,
        text: String,
        preferText: Boolean = true
    ) {
        if (token != captureToken) return
        captureToken++
        rollingAsr.stop()
        maxJob?.cancel()
        maxJob = null
        val apiKey = activeApiKey
        activeHandsFreeId = null
        endDetector.reset()
        val wav = handsFreeRecorder.stop()
        headsetMic.stop()

        scope.launch {
            emitter.emit("onStatus", id, "识别中")

            val cleaned = if (preferText) cleanTranscript(text) else ""
            if (cleaned.isNotBlank()) {
                emitter.emit("onAsrResult", id, true, cleaned)
                return@launch
            }

            if (wav == null) {
                val fallback = cleanTranscript(text)
                if (fallback.isNotBlank()) {
                    emitter.emit("onAsrResult", id, true, fallback)
                } else {
                    emitter.emit("onAsrResult", id, false, "没有听到内容，请重说")
                }
                return@launch
            }

            try {
                val raw = withContext(Dispatchers.IO) { audioGateway.transcribe(apiKey, wav) }
                val finalText = cleanTranscript(raw)
                if (finalText.isNotBlank()) {
                    emitter.emit("onAsrResult", id, true, finalText)
                } else {
                    val fallback = cleanTranscript(text)
                    if (fallback.isNotBlank()) {
                        emitter.emit("onAsrResult", id, true, fallback)
                    } else {
                        emitter.emit("onAsrResult", id, false, "没有识别到内容，请重说")
                    }
                }
            } catch (t: Throwable) {
                Logs.add("Hands-free final ASR failed: ${t.message}")
                val fallback = cleanTranscript(text)
                if (fallback.isNotBlank()) {
                    emitter.emit("onAsrResult", id, true, fallback)
                } else {
                    emitter.emit("onAsrResult", id, false, t.message ?: "识别失败")
                }
            }
        }
    }

    private fun resetHandsFreeState(stopRecorder: Boolean = true) {
        captureToken++
        rollingAsr.stop()
        maxJob?.cancel()
        maxJob = null
        endDetector.reset()
        activeApiKey = ""
        activeHandsFreeId = null
        if (stopRecorder) handsFreeRecorder.cancel()
    }

    private fun cleanTranscript(raw: String): String {
        val body = endDetector.stripEndWord(raw)
        return (body ?: raw).trim()
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
        val raw = withContext(Dispatchers.IO) { audioGateway.transcribe(settings.apiKey, wav) }
        val text = cleanTranscript(raw)
        if (text.isBlank()) {
            emitter.emit("onAsrResult", id, false, "没有识别到内容，请重说")
        } else {
            emitter.emit("onAsrResult", id, true, text)
        }
    }
}
