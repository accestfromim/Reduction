package com.mindtravel.entropy.core.audio

interface AudioGateway {
    fun transcribe(apiKey: String, wav: ByteArray): String
    fun synthesize(apiKey: String, text: String, voice: String): ByteArray
}
