package com.mindtravel.deepseekassistant.core

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {

    @Test
    fun `json null is not a string`() {
        val json = JSONObject().put("value", JSONObject.NULL)
        assertNull(json.stringOrNull("value"))
    }

    @Test
    fun `number is not a string`() {
        val json = JSONObject().put("value", 42)
        assertNull(json.stringOrNull("value"))
    }

    @Test
    fun `missing key is null`() {
        assertNull(JSONObject().stringOrNull("value"))
    }
}
