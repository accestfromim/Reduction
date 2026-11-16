package com.mindtravel.reduction.hardware.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.mindtravel.reduction.core.Logs

/** The phone's audio output as a single 24 kHz mono stream. Used from the audio queue's thread only. */
class AudioTrackSink : PcmSink {

    private var track: AudioTrack? = null

    override fun write(data: ByteArray, offset: Int, length: Int): Int =
        open().write(data, offset, length, AudioTrack.WRITE_BLOCKING)

    override fun playedFrames(): Long =
        (track?.playbackHeadPosition ?: 0).toLong() and 0xffffffffL

    override fun flush() {
        val current = track ?: return
        runCatching {
            current.pause()
            current.flush()
            current.play()
        }.onFailure { Logs.add("Audio flush failed: ${it.message}") }
    }

    override fun release() {
        track?.let { runCatching { it.release() } }
        track = null
    }

    private fun open(): AudioTrack {
        track?.let { return it }
        val minBuffer = AudioTrack.getMinBufferSize(
            AudioQueue.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Small on purpose: what is in the buffer is what cannot be taken back.
        val bufferSize = maxOf(minBuffer, AudioQueue.SAMPLE_RATE * 2 * BUFFER_MS / 1000)
        val created = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioQueue.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        created.play()
        Logs.add("Audio stream opened buffer=${bufferSize}B min=${minBuffer}B")
        track = created
        return created
    }

    private companion object {
        const val BUFFER_MS = 150
    }
}
