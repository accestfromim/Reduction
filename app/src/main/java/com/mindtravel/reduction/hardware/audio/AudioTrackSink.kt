package com.mindtravel.reduction.hardware.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.mindtravel.reduction.core.Logs

/**
 * The phone's audio output as a single 24 kHz stereo stream. Mono sound is sent to both
 * sides alike. Used from the audio queue's thread only.
 */
class AudioTrackSink : PcmSink {

    private var track: AudioTrack? = null

    private var both = ByteArray(0)

    override fun write(data: ByteArray, offset: Int, length: Int, stereo: Boolean): Int {
        if (stereo) return open().write(data, offset, length, AudioTrack.WRITE_BLOCKING)
        // Each mono sample goes out twice: once to the left, once to the right.
        val even = length - length % 2
        if (both.size < even * 2) both = ByteArray(even * 2)
        var from = offset
        var to = 0
        while (from < offset + even) {
            both[to] = data[from]; both[to + 1] = data[from + 1]
            both[to + 2] = data[from]; both[to + 3] = data[from + 1]
            from += 2
            to += 4
        }
        val accepted = open().write(both, 0, even * 2, AudioTrack.WRITE_BLOCKING)
        // Answered in the caller's own terms: mono bytes, whole samples only.
        return if (accepted < 0) accepted else accepted / 4 * 2
    }

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
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // Small on purpose: what is in the buffer is what cannot be taken back.
        val bufferSize = maxOf(minBuffer, AudioQueue.SAMPLE_RATE * 4 * BUFFER_MS / 1000)
        val created = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioQueue.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
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
