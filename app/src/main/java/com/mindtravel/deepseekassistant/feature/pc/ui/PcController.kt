package com.mindtravel.deepseekassistant.feature.pc.ui

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PcController(
    private val settingsRepository: SettingsGateway,
    private val pcGateway: PcGateway,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {

    @Volatile
    private var connecting = false

    @Volatile
    private var connectionState = "disconnected"

    fun state(): String = connectionState

    private fun setState(next: String) {
        if (connectionState == next) return
        connectionState = next
        emitter.emit("onPcConnection", next)
    }

    fun connect() {
        val profile = profile()
        if (profile.host.isBlank() || profile.user.isBlank()) return
        if (pcGateway.isConnected()) {
            setState("connected")
            return
        }
        if (connecting) return
        connecting = true
        setState("connecting")
        scope.launch(Dispatchers.IO) {
            try {
                var last: Throwable? = null
                repeat(3) { attempt ->
                    val result = runCatching { pcGateway.connect(profile) }
                    if (result.isSuccess) {
                        Logs.add("PC SSH auto-connected")
                        setState("connected")
                        return@launch
                    }
                    last = result.exceptionOrNull()
                    if (attempt < 2) kotlinx.coroutines.delay(2_000L)
                }
                Logs.add("PC SSH auto-connect failed: ${last?.message}")
                setState("disconnected")
            } finally {
                connecting = false
            }
        }
    }

    fun isConnected(): Boolean = pcGateway.isConnected()

    fun info(requestId: String) {
        val profile = profile()
        scope.launch(Dispatchers.IO) {
            runCatching { pcGateway.info(profile) }
                .onSuccess {
                    Logs.add("PC SSH info ok")
                    setState("connected")
                    emitter.emit("onPcInfo", requestId, it.toJson().toString())
                }
                .onFailure {
                    Logs.add("PC SSH info failed: ${it.message}")
                    setState("disconnected")
                    emitter.emit("onPcError", requestId, it.message ?: "连接电脑失败")
                }
        }
    }

    fun exec(requestId: String, command: String) {
        if (command.isBlank()) {
            emitter.emit("onPcError", requestId, "命令为空")
            return
        }
        val profile = profile()
        Logs.add("PC SSH exec length=${command.length}")
        emitter.emit("onPcStatus", requestId, "执行中…")
        scope.launch(Dispatchers.IO) {
            runCatching { pcGateway.exec(profile, command) }
                .onSuccess {
                    setState("connected")
                    emitter.emit("onPcExec", requestId, it)
                }
                .onFailure {
                    setState("disconnected")
                    emitter.emit("onPcError", requestId, it.message ?: "命令执行失败")
                }
        }
    }

    fun publicKey(): String = pcGateway.publicKey()

    fun close() {
        setState("disconnected")
        pcGateway.close()
    }

    private fun profile(): PcProfile {
        val settings = settingsRepository.load()
        return PcProfile(
            host = settings.pcHost,
            port = settings.pcPort,
            user = settings.pcUser
        )
    }
}
