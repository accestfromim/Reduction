package com.mindtravel.deepseekassistant.network.audio

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.network.DeepSeekHttp
import org.json.JSONObject

class AudioApi(private val http: DeepSeekHttp) {

    fun transcribe(apiKey: String, wav: ByteArray): String {
        val response = http.postMultipart(
            path = "/v1/tools/asr",
            apiKey = apiKey,
            fieldName = "file",
            fileName = "audio.wav",
            contentType = "audio/wav",
            bytes = wav
        )
        return response.stringOrNull("text")?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ApiException("没有识别到文字")
    }

    fun synthesize(apiKey: String, text: String, voice: String): ByteArray {
        if (text.isBlank()) throw ApiException("没有可朗读的文本")
        val body = JSONObject().apply {
            put("text", text)
            put("voice_id", if (voice == "tide") "tide" else "stella")
            put("format", "pcm")
            put("filter_markdown", true)
        }
        Logs.add("TTS request chars=${text.length} voice=$voice")
        val bytes = http.postBytes("/v1/tools/tts", apiKey, body)
        Logs.add("TTS response bytes=${bytes.size}")
        return bytes
    }
}
