package com.mindtravel.reduction.hardware.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The queue's promise: sounds are heard in the order they were queued, back to back,
 * and each one's onDone runs exactly once — after it has been played, or when it is dropped.
 */
class AudioQueueTest {

    /**
     * A stream that plays [SPEED] times faster than real time and remembers what was
     * heard. Like the real one it holds a short buffer and makes the writer wait when full.
     */
    private class FakeSink : PcmSink {
        private val lock = Object()
        private val accepted = ArrayList<Byte>()
        private val heardBeforeFlush = ArrayList<Byte>()
        private var startedAt = 0L
        var flushes = 0
            private set

        private fun playedBytes(): Int {
            if (startedAt == 0L) return 0
            val elapsed = (System.nanoTime() - startedAt) / 1e9
            return minOf(accepted.size, (elapsed * AudioQueue.SAMPLE_RATE * SPEED).toInt() * 2)
        }

        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            synchronized(lock) {
                if (startedAt == 0L) startedAt = System.nanoTime()
                while (accepted.size - playedBytes() > BUFFER_BYTES) lock.wait(1)
                for (i in offset until offset + length) accepted += data[i]
            }
            return length
        }

        override fun playedFrames(): Long = synchronized(lock) { playedBytes() / 2L }

        override fun flush() {
            synchronized(lock) {
                flushes++
                heardBeforeFlush += accepted.subList(0, playedBytes())
                accepted.clear()
                startedAt = 0L
            }
        }

        override fun release() = Unit

        /** Everything that was actually heard, as a run-length summary: "A*480 H*960 B*480". Silence is "_". */
        fun heard(): String = synchronized(lock) {
            val all = heardBeforeFlush + accepted.subList(0, playedBytes())
            val out = ArrayList<String>()
            var i = 0
            while (i < all.size) {
                var j = i
                while (j < all.size && all[j] == all[i]) j++
                val name = if (all[i] == 0.toByte()) "_" else all[i].toInt().toChar().toString()
                out += "$name*${j - i}"
                i = j
            }
            out.joinToString(" ")
        }

        companion object {
            const val SPEED = 20
            const val BUFFER_BYTES = 7_200
        }
    }

    private val sink = FakeSink()
    private val events = Collections.synchronizedList(ArrayList<String>())
    private val queue = AudioQueue(sink) { it() }

    @After
    fun shutdown() = queue.shutdown()

    private fun sound(name: Char, millis: Int) = ByteArray(AudioQueue.SAMPLE_RATE * 2 * millis / 1000) { name.code.toByte() }

    private fun play(name: Char, millis: Int, owner: Any? = null): CountDownLatch {
        val done = CountDownLatch(1)
        queue.play(
            sound(name, millis),
            owner = owner,
            onStart = { events += "start $name" },
            onDone = {
                events += "done $name"
                done.countDown()
            }
        )
        return done
    }

    private fun CountDownLatch.awaitDone() = assertTrue("timed out; events=$events", await(5, TimeUnit.SECONDS))

    /** The names heard, in order, ignoring how long each lasted. */
    private fun order() = sink.heard().split(" ").filter { it.isNotBlank() }.map { it.substringBefore('*') }

    @Test
    fun soundsAreHeardInOrderBackToBack() {
        play('A', 400)
        play('B', 300)
        play('C', 200).awaitDone()

        assertEquals(listOf("start A", "done A", "start B", "done B", "start C", "done C").sorted(), events.sorted())
        assertEquals("each done follows its own start", listOf("done A", "done B", "done C"), events.filter { it.startsWith("done") })
        val heard = sink.heard()
        assertTrue(heard, heard.startsWith("A*19200 B*14400 C*9600"))
    }

    @Test
    fun theLoopStartsOnTheSampleAfterTheSoundBeforeIt() {
        val wake = play('W', 400)
        queue.setLoop(sound('H', 100))
        wake.awaitDone()
        Thread.sleep(30)

        val heard = sink.heard().split(" ")
        assertEquals("W*19200", heard[0])
        assertTrue("no silence between the cue and the loop: $heard", heard[1].startsWith("H*"))
        assertEquals(listOf("W", "H"), order())
    }

    @Test
    fun aCueFollowedByTheLoopThenACueThenSpeech() {
        // The whole of a hands-free turn, as the recording state plays it.
        play('W', 300).awaitDone()
        queue.setLoop(sound('H', 100))
        Thread.sleep(40)
        queue.setLoop(null)
        queue.clear()
        play('T', 300)
        play('1', 200)
        play('2', 200).awaitDone()

        val names = order().filter { it != "_" }
        assertEquals(listOf("W", "H", "T", "1", "2"), names)
        assertFalse("nothing is heard twice or out of turn", names.distinct().size != names.size)
    }

    @Test
    fun onDoneWaitsUntilTheSoundHasBeenPlayed() {
        val start = System.nanoTime()
        play('A', 1000).awaitDone()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // One second of audio at 20x takes 50 ms to play.
        assertTrue("done after ${elapsedMs}ms", elapsedMs >= 45)
    }

    @Test
    fun droppingAnOwnerSilencesItsSoundsAndFinishesThemOnce() {
        val speech = Any()
        val first = play('1', 2000, owner = speech)
        val second = play('2', 2000, owner = speech)
        Thread.sleep(20)
        queue.drop(speech)
        first.awaitDone()
        second.awaitDone()
        val cue = play('W', 200)
        cue.awaitDone()

        assertEquals(1, events.count { it == "done 1" })
        assertEquals(1, events.count { it == "done 2" })
        assertFalse("the dropped sound never started", events.contains("start 2"))
        val names = order().filter { it != "_" }
        assertEquals(listOf("1", "W"), names)
        assertTrue("the first sound was cut short: ${sink.heard()}", !sink.heard().contains("1*96000"))
    }

    @Test
    fun droppingOneOwnerLeavesAnothersQueuedSoundAlone() {
        val speech = Any()
        play('1', 2000, owner = speech)
        val cue = play('W', 200, owner = "cue")
        Thread.sleep(20)
        queue.drop(speech)
        cue.awaitDone()
        assertEquals(listOf("1", "W"), order().filter { it != "_" })
    }

    @Test
    fun clearSilencesEverythingIncludingTheLoop() {
        queue.setLoop(sound('H', 100))
        val queued = play('A', 2000)
        Thread.sleep(20)
        queue.clear()
        queued.awaitDone()
        // The writer finishes the 20 ms block in hand before it empties the stream.
        Thread.sleep(30)
        val before = sink.heard()
        Thread.sleep(60)
        assertEquals("nothing more is heard", before, sink.heard())
    }

    @Test
    fun aSoundAfterALongQuietStartsAtOnce() {
        play('A', 200).awaitDone()
        Thread.sleep(80)
        val start = System.nanoTime()
        play('B', 200).awaitDone()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 200 ms of audio at 20x is 10 ms; anything near that means it did not queue behind stale silence.
        assertTrue("took ${elapsedMs}ms", elapsedMs < 60)
        assertEquals(listOf("A", "B"), order().filter { it != "_" })
    }

    @Test
    fun everySoundFinishesExactlyOnceUnderRandomUse() {
        val random = java.util.Random(7)
        val owners = listOf(Any(), Any(), null)
        var queued = 0
        val all = ArrayList<CountDownLatch>()
        repeat(200) { i ->
            when (random.nextInt(10)) {
                0 -> queue.clear()
                1 -> owners[random.nextInt(2)]?.let { queue.drop(it) }
                2 -> queue.setLoop(if (random.nextBoolean()) sound('H', 60) else null)
                3 -> Thread.sleep(2)
                else -> {
                    queued++
                    val done = CountDownLatch(1)
                    queue.play(sound('A', 20 + random.nextInt(200)), owner = owners[random.nextInt(3)], onDone = {
                        events += "done $i"
                        done.countDown()
                    })
                    all += done
                }
            }
        }
        queue.setLoop(null)
        all.forEach { it.awaitDone() }
        assertEquals(queued, events.size)
        assertEquals("no sound finished twice", queued, events.distinct().size)
    }
}
