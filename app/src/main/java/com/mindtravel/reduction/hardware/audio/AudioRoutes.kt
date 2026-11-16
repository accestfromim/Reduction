package com.mindtravel.reduction.hardware.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Where sound is going right now. */
object AudioRoutes {

    /** True when a headset or headphones are connected, wired or wireless. */
    fun hasHeadsetOutput(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> true
                else -> false
            }
        }
    }

    /** For the log: the output device and the media volume. */
    fun describe(context: Context): String {
        val manager = context.getSystemService(AudioManager::class.java) ?: return "unknown"
        val volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (if (hasHeadsetOutput(context)) "headset" else "speaker") + ", media volume $volume/$max"
    }
}
