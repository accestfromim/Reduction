package com.mindtravel.deepseekassistant.feature.update.ui

import com.mindtravel.deepseekassistant.BuildConfig
import com.mindtravel.deepseekassistant.api.update.UpdateClient
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.events.EventSink
import com.mindtravel.deepseekassistant.core.host.UpdateHost
import com.mindtravel.deepseekassistant.platform.update.UpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    @Volatile
    private var status = "idle"

    @Volatile
    private var readyVersion = ""

    @Volatile
    private var readyApk: File? = null

    fun state(): String = status
    fun version(): String = readyVersion

    fun check() {
        if (checking || status == "ready") return
        checking = true
        setStatus("checking", "")
        scope.launch(Dispatchers.IO) {
            try {
                val manifest = client.fetch(MANIFEST_URL)
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    Logs.add("Update check: current ${BuildConfig.VERSION_NAME}")
                    setStatus("idle", "")
                    return@launch
                }
                Logs.add("Update available ${manifest.versionName} (${manifest.versionCode})")
                setStatus("available", manifest.versionName)
                delay(700L)
                setStatus("downloading", manifest.versionName, 0)
                val dir = File(host.cacheDirectory(), "updates").apply { mkdirs() }
                val apk = File(dir, "DSAssist-${manifest.versionName}.apk")
                client.download(MANIFEST_URL, manifest, apk) { progress ->
                    setStatus("downloading", manifest.versionName, progress)
                }
                if (manifest.sha256.isNotBlank() &&
                    !UpdateInstaller.sha256(apk).equals(manifest.sha256, ignoreCase = true)
                ) {
                    apk.delete()
                    throw IllegalStateException("更新包校验失败")
                }
                readyApk = apk
                readyVersion = manifest.versionName
                setStatus("ready", manifest.versionName)
                withContext(Dispatchers.Main) {
                    host.showUpdateReady(manifest.versionName, apk)
                }
            } catch (t: Throwable) {
                Logs.add("Update check failed: ${t.message}")
                readyApk = null
                setStatus("error", "")
                emitter.emit("onUpdateError", t.message ?: "自动更新失败")
            } finally {
                checking = false
            }
        }
    }

    fun install() {
        val apk = readyApk ?: return
        if (status != "ready") return
        scope.launch(Dispatchers.Main) {
            try {
                setStatus("installing", readyVersion)
                host.installApk(apk)
            } catch (t: Throwable) {
                Logs.add("Update install failed: ${t.message}")
                setStatus("ready", readyVersion)
                emitter.emit("onUpdateError", t.message ?: "无法打开安装窗口")
            }
        }
    }

    private fun setStatus(next: String, version: String, progress: Int = 0) {
        status = next
        if (version.isNotBlank()) readyVersion = version
        emitter.emit("onUpdateState", next, readyVersion, progress)
    }
}
