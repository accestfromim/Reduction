package com.mindtravel.entropy.hardware.pc

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class PinnedHostKeyRepository(
    private val fingerprintFile: File
) : HostKeyRepository {

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val pinned = readPinned()
        if (pinned.isBlank()) return HostKeyRepository.OK
        return if (fingerprint(key) == pinned) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String = "dsassist"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    fun readPinned(): String =
        runCatching { fingerprintFile.readText().trim() }.getOrDefault("")

    fun pinIfEmpty(rawKey: ByteArray) {
        if (readPinned().isNotBlank()) return
        fingerprintFile.parentFile?.mkdirs()
        fingerprintFile.writeText(fingerprint(rawKey))
    }

    fun currentFingerprint(): String = readPinned()

    companion object {
        fun fingerprint(rawKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawKey)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
