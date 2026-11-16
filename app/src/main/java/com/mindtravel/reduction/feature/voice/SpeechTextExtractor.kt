package com.mindtravel.reduction.feature.voice

/**
 * Turns streamed Markdown into text worth reading aloud: drops code, maths, URLs,
 * images, HTML tags and Markdown markers, and keeps link labels.
 */
class SpeechTextExtractor {

    private enum class Mode {
        NORMAL, INLINE_MATH, DISPLAY_MATH, PAREN_MATH, BRACKET_MATH,
        FENCE, INLINE_CODE, LINK_LABEL, LINK_URL, IMAGE, HTML, URL
    }

    private var mode = Mode.NORMAL
    private var buffer = ""
    private var pos = 0
    private var lineStart = true

    fun push(token: String): String {
        buffer += token
        return consume(final = false)
    }

    fun finish(): String {
        val output = consume(final = true)
        mode = Mode.NORMAL
        buffer = ""
        pos = 0
        lineStart = true
        return output
    }

    private fun consume(final: Boolean): String {
        val output = StringBuilder()

        while (pos < buffer.length) {
            val rest = buffer.substring(pos)

            if (mode == Mode.NORMAL) {
                if (!final && needsMore(rest)) break
                consumeNormal(rest, output)
                continue
            }

            if (mode == Mode.LINK_LABEL) {
                val end = rest.indexOf(']')
                if (end < 0) {
                    output.append(rest)
                    pos = buffer.length
                    if (!final) break
                    mode = Mode.NORMAL
                    continue
                }
                output.append(rest, 0, end)
                pos += end + 1
                mode = Mode.LINK_URL
                lineStart = false
                continue
            }

            if (mode == Mode.URL) {
                val stop = rest.indexOfFirst { it.isWhitespace() || it in URL_STOP }
                if (stop < 0) {
                    pos = buffer.length
                    if (!final) break
                    mode = Mode.NORMAL
                    continue
                }
                pos += stop
                if (rest[stop].isWhitespace()) {
                    pos += 1
                    output.append(' ')
                }
                mode = Mode.NORMAL
                lineStart = false
                continue
            }

            // Every remaining mode skips everything up to its closing marker.
            val close = when (mode) {
                Mode.INLINE_MATH -> "$"
                Mode.DISPLAY_MATH -> "$$"
                Mode.PAREN_MATH -> "\\)"
                Mode.BRACKET_MATH -> "\\]"
                Mode.FENCE -> "```"
                Mode.INLINE_CODE -> "`"
                Mode.IMAGE, Mode.LINK_URL -> ")"
                else -> ">"
            }
            val end = rest.indexOf(close)
            if (end < 0) {
                pos = buffer.length
                if (!final) break
                mode = Mode.NORMAL
                continue
            }
            pos += end + close.length
            lineStart = mode == Mode.FENCE
            mode = Mode.NORMAL
        }

        if (pos > 0) {
            buffer = buffer.substring(pos)
            pos = 0
        }
        return output.toString()
    }

    private fun consumeNormal(rest: String, output: StringBuilder) {
        when {
            rest.startsWith("```") -> return enter(Mode.FENCE, 3)
            rest.startsWith("$$") -> return enter(Mode.DISPLAY_MATH, 2)
            rest.startsWith("\\[") -> return enter(Mode.BRACKET_MATH, 2)
            rest.startsWith("\\(") -> return enter(Mode.PAREN_MATH, 2)
            rest.startsWith("![") -> return enter(Mode.IMAGE, 2)
        }

        val prefix = URL_PREFIXES.firstOrNull { rest.startsWith(it, ignoreCase = true) }
        if (prefix != null) return enter(Mode.URL, prefix.length)

        val ch = rest[0]
        val next = rest.getOrNull(1)
        when (ch) {
            '$' -> {
                if (next != null && (next.isAsciiLetter() || next in "\\{([")) return enter(Mode.INLINE_MATH, 1)
                return keep('$', output)
            }
            '`' -> return enter(Mode.INLINE_CODE, 1)
            '[' -> return enter(Mode.LINK_LABEL, 1)
            '<' -> {
                if (next != null && (next.isAsciiLetter() || next == '/' || next == '!')) return enter(Mode.HTML, 1)
                return keep('<', output)
            }
            '\\' -> {
                if (next != null) {
                    output.append(next)
                    pos += 2
                } else {
                    output.append('\\')
                    pos += 1
                }
                lineStart = false
                return
            }
            '\n' -> {
                output.append(' ')
                pos += 1
                lineStart = true
                return
            }
            '|' -> return keep(' ', output)
        }

        if (lineStart) {
            if (ch in LINE_MARKERS) {
                pos += 1
                return
            }
            val ordered = ORDERED_ITEM.find(rest)
            if (ordered != null) {
                pos += ordered.value.length
                return
            }
        }
        if (ch == '*' || ch == '_' || ch == '~') {
            pos += 1
            return
        }
        keep(ch, output)
    }

    private fun enter(next: Mode, skip: Int) {
        mode = next
        pos += skip
    }

    private fun keep(ch: Char, output: StringBuilder) {
        output.append(ch)
        pos += 1
        lineStart = false
    }

    /** True when [rest] might be the start of a multi-character marker that has not fully arrived. */
    private fun needsMore(rest: String): Boolean {
        if (rest == "$" || rest == "!" || rest == "\\" || rest == "<") return true
        if (rest == "`" || rest == "``") return true
        return URL_PREFIXES.any { it.length > rest.length && it.startsWith(rest, ignoreCase = true) }
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    companion object {
        private val URL_PREFIXES = listOf("https://", "http://", "www.")
        private const val URL_STOP = "。！？；，、()（）[]【】<>"
        private const val LINE_MARKERS = "#-*+>"
        private val ORDERED_ITEM = Regex("^\\d+[.)]\\s+")
        private val SPACES = Regex("\\s+")
        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([。！？!?；;，,])")

        /** Whole-text convenience with whitespace collapsed. */
        fun clean(raw: String): String {
            val extractor = SpeechTextExtractor()
            return (extractor.push(raw) + extractor.finish())
                .replace(SPACES, " ")
                .replace(SPACE_BEFORE_PUNCTUATION, "$1")
                .trim()
        }
    }
}
