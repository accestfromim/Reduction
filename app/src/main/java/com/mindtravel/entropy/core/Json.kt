package com.mindtravel.entropy.core

import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return opt(key) as? String
}

fun JSONObject.stringOrEmpty(key: String): String = stringOrNull(key).orEmpty()

fun JSONObject.arrayOrEmpty(key: String): JSONArray =
    if (!has(key) || isNull(key)) JSONArray() else optJSONArray(key) ?: JSONArray()

fun JSONObject.objectOrNull(key: String): JSONObject? =
    if (!has(key) || isNull(key)) null else optJSONObject(key)

fun JSONObject.intOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
