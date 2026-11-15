package com.mindtravel.deepseekassistant.feature.voice.model

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SpeculativeAsr(
    private val audioGateway: AudioGateway,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private var generation = 0
    private var result: String? = null
    private var failed = false
    private var finalizing = false

    fun start(apiKey: String, wav: ByteArray) {
        if (wav.isEmpty() || apiKey.isBlank()) return

        var current = 0
        synchronized(this) {
            if (!finalizing) {
                generation++
                current = generation
                job?.cancel()
                job = null
                result = null
                failed = false
            }
        }
        if (current == 0) return

        Logs.add("Speculative ASR start bytes=${wav.size}")
        val launched = scope.launch(Dispatchers.IO) {
            try {
                val text = audioGateway.transcribe(apiKey, wav)
                synchronized(this@SpeculativeAsr) {
                    if (current == generation && !finalizing) {
                        result = text
                        Logs.add("Speculative ASR ready chars=${text.length}")
                    }
                }
            } catch (t: Throwable) {
                synchronized(this@SpeculativeAsr) {
                    if (current == generation && !finalizing) failed = true
                }
                Logs.add("Speculative ASR failed: ${t.message}")
            } finally {
                synchronized(this@SpeculativeAsr) {
                    if (current == generation) job = null
                }
            }
        }
        synchronized(this) {
            if (current == generation && !finalizing) {
                job = launched
            } else {
                launched.cancel()
            }
        }
    }

    fun invalidate() {
        synchronized(this) {
            generation++
            job?.cancel()
            job = null
            result = null
            failed = false
        }
    }

    fun reset() {
        synchronized(this) {
            generation++
            finalizing = false
            job?.cancel()
            job = null
            result = null
            failed = false
        }
    }

    suspend fun finish(apiKey: String, wav: ByteArray): String {
        var pending: Job? = null
        var ready: String? = null
        synchronized(this) {
            finalizing = true
            pending = job
            ready = result
        }

        if (ready == null && pending != null) {
            val completed = withTimeoutOrNull(1_500L) { pending.join() }
            if (completed == null) Logs.add("Speculative ASR wait timeout")
            synchronized(this) {
                ready = result
            }
        }

        if (!ready.isNullOrBlank()) {
            Logs.add("Speculative ASR hit")
            reset()
            return ready
        }

        Logs.add("Speculative ASR miss failed=$failed")
        reset()
        return audioGateway.transcribe(apiKey, wav)
    }
}
