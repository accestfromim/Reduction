package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.feature.chat.data.CardPatch
import com.mindtravel.reduction.feature.chat.data.CardRepository
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
