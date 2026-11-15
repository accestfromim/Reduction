package com.mindtravel.entropy.feature.voice

/**
 * A reply is "【朗读】" and the spoken version, optionally followed by "【详情】" and
 * material that is only shown. This passes the spoken version through, without its
 * tag, and drops the rest.
 */
class SpokenPart {
    private var held = ""
    private var done = false
    private var opened = false

    fun push(text: String): String {
        if (done) return ""
        val all = held + text
        val at = all.indexOf(MARKER)
        if (at >= 0) {
            done = true
            held = ""
            return untagged(all.substring(0, at))
        }
        // The marker may be arriving in pieces: keep back whatever could be its beginning.
        var keep = minOf(MARKER.length - 1, all.length)
        while (keep > 0 && !MARKER.startsWith(all.substring(all.length - keep))) keep--
        held = all.substring(all.length - keep)
        val ready = all.substring(0, all.length - keep)
        // Until the opening tag has either fully arrived or been ruled out, say nothing.
        if (!opened && SPOKEN_TAG.startsWith(ready.trimStart()) && ready.trimStart().length < SPOKEN_TAG.length) {
            held = all
            return ""
        }
        return untagged(ready)
    }

    private fun untagged(text: String): String {
        if (opened) return text
        opened = true
        val trimmed = text.trimStart()
        return if (trimmed.startsWith(SPOKEN_TAG)) trimmed.substring(SPOKEN_TAG.length) else text
    }

    fun finish(): String {
        val rest = if (done) "" else untagged(held)
        held = ""
        done = true
        return rest
    }

    companion object {
        const val SPOKEN_TAG = "【朗读】"
        const val MARKER = "【详情】"
    }
}
