package com.mindtravel.deepseekassistant.platform.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.mindtravel.deepseekassistant.core.Logs

class WakeWordDetector(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onListening: () -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = 16_000
    }

    private var kws: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        worker = Thread { runLoop() }.also { it.start() }
    }

    fun stop() {
        running = false
        worker?.let { runCatching { it.join(1_000) } }
        worker = null
        releaseAudio()
        Logs.add("Wake word listening stopped")
    }

    fun release() {
        stop()
        kws?.let { runCatching { it.release() } }
        kws = null
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        var currentStream: OnlineStream? = null
        var recorder: AudioRecord? = null
        var detected = false
        try {
            val spotter = ensureKws() ?: return
            currentStream = spotter.createStream("")
            if (currentStream.ptr == 0L) {
                onError("唤醒词流创建失败")
                return
            }
            stream = currentStream

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, SAMPLE_RATE / 5 * 2)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                onError("唤醒词麦克风初始化失败")
                return
            }
            audioRecord = recorder
            recorder.startRecording()
            onListening()
            Logs.add("Wake word listening started")

            val buffer = ShortArray(SAMPLE_RATE / 10)
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                val samples = FloatArray(read) { buffer[it] / 32768.0f }
                currentStream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                while (spotter.isReady(currentStream)) {
                    spotter.decode(currentStream)
                    val keyword = spotter.getResult(currentStream).keyword
                    if (keyword.isNotBlank()) {
                        spotter.reset(currentStream)
                        detected = true
                        Logs.add("Wake word detected: $keyword")
                        onWake()
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            Logs.add("Wake word loop error: ${t.message}")
            onError(t.message ?: "唤醒词监听失败")
        } finally {
            recorder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            if (audioRecord === recorder) audioRecord = null
            currentStream?.let { runCatching { it.release() } }
            if (stream === currentStream) stream = null
            running = false
            if (!detected) Logs.add("Wake word loop finished")
        }
    }

    private fun ensureKws(): KeywordSpotter? {
        kws?.let { return it }
        return try {
            val feature = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)
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
            Logs.add("Wake word model failed: ${t.message}")
            onError("唤醒词模型加载失败")
            null
        }
    }

    private fun releaseAudio() {
        audioRecord?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        audioRecord = null
        stream?.let { runCatching { it.release() } }
        stream = null
    }
}
