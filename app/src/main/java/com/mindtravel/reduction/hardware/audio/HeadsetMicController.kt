package com.mindtravel.reduction.hardware.audio

import android.content.Context
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.mindtravel.reduction.core.Logs

/**
 * Switches recording to a Bluetooth headset's microphone. The headset has to open its
 * call link for that, which takes a moment: [start] returns only once the link is really
 * up, so that what is recorded afterwards comes from the headset and not from the phone.
 */
class HeadsetMicController(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var active = false

    fun hasBluetoothHeadset(): Boolean {
        val manager = audioManager ?: return false
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) return true
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    fun start(): Boolean {
        val manager = audioManager ?: return false
        if (!hasBluetoothHeadset()) {
            Logs.add("Headset mic unavailable: no Bluetooth SCO input")
            return false
        }
        return try {
            var routed = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = manager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                routed = device != null && manager.setCommunicationDevice(device)
            }
            if (!routed) {
                @Suppress("DEPRECATION")
                manager.startBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = true
            }
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            active = true
            val startedAt = System.currentTimeMillis()
            var linked = linkIsUp()
            while (!linked && System.currentTimeMillis() - startedAt < LINK_LIMIT_MS) {
                Thread.sleep(LINK_POLL_MS)
                linked = linkIsUp()
            }
            val waited = System.currentTimeMillis() - startedAt
            if (linked) {
                Logs.add("Headset mic active, call link up after ${waited}ms")
            } else {
                // Without the link the phone's own microphone would be recorded instead: say so, and use it openly.
                Logs.add("Headset mic: call link did not come up in ${waited}ms, using the phone's microphone")
                stop()
            }
            linked
        } catch (t: Throwable) {
            Logs.add("Headset mic failed: ${t.message}")
            false
        }
    }

    /** Whether the headset's call link is carrying audio, as the system last announced it. */
    private fun linkIsUp(): Boolean {
        @Suppress("DEPRECATION")
        val sticky = context.registerReceiver(null, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        return sticky?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
    }

    fun stop() {
        if (!active) return
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = false
            }
            manager.mode = AudioManager.MODE_NORMAL
        } catch (t: Throwable) {
            Logs.add("Headset mic stop failed: ${t.message}")
        } finally {
            active = false
            Logs.add("Headset mic stopped")
        }
    }

    private companion object {
        const val LINK_LIMIT_MS = 2_500L
        const val LINK_POLL_MS = 50L
    }
}
