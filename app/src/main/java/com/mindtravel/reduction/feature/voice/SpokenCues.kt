package com.mindtravel.reduction.feature.voice

import com.mindtravel.reduction.hardware.audio.Loudness

/** The short things the assistant says by itself, without the model: an acknowledgement, and why there is no answer. */
enum class Cue {
    OK,

    /** The request did not get through; it is being sent again. */
    RETRYING,

    /** The request did not get through at all. */
    NO_NETWORK,

    /** The answer failed for another reason. */
    FAILED,

    /** Nothing intelligible was recorded. */
    NOT_HEARD
}

/** Bundled recordings are immediately available, including when inference or the network fails. */
class SpokenCues(bundled: Map<Cue, ByteArray>) {
    private val sounds = bundled.mapValues { Loudness.leveled(it.value) }

    fun sound(cue: Cue): ByteArray = sounds.getValue(cue)
}
