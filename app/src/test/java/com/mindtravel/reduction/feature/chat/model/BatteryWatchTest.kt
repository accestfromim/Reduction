package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.feature.chat.model.BatteryWatch.Device
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryWatchTest {

    private val none = emptyList<String>()

    @Test
    fun aDeviceRunningLowIsMentionedOnce() {
        val watch = BatteryWatch()
        assertEquals(none, watch.notices(listOf(Device("手机", 45, false))))
        assertEquals(listOf("手机电量 20%"), watch.notices(listOf(Device("手机", 20, false))))
        assertEquals(none, watch.notices(listOf(Device("手机", 17, false))))
    }

    @Test
    fun aChargingDeviceIsNotMentioned() {
        assertEquals(none, BatteryWatch().notices(listOf(Device("手机", 8, true))))
    }

    @Test
    fun afterBeingChargedItIsMentionedAgainTheNextTimeItRunsLow() {
        val watch = BatteryWatch()
        watch.notices(listOf(Device("手机", 15, false)))
        assertEquals(none, watch.notices(listOf(Device("手机", 24, false))))
        assertEquals(none, watch.notices(listOf(Device("手机", 15, true))))
        assertEquals(listOf("手机电量 12%"), watch.notices(listOf(Device("手机", 12, false))))
    }

    @Test
    fun eachDeviceIsJudgedOnItsOwn() {
        val watch = BatteryWatch()
        assertEquals(listOf("Pill电量 10%"), watch.notices(listOf(Device("手机", 80, false), Device("Pill", 10, false))))
        assertEquals(listOf("手机电量 19%"), watch.notices(listOf(Device("手机", 19, false), Device("Pill", 9, false))))
    }

    @Test
    fun aHeadsetThatComesBackLowIsNewsAgain() {
        val watch = BatteryWatch()
        watch.notices(listOf(Device("Pill", 10, false)))
        watch.notices(emptyList())
        assertEquals(listOf("Pill电量 9%"), watch.notices(listOf(Device("Pill", 9, false))))
    }
}
