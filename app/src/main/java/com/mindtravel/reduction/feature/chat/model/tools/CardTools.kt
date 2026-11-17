package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.feature.chat.data.CardPatch
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject

/** Every change to the memo cards: create, edit, delete, any number of them, as one patch. */
class PatchCardsTool(
    private val cards: CardRepository,
    private val emitter: EventSink
) : ChatTool {

    override val spec = ToolSpec(
        name = "patch_cards",
        description = "用一份补丁修改备忘卡片，写法和 git patch 一样，但不写行号。一份补丁可以新建、修改、删除多张卡片，要么全部生效，要么一处都不动。\n" +
            "*** Add Card: 名称      下一行写 Purpose: 一句话用途，之后每行以 + 开头，是卡片内容。\n" +
            "*** Update Card: 名称   之后是改动：以空格开头的行是不变的上下文，以 - 开头的行删掉，以 + 开头的行加上；用单独一行 @@ 分隔同一张卡片里的另一处改动。" +
            "上下文行和 - 行必须照抄卡片原文，它们决定改在哪里。只有 + 行的一处改动表示加在卡片末尾。可以在标题下一行写 Purpose: 来改用途。\n" +
            "*** Delete Card: 名称\n" +
            "*** Move Card: 旧名称 -> 新名称   给卡片改名。名称里的“/”表示文件夹，比如 产品需求/短期；把卡片放进文件夹就是改名。",
        parameterName = "patch",
        parameterDescription = "补丁全文"
    )

    override fun describe(argumentsJson: String): String {
        val patch = JSONObject(argumentsJson).stringOrNull("patch").orEmpty()
        val names = runCatching { CardPatch.parse(patch).map { it.name } }.getOrDefault(emptyList())
        return if (names.isEmpty()) "改卡片" else "改卡片：" + names.distinct().joinToString("、")
    }

    override fun execute(argumentsJson: String): String {
        val patch = JSONObject(argumentsJson).stringOrNull("patch").orEmpty()
        if (patch.isBlank()) return "补丁是空的"
        // A picture can only be one the program has kept: an id that names nothing is a mistake, caught before anything is changed.
        val unknown = cards.mediaIn(patch.lines().filter { it.startsWith("+") }.joinToString("\n")).filter { cards.mediaFile(it) == null }
        if (unknown.isNotEmpty()) {
            return "补丁没有生效。没有这些附件：" + unknown.joinToString("、") + "。附件编号只能用程序给出的：用户发图时消息末尾的编号，或卡片里已有的。"
        }
        return try {
            val changed = cards.patch(patch)
            emitter.emit("onCardsChanged", lastTouched(patch))
            "已生效：" + changed.joinToString("；")
        } catch (e: CardPatch.Invalid) {
            "补丁没有生效。${e.message}"
        }
    }

    /** The card to show afterwards: the last one the patch created or changed. */
    private fun lastTouched(patch: String): String =
        runCatching { CardPatch.parse(patch).lastOrNull { it !is CardPatch.Delete }?.name }.getOrNull().orEmpty()
}

/**
 * Reads one memo card in full: its text exactly as it stands, and after it what is attached to
 * it. Pictures and the pages of PDFs are handed over as pictures, to be looked at; a note after
 * the text says which they are. Nothing of this is written into the card.
 */
class ReadCardTool(private val cards: CardRepository) : ChatTool {

    override val spec = ToolSpec(
        name = "read_card",
        description = "读取一张备忘卡片的完整内容。目录里只有名称和用途；要改一张卡片而不清楚它现在的原文时，先读。" +
            "卡片里挂的图片和 PDF 会作为画面随后给你看：图片是原图，PDF 是逐页渲染的页面。PDF 一次给 $PAGES_AT_ONCE 页，要看后面的页用 pages。",
        parameters = listOf(
            ToolParam("name", "卡片名称，与目录中的一致"),
            ToolParam("pages", "可选：要看 PDF 的哪些页，例如 5-8。不填从第 1 页看起", required = false)
        )
    )

    private var showing: List<ChatImage> = emptyList()

    override fun pictures(): List<ChatImage> = showing.also { showing = emptyList() }

    override fun describe(argumentsJson: String): String =
        "读卡片：" + JSONObject(argumentsJson).stringOrNull("name").orEmpty().trim()

    override fun execute(argumentsJson: String): String {
        showing = emptyList()
        val args = JSONObject(argumentsJson)
        val name = args.stringOrNull("name").orEmpty()
        val text = cards.read(name) ?: return "没有名为“${name.trim()}”的卡片。现有卡片：" +
            cards.list().joinToString("、") { it.name }.ifBlank { "无" }
        val from = PAGE_RANGE.find(args.stringOrNull("pages").orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        val pictures = ArrayList<ChatImage>()
        val notes = ArrayList<String>()
        for (id in cards.mediaIn(text)) {
            val kind = id.substringAfterLast('.')
            val label = LABEL.find(text.substringBefore("(media:$id)").takeLast(80))?.groupValues?.get(1).orEmpty().ifBlank { id }
            when {
                kind in CardRepository.PICTURE_KINDS -> {
                    val file = cards.mediaFile(id)
                    if (file == null) notes += "- 图片「$label」：文件不在这台设备上"
                    else if (pictures.size >= ROOM || file.length() > MAX_PICTURE_BYTES) notes += "- 图片「$label」：这次没有带上（太多或太大）"
                    else {
                        pictures += ChatImage("图片「$label」", if (kind == "jpg") "image/jpeg" else "image/$kind", encode(file.readBytes()))
                        notes += "- 图片「$label」：画面随后给出"
                    }
                }
                kind == "pdf" -> {
                    val pages = cards.pages(id)
                    if (pages.isEmpty()) {
                        notes += "- PDF「$label」：还没有渲染成页面，看不到内容（在卡片页打开一次这张卡片就会渲染）"
                        continue
                    }
                    val first = from.coerceAtMost(pages.size)
                    val last = minOf(pages.size, first + PAGES_AT_ONCE - 1, first + (ROOM - pictures.size) - 1)
                    for (page in first..last) pictures += ChatImage("PDF「$label」第 $page 页", "image/jpeg", encode(pages[page - 1].readBytes()))
                    notes += "- PDF「$label」：共 ${pages.size} 页，" +
                        (if (last >= first) "第 $first–$last 页的画面随后给出" else "这次没有带上页面") +
                        (if (last < pages.size) "；要看后面的页，再读一次并填 pages=${last + 1}-${minOf(pages.size, last + PAGES_AT_ONCE)}" else "")
                }
                else -> notes += "- ${if (kind in VIDEO) "视频" else "录音"}「$label」：只能在卡片页播放，这里看不到内容"
            }
        }
        showing = pictures
        if (notes.isEmpty()) return text
        return text.trimEnd() + "\n\n———\n以上是卡片原文。附件（不属于卡片正文）：\n" + notes.joinToString("\n")
    }

    private fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private companion object {
        /** How many pages of a PDF are shown at a time, and how many pictures in all. */
        const val PAGES_AT_ONCE = 4
        const val ROOM = 6
        const val MAX_PICTURE_BYTES = 4L * 1024 * 1024
        val VIDEO = setOf("mp4", "mov")
        val PAGE_RANGE = Regex("(\\d{1,3})")
        val LABEL = Regex("\\[([^\\]]*)\\]$")
    }
}

/**
 * Finds where something is said across the cards: the cards, the very lines, and the
 * section each line stands in. Nothing is reworded — what comes back is the cards' own text.
 */
class SearchCardsTool(private val cards: CardRepository) : ChatTool {

    override val spec = ToolSpec(
        name = "search_cards",
        description = "按关键词搜所有卡片的正文，返回命中的卡片名、命中的那几行原文和所在小节。要知道一个说法出现在哪几张卡片里时用它，不要逐张 read_card。" +
            "多个关键词用空格分开，默认一行里全都出现才算命中；不分大小写。",
        parameters = listOf(
            ToolParam("query", "关键词，多个用空格分开，例如：验收 截图"),
            ToolParam("match", "all：一行里所有关键词都出现（默认）；any：出现任意一个", required = false, choices = listOf("all", "any")),
            ToolParam("folder", "只搜这个文件夹里的卡片（含子文件夹），例如：产品/开发排期。不填搜全部", required = false)
        )
    )

    override fun describe(argumentsJson: String): String =
        "搜卡片：" + JSONObject(argumentsJson).stringOrNull("query").orEmpty().trim().take(18)

    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val words = args.stringOrNull("query").orEmpty().split(Regex("[\\s,，、]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (words.isEmpty()) return "没有生效：query 为空"
        val any = args.stringOrNull("match")?.trim() == "any"
        val folder = args.stringOrNull("folder")?.trim()?.trim('/').orEmpty()

        val within = cards.list().map { it.name }.filter { folder.isEmpty() || it == folder || it.startsWith("$folder/") }.sorted()
        if (within.isEmpty()) return "没有名为“$folder”的文件夹。现有卡片：" + cards.list().joinToString("、") { it.name }.ifBlank { "无" }

        class Hit(val card: String, val line: Int, val section: String, val text: String)
        val hits = ArrayList<Hit>()
        val named = ArrayList<String>()
        for (name in within) {
            val low = name.lowercase()
            if (if (any) words.any { it in low } else words.all { it in low }) named += name
            var section = ""
            cards.read(name).orEmpty().lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                HEADING.find(line)?.let { section = it.groupValues[1].trim() }
                if (line.isEmpty()) return@forEachIndexed
                val text = line.lowercase()
                if (if (any) words.any { it in text } else words.all { it in text }) hits += Hit(name, index + 1, section, line)
            }
        }
        if (hits.isEmpty() && named.isEmpty()) {
            return "没有命中：${words.joinToString(" ")}（搜了 ${within.size} 张卡片" + (if (any) "" else "，要求一行里全都出现；可以改用 match=any") + "）"
        }
        return buildString {
            val shown = hits.take(MAX_HITS)
            appendLine("命中 ${hits.size} 行，在 ${hits.map { it.card }.distinct().size} 张卡片里（搜了 ${within.size} 张）" + if (hits.size > shown.size) "，下面是前 ${shown.size} 行" else "")
            for ((card, lines) in shown.groupBy { it.card }) {
                appendLine()
                appendLine("## $card")
                for (hit in lines) {
                    val where = if (hit.section.isEmpty()) "" else "〔${hit.section}〕"
                    appendLine("- 第 ${hit.line} 行$where：${hit.text.take(LINE_CHARS)}" + if (hit.text.length > LINE_CHARS) "…" else "")
                }
            }
            if (named.isNotEmpty()) {
                appendLine()
                appendLine("名称里有关键词的卡片：" + named.joinToString("、"))
            }
        }.trimEnd()
    }

    private companion object {
        val HEADING = Regex("^#{1,6}\\s+(.+)$")
        const val MAX_HITS = 60
        const val LINE_CHARS = 300
    }
}
