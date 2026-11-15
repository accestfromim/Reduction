package com.mindtravel.entropy.feature.chat.ui

import com.mindtravel.entropy.core.ApiException
import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.core.events.EventSink
import com.mindtravel.entropy.core.settings.SettingsGateway
import com.mindtravel.entropy.feature.chat.model.ChatCallbacks
import com.mindtravel.entropy.feature.chat.model.ChatEngine
import com.mindtravel.entropy.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.entropy.feature.voice.SpeechController
import com.mindtravel.entropy.model.chat.ChatMessage
import com.mindtravel.entropy.model.chat.ChatRequest
import com.mindtravel.entropy.model.chat.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Told when a reply begins and when it is completely over (generated and spoken). */
interface ReplyObserver {
    fun onReplyStarted(id: String)
    fun onReplyEnded(id: String, ok: Boolean)
}

/**
 * Runs one reply at a time: streams the model's text to the UI and, in step,
 * into speech. Starting a new reply or calling [cancel] abandons the current one.
 */
class ChatController(
    private val settingsRepository: SettingsGateway,
    private val chatEngine: ChatEngine,
    private val toolFactory: ChatToolFactory,
    private val speech: SpeechController,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {
    var observer: ReplyObserver? = null

    private class Reply(val id: String) {
        var generated = false
        var spoken = false
        var closed = false
        var job: Job? = null
    }

    private val lock = Any()
    private var current: Reply? = null

    /**
     * Starts a reply to [messages]. [onCompleted] is given the finished text once, when the
     * reply has been generated in full.
     */
    fun send(requestId: String, messages: List<ChatMessage>, onCompleted: (String) -> Unit = {}): Boolean {
        val settings = settingsRepository.load()
        if (settings.apiKey.isBlank()) {
            emitter.emit("onChatError", requestId, "请先在设置里填写 API Key")
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

        cancel()
        val reply = Reply(requestId)
        synchronized(lock) { current = reply }
        speech.open(requestId) {
            synchronized(lock) { reply.spoken = true }
            settle(reply, ok = true)
        }
        observer?.onReplyStarted(requestId)

        Logs.add("chat request model=${settings.model} tools=${registry.specs().size}")
        emitter.emit("onStatus", requestId, "思考中")

        reply.job = scope.launch(Dispatchers.IO) {
            try {
                chatEngine.run(request, registry, callbacksFor(reply, onCompleted))
            } catch (t: ApiException) {
                fail(reply, t.message ?: "请求失败")
            } catch (t: Throwable) {
                Logs.add("chat crashed: ${t.message}")
                fail(reply, "请求失败：${t.message ?: "未知错误"}")
            }
        }
        return true
    }

    /** Abandons the reply in progress, if any. The UI is told through onChatAborted. */
    fun cancel() {
        val reply = synchronized(lock) {
            val active = current ?: return
            current = null
            active.closed = true
            active
        }
        reply.job?.cancel()
        speech.stop(notify = false)
        Logs.add("chat aborted by ${Throwable().stackTrace.getOrNull(1)?.let { it.className.substringAfterLast('.') + "." + it.methodName }}")
        emitter.emit("onChatAborted", reply.id)
    }

    private fun callbacksFor(reply: Reply, onCompleted: (String) -> Unit) = object : ChatCallbacks {
        override fun onToken(text: String) {
            if (!isCurrent(reply)) return
            emitter.emit("onChatToken", reply.id, text)
            speech.push(reply.id, text)
        }

        override fun onToolStarted(name: String, summary: String, arguments: String, announce: Boolean) {
            if (!isCurrent(reply)) return
            Logs.add("chat tool started=$name announce=$announce")
            // Text before a tool call is a preamble the UI discards; stop saying it.
            speech.restart(reply.id)
            if (announce) speech.push(reply.id, "$summary。\n")
            emitter.emit("onToolStarted", reply.id, name, summary, arguments)
        }

        override fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String) {
            if (!isCurrent(reply)) return
            Logs.add("chat tool finished=$name ${durationMs}ms success=$success")
            emitter.emit("onToolFinished", reply.id, name, durationMs, success, result.take(TOOL_RESULT_SHOWN))
        }

        override fun onCompleted(text: String) {
            if (!isCurrent(reply)) return
            Logs.add("chat completed chars=${text.length}")
            emitter.emit("onChatDone", reply.id, text)
            onCompleted(text)
            synchronized(lock) { reply.generated = true }
            speech.close(reply.id)
            settle(reply, ok = true)
        }

        override fun onFailed(message: String) {
            Logs.add("chat failed: $message")
            fail(reply, message)
        }
    }

    private fun fail(reply: Reply, message: String) {
        if (!isCurrent(reply)) return
        emitter.emit("onChatError", reply.id, message)
        settle(reply, ok = false)
        speech.stop(notify = false)
    }

    /** Reports the end of [reply] once: on failure at once, otherwise when generated and spoken. */
    private fun settle(reply: Reply, ok: Boolean) {
        synchronized(lock) {
            if (reply.closed) return
            if (ok && !(reply.generated && reply.spoken)) return
            reply.closed = true
            if (current === reply) current = null
        }
        observer?.onReplyEnded(reply.id, ok)
    }

    private fun isCurrent(reply: Reply): Boolean = synchronized(lock) { current === reply && !reply.closed }

    companion object {
        private const val MAX_MESSAGES = 40

        /** How much of a tool result the tool card can show. */
        private const val TOOL_RESULT_SHOWN = 12_000
    }
}
