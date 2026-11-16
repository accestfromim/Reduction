package com.mindtravel.reduction.feature.pc.ui

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.hardware.audio.RecordingArchive
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64

class PcController(
    private val settingsRepository: SettingsGateway,
    private val pcGateway: PcGateway,
    private val recordings: RecordingArchive,
    private val emitter: EventSink,
    private val scope: CoroutineScope,
    private val updateManifestUrl: String
) {
    /** Called when the computer reports a newer version. Set by whoever can act on it. */
    @Volatile
    var onUpdateSignal: () -> Unit = {}


    @Volatile
    private var connecting = false

    @Volatile
    private var connectionState = "disconnected"

    private val watchLock = Any()
    private var watch: AutoCloseable? = null
    private var watchStarting = false
    private var logJob: Job? = null
    private var recordingJob: Job? = null

    fun state(): String = connectionState

    private fun setState(next: String) {
        if (connectionState == next) {
            if (next == "connected") startConnectedWork()
            return
        }
        connectionState = next
        when (next) {
            "connected" -> {
                startConnectedWork()
                onUpdateSignal()
            }
            "disconnected" -> stopWatch()
        }
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
        stopWatch()
        setState("disconnected")
        pcGateway.close()
    }

    /** Everything that runs for as long as the computer is reachable. Safe to call again. */
    private fun startConnectedWork() {
        val profile = profile()
        startWatch(profile)
        startLogShipping(profile)
        startRecordingShipping(profile)
    }

    private fun startWatch(profile: PcProfile) {
        if (profile.host.isBlank() || profile.user.isBlank()) return
        synchronized(watchLock) {
            if (watch != null || watchStarting) return
            watchStarting = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                val handle = pcGateway.watchUpdates(profile, updateManifestUrl) { line ->
                    val code = line.removePrefix("UPDATE ").trim()
                    if (line.startsWith("UPDATE ") && code.isNotEmpty()) {
                        Logs.add("PC update signal: $code")
                        onUpdateSignal()
                    }
                }
                synchronized(watchLock) {
                    if (connectionState == "connected") {
                        watch = handle
                    } else {
                        runCatching { handle.close() }
                    }
                }
            } catch (t: Throwable) {
                Logs.add("PC update watch failed: ${t.message}")
            } finally {
                synchronized(watchLock) { watchStarting = false }
            }
        }
    }

    /**
     * While connected, appends the app's log to a file on the computer every few seconds,
     * so what happened on the phone can be read there.
     */
    private fun startLogShipping(profile: PcProfile) {
        if (logJob?.isActive == true) return
        logJob = scope.launch(Dispatchers.IO) {
            var seq = 0L
            while (isActive && connectionState == "connected") {
                val (lines, next) = Logs.since(seq)
                if (lines.isNotEmpty()) {
                    val text = lines.joinToString("\n", postfix = "\n")
                    val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                    val shipped = runCatching {
                        pcGateway.exec(
                            profile,
                            "mkdir -p $LOG_DIR && echo $encoded | base64 -d >> $LOG_DIR/phone.log"
                        )
                    }.isSuccess
                    // Shipping failures are not logged: that would feed the loop.
                    if (shipped) seq = next
                }
                delay(LOG_SHIP_INTERVAL_MS)
            }
        }
    }

    /**
     * While connected, hands every kept recording over to the computer, oldest first. A
     * recording leaves the phone only after the computer holds all of it.
     */
    private fun startRecordingShipping(profile: PcProfile) {
        if (recordingJob?.isActive == true) return
        recordingJob = scope.launch(Dispatchers.IO) {
            while (isActive && connectionState == "connected") {
                for (item in recordings.pending()) {
                    if (!isActive || connectionState != "connected") break
                    val delivered = runCatching {
                        item.note?.let { deliver(profile, it.name, it.readBytes()) }
                        deliver(profile, item.audio.name, item.audio.readBytes())
                    }.getOrDefault(false)
                    if (!delivered) break
                    recordings.remove(item)
                    Logs.add("Recording delivered: ${item.audio.name}")
                }
                delay(RECORDING_SHIP_INTERVAL_MS)
            }
        }
    }

    /** Writes one file on the computer. True once it is there in full under its own name. */
    private fun deliver(profile: PcProfile, name: String, data: ByteArray): Boolean {
        val target = "$RECORDING_DIR/$name"
        val command = "mkdir -p $RECORDING_DIR && cat > $target.part && " +
            "[ \"\$(wc -c < $target.part)\" -eq ${data.size} ] && mv $target.part $target && echo DELIVERED"
        return pcGateway.send(profile, command, data).trim() == "DELIVERED"
    }

    private fun stopWatch() {
        val current = synchronized(watchLock) {
            val active = watch
            watch = null
            active
        }
        runCatching { current?.close() }
    }

    private companion object {
        const val LOG_DIR = "~/reduction-agents/logs"
        const val LOG_SHIP_INTERVAL_MS = 3_000L
        const val RECORDING_DIR = "~/reduction-agents/recordings"
        const val RECORDING_SHIP_INTERVAL_MS = 10_000L
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
