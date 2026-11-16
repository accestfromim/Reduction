package com.mindtravel.reduction.feature.chat.data

/**
 * A patch over memo cards, written the way a git patch is: which card, then lines to
 * keep, remove and add. One patch can create, change and delete several cards.
 *
 *     *** Add Card: 科研想法·检索
 *     Purpose: 向量检索方向的想法
 *     +## 粗筛
 *     +- 用倒排索引做粗筛
 *     *** Update Card: 备忘
 *     @@
 *      - 买电池
 *     -- 周五前交报告
 *     +- 周四前交报告
 *     *** Delete Card: 旧任务
 *     *** Move Card: 需求·短期 -> 产品需求/短期
 *
 * In an update, a line starting with a space is context, "-" is removed, "+" is added;
 * "@@" starts another place in the same card. There are no line numbers: the context and
 * removed lines say where the change goes. A hunk with only "+" lines adds at the end.
 * "Move" renames a card; a name with "/" in it puts the card in a folder.
 */
object CardPatch {

    sealed interface Op {
        val name: String
    }

    class Add(override val name: String, val purpose: String, val text: String) : Op

    class Update(override val name: String, val purpose: String?, val hunks: List<Hunk>) : Op

    class Delete(override val name: String) : Op

    /** Renames [name] to [to], text and purpose unchanged. */
    class Move(override val name: String, val to: String) : Op

    /** One place to change, as the patch wrote it: lines marked " ", "-" or "+". */
    class Hunk(val lines: List<String>) {

        /**
         * The lines to find and the lines to put in their place.
         *
         * Markdown list items begin with "- ", which looks just like a removed line. When
         * [dashItemIsContext] is set, such a line is read as an unchanged list item whose
         * leading space was left out, rather than as a removal.
         */
        fun sides(dashItemIsContext: Boolean = false): Pair<List<String>, List<String>> {
            val old = ArrayList<String>()
            val new = ArrayList<String>()
            for (line in lines) {
                when {
                    line.startsWith("+") -> new += line.substring(1)
                    line.startsWith("-") && !(dashItemIsContext && line.startsWith("- ")) -> old += line.substring(1)
                    else -> {
                        // Context: a leading space is the patch marker, not part of the line.
                        val context = if (line.startsWith(" ")) line.substring(1) else line
                        old += context
                        new += context
                    }
                }
            }
            return old to new
        }
    }

    class Invalid(message: String) : Exception(message)

    private val HEADER = Regex("^\\*\\*\\*\\s*(Add|Update|Delete|Move)\\s+(?:Card|File)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val ARROW = Regex("\\s*(?:->|→|=>)\\s*")
    private val PURPOSE = Regex("^(?:Purpose|用途)\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val ENVELOPE = Regex("^\\*\\*\\*\\s*(Begin|End) Patch\\s*$", RegexOption.IGNORE_CASE)

    fun parse(patch: String): List<Op> {
        val ops = ArrayList<Op>()
        val lines = patch.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val header = HEADER.matchEntire(line.trim())
            if (header == null) {
                if (line.isNotBlank() && !ENVELOPE.matches(line.trim()) && !line.trim().startsWith("```")) {
                    throw Invalid("补丁第 ${i + 1} 行不在任何卡片之下：“${line.take(40)}”。每段要以 *** Add Card: / *** Update Card: / *** Delete Card: 开头")
                }
                i++
                continue
            }
            val kind = header.groupValues[1].lowercase()
            val named = header.groupValues[2]
            val arrow = if (kind == "move") ARROW.find(named) else null
            if (kind == "move" && arrow == null) throw Invalid("改名要写成 *** Move Card: 旧名称 -> 新名称")
            val name = CardRepository.cleanName(if (arrow != null) named.substring(0, arrow.range.first) else named)
                ?: throw Invalid("卡片名称无效：“${named.take(50)}”")
            val target = arrow?.let {
                CardRepository.cleanName(named.substring(it.range.last + 1)) ?: throw Invalid("新名称无效：“${named.take(60)}”")
            }
            i++
            val body = ArrayList<String>()
            while (i < lines.size && HEADER.matchEntire(lines[i].trim()) == null && !ENVELOPE.matches(lines[i].trim())) {
                body += lines[i]
                i++
            }
            var purpose: String? = null
            if (body.isNotEmpty()) {
                PURPOSE.matchEntire(body[0].trim())?.let {
                    purpose = it.groupValues[1].trim()
                    body.removeAt(0)
                }
            }
            ops += when (kind) {
                "add" -> {
                    val text = body.dropLastWhile { it.isBlank() || it.trim().startsWith("```") }
                        .joinToString("\n") { if (it.startsWith("+")) it.substring(1) else it }
                    if (purpose.isNullOrBlank()) throw Invalid("新建卡片“$name”要在标题下一行写 Purpose: 一句话用途")
                    Add(name, purpose!!, text)
                }
                "update" -> Update(name, purpose?.takeIf { it.isNotBlank() }, hunks(name, body))
                "move" -> Move(name, target!!)
                else -> Delete(name)
            }
        }
        if (ops.isEmpty()) throw Invalid("补丁里没有任何卡片。以 *** Add Card: 名称、*** Update Card: 名称 或 *** Delete Card: 名称 开头")
        return ops
    }

    private fun hunks(name: String, body: List<String>): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        var lines = ArrayList<String>()
        for (line in body.dropLastWhile { it.isBlank() || it.trim().startsWith("```") }) {
            if (line.startsWith("@@")) {
                if (lines.isNotEmpty()) hunks += Hunk(lines)
                lines = ArrayList()
            } else {
                lines += line
            }
        }
        if (lines.isNotEmpty()) hunks += Hunk(lines)
        return hunks.filter { hunk -> hunk.sides().let { it.first != it.second } }
    }

    /**
     * Applies [hunks] to [text] in order and returns the new text. Throws [Invalid], naming
     * the lines it could not find, when a hunk does not fit.
     */
    fun apply(name: String, text: String, hunks: List<Hunk>): String {
        val lines = ArrayList(text.trimEnd('\n').let { if (it.isEmpty()) emptyList() else it.split('\n') })
        var cursor = 0
        for ((index, hunk) in hunks.withIndex()) {
            val (strictOld, strictNew) = hunk.sides()
            if (strictOld.isEmpty()) {
                if (lines.isNotEmpty() && lines.last().isNotBlank() && strictNew.firstOrNull()?.isNotBlank() == true) lines += ""
                lines += strictNew
                cursor = lines.size
                continue
            }
            var old = strictOld
            var new = strictNew
            var at = locate(lines, old, cursor)
            if (at < 0) {
                val (lenientOld, lenientNew) = hunk.sides(dashItemIsContext = true)
                if (lenientOld != lenientNew) {
                    val lenientAt = locate(lines, lenientOld, cursor)
                    if (lenientAt >= 0) {
                        old = lenientOld
                        new = lenientNew
                        at = lenientAt
                    }
                }
            }
            if (at < 0) {
                throw Invalid(
                    "卡片“$name”的第 ${index + 1} 处改动对不上原文，找不到这几行：\n" +
                        strictOld.take(4).joinToString("\n") { "  $it" } +
                        "\n先 read_card 看原文，上下文行和要删的行要照抄。整个补丁没有生效"
                )
            }
            repeat(old.size) { lines.removeAt(at) }
            lines.addAll(at, new)
            cursor = at + new.size
        }
        // A card ends with its last line of text, whatever the edit left trailing.
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
    }

    /** Where [wanted] sits, looking onward from [cursor] first so edits keep their order. */
    private fun locate(lines: List<String>, wanted: List<String>, cursor: Int): Int =
        find(lines, wanted, cursor).takeIf { it >= 0 } ?: find(lines, wanted, 0)

    /** Where [wanted] occurs in [lines] at or after [from]; trailing spaces never matter, and as a fallback neither does indentation. */
    private fun find(lines: List<String>, wanted: List<String>, from: Int): Int {
        for (normalise in listOf<(String) -> String>({ it.trimEnd() }, { it.trim() })) {
            val target = wanted.map(normalise)
            for (start in from..lines.size - target.size) {
                var same = true
                for (k in target.indices) {
                    if (normalise(lines[start + k]) != target[k]) {
                        same = false
                        break
                    }
                }
                if (same) return start
            }
        }
        return -1
    }
}
