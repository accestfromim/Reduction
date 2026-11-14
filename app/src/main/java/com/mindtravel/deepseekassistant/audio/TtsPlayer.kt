package com.mindtravel.deepseekassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.network.audio.AudioApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TtsPlayer(
    private val context: Context,
    private val audioApi: AudioApi,
    private val cache: TtsCache,
    private val scope: CoroutineScope
) {

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val END = "\u0000END\u0000"
        private const val MAX_CHUNK_CHARS = 500
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var job: Job? = null
    private var textChannel: Channel<String>? = null
    private var focusRequest: AudioFocusRequest? = null
    private var sessionId = 0

    @Volatile
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var finished = false

    private var apiKey: String = ""
    private var voice: String = "stella"

    private var onStartCallback: (() -> Unit)? = null
    private var onDoneCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    fun startSession(
        apiKey: String,
        voice: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()

        val currentSession = ++sessionId
        this.apiKey = apiKey
        this.voice = voice
        this.finished = false
        this.onStartCallback = onStart
        this.onDoneCallback = onDone
        this.onErrorCallback = onError

        val texts = Channel<String>(Channel.UNLIMITED)
        textChannel = texts
        val audio = Channel<ByteArray>(capacity = 3)

        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .joinToString(",") { it.type.toString() }
        Logs.add("TTS session start voice=$voice volume=$volume outputs=$outputs")

        job = scope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    launch { synthLoop(currentSession, texts, audio) }
                    launch { playLoop(currentSession, audio) }
                }
                if (currentSession == sessionId) {
                    Logs.add("TTS session done")
                    withContext(Dispatchers.Main) { onDoneCallback?.invoke() }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Logs.add("TTS session error: ${t.message}")
                if (currentSession == sessionId) {
                    withContext(Dispatchers.Main) { onErrorCallback?.invoke(t.message ?: "TTS 失败") }
                }
            } finally {
                abandonFocus()
            }
        }

    }

    fun append(text: String) {
        if (finished || text.isBlank()) return
        val queue = textChannel ?: return
        for (piece in chunk(text)) {
            if (piece.isNotBlank()) queue.trySend(piece)
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        textChannel?.trySend(END)
    }

    fun speak(
        apiKey: String,
        text: String,
        voice: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (text.isBlank()) {
            onError("没有可朗读的文本")
            return
        }
        startSession(apiKey, voice, onStart, onDone, onError)
        append(text)
        finish()
    }

    private suspend fun synthLoop(
        currentSession: Int,
        texts: Channel<String>,
        audio: Channel<ByteArray>
    ) {
        try {
            for (text in texts) {
                if (currentSession != sessionId) return
                if (text == END) break
                if (text.isBlank()) continue
                val pcm = loadAudio(text)
                if (currentSession != sessionId) return
                audio.send(pcm)
            }
        } finally {
            audio.close()
        }
    }

    private suspend fun playLoop(
        currentSession: Int,
        audio: Channel<ByteArray>
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

        val track = try {
            AudioTrack(
                attributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (t: Throwable) {
            throw ApiException("无法创建音频输出：${t.message}")
        }

        audioTrack = track
        var started = false
        var totalFrames = 0L
        val writeSize = maxOf(bufferSize, 4_096)

        try {
            for (pcm in audio) {
                if (currentSession != sessionId) return
                if (pcm.isEmpty()) continue

                if (!started) {
                    requestFocus(attributes)
                    track.play()
                    started = true
                    Logs.add("TTS track start buffer=$bufferSize")
                    withContext(Dispatchers.Main) { onStartCallback?.invoke() }
                }

                var offset = 0
                while (offset < pcm.size) {
                    if (currentSession != sessionId) return
                    val length = minOf(writeSize, pcm.size - offset)
                    val written = track.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) {
                        throw ApiException("音频写入失败：$written")
                    }
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
                    currentSession == sessionId &&
                    track.playbackHeadPosition < totalFrames &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(20)
                }
                Logs.add("TTS track done pos=${track.playbackHeadPosition}/$totalFrames underruns=${track.underrunCount}")
            }
        } finally {
            if (audioTrack === track) audioTrack = null
            runCatching { track.stop() }
            runCatching { track.flush() }
            runCatching { track.release() }
            abandonFocus()
        }
    }

    private suspend fun loadAudio(text: String): ByteArray {
        val cached = cache.get(voice, text)
        if (cached != null) {
            Logs.add("TTS cache hit chars=${text.length} bytes=${cached.size}")
            return cached
        }

        val pcm = audioApi.synthesize(apiKey, text, voice)
        cache.put(voice, text, pcm)
        Logs.add("TTS cache miss chars=${text.length} bytes=${pcm.size}")
        return pcm
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

    fun stop() {
        sessionId++
        job?.cancel()
        job = null
        textChannel?.close()
        textChannel = null
        finished = false

        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        abandonFocus()
    }

    private fun chunk(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= MAX_CHUNK_CHARS) return listOf(clean)

        val result = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + MAX_CHUNK_CHARS, clean.length)
            if (end < clean.length) {
                val punctuation = clean.lastIndexOfAny(
                    charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';'),
                    end
                )
                if (punctuation > start + 80) end = punctuation + 1
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) result.add(piece)
            start = end
        }
        return result
    }
}
