package com.mindtravel.reduction.model.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

class AndroidTts(assets: AssetManager) {
    private val engine by lazy {
        val dir = TtsSynthesizer.MODEL_DIR
        val vits = OfflineTtsVitsModelConfig(model = "$dir/model.onnx", lexicon = "$dir/lexicon.txt", tokens = "$dir/tokens.txt")
        OfflineTts(assets, OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = 2, provider = "cpu"),
            ruleFsts = TtsSynthesizer.rules(dir)
        ))
    }

    fun generate(text: String, speaker: Int): GeneratedAudio = engine.generate(text, speaker)
}
