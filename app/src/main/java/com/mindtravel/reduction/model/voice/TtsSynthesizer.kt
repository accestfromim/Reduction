package com.mindtravel.reduction.model.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.audio.AudioGateway

class TtsSynthesizer(
    private val audioApi: AudioGateway,
    private val cache: TtsCache
) {
    fun synthesize(apiKey: String, voice: String, text: String): ByteArray {
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
}
