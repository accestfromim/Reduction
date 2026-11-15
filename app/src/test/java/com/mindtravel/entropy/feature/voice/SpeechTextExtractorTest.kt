package com.mindtravel.entropy.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextExtractorTest {

    private fun clean(raw: String) = SpeechTextExtractor.clean(raw)

    @Test
    fun keepsProseAndLinkLabels() {
        assertEquals(
            "标题 你好 世界，这是 链接。",
            clean("# 标题\n\n你好 **世界**，这是 [链接](https://example.com)。")
        )
    }

    @Test
    fun dropsMaths() {
        assertEquals("", clean("\$E=mc^2\$"))
        assertEquals("前文 后文", clean("前文 \$\$\\frac{1}{2}\$\$ 后文"))
        assertEquals("前文 后文", clean("前文\n\$\$\nE=mc^2\n\$\$\n后文"))
        assertEquals("前文 后文", clean("前文 \\(a+b\\) 后文"))
        assertEquals("前文 后文", clean("前文 \\[a+b\\] 后文"))
        assertEquals("未闭合", clean("未闭合 \$x+1"))
    }

    @Test
    fun keepsDollarAmounts() {
        assertEquals("\$5 美元", clean("\$5 美元"))
    }

    @Test
    fun dropsCode() {
        assertEquals("正文", clean("```js\nconsole.log(1)\n```\n正文"))
        assertEquals("这是 文本", clean("这是 `code` 文本"))
    }

    @Test
    fun dropsUrls() {
        assertEquals("看这个 很好", clean("看这个 https://example.com 很好"))
        assertEquals("访问。很好", clean("访问 https://example.com。很好"))
        assertEquals("结束", clean("www.example.com 结束"))
        assertEquals("看 结束", clean("看 https://example.com/a?x=1&y=2 结束"))
    }

    @Test
    fun holdsBackAnUnfinishedMarkerWhileStreaming() {
        val extractor = SpeechTextExtractor()
        assertEquals("", extractor.push("\$E="))
        assertEquals(" 你好。", extractor.push("mc^2\$ 你好。"))
        assertEquals("", extractor.finish())
    }

    @Test
    fun urlSplitAcrossTokensIsStillDropped() {
        val extractor = SpeechTextExtractor()
        val out = extractor.push("看 htt") + extractor.push("ps://example.com 结束") + extractor.finish()
        assertEquals("看  结束", out)
    }
}
