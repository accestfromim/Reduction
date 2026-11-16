package com.mindtravel.reduction.hardware.audio

/** One output stream. Bytes are heard in the order they are written. */
interface PcmSink {
    /**
     * Blocks until the bytes are accepted. Returns how many were, or a negative error.
     * The bytes are mono unless [stereo], in which case they are left and right in turn.
     */
    fun write(data: ByteArray, offset: Int, length: Int, stereo: Boolean = false): Int

    /** How many frames have been played since the last [flush]. */
    fun playedFrames(): Long

    /** Throws away everything written but not yet played. */
    fun flush()

    fun release()
}

/**
 * Everything the app plays goes through this one queue into one stream: cues, the
 * recording hum, speech. Sounds are heard in the order they were queued, each starting
 * on the sample after the previous one ended — there is nothing to line up, so nothing
 * can overlap or leave a gap.
 *
 * All audio is 16-bit PCM at the stream's rate: mono, except that the loop may be stereo.
 * Callbacks are handed to [deliver].
 */
class AudioQueue(
    private val sink: PcmSink,
    private val deliver: (() -> Unit) -> Unit
) {
    private class Item(
        val pcm: ByteArray,
        val owner: Any?,
        val onStart: (() -> Unit)?,
        val onDone: (() -> Unit)?
    )

    private class Written(val endFrame: Long, val item: Item)

    private val lock = Object()
    private val queue = ArrayDeque<Item>()

    /** Written to the stream, not yet fully played. */
    private val unplayed = ArrayDeque<Written>()
    private var writing: Item? = null
    private var loop: ByteArray? = null
    private var loopStereo = false
    private var loopAt = 0
    private var written = 0L

    /** Bumped when audio already handed to the stream is cancelled. */
    private var epoch = 0
    private var needFlush = false

    /** The stream has run dry; whatever is left in it is stale silence. */
    private var rested = true
    private var running = true
    private var thread: Thread? = null

    /**
     * Queues a sound. [onStart] runs when its first sample is written; [onDone] runs
     * exactly once, when its last sample has been played or when it is dropped.
     */
    fun play(pcm: ByteArray, owner: Any? = null, onStart: (() -> Unit)? = null, onDone: (() -> Unit)? = null) {
        synchronized(lock) {
            queue.addLast(Item(pcm, owner, onStart, onDone))
            wake()
        }
    }

    /** A sound repeated whenever nothing is queued, until set to null. It follows queued sounds without a gap. */
    fun setLoop(pcm: ByteArray?, gain: Float = 1f, stereo: Boolean = false) {
        synchronized(lock) {
            loop = pcm?.let { scale(it, gain) }
            loopStereo = stereo
            loopAt = 0
            wake()
        }
    }

    /** Removes everything [owner] queued. If any of it is sounding or about to, the stream is silenced now. */
    fun drop(owner: Any) = cut { it.owner === owner }

    /** Silences everything now: queued sounds, the loop, and what is already on its way out. */
    fun clear() {
        synchronized(lock) { loop = null }
        cut { true }
    }

    /** How many frames the stream has played since it was last emptied. For diagnostics. */
    fun playedFrames(): Long = sink.playedFrames()

    fun shutdown() {
        synchronized(lock) {
            running = false
            lock.notifyAll()
        }
    }

    private fun cut(matches: (Item) -> Boolean) {
        val callbacks = ArrayList<() -> Unit>()
        synchronized(lock) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (matches(item)) {
                    iterator.remove()
                    item.onDone?.let(callbacks::add)
                }
            }
            val current = writing
            val sounding = unplayed.any { matches(it.item) } || (current != null && matches(current))
            if (sounding) {
                // The stream cannot drop one sound and keep another: everything in it goes.
                epoch++
                needFlush = true
                if (current != null) {
                    current.onDone?.let(callbacks::add)
                    writing = null
                }
            }
            lock.notifyAll()
        }
        callbacks.forEach(deliver)
    }

    private fun wake() {
        if (thread == null && running) {
            thread = Thread({ run() }, "audio-queue").apply {
                isDaemon = true
                start()
            }
        }
        lock.notifyAll()
    }

    private fun run() {
        val silence = ByteArray(BLOCK_BYTES)
        while (true) {
            val callbacks = ArrayList<() -> Unit>()
            var item: Item? = null
            var block: ByteArray? = null
            var blockAt = 0
            var blockLength = 0
            var myEpoch = 0
            var blockStereo = false

            synchronized(lock) {
                if (!running) {
                    sink.release()
                    return
                }
                if (needFlush) {
                    needFlush = false
                    discard(callbacks)
                }
                collectPlayed(callbacks)
                myEpoch = epoch
                item = queue.removeFirstOrNull()
                val repeated = loop
                when {
                    item != null -> writing = item
                    repeated != null -> {
                        block = repeated
                        blockAt = loopAt
                        blockStereo = loopStereo
                        blockLength = minOf(if (loopStereo) BLOCK_BYTES * 2 else BLOCK_BYTES, repeated.size - loopAt)
                        loopAt = (loopAt + blockLength) % repeated.size
                    }
                    // Silence keeps the stream moving until the last sound has been played out.
                    unplayed.isNotEmpty() -> {
                        block = silence
                        blockLength = BLOCK_BYTES
                    }
                    callbacks.isEmpty() -> {
                        rested = true
                        lock.wait()
                    }
                }
                if ((item != null || block != null) && rested) {
                    // Start on an empty stream, so the sound is not held up behind stale silence.
                    rested = false
                    discard(callbacks)
                }
            }

            callbacks.forEach(deliver)
            val sound = item
            val filler = block
            if (sound != null) {
                sound.onStart?.let(deliver)
                writeItem(sound, myEpoch)
            } else if (filler != null) {
                val accepted = sink.write(filler, blockAt, blockLength, blockStereo)
                val frameBytes = if (blockStereo) FRAME_BYTES * 2 else FRAME_BYTES
                synchronized(lock) { if (accepted > 0 && epoch == myEpoch) written += accepted / frameBytes }
            }
        }
    }

    private fun writeItem(item: Item, myEpoch: Int) {
        var offset = 0
        while (offset < item.pcm.size) {
            synchronized(lock) { if (epoch != myEpoch || !running) return }
            val length = minOf(BLOCK_BYTES, item.pcm.size - offset)
            val accepted = sink.write(item.pcm, offset, length)
            if (accepted <= 0) break
            offset += accepted
        }
        synchronized(lock) {
            if (epoch != myEpoch) return
            written += offset / FRAME_BYTES
            unplayed.addLast(Written(written, item))
            writing = null
        }
    }

    /** Empties the stream. Every sound that was in it is over. Call with the lock held. */
    private fun discard(callbacks: MutableList<() -> Unit>) {
        sink.flush()
        written = 0
        for (entry in unplayed) entry.item.onDone?.let(callbacks::add)
        unplayed.clear()
    }

    private fun collectPlayed(callbacks: MutableList<() -> Unit>) {
        if (unplayed.isEmpty()) return
        val played = sink.playedFrames()
        while (unplayed.isNotEmpty() && unplayed.first().endFrame <= played) {
            unplayed.removeFirst().item.onDone?.let(callbacks::add)
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val FRAME_BYTES = 2

        /** 20 ms: how often the queue looks for something new to play. */
        const val BLOCK_BYTES = SAMPLE_RATE / 50 * FRAME_BYTES

        /** Returns [pcm] with every sample multiplied by [gain]. */
        fun scale(pcm: ByteArray, gain: Float): ByteArray {
            if (gain == 1f) return pcm
            val out = ByteArray(pcm.size)
            var i = 0
            while (i + 1 < pcm.size) {
                val sample = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
                val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i] = scaled.toByte()
                out[i + 1] = (scaled shr 8).toByte()
                i += 2
            }
            return out
        }
    }
}
