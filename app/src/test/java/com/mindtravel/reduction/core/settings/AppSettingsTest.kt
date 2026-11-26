package com.mindtravel.reduction.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test fun mimocodeSelectionSurvivesSavingAndUnknownEnginesUseTheDefault() {
        val saved = AppSettings(agentEngine = "mimocode").toJson().toString()
        assertEquals("mimocode", AppSettings.fromJson(saved).agentEngine)
        assertEquals("opencode", AppSettings.fromJson("""{"agentEngine":"unknown"}""").agentEngine)
    }


    @Test
    fun aProjectPathFromAnEarlierNameFollowsTheDirectory() {
        for (former in listOf("/home/someone/DSAssist-iterate", "/home/someone/dsassist-iterate", "~/DSAssist-iterate")) {
            assertEquals("~/Reduction-iterate", AppSettings.fromJson("""{"pcProject":"$former"}""").pcProject)
        }
    }

    @Test
    fun anyOtherProjectPathIsKeptAsWritten() {
        val settings = AppSettings.fromJson("""{"pcProject":"/home/someone/other-Project"}""")
        assertEquals("/home/someone/other-Project", settings.pcProject)
    }

    @Test
    fun aMissingProjectFallsBackToTheDefault() {
        assertEquals(AppSettings.DEFAULT_PROJECT, AppSettings.fromJson("{}").pcProject)
    }
}
