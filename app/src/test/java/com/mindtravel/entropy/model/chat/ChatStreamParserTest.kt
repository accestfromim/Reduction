package com.mindtravel.entropy.model.chat

import com.mindtravel.entropy.core.ApiException
import com.mindtravel.entropy.feature.chat.model.ChatCallbacks
import com.mindtravel.entropy.feature.chat.model.ChatSession
import com.mindtravel.entropy.feature.chat.model.ToolCallAccumulator
import com.mindtravel.entropy.feature.chat.model.tools.CalculatorTool
import com.mindtravel.entropy.feature.chat.model.tools.ToolRegistry
import com.mindtravel.entropy.model.chat.ChatStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatStreamParserTest {

    private val parser = ChatStreamParser()

    @Test
    fun `normal content is emitted`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}"""
        )
        assertEquals(listOf(ChatStreamEvent.Content("你好")), events)
    }

    @Test
    fun `null content is ignored`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `empty content is ignored`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"content":""}}]}"""
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `tool call delta is typed`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"calculator","arguments":"{\"expression\":\"1+1\"}"}}]}}]}"""
        )
        assertEquals(
            listOf(
                ChatStreamEvent.ToolCallDelta(
                    index = 0,
                    id = "call_1",
                    name = "calculator",
                    arguments = """{"expression":"1+1"}"""
                )
            ),
            events
        )
    }

    @Test
    fun `finish reason is emitted`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        )
        assertEquals(listOf(ChatStreamEvent.Finished("stop")), events)
    }

    @Test
    fun `error chunk is a failure`() {
        val events = parser.parse(
            """data: {"error":{"message":"The model failed to generate a complete response."}}"""
        )
        assertEquals(
            listOf(ChatStreamEvent.Failure("The model failed to generate a complete response.")),
            events
        )
    }

    @Test
    fun `done marker is typed`() {
        assertEquals(listOf(ChatStreamEvent.Done), parser.parse("data: [DONE]"))
    }

    @Test
    fun `non data lines are ignored`() {
        assertTrue(parser.parse("event: message").isEmpty())
        assertTrue(parser.parse("").isEmpty())
    }
}

class ToolCallAccumulatorTest {

    @Test
    fun `arguments are appended across deltas`() {
        val accumulator = ToolCallAccumulator()
        accumulator.apply(ChatStreamEvent.ToolCallDelta(0, "call_1", "calculator", "{\"expr"))
        accumulator.apply(ChatStreamEvent.ToolCallDelta(0, null, null, "ession\":\"1+1\"}"))
        assertEquals(
            listOf(ToolCall("call_1", "calculator", "{\"expression\":\"1+1\"}")),
            accumulator.build()
        )
    }

    @Test
    fun `missing id fails`() {
        val accumulator = ToolCallAccumulator()
        accumulator.apply(ChatStreamEvent.ToolCallDelta(0, null, "calculator", "{}"))
        try {
            accumulator.build()
            fail("expected ApiException")
        } catch (_: ApiException) {
        }
    }
}

class ChatSessionTest {

    private class FakeChatGateway(private val rounds: List<List<ChatStreamEvent>>) : ChatGateway {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0

        override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
            requests += request
            rounds[index++].forEach(onEvent)
        }
    }

    private class RecordingCallbacks : ChatCallbacks {
        val tokens = mutableListOf<String>()
        val tools = mutableListOf<String>()
        var completed: String? = null
        var failed: String? = null

        override fun onToken(text: String) {
            tokens += text
        }

        override fun onToolStarted(name: String, summary: String, arguments: String, announce: Boolean) {
            tools += "$name｜$summary｜$announce"
        }

        override fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String) = Unit

        override fun onCompleted(text: String) {
            completed = text
        }

        override fun onFailed(message: String) {
            failed = message
        }
    }

    @Test
    fun `leading literal null tokens are dropped`() {
        val api = FakeChatGateway(
            listOf(
                listOf(
                    ChatStreamEvent.Content("null"),
                    ChatStreamEvent.Content("nullnull"),
                    ChatStreamEvent.Content("你好"),
                    ChatStreamEvent.Finished("stop")
                )
            )
        )
        val callbacks = RecordingCallbacks()
        ChatSession(api).run(
            ChatRequest(
                apiKey = "test",
                model = "deepseek-flash",
                messages = listOf(ChatMessage(Role.USER, "你好")),
                tools = emptyList()
            ),
            ToolRegistry(emptyList()),
            callbacks
        )
        assertEquals("你好", callbacks.completed)
        assertEquals(listOf("你好"), callbacks.tokens)
    }

    @Test
    fun `tool round appends assistant and tool messages`() {
        val api = FakeChatGateway(
            listOf(
                listOf(
                    ChatStreamEvent.ToolCallDelta(
                        0,
                        "call_1",
                        "calculator",
                        """{"expression":"1+1"}"""
                    ),
                    ChatStreamEvent.Finished("tool_calls")
                ),
                listOf(
                    ChatStreamEvent.Content("答案"),
                    ChatStreamEvent.Content("是 2"),
                    ChatStreamEvent.Finished("stop")
                )
            )
        )
        val callbacks = RecordingCallbacks()
        val session = ChatSession(api)
        session.run(
            ChatRequest(
                apiKey = "test",
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage(Role.SYSTEM, "system"),
                    ChatMessage(Role.USER, "1+1")
                ),
                tools = emptyList()
            ),
            ToolRegistry(listOf(CalculatorTool())),
            callbacks
        )

        assertEquals("答案是 2", callbacks.completed)
        assertEquals(listOf("答案", "是 2"), callbacks.tokens)
        assertEquals(listOf("calculator｜计算：1+1｜false"), callbacks.tools)
        assertEquals(2, api.requests.size)
        assertEquals(4, api.requests[1].messages.size)
        assertEquals(Role.ASSISTANT, api.requests[1].messages[2].role)
        assertEquals("calculator", api.requests[1].messages[2].toolCalls.single().name)
        assertEquals(Role.TOOL, api.requests[1].messages[3].role)
        assertEquals("2", api.requests[1].messages[3].content)
    }
}
