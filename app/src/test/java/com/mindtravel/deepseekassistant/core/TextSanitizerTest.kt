package com.mindtravel.deepseekassistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSanitizerTest {

    @Test
    fun `plain null chunks are removed`() {
        assertEquals("", TextSanitizer.stripLeadingNullChunks("null"))
        assertEquals("", TextSanitizer.stripLeadingNullChunks("nullnullnull"))
    }

    @Test
    fun `null prefix before text is removed`() {
        assertEquals("你好", TextSanitizer.stripLeadingNullChunks("nullnull你好"))
        assertEquals("你好", TextSanitizer.stripLeadingNullChunks("null你好"))
    }

    @Test
    fun `legitimate english null is preserved`() {
        assertEquals("null pointer", TextSanitizer.stripLeadingNullChunks("null pointer"))
    }

    @Test
    fun `history strips only repeated null prefix`() {
        assertEquals("你好", TextSanitizer.stripRepeatedNullPrefix("nullnull你好"))
        assertEquals("null pointer", TextSanitizer.stripRepeatedNullPrefix("null pointer"))
    }
}
