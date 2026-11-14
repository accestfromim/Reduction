package com.mindtravel.deepseekassistant.core.settings

interface SettingsGateway {
    fun load(): AppSettings
}
