package com.mindtravel.reduction.feature.article

import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.tools.AgentHarness
import com.mindtravel.reduction.hardware.pc.PcGateway
import com.mindtravel.reduction.hardware.pc.PcInfo
import com.mindtravel.reduction.hardware.pc.PcProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class RenderArticleToolTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val assets = File("src/main/assets")
    private val cards by lazy { CardRepository(temporary.newFolder()) }
    private val builder by lazy {
        ArticleBuilder(asset = { File(assets, it).readText() }, assetBytes = { File(assets, it).readBytes() }, card = { cards.read(it) })
    }

    private fun text(page: String, id: String): String {
        val block = Regex("<script id=\"$id\" type=\"text/plain\">([A-Za-z0-9+/=]*)</script>").find(page)!!.groupValues[1]
        return String(Base64.getDecoder().decode(block), Charsets.UTF_8)
    }

    @Test
    fun theBuilderPageCarriesEverythingItNeedsAndNothingInTheArticleCanBreakIt() {
        val source = "# 标题\n\n正文 </script><script>alert(1)</script> <!-- 注释 --> [[data]] {{body}}\n\n![图](media:m0123456789ab.png)"
        val page = builder.page(source, mapOf("m0123456789ab.png" to "data:image/png;base64,QUJD"))
        // Every place is filled, and what is text for the page to read is carried as base64.
        assertFalse(Regex("\\[\\[(libraries|build|template|style|katex-style|data)]]").containsMatchIn(page.substringAfter("<head>")))
        val data = JSONObject(text(page, "article-data"))
        assertEquals(source, data.getString("source"))
        assertEquals("data:image/png;base64,QUJD", data.getJSONObject("media").getString("m0123456789ab.png"))
        assertEquals(File(assets, "article/template.html").readText(), text(page, "article-template"))
        assertEquals(File(assets, "article/article.css").readText(), text(page, "article-style"))
        // The fonts of formulas are inside, so the page is the same wherever it is opened.
        val katex = text(page, "article-katex")
        assertTrue(katex.contains("url(data:font/woff2;base64,") && !katex.contains("url(fonts/"))
        // The page ends where it should: nothing carried in it closed a script early.
        assertEquals(7, Regex("</script>").findAll(page).count())
        assertTrue(page.contains("ArticleBuild.page(") && page.contains("root.ArticleBuild ="))
    }

    @Test
    fun theUsersOwnTemplateCardsAreUsedWhenTheyCanBeAndSaidOfWhenTheyCannot() {
        assertEquals(File(assets, "article/template.html").readText(), builder.template())
        assertNull(builder.templateProblem())
        cards.save(ArticleBuilder.STYLE_CARD, "```css\nbody { color: red; }\n```")
        assertEquals("body { color: red; }", builder.style())
        cards.save(ArticleBuilder.TEMPLATE_CARD, "<html><style>{{style}}</style><title>{{title}}</title><h1>{{title}}</h1>{{body}}</html>")
        assertTrue(builder.template().startsWith("<html><style>"))
        assertNull(builder.templateProblem())
        // One that leaves a place out is not used, and the reason is given.
        cards.save(ArticleBuilder.TEMPLATE_CARD, "<html>{{body}}</html>")
        assertEquals(File(assets, "article/template.html").readText(), builder.template())
        assertTrue(builder.templateProblem()!!.contains("现在用的是自带模板"))
    }

    /** A computer that answers the render script as the real one does, and keeps what it was sent. */
    private class Computer(private val answer: String) : PcGateway {
        var command = ""
        var page = ""
        override fun exec(profile: PcProfile, command: String): String = ""
        override fun send(profile: PcProfile, command: String, input: ByteArray): String {
            this.command = command
            page = String(input, Charsets.UTF_8)
            return answer
        }
        override fun connect(profile: PcProfile) = Unit
        override fun isConnected() = true
        override fun info(profile: PcProfile): PcInfo = throw UnsupportedOperationException()
        override fun watchUpdates(profile: PcProfile, manifestUrl: String, onLine: (String) -> Unit) = AutoCloseable { }
        override fun publicKey() = ""
        override fun close() = Unit
    }

    private fun tool(computer: Computer) = RenderArticleTool(
        cards, builder, AgentHarness(computer, PcProfile("h", 22, "u"), "key", "model") { "" }
    ) { File(assets, "harness/render-article.sh").readText() }

    private fun call(tool: RenderArticleTool, vararg pairs: Pair<String, String>) = tool.execute(JSONObject(pairs.toMap()).toString())

    @Test
    fun aCardIsDrawnOnTheComputerAndThePictureComesBackToBeLookedAt() {
        val picture = cards.saveMedia(byteArrayOf(1, 2, 3), "png")!!
        val text = "# 稿子\n\n正文。\n\n![图](media:$picture)\n\n![缺的](media:m000000000000.png)"
        cards.save("产品/公众号文章/原文", text)
        val computer = Computer("PATH /home/u/reduction-agents/articles/原文.png\nSIZE 1080 4200\nSHOWN top 3600 of 4200\n\n=====IMAGE article.jpeg image/jpeg=====\nQUJD")
        val tool = tool(computer)

        val said = call(tool, "card" to "产品/公众号文章/原文")
        assertTrue(said, said.startsWith("出好了：/home/u/reduction-agents/articles/原文.png（1080×4200）"))
        assertTrue(said, said.contains("开头 3600 像素") && said.contains("画面随后给出"))
        assertTrue(said, said.contains("图里是空的：m000000000000.png"))
        val shown = tool.pictures().single()
        assertEquals(listOf("「产品/公众号文章/原文」的长图", "image/jpeg", "QUJD"), listOf(shown.name, shown.mediaType, shown.base64))
        // What went to the computer: the card's own name for the file, and the page with the article and its picture in it.
        assertTrue(computer.command, computer.command.contains("'原文'"))
        val data = JSONObject(text(computer.page, "article-data"))
        assertEquals(cards.document("产品/公众号文章/原文"), data.getString("source"))
        assertEquals("data:image/png;base64,AQID", data.getJSONObject("media").getString(picture))
        // The card is as it was.
        assertEquals(text, cards.read("产品/公众号文章/原文"))
    }

    @Test
    fun aDrawingAttachedToTheCardGoesIntoThePageAsADrawing() {
        val id = cards.saveMedia("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(), "svg")!!
        cards.save("稿子", "![图](media:$id)")
        val computer = Computer("PATH /x/稿子.png\nSIZE 1080 600\n")
        call(tool(computer), "card" to "稿子")
        val address = JSONObject(text(computer.page, "article-data")).getJSONObject("media").getString(id)
        assertTrue(address, address.startsWith("data:image/svg+xml;base64,"))
    }

    @Test
    fun whatGoesWrongOnTheComputerIsSaidAndNothingIsShown() {
        cards.save("稿子", "正文")
        val tool = tool(Computer("出图失败：这台电脑上没有 Chrome 或 Chromium\n"))
        assertEquals("出图失败：这台电脑上没有 Chrome 或 Chromium", call(tool, "card" to "稿子"))
        assertTrue(tool.pictures().isEmpty())
        assertTrue(call(tool, "card" to "没有").startsWith("没有名为“没有”的卡片"))
        assertTrue(call(tool).startsWith("没有生效"))
    }

    @Test
    fun theTemplateIsWrittenOutAsCardsOnceAndNeverOverWhatIsThere() {
        val tool = tool(Computer(""))
        val first = call(tool, "action" to "template")
        assertTrue(first, first.startsWith("写好了：${ArticleBuilder.TEMPLATE_CARD}、${ArticleBuilder.STYLE_CARD}"))
        // Read back through the builder they are exactly the bundled ones.
        assertEquals(File(assets, "article/template.html").readText().trim(), builder.template())
        assertEquals(File(assets, "article/article.css").readText().trim(), builder.style())
        cards.save(ArticleBuilder.STYLE_CARD, "```css\nbody { color: red; }\n```")
        assertTrue(call(tool, "action" to "template").startsWith("已经有了，没有覆盖"))
        assertEquals("body { color: red; }", builder.style())
    }
}
