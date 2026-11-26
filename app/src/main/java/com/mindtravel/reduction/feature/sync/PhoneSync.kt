package com.mindtravel.reduction.feature.sync

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The phone's half of the exchange with the computer. Whenever the phone is connected to
 * its computer it brings the two up to date: soon after anything changes here, and every
 * [EVERY_MS] besides, which is how a change made on the computer arrives. It reaches the
 * computer's service through the connection the phone already has, and does nothing when
 * there is no connection or no service running.
 */
class PhoneSync(
    private val sync: DataSync,
    private val pcGateway: PcGateway,
    private val settings: SettingsGateway,
    private val scope: CoroutineScope
) {
    private var pending: Job? = null

    @Volatile
    private var merging = false

    /** Something changed here: the computer is told shortly, once things have settled. */
    fun changed() {
        // What the exchange itself writes is not news for the computer.
        if (merging) return
        scope.launch {
            pending?.cancel()
            pending = scope.launch(Dispatchers.IO) {
                delay(SETTLE_MS)
                exchange()
            }
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                exchange()
                delay(EVERY_MS)
            }
        }
    }

    @Synchronized
    private fun exchange() {
        if (!pcGateway.isConnected()) return
        val saved = settings.load()
        val profile = PcProfile(saved)
        try {
            val answer = post(profile, sync.export()) ?: return
            merging = true
            val changed = try { sync.merge(answer) } finally { merging = false }
            // The pictures and sound the computer lacks go in a second exchange.
            val files = sync.filesFor(answer)
            if (files.length() > 0) post(profile, sync.export().put("files", files))
            if (changed) Logs.add("Sync: took in changes from the computer")
        } catch (t: Throwable) {
            Logs.add("Sync failed: ${t.message}")
        }
    }

    /** One exchange; null when the computer's service did not answer. */
    private fun post(profile: PcProfile, body: JSONObject): JSONObject? {
        val command = "curl -s -m 60 --noproxy '*' -H 'X-Reduction: 1' -H 'Content-Type: application/json' " +
            "--data-binary @- ${DataSync.HUB_URL}"
        val output = pcGateway.send(profile, command, body.toString().toByteArray(Charsets.UTF_8))
        return runCatching { JSONObject(output) }.getOrNull()?.takeIf { it.optInt("version") == DataSync.VERSION }
    }

    private companion object {
        const val SETTLE_MS = 2_500L
        const val EVERY_MS = 60_000L
    }
}
