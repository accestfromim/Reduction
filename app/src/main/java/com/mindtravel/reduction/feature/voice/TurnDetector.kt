package com.mindtravel.reduction.feature.voice

class TurnDetector(
    private val endWords: List<String> = listOf("over", "完毕")
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

        lastText = raw
        return Decision.Continue
    }

    fun stripEndWord(text: String): String? {
        val trimmed = text.trim()
        for (word in endWords) {
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
