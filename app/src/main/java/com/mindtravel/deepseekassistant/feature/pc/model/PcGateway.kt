package com.mindtravel.deepseekassistant.feature.pc.model

interface PcGateway {
    fun connect(profile: PcProfile)
    fun info(profile: PcProfile): PcInfo
    fun exec(profile: PcProfile, command: String): String
    fun publicKey(): String
    fun close()
}
