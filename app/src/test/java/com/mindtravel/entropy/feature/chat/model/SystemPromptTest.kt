package com.mindtravel.entropy.feature.chat.model

import com.mindtravel.entropy.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {

    private fun reference(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("system-prompt-$name.txt")!!.bufferedReader().readText()

    private fun settings(web: Boolean, calc: Boolean, read: Boolean, write: Boolean, project: String) = AppSettings(
        webSearchEnabled = web, calculatorEnabled = calc, opencodeEnabled = read, opencodeWriteEnabled = write, pcProject = project
    )

    /** The reference texts were produced by the wording this prompt was ported from. */
    @Test
    fun theTextIsExactlyTheApprovedWording() {
        assertEquals(
            reference("all"),
            SystemPrompt.build(
                settings(true, true, true, true, "/home/u/p"), "2026-01-02",
                listOf("- 备忘：零碎待办（12 字）", "- 想法：（没有写用途）（3 字）")
            )
        )
        assertEquals(reference("readonly"), SystemPrompt.build(settings(false, true, true, false, "/home/u/p"), "2026-01-02", emptyList()))
        assertEquals(reference("bare"), SystemPrompt.build(settings(false, false, false, false, "/x"), "2026-01-02", emptyList()))
    }

    @Test
    fun toolsThatAreOffAreNotMentioned() {
        val bare = SystemPrompt.build(settings(false, false, false, false, "/x"), "2026-01-02", emptyList())
        assertFalse(bare.contains("web_search") || bare.contains("calculator") || bare.contains("run_agent"))
        assertTrue(bare.contains("patch_cards") && bare.contains("（还没有卡片）"))
    }
}
