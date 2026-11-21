package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.feature.chat.data.CardDocument
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONObject
import org.jsoup.Jsoup

class LocalCardTool(private val cards: CardRepository, private val events: EventSink, private val render: ((String, String, Int, Boolean) -> Pair<com.mindtravel.reduction.model.chat.ChatImage, Int>)? = null) : ChatTool {
    private var images = emptyList<com.mindtravel.reduction.model.chat.ChatImage>()
    override fun pictures() = images.also { images = emptyList() }
    override val spec = ToolSpec("card", "只传卡名读完整源码和版本号；{render:true} 看公开画面；{render:true,view:author} 看含私人标注的画面。" + CARD_COMPONENT_GUIDE, listOf(
        ToolParam("card", "完整卡名"),
        ToolParam("do", "追加 {text,style?}、{rows,columns?,caption?,view?}、{timeline:[[时间,事件]]} 或 {media:附件编号,caption?}。修改 {target:原句,text:新句}。", false, jsonType="object")
    ))
    override fun execute(argumentsJson: String): String {
        images = emptyList()
        val args = JSONObject(argumentsJson)
        val name = args.getString("card")
        val saved = cards.document(name)
        val op = cardOperation(args) ?: JSONObject().put("source", true)
        if (op.has("source") || op.has("render")) {
            val source = requireNotNull(saved) { "卡片不存在" }
            val revision = java.security.MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            if (op.has("source")) {
                require(op.length() == 1 && op.getBoolean("source"))
                return JSONObject().put("revision",revision).put("source",source).toString()
            }
            require(op.getBoolean("render") && op.optString("revision",revision) == revision) { "请读取当前版本" }
            val page = op.optInt("page",1)
            val view = op.optString("view","public")
            require(view in setOf("author","public"))
            if (view == "public") cards.requireShareable(name)
            val (image,count) = requireNotNull(render) { "渲染器未连接" }(name,source,page,view == "author")
            check(cards.document(name) == source) { "正文已更新，请重新读取" }
            images = listOf(image)
            return JSONObject().put("revision",revision).put("page",page).put("pages",count).toString()
        }
        if (op.has("help")) return CARD_COMPONENT_GUIDE
        if (op.has("note") || op.has("date")) {
            val source = requireNotNull(saved)
            val edited = com.mindtravel.reduction.feature.chat.data.CardAnnotation.apply(source,op)
            check(cards.autosave(name,"",edited,false,"",source) == "saved") { "卡片已锁定或正文已变化" }
            return "已保存"
        }
        val doc = Jsoup.parse(saved ?: CardDocument.from(""))
        doc.outputSettings().prettyPrint(false)
        val body = doc.body()
        fun only(vararg keys: String) = require(keys.toSet().containsAll(op.keys().asSequence().toSet())) { "未知组件字段" }
        when {
            op.has("target") -> {
                only("target","text")
                val matches = body.getAllElements().filter { it.childrenSize() == 0 && it.text() == op.getString("target") }
                require(matches.size == 1) { "原句不存在或不唯一" }
                matches.single().text(op.getString("text"))
            }
            op.has("svg") -> {
                only("svg")
                body.appendElement("figure").appendChild(cardSvg(op.getString("svg")))
            }
            op.has("rows") -> {
                only("rows","columns","caption","view")
                val table = body.appendElement("table")
                val view = op.optString("view","table")
                require(view in setOf("table","bar","share"))
                if (view != "table") table.attr("data-chart", view)
                if (op.has("caption")) table.appendElement("caption").text(op.getString("caption"))
                op.optJSONArray("columns")?.let { cols ->
                    val row = table.appendElement("thead").appendElement("tr")
                    for (i in 0 until cols.length()) row.appendElement("th").text(cols.getString(i))
                }
                val group = table.appendElement("tbody")
                val rows = op.getJSONArray("rows")
                for (i in 0 until rows.length()) {
                    val values = rows.getJSONArray(i)
                    val row = group.appendElement("tr")
                    for (j in 0 until values.length()) row.appendElement(if (j == 0) "th" else "td").text(values.get(j).toString())
                }
            }
            op.has("timeline") -> {
                only("timeline")
                val list = body.appendElement("ol").attr("data-timeline","")
                val rows = op.getJSONArray("timeline")
                for (i in 0 until rows.length()) {
                    val pair = rows.getJSONArray(i)
                    require(pair.length() == 2)
                    val item = list.appendElement("li")
                    item.appendElement("strong").text(pair.getString(0))
                    item.appendElement("span").text(pair.getString(1))
                }
            }
            op.has("media") -> {
                only("media","caption")
                val id = op.getString("media")
                require(cards.mediaFile(id)?.isFile == true) { "附件不存在" }
                val figure = body.appendElement("figure")
                val tag = when(id.substringAfterLast('.')) {
                    "mp4","mov" -> "video"
                    "m4a","mp3","ogg","wav","flac","aac","webm" -> "audio"
                    else -> "img"
                }
                val media = figure.appendElement(tag).attr("data-attachment","media:$id")
                if (tag != "img") media.attr("controls","")
                if (op.has("caption")) figure.appendElement("figcaption").text(op.getString("caption"))
            }
            else -> {
                only("text","style")
                val tag = op.optString("style","p")
                if (tag == "routine") {
                    val aside = body.appendElement("aside").attr("data-card-routine","")
                    val url = "https://github.com/mindtravel/Reduction"
                    op.getString("text").split(url).forEachIndexed { index, part ->
                        if (index > 0) aside.appendElement("a").attr("href",url).text(url)
                        aside.appendText(part)
                    }
                } else {
                    require(tag in setOf("h1","h2","h3","p","blockquote"))
                    body.appendElement(tag).text(op.getString("text"))
                }
            }
        }
        val status = cards.autosave(name,"",doc.outerHtml(),saved == null,"",saved.orEmpty())
        check(status == "saved") { "写入失败：$status" }
        events.emit("onCardsChanged")
        return "已保存"
    }
}
