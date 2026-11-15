package com.mindtravel.entropy.model.chat

import com.mindtravel.entropy.core.arrayOrEmpty
import com.mindtravel.entropy.core.objectOrNull
import com.mindtravel.entropy.core.objects
import com.mindtravel.entropy.core.stringOrNull
import com.mindtravel.entropy.model.chat.ChatStreamEvent
import org.json.JSONObject

class ChatStreamParser {

    fun parse(line: String): List<ChatStreamEvent> {
        if (line.isBlank() || !line.startsWith("data:")) return emptyList()
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return listOf(ChatStreamEvent.Done)

        val event = try {
            JSONObject(data)
        } catch (t: Throwable) {
            return listOf(ChatStreamEvent.Failure("流式数据解析失败"))
        }

        event.objectOrNull("error")?.let { error ->
            val message = error.stringOrNull("message")?.takeIf { it.isNotBlank() } ?: "生成失败"
            return listOf(ChatStreamEvent.Failure(message))
        }

        val choices = event.arrayOrEmpty("choices")
        if (choices.length() == 0) return emptyList()
        val choice = choices.objects().firstOrNull() ?: return emptyList()

        val events = mutableListOf<ChatStreamEvent>()
        choice.objectOrNull("delta")?.let { delta ->
            delta.stringOrNull("content")
                ?.takeIf { it.isNotEmpty() }
                ?.let { events += ChatStreamEvent.Content(it) }

            delta.arrayOrEmpty("tool_calls").objects().forEach { item ->
                val index = item.optInt("index", 0)
                val function = item.objectOrNull("function")
                events += ChatStreamEvent.ToolCallDelta(
                    index = index,
                    id = item.stringOrNull("id")?.takeIf { it.isNotBlank() },
                    name = function?.stringOrNull("name")?.takeIf { it.isNotBlank() },
                    arguments = function?.stringOrNull("arguments")
                )
            }
        }

        choice.stringOrNull("finish_reason")
            ?.takeIf { it.isNotBlank() }
            ?.let { events += ChatStreamEvent.Finished(it) }

        return events
    }
}
