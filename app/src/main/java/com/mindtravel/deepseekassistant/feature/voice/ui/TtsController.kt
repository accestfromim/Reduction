package com.mindtravel.deepseekassistant.feature.voice.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.platform.audio.TtsCache
import com.mindtravel.deepseekassistant.platform.audio.BargeInDetector
import com.mindtravel.deepseekassistant.platform.audio.TtsPlayer
import com.mindtravel.deepseekassistant.core.host.AudioOutputHost
import com.mindtravel.deepseekassistant.core.host.PermissionHost
import com.mindtravel.deepseekassistant.core.events.EventSink

class TtsController(
    private val permissionHost: PermissionHost,
    private val audioOutputHost: AudioOutputHost,
    private val settingsRepository: SettingsGateway,
    private val ttsPlayer: TtsPlayer,
    private val ttsCache: TtsCache,
    private val emitter: EventSink
) {

    @Volatile
    private var activeTtsId: String? = null

    private var bargeInDetector: BargeInDetector? = null

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
    }

    private fun maybeStartBargeIn(id: String) {
        val settings = settingsRepository.load()
        if (!settings.bargeInEnabled || !permissionHost.hasMicPermission()) return
        if (!audioOutputHost.hasHeadsetOutput()) {
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

}
