package com.mindtravel.deepseekassistant.api.voice

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.core.audio.AudioGateway
import com.mindtravel.deepseekassistant.api.DeepSeekHttp
import org.json.JSONObject

class DeepSeekAudioApi(private val http: DeepSeekHttp) : AudioGateway {

    override fun transcribe(apiKey: String, wav: ByteArray): String {
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

    override fun synthesize(apiKey: String, text: String, voice: String): ByteArray {
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
