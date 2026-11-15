package com.mindtravel.deepseekassistant.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.mindtravel.deepseekassistant.R
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.events.EventSink

class QuickAckPlayer(
    private val context: Context,
    private val emitter: EventSink
) {

    private var current: MediaPlayer? = null

    fun playWakeAck(): Boolean = play(R.raw.ack_wake, notifyWake = true)

    fun playAck(text: String): Boolean {
        if (text.trim() != "好的") return false
        return play(R.raw.ack_done, notifyWake = false)
    }

    fun stop() {
        val player = synchronized(this) {
            val active = current
            current = null
            active
        }
        release(player)
    }

    fun shutdown() = stop()

    private fun play(resId: Int, notifyWake: Boolean): Boolean {
        stop()
        val player = try {
            MediaPlayer.create(context, resId)
        } catch (t: Throwable) {
            Logs.add("Quick ack create failed: ${t.message}")
            null
        }
        if (player == null) {
            if (notifyWake) emitter.emit("onWakeAckDone", "")
            return false
        }
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setOnCompletionListener { finish(player, notifyWake) }
        player.setOnErrorListener { _, _, _ ->
            finish(player, notifyWake)
            true
        }
        synchronized(this) { current = player }
        return try {
            player.start()
            true
        } catch (t: Throwable) {
            Logs.add("Quick ack start failed: ${t.message}")
            finish(player, notifyWake)
            false
        }
    }

    private fun finish(player: MediaPlayer, notifyWake: Boolean) {
        synchronized(this) {
            if (current === player) current = null
        }
        release(player)
        if (notifyWake) emitter.emit("onWakeAckDone", "")
    }

    private fun release(player: MediaPlayer?) {
        if (player == null) return
        runCatching { player.stop() }
        runCatching { player.release() }
    }
}
