package com.mindtravel.entropy.core.settings

interface SettingsGateway {
    fun load(): AppSettings
}
