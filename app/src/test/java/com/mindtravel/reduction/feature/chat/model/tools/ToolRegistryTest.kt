package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.hardware.pc.PcProfile
import com.mindtravel.reduction.model.chat.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private class Echo : ChatTool {
        override val spec = ToolSpec("echo", "repeats", "text", "what to repeat")
        override fun execute(argumentsJson: String) = argumentsJson
        override fun describe(argumentsJson: String) = "重复一句话"
    }

    private fun agentTool() = RunAgentTool(
        harness = AgentHarness(LocalShell(), PcProfile("host", 22, "user"), "key", "model") { "" },
        runs = AgentRuns(),
        project = "/p", agentsRoot = "/a", allowWrite = false, directories = emptyList()
    )

    @Test
    fun everyToolAsksForASummaryFirst() {
        val spec = ToolRegistry(listOf(Echo())).specs().single()
        assertEquals(listOf("summary", "text"), spec.parameters.map { it.name })
        assertTrue(spec.parameters.first().required)
    }

    @Test
    fun theSummaryIsTheAssistantsOwnLine() {
        val tools = ToolRegistry(listOf(Echo()))
        assertEquals("查一下明天的天气", tools.summary("echo", """{"summary":" 查一下明天的天气 \n第二行","text":"x"}"""))
    }

    @Test
    fun aMissingSummaryFallsBackToTheToolsOwnWords() {
        val tools = ToolRegistry(listOf(Echo()))
        assertEquals("重复一句话", tools.summary("echo", """{"text":"x"}"""))
        assertEquals("重复一句话", tools.summary("echo", """{"summary":"  ","text":"x"}"""))
        assertEquals("重复一句话", tools.summary("echo", "not json"))
        assertEquals("nobody", tools.summary("nobody", "{}"))
    }

    @Test
    fun aLongSummaryIsCutToOneShortLine() {
        val tools = ToolRegistry(listOf(Echo()))
        val line = tools.summary("echo", """{"summary":"${"很".repeat(90)}"}""")
        assertEquals(41, line.length)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun onlyHandingATaskToASubAgentIsSaidAloud() {
        val tools = ToolRegistry(listOf(Echo(), agentTool()))
        assertTrue(tools.announces("run_agent", """{"action":"start","task":"查资料"}"""))
        assertFalse(tools.announces("run_agent", """{"action":"status","id":"t1"}"""))
        assertFalse(tools.announces("run_agent", """{"action":"stop","id":"t1"}"""))
        assertFalse(tools.announces("echo", """{"text":"x"}"""))
        assertFalse(tools.announces("run_agent", "not json"))
    }

    @Test
    fun aDispatchWithoutASummaryIsDescribedByItsTask() {
        val tools = ToolRegistry(listOf(agentTool()))
        assertEquals("派发：查向量检索的综述", tools.summary("run_agent", """{"action":"start","task":"查向量检索的综述\n约束：只读"}"""))
        assertEquals("查询 sub agent 进度", tools.summary("run_agent", """{"action":"status","id":"t1"}"""))
    }
}
