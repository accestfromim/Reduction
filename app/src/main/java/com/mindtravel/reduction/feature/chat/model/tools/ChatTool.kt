package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ToolSpec

interface ChatTool {
    val spec: ToolSpec
    fun execute(argumentsJson: String): String

    /**
     * Pictures that belong to the answer [execute] has just given, for the assistant to look at.
     * A tool's answer is words; what it has to show follows it at once, in the same reply.
     * Asked for once after each call, on the thread that made the call.
     */
    fun pictures(): List<ChatImage> = emptyList()

    /** What this call does, in a few words, when the assistant did not say so itself. */
    fun describe(argumentsJson: String): String = spec.name

    /** Whether this call is costly enough that its summary is said aloud, not only shown. */
    fun announces(argumentsJson: String): Boolean = false
}
