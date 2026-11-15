package com.mindtravel.deepseekassistant.feature.pc.ui

import com.mindtravel.deepseekassistant.BuildConfig
import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.host.UpdateHost
import com.mindtravel.deepseekassistant.platform.update.UpdateInstaller
import com.mindtravel.deepseekassistant.core.settings.SettingsGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.core.events.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class UpdateController(
    private val host: UpdateHost,
    private val settingsRepository: SettingsGateway,
    private val pcGateway: PcGateway,
    private val emitter: EventSink,
    private val scope: CoroutineScope
) {

    fun check(requestId: String) {
        val settings = settingsRepository.load()
        emitter.emit("onUpdateStatus", requestId, "检查更新…")
        scope.launch(Dispatchers.IO) {
            try {
                val manifest = pcGateway.updateManifest(settings.pcUrl, settings.pcToken)
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    emitter.emit("onUpdateNone", requestId, BuildConfig.VERSION_NAME)
                    return@launch
                }

                emitter.emit("onUpdateStatus", requestId, "下载中…")
                val directory = File(host.cacheDirectory(), "updates").apply { mkdirs() }
                val apk = File(directory, "DeepSeekAssistant-update.apk")
                pcGateway.downloadApk(settings.pcUrl, settings.pcToken, apk)

                val expected = manifest.sha256
                if (expected.isNotBlank() && !UpdateInstaller.sha256(apk).equals(expected, ignoreCase = true)) {
                    apk.delete()
                    throw ApiException("更新包校验失败，请重新下载")
                }

                emitter.emit("onUpdateStatus", requestId, "准备安装…")
                host.installApk(apk)
                emitter.emit("onUpdateReady", requestId, manifest.versionName.ifBlank { "?" })
            } catch (t: Throwable) {
                Logs.add("Update failed: ${t.message}")
                emitter.emit("onUpdateError", requestId, t.message ?: "更新失败")
            }
        }
    }
}
