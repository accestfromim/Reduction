package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.chat.ui.Conversation
import com.mindtravel.reduction.feature.settings.data.SettingsRepository
import com.mindtravel.reduction.hardware.pc.PcProfile

class SettingsBridge(
    private val settings: SettingsRepository,
    private val conversation: Conversation,
    private val onPcChanged: () -> Unit
) {
    fun loadSettings(): String = settings.load().toJson().toString()

    fun saveSettings(json: String): Boolean = runCatching {
        val previous = PcProfile(settings.load())
        val next = AppSettings.fromJson(json)
        settings.save(next)
        if (PcProfile(next) != previous) onPcChanged()
        true
    }.getOrElse {
        Logs.add("saveSettings failed: ${it.message}")
        false
    }

    fun loadHistory(): String = conversation.historyJson()

    fun clearHistory() = conversation.clear()
}
