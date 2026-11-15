package com.mindtravel.entropy.hardware.pc

data class PcProfile(
    val host: String,
    val port: Int = 22,
    val user: String
)
