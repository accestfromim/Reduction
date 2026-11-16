package com.mindtravel.reduction.model.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.model.NativeModels

/**
 * Local keyword spotter: 16 kHz PCM frames in, detected keyword out. [language] picks
 * the model: each one can only spot words of its own language.
 */
class KeywordSpotterModel(private val context: Context, private val language: Language) {

    /**
     * [boost] favours the keyword's path while decoding; [threshold] is how sure the model
     * must be before it reports the keyword.
     */
    enum class Language(val dir: String, val boost: Float, val threshold: Float) {
        /**
         * Spots English phrases. Its keywords file holds the wake phrase "Hi Reduction" — with
         * "hi" also accepted as "high", which is how a clipped "hi" is often heard — and the
         * name on its own, for the phrase said with a pause. Measured on synthesized speech from
         * 30 voices: the phrase is found about nine times in ten whether or not there is a
         * pause, and unrelated English or Chinese sentences trigger nothing.
         */
        ENGLISH("kws-en", boost = 3.0f, threshold = 0.1f),

        /** Spots Chinese words, written as pinyin syllables. */
        CHINESE("kws", boost = 1.5f, threshold = 0.25f)
    }

    private var kws: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    /**
     * Loads the model if needed and opens a fresh stream. [keywords] replaces the ones in
     * the model's keywords file for this stream; empty keeps them. Throws when the model cannot be loaded.
     */
    fun open(keywords: String = "") {
        close()
        val spotter = kws ?: load().also { kws = it }
        val created = spotter.createStream(keywords)
        check(created.ptr != 0L) { "唤醒词流创建失败" }
        stream = created
    }

    /** Returns the keyword when one is detected in the audio fed so far, otherwise null. */
    fun accept(frame: ShortArray, sampleRate: Int): String? {
        val spotter = kws ?: return null
        val current = stream ?: return null
        val samples = FloatArray(frame.size) { frame[it] / 32768.0f }
        current.acceptWaveform(samples, sampleRate = sampleRate)
        while (spotter.isReady(current)) {
            spotter.decode(current)
            val keyword = spotter.getResult(current).keyword
            if (keyword.isNotBlank()) {
                spotter.reset(current)
                return keyword
            }
        }
        return null
    }

    fun close() {
        stream?.let { runCatching { it.release() } }
        stream = null
    }

    fun release() {
        close()
        kws?.let { runCatching { it.release() } }
        kws = null
    }

    private fun load(): KeywordSpotter {
        check(NativeModels.available) { "此设备不支持本地关键词模型" }
        val model = OnlineModelConfig(
            // Named without the epochs each model was trained for: tools/models/fetch-models.sh puts
            // whatever build the configuration there names under these three names.
            transducer = OnlineTransducerModelConfig(
                encoder = "${language.dir}/encoder.onnx",
                decoder = "${language.dir}/decoder.onnx",
                joiner = "${language.dir}/joiner.onnx"
            ),
            tokens = "${language.dir}/tokens.txt",
            // The model is tiny: a second thread costs several times the CPU for no gain.
            numThreads = 1,
            modelType = "zipformer2"
        )
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = model,
            keywordsFile = "${language.dir}/keywords.txt",
            keywordsScore = language.boost,
            keywordsThreshold = language.threshold
        )
        return KeywordSpotter(assetManager = context.assets, config = config).also {
            Logs.add("Keyword model loaded: ${language.dir}")
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
