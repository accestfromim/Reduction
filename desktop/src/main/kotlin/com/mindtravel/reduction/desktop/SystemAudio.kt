package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.hardware.audio.SpeechOutput
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The computer's own loudspeakers and microphone, reached through the PulseAudio client
 * tool `pacat`: one process that plays whatever PCM it is fed, and one, while recording,
 * that hands back what the microphone hears. No browser is involved in either.
 */
class SystemAudio private constructor(private val pacat: File, private val libraries: String) {

    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "reduction-audio").apply { isDaemon = true } }

    // ── playing ─────────────────────────────────────────────────────────────

    private var player: Process? = null
    private var line: OutputStream? = null

    /** When everything handed over so far will have been heard, on the system clock. */
    private var heardBy = 0L

    /** Plays [pcm] (24 kHz mono, 16 bit) after whatever is already sounding. */
    @Synchronized
    fun play(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        try {
            val out = line ?: start(listOf("--playback", "--rate=$PLAY_RATE", "--latency-msec=80")).let { process ->
                player = process
                process.outputStream.also { line = it }
            }
            out.write(pcm)
            out.flush()
            heardBy = maxOf(System.currentTimeMillis(), heardBy) + pcm.size / 2 * 1000L / PLAY_RATE
        } catch (t: Throwable) {
            Logs.add("Speaker failed: ${t.message}")
            silence()
        }
    }

    /** How long until what has been handed over has been heard. */
    @Synchronized
    fun remainingMillis(): Long = (heardBy - System.currentTimeMillis()).coerceAtLeast(0) + OUTPUT_LAG_MS

    /** Stops the sound at once, dropping what has not been heard yet. */
    @Synchronized
    fun silence() {
        runCatching { line?.close() }
        player?.destroyForcibly()
        player = null
        line = null
        heardBy = 0
    }

    /** Speech for the conversation: pieces in order, with word of when they start and end. */
    inner class Speech(private val onMain: (() -> Unit) -> Unit) : SpeechOutput {
        private var session = 0
        private var started = false
        private var onStart: () -> Unit = {}
        private var onDone: () -> Unit = {}
        private var ending: ScheduledFuture<*>? = null

        @Synchronized
        override fun start(onStart: () -> Unit, onDone: () -> Unit) {
            stop()
            session++
            started = false
            this.onStart = onStart
            this.onDone = onDone
        }

        override suspend fun send(pcm: ByteArray): Boolean {
            val current = synchronized(this) { session }
            // Not too far ahead of the ear: what is queued cannot be taken back piece by piece.
            while (remainingMillis() > AHEAD_MS) {
                kotlinx.coroutines.delay(100)
                if (synchronized(this) { session } != current) return false
            }
            synchronized(this) {
                if (session != current) return false
                play(pcm)
                if (!started) {
                    started = true
                    val begin = onStart
                    onMain { begin() }
                }
            }
            return true
        }

        @Synchronized
        override fun finish() {
            val current = session
            val done = onDone
            ending = timer.schedule({
                if (synchronized(this) { session } == current) onMain { done() }
            }, remainingMillis(), TimeUnit.MILLISECONDS)
        }

        @Synchronized
        override fun stop() {
            session++
            ending?.cancel(false)
            ending = null
            silence()
        }
    }

    // ── recording ───────────────────────────────────────────────────────────

    private var recorder: Process? = null
    private var heard: ByteArrayOutputStream? = null

    /**
     * Opens the microphone and returns once it is really delivering sound — a microphone that has
     * been resting takes up to a second to wake, and saying "listening" before then would lose the
     * first words. What it hears is kept until [stopRecording].
     */
    @Synchronized
    fun startRecording(): Boolean {
        cancelRecording()
        return try {
            // Asked for in small pieces: otherwise the first half second of what is said is lost while the first large one fills.
            val process = start(listOf("--record", "--rate=$RECORD_RATE", "--latency-msec=20", "--process-time-msec=10"))
            val buffer = ByteArrayOutputStream()
            val awake = java.util.concurrent.CountDownLatch(1)
            recorder = process
            heard = buffer
            Thread({
                runCatching {
                    val chunk = ByteArray(3200)
                    while (true) {
                        val read = process.inputStream.read(chunk)
                        if (read < 0) break
                        synchronized(buffer) { buffer.write(chunk, 0, read) }
                        awake.countDown()
                    }
                }
                awake.countDown()
            }, "reduction-microphone").apply { isDaemon = true }.start()
            awake.await(WAKE_LIMIT_MS, TimeUnit.MILLISECONDS) && process.isAlive
        } catch (t: Throwable) {
            Logs.add("Microphone failed: ${t.message}")
            false
        }
    }

    /** Closes the microphone and returns the recording as a 16 kHz mono WAV file; null when there is none. */
    @Synchronized
    fun stopRecording(): ByteArray? {
        val buffer = heard ?: return null
        cancelRecording()
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return if (pcm.size < RECORD_RATE / 5 * 2) null else wav(pcm, RECORD_RATE)
    }

    @Synchronized
    fun cancelRecording() {
        recorder?.destroyForcibly()
        recorder = null
        heard = null
    }

    private fun start(mode: List<String>): Process {
        val command = listOf(pacat.path) + mode + listOf("--raw", "--format=s16le", "--channels=1", "--client-name=Reduction")
        return ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
            if (libraries.isNotEmpty()) environment()["LD_LIBRARY_PATH"] = libraries
        }.start()
    }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        const val PLAY_RATE = 24_000
        const val RECORD_RATE = 16_000

        /** What the sound system holds back before a sample reaches the loudspeaker. */
        private const val OUTPUT_LAG_MS = 150L
        private const val AHEAD_MS = 4_000L
        private const val WAKE_LIMIT_MS = 2_500L

        /**
         * The audio tool, where there is one: the system's own, or the copy `desktop/install.sh`
         * keeps under [dataDir]. Null when the sound system cannot be reached — then the page
         * in the browser has to do the sounding and listening.
         */
        fun find(dataDir: File): SystemAudio? {
            val own = File(System.getenv("REDUCTION_AUDIO_DIR") ?: File(dataDir, "audio").path)
            val bundled = File(own, "usr/bin/pacat")
            val lib = File(own, "usr/lib/x86_64-linux-gnu")
            val candidates = listOf(bundled to "${lib.path}:${lib.path}/pulseaudio") +
                System.getenv("PATH").orEmpty().split(':').map { File(it, "pacat") to "" }
            for ((tool, libraries) in candidates) {
                if (!tool.canExecute()) continue
                val audio = SystemAudio(tool, libraries)
                // A tool that cannot reach the sound server leaves at once.
                val probe = runCatching { audio.start(listOf("--playback", "--rate=$PLAY_RATE")) }.getOrNull() ?: continue
                Thread.sleep(400)
                val alive = probe.isAlive
                probe.destroyForcibly()
                if (alive) return audio
            }
            return null
        }
    }
}
