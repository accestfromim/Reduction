package com.mindtravel.reduction.feature.chat.model

/**
 * Decides when a low battery is worth a sentence. Each device is mentioned once as it
 * runs low, and again only after it has been charged and has run low once more.
 */
class BatteryWatch(
    private val lowAt: Int = 20,
    private val recoveredAt: Int = 30
) {
    class Device(val name: String, val percent: Int, val charging: Boolean)

    private val mentioned = HashSet<String>()

    /** One line per device that has just run low; empty when there is nothing new to say. */
    @Synchronized
    fun notices(devices: List<Device>): List<String> {
        val lines = ArrayList<String>()
        for (device in devices) {
            if (device.charging || device.percent >= recoveredAt) {
                mentioned.remove(device.name)
            } else if (device.percent <= lowAt && mentioned.add(device.name)) {
                lines += "${device.name}电量 ${device.percent}%"
            }
        }
        // A device that is gone is forgotten: the next time it shows up low, it is news again.
        mentioned.retainAll(devices.map { it.name }.toSet())
        return lines
    }
}
