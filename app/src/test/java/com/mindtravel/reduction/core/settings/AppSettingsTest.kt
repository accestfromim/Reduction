package com.mindtravel.reduction.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {

    @Test
    fun theLowercaseDefaultProjectIsCorrected() {
        val settings = AppSettings.fromJson("""{"pcProject":"/home/user/dsassist-iterate"}""")
        assertEquals("/home/user/DSAssist-iterate", settings.pcProject)
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
