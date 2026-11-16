package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.model.chat.ToolSpec

interface ChatTool {
    val spec: ToolSpec
    fun execute(argumentsJson: String): String

    /** What this call does, in a few words, when the assistant did not say so itself. */
    fun describe(argumentsJson: String): String = spec.name

    /** Whether this call is costly enough that its summary is said aloud, not only shown. */
    fun announces(argumentsJson: String): Boolean = false
}
