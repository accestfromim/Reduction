package com.mindtravel.reduction.feature.chat.data

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup

/** Markdown enters at the import boundary. Existing CARD documents retain their exact bytes. */
object CardDocument {
    private val document = Regex("""^\s*(?:<!doctype\s+html\b|<html\b)""", RegexOption.IGNORE_CASE)
    private val extensions = listOf(TablesExtension.create(), StrikethroughExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).softbreak("<br>\n").build()

    fun from(source: String): String {
        if (document.containsMatchIn(source)) return source
        val doc = Jsoup.parseBodyFragment(renderer.render(parser.parse(source)))
        doc.outputSettings().prettyPrint(false)
        for (image in doc.select("img[src^=media:]")) {
            val ref = image.attr("src")
            val kind = when (ref.substringAfterLast('.').lowercase()) {
                "m4a","mp3","wav","ogg","aac","flac","webm" -> "audio"
                "mp4","mov" -> "video"
                else -> "img"
            }
            image.tagName(kind).attr("data-attachment", ref).removeAttr("src")
            if (kind != "img") image.attr("controls", "")
        }
        return "<!doctype html>\n" + doc.outerHtml()
    }
}
