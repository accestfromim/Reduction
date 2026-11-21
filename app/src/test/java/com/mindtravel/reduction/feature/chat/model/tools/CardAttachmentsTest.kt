package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.ChatCallbacks
import com.mindtravel.reduction.feature.chat.model.ChatSession
import com.mindtravel.reduction.model.chat.ChatGateway
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.ChatRequest
import com.mindtravel.reduction.model.chat.ChatStreamEvent
import com.mindtravel.reduction.model.chat.Role
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardAttachmentsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val cards by lazy { CardRepository(temporary.newFolder()) }

    private fun read(tool: ReadCardTool, vararg pairs: Pair<String, String>) = tool.execute(JSONObject(pairs.toMap()).toString())

    private fun pdf(pages: Int): String {
        val id = cards.saveMedia("%PDF-1.4".toByteArray(), "pdf")!!
        for (page in 1..pages) assertTrue(cards.savePage(id, page, byteArrayOf(page.toByte(), 2, 3)))
        return id
    }

    @Test
    fun aPdfIsKeptWithItsPagesAndTheyGoWhenItDoes() {
        val id = pdf(3)
        assertEquals(listOf("$id.p1.jpg", "$id.p2.jpg", "$id.p3.jpg"), cards.pages(id).map { it.name })
        assertEquals(cards.pages(id)[1], cards.pageFile("$id.p2.jpg"))
        // A page is only kept for a PDF that is there, and only under a name that is a page's.
        assertFalse(cards.savePage("m000000000000.pdf", 1, byteArrayOf(1)))
        assertFalse(cards.savePage(id, 0, byteArrayOf(1)))
        assertEquals(null, cards.pageFile("../$id.p1.jpg"))
        cards.save("报告", "![季报](media:$id)")
        assertEquals(setOf(id, "$id.p1.jpg", "$id.p2.jpg", "$id.p3.jpg"), cards.heldMedia())
    }

    @Test
    fun readingACardGivesItsTextAsItIsAndItsAttachmentsToLookAt() {
        val picture = cards.saveMedia(byteArrayOf(9, 9), "png")!!
        val report = pdf(6)
        val film = cards.saveMedia(byteArrayOf(1), "mp4")!!
        val text = "# 报告\n\n![现场](media:$picture)\n\n![季报](media:$report)\n\n![演示](media:$film)\n结尾"
        cards.save("报告", text)
        val tool = ReadCardTool(cards)

        val said = read(tool, "name" to "报告")
        // The card's own text comes first, word for word; what is attached is told of after it.
        assertTrue(said, said.startsWith(cards.document("报告") + "\n\n———\n以上是卡片原文。"))
        assertEquals(text, cards.read("报告"))
        assertTrue(said, said.contains("- 图片「现场」：画面随后给出"))
        assertTrue(said, said.contains("- PDF「季报」：共 6 页，第 1–4 页的画面随后给出；要看后面的页，再读一次并填 pages=5-6"))
        assertTrue(said, said.contains("- 视频「演示」：只能在卡片页播放"))
        val shown = tool.pictures()
        assertEquals(listOf("图片「现场」", "PDF「季报」第 1 页", "PDF「季报」第 2 页", "PDF「季报」第 3 页", "PDF「季报」第 4 页"), shown.map { it.name })
        assertEquals(listOf("image/png", "image/jpeg"), shown.map { it.mediaType }.distinct())
        // Handed over once.
        assertTrue(tool.pictures().isEmpty())

        val later = read(tool, "name" to "报告", "pages" to "5-6")
        assertTrue(later, later.contains("共 6 页，第 5–6 页的画面随后给出") && !later.contains("再读一次"))
        assertEquals(listOf("图片「现场」", "PDF「季报」第 5 页", "PDF「季报」第 6 页"), tool.pictures().map { it.name })
    }

    @Test
    fun aDrawingAttachedIsKeptAsItIsAndReadAsItsSourceNotHandedOverAsAPicture() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>"
        val id = cards.saveMedia(svg.toByteArray(), "svg")!!
        assertTrue(id.endsWith(".svg"))
        assertEquals("image/svg+xml", CardRepository.pictureType("svg"))
        cards.save("图", "![示意](media:$id)")
        val tool = ReadCardTool(cards)
        val said = read(tool, "name" to "图")
        assertTrue(said, said.contains("- 矢量图「示意」") && said.contains("```svg\n$svg\n```") && said.contains("render_article"))
        assertTrue(tool.pictures().isEmpty())
        assertEquals(setOf(id), cards.heldMedia())
    }

    @Test
    fun aCardWithNothingAttachedReadsExactlyAsBefore() {
        cards.save("备忘", "买花\n周末去爬山")
        val tool = ReadCardTool(cards)
        assertEquals(cards.document("备忘"), read(tool, "name" to "备忘"))
        assertTrue(tool.pictures().isEmpty())
        assertTrue(read(tool, "name" to "没有").startsWith("没有名为“没有”的卡片"))
    }

    @Test
    fun aPdfNotYetDrawnSaysSoRatherThanShowingNothing() {
        val id = cards.saveMedia("%PDF-1.4".toByteArray(), "pdf")!!
        cards.save("报告", "![季报](media:$id)")
        val tool = ReadCardTool(cards)
        assertTrue(read(tool, "name" to "报告").contains("PDF「季报」：还没有渲染成页面"))
        assertTrue(tool.pictures().isEmpty())
    }

    @Test
    fun aPictureIsPutIntoACardByTheIdTheProgramGaveItAndAMadeUpIdIsRefused() {
        val picture = cards.saveMedia(byteArrayOf(7, 7), "jpg")!!
        cards.save("稿子", "第一段\n\n第二段\n\n配图说明：助手先搜昨天\n\n第三段")
        val tool = LocalCardTool(cards, object : com.mindtravel.reduction.core.events.EventSink {
            override fun emit(function: String, vararg args: Any?) {}
        })
        fun append(id: String) = tool.execute(JSONObject().put("card","稿子")
            .put("do",JSONObject().put("media",id).put("caption","截图")).toString())
        assertTrue(runCatching { append("m00000000dead.png") }.isFailure)
        assertFalse(cards.read("稿子")!!.contains("media:"))
        assertEquals("已保存",append(picture))
        val doc = org.jsoup.Jsoup.parse(cards.read("稿子")!!)
        assertEquals("media:$picture",doc.selectFirst("figure img")!!.attr("data-attachment"))
        assertEquals("第一段", doc.selectFirst("p")!!.text())
        assertEquals("截图", doc.selectFirst("figcaption")!!.text())
    }

    /** Answers a first request with a call to read_card, and a second with words; keeps what it was sent. */
    private class Scripted : ChatGateway {
        val sent = ArrayList<List<ChatMessage>>()
        override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
            sent += request.messages
            if (sent.size == 1) {
                onEvent(ChatStreamEvent.ToolCallDelta(0, "c1", "read_card", """{"name":"报告"}"""))
                onEvent(ChatStreamEvent.Finished("tool_calls"))
            } else {
                onEvent(ChatStreamEvent.Content("看到了。"))
                onEvent(ChatStreamEvent.Finished("stop"))
            }
        }
    }

    @Test
    fun whatAToolHasToShowReachesTheModelRightAfterItsAnswerInTheSameReply() {
        cards.save("报告", "![季报](media:${pdf(2)})")
        val gateway = Scripted()
        var done = ""
        ChatSession(gateway, pause = {}).run(
            ChatRequest("k", "m", listOf(ChatMessage(Role.USER, "读报告")), emptyList()),
            ToolRegistry(listOf(ReadCardTool(cards))),
            object : ChatCallbacks {
                override fun onToken(text: String) {}
                override fun onToolStarted(name: String, summary: String, arguments: String, announce: Boolean) {}
                override fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String) {}
                override fun onRetrying(attempt: Int, attempts: Int) {}
                override fun onCompleted(fullText: String) { done = fullText }
                override fun onFailed(message: String) { done = "failed: $message" }
            }
        )
        assertEquals("看到了。", done)
        val second = gateway.sent[1]
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.USER), second.map { it.role })
        assertTrue(second[2].content!!.contains("共 2 页"))
        assertEquals(listOf("PDF「季报」第 1 页", "PDF「季报」第 2 页"), second[3].images.map { it.name })
        assertTrue(second[3].content!!.startsWith("［程序补充，不是用户说的话］"))
        // As the API takes it: words and then the pictures themselves.
        assertEquals(3, second[3].toJson().getJSONArray("content").length())
    }
}
