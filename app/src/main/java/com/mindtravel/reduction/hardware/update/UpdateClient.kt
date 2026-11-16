package com.mindtravel.reduction.hardware.update

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.stringOrNull
import com.mindtravel.reduction.hardware.update.UpdateManifest
import com.mindtravel.reduction.hardware.update.UpdatePatch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
) {
    fun fetch(url: String): UpdateManifest {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("更新检查失败（HTTP ${response.code}）")
            val json = JSONObject(response.body?.string().orEmpty())
            return UpdateManifest(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.stringOrNull("versionName").orEmpty(),
                apk = json.stringOrNull("apk").orEmpty(),
                sha256 = json.stringOrNull("sha256").orEmpty(),
                assetsVersion = json.stringOrNull("assetsVersion").orEmpty(),
                assetsZip = json.stringOrNull("assetsZip").orEmpty(),
                assetsSha256 = json.stringOrNull("assetsSha256").orEmpty(),
                assetsMinVersionCode = json.optInt("assetsMinVersionCode", 0),
                patch = json.optJSONObject("patch")?.let { patch ->
                    UpdatePatch(
                        fromVersionCode = patch.optInt("fromVersionCode", 0),
                        apk = patch.stringOrNull("apk").orEmpty(),
                        sha256 = patch.stringOrNull("sha256").orEmpty()
                    )
                }
            )
        }
    }

    fun download(
        manifestUrl: String,
        manifest: UpdateManifest,
        destination: File,
        onProgress: (Int) -> Unit = {}
    ): File = downloadFile(
        manifestUrl = manifestUrl,
        relativePath = manifest.apk,
        destination = destination,
        onProgress = onProgress,
        minBytes = 100_000
    )

    fun downloadFile(
        manifestUrl: String,
        relativePath: String,
        destination: File,
        onProgress: (Int) -> Unit = {},
        minBytes: Long = 1
    ): File {
        val base = manifestUrl.toHttpUrl()
        val url = base.resolve(relativePath) ?: throw ApiException("下载地址无效")
        val request = Request.Builder().url(url).get().build()
        destination.parentFile?.mkdirs()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("下载失败（HTTP ${response.code}）")
            val body = response.body ?: throw ApiException("下载内容为空")
            val total = body.contentLength()
            var copied = 0L
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress(((copied * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            onProgress(100)
        }
        if (destination.length() < minBytes) {
            destination.delete()
            throw ApiException("下载内容大小异常")
        }
        return destination
    }
}
