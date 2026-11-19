package com.mindtravel.reduction.core.settings

import com.mindtravel.reduction.core.stringOrEmpty
import org.json.JSONObject

data class AppSettings(
    val apiKey: String = "",
    val model: String = "deepseek-flash",
    val voice: String = DEFAULT_VOICE,
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
    val opencodeSessionId: String = "",
    val pcProject: String = DEFAULT_PROJECT
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
        put("opencodeSessionId", opencodeSessionId)
        put("pcProject", pcProject)
    }

    companion object {
        const val DEFAULT_VOICE = "aishell3-0"
        const val SECOND_VOICE = "aishell3-1"

        fun localVoice(value: String?): String =
            if (value == SECOND_VOICE || value == "tide") SECOND_VOICE else DEFAULT_VOICE

        /** On the computer; "~" is the home directory of the user the phone logs in as. */
        const val DEFAULT_PROJECT = "~/Reduction-iterate"

        /** Where the project directory was under the app's earlier names; it is found at [DEFAULT_PROJECT] now. */
        private val FORMER_DEFAULT_PROJECTS = setOf("~/DSAssist-iterate", "~/dsassist-iterate")

        fun fromJson(raw: String): AppSettings {
            val json = JSONObject(raw)
            return AppSettings(
                apiKey = json.stringOrEmpty("apiKey").trim(),
                model = json.stringOrEmpty("model").trim().ifBlank { "deepseek-flash" },
                voice = localVoice(json.stringOrEmpty("voice").trim()),
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
                opencodeSessionId = json.stringOrEmpty("opencodeSessionId").trim(),
                pcProject = json.stringOrEmpty("pcProject").trim()
                    .ifBlank { DEFAULT_PROJECT }
                    // A path under some home directory that ends in an earlier default is that earlier default.
                    .let { path -> if (FORMER_DEFAULT_PROJECTS.any { path == it || path.endsWith(it.removePrefix("~")) && path.startsWith("/home/") }) DEFAULT_PROJECT else path }
            )
        }
    }
}
