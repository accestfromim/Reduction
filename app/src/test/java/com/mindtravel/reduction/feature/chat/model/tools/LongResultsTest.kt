package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongResultsTest {

    /** Answers with the text it was made with. */
    private class Says(private val text: String, name: String = "says") : ChatTool {
        override val spec = ToolSpec(name, "says", "x", "x")
        override fun execute(argumentsJson: String): String = text
    }

    /** 400 lines, each telling its own number: about 20 000 characters. */
    private val long = (1..400).joinToString("\n") { "第 $it 行：" + "内容".repeat(20) + if (it == 333) " 暗号" else "" }

    private fun more(tools: ToolRegistry, vararg pairs: Pair<String, String>) = tools.execute("read_more", JSONObject(pairs.toMap()).toString())

    /** What a piece says, without the lines the program puts round it. */
    private fun body(answer: String) = answer.lines().filterNot { it.startsWith("［") || it.startsWith("————") }.joinToString("\n").trim('\n')

    @Test
    fun aShortAnswerIsHandedOverAsItIs() {
        val tools = ToolRegistry(listOf(Says("短的")), LongResults())
        assertEquals("短的", tools.execute("says", "{}"))
        assertEquals("现在没有存着的长结果。", more(tools, "piece" to "2"))
    }

    @Test
    fun aLongAnswerComesInPiecesThatTogetherAreTheWholeOfIt() {
        val tools = ToolRegistry(listOf(Says(long)), LongResults())
        val first = tools.execute("says", "{}")
        val count = Regex("分 (\\d+) 片").find(first)!!.groupValues[1].toInt()
        assertTrue(first, count >= 3 && first.contains("长结果 1（says）：共 ${long.length} 字") && first.contains("这是第 1 片"))
        assertTrue(first.length < LongResults.PIECE_CHARS + 400)

        val parts = ArrayList<String>()
        parts += body(first)
        for (piece in 2..count) {
            val answer = more(tools, "result" to "1", "piece" to "$piece")
            assertTrue(answer, answer.startsWith("———— 长结果 1 第 $piece/$count 片 ————"))
            parts += body(answer)
        }
        // Not a character lost, and every piece ends at the end of a line.
        assertEquals(long, parts.joinToString("\n"))
        assertTrue(parts.all { it.lines().last().matches(Regex("第 \\d+ 行：.*")) && it.lines().first().startsWith("第 ") })
    }

    @Test
    fun piecesAreFetchedByRangeAndByTheWordsTheyHold() {
        val tools = ToolRegistry(listOf(Says(long)), LongResults())
        val count = Regex("分 (\\d+) 片").find(tools.execute("says", "{}"))!!.groupValues[1].toInt()

        val range = more(tools, "piece" to "2-3")
        assertTrue(range, range.contains("第 2/$count 片") && range.contains("第 3/$count 片"))

        // The one piece that holds the word, wherever it is.
        val found = more(tools, "find" to "暗号")
        assertTrue(found, found.contains("第 333 行：") && Regex("第 \\d+/$count 片").findAll(found).count() == 1)
        assertTrue(more(tools, "find" to "没有这个词").startsWith("长结果 1 的 $count 片里都没有同时出现"))

        // More than fits one answer is given as far as it fits, and the rest is named.
        val all = more(tools, "piece" to "1-$count")
        assertTrue(all, all.contains("接着取：") && all.contains("第 1/$count 片") && !all.contains("第 $count/$count 片"))
        assertTrue(more(tools, "piece" to "99").contains("没有第 99 片"))
        assertTrue(more(tools, "result" to "7", "piece" to "1").startsWith("没有长结果 7"))
    }

    @Test
    fun theToolIsOnlyThereWhenLongAnswersAreKeptAndItsOwnAnswerIsNeverCutAgain() {
        assertFalse(ToolRegistry(listOf(Says("x"))).specs().any { it.name == "read_more" })
        assertEquals(long, ToolRegistry(listOf(Says(long))).execute("says", "{}"))
        val tools = ToolRegistry(listOf(Says(long)), LongResults())
        assertTrue(tools.specs().any { it.name == "read_more" })
        tools.execute("says", "{}")
        // Two pieces come back whole in one answer, though together they are longer than a piece.
        val two = more(tools, "piece" to "1-2")
        assertTrue(two.length > LongResults.PIECE_CHARS && !two.contains("这是第 1 片"))
    }

    @Test
    fun aSearchOfTheCardsGivesEveryHitAndEveryLineInFull() {
        val folder = java.nio.file.Files.createTempDirectory("cards").toFile()
        val cards = com.mindtravel.reduction.feature.chat.data.CardRepository(folder)
        val wide = "关键词 " + "很长的一行".repeat(100) + " 行尾"
        cards.save("一", (1..90).joinToString("\n") { "关键词 第 $it 条" } + "\n" + wide)
        val said = SearchCardsTool(cards).execute("""{"query":"关键词"}""")
        assertTrue(said, said.startsWith("命中 91 行，在 1 张卡片里（搜了 1 张）\n"))
        assertTrue(said.contains("关键词 第 90 条") && said.contains(wide) && !said.contains("…"))
        folder.deleteRecursively()
    }
}
