package com.mindtravel.reduction.feature.article

import org.json.JSONObject
import java.util.Base64

/**
 * Puts together the page that builds an article by itself: `article/builder.html` with the
 * libraries and the build script inside it as they are, and the template, the stylesheets
 * and the article as base64.
 * A browser that opens the result needs nothing else — no server, no files beside it — and
 * becomes the finished article page.
 *
 * The template and the stylesheet are the bundled ones unless the user keeps their own in
 * the cards [TEMPLATE_CARD] and [STYLE_CARD]; then those are used, which is how the look
 * of articles is changed without touching the app.
 */
class ArticleBuilder(
    /** A bundled file's text by its path under the assets, such as "article/template.html". */
    private val asset: (String) -> String,
    /** A bundled file's bytes, for the fonts. */
    private val assetBytes: (String) -> ByteArray,
    /** A card's text, or null when there is no such card. */
    private val card: (String) -> String?
) {
    /** The template in use: the user's card when it holds one that can be used, the bundled one otherwise. */
    fun template(): String = own(TEMPLATE_CARD)?.takeIf { usable(it) } ?: asset("article/template.html")

    fun style(): String = own(STYLE_CARD) ?: asset("article/article.css")

    /** A bundled file as it comes with the app, whatever the cards say. */
    fun bundled(path: String): String = asset(path)

    /** Why the template card cannot be used, or null when there is none or it can. */
    fun templateProblem(): String? {
        val text = own(TEMPLATE_CARD) ?: return null
        return if (usable(text)) null else "卡片「$TEMPLATE_CARD」里 {{style}}、{{body}} 要各出现一次，{{title}} 两次；现在用的是自带模板"
    }

    private fun usable(template: String): Boolean =
        count(template, "{{style}}") == 1 && count(template, "{{body}}") == 1 && count(template, "{{title}}") == 2

    private fun count(text: String, part: String) = text.split(part).size - 1

    /** A card kept as a block of code is taken without its fence. */
    private fun own(name: String): String? {
        val text = card(name)?.trim().orEmpty()
        if (text.isEmpty()) return null
        val fenced = FENCE.matchEntire(text)
        return (fenced?.groupValues?.get(1) ?: text).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The page that builds the article of [source]. [media] maps the ids of the pictures it
     * refers to onto data: addresses, so that the page carries its pictures with it.
     */
    fun page(source: String, media: Map<String, String> = emptyMap()): String {
        val data = JSONObject().put("source", source).put("media", JSONObject(media)).toString()
        val libraries = listOf("vendor/marked.umd.js", "vendor/purify.min.js", "vendor/katex/katex.min.js")
            .joinToString("\n;\n") { asset(it) }
        return fill(
            asset("article/builder.html"),
            "[[libraries]]" to script(libraries),
            "[[build]]" to script(asset("article/build.js")),
            "[[template]]" to carried(template()),
            "[[style]]" to carried(style()),
            "[[katex-style]]" to carried(katexStyle()),
            "[[data]]" to carried(data)
        )
    }

    /** KaTeX's stylesheet with its fonts inside it, so that formulas are set the same wherever the page is opened. */
    fun katexStyle(): String = FONT.replace(asset("vendor/katex/katex.min.css")) { match ->
        val file = match.groupValues[1]
        // One form of each font is enough, and woff2 is the one every browser that can run this takes.
        if (!file.endsWith(".woff2")) return@replace "url(data:,)"
        val bytes = runCatching { assetBytes("vendor/katex/$file") }.getOrNull() ?: return@replace "url(data:,)"
        "url(data:font/woff2;base64,${Base64.getEncoder().encodeToString(bytes)})"
    }

    /**
     * Code that goes inside a script element must not end it. Written "<\/script" it is the same
     * to JavaScript and nothing to the page that holds it.
     */
    private fun script(code: String): String = code.replace("</script", "<\\/script", ignoreCase = true)

    /** Text for the builder to read, as base64 of its UTF-8: nothing in it can be taken for part of the page. */
    private fun carried(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun fill(page: String, vararg parts: Pair<String, String>): String {
        var filled = page
        for ((place, text) in parts) {
            val at = filled.lastIndexOf(place)
            require(at >= 0) { "builder.html has no $place" }
            filled = filled.substring(0, at) + text + filled.substring(at + place.length)
        }
        return filled
    }

    companion object {
        const val TEMPLATE_CARD = "文章模板/template.html"
        const val STYLE_CARD = "文章模板/article.css"
        private val FENCE = Regex("^```[a-zA-Z]*\\n([\\s\\S]*?)\\n```$")
        private val FONT = Regex("url\\(([^)]+)\\)")
    }
}
