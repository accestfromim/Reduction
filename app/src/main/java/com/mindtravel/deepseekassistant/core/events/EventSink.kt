package com.mindtravel.deepseekassistant.core.events

interface EventSink {
    fun emit(function: String, vararg args: Any?)
}
