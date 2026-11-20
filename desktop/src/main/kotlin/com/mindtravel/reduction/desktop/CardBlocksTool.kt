package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject
import java.io.File

class CardBlocksTool(private val cards: CardRepository, private val files: CardFiles) : ChatTool {
    override val spec = ToolSpec("card_blocks", "读取或编辑电脑端 CARD 组件。只传 card 返回简短组件目录。新卡可直接追加首个组件。", listOf(
        ToolParam("card", "卡片全名"),
        ToolParam("change", """追加组件如 {"type":"text","text":"原话"}；text 可选 style=p/h1/h2/h3/blockquote。table 用 rows，可选 caption、columns、view=table/bar/share。media 用 id、caption；link 用 text、url。按行名改值用 {"row":"订阅","text":"25"}，可用 table、column 指定表名和列名。其他修改用目录中的 ref 加 text 或 view。""", required=false, jsonType="object")
    ))

    override fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val card = args.getString("card")
        val saved = cards.read(card)
        val change = if (args.has("change")) args.getJSONObject("change") else null
        require(saved != null || change != null) { "卡片不存在" }
        if (change != null) {
            require(!cards.isLocked(card) && !cards.isProgramCard(card)) { "卡片已锁定" }
            require(!files.pending(card, saved.orEmpty())) { "用户正在编辑，草稿尚未保存" }
        }
        val request = JSONObject().put("source", saved.orEmpty()).put("change", change)
            .put("revision", args.optString("revision"))
        if (change?.optString("type") == "media") {
            val id = change.getString("id")
            val mime = if (Regex("[0-9a-f]{32}").matches(id)) {
                val meta = File(File(cards.attachmentDirectory(card), id), "meta.json")
                require(meta.isFile && File(meta.parentFile,"original").isFile) { "附件不在此卡片" }
                JSONObject(meta.readText()).getString("type")
            } else {
                require(cards.mediaFile(id) != null) { "附件不存在" }
                java.nio.file.Files.probeContentType(cards.mediaFile(id)!!.toPath()).orEmpty()
            }
            request.put("files", JSONObject().put(id, mime))
        }
        val result = JSONObject(runCardWorker("blocks.py", request))
        if (change == null) return result.toString()
        val output = result.remove("source") as String
        check(!files.pending(card, saved.orEmpty())) { "用户正在编辑，草稿尚未保存" }
        val status = cards.autosave(card, "", output, saved == null, "", saved.orEmpty())
        check(status == "saved") { "组件写入失败：$status" }
        return "已保存"
    }
}
