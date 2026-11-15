package com.mindtravel.deepseekassistant.ui.bridge

import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.settings.AppSettings
import com.mindtravel.deepseekassistant.feature.chat.data.HistoryRepository
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import org.json.JSONArray

class SettingsBridge(
    private val settings: SettingsRepository,
    private val history: HistoryRepository
) {
    fun loadSettings(): String = settings.load().toJson().toString()

    fun saveSettings(json: String): Boolean = runCatching {
        settings.save(AppSettings.fromJson(json))
        true
    }.getOrElse {
        Logs.add("saveSettings failed: ${it.message}")
        false
    }

    fun loadHistory(): String = history.load()

    fun saveHistory(json: String): Boolean = runCatching {
        JSONArray(json)
        history.save(json)
    }.getOrDefault(false)

    fun clearHistory() = history.clear()
}
