package com.mindtravel.deepseekassistant.feature.pc.model

data class PcProfile(
    val host: String,
    val port: Int = 22,
    val user: String
)
