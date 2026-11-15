package com.mindtravel.entropy.hardware.pc

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.mindtravel.entropy.core.ApiException
import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.hardware.pc.PcGateway
import com.mindtravel.entropy.hardware.pc.PcInfo
import com.mindtravel.entropy.hardware.pc.PcProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.Base64

class SshPcClient(context: Context) : PcGateway {

    private val keyStore = SshKeyStore(context)
    private val hostKeyFile = File(context.filesDir, "ssh/host_key.txt")
    private val hostKeys = PinnedHostKeyRepository(hostKeyFile)
    private val lock = Any()

    private var session: Session? = null
    private var connectedProfile: PcProfile? = null

    private val watchLock = Any()
    private var watchSession: Session? = null
    private var watchChannel: ChannelExec? = null
    private var watchThread: Thread? = null

    override fun connect(profile: PcProfile) {
        synchronized(lock) { ensureSessionLocked(profile) }
    }

    override fun isConnected(): Boolean = synchronized(lock) {
        session?.isConnected == true
    }

    override fun info(profile: PcProfile): PcInfo = synchronized(lock) {
        withSessionLocked(profile) { current ->
            val hostname = run(current, "hostname").trim()
            val user = run(current, "whoami").trim()
            var os = run(current, "ver").trim()
            if (os.isBlank() || os.startsWith("错误")) os = run(current, "uname -a").trim()
            PcInfo(
                hostname = hostname,
                os = os,
                user = user,
                time = LocalDateTime.now().toString(),
                hostKey = hostKeys.currentFingerprint()
            )
        }
    }

    override fun exec(profile: PcProfile, command: String): String {
        if (command.isBlank()) throw ApiException("命令为空")
        return synchronized(lock) {
            withSessionLocked(profile) { current -> run(current, command) }
        }
    }

    override fun send(profile: PcProfile, command: String, input: ByteArray): String {
        if (command.isBlank()) throw ApiException("命令为空")
        return synchronized(lock) {
            withSessionLocked(profile) { current -> run(current, command, input) }
        }
    }

    override fun watchUpdates(
        profile: PcProfile,
        manifestUrl: String,
        onLine: (String) -> Unit
    ): AutoCloseable {
        if (manifestUrl.isBlank()) throw ApiException("更新地址为空")
        val fresh = openSession(profile)
        val channel: ChannelExec
        val input: java.io.InputStream
        try {
            channel = fresh.openChannel("exec") as ChannelExec
            channel.setCommand(watchCommand(manifestUrl))
            channel.setErrStream(ByteArrayOutputStream())
            input = channel.inputStream
            channel.connect(10_000)
        } catch (t: Throwable) {
            runCatching { fresh.disconnect() }
            throw ApiException("更新监听失败：${t.message ?: t.javaClass.simpleName}", t)
        }
        synchronized(watchLock) {
            stopWatchLocked()
            watchSession = fresh
            watchChannel = channel
        }
        val thread = Thread({
            try {
                input.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) onLine(line.trim())
                    }
                }
            } catch (t: Throwable) {
                Logs.add("PC update watcher stopped: ${t.message}")
            }
        }, "entropy-update-watch").apply {
            isDaemon = true
            start()
        }
        synchronized(watchLock) { watchThread = thread }
        return AutoCloseable { synchronized(watchLock) { stopWatchLocked() } }
    }

    override fun publicKey(): String = keyStore.publicKey()

    override fun close() {
        synchronized(lock) { disconnectLocked() }
        stopWatch()
    }

    private fun <T> withSessionLocked(profile: PcProfile, block: (Session) -> T): T {
        return try {
            block(ensureSessionLocked(profile))
        } catch (t: ApiException) {
            disconnectLocked()
            throw t
        } catch (t: Throwable) {
            disconnectLocked()
            Logs.add("SSH PC error: ${t.javaClass.simpleName}: ${t.message}")
            throw ApiException("连接电脑失败：${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    private fun ensureSessionLocked(profile: PcProfile): Session {
        val existing = session
        if (existing?.isConnected == true && connectedProfile == profile) return existing
        disconnectLocked()
        val fresh = openSession(profile)
        session = fresh
        connectedProfile = profile
        return fresh
    }

    private fun openSession(profile: PcProfile): Session {
        if (profile.host.isBlank()) throw ApiException("请先填写电脑主机")
        if (profile.user.isBlank()) throw ApiException("请先填写 SSH 用户")

        val key = keyStore.ensure()
        val jsch = JSch().apply {
            addIdentity("dsassist", key.privateKey, null as ByteArray?, null as ByteArray?)
            hostKeyRepository = hostKeys
        }
        val fresh = jsch.getSession(profile.user, profile.host, profile.port)
        fresh.setConfig(
            "StrictHostKeyChecking",
            if (hostKeys.currentFingerprint().isBlank()) "no" else "yes"
        )
        fresh.setConfig("PreferredAuthentications", "publickey")
        fresh.setConfig("ServerAliveInterval", "30")
        fresh.setConfig("ServerAliveCountMax", "3")
        fresh.connect(10_000)

        fresh.hostKey?.let { hostKey ->
            val raw = Base64.getDecoder().decode(hostKey.key)
            hostKeys.pinIfEmpty(raw)
        }
        return fresh
    }

    private fun disconnectLocked() {
        runCatching { session?.disconnect() }
        session = null
        connectedProfile = null
    }

    private fun stopWatch() {
        synchronized(watchLock) { stopWatchLocked() }
    }

    private fun stopWatchLocked() {
        val channel = watchChannel
        val watch = watchSession
        val thread = watchThread
        watchChannel = null
        watchSession = null
        watchThread = null
        runCatching { channel?.disconnect() }
        runCatching { watch?.disconnect() }
        thread?.interrupt()
    }

    private fun watchCommand(manifestUrl: String): String {
        val script = """
last=""
while :; do
  n=${'$'}(curl -fsS --max-time 10 "$manifestUrl" 2>/dev/null | sed -n 's/.*"versionCode"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n1)
  if [ -n "${'$'}n" ] && [ "${'$'}n" != "${'$'}last" ]; then
    if [ -n "${'$'}last" ]; then echo "UPDATE ${'$'}n"; fi
    last="${'$'}n"
  fi
  sleep 60
done
""".trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        return "echo $encoded | base64 -d | sh"
    }

    private fun run(current: Session, command: String, stdin: ByteArray? = null): String {
        val channel = current.openChannel("exec") as ChannelExec
        val error = ByteArrayOutputStream()
        channel.setErrStream(error)
        channel.setCommand(command)
        if (stdin != null) channel.setInputStream(ByteArrayInputStream(stdin))
        val input = channel.inputStream
        channel.connect(10_000)
        return try {
            val output = input.bufferedReader().use { it.readText() }
            val errorText = error.toString(Charsets.UTF_8.name())
            when {
                output.isNotBlank() -> output
                errorText.isNotBlank() -> "错误：$errorText"
                else -> ""
            }
        } finally {
            runCatching { channel.disconnect() }
        }
    }
}
