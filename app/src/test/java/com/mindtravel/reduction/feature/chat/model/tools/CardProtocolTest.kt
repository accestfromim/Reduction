package com.mindtravel.reduction.feature.chat.model.tools

import com.mindtravel.reduction.feature.chat.data.CardDocument
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CardProtocolTest {
    @Test fun componentObjectMayHaveOneExtraTransportEncoding() {
        val op = org.json.JSONObject().put("media","m6ecceb99a57f.jpg")
        fun decode(value: Any) = cardOperation(org.json.JSONObject().put("do",value))
        assertEquals(op.toString(),decode(op)?.toString())
        assertEquals(op.toString(),decode(op.toString())?.toString())
        assertTrue(runCatching { decode("普通文字") }.isFailure)
        assertTrue(runCatching { decode(op.toString()+" trailing") }.isFailure)
        assertNull(cardOperation(org.json.JSONObject()))
    }

    @Test fun markdownImportPreservesStructureAndIsIdempotent() {
        val source = "# 标题\n\n| 项 | 数量 |\n|---|---|\n| A | 3 |\n\n- **重点**\n\n<span data-card-privacy=\"note\">私注</span>"
        val card = CardDocument.from(source)
        assertEquals(card, CardDocument.from(card))
        val doc = Jsoup.parse(card)
        assertEquals("标题", doc.selectFirst("h1")!!.text())
        assertEquals("3", doc.select("tbody td").last()!!.text())
        assertEquals("重点", doc.selectFirst("li strong")!!.text())
        assertEquals("私注", doc.selectFirst("[data-card-privacy]")!!.text())
    }
}
