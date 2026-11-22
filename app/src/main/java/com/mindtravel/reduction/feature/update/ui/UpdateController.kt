package com.mindtravel.reduction.feature.update.ui

import com.mindtravel.reduction.BuildConfig
import com.mindtravel.reduction.hardware.update.UpdateClient
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.host.UpdateHost
import com.mindtravel.reduction.hardware.update.AssetUpdater
import com.mindtravel.reduction.hardware.update.BsPatch
import com.mindtravel.reduction.hardware.update.UpdateInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateController(
    private val host: UpdateHost,
    private val client: UpdateClient,
    private val assetUpdater: AssetUpdater,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {
    companion object {
        /** Where updates are looked for; blank in a build that was not given one, which then never looks. */
        val MANIFEST_URL: String = BuildConfig.UPDATE_MANIFEST_URL
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

    fun check(userInitiated: Boolean = false) {
        if (MANIFEST_URL.isBlank()) {
            if (userInitiated) emitter.emit("onUpdateError", "当前版本未配置更新地址，无法检查更新")
            return
        }
        if (checking || status == "ready") return
        checking = true
        setStatus("checking", "")
        scope.launch(Dispatchers.IO) {
            try {
                val manifest = client.fetch(MANIFEST_URL)
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    Logs.add("Update check: current ${BuildConfig.VERSION_NAME}")
                    val assetsUpdated = assetUpdater.updateIfNeeded(MANIFEST_URL, manifest)
                    setStatus("idle", "")
                    if (assetsUpdated) {
                        withContext(Dispatchers.Main) {
                            host.showAssetsReady(manifest.assetsVersion)
                        }
                    }
                    return@launch
                }
                Logs.add("Update available ${manifest.versionName} (${manifest.versionCode})")
                setStatus("available", manifest.versionName)
                delay(700L)
                val dir = File(host.cacheDirectory(), "updates").apply { mkdirs() }
                val apk = File(dir, "Reduction-${manifest.versionName}.apk")
                var prepared = false
                val patch = manifest.patch
                if (patch != null &&
                    patch.fromVersionCode == BuildConfig.VERSION_CODE &&
                    patch.apk.isNotBlank()
                ) {
                    prepared = runCatching {
                        setStatus("downloading", manifest.versionName, 0)
                        val patchFile = File(dir, "patch-${manifest.versionCode}.bin")
                        client.downloadFile(
                            manifestUrl = MANIFEST_URL,
                            relativePath = patch.apk,
                            destination = patchFile,
                            onProgress = { progress ->
                                setStatus("downloading", manifest.versionName, progress)
                            },
                            minBytes = 1_000
                        )
                        if (patch.sha256.isNotBlank() &&
                            !UpdateInstaller.sha256(patchFile)
                                .equals(patch.sha256, ignoreCase = true)
                        ) {
                            throw IllegalStateException("补丁校验失败")
                        }
                        Logs.add("Applying APK patch ${patch.fromVersionCode}->${manifest.versionCode}")
                        apk.delete()
                        BsPatch.apply(host.installedApk(), patchFile, apk)
                        patchFile.delete()
                        if (manifest.sha256.isNotBlank() &&
                            !UpdateInstaller.sha256(apk)
                                .equals(manifest.sha256, ignoreCase = true)
                        ) {
                            apk.delete()
                            throw IllegalStateException("补丁结果校验失败")
                        }
                        true
                    }.getOrElse { error ->
                        Logs.add("APK patch failed, falling back to full: ${error.message}")
                        apk.delete()
                        false
                    }
                }
                if (!prepared) {
                    setStatus("downloading", manifest.versionName, 0)
                    client.download(MANIFEST_URL, manifest, apk) { progress ->
                        setStatus("downloading", manifest.versionName, progress)
                    }
                    if (manifest.sha256.isNotBlank() &&
                        !UpdateInstaller.sha256(apk).equals(manifest.sha256, ignoreCase = true)
                    ) {
                        apk.delete()
                        throw IllegalStateException("更新包校验失败")
                    }
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
