package com.mindtravel.reduction.hardware.update

import android.content.Context
import com.mindtravel.reduction.BuildConfig
import com.mindtravel.reduction.hardware.update.UpdateClient
import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.hardware.update.UpdateManifest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class AssetUpdater(
    private val context: Context,
    private val client: UpdateClient,
    private val emitter: EventSink
) {
    companion object {
        private const val PREFS = "asset_update"
        private const val KEY_ACTIVE = "active"
        private const val KEY_ACTIVE_MIN_CODE = "active_min_code"

        /**
         * The app version whose JS bridge the web assets must be written for.
         * A pack declaring a lower assetsMinVersionCode targets an earlier bridge and is not used.
         */
        const val BRIDGE_VERSION_CODE = 86
        private const val MAX_TOTAL_BYTES = 80L * 1024 * 1024

        fun activeDir(context: Context): File? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val version = prefs.getString(KEY_ACTIVE, "").orEmpty()
            if (version.isBlank()) return null
            if (prefs.getInt(KEY_ACTIVE_MIN_CODE, 0) < BRIDGE_VERSION_CODE) return null
            val dir = File(context.filesDir, "web/$version")
            return dir.takeIf { File(it, "index.html").isFile }
        }

        fun activeVersion(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE, "").orEmpty()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun updateIfNeeded(manifestUrl: String, manifest: UpdateManifest): Boolean {
        val version = manifest.assetsVersion.trim()
        if (version.isBlank() || manifest.assetsZip.isBlank()) return false
        if (manifest.assetsMinVersionCode < BRIDGE_VERSION_CODE ||
            manifest.assetsMinVersionCode > BuildConfig.VERSION_CODE
        ) return false

        if (!version.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            Logs.add("Hot assets version invalid")
            return false
        }
        val active = prefs.getString(KEY_ACTIVE, "").orEmpty()
        if (active == version &&
            (activeDir(context) != null || version == BuildConfig.VERSION_NAME)
        ) return false
        if (active.isBlank() && version == BuildConfig.VERSION_NAME) {
            prefs.edit().putString(KEY_ACTIVE, version).apply()
            return false
        }

        val cache = File(context.cacheDir, "assets").apply { mkdirs() }
        val zip = File(cache, "assets-$version.zip")
        return try {
            client.downloadFile(
                manifestUrl = manifestUrl,
                relativePath = manifest.assetsZip,
                destination = zip,
                minBytes = 1_000
            )
            if (manifest.assetsSha256.isNotBlank() &&
                !UpdateInstaller.sha256(zip).equals(manifest.assetsSha256, ignoreCase = true)
            ) {
                throw ApiException("资源包校验失败")
            }
            install(zip, version, manifest.assetsMinVersionCode)
            zip.delete()
            emitter.emit("onAssetsUpdated", version)
            Logs.add("Hot assets updated to $version")
            true
        } catch (t: Throwable) {
            Logs.add("Hot assets update failed: ${t.message}")
            zip.delete()
            false
        }
    }

    private fun install(zip: File, version: String, minVersionCode: Int) {
        val root = File(context.filesDir, "web").apply { mkdirs() }
        val target = File(root, version)
        val temp = File(root, ".$version.tmp")
        temp.deleteRecursively()
        temp.mkdirs()
        var total = 0L
        try {
            ZipInputStream(FileInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                        throw ApiException("资源包路径非法")
                    }
                    val out = File(temp, name)
                    val canonical = out.canonicalFile
                    if (!canonical.path.startsWith(temp.canonicalPath + File.separator)) {
                        throw ApiException("资源包路径越界")
                    }
                    canonical.parentFile?.mkdirs()
                    FileOutputStream(canonical).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_TOTAL_BYTES) throw ApiException("资源包过大")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            if (!File(temp, "index.html").isFile) throw ApiException("资源包缺少 index.html")
            target.deleteRecursively()
            if (!temp.renameTo(target)) throw ApiException("资源目录切换失败")
            prefs.edit()
                .putString(KEY_ACTIVE, version)
                .putInt(KEY_ACTIVE_MIN_CODE, minVersionCode)
                .apply()
            root.listFiles()?.forEach { dir ->
                if (dir.name != version && dir.name != ".$version.tmp") {
                    runCatching { dir.deleteRecursively() }
                }
            }
        } catch (t: Throwable) {
            temp.deleteRecursively()
            throw t
        }
    }
}
