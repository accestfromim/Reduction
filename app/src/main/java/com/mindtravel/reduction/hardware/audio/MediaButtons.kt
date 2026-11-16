package com.mindtravel.reduction.hardware.audio

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.mindtravel.reduction.core.Logs

/**
 * Hears the play/pause button of a headset — on most earbuds, a single tap.
 *
 * Android sends that button to whichever app made sound last. This app speaks, so it is
 * often that app; it registers here as one that takes the button, and reports each press
 * through [onPress]. After the user has played music in another app, the button goes
 * there instead, as it should.
 */
class MediaButtons(context: Context, private val onPress: () -> Unit) {

    private val session = MediaSession(context, "Reduction").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (event.keyCode !in KEYS) return false
                // One press is a down and an up; a held button repeats the down.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    Logs.add("Headset button: ${KeyEvent.keyCodeToString(event.keyCode)}")
                    onPress()
                }
                return true
            }
        })
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .build()
        )
    }

    fun start() {
        runCatching { session.isActive = true }.onFailure { Logs.add("Headset button unavailable: ${it.message}") }
    }

    private companion object {
        val KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK
        )
    }
}
