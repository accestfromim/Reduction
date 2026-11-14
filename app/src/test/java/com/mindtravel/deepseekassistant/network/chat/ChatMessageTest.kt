package com.mindtravel.deepseekassistant.network.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {

    @Test
    fun `assistant tool call keeps null content`() {
        val message = ChatMessage(
            role = Role.ASSISTANT,
            content = null,
            toolCalls = listOf(ToolCall("call_1", "calculator", "{}"))
        )
        val json = message.toJson()
        assertTrue(json.isNull("content"))
        assertEquals("calculator", json.getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function").getString("name"))
    }

    @Test
    fun `tool message serializes tool call id`() {
        val json = ChatMessage(
            role = Role.TOOL,
            content = "2",
            toolCallId = "call_1"
        ).toJson()
        assertEquals("tool", json.getString("role"))
        assertEquals("call_1", json.getString("tool_call_id"))
        assertEquals("2", json.getString("content"))
    }

    @Test
    fun `client history rejects tool messages`() {
        val raw = """[{"role":"tool","content":"x","tool_call_id":"call_1"}]"""
        try {
            ChatMessage.fromJsonArray(raw)
            throw AssertionError("expected failure")
        } catch (_: Exception) {
        }
    }
}
