package com.mindtravel.reduction.feature.chat.data

import org.jsoup.parser.Parser
import org.jsoup.nodes.TextNode
import org.json.JSONObject

/** Patch the original source range so unrelated markup and attachments keep their exact bytes. */
object CardAnnotation {
    fun apply(source: String, op: JSONObject): String {
        val key = if (op.has("note")) "note" else "date"
        require(op.length() == 1) { "此标注操作只接受 $key 字段。请只传唯一的单行原文；桌面端可先用 blocks 查看位置。" }
        val phrase = op.getString(key)
        require(phrase.isNotBlank() && phrase.none { it in "<>\r\n" }) { "请选择单行原文" }
        val doc = Parser.htmlParser().setTrackPosition(true).parseInput(source, "")
        val matches = mutableListOf<Int>()
        for (node in doc.body().nodeStream().filter { it is TextNode }.iterator()) {
            val parent = node.parent() as? org.jsoup.nodes.Element ?: continue
            if (parent.closest("script,style,code,pre,svg,card-private,card-date,[data-card-privacy]") != null) continue
            val range = node.sourceRange()
            if (!range.isTracked) continue
            val start = range.start().pos()
            val raw = source.substring(start,range.end().pos())
            var offset = raw.indexOf(phrase)
            while (offset >= 0) {
                matches.add(start + offset)
                offset = raw.indexOf(phrase,offset + 1)
            }
        }
        require(matches.size == 1) { "请选择唯一且尚未标注的源码文字" }
        val tag = if (key == "note") "card-private" else "card-date"
        val start = matches.single()
        val end = start + phrase.length
        if (key == "note" && start >= 6 && source.substring(start-6,start) == "{note:" && source.getOrNull(end) == '}') {
            return source.replaceRange(start-6,end+1,"<$tag>$phrase</$tag>")
        }
        return source.replaceRange(start,end,"<$tag>$phrase</$tag>")
    }
}
