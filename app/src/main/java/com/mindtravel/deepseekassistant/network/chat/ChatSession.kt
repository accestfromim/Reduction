package com.mindtravel.deepseekassistant.network.chat

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs

class ChatSession(
    private val api: ChatApi,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS
) {

    companion object {
        private const val DEFAULT_MAX_TOOL_ROUNDS = 4
    }

    fun run(
        request: ChatRequest,
        tools: ToolRegistry,
        callbacks: ChatCallbacks
    ) {
        val messages = request.messages.toMutableList()

        repeat(maxToolRounds) {
            val content = StringBuilder()
            val toolCalls = ToolCallAccumulator()
            var failure: String? = null
            var finishReason: String? = null

            api.stream(request.copy(messages = messages.toList(), tools = tools.specs())) { event ->
                when (event) {
                    is ChatStreamEvent.Content -> {
                        content.append(event.text)
                        callbacks.onToken(event.text)
                    }
                    is ChatStreamEvent.ToolCallDelta -> toolCalls.apply(event)
                    is ChatStreamEvent.Failure -> failure = event.message
                    is ChatStreamEvent.Finished -> finishReason = event.reason
                    ChatStreamEvent.Done -> Unit
                }
            }

            failure?.let {
                callbacks.onFailed(it)
                return
            }

            val calls = try {
                toolCalls.build()
            } catch (t: ApiException) {
                callbacks.onFailed(t.message ?: "工具调用格式错误")
                return
            }

            if (calls.isEmpty()) {
                val text = content.toString()
                if (text.isBlank()) {
                    val message = when (finishReason) {
                        "content_filter" -> "内容被安全策略拦截"
                        "length" -> "回复超长被截断"
                        else -> "DeepSeek 没有返回内容"
                    }
                    callbacks.onFailed(message)
                } else {
                    callbacks.onCompleted(text)
                }
                return
            }

            Logs.add("Chat tool round tools=${calls.size}")
            messages += ChatMessage(
                role = Role.ASSISTANT,
                content = content.toString().takeIf { it.isNotBlank() },
                toolCalls = calls
            )

            for (call in calls) {
                callbacks.onToolStarted(call.name)
                val result = tools.execute(call.name, call.arguments)
                messages += ChatMessage(
                    role = Role.TOOL,
                    content = result,
                    toolCallId = call.id
                )
            }
        }

        callbacks.onFailed("工具调用次数过多，请换个问法")
    }
}
