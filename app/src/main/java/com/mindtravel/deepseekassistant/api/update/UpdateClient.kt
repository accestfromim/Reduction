package com.mindtravel.deepseekassistant.api.update

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.core.stringOrNull
import com.mindtravel.deepseekassistant.feature.update.model.UpdateManifest
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
                sha256 = json.stringOrNull("sha256").orEmpty()
            )
        }
    }

    fun download(
        manifestUrl: String,
        manifest: UpdateManifest,
        destination: File,
        onProgress: (Int) -> Unit = {}
    ): File {
        val base = manifestUrl.toHttpUrl()
        val apkUrl = base.resolve(manifest.apk) ?: throw ApiException("更新包地址无效")
        val request = Request.Builder().url(apkUrl).get().build()
        destination.parentFile?.mkdirs()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("下载更新失败（HTTP ${response.code}）")
            val body = response.body ?: throw ApiException("更新包为空")
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
        if (destination.length() < 100_000) {
            destination.delete()
            throw ApiException("更新包大小异常")
        }
        return destination
    }
}
