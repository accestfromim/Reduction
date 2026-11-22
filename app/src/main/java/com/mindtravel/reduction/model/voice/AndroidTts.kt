package com.mindtravel.reduction.model.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.WavPcm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal fun isInstalledChineseVoice(locale: Locale, requiresNetwork: Boolean, features: Set<String>?): Boolean =
    (locale.language == "zh" || locale.language == "cmn") &&
        !requiresNetwork && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty()

class AndroidTts(private val context: Context, private val cache: TtsCache) {
    suspend fun openVoice(): SpeechVoice {
        val session = Session()
        try {
            session.connect()
            return session
        } catch (t: Throwable) {
            session.close()
            throw t
        }
    }

    private inner class Session : SpeechVoice {
        private var engine: TextToSpeech? = null
        private var cacheKey = ""

        suspend fun connect() {
            val ready = CompletableDeferred<Int>()
            val tts = TextToSpeech(context) { ready.complete(it) }
            engine = tts
            if (withTimeoutOrNull(5_000) { ready.await() } != TextToSpeech.SUCCESS) {
                error("系统语音引擎未就绪，请检查安卓语音设置")
            }
            val voice = selectOfflineChineseVoice(tts) ?: error("请先在安卓语音设置中安装中文离线音色")
            val engineName = tts.defaultEngine
            cacheKey = "android-tts/$engineName/${voice.name}"
            Logs.add("TTS: 系统离线 · $engineName · ${voice.name}")
        }

        override suspend fun synthesize(text: String): ByteArray {
            if (text.none { it.isLetterOrDigit() }) return ByteArray(0)
            val tts = checkNotNull(engine)
            cache.get(cacheKey, text)?.let { return it }
            val pcm = render(tts, text)
            cache.put(cacheKey, text, pcm)
            return pcm
        }

        override fun close() {
            val tts = engine ?: return
            engine = null
            tts.shutdown()
        }
    }

    private fun selectOfflineChineseVoice(tts: TextToSpeech): Voice? {
        val preferred = tts.defaultVoice?.name
        return tts.voices.orEmpty()
            .filter { isInstalledChineseVoice(it.locale, it.isNetworkConnectionRequired, it.features) }
            .sortedWith(compareByDescending<Voice> { it.name == preferred }
                .thenByDescending { it.locale.country == "CN" }.thenByDescending { it.quality }.thenBy { it.name })
            .firstOrNull { tts.setVoice(it) == TextToSpeech.SUCCESS }
    }

    /** The system produces a WAV; playback stays on our queue so speech follows cues and can be interrupted. */
    private suspend fun render(tts: TextToSpeech, text: String): ByteArray {
        val file = File.createTempFile("system-tts-", ".wav", context.cacheDir)
        try {
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == file.name) done.complete(true) }
                @Deprecated("Required by UtteranceProgressListener")
                override fun onError(utteranceId: String?) { if (utteranceId == file.name) done.complete(false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == file.name) done.complete(false)
                }
            })
            check(tts.synthesizeToFile(text, Bundle(), file, file.name) == TextToSpeech.SUCCESS &&
                withTimeoutOrNull(30_000) { done.await() } == true) { "系统语音合成失败" }
            return toPlaybackPcm(WavPcm.parse(file.readBytes(), androidTts = true))
        } finally {
            file.delete()
        }
    }
    internal companion object {
        const val PLAYBACK_RATE = 24_000
        internal fun toPlaybackPcm(wav: WavPcm): ByteArray {
            if (wav.sampleRate == PLAYBACK_RATE && wav.channels == 1) return wav.data
            fun sample(at: Int) = (wav.data[at].toInt() and 0xff) or (wav.data[at + 1].toInt() shl 8)
            return resample(wav.frames, wav.sampleRate) { frame ->
                val offset = frame * wav.channels * 2
                val mono = if (wav.channels == 1) sample(offset).toFloat() else (sample(offset) + sample(offset + 2)) / 2f
                mono / Short.MAX_VALUE
            }
        }

        private inline fun resample(count: Int, rate: Int, sample: (Int) -> Float): ByteArray {
            if (count == 0) return ByteArray(0)
            require(rate > 0) { "本地朗读的采样率无效" }
            val frames = ((count.toLong() * PLAYBACK_RATE + rate / 2) / rate).toInt()
            val pcm = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val position = i.toLong() * rate
                val left = (position / PLAYBACK_RATE).toInt()
                val right = minOf(left + 1, count - 1)
                val fraction = (position % PLAYBACK_RATE).toFloat() / PLAYBACK_RATE
                val first = sample(left)
                val value = ((first + (sample(right) - first) * fraction).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
                pcm[i * 2] = value.toByte()
                pcm[i * 2 + 1] = (value shr 8).toByte()
            }
            return pcm
        }
    }
}
