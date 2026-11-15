package com.mindtravel.deepseekassistant.feature.voice.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.feature.voice.model.RollingAsr
import com.mindtravel.deepseekassistant.feature.voice.model.TranscriptEndDetector
import com.mindtravel.deepseekassistant.platform.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.platform.audio.HeadsetMicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HandsFreeSession(
    private val settingsRepository: SettingsGateway,
    private val audioGateway: AudioGateway,
    private val handsFreeRecorder: HandsFreeRecorder,
    private val headsetMic: HeadsetMicController,
    private val rollingAsr: RollingAsr,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {
    private val endDetector = TranscriptEndDetector()

    @Volatile
    private var captureToken = 0

    @Volatile
    private var activeApiKey = ""

    @Volatile
    private var activeId: String? = null

    private var maxJob: Job? = null

    fun start(id: String, followup: Boolean) {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onHandsFreeError", id, "请先在设置里填写 API Key")
            return
        }

        Logs.add("Hands-free requested followup=$followup")
        reset(stopRecorder = true)
        activeApiKey = settings.apiKey
        activeId = id
        val token = captureToken
        val useHeadset = settings.headsetMicEnabled && headsetMic.start()
        val started = handsFreeRecorder.start(
            followup = followup,
            useCommunicationSource = useHeadset,
            onSpeechStart = {
                if (token == captureToken) {
                    emitter.emit("onHandsFreeStart", id)
                    startRolling(id, token, settings.apiKey)
                }
            },
            onTimeout = {
                if (token == captureToken) {
                    reset(stopRecorder = false)
                    emitter.emit("onHandsFreeTimeout", id)
                }
            },
            onError = { message ->
                if (token == captureToken) {
                    reset(stopRecorder = false)
                    emitter.emit("onHandsFreeError", id, message)
                }
            }
        )
        if (!started && token == captureToken) {
            reset(stopRecorder = false)
            emitter.emit("onHandsFreeError", id, "无法启动录音")
        }
    }

    fun finishNow() {
        val id = activeId ?: return
        val token = captureToken
        if (token == 0) return
        finish(id, token, endDetector.currentText(), preferText = false)
    }

    fun cancel() {
        reset(stopRecorder = true)
        headsetMic.stop()
    }

    fun dispose() = cancel()

    private fun startRolling(id: String, token: Int, apiKey: String) {
        rollingAsr.start(
            apiKey = apiKey,
            snapshot = { handsFreeRecorder.snapshotWav() },
            onResult = { text, snapshotAt ->
                if (token == captureToken) handleResult(id, token, text, snapshotAt)
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
                finish(id, token, endDetector.currentText(), preferText = false)
            }
        }
    }

    private fun handleResult(id: String, token: Int, text: String?, snapshotAt: Long) {
        if (token != captureToken) return
        val hasVoice = handsFreeRecorder.hasVoiceSince(snapshotAt)
        when (val decision = endDetector.evaluate(text, hasVoice)) {
            TranscriptEndDetector.Decision.Continue -> Unit
            is TranscriptEndDetector.Decision.End -> {
                if (!text.isNullOrBlank() && endDetector.stripEndWord(text) != null) {
                    emitter.emit("onTurnEnd", id)
                }
                finish(id, token, decision.text)
            }
        }
    }

    private fun finish(
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
        activeId = null
        endDetector.reset()
        val wav = handsFreeRecorder.stop()
        headsetMic.stop()

        scope.launch {
            emitter.emit("onStatus", id, "识别中")

            val cleaned = if (preferText) clean(text) else ""
            if (cleaned.isNotBlank()) {
                emitter.emit("onAsrResult", id, true, cleaned)
                return@launch
            }

            if (wav == null) {
                val fallback = clean(text)
                if (fallback.isNotBlank()) {
                    emitter.emit("onAsrResult", id, true, fallback)
                } else {
                    emitter.emit("onAsrResult", id, false, "没有听到内容，请重说")
                }
                return@launch
            }

            try {
                val raw = withContext(Dispatchers.IO) { audioGateway.transcribe(apiKey, wav) }
                val finalText = clean(raw)
                if (finalText.isNotBlank()) {
                    emitter.emit("onAsrResult", id, true, finalText)
                } else {
                    val fallback = clean(text)
                    if (fallback.isNotBlank()) {
                        emitter.emit("onAsrResult", id, true, fallback)
                    } else {
                        emitter.emit("onAsrResult", id, false, "没有识别到内容，请重说")
                    }
                }
            } catch (t: Throwable) {
                Logs.add("Hands-free final ASR failed: ${t.message}")
                val fallback = clean(text)
                if (fallback.isNotBlank()) {
                    emitter.emit("onAsrResult", id, true, fallback)
                } else {
                    emitter.emit("onAsrResult", id, false, t.message ?: "识别失败")
                }
            }
        }
    }

    private fun reset(stopRecorder: Boolean) {
        captureToken++
        rollingAsr.stop()
        maxJob?.cancel()
        maxJob = null
        endDetector.reset()
        activeApiKey = ""
        activeId = null
        if (stopRecorder) handsFreeRecorder.cancel()
    }

    private fun clean(raw: String): String {
        val body = endDetector.stripEndWord(raw)
        return (body ?: raw).trim()
    }
}
