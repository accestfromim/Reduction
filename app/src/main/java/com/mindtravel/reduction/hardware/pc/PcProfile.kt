package com.mindtravel.reduction.hardware.pc

import com.mindtravel.reduction.core.settings.AppSettings

data class PcProfile(
    val host: String,
    val port: Int = 22,
    val user: String,
    val proxyJump: String = ""
) {
    constructor(settings: AppSettings) : this(settings.pcHost, settings.pcPort, settings.pcUser, settings.pcProxyJump)
}
