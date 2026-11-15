package com.mindtravel.deepseekassistant.feature.pc.model

interface PcGateway {
    fun connect(profile: PcProfile)
    fun isConnected(): Boolean
    fun info(profile: PcProfile): PcInfo
    fun exec(profile: PcProfile, command: String): String
    fun watchUpdates(
        profile: PcProfile,
        manifestUrl: String,
        onLine: (String) -> Unit
    ): AutoCloseable
    fun publicKey(): String
    fun close()
}
