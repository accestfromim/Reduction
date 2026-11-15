package com.mindtravel.deepseekassistant.api.pc

import com.mindtravel.deepseekassistant.core.ApiException
import com.mindtravel.deepseekassistant.feature.pc.model.PcGateway
import com.mindtravel.deepseekassistant.feature.pc.model.PcInfo
import com.mindtravel.deepseekassistant.feature.pc.model.UpdateManifest
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.core.arrayOrEmpty
import com.mindtravel.deepseekassistant.core.stringOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class PcClient : PcGateway {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override fun info(base: String, token: String): PcInfo {
        val request = request("${normalize(base)}/api/info", token).get().build()
        val json = executeJson(request)
        return PcInfo(
            hostname = json.stringOrNull("hostname").orEmpty(),
            os = json.stringOrNull("os").orEmpty(),
            user = json.stringOrNull("user").orEmpty(),
            ips = json.arrayOrEmpty("ips").let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf { it.isNotBlank() }
                }
            },
            time = json.stringOrNull("time").orEmpty()
        )
    }

    override fun exec(base: String, token: String, command: String): String {
        val payload = JSONObject().put("command", command).toString()
        val request = request("${normalize(base)}/api/exec", token)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()
        val result = executeJson(request)
        val output = result.stringOrNull("output").orEmpty()
        val error = result.stringOrNull("error").orEmpty()
        return when {
            output.isNotBlank() -> output
            error.isNotBlank() -> "错误：$error"
            else -> ""
        }
    }

    override fun updateManifest(base: String, token: String): UpdateManifest {
        val request = request("${normalize(base)}/api/update/manifest", token).get().build()
        val json = executeJson(request)
        return UpdateManifest(
            versionCode = json.optInt("versionCode", 0),
            versionName = json.stringOrNull("versionName").orEmpty(),
            sha256 = json.stringOrNull("sha256").orEmpty()
        )
    }

    override fun downloadApk(base: String, token: String, destination: File): File {
        val request = request("${normalize(base)}/api/update/apk", token).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException("下载更新失败（HTTP ${response.code}）")
            }
            destination.parentFile?.mkdirs()
            val body = response.body ?: throw ApiException("更新包为空")
            body.byteStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            if (destination.length() < 100_000) {
                destination.delete()
                throw ApiException("更新包大小异常")
            }
            return destination
        }
    }

    private fun normalize(value: String): String {
        var url = value.trim().trimEnd('/')
        if (url.isBlank()) throw ApiException("请先填写电脑地址")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        return url
    }

    private fun request(url: String, token: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (token.isNotBlank()) builder.header("X-Auth-Token", token)
        return builder
    }

    private fun executeJson(request: Request): JSONObject {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(body).stringOrNull("error")
                    }.getOrNull()
                    throw ApiException(message ?: "电脑端返回 HTTP ${response.code}")
                }
                return runCatching { JSONObject(body) }
                    .getOrElse { throw ApiException("电脑端返回格式异常") }
            }
        } catch (t: ApiException) {
            throw t
        } catch (t: Throwable) {
            Logs.add("PC network error: ${t.javaClass.simpleName}: ${t.message}")
            throw ApiException("连接电脑失败：${t.message ?: t.javaClass.simpleName}", t)
        }
    }
}
