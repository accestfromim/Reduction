package com.mindtravel.deepseekassistant.data

import com.mindtravel.deepseekassistant.core.stringOrEmpty
import org.json.JSONObject

data class AppSettings(
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val voice: String = "stella",
    val autoSpeak: Boolean = true,
    val pcUrl: String = "",
    val pcToken: String = "",
    val userName: String = 'user',
    val webSearchEnabled: Boolean = true,
    val calculatorEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("apiKey", apiKey)
        put("model", model)
        put("voice", voice)
        put("autoSpeak", autoSpeak)
        put("pcUrl", pcUrl)
        put("pcToken", pcToken)
        put("userName", userName)
        put("webSearchEnabled", webSearchEnabled)
        put("calculatorEnabled", calculatorEnabled)
        put("wakeWordEnabled", wakeWordEnabled)
    }

    companion object {
        fun fromJson(raw: String): AppSettings {
            val json = JSONObject(raw)
            return AppSettings(
                apiKey = json.stringOrEmpty("apiKey").trim(),
                model = json.stringOrEmpty("model").trim().ifBlank { "deepseek-flash" },
                voice = json.stringOrEmpty("voice").trim().takeIf { it == "tide" } ?: "stella",
                autoSpeak = json.optBoolean("autoSpeak", true),
                pcUrl = json.stringOrEmpty("pcUrl").trim(),
                pcToken = json.stringOrEmpty("pcToken").trim(),
                userName = json.stringOrEmpty("userName").trim().ifBlank { 'user' },
                webSearchEnabled = json.optBoolean("webSearchEnabled", true),
                calculatorEnabled = json.optBoolean("calculatorEnabled", true),
                wakeWordEnabled = json.optBoolean("wakeWordEnabled", false)
            )
        }
    }
}
