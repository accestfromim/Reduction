package com.mindtravel.deepseekassistant.ui.controller

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.data.SettingsRepository
import com.mindtravel.deepseekassistant.network.pc.PcClient
import com.mindtravel.deepseekassistant.ui.JsEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PcController(
    private val settingsRepository: SettingsRepository,
    private val pcClient: PcClient,
    private val emitter: JsEmitter,
    private val scope: CoroutineScope
) {

    fun info(requestId: String) {
        val settings = settingsRepository.load()
        scope.launch(Dispatchers.IO) {
            runCatching { pcClient.info(settings.pcUrl, settings.pcToken) }
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
            runCatching { pcClient.exec(settings.pcUrl, settings.pcToken, command) }
                .onSuccess { emitter.emit("onPcExec", requestId, it) }
                .onFailure { emitter.emit("onPcError", requestId, it.message ?: "命令执行失败") }
        }
    }
}
