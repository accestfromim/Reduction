package com.mindtravel.reduction.hardware.web

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.mindtravel.reduction.hardware.update.AssetUpdater
import java.io.File
import java.io.FileInputStream

class HotAssetPathHandler(
    private val context: Context,
    private val fallback: WebViewAssetLoader.PathHandler
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val root = AssetUpdater.activeDir(context) ?: return fallback.handle(path)
        val relative = path.trimStart('/')
        if (relative.isBlank()) return fallback.handle(path)
        val file = File(root, relative)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return fallback.handle(path)
        if (!canonical.path.startsWith(root.canonicalPath + File.separator) || !canonical.isFile) {
            return fallback.handle(path)
        }
        val mime = mimeFor(canonical.name)
        val encoding = if (mime.startsWith("text/") || mime.contains("javascript") ||
            mime.contains("json") || mime.contains("svg")
        ) "utf-8" else null
        return WebResourceResponse(mime, encoding, FileInputStream(canonical)).apply {
            setResponseHeaders(mapOf("Cache-Control" to "no-store"))
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}
