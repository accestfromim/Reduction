package com.mindtravel.entropy.core

object TextSanitizer {
    private val onlyNulls = Regex("^(?:null)+$", RegexOption.IGNORE_CASE)
    private val leadingNullsBeforeText = Regex("^(?:null)+(?=\\S)", RegexOption.IGNORE_CASE)
    private val repeatedNullPrefix = Regex("^(?:null){2,}(?=\\S)", RegexOption.IGNORE_CASE)

    fun stripLeadingNullChunks(text: String): String {
        if (onlyNulls.matches(text)) return ""
        val match = leadingNullsBeforeText.find(text) ?: return text
        return text.removePrefix(match.value)
    }

    fun stripRepeatedNullPrefix(text: String): String =
        text.replaceFirst(repeatedNullPrefix, "")
}
