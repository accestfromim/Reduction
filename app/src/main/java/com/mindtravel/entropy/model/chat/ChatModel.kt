package com.mindtravel.entropy.model.chat

import com.mindtravel.entropy.core.ApiException
import com.mindtravel.entropy.core.objects
import com.mindtravel.entropy.core.TextSanitizer
import com.mindtravel.entropy.core.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wire == value }
                ?: throw ApiException("未知消息角色：$value")
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
) {
    init {
        if (id.isBlank()) throw ApiException("工具调用 id 为空")
        if (name.isBlank()) throw ApiException("工具调用名称为空")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        })
    }
}

data class ChatMessage(
    val role: Role,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
) {
    init {
        when (role) {
            Role.SYSTEM, Role.USER -> if (content.isNullOrBlank()) {
                throw ApiException("${role.wire} 消息缺少 content")
            }
            Role.TOOL -> if (toolCallId.isNullOrBlank() || content == null) {
                throw ApiException("tool 消息缺少 toolCallId 或 content")
            }
            Role.ASSISTANT -> if (content == null && toolCalls.isEmpty()) {
                throw ApiException("assistant 消息缺少 content 和 tool_calls")
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role.wire)
        when (role) {
            Role.ASSISTANT -> {
                if (content == null) put("content", JSONObject.NULL) else put("content", content)
                if (toolCalls.isNotEmpty()) {
                    put("tool_calls", JSONArray().apply {
                        toolCalls.forEach { put(it.toJson()) }
                    })
                }
            }
            Role.TOOL -> {
                put("content", content)
                put("tool_call_id", toolCallId)
            }
            else -> put("content", content)
        }
    }

    companion object {
        fun fromJsonArray(raw: String): List<ChatMessage> {
            val array = try {
                JSONArray(raw)
            } catch (t: Throwable) {
                throw ApiException("消息格式错误", t)
            }
            val items = array.objects()
            if (items.isEmpty()) throw ApiException("消息为空")
            return items.map { item ->
                val role = Role.fromWire(item.stringOrNull("role") ?: throw ApiException("消息缺少 role"))
                when (role) {
                    Role.SYSTEM, Role.USER -> {
                        val content = item.stringOrNull("content")
                            ?.takeIf { it.isNotBlank() }
                            ?: throw ApiException("${role.wire} 消息缺少 content")
                        ChatMessage(role = role, content = content)
                    }
                    Role.ASSISTANT -> {
                        val content = item.stringOrNull("content")
                            ?.let(TextSanitizer::stripRepeatedNullPrefix)
                            ?.takeIf { it.isNotBlank() }
                        ChatMessage(role = role, content = content)
                    }
                    Role.TOOL -> throw ApiException("客户端不能提交 tool 消息")
                }
            }
        }
    }
}

/** One argument of a tool. [choices], when given, are the only values allowed. */
data class ToolParam(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val choices: List<String> = emptyList()
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParam>
) {
    /** A tool with a single required text argument. */
    constructor(name: String, description: String, parameterName: String, parameterDescription: String) :
        this(name, description, listOf(ToolParam(parameterName, parameterDescription)))

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    for (parameter in parameters) {
                        put(parameter.name, JSONObject().apply {
                            put("type", "string")
                            put("description", parameter.description)
                            if (parameter.choices.isNotEmpty()) put("enum", JSONArray(parameter.choices))
                        })
                    }
                })
                put("required", JSONArray(parameters.filter { it.required }.map { it.name }))
            })
        })
    }
}

data class ChatRequest(
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double = 0.7
)

sealed interface ChatStreamEvent {
    data class Content(val text: String) : ChatStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?
    ) : ChatStreamEvent
    data class Failure(val message: String) : ChatStreamEvent
    data class Finished(val reason: String?) : ChatStreamEvent
    data object Done : ChatStreamEvent
}
