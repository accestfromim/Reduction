package com.mindtravel.reduction.feature.chat.model.tools

import org.json.JSONObject
import org.json.JSONTokener

const val CARD_COMPONENT_GUIDE = "段落 {text}，标题 {text,style:h1}，公众号标题后的 API 开销、开源说明和仓库链接合写一个 {text,style:routine}，截图或音视频 {media,caption?}，矢量图 {svg}，表格 {rows,columns?,caption?}，时间轴 {timeline}。标注唯一单行原文用 {date:原文} 或 {note:私人标注原文}。"

/** Some model gateways quote the nested object again. Decode that transport layer once. */
fun cardOperation(args: JSONObject): JSONObject? {
    val misplaced = args.keys().asSequence().filterNot { it in setOf("card","do","summary") }.toList()
    require(misplaced.isEmpty()) { "字段 ${misplaced.joinToString()} 属于 do。示例：{\"card\":\"卡名\",\"do\":{\"note\":\"单行原文\"}}" }
    if (!args.has("do")) return null
    val value = args.get("do")
    if (value is JSONObject) return value
    if (value is String) {
        val parser = JSONTokener(value)
        val parsed = runCatching { parser.nextValue() }.getOrNull()
        if (parsed is JSONObject && parser.nextClean().code == 0) return parsed
    }
    throw IllegalArgumentException("""do 需要一个 CARD 组件对象，例如 {"media":"附件编号","caption":"图注"}""")
}

/** Only self-contained drawing primitives belong in an authored diagram. */
fun cardSvg(source: String): org.jsoup.nodes.Element {
    val doc = org.jsoup.Jsoup.parse(source, "", org.jsoup.parser.Parser.xmlParser())
    val root = doc.children().singleOrNull()
    require(root != null && root.tagName() == "svg") { "需要单个 SVG 根元素" }
    val tags = setOf("svg","g","path","rect","circle","ellipse","line","polyline","polygon","text","tspan","title","desc")
    val attrs = "xmlns viewBox width height x y dx dy cx cy r rx ry x1 x2 y1 y2 points d fill stroke stroke-width stroke-linecap stroke-linejoin fill-rule opacity font-family font-size font-weight text-anchor letter-spacing dominant-baseline transform role aria-label".split(" ").toSet()
    for (el in root.getAllElements()) {
        require(el.tagName() in tags && el.attributes().all { it.key in attrs && !it.value.contains("url(", true) }) { "SVG 仅支持静态图形、文字和绘图属性" }
        for (key in listOf("fill","stroke")) if (el.hasAttr(key)) {
            require(Regex("#[a-fA-F0-9]{3,8}|[a-zA-Z]+").matches(el.attr(key))) { "颜色请使用色名或十六进制" }
        }
    }
    return root
}
