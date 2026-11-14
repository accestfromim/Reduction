package com.mindtravel.deepseekassistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mindtravel.deepseekassistant.core.Logs
import org.json.JSONObject

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "deepseek_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        Logs.add("Encrypted settings unavailable: ${t.javaClass.simpleName}")
        context.getSharedPreferences("deepseek_settings_plain", Context.MODE_PRIVATE)
    }

    fun load(): AppSettings {
        val raw = prefs.getString(KEY, null)
        if (raw == null) return migrateLegacy()
        return runCatching { AppSettings.fromJson(raw) }
            .getOrElse {
                Logs.add("Settings parse failed: ${it.message}")
                migrateLegacy()
            }
    }

    private fun migrateLegacy(): AppSettings {
        val legacy = AppSettings(
            apiKey = prefs.getString("apiKey", "") ?: "",
            model = prefs.getString("model", "deepseek-flash") ?: "deepseek-flash",
            voice = prefs.getString("voice", "stella") ?: "stella",
            autoSpeak = prefs.getBoolean("autoSpeak", true),
            pcUrl = prefs.getString("pcUrl", "") ?: "",
            pcToken = prefs.getString("pcToken", "") ?: "",
            webSearchEnabled = prefs.getBoolean("webSearchEnabled", true),
            calculatorEnabled = prefs.getBoolean("calculatorEnabled", true),
            wakeWordEnabled = prefs.getBoolean("wakeWordEnabled", false),
            bargeInEnabled = prefs.getBoolean("bargeInEnabled", true)
        )
        save(legacy)
        Logs.add("Settings migrated to v2")
        return legacy
    }

    fun save(settings: AppSettings) {
        prefs.edit().putString(KEY, settings.toJson().toString()).apply()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        save(transform(load()))
    }

    companion object {
        private const val KEY = "settings_v2"
    }
}
