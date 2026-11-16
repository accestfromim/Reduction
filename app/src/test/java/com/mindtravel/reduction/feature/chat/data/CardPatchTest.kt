package com.mindtravel.reduction.feature.chat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CardPatchTest {

    private fun cards() = CardRepository(Files.createTempDirectory("cards").toFile())

    private val ideas = "## 粗筛\n\n- 用倒排索引做粗筛\n- 先聚类再检索\n\n## 待验证\n\n- 对比 HNSW 的召回\n"

    private fun patch(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun onePatchCreatesChangesAndDeletesSeveralCards() {
        val cards = cards()
        cards.save("科研想法·检索", ideas, purpose = "向量检索方向的想法")
        cards.save("旧任务", "做完了", purpose = "上周的任务")

        val changed = cards.patch(
            patch(
                "*** Update Card: 科研想法·检索",
                "@@",
                " - 用倒排索引做粗筛",
                "-- 先聚类再检索",
                "+- 先聚类再检索，nlist 取 4096",
                "+- 试试分层聚类",
                "@@",
                " ## 待验证",
                " ",
                "-- 对比 HNSW 的召回",
                "*** Add Card: 备忘",
                "Purpose: 零碎待办",
                "+- 周五前交报告",
                "+- 买电池",
                "*** Delete Card: 旧任务"
            )
        )

        assertEquals(listOf("修改“科研想法·检索”（2 处）", "新建“备忘”", "删除“旧任务”"), changed)
        assertEquals(
            "## 粗筛\n\n- 用倒排索引做粗筛\n- 先聚类再检索，nlist 取 4096\n- 试试分层聚类\n\n## 待验证\n",
            cards.read("科研想法·检索")
        )
        assertEquals("- 周五前交报告\n- 买电池\n", cards.read("备忘"))
        assertEquals("零碎待办", cards.purpose("备忘"))
        assertNull(cards.read("旧任务"))
    }

    @Test
    fun aHunkWithOnlyAddedLinesGoesAtTheEnd() {
        val cards = cards()
        cards.save("备忘", "- 买电池\n", purpose = "零碎待办")
        cards.patch(patch("*** Update Card: 备忘", "+- 周五前交报告"))
        assertEquals("- 买电池\n\n- 周五前交报告\n", cards.read("备忘"))
    }

    @Test
    fun contextDecidesWhichOfTwoIdenticalLinesIsMeant() {
        val cards = cards()
        cards.save("清单", "## 甲\n- 待定\n\n## 乙\n- 待定\n", purpose = "x")
        cards.patch(patch("*** Update Card: 清单", "@@", " ## 乙", "-- 待定", "+- 已定"))
        assertEquals("## 甲\n- 待定\n\n## 乙\n- 已定\n", cards.read("清单"))
    }

    @Test
    fun aPatchThatDoesNotFitChangesNothingAtAll() {
        val cards = cards()
        cards.save("科研想法·检索", ideas, purpose = "p")
        val error = assertThrows(CardPatch.Invalid::class.java) {
            cards.patch(
                patch(
                    "*** Add Card: 备忘",
                    "Purpose: 零碎待办",
                    "+- 买电池",
                    "*** Update Card: 科研想法·检索",
                    "-- 这一行卡片里没有",
                    "+- 新的一行"
                )
            )
        }
        assertTrue(error.message, error.message!!.contains("这一行卡片里没有") && error.message!!.contains("read_card"))
        assertEquals("the card is untouched", ideas, cards.read("科研想法·检索"))
        assertNull("the card earlier in the same patch was not created", cards.read("备忘"))
    }

    @Test
    fun smallSlipsInTheModelsPatchAreTolerated() {
        val cards = cards()
        cards.save("想法", "  - 缩进的一行   \n- 第二行\n", purpose = "p")
        // trailing spaces and indentation of context differ; a fence and the envelope are present
        cards.patch(
            patch(
                "```",
                "*** Begin Patch",
                "*** Update File：想法",
                "用途：改过的用途",
                "- 缩进的一行",
                "-- 第二行",
                "+- 第二行，改了",
                "*** End Patch",
                "```"
            )
        )
        assertEquals("- 缩进的一行\n- 第二行，改了\n", cards.read("想法"))
        assertEquals("改过的用途", cards.purpose("想法"))
    }

    @Test
    fun whatCannotBeDoneIsSaidPlainly() {
        val cards = cards()
        cards.save("想法", "a\n", purpose = "p")
        fun reason(vararg lines: String) = assertThrows(CardPatch.Invalid::class.java) { cards.patch(patch(*lines)) }.message!!

        assertTrue(reason("*** Add Card: 想法", "Purpose: x", "+b").contains("已经有一张"))
        assertTrue(reason("*** Add Card: 新卡", "+b").contains("Purpose"))
        assertTrue(reason("*** Update Card: 没有这张", "+b").contains("没有名为"))
        assertTrue(reason("*** Delete Card: 没有这张").contains("可删"))
        assertTrue(reason("随便写的一段话").contains("不在任何卡片之下"))
        assertTrue(reason("*** Update Card: 想法", " a").contains("没有改变任何内容"))
        assertEquals("a\n", cards.read("想法"))
    }

    @Test
    fun aCardCanBeEmptiedAndRewrittenInOnePatch() {
        val cards = cards()
        cards.save("任务", "旧目标\n旧约束\n", purpose = "当前任务卡")
        cards.patch(patch("*** Delete Card: 任务", "*** Add Card: 任务", "Purpose: 当前任务卡", "+新目标"))
        assertEquals("新目标\n", cards.read("任务"))
    }
}
