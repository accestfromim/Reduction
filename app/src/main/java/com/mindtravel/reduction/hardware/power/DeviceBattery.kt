package com.mindtravel.reduction.hardware.power

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * How much charge the phone and the connected Bluetooth headsets have left.
 *
 * Android has no public way to ask a headset for its charge, but it announces the figure
 * whenever the headset reports it, and keeps the last one. Both are read here; a headset
 * that never reports one is simply not listed.
 */
class DeviceBattery(private val context: Context) {

    /** [percent] is 0–100. [charging] is known for the phone only. */
    data class Reading(val name: String, val percent: Int, val charging: Boolean)

    private val announced = ConcurrentHashMap<String, Reading>()

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.device() ?: return
            val address = runCatching { device.address }.getOrNull() ?: return
            when (intent.action) {
                ACTION_LEVEL -> {
                    val level = intent.getIntExtra(EXTRA_LEVEL, -1)
                    if (level in 0..100) announced[address] = Reading(nameOf(device), level, charging = false)
                    else announced.remove(address)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> announced.remove(address)
            }
        }
    }

    init {
        val filter = IntentFilter(ACTION_LEVEL).apply { addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED) }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    fun readings(): List<Reading> = listOfNotNull(phone()) + headsets()

    private fun phone(): Reading? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return Reading(PHONE, level * 100 / scale, charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    /** Headsets connected now: what the system remembers of each, else what was last announced. */
    @SuppressLint("MissingPermission")
    private fun headsets(): List<Reading> {
        val found = LinkedHashMap<String, Reading>()
        runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            for (device in adapter?.bondedDevices.orEmpty()) {
                // Neither question has a public method; both have been answerable this way since Android 8.
                val connected = BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean
                if (connected != true) continue
                val level = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as? Int
                if (level != null && level in 0..100) found[device.address] = Reading(nameOf(device), level, charging = false)
            }
        }
        for ((address, reading) in announced) found.putIfAbsent(address, reading)
        return found.values.toList()
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: HEADSET

    @Suppress("DEPRECATION")
    private fun Intent.device(): BluetoothDevice? = getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    companion object {
        const val PHONE = "手机"
        private const val HEADSET = "蓝牙耳机"
        private const val ACTION_LEVEL = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}
