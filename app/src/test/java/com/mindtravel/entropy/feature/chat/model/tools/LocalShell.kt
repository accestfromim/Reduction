package com.mindtravel.entropy.feature.chat.model.tools

import com.mindtravel.entropy.hardware.pc.PcGateway
import com.mindtravel.entropy.hardware.pc.PcInfo
import com.mindtravel.entropy.hardware.pc.PcProfile
import java.io.File

/**
 * Runs each command with a real shell, the way the SSH session on the computer would.
 * [home], when given, is the home directory the commands see.
 */
class LocalShell(private val home: File? = null) : PcGateway {
    override fun exec(profile: PcProfile, command: String): String = run(command, null)

    override fun send(profile: PcProfile, command: String, input: ByteArray): String = run(command, input)

    private fun run(command: String, input: ByteArray?): String {
        val builder = ProcessBuilder("sh", "-c", command).redirectErrorStream(true)
        home?.let { builder.environment()["HOME"] = it.absolutePath }
        val process = builder.start()
        process.outputStream.use { if (input != null) it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    override fun connect(profile: PcProfile) = Unit
    override fun isConnected() = true
    override fun info(profile: PcProfile): PcInfo = throw UnsupportedOperationException()
    override fun watchUpdates(profile: PcProfile, manifestUrl: String, onLine: (String) -> Unit) =
        AutoCloseable { }
    override fun publicKey() = ""
    override fun close() = Unit
}
