package com.mindtravel.entropy.hardware.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM read out of a WAV file. */
class WavPcm(val sampleRate: Int, val channels: Int, val data: ByteArray) {

    val frames: Int get() = data.size / (2 * channels)
    val durationMs: Long get() = frames * 1000L / sampleRate

    companion object {
        /** Parses a RIFF/WAVE file holding 16-bit PCM. Throws [IllegalArgumentException] for anything else. */
        fun parse(bytes: ByteArray): WavPcm {
            require(bytes.size >= 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "WAVE") { "not a WAV file" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = tag(bytes, offset)
                val size = buffer.getInt(offset + 4)
                val body = offset + 8
                require(size >= 0 && body + size <= bytes.size || id == "data") { "broken WAV chunk $id" }
                when (id) {
                    "fmt " -> {
                        format = buffer.getShort(body).toInt() and 0xffff
                        channels = buffer.getShort(body + 2).toInt()
                        sampleRate = buffer.getInt(body + 4)
                        bits = buffer.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        require(format == 1 && bits == 16 && channels in 1..2 && sampleRate > 0) {
                            "only 16-bit PCM WAV is supported"
                        }
                        val length = minOf(size, bytes.size - body).let { it - it % (2 * channels) }
                        return WavPcm(sampleRate, channels, bytes.copyOfRange(body, body + length))
                    }
                }
                // Chunks are padded to an even length.
                offset = body + size + (size and 1)
            }
            throw IllegalArgumentException("WAV file has no audio data")
        }

        private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
    }
}
