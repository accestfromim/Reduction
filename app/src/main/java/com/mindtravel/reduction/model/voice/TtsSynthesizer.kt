package com.mindtravel.reduction.model.voice

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.settings.AppSettings
import kotlin.math.roundToInt

/** Both hosts feed the same 24 kHz mono PCM playback queue. The model produces 8 kHz floats. */
class TtsSynthesizer(
    private val cache: TtsCache,
    private val generate: (text: String, speaker: Int) -> GeneratedAudio
) {
    // A stopped reply may still be finishing native inference when the next one begins.
    @Synchronized
    fun synthesize(voice: String, text: String): ByteArray {
        val key = AppSettings.localVoice(voice)
        cache.get(key, text)?.let {
            Logs.add("Local TTS cache hit voice=$key")
            return it
        }
        if (text.none { it.isLetterOrDigit() }) return ByteArray(0)
        val audio = generate(spokenText(text), if (key == AppSettings.SECOND_VOICE) 1 else 0)
        Logs.add("Local TTS generated with $ENGINE_NAME voice=$key")
        val pcm = toPlaybackPcm(audio.samples, audio.sampleRate)
        cache.put(key, text, pcm)
        return pcm
    }

    companion object {
        const val MODEL_DIR = "tts-aishell3"
        const val ENGINE_NAME = "sherpa-onnx · AISHELL-3 VITS (CPU)"
        private const val PLAYBACK_RATE = 24_000

        fun rules(dir: String): String =
            listOf("phone", "date", "number").joinToString(",") { "$dir/$it.fst" }

        // The Mandarin lexicon has no Latin letters. Keep abbreviations such as API audible.
        private val letterNames = "诶 比 西 迪 伊 艾弗 吉 艾尺 艾 杰 开 艾勒 艾姆 恩 欧 皮 丘 阿尔 艾丝 提 优 维 达布柳 艾克斯 歪 泽德".split(' ')

        private fun spokenText(text: String) = buildString {
            for (ch in text) {
                val index = ch.lowercaseChar() - 'a'
                if (ch.code < 128 && index in letterNames.indices) append(letterNames[index]) else append(ch)
            }
        }

        private fun toPlaybackPcm(samples: FloatArray, rate: Int): ByteArray {
            if (samples.isEmpty()) return ByteArray(0)
            require(rate > 0) { "本地朗读模型的采样率无效" }
            val frames = ((samples.size.toLong() * PLAYBACK_RATE + rate / 2) / rate).toInt()
            val pcm = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val position = i.toLong() * rate
                val left = (position / PLAYBACK_RATE).toInt()
                val right = minOf(left + 1, samples.lastIndex)
                val fraction = (position % PLAYBACK_RATE).toFloat() / PLAYBACK_RATE
                val value = samples[left] + (samples[right] - samples[left]) * fraction
                val sample = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                pcm[i * 2] = sample.toByte()
                pcm[i * 2 + 1] = (sample shr 8).toByte()
            }
            return pcm
        }
    }
}
