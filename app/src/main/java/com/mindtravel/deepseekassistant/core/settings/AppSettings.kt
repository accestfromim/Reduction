package com.mindtravel.deepseekassistant.core.settings

import com.mindtravel.deepseekassistant.core.stringOrEmpty
import org.json.JSONObject

data class AppSettings(
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val voice: String = "stella",
    val autoSpeak: Boolean = true,
    val pcHost: String = "",
    val pcPort: Int = 22,
    val pcUser: String = "",
    val webSearchEnabled: Boolean = true,
    val calculatorEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val headsetMicEnabled: Boolean = false,
    val opencodeEnabled: Boolean = false,
    val opencodeWriteEnabled: Boolean = false,
    val pcProject: String = "/home/user/DSAssist-iterate"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("apiKey", apiKey)
        put("model", model)
        put("voice", voice)
        put("autoSpeak", autoSpeak)
        put("pcHost", pcHost)
        put("pcPort", pcPort)
        put("pcUser", pcUser)
        put("webSearchEnabled", webSearchEnabled)
        put("calculatorEnabled", calculatorEnabled)
        put("wakeWordEnabled", wakeWordEnabled)
        put("bargeInEnabled", bargeInEnabled)
        put("headsetMicEnabled", headsetMicEnabled)
        put("opencodeEnabled", opencodeEnabled)
        put("opencodeWriteEnabled", opencodeWriteEnabled)
        put("pcProject", pcProject)
    }

    companion object {
        fun fromJson(raw: String): AppSettings {
            val json = JSONObject(raw)
            return AppSettings(
                apiKey = json.stringOrEmpty("apiKey").trim(),
                model = json.stringOrEmpty("model").trim().ifBlank { "deepseek-flash" },
                voice = json.stringOrEmpty("voice").trim().takeIf { it == "tide" } ?: "stella",
                autoSpeak = json.optBoolean("autoSpeak", true),
                pcHost = json.stringOrEmpty("pcHost").trim(),
                pcPort = json.optInt("pcPort", 22).takeIf { it in 1..65535 } ?: 22,
                pcUser = json.stringOrEmpty("pcUser").trim(),
                webSearchEnabled = json.optBoolean("webSearchEnabled", true),
                calculatorEnabled = json.optBoolean("calculatorEnabled", true),
                wakeWordEnabled = json.optBoolean("wakeWordEnabled", false),
                bargeInEnabled = json.optBoolean("bargeInEnabled", true),
                headsetMicEnabled = json.optBoolean("headsetMicEnabled", false),
                opencodeEnabled = json.optBoolean("opencodeEnabled", false),
                opencodeWriteEnabled = json.optBoolean("opencodeWriteEnabled", false),
                pcProject = json.stringOrEmpty("pcProject").trim()
                    .ifBlank { "/home/user/DSAssist-iterate" }
            )
        }
    }
}
