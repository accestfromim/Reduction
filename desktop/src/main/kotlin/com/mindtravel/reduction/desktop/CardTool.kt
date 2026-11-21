package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import com.mindtravel.reduction.feature.chat.model.tools.cardOperation
import com.mindtravel.reduction.feature.chat.model.tools.CARD_COMPONENT_GUIDE
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CardTool(private val cards: CardRepository, private val files: CardFiles, private val settings: AppSettings, private val sources: CardSources? = null) : ChatTool {
    private val storage = files.tool(settings)
    private val blocks = CardBlocksTool(cards, files)
    private var images = emptyList<ChatImage>()
    override fun pictures() = images.also { images = emptyList() }
    override val spec = ToolSpec("card", "读完整 CARD 源码与版本号，按组件编辑，查看同版本渲染图。", listOf(
        ToolParam("card", "卡片全名"),
        ToolParam("do", "操作字段都放入 do 对象。省略 do 读全文。私人标注用 {\"note\":\"唯一原文\"}；看自己的卡用 {\"render\":true,\"view\":\"author\"}；看公开效果用 {\"render\":true}。长图用 page 翻页。{help:true} 查看组件写法；{blocks:true} 取编辑位置。", false, jsonType="object", fields=listOf(
            ToolParam("note","要标为私人标注的现有单行原文。转换 {note:原文} 时只传括号内的原文，不带 note:。"),
            ToolParam("ref","可与 note 同传，将查找限定到该组件。使用 blocks 返回的当前 ref。"),
            ToolParam("blocks","true 获取当前组件引用",jsonType="boolean"),
            ToolParam("render","true 查看渲染图",jsonType="boolean"),
            ToolParam("view","渲染时 author 含私人标注，public 为公开画面；编辑表格时可取 table、share、bar。")
        ))
    ))

    override fun execute(argumentsJson: String): String {
        images = emptyList()
        val args = JSONObject(argumentsJson)
        val name = args.getString("card")
        val request = JSONObject().put("card", name)
        val op = cardOperation(args) ?: JSONObject().put("source", true)
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
        fun update(change: JSONObject): String {
            if (op.has("at")) change.put("at",op.getString("at"))
            return blocks.execute(request.put("change", change).toString())
        }
        return when {
            op.has("help") -> {
                only("help")
                CARD_COMPONENT_GUIDE + " 编辑用 {target,text} 或 {ref,old,text}，插入用 at 指定 ref。新增私人标注 {note}；限定组件用 {ref,note}，原文须逐字匹配；修改可用 {target:私人标注内唯一原文,text:替换文字}。附件 {attachments:true}，导出 {export:html/markdown/pdf/png/jpg/zip}。"
            }
            op.has("blocks") -> { only("blocks"); blocks.execute(request.toString()) }
            op.has("note") && op.has("ref") -> { only("ref","note"); update(op) }
            op.has("note") || op.has("date") -> synchronized(cards) {
                val source = requireNotNull(cards.document(name))
                val edited = com.mindtravel.reduction.feature.chat.data.CardAnnotation.apply(source,op)
                check(cards.autosave(name,"",edited,false,"",source) == "saved") { "卡片已锁定或正文已变化" }
                "已保存"
            }
            op.has("share") -> { only("share"); require(op.getBoolean("share")); JSONArray("["+fileAction("export","format","html")+"]").getString(0) }
            op.has("source") || op.has("render") -> {
                val source = requireNotNull(cards.document(name)) { "卡片不存在" }
                val revision = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
                if (op.has("source")) {
                    only("source")
                    require(op.getBoolean("source"))
                    JSONObject().put("revision", revision).put("source", source).toString()
                } else synchronized(cards) {
                    only("render","revision","page","view")
                    require(op.getBoolean("render") && op.optString("revision",revision) == revision && cards.document(name) == source) { "正文版本已变化，请重新读取" }
                    val page = op.optInt("page",1)
                    require(page > 0) { "page 从 1 开始" }
                    val view = op.optString("view","public")
                    require(view in setOf("author","public")) { "view 选 author 或 public" }
                    val (picture,count) = files.preview(name,source,page,view == "author")
                    check(cards.document(name) == source) { "正文已更新，请重新读取" }
                    images = listOf(picture)
                    JSONObject().put("revision",revision).put("view",view).put("page",page).put("pages",count).toString()
                }
            }
            op.has("export") -> {
                val format = op.getString("export")
                if (format == "png") only("export","page") else only("export")
                val page = op.optInt("page",1)
                require(page > 0) { "page 从 1 开始" }
                val path = JSONArray("["+fileAction("export","format",format)+"]").getString(0)
                if (format != "png") path else {
                    val (picture,count) = cardPngPage(File(path),page)
                    images = listOf(picture)
                    "$path（画面 $page/$count）"
                }
            }
            op.has("attachments") -> { only("attachments"); require(op.getBoolean("attachments")); JSONArray(entries().keys.toList()).toString() }
            op.has("read") -> {
                only("read")
                val result = fileAction("read","id",resolve(op.getString("read")).getString("id"))
                images = storage.pictures()
                val meta = runCatching { JSONObject(result) }.getOrNull()
                if (meta?.has("original")==true) meta.optString("status","已读取") else result
            }
            op.has("import") || op.has("importMarkdown") -> {
                val key = if (op.has("importMarkdown")) "importMarkdown" else "import"
                only(key,"source")
                val mounts = requireNotNull(sources) { "未配置目录挂载" }
                val folders = JSONArray(mounts.folders())
                val folder = if (op.has("source")) op.getString("source") else
                    (0 until folders.length()).map { folders.getString(it) }.filter { name.startsWith("$it/") }
                        .maxByOrNull { it.length } ?: error("请指定 source 卡片目录")
                val file = mounts.resolve(folder, op.getString(key), settings)
                require(file.isFile) { "导入目标必须是文件" }
                if (key == "importMarkdown") {
                    require(file.extension.lowercase() in setOf("md","markdown") && file.length() <= CardRepository.MAX_CHARS * 4L) { "需要卡片大小范围内的 Markdown 文件" }
                    val document = com.mindtravel.reduction.feature.chat.data.CardDocument.from(file.readText())
                    val status = cards.autosave(name,"",document,true,"","")
                    check(status == "saved") { "导入失败：$status" }
                    "已导入 CARD"
                } else {
                    synchronized(cards) { fileAction("import","path",file.path) }
                    "已挂载"
                }
            }
            op.has("attach") -> { only("attach"); synchronized(cards) { fileAction("import","path",op.getString("attach")) }; "已挂载" }
            op.has("svg") -> {
                only("svg","at")
                val svg = com.mindtravel.reduction.feature.chat.model.tools.cardSvg(op.getString("svg"))
                update(JSONObject().put("type","svg").put("svg",svg.outerHtml()))
            }
            op.has("media") -> synchronized(cards) {
                only("media","caption","at")
                val value = op.getString("media")
                val imported = File(value).isAbsolute || Regex("""^[A-Za-z]:[\\/].*""").matches(value)
                val id = when {
                    imported -> JSONObject(fileAction("import","path",value)).getString("id")
                    cards.mediaFile(value) != null -> value
                    else -> resolve(value).getString("id")
                }
                try {
                    update(JSONObject().put("type","media").put("id",id).put("caption",op.optString("caption")))
                } catch (failure: Exception) {
                    if (imported) check(File(cards.attachmentDirectory(name),id).deleteRecursively()) { "正文写入失败，附件回滚失败" }
                    throw failure
                }
            }
            op.has("row") -> { only("row","table","column","text"); update(op) }
            op.has("ref") || op.has("target") -> { only(if(op.has("target")) "target" else "ref","text","view","old"); update(op) }
            op.has("quote") -> { only("quote","source","at"); update(op.put("type","quote")) }
            op.has("timeline") -> { only("timeline","at"); update(op.put("type","timeline")) }
            op.has("rows") -> { only("rows","columns","caption","view","at"); update(op.put("type","table")) }
            op.has("url") -> { only("url","text","at"); update(op.put("type","link")) }
            else -> { only("text","style","at"); update(op.put("type","text")) }
        }
    }
}
