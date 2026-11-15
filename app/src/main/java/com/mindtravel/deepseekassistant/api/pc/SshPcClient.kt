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

    override fun info(profile: PcProfile): PcInfo = withSession(profile) { session ->
        val hostname = run(session, "hostname").trim()
        val user = run(session, "whoami").trim()
        var os = run(session, "ver").trim()
        if (os.isBlank() || os.startsWith("错误")) os = run(session, "uname -a").trim()
        PcInfo(
            hostname = hostname,
            os = os,
            user = user,
            time = LocalDateTime.now().toString(),
            hostKey = hostKeys.currentFingerprint()
        )
    }

    override fun exec(profile: PcProfile, command: String): String {
        if (command.isBlank()) throw ApiException("命令为空")
        return withSession(profile) { session -> run(session, command) }
    }

    override fun publicKey(): String = keyStore.publicKey()

    private fun <T> withSession(profile: PcProfile, block: (Session) -> T): T {
        if (profile.host.isBlank()) throw ApiException("请先填写电脑主机")
        if (profile.user.isBlank()) throw ApiException("请先填写 SSH 用户")

        val key = keyStore.ensure()
        val jsch = JSch().apply {
            addIdentity("dsassist", key.privateKey, null as ByteArray?, null as ByteArray?)
            hostKeyRepository = hostKeys
        }
        var session: Session? = null
        try {
            session = jsch.getSession(profile.user, profile.host, profile.port)
            session.setConfig(
                "StrictHostKeyChecking",
                if (hostKeys.currentFingerprint().isBlank()) "no" else "yes"
            )
            session.setConfig("PreferredAuthentications", "publickey")
            session.setConfig("ServerAliveInterval", "30")
            session.connect(10_000)

            session.hostKey?.let { hostKey ->
                val raw = Base64.getDecoder().decode(hostKey.key)
                hostKeys.pinIfEmpty(raw)
            }
            return block(session)
        } catch (t: ApiException) {
            throw t
        } catch (t: Throwable) {
            Logs.add("SSH PC error: ${t.javaClass.simpleName}: ${t.message}")
            throw ApiException("连接电脑失败：${t.message ?: t.javaClass.simpleName}", t)
        } finally {
            runCatching { session?.disconnect() }
        }
    }

    private fun run(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
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
