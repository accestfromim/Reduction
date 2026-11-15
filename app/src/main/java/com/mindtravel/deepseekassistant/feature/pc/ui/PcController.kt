package com.mindtravel.deepseekassistant.feature.pc.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.core.events.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PcController(
    private val settingsRepository: SettingsGateway,
    private val pcGateway: PcGateway,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {

    fun info(requestId: String) {
        val settings = settingsRepository.load()
        scope.launch(Dispatchers.IO) {
            runCatching { pcGateway.info(settings.pcUrl, settings.pcToken) }
                .onSuccess {
                    Logs.add("PC info ok")
                    emitter.emit("onPcInfo", requestId, it.toString())
                }
                .onFailure {
                    Logs.add("PC info failed: ${it.message}")
                    emitter.emit("onPcError", requestId, it.message ?: "连接电脑失败")
                }
        }
    }

    fun exec(requestId: String, command: String) {
        if (command.isBlank()) {
            emitter.emit("onPcError", requestId, "命令为空")
            return
        }
        val settings = settingsRepository.load()
        Logs.add("PC exec length=${command.length}")
        emitter.emit("onPcStatus", requestId, "执行中…")
        scope.launch(Dispatchers.IO) {
            runCatching { pcGateway.exec(settings.pcUrl, settings.pcToken, command) }
                .onSuccess { emitter.emit("onPcExec", requestId, it) }
                .onFailure { emitter.emit("onPcError", requestId, it.message ?: "命令执行失败") }
        }
    }
}
