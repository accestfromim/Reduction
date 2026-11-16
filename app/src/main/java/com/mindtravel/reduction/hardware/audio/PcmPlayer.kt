package com.mindtravel.reduction.hardware.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.mindtravel.reduction.core.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Plays speech as it arrives: PCM pieces go into the audio queue a few at a time, so
 * speech takes its turn behind whatever is already playing.
 */
class PcmPlayer(
    context: Context,
    private val queue: AudioQueue,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var job: Job? = null
    private var channel: Channel<ByteArray>? = null
    private var owner: Any? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var session = 0

    /** Begins a stretch of speech. [onStart] and [onDone] arrive on the main thread. */
    fun start(onStart: () -> Unit, onDone: () -> Unit) {
        stop()
        val current = ++session
        val audio = Channel<ByteArray>(capacity = QUEUED_AHEAD)
        val mine = Any()
        channel = audio
        owner = mine
        job = scope.launch(Dispatchers.IO) {
            val slots = Semaphore(QUEUED_AHEAD)
            var pieces = 0
            for (pcm in audio) {
                if (pcm.isEmpty()) continue
                slots.acquire()
                if (current != session) return@launch
                val first = pieces++ == 0
                if (first) requestFocus()
                queue.play(
                    pcm = pcm,
                    owner = mine,
                    onStart = if (first) ({ if (current == session) onStart() }) else null,
                    onDone = { slots.release() }
                )
            }
            // Getting every slot back means everything queued has been played.
            repeat(QUEUED_AHEAD) { slots.acquire() }
            if (current == session) {
                Logs.add("Speech played pieces=$pieces")
                abandonFocus()
                withContext(Dispatchers.Main) { if (current == session) onDone() }
            }
        }
    }

    /** Hands over PCM, waiting while enough is already queued. Returns false once playback has stopped. */
    suspend fun send(pcm: ByteArray): Boolean {
        val audio = channel ?: return false
        return try {
            audio.send(pcm)
            true
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            false
        }
    }

    /** No more PCM is coming; onDone follows when what was sent has been played. */
    fun finish() {
        channel?.close()
    }

    /** Silences the speech now. No callback follows. */
    fun stop() {
        if (owner != null) Logs.add("Speech stopped by the app")
        session++
        job?.cancel()
        job = null
        channel?.close()
        channel = null
        owner?.let { queue.drop(it) }
        owner = null
        abandonFocus()
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change -> Logs.add("Speech audio focus change=$change") }
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { request -> runCatching { audioManager.abandonAudioFocusRequest(request) } }
        focusRequest = null
    }

    private companion object {
        /** Pieces handed to the queue ahead of the one playing. */
        const val QUEUED_AHEAD = 3
    }
}
