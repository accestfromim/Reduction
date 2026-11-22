package com.mindtravel.reduction.model.chat

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.NetworkException
import org.json.JSONArray
import org.json.JSONObject

/** Reduction owns the tool loop. Dot receives only the conversation and declared tools. */
class SuperAgentGateway(
    private val fallback: ChatGateway,
    private val sendToDot: (ByteArray) -> String
) : ChatGateway {
    companion object {
        const val DOT = "gpt-6.2-astra-dot"
        // Runs on the paired Windows computer, locally or through the phone's existing SSH link.
        const val COMMAND = "/mnt/c/Windows/System32/curl.exe --noproxy '*' --silent --show-error --write-out '\\nDOT_HTTP=%{http_code}\\n' --max-time 180 -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:8788/v1/responses"
    }

    override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
        if (request.model != DOT) return fallback.stream(request, onEvent)
        val input = JSONArray()
        for (message in request.messages) {
            if (message.role == Role.TOOL) {
                input.put(JSONObject().put("type", "function_call_output").put("call_id", message.toolCallId).put("output", message.content))
                continue
            }
            if (!message.content.isNullOrEmpty() || message.images.isNotEmpty()) {
                val parts = JSONArray()
                message.content?.let { parts.put(JSONObject().put("type", if (message.role == Role.ASSISTANT) "output_text" else "input_text").put("text", it)) }
                for (image in message.images) parts.put(JSONObject().put("type", "input_image").put("image_url", "data:${image.mediaType};base64,${image.base64}"))
                input.put(JSONObject().put("role", message.role.wire).put("content", parts))
            }
            for (call in message.toolCalls) input.put(JSONObject().put("type", "function_call").put("call_id", call.id).put("name", call.name).put("arguments", call.arguments))
        }
        val tools = JSONArray()
        for (tool in request.tools) tools.put(tool.toJson().getJSONObject("function").put("type", "function").put("strict", false))
        val body = JSONObject().put("model", DOT).put("stream", true).put("input", input).put("tools", tools)
        val raw = sendToDot(body.toString().toByteArray(Charsets.UTF_8))
        val status = raw.lineSequence().lastOrNull { it.startsWith("DOT_HTTP=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()
        val payload = raw.substringBeforeLast("\nDOT_HTTP=").trim()
        if (status == 0 || status in listOf(408, 429, 500, 502, 503, 504)) {
            throw NetworkException("Dot Astra 连接失败（HTTP ${status}）")
        }
        if (status != null && status !in 200..299) {
            val details = runCatching { JSONObject(payload).optJSONObject("error") }.getOrNull()
            throw ApiException("Dot Astra HTTP $status：" + (details?.optString("message")?.take(240) ?: "请求被拒绝"))
        }
        val terminal = payload.lineSequence().filter { it.startsWith("data:") }.mapNotNull {
            runCatching { JSONObject(it.substringAfter(':').trim()) }.getOrNull()
        }.lastOrNull { it.optString("type") in setOf("response.completed", "response.failed", "response.incomplete", "error") }
            ?: throw NetworkException("Dot Astra 响应流中断（HTTP ${status ?: "未知"}），未收到结束事件")
        val response = terminal.optJSONObject("response")
        if (terminal.optString("type") != "response.completed" || response == null) {
            val failure = response?.optJSONObject("error") ?: terminal.optJSONObject("error") ?: terminal
            val reason = response?.optJSONObject("incomplete_details")?.optString("reason")
            val code = failure.optString("code")
            val message = failure.optString("message").take(240).ifBlank { reason ?: terminal.optString("type") }
            if (code in setOf("server_error", "rate_limit_exceeded", "overloaded")) throw NetworkException("Dot Astra：$message")
            throw ApiException("Dot Astra：$message")
        }
        val output = response.getJSONArray("output")
        var calls = 0
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            when (item.optString("type")) {
                "function_call" -> onEvent(ChatStreamEvent.ToolCallDelta(calls++, item.getString("call_id"), item.getString("name"), item.getString("arguments")))
                "message" -> {
                    val parts = item.getJSONArray("content")
                    for (j in 0 until parts.length()) {
                        val part = parts.getJSONObject(j)
                        if (part.optString("type") == "output_text") onEvent(ChatStreamEvent.Content(part.getString("text")))
                    }
                }
            }
        }
        onEvent(ChatStreamEvent.Finished(if (calls > 0) "tool_calls" else "stop"))
        onEvent(ChatStreamEvent.Done)
    }
}
