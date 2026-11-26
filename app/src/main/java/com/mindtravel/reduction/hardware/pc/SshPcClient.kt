package com.mindtravel.reduction.hardware.pc

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.shared.ssh.SshJumpHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.Base64

class SshPcClient(filesDir: File, previousProfile: PcProfile) : PcGateway {

    private val sshDir = File(filesDir, "ssh")
    private val keyStore = SshKeyStore(sshDir)
    private val hostKeys = PinnedHostKeyRepository(sshDir, previousProfile)
    private val lock = Any()

    private class Route(val target: Session, val jump: Session? = null) : AutoCloseable {
        override fun close() {
            try {
                target.disconnect()
            } finally {
                jump?.disconnect()
            }
        }
    }

    private var route: Route? = null
    private var connectedProfile: PcProfile? = null

    private val watchLock = Any()
    private val watches = mutableSetOf<Route>()

    override fun connect(profile: PcProfile) {
        synchronized(lock) { ensureSessionLocked(profile) }
    }

    override fun isConnected(): Boolean = synchronized(lock) {
        route?.target?.isConnected == true
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
                hostKey = PinnedHostKeyRepository.fingerprint(Base64.getDecoder().decode(current.hostKey.key))
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
            channel = fresh.target.openChannel("exec") as ChannelExec
            channel.setCommand(watchCommand(manifestUrl))
            channel.setErrStream(ByteArrayOutputStream())
            input = channel.inputStream
            channel.connect(10_000)
        } catch (t: Throwable) {
            fresh.close()
            throw ApiException("更新监听失败：${t.message ?: t.javaClass.simpleName}", t)
        }
        synchronized(watchLock) { watches.add(fresh) }
        Thread({
            try {
                input.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) onLine(line.trim())
                    }
                }
            } catch (t: Throwable) {
                Logs.add("PC update watcher stopped: ${t.message}")
            } finally {
                closeWatch(fresh)
            }
        }, "reduction-update-watch").apply {
            isDaemon = true
            start()
        }
        return AutoCloseable { closeWatch(fresh) }
    }

    override fun publicKey(): String = keyStore.publicKey()

    override fun close() {
        synchronized(lock) { disconnectLocked() }
        synchronized(watchLock) { watches.toList() }.forEach(::closeWatch)
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
        val existing = route?.target
        if (existing?.isConnected == true && connectedProfile == profile) return existing
        disconnectLocked()
        val fresh = openSession(profile)
        route = fresh
        connectedProfile = profile
        return fresh.target
    }

    private fun openSession(profile: PcProfile): Route {
        if (profile.host.isBlank()) throw ApiException("请先填写电脑主机")
        if (profile.user.isBlank()) throw ApiException("请先填写 SSH 用户")

        val via = SshJumpHost.parse(profile.proxyJump, profile.user)
        val key = keyStore.ensure()
        val jsch = JSch().apply {
            addIdentity("dsassist", key.privateKey, null as ByteArray?, null as ByteArray?)
            hostKeyRepository = hostKeys
        }
        if (via == null) return Route(dial(jsch, profile))
        val jump = try {
            dial(jsch, PcProfile(via.host, via.port, via.user))
        } catch (e: Exception) {
            throw ApiException("跳板机连接失败：${e.message}", e)
        }
        try {
            // JSch opens a direct-tcpip channel from the jump host to the target, including DNS resolution.
            val targetHost = profile.host.removeSurrounding("[", "]")
            val localPort = jump.setPortForwardingL("127.0.0.1", 0, targetHost, profile.port, null, 10_000)
            val target = dial(jsch, profile, "127.0.0.1", localPort)
            // Closing the listener leaves the established channel and its end-to-end SSH session intact.
            jump.delPortForwardingL("127.0.0.1", localPort)
            return Route(target, jump)
        } catch (e: Exception) {
            jump.disconnect()
            throw ApiException("经跳板机连接目标失败：${e.message}", e)
        }
    }

    private fun dial(jsch: JSch, endpoint: PcProfile, address: String = endpoint.host, port: Int = endpoint.port): Session {
        val hostId = PinnedHostKeyRepository.hostId(endpoint.host, endpoint.port)
        val fresh = jsch.getSession(endpoint.user, address, port)
        fresh.hostKeyAlias = hostId
        fresh.setConfig("StrictHostKeyChecking", "yes")
        fresh.setConfig("PreferredAuthentications", "publickey")
        fresh.setConfig("ServerAliveInterval", "30")
        fresh.setConfig("ServerAliveCountMax", "3")
        try {
            fresh.connect(10_000)
            val raw = Base64.getDecoder().decode(fresh.hostKey.key)
            hostKeys.remember(hostId, PinnedHostKeyRepository.fingerprint(raw))
            return fresh
        } catch (e: Exception) {
            fresh.disconnect()
            throw e
        }
    }

    private fun disconnectLocked() {
        route?.close()
        route = null
        connectedProfile = null
    }

    private fun closeWatch(watch: Route) {
        if (synchronized(watchLock) { watches.remove(watch) }) watch.close()
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
