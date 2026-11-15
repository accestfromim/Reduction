package com.mindtravel.deepseekassistant.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.mindtravel.deepseekassistant.R
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.events.EventSink

class QuickAckPlayer(
    private val context: Context,
    private val emitter: EventSink
) {

    private val main = Handler(Looper.getMainLooper())
    private var current: MediaPlayer? = null

    fun playWakeAck(): Boolean = onMain {
        Logs.add("Quick ack request wake")
        play(R.raw.ack_wake, notifyWake = true)
    }

    fun playAck(text: String): Boolean {
        if (text.trim() != "好的") return false
        return onMain {
            Logs.add("Quick ack request turn-end")
            play(R.raw.ack_done, notifyWake = false)
        }
    }

    fun stop() {
        onMain { stopInternal() }
    }

    fun shutdown() = stop()

    private fun onMain(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            main.post(block)
        }
        return true
    }

    private fun play(resId: Int, notifyWake: Boolean) {
        stopInternal()
        val player = try {
            MediaPlayer.create(context, resId)
        } catch (t: Throwable) {
            Logs.add("Quick ack create failed: ${t.message}")
            null
        }
        if (player == null) {
            Logs.add("Quick ack resource unavailable res=$resId")
            if (notifyWake) emitter.emit("onWakeAckDone", "")
            return
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setVolume(1f, 1f)
        player.setOnCompletionListener {
            Logs.add("Quick ack completed")
            finish(player, notifyWake)
        }
        player.setOnErrorListener { _, what, extra ->
            Logs.add("Quick ack error what=$what extra=$extra")
            finish(player, notifyWake)
            true
        }
        current = player
        try {
            player.start()
            Logs.add("Quick ack started res=$resId duration=${player.duration}ms")
        } catch (t: Throwable) {
            Logs.add("Quick ack start failed: ${t.message}")
            finish(player, notifyWake)
        }
    }

    private fun finish(player: MediaPlayer, notifyWake: Boolean) {
        if (current === player) current = null
        release(player)
        if (notifyWake) emitter.emit("onWakeAckDone", "")
    }

    private fun stopInternal() {
        val player = current ?: return
        current = null
        Logs.add("Quick ack stopped")
        release(player)
    }

    private fun release(player: MediaPlayer) {
        runCatching { player.stop() }
        runCatching { player.release() }
    }
}
