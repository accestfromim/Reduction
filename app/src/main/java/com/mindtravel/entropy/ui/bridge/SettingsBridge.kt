package com.mindtravel.entropy.ui.bridge

import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.core.settings.AppSettings
import com.mindtravel.entropy.feature.chat.ui.Conversation
import com.mindtravel.entropy.feature.settings.data.SettingsRepository

class SettingsBridge(
    private val settings: SettingsRepository,
    private val conversation: Conversation
) {
    fun loadSettings(): String = settings.load().toJson().toString()

    fun saveSettings(json: String): Boolean = runCatching {
        settings.save(AppSettings.fromJson(json))
        true
    }.getOrElse {
        Logs.add("saveSettings failed: ${it.message}")
        false
    }

    fun loadHistory(): String = conversation.historyJson()

    fun clearHistory() = conversation.clear()
}
