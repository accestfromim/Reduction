package com.mindtravel.entropy.hardware.audio

import java.io.ByteArrayOutputStream

object WavEncoder {
    const val SAMPLE_RATE = 16_000

    fun pcm16ToWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        require(pcm.isNotEmpty()) { "pcm cannot be empty" }
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun le32(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
        }

        fun le16(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }

        out.write("RIFF".toByteArray())
        le32(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le32(16)
        le16(1)
        le16(1)
        le32(sampleRate)
        le32(sampleRate * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray())
        le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
