package com.mindtravel.reduction.feature.chat.ui

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.model.chat.ChatImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings the result of a sub agent's run to the conversation by itself. When a run
 * ends, its report is handed to the assistant as a message of its own, and the assistant
 * answers it with one reply, spoken like any other. It waits for a quiet moment: never
 * while the user is speaking or a reply is under way.
 */
class AgentWatch(
    /** The reports of runs that have ended since the last call; asks the computer, so it is slow. */
    private val finished: () -> List<Report>,
    private val quiet: () -> Boolean,
    private val deliver: (Report) -> Boolean,
    private val scope: CoroutineScope,
    private val everyMillis: Long = 4_000
) {
    /** [title] is what the user is shown; [text] is what the assistant is given, with [images] to look at. */
    class Report(val title: String, val text: String, val images: List<ChatImage> = emptyList())

    private val waiting = ArrayDeque<Report>()

    fun start() {
        scope.launch {
            while (isActive) {
                delay(everyMillis)
                runCatching { take(withContext(Dispatchers.IO) { finished() }) }
                    .onFailure { Logs.add("Agent watch: ${it.message}") }
                pass()
            }
        }
    }

    /** Queues reports to be handed over. */
    internal fun take(reports: List<Report>) {
        waiting += reports
    }

    /** Hands over one waiting report, if this is a quiet moment. */
    internal fun pass() {
        val next = waiting.firstOrNull() ?: return
        if (!quiet()) return
        if (deliver(next)) {
            waiting.removeFirst()
            Logs.add("Agent watch: delivered ${next.title}")
        }
    }
}
