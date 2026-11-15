package com.mindtravel.entropy.core.events

interface EventSink {
    fun emit(function: String, vararg args: Any?)
}
