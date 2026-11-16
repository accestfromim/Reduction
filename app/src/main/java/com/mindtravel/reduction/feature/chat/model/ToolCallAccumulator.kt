package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.model.chat.ChatStreamEvent
import com.mindtravel.reduction.model.chat.ToolCall
import java.util.TreeMap

class ToolCallAccumulator {

    private data class MutableCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder()
    )

    private val calls = TreeMap<Int, MutableCall>()

    fun apply(delta: ChatStreamEvent.ToolCallDelta) {
        val call = calls.getOrPut(delta.index) { MutableCall() }
        delta.id?.let { call.id = it }
        delta.name?.let { call.name = it }
        delta.arguments?.let { call.arguments.append(it) }
    }

    fun build(): List<ToolCall> = calls.values.map { call ->
        val id = call.id?.takeIf { it.isNotBlank() }
            ?: throw ApiException("工具调用缺少 id")
        val name = call.name?.takeIf { it.isNotBlank() }
            ?: throw ApiException("工具调用缺少名称")
        ToolCall(
            id = id,
            name = name,
            arguments = call.arguments.toString().ifBlank { "{}" }
        )
    }
}
