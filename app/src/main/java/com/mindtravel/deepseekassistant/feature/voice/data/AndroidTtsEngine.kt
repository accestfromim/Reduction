package com.mindtravel.deepseekassistant.feature.voice.data

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.feature.voice.model.TtsEngine
import com.mindtravel.deepseekassistant.platform.audio.TtsCache
import com.mindtravel.deepseekassistant.platform.audio.TtsPipeline

class AndroidTtsEngine(
    private val pipeline: TtsPipeline,
    private val cache: TtsCache
) : TtsEngine {

    override fun startSession(
        apiKey: String,
        voice: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        pipeline.startSession(apiKey, voice, onStart, onDone, onError)
    }

    override fun append(text: String) = pipeline.append(text)

    override fun finish() = pipeline.finish()

    override fun stop() = pipeline.stop()

    override fun clearCache(): String {
        val bytes = cache.clear()
        val mb = bytes / 1024.0 / 1024.0
        Logs.add("TTS cache cleared bytes=$bytes")
        return "已清理 %.1f MB 音频缓存".format(mb)
    }

    override fun cacheSizeMb(): Double = cache.size() / 1024.0 / 1024.0
}
