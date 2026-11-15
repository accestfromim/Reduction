package com.mindtravel.deepseekassistant.api.pc

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcInfo
import com.mindtravel.deepseekassistant.feature.pc.model.PcProfile
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

    override fun connect(profile: PcProfile) {
        synchronized(lock) { ensureSessionLocked(profile) }
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

    override fun publicKey(): String = keyStore.publicKey()

    override fun close() {
        synchronized(lock) { disconnectLocked() }
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
        if (profile.host.isBlank()) throw ApiException("请先填写电脑主机")
        if (profile.user.isBlank()) throw ApiException("请先填写 SSH 用户")

        val existing = session
        if (existing?.isConnected == true && connectedProfile == profile) return existing
        disconnectLocked()

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
        session = fresh
        connectedProfile = profile
        return fresh
    }

    private fun disconnectLocked() {
        runCatching { session?.disconnect() }
        session = null
        connectedProfile = null
    }

    private fun run(current: Session, command: String): String {
        val channel = current.openChannel("exec") as ChannelExec
        val error = ByteArrayOutputStream()
        channel.setErrStream(error)
        channel.setCommand(command)
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
