package com.mindtravel.reduction.core.events

interface EventSink {
    fun emit(function: String, vararg args: Any?)
}
