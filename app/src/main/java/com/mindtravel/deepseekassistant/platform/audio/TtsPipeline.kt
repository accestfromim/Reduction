package com.mindtravel.deepseekassistant.platform.audio

import com.mindtravel.deepseekassistant.core.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class TtsPipeline(
    private val synthesizer: TtsSynthesizer,
    private val playback: TtsPlayback,
    private val scope: CoroutineScope
) {
    companion object {
        private const val END = "\u0000END\u0000"
        private const val MAX_CHUNK_CHARS = 200
    }

    private var job: Job? = null
    private var textChannel: Channel<String>? = null
    private var session = 0
    private var finished = false
    private var apiKey = ""
    private var voice = "stella"

    fun startSession(
        apiKey: String,
        voice: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val current = ++session
        this.apiKey = apiKey
        this.voice = voice
        this.finished = false

        val texts = Channel<String>(Channel.UNLIMITED)
        textChannel = texts

        playback.start(
            onStart = { if (current == session) onStart() },
            onDone = { if (current == session) onDone() },
            onError = { message ->
                if (current == session) {
                    Logs.add("TTS playback failed: $message")
                    fail(current, message, onError)
                }
            }
        )

        job = scope.launch(Dispatchers.IO) {
            try {
                for (text in texts) {
                    if (current != session) return@launch
                    if (text == END) break
                    if (text.isBlank()) continue
                    val pcm = synthesizer.synthesize(apiKey, voice, text)
                    if (current != session) return@launch
                    if (!playback.send(pcm)) return@launch
                }
                if (current == session) playback.finish()
            } catch (t: Throwable) {
                if (current == session) {
                    Logs.add("TTS synthesis failed: ${t.message}")
                    fail(current, t.message ?: "TTS 失败", onError)
                }
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

    fun stop() {
        session++
        job?.cancel()
        job = null
        textChannel?.close()
        textChannel = null
        finished = false
        playback.stop()
    }

    private fun fail(current: Int, message: String, onError: (String) -> Unit) {
        if (current != session) return
        session++
        job?.cancel()
        job = null
        textChannel?.close()
        textChannel = null
        playback.stop()
        scope.launch(Dispatchers.Main) { onError(message) }
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
                val sentence = clean.lastIndexOfAny(
                    charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';'),
                    end
                )
                val clause = clean.lastIndexOfAny(
                    charArrayOf('，', ',', '、', '：', ':'),
                    end
                )
                if (sentence > start + 40) {
                    end = sentence + 1
                } else if (clause > start + 40) {
                    end = clause + 1
                }
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) result.add(piece)
            start = end
        }
        return result
    }
}
