package com.mindtravel.reduction.desktop

import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.mindtravel.reduction.model.voice.TtsSynthesizer
import java.io.File

class DesktopTts {
    private val engine by lazy {
        val jar = File(javaClass.protectionDomain.codeSource.location.toURI())
        val dir = System.getProperty("reduction.tts.dir")
            ?: File(jar.parentFile.parentFile, "models/${TtsSynthesizer.MODEL_DIR}").path
        val vits = OfflineTtsVitsModelConfig.builder()
            .setModel("$dir/model.onnx").setLexicon("$dir/lexicon.txt").setTokens("$dir/tokens.txt").build()
        val model = OfflineTtsModelConfig.builder().setVits(vits).setProvider("cpu").setNumThreads(2).build()
        OfflineTts(OfflineTtsConfig.builder().setModel(model).setRuleFsts(TtsSynthesizer.rules(dir)).build())
    }

    fun generate(text: String, speaker: Int): GeneratedAudio = engine.generate(text, speaker)
}
