package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.feature.chat.data.CardPatch
import com.mindtravel.reduction.feature.chat.data.CardRepository
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

/** Reads one memo card in full. */
class ReadCardTool(private val cards: CardRepository) : ChatTool {

    override val spec = ToolSpec(
        name = "read_card",
        description = "读取一张备忘卡片的完整内容。目录里只有名称和用途；要改一张卡片而不清楚它现在的原文时，先读。",
        parameterName = "name",
        parameterDescription = "卡片名称，与目录中的一致"
    )

    override fun describe(argumentsJson: String): String =
        "读卡片：" + JSONObject(argumentsJson).stringOrNull("name").orEmpty().trim()

    override fun execute(argumentsJson: String): String {
        val name = JSONObject(argumentsJson).stringOrNull("name").orEmpty()
        return cards.read(name) ?: "没有名为“${name.trim()}”的卡片。现有卡片：" +
            cards.list().joinToString("、") { it.name }.ifBlank { "无" }
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
