package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject

/**
 * What tools answer, kept whole. An answer too long to hand over at once is cut into
 * pieces at the ends of lines; the assistant is given the first and told how many there
 * are, and fetches the others — by number, or the ones that hold given words — with
 * [ReadMoreTool]. Nothing a tool said is thrown away for being long.
 *
 * The answers of the latest [KEPT] long calls are held, for as long as the app runs.
 */
class LongResults {
    class Kept(val number: Int, val tool: String, val text: String, val pieces: List<String>)

    private val kept = ArrayList<Kept>()
    private var last = 0

    /**
     * What to hand the assistant for [text], the answer of [tool]: the text itself when it is
     * short, otherwise its first piece with a note of how to get the rest.
     */
    @Synchronized
    fun first(tool: String, text: String): String {
        if (text.length <= PIECE_CHARS) return text
        val entry = Kept(++last, tool, text, pieces(text))
        kept += entry
        while (kept.size > KEPT) kept.removeAt(0)
        return entry.pieces[0] + "\n\n" + note(entry, 1)
    }

    @Synchronized
    fun find(number: Int): Kept? = kept.firstOrNull { it.number == number }

    @Synchronized
    fun latest(): Kept? = kept.lastOrNull()

    /** The line under a piece: where it stands, and how the others are fetched. */
    fun note(entry: Kept, piece: Int): String =
        "［长结果 ${entry.number}（${entry.tool}）：共 ${entry.text.length} 字，分 ${entry.pieces.size} 片，这是第 $piece 片。" +
            "没有任何内容被丢掉：要其余部分，用 read_more，result=${entry.number}，piece 填片号（可以写 2-3），或 find 填关键词取出包含它的片］"

    companion object {
        /** How long a piece is at most. */
        const val PIECE_CHARS = 6_000
        const val KEPT = 12

        /** [text] in pieces of at most [PIECE_CHARS], each ending at the end of a line where it can. */
        fun pieces(text: String): List<String> {
            val made = ArrayList<String>()
            var from = 0
            while (from < text.length) {
                var to = minOf(text.length, from + PIECE_CHARS)
                if (to < text.length) {
                    // The last line break in the second half of the piece, so that pieces do not get very short.
                    val at = text.lastIndexOf('\n', to - 1)
                    if (at > from + PIECE_CHARS / 2) to = at + 1
                }
                made += text.substring(from, to)
                from = to
            }
            return made
        }
    }
}

/** Fetches the rest of a long answer: pieces by number, or the pieces that hold given words. */
class ReadMoreTool(private val results: LongResults) : ChatTool {

    override val spec = ToolSpec(
        name = "read_more",
        description = "取出一个长结果的其余部分。别的工具的结果太长时只先给第一片，并注明“长结果 N、共几片”；用这个工具按片号取，或者按关键词取出包含它的那几片。结果一个字都不会被丢掉。",
        parameters = listOf(
            ToolParam("result", "长结果的编号，就是提示里的 N。不填是最近的一个", required = false),
            ToolParam("piece", "要第几片，例如 2，或一段 2-4。和 find 二选一", required = false),
            ToolParam("find", "关键词，多个用空格分开：取出同时包含它们的片。和 piece 二选一", required = false)
        )
    )

    override fun describe(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val find = args.stringOrNull("find")?.trim().orEmpty()
        return if (find.isNotEmpty()) "在长结果里找：${find.take(14)}" else "读长结果第 ${args.stringOrNull("piece")?.trim().orEmpty().ifEmpty { "2" }} 片"
    }

    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val asked = args.stringOrNull("result")?.trim().orEmpty()
        val entry = (if (asked.isEmpty()) results.latest() else asked.toIntOrNull()?.let(results::find))
            ?: return if (asked.isEmpty()) "现在没有存着的长结果。" else "没有长结果 $asked：它太早了，已经不在；把那次工具调用重做一遍。"
        val count = entry.pieces.size

        val words = args.stringOrNull("find").orEmpty().split(Regex("[\\s,，、]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val wanted: List<Int> = if (words.isNotEmpty()) {
            (1..count).filter { piece -> entry.pieces[piece - 1].lowercase().let { text -> words.all { it in text } } }
                .ifEmpty { return "长结果 ${entry.number} 的 $count 片里都没有同时出现：${words.joinToString(" ")}" }
        } else {
            val range = RANGE.find(args.stringOrNull("piece").orEmpty()) ?: return "要填 piece（片号，例如 2 或 2-4）或 find（关键词）。长结果 ${entry.number} 共 $count 片。"
            val from = range.groupValues[1].toInt()
            val to = range.groupValues[2].toIntOrNull() ?: from
            if (from !in 1..count) return "长结果 ${entry.number} 只有 $count 片，没有第 $from 片。"
            (from..minOf(to, count)).toList()
        }
        // As many as fit one answer; the rest are named, to be asked for next.
        val given = ArrayList<Int>()
        var room = AT_ONCE_CHARS
        for (piece in wanted) {
            val size = entry.pieces[piece - 1].length
            if (given.isNotEmpty() && size > room) break
            given += piece
            room -= size
        }
        return buildString {
            for (piece in given) {
                appendLine("———— 长结果 ${entry.number} 第 $piece/$count 片 ————")
                appendLine(entry.pieces[piece - 1].trimEnd('\n'))
            }
            val left = wanted - given.toSet()
            if (left.isNotEmpty()) appendLine("［还要的片没有给完，接着取：${left.joinToString("、")}］")
            if (words.isNotEmpty()) appendLine("［包含 ${words.joinToString(" ")} 的片：${wanted.joinToString("、")}，共 $count 片］")
        }.trimEnd()
    }

    private companion object {
        /** How much one call hands over: two pieces' worth. */
        const val AT_ONCE_CHARS = 2 * LongResults.PIECE_CHARS
        val RANGE = Regex("(\\d{1,4})(?:\\s*[-–~到至]\\s*(\\d{1,4}))?")
    }
}
