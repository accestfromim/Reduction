package com.mindtravel.reduction.hardware.pc

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class SshKeyStore(context: Context) {

    data class Key(val privateKey: ByteArray, val publicKey: String)

    private val dir = File(context.filesDir, "ssh").apply { mkdirs() }
    private val privateFile = File(dir, "id_rsa")
    private val publicFile = File(dir, "id_rsa.pub")

    @Synchronized
    fun ensure(): Key {
        if (privateFile.exists() && publicFile.exists()) {
            return Key(privateFile.readBytes(), publicFile.readText().trim())
        }
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val privateBytes = ByteArrayOutputStream().use {
            pair.writeOpenSSHv1PrivateKey(it, null as ByteArray?)
            it.toByteArray()
        }
        val publicKey = "${pair.keyTypeString} ${
            Base64.getEncoder().encodeToString(pair.publicKeyBlob)
        } dsassist@android"
        privateFile.writeBytes(privateBytes)
        publicFile.writeText(publicKey)
        pair.dispose()
        return Key(privateBytes, publicKey)
    }

    @Synchronized
    fun publicKey(): String = ensure().publicKey

    @Synchronized
    fun reset() {
        privateFile.delete()
        publicFile.delete()
    }
}
