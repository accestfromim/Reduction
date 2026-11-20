package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CardTool(private val cards: CardRepository, files: CardFiles, settings: AppSettings) : ChatTool {
    private val storage = files.tool(settings)
    private val blocks = CardBlocksTool(cards, files)
    private var images = emptyList<ChatImage>()
    override fun pictures() = images.also { images = emptyList() }
    override val spec = ToolSpec("card", "电脑端 CARD。只传 card 看组件摘要。每次传一种意图。", listOf(
        ToolParam("card","卡片全名"),
        ToolParam("do","""引文用 {quote,source}。时间轴用 {timeline:[[时间,事件],…]}。追加用 {text}、{rows,columns?,caption?,view?}、{url,text}；style 可选 h1/h2/h3/blockquote，view 可选 table/bar/share。修改用 {row,text,table?,column?} 或 {target,text 或 view}，target 是原文或表题，歧义时用目录返回的 ref。{attach:绝对路径} 挂原件；{media:绝对路径或已挂文件名,caption?} 插入媒体。{attachments:true} 列清单，{read:文件名} 读附件，{export:html/markdown/pdf/png/zip} 导出。""", false, jsonType="object")
    ))

    override fun execute(argumentsJson: String): String {
        images = emptyList()
        val args = JSONObject(argumentsJson)
        val name = args.getString("card")
        val request = JSONObject().put("card", name)
        if (!args.has("do")) return blocks.execute(request.toString())
        val op = args.getJSONObject("do")
        val keys = op.keySet()
        fun only(vararg fields: String) = require(fields.toSet().containsAll(keys)) { "操作含多余字段，请每次指定一种意图" }
        fun fileAction(action: String, field: String? = null, value: String? = null): String {
            val q = JSONObject().put("card",name).put("action",action)
            if (field != null) q.put(field,value)
            return storage.execute(q.toString())
        }
        fun entries(): Map<String,JSONObject> {
            val list = JSONArray(fileAction("list"))
            val all = (0 until list.length()).map { list.getJSONObject(it) }
            val counts = all.groupingBy { it.getString("name") }.eachCount()
            val labels = all.map { it.getString("name") }.toMutableSet()
            return all.associateBy {
                val label = it.getString("name")
                if (counts[label] == 1) label else {
                    var candidate = "$label [" + it.getString("id") + "]"
                    while (!labels.add(candidate)) candidate += "#"
                    candidate
                }
            }
        }
        fun resolve(label: String) = entries()[label] ?: error("未唯一匹配文件，请查看附件清单")
        fun update(change: JSONObject) = blocks.execute(request.put("change", change).toString())
        return when {
            op.has("export") -> { only("export"); JSONArray("["+fileAction("export","format",op.getString("export"))+"]").getString(0) }
            op.has("attachments") -> { only("attachments"); require(op.getBoolean("attachments")); JSONArray(entries().keys.toList()).toString() }
            op.has("read") -> {
                only("read")
                val result = fileAction("read","id",resolve(op.getString("read")).getString("id"))
                images = storage.pictures()
                val meta = runCatching { JSONObject(result) }.getOrNull()
                if (meta?.has("original")==true) meta.optString("status","已读取") else result
            }
            op.has("attach") -> { only("attach"); synchronized(cards) { fileAction("import","path",op.getString("attach")) }; "已挂载" }
            op.has("media") -> synchronized(cards) {
                only("media","caption")
                val value = op.getString("media")
                val imported = File(value).isAbsolute || Regex("""^[A-Za-z]:[\\/].*""").matches(value)
                val meta = if (imported) JSONObject(fileAction("import","path",value)) else resolve(value)
                val id = meta.getString("id")
                try {
                    update(JSONObject().put("type","media").put("id",id).put("caption",op.optString("caption")))
                } catch (failure: Exception) {
                    if (imported) check(File(cards.attachmentDirectory(name),id).deleteRecursively()) { "正文写入失败，附件回滚失败" }
                    throw failure
                }
            }
            op.has("row") -> { only("row","table","column","text"); update(op) }
            op.has("ref") || op.has("target") -> { only(if(op.has("target")) "target" else "ref","text","view"); update(op) }
            op.has("quote") -> { only("quote","source"); update(op.put("type","quote")) }
            op.has("timeline") -> { only("timeline"); update(op.put("type","timeline")) }
            op.has("rows") -> { only("rows","columns","caption","view"); update(op.put("type","table")) }
            op.has("url") -> { only("url","text"); update(op.put("type","link")) }
            else -> { only("text","style"); update(op.put("type","text")) }
        }
    }
}
