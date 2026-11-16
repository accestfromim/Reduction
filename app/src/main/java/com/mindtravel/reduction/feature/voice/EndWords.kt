package com.mindtravel.reduction.feature.voice

/**
 * The words that close a turn when said last: "完毕" and "over". They are not part of
 * what the user meant, so they are taken off the end of a transcript.
 *
 * Taking them off is all that happens here. Ending a recording is decided on the device,
 * from the audio itself; a transcript never ends one.
 */
object EndWords {
    private val words = listOf("over", "完毕")

    /** [text] without a closing end word, or null when it does not end with one. */
    fun strip(text: String): String? {
        val trimmed = text.trim()
        for (word in words) {
            val wordPattern = word.toCharArray().joinToString("\\s*") { Regex.escape(it.toString()) }
            val pattern = Regex(
                "^(.*?)[\\s，,、]*" + wordPattern + "[。！？!?，,、.．…\\s]*$",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val match = pattern.matchEntire(trimmed) ?: continue
            return match.groupValues[1].trim().trimEnd('，', ',', '、', '。', '．', '.', '…', '！', '!', '？', '?', ' ', '\n')
        }
        return null
    }
}
