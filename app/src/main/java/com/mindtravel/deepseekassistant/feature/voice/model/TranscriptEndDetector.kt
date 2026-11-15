package com.mindtravel.deepseekassistant.feature.voice.model

class TranscriptEndDetector(
    private val endWord: String = "完毕"
) {
    sealed class Decision {
        data object Continue : Decision()
        data class End(val text: String) : Decision()
    }

    private var lastText = ""
    private var emptyCount = 0

    fun reset() {
        lastText = ""
        emptyCount = 0
    }

    fun currentText(): String = lastText

    fun evaluate(text: String?, hasVoiceSinceSnapshot: Boolean): Decision {
        val raw = text?.trim().orEmpty()
        if (raw.isBlank()) {
            if (hasVoiceSinceSnapshot) {
                emptyCount = 0
                return Decision.Continue
            }
            emptyCount++
            if (emptyCount >= 2) return Decision.End(stripEndWord(lastText) ?: lastText)
            return Decision.Continue
        }

        emptyCount = 0
        if (hasVoiceSinceSnapshot) {
            lastText = raw
            return Decision.Continue
        }

        val body = stripEndWord(raw)
        if (body != null) return Decision.End(body)

        if (lastText.isNotBlank() && noNewChars(raw, lastText)) {
            return Decision.End(stripEndWord(lastText) ?: lastText)
        }

        lastText = raw
        return Decision.Continue
    }

    fun stripEndWord(text: String): String? {
        val trimmed = text.trim()
        val wordPattern = endWord.toCharArray().joinToString("\\s*") { Regex.escape(it.toString()) }
        val pattern = Regex(
            "^(.*?)[\\s，,、]*" + wordPattern + "[。！？!?，,、\\s]*$",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = pattern.matchEntire(trimmed) ?: return null
        return match.groupValues[1].trim().trimEnd('，', ',', '、', '。', '！', '!', '？', '?', ' ', '\n')
    }

    private fun noNewChars(nextRaw: String, prevRaw: String): Boolean {
        val next = normalize(nextRaw)
        val prev = normalize(prevRaw)
        if (next.isEmpty() || prev.isEmpty()) return false
        return next == prev || prev.startsWith(next)
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[\\s\\p{Punct}，。！？；：、…—（）【】《》“”‘’]"), "")
}
