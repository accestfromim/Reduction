package com.mindtravel.deepseekassistant.platform.wake

import android.content.Context
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.platform.audio.PcmCapture

class WakeWordDetector(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onListening: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var kws: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private val capture = PcmCapture()

    @Volatile
    private var detected = false

    fun start() {
        if (capture.isRunning()) return
        detected = false
        val spotter = ensureKws() ?: return
        val currentStream = spotter.createStream("")
        if (currentStream.ptr == 0L) {
            onError("唤醒词流创建失败")
            return
        }
        stream = currentStream
        onListening()
        Logs.add("Wake word listening started")

        val started = capture.start(
            source = MediaRecorder.AudioSource.MIC,
            onFrame = { frame ->
                if (!detected) {
                    val samples = FloatArray(frame.size) { frame[it] / 32768.0f }
                    currentStream.acceptWaveform(samples, sampleRate = PcmCapture.SAMPLE_RATE)
                    while (spotter.isReady(currentStream)) {
                        spotter.decode(currentStream)
                        val keyword = spotter.getResult(currentStream).keyword
                        if (keyword.isNotBlank()) {
                            spotter.reset(currentStream)
                            detected = true
                            Logs.add("Wake word detected: $keyword")
                            capture.requestStop()
                            onWake()
                            break
                        }
                    }
                }
            },
            onError = { message ->
                Logs.add("Wake word loop error: $message")
                if (!detected) onError(message)
            }
        )
        if (!started) onError("无法启动唤醒词麦克风")
    }

    fun stop() {
        capture.stop()
        releaseStream()
        Logs.add("Wake word listening stopped")
    }

    fun release() {
        stop()
        kws?.let { runCatching { it.release() } }
        kws = null
    }

    private fun releaseStream() {
        stream?.let { runCatching { it.release() } }
        stream = null
    }

    private fun ensureKws(): KeywordSpotter? {
        kws?.let { return it }
        return try {
            val feature = FeatureConfig(sampleRate = PcmCapture.SAMPLE_RATE, featureDim = 80)
            val model = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    decoder = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    joiner = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),
                tokens = "kws/tokens.txt",
                numThreads = 2,
                modelType = "zipformer2"
            )
            val config = KeywordSpotterConfig(
                featConfig = feature,
                modelConfig = model,
                keywordsFile = "kws/keywords.txt",
                keywordsScore = 1.5f,
                keywordsThreshold = 0.25f
            )
            KeywordSpotter(assetManager = context.assets, config = config).also {
                kws = it
                Logs.add("Wake word model loaded")
            }
        } catch (t: Throwable) {
            Logs.add("Wake word init error: ${t.message}")
            onError(t.message ?: "唤醒词初始化失败")
            null
        }
    }
}
