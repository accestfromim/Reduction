package com.mindtravel.deepseekassistant.ui.controller

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.data.SettingsRepository
import com.mindtravel.deepseekassistant.network.chat.CalculatorTool
import com.mindtravel.deepseekassistant.network.chat.ChatCallbacks
import com.mindtravel.deepseekassistant.network.chat.ChatMessage
import com.mindtravel.deepseekassistant.network.chat.ChatRequest
import com.mindtravel.deepseekassistant.network.chat.ChatSession
import com.mindtravel.deepseekassistant.network.chat.Role
import com.mindtravel.deepseekassistant.network.chat.ToolRegistry
import com.mindtravel.deepseekassistant.network.chat.WebSearchTool
import com.mindtravel.deepseekassistant.network.search.SearchApi
import com.mindtravel.deepseekassistant.ui.JsEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatController(
    private val settingsRepository: SettingsRepository,
    private val chatSession: ChatSession,
    private val searchApi: SearchApi,
    private val emitter: JsEmitter,
    private val scope: CoroutineScope
) {

    fun send(requestId: String, messagesJson: String): Boolean {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onChatError", requestId, "请先在设置里填写 API Key")
            return false
        }

        val messages = try {
            ChatMessage.fromJsonArray(messagesJson)
        } catch (t: Throwable) {
            emitter.emit("onChatError", requestId, t.message ?: "消息格式错误")
            return false
        }

        val boundedMessages = if (messages.size <= MAX_MESSAGES) {
            messages
        } else {
            val system = messages.firstOrNull { it.role == Role.SYSTEM }
            listOfNotNull(system) + messages.filter { it.role != Role.SYSTEM }.takeLast(MAX_MESSAGES - 1)
        }

        val tools = buildList {
            if (settings.webSearchEnabled) add(WebSearchTool(searchApi, settings.apiKey))
            if (settings.calculatorEnabled) add(CalculatorTool())
        }
        val registry = ToolRegistry(tools)
        val request = ChatRequest(
            apiKey = settings.apiKey,
            model = settings.model,
            messages = boundedMessages,
            tools = tools.map { it.spec }
        )

        Logs.add("chat request model=${settings.model} tools=${tools.size}")
        emitter.emit("onStatus", requestId, "思考中")

        scope.launch(Dispatchers.IO) {
            try {
                chatSession.run(
                    request = request,
                    tools = registry,
                    callbacks = object : ChatCallbacks {
                        override fun onToken(text: String) {
                            emitter.emit("onChatToken", requestId, text)
                        }

                        override fun onToolStarted(name: String) {
                            Logs.add("chat tool started=$name")
                            emitter.emit("onTool", requestId, name)
                        }

                        override fun onCompleted(text: String) {
                            Logs.add("chat completed chars=${text.length}")
                            emitter.emit("onChatDone", requestId, text)
                        }

                        override fun onFailed(message: String) {
                            Logs.add("chat failed: $message")
                            emitter.emit("onChatError", requestId, message)
                        }
                    }
                )
            } catch (t: ApiException) {
                emitter.emit("onChatError", requestId, t.message ?: "请求失败")
            } catch (t: Throwable) {
                Logs.add("chat crashed: ${t.message}")
                emitter.emit("onChatError", requestId, "请求失败：${t.message ?: "未知错误"}")
            }
        }
        return true
    }

    companion object {
        private const val MAX_MESSAGES = 40
    }
}
