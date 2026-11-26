package com.mindtravel.reduction.hardware.pc

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

class PinnedHostKeyRepository(private val directory: File, previousProfile: PcProfile) : HostKeyRepository {
    private val file = File(directory, "known_hosts.properties")
    private val pins = Properties()

    init {
        if (file.exists()) {
            file.inputStream().use(pins::load)
        } else {
            val legacy = File(directory, "host_key.txt")
            if (legacy.isFile && previousProfile.host.isNotBlank()) {
                val pin = legacy.readText().trim()
                if (pin.isNotEmpty()) remember(hostId(previousProfile.host, previousProfile.port), pin)
            }
        }
    }

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null || host == null) return HostKeyRepository.NOT_INCLUDED
        val pinned = pins.getProperty(host) ?: return HostKeyRepository.OK
        return if (fingerprint(key) == pinned) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "dsassist"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    @Synchronized
    fun remember(host: String, fingerprint: String) {
        val pinned = pins.getProperty(host)
        if (pinned != null) {
            check(pinned == fingerprint) { "SSH 主机密钥已变化：$host" }
            return
        }
        directory.mkdirs()
        val next = Properties().apply {
            putAll(pins)
            setProperty(host, fingerprint)
        }
        val temporary = File.createTempFile("hosts-", ".tmp", directory)
        try {
            temporary.outputStream().use { next.store(it, null) }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            pins.setProperty(host, fingerprint)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        fun hostId(host: String, port: Int): String = "[${host.removeSurrounding("[", "]").lowercase()}]:$port"

        fun fingerprint(rawKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawKey)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
