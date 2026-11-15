package com.mindtravel.deepseekassistant.feature.chat.model

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.TextSanitizer
import com.mindtravel.deepseekassistant.feature.chat.model.tools.ToolRegistry

class ChatSession(
    private val gateway: ChatGateway,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS
) : ChatEngine {

    companion object {
        private const val DEFAULT_MAX_TOOL_ROUNDS = 4
    }

    override fun run(
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
            var sawContent = false
            var droppedNulls = 0

            gateway.stream(request.copy(messages = messages.toList(), tools = tools.specs())) { event ->
                when (event) {
                    is ChatStreamEvent.Content -> {
                        val text = if (sawContent) {
                            event.text
                        } else {
                            TextSanitizer.stripLeadingNullChunks(event.text)
                        }
                        if (!sawContent && text != event.text) droppedNulls++
                        if (text.isBlank()) return@stream
                        sawContent = true
                        content.append(text)
                        callbacks.onToken(text)
                    }
                    is ChatStreamEvent.ToolCallDelta -> toolCalls.apply(event)
                    is ChatStreamEvent.Failure -> failure = event.message
                    is ChatStreamEvent.Finished -> finishReason = event.reason
                    ChatStreamEvent.Done -> Unit
                }
            }

            if (droppedNulls > 0) {
                Logs.add("chat dropped null tokens=$droppedNulls")
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
                callbacks.onToolStarted(call.name, call.arguments)
                val startedAt = System.nanoTime()
                val result = tools.execute(call.name, call.arguments)
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                val success = !result.startsWith("工具执行失败") && !result.startsWith("未知工具")
                callbacks.onToolFinished(call.name, durationMs, success)
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
