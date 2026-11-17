package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.feature.chat.data.CardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchCardsToolTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun tool(): SearchCardsTool {
        val cards = CardRepository(temporary.newFolder())
        cards.save("产品/需求·短期", "# 短期\n\n## 输入\n- 输入框直接收图片\n- 验收：发一张照片能说出内容\n\n## 卡片\n- 卡片关键词搜索")
        cards.save("产品/开发排期/紧急项", "先做 Sub Agent 回传图片\n验收要截图")
        cards.save("备忘", "买花\n周末去爬山")
        return SearchCardsTool(cards)
    }

    private fun search(tool: SearchCardsTool, vararg pairs: Pair<String, String>) =
        tool.execute(org.json.JSONObject(pairs.toMap()).toString())

    @Test
    fun aWordIsFoundWithItsCardItsLineAndItsSection() {
        val found = search(tool(), "query" to "图片")
        assertTrue(found, found.startsWith("命中 2 行，在 2 张卡片里（搜了 3 张）"))
        assertTrue(found, found.contains("## 产品/需求·短期\n- 第 4 行〔输入〕：- 输入框直接收图片"))
        assertTrue(found, found.contains("## 产品/开发排期/紧急项\n- 第 1 行：先做 Sub Agent 回传图片"))
        assertFalse(found, found.contains("备忘"))
    }

    @Test
    fun severalWordsMustAllBeInTheLineUnlessAnyIsAskedFor() {
        val tool = tool()
        val all = search(tool, "query" to "验收 截图")
        assertTrue(all, all.startsWith("命中 1 行") && all.contains("验收要截图"))
        val any = search(tool, "query" to "验收 截图", "match" to "any")
        assertTrue(any, any.startsWith("命中 2 行") && any.contains("发一张照片能说出内容"))
    }

    @Test
    fun caseDoesNotMatterAndAFolderNarrowsTheSearchToWhatIsUnderIt() {
        val tool = tool()
        assertTrue(search(tool, "query" to "sub agent").contains("Sub Agent 回传图片"))
        val narrowed = search(tool, "query" to "图片", "folder" to "产品/开发排期")
        assertTrue(narrowed, narrowed.startsWith("命中 1 行，在 1 张卡片里（搜了 1 张）"))
        assertEquals(1, Regex("^## ", RegexOption.MULTILINE).findAll(search(tool, "query" to "图片", "folder" to "产品/开发排期")).count())
        assertTrue(search(tool, "query" to "图片", "folder" to "不存在").startsWith("没有名为“不存在”的文件夹"))
    }

    @Test
    fun aWordInACardsNameIsToldOfAndNothingFoundSaysSo() {
        val tool = tool()
        val named = search(tool, "query" to "紧急")
        assertTrue(named, named.contains("名称里有关键词的卡片：产品/开发排期/紧急项"))
        val none = search(tool, "query" to "火星 移民")
        assertTrue(none, none.startsWith("没有命中：火星 移民（搜了 3 张卡片") && none.contains("match=any"))
        assertTrue(search(tool, "query" to "  ").startsWith("没有生效"))
    }
}
