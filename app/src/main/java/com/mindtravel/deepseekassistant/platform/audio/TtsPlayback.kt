package com.mindtravel.deepseekassistant.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TtsPlayback(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val SAMPLE_RATE = 24_000
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var job: Job? = null
    private var channel: Channel<ByteArray>? = null
    private var session = 0
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var track: AudioTrack? = null

    fun start(
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val current = ++session
        val audio = Channel<ByteArray>(capacity = 3)
        channel = audio
        job = scope.launch(Dispatchers.IO) {
            play(current, audio, onStart, onDone, onError)
        }
    }

    fun send(pcm: ByteArray): Boolean = channel?.trySend(pcm)?.isSuccess == true

    fun finish() {
        channel?.close()
    }

    fun stop() {
        session++
        job?.cancel()
        job = null
        channel?.close()
        channel = null
        val current = track
        track = null
        if (current != null) {
            runCatching { current.pause() }
            runCatching { current.flush() }
            runCatching { current.release() }
        }
        abandonFocus()
    }

    private suspend fun play(
        current: Int,
        audio: Channel<ByteArray>,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer * 2, 14_400)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val player = try {
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (t: Throwable) {
            withContext(Dispatchers.Main) { onError("无法创建音频输出：${t.message}") }
            return
        }

        track = player
        var started = false
        var totalFrames = 0L
        val writeSize = maxOf(bufferSize, 4_096)

        try {
            for (pcm in audio) {
                if (current != session) return
                if (pcm.isEmpty()) continue

                if (!started) {
                    requestFocus(attributes)
                    player.play()
                    started = true
                    Logs.add("TTS track start buffer=$bufferSize")
                    withContext(Dispatchers.Main) { onStart() }
                }

                var offset = 0
                while (offset < pcm.size) {
                    if (current != session) return
                    val length = minOf(writeSize, pcm.size - offset)
                    val written = player.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw ApiException("音频写入失败：$written")
                    if (written == 0) {
                        delay(5)
                        continue
                    }
                    offset += written
                }
                totalFrames += pcm.size / 2
            }

            if (started) {
                val expectedMs = (totalFrames / (SAMPLE_RATE / 1000.0)).toLong()
                val deadline = System.currentTimeMillis() + expectedMs + 3_000
                while (
                    current == session &&
                    player.playbackHeadPosition < totalFrames &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(20)
                }
                Logs.add("TTS track done pos=${player.playbackHeadPosition}/$totalFrames underruns=${player.underrunCount}")
            }
            if (current == session) {
                withContext(Dispatchers.Main) { onDone() }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Logs.add("TTS playback error: ${t.message}")
            if (current == session) {
                withContext(Dispatchers.Main) { onError(t.message ?: "TTS 播放失败") }
            }
        } finally {
            if (track === player) track = null
            runCatching { player.stop() }
            runCatching { player.flush() }
            runCatching { player.release() }
            abandonFocus()
        }
    }

    private fun requestFocus(attributes: AudioAttributes) {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { request ->
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        focusRequest = null
    }
}
