package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.hardware.audio.Loudness
import com.mindtravel.reduction.model.voice.TtsSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * "好的" in the assistant's own voice. It is synthesised once per voice, in the
 * background, and kept; until that has happened — or when it cannot, as with no network
 * or no API key — the recording bundled with the app is used. Either way it is as loud
 * as the speech that follows it.
 */
class AckVoice(
    private val settingsRepository: SettingsGateway,
    private val synthesizer: TtsSynthesizer,
    bundled: ByteArray,
    private val sampleRate: Int,
    private val scope: CoroutineScope
) {
    private val bundled = Loudness.leveled(bundled)
    private val spoken = ConcurrentHashMap<String, ByteArray>()
    private val asked = ConcurrentHashMap.newKeySet<String>()

    /** What to play now. Never waits. */
    fun sound(): ByteArray {
        val voice = settingsRepository.load().voice
        return spoken[voice] ?: bundled.also { prepare() }
    }

    /** Gets the current voice's "好的" ready, if it is not already. */
    fun prepare() {
        val settings = settingsRepository.load()
        val voice = settings.voice
        if (settings.apiKey.isBlank() || spoken.containsKey(voice) || !asked.add(voice)) return
        scope.launch(Dispatchers.IO) {
            try {
                val pcm = Loudness.trimmed(synthesizer.synthesize(settings.apiKey, voice, TEXT), sampleRate)
                val millis = pcm.size / 2 * 1000L / sampleRate
                check(millis in MIN_MS..MAX_MS) { "it came out ${millis}ms long" }
                spoken[voice] = Loudness.leveled(pcm)
                Logs.add("Cue 好的 ready in voice=$voice ${millis}ms")
            } catch (t: Throwable) {
                Logs.add("Cue 好的 not synthesised for voice=$voice: ${t.message}")
                asked.remove(voice)
            }
        }
    }

    private companion object {
        const val TEXT = "好的。"

        /** Anything outside this is not the two syllables that were asked for. */
        const val MIN_MS = 200L
        const val MAX_MS = 1_300L
    }
}
