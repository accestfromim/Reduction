package com.mindtravel.reduction

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import com.mindtravel.reduction.hardware.audio.PcmCapture
import com.mindtravel.reduction.hardware.audio.WarmHeadsetInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun verifyWarmAudio(context: Context) {
    val manager = context.getSystemService(AudioManager::class.java)
    fun nextClient(): PcmCapture {
        val ready = CountDownLatch(1)
        val client = PcmCapture()
        check(client.start(MediaRecorder.AudioSource.VOICE_COMMUNICATION,{ ready.countDown() },{ error(it) }))
        check(ready.await(5,TimeUnit.SECONDS))
        return client
    }
    try {
        check(WarmHeadsetInput.open())
        val wake = nextClient()
        val id = manager.activeRecordingConfigurations.single().clientAudioSessionId
        wake.stop()
        check(manager.activeRecordingConfigurations.single().clientAudioSessionId == id)
        val turn = nextClient()
        check(manager.activeRecordingConfigurations.single().clientAudioSessionId == id)
        turn.stop()
    } finally { WarmHeadsetInput.close() }
}
