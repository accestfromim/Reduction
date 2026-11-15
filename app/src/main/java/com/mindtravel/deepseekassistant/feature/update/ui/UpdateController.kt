package com.mindtravel.deepseekassistant.feature.update.ui

import com.mindtravel.deepseekassistant.BuildConfig
import com.mindtravel.deepseekassistant.api.update.UpdateClient
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.host.UpdateHost
import com.mindtravel.deepseekassistant.platform.update.UpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateController(
    private val host: UpdateHost,
    private val client: UpdateClient,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {
    companion object {
        const val MANIFEST_URL = "http://example.com/dsassist/manifest.json"
    }

    @Volatile
    private var checking = false

    fun check() {
        if (checking) return
        checking = true
        scope.launch(Dispatchers.IO) {
            try {
                val manifest = client.fetch(MANIFEST_URL)
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    Logs.add("Update check: current ${BuildConfig.VERSION_NAME}")
                    return@launch
                }
                Logs.add("Update available ${manifest.versionName} (${manifest.versionCode})")
                emitter.emit("onUpdateStatus", "发现新版本 ${manifest.versionName}，下载中…")
                val dir = File(host.cacheDirectory(), "updates").apply { mkdirs() }
                val apk = File(dir, "DSAssist-${manifest.versionName}.apk")
                client.download(MANIFEST_URL, manifest, apk)
                if (manifest.sha256.isNotBlank() &&
                    !UpdateInstaller.sha256(apk).equals(manifest.sha256, ignoreCase = true)
                ) {
                    apk.delete()
                    throw IllegalStateException("更新包校验失败")
                }
                withContext(Dispatchers.Main) {
                    emitter.emit("onUpdateStatus", "更新已下载，请确认安装")
                    host.installApk(apk)
                }
            } catch (t: Throwable) {
                Logs.add("Update check failed: ${t.message}")
                emitter.emit("onUpdateError", t.message ?: "自动更新失败")
            } finally {
                checking = false
            }
        }
    }
}
