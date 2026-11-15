package com.mindtravel.deepseekassistant.feature.chat.ui

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.feature.chat.model.ChatCallbacks
import com.mindtravel.deepseekassistant.feature.chat.model.ChatEngine
import com.mindtravel.deepseekassistant.feature.chat.model.ChatMessage
import com.mindtravel.deepseekassistant.feature.chat.model.ChatRequest
import com.mindtravel.deepseekassistant.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.deepseekassistant.feature.chat.model.Role
import com.mindtravel.deepseekassistant.core.events.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatController(
    private val settingsRepository: SettingsGateway,
    private val chatEngine: ChatEngine,
    private val toolFactory: ChatToolFactory,
    private val emitter: EventSink,
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

        val registry = toolFactory.create(settings)
        val request = ChatRequest(
            apiKey = settings.apiKey,
            model = settings.model,
            messages = boundedMessages,
            tools = registry.specs()
        )

        Logs.add("chat request model=${settings.model} tools=${registry.specs().size}")
        emitter.emit("onStatus", requestId, "思考中")

        scope.launch(Dispatchers.IO) {
            try {
                chatEngine.run(
                    request = request,
                    tools = registry,
                    callbacks = object : ChatCallbacks {
                        override fun onToken(text: String) {
                            emitter.emit("onChatToken", requestId, text)
                        }

                        override fun onToolStarted(name: String, arguments: String) {
                            Logs.add("chat tool started=$name")
                            emitter.emit("onToolStarted", requestId, name, arguments)
                        }

                        override fun onToolFinished(name: String, durationMs: Long, success: Boolean) {
                            Logs.add("chat tool finished=$name ${durationMs}ms success=$success")
                            emitter.emit("onToolFinished", requestId, name, durationMs, success)
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
