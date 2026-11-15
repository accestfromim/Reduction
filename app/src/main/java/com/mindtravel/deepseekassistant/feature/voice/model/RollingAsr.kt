package com.mindtravel.deepseekassistant.feature.voice.model

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RollingAsr(
    private val audioGateway: AudioGateway,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    @Volatile
    private var token = 0

    fun start(
        apiKey: String,
        snapshot: () -> ByteArray?,
        onResult: (text: String?, snapshotAt: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val current = ++token
        job = scope.launch(Dispatchers.IO) {
            while (isActive && current == token) {
                delay(1_000L)
                val snapshotAt = System.currentTimeMillis()
                val wav = snapshot()
                if (wav == null || wav.isEmpty()) continue
                try {
                    val text = audioGateway.transcribe(apiKey, wav)
                    if (!isActive || current != token) break
                    withContext(Dispatchers.Main) { onResult(text, snapshotAt) }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    if (current != token) break
                    Logs.add("Rolling ASR failed: ${t.message}")
                    withContext(Dispatchers.Main) { onError(t.message ?: "识别失败") }
                }
            }
        }
    }

    fun stop() {
        token++
        job?.cancel()
        job = null
    }
}
