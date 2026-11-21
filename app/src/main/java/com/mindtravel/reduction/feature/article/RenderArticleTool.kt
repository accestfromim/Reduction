package com.mindtravel.reduction.feature.article

import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.tools.AgentHarness
import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject
import java.util.Base64

/**
 * Draws a card as the article it is published as: the long picture, made on the computer by
 * its own browser in a few seconds, kept there as a file, and shown to the assistant. The
 * page is the one the card's preview shows, built by the same script from the same template.
 */
class RenderArticleTool(
    private val cards: CardRepository,
    private val builder: ArticleBuilder,
    private val harness: AgentHarness,
    private val script: () -> String
) : ChatTool {

    override val spec = ToolSpec(
        name = "render_article",
        description = "把一张卡片按文章模板渲染成发布用的长图（宽 1080 像素）。在电脑上出图，几秒钟，图片存在电脑的 ~/reduction-agents/articles/ 里，" +
            "同时把画面给你看：短文是整张，长文是开头一段。卡片本身不会被改动。" +
            "文章的样子由模板决定：有卡片「${ArticleBuilder.TEMPLATE_CARD}」和「${ArticleBuilder.STYLE_CARD}」就用它们，没有就用自带的。" +
            "action=template 可将模板保存为 CARD 代码组件。",
        parameters = listOf(
            ToolParam("action", "render：渲染一张卡片（默认）", required = false, choices = listOf("render","template")),
            ToolParam("card", "render 时必填：要渲染的卡片名称，与目录中的一致", required = false)
        )
    )

    private var showing: List<ChatImage> = emptyList()

    override fun pictures(): List<ChatImage> = showing.also { showing = emptyList() }

    override fun describe(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        return if (args.stringOrNull("action")?.trim() == "template") "取出文章模板" else "出长图：" + args.stringOrNull("card").orEmpty().trim().take(18)
    }

    override fun execute(argumentsJson: String): String {
        showing = emptyList()
        val args = JSONObject(argumentsJson)
        if (args.stringOrNull("action")?.trim() == "template") return writeTemplate()
        val name = args.stringOrNull("card")?.trim().orEmpty()
        if (name.isEmpty()) return "没有生效：render 要填 card"
        cards.requireShareable(name)
        val text = cards.document(name) ?: return "没有名为“$name”的卡片。现有卡片：" + cards.list().joinToString("、") { it.name }.ifBlank { "无" }
        if (text.isBlank()) return "没有生效：卡片“$name”是空的"
        if (!harness.isConfigured) return "出不了图：没有连上电脑"

        val media = LinkedHashMap<String, String>()
        val missing = ArrayList<String>()
        val public = builder.publicSource(text)
        for (id in cards.mediaIn(public)) {
            val kind = id.substringAfterLast('.')
            if (kind !in CardRepository.PICTURE_KINDS) continue
            val file = cards.mediaFile(id)
            if (file == null) missing += id
            else media[id] = "data:${CardRepository.pictureType(kind)};base64," + Base64.getEncoder().encodeToString(file.readBytes())
        }
        val file = name.substringAfterLast('/').ifBlank { "article" }
        val output = harness.runScript("sh -s --", script(), file, input = builder.page(public, media).toByteArray(Charsets.UTF_8))
        val progress = AgentHarness.parse("STATE done 0\n$output")
        val path = LINE_PATH.find(output)?.groupValues?.get(1)
        val size = LINE_SIZE.find(output)
        if (path == null || size == null) {
            return "出图失败：" + output.substringBefore("\n=====IMAGE").lines().firstOrNull { it.isNotBlank() }.orEmpty().removePrefix("出图失败：").ifBlank { "电脑没有返回结果" }
        }
        showing = progress.images.map { it.copy(name = "「$name」的长图") }
        return buildString {
            appendLine("出好了：$path（${size.groupValues[1]}×${size.groupValues[2]}）")
            LINE_SHOWN.find(output)?.let { appendLine("图太长，给你看的是开头 ${it.groupValues[1]} 像素，全图在上面的文件里。") }
            if (showing.isNotEmpty()) appendLine("画面随后给出。") else appendLine("画面没有带回来，只有文件。")
            if (missing.isNotEmpty()) appendLine("这些图片不在这台设备上，图里是空的：" + missing.joinToString("、"))
            builder.templateProblem()?.let { appendLine(it) }
        }.trim()
    }

    /** The bundled template and stylesheet as cards of the user's, to be changed like any card. What is there stays. */
    private fun writeTemplate(): String {
        val made = ArrayList<String>()
        val kept = ArrayList<String>()
        for ((card, asset, language, purpose) in listOf(
            Quad(ArticleBuilder.TEMPLATE_CARD, "article/template.html", "html", "文章模板的页面骨架：{{style}}、{{body}} 各一处，{{title}} 两处"),
            Quad(ArticleBuilder.STYLE_CARD, "article/article.css", "css", "文章模板的样式：字号、颜色、间距")
        )) {
            if (cards.read(card) != null) { kept += card; continue }
            if (cards.save(card, com.mindtravel.reduction.feature.chat.data.CardDocument.from("```$language\n" + builder.bundled(asset).trim() + "\n```"), purpose)) made += card
        }
        return buildString {
            if (made.isNotEmpty()) appendLine("写好了：" + made.joinToString("、") + "。改它们就是改文章的样子，删掉就回到自带模板。")
            if (kept.isNotEmpty()) appendLine("已经有了，没有覆盖：" + kept.joinToString("、"))
        }.trim()
    }

    private data class Quad(val card: String, val asset: String, val language: String, val purpose: String)

    private companion object {
        val LINE_PATH = Regex("(?m)^PATH (.+)$")
        val LINE_SIZE = Regex("(?m)^SIZE (\\d+) (\\d+)$")
        val LINE_SHOWN = Regex("(?m)^SHOWN top (\\d+) of \\d+$")
    }
}
