package com.mindtravel.deepseekassistant.feature.voice.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import com.mindtravel.deepseekassistant.platform.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.platform.audio.HeadsetMicController
import com.mindtravel.deepseekassistant.platform.audio.WavRecorder
import com.mindtravel.deepseekassistant.core.host.PermissionHost
import com.mindtravel.deepseekassistant.core.events.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class RecordingController(
    private val host: PermissionHost,
    private val settingsRepository: SettingsGateway,
    private val audioGateway: AudioGateway,
    private val wavRecorder: WavRecorder,
    private val handsFreeRecorder: HandsFreeRecorder,
    private val headsetMic: HeadsetMicController,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {

    private val finishingPushToTalk = AtomicBoolean(false)

    private var speculativeJob: Job? = null
    private var speculativeGeneration = 0
    private var speculativeResult: String? = null
    private var speculativeFailed = false
    private var finalizingSpeculative = false

    fun requestMicPermission() {
        host.requestMicPermission { granted ->
            emitter.emit("onMicPermission", granted)
        }
    }

    fun startRecording(id: String) {
        resetSpeculative()
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
        resetSpeculative()
        val useHeadset = settings.headsetMicEnabled && headsetMic.start()
        handsFreeRecorder.start(
            followup = followup,
            useCommunicationSource = useHeadset,
            onSpeechStart = { emitter.emit("onHandsFreeStart", id) },
            onSpeechPause = { wav -> startSpeculativeAsr(id, wav) },
            onSpeechResume = { invalidateSpeculative() },
            onSpeechEnd = { wav ->
                headsetMic.stop()
                scope.launch {
                    try {
                        finishHandsFreeAsr(id, wav)
                    } catch (t: Throwable) {
                        Logs.add("Hands-free ASR failed: ${t.message}")
                        emitter.emit("onAsrResult", id, false, t.message ?: "识别失败")
                    }
                }
            },
            onTimeout = {
                headsetMic.stop()
                resetSpeculative()
                emitter.emit("onHandsFreeTimeout", id)
            },
            onError = { message ->
                headsetMic.stop()
                resetSpeculative()
                emitter.emit("onHandsFreeError", id, message)
            }
        )
    }

    fun cancelHandsFree() {
        resetSpeculative()
        handsFreeRecorder.cancel()
        headsetMic.stop()
    }

    fun dispose() {
        resetSpeculative()
        wavRecorder.cancel()
        handsFreeRecorder.cancel()
        headsetMic.stop()
    }

    private fun startSpeculativeAsr(id: String, wav: ByteArray) {
        if (wav.isEmpty()) return
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) return

        var generation = 0
        synchronized(this) {
            if (!finalizingSpeculative) {
                speculativeGeneration++
                generation = speculativeGeneration
                speculativeJob?.cancel()
                speculativeJob = null
                speculativeResult = null
                speculativeFailed = false
            }
        }
        if (generation == 0) return

        Logs.add("Speculative ASR start bytes=${wav.size}")
        val job = scope.launch(Dispatchers.IO) {
            try {
                val text = audioGateway.transcribe(settings.apiKey, wav)
                synchronized(this@RecordingController) {
                    if (generation == speculativeGeneration && !finalizingSpeculative) {
                        speculativeResult = text
                        Logs.add("Speculative ASR ready chars=${text.length}")
                    }
                }
            } catch (t: Throwable) {
                synchronized(this@RecordingController) {
                    if (generation == speculativeGeneration && !finalizingSpeculative) {
                        speculativeFailed = true
                    }
                }
                Logs.add("Speculative ASR failed: ${t.message}")
            } finally {
                synchronized(this@RecordingController) {
                    if (generation == speculativeGeneration) speculativeJob = null
                }
            }
        }
        synchronized(this) {
            if (generation == speculativeGeneration && !finalizingSpeculative) {
                speculativeJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun invalidateSpeculative() {
        synchronized(this) {
            speculativeGeneration++
            speculativeJob?.cancel()
            speculativeJob = null
            speculativeResult = null
            speculativeFailed = false
        }
    }

    private fun resetSpeculative() {
        synchronized(this) {
            speculativeGeneration++
            finalizingSpeculative = false
            speculativeJob?.cancel()
            speculativeJob = null
            speculativeResult = null
            speculativeFailed = false
        }
    }

    private suspend fun finishHandsFreeAsr(id: String, wav: ByteArray) {
        emitter.emit("onStatus", id, "识别中")
        var job: Job? = null
        var result: String? = null
        synchronized(this) {
            finalizingSpeculative = true
            job = speculativeJob
            result = speculativeResult
        }

        if (result == null && job != null) {
            val finished = withTimeoutOrNull(1_500L) { job.join() }
            if (finished == null) Logs.add("Speculative ASR wait timeout")
            synchronized(this) {
                result = speculativeResult
            }
        }

        if (!result.isNullOrBlank()) {
            Logs.add("Speculative ASR hit")
            resetSpeculative()
            emitter.emit("onAsrResult", id, true, result)
            return
        }

        Logs.add("Speculative ASR miss failed=$speculativeFailed")
        resetSpeculative()
        transcribeWav(id, wav)
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
