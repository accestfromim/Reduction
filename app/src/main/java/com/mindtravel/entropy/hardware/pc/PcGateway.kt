package com.mindtravel.entropy.hardware.pc

interface PcGateway {
    fun connect(profile: PcProfile)
    fun isConnected(): Boolean
    fun info(profile: PcProfile): PcInfo
    fun exec(profile: PcProfile, command: String): String

    /** Runs [command] on the computer with [input] as its standard input. */
    fun send(profile: PcProfile, command: String, input: ByteArray): String
    fun watchUpdates(
        profile: PcProfile,
        manifestUrl: String,
        onLine: (String) -> Unit
    ): AutoCloseable
    fun publicKey(): String
    fun close()
}
