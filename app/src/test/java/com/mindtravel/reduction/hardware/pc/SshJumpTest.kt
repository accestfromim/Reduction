package com.mindtravel.reduction.hardware.pc

import com.jcraft.jsch.HostKeyRepository
import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.shared.ssh.SshJumpHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshJumpTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun optionalHopRoundTripsWithoutChangingOlderProfiles() {
        val saved = AppSettings.fromJson("""{"pcHost":"target","pcPort":2200,"pcUser":"worker"}""")
        assertNull(SshJumpHost.parse(PcProfile(saved).proxyJump, saved.pcUser))
        val routed = saved.copy(pcProxyJump = "gateway")
        assertEquals(routed, AppSettings.fromJson(routed.toJson().toString()))
        assertEquals(SshJumpHost("gateway", 22, "worker"), SshJumpHost.parse(PcProfile(routed).proxyJump, saved.pcUser))
        assertEquals(SshJumpHost("2001:db8::2", 2222, "ops"), SshJumpHost.parse(" ops@[2001:db8::2]:2222 ", "worker"))
        assertEquals(SshJumpHost("bastion", 2222, "worker"), SshJumpHost.parse("bastion:2222", "worker"))
    }

    @Test fun invalidOrMultipleHopsNeverBecomeADirectConnection() {
        for (spec in listOf("one,two", "host:", "host:zero", "u@host:0", "host:65536", "@host", "user@", "host/abc")) {
            assertThrows(spec, IllegalArgumentException::class.java) { SshJumpHost.parse(spec, "worker") }
        }
    }

    @Test fun legacyTargetAndJumpKeepSeparatePersistentPins() {
        val directory = temp.newFolder()
        val target = PcProfile("example", 22, "worker")
        val targetId = PinnedHostKeyRepository.hostId(target.host, target.port)
        val jumpId = PinnedHostKeyRepository.hostId(target.host, 2222)
        val targetKey = "target".toByteArray()
        val jumpKey = "jump".toByteArray()
        directory.resolve("host_key.txt").writeText(PinnedHostKeyRepository.fingerprint(targetKey))
        val pins = PinnedHostKeyRepository(directory, target)
        assertEquals(HostKeyRepository.CHANGED, pins.check(targetId, jumpKey))
        pins.remember(jumpId, PinnedHostKeyRepository.fingerprint(jumpKey))
        val reopened = PinnedHostKeyRepository(directory, PcProfile("other", 22, "worker"))
        assertEquals(HostKeyRepository.OK, reopened.check(targetId, targetKey))
        assertEquals(HostKeyRepository.OK, reopened.check(jumpId, jumpKey))
        assertEquals(HostKeyRepository.CHANGED, reopened.check(jumpId, targetKey))
        assertThrows(IllegalStateException::class.java) { reopened.remember(targetId, PinnedHostKeyRepository.fingerprint(jumpKey)) }
    }
}
