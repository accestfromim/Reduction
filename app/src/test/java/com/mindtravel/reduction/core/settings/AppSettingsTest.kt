package com.mindtravel.reduction.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun aProjectPathFromAnEarlierNameFollowsTheDirectory() {
        for (former in listOf("/home/user/DSAssist-iterate", "/home/user/dsassist-iterate")) {
            assertEquals("/home/user/Reduction-iterate", AppSettings.fromJson("""{"pcProject":"$former"}""").pcProject)
        }
    }

    @Test
    fun anyOtherProjectPathIsKeptAsWritten() {
        val settings = AppSettings.fromJson("""{"pcProject":"/home/user/other-Project"}""")
        assertEquals("/home/user/other-Project", settings.pcProject)
    }

    @Test
    fun aMissingProjectFallsBackToTheDefault() {
        assertEquals(AppSettings.DEFAULT_PROJECT, AppSettings.fromJson("{}").pcProject)
    }
}
