package com.mindtravel.deepseekassistant.platform.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.mindtravel.deepseekassistant.core.Logs

class HeadsetMicController(context: Context) {

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
            Logs.add("Headset mic active")
            true
        } catch (t: Throwable) {
            Logs.add("Headset mic failed: ${t.message}")
            false
        }
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
}
