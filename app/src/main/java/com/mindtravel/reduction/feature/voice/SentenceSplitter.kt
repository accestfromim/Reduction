package com.mindtravel.reduction.feature.voice

/**
 * Cuts a growing text stream into pieces for speech synthesis.
 * The first piece is cut early, at the first clause boundary, so audio starts sooner;
 * later pieces are whole sentences. No piece exceeds [maxChars].
 */
class SentenceSplitter(
    private val firstMinChars: Int = 6,
    private val firstMaxChars: Int = 32,
    private val maxChars: Int = 200
) {
    private val buffer = StringBuilder()
    private var emitted = false

    fun push(text: String): List<String> {
        buffer.append(text)
        return drain(final = false)
    }

    fun finish(): List<String> = drain(final = true)

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val cut = nextCut()
            if (cut < 0) break
            emit(buffer.substring(0, cut + 1), out)
            buffer.delete(0, cut + 1)
        }
        if (final) {
            emit(buffer.toString(), out)
            buffer.setLength(0)
        }
        return out
    }

    private fun nextCut(): Int {
        val sentence = buffer.indexOfFirst { it in SENTENCE_END }
        if (emitted) return sentence

        var clause = -1
        for (i in (firstMinChars - 1).coerceAtLeast(0) until buffer.length) {
            if (buffer[i] in CLAUSE_END) {
                clause = i
                break
            }
        }
        if (sentence >= 0 && (clause < 0 || sentence < clause)) return sentence
        if (clause >= 0) return clause
        if (buffer.length < firstMaxChars) return -1

        var soft = -1
        for (i in firstMaxChars - 1 downTo firstMinChars - 1) {
            if (buffer[i] == ' ' || buffer[i] in CLAUSE_END) {
                soft = i
                break
            }
        }
        return if (soft >= 0) soft else firstMaxChars - 1
    }

    private fun emit(raw: String, out: MutableList<String>) {
        val clean = raw.trim()
        if (clean.isEmpty()) return
        emitted = true
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + maxChars, clean.length)
            if (end < clean.length) {
                val sentence = clean.lastIndexOfAny(SENTENCE_END, end - 1)
                val clause = clean.lastIndexOfAny(CLAUSE_END, end - 1)
                if (sentence > start + MIN_TAIL) {
                    end = sentence + 1
                } else if (clause > start + MIN_TAIL) {
                    end = clause + 1
                }
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            start = end
        }
    }

    companion object {
        private const val MIN_TAIL = 40
        private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')
        private val CLAUSE_END = charArrayOf('，', ',', '、', '：', ':')
    }
}
