package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.chat.ui.Conversation
import com.mindtravel.reduction.feature.settings.data.SettingsRepository

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
