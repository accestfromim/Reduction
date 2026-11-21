package com.mindtravel.reduction.hardware.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.model.chat.ChatImage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.Base64

/** A local, disposable view of one page. No application bridge is installed. */
class CardPageRenderer(private val context: Context, private val cards: CardRepository) {
    fun render(name: String, source: String, page: Int): Pair<ChatImage, Int> {
        check(Looper.myLooper() != Looper.getMainLooper())
        val main = Handler(Looper.getMainLooper())
        val result = CompletableFuture<Pair<ChatImage, Int>>()
        var view: WebView? = null
        var display: android.hardware.display.VirtualDisplay? = null
        var presentation: android.app.Presentation? = null
        var surface: android.media.ImageReader? = null
        main.post {
            runCatching {
                val output = android.media.ImageReader.newInstance(720, 1200, android.graphics.PixelFormat.RGBA_8888, 2)
                surface = output
                output.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, main)
                val manager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val screen = manager.createVirtualDisplay("CARD", 720, 1200, 160, output.surface,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
                display = screen
                val host = android.app.Presentation(context, screen.display)
                presentation = host
                val web = WebView(host.context)
                host.setContentView(web)
                host.show()
                host.window!!.setLayout(720, 1200)
                view = web
                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", HotAssetPathHandler(context, WebViewAssetLoader.AssetsPathHandler(context)))
                    .addPathHandler("/card-media/") { path ->
                        cards.heldFile(path)?.let {
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension)
                            WebResourceResponse(mime, null, it.inputStream())
                        }
                    }.build()
                web.settings.javaScriptEnabled = true
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.setInitialScale(100)
                web.settings.offscreenPreRaster = true
                web.measure(android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY))
                web.layout(0, 0, 720, 1200)
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse =
                        loader.shouldInterceptRequest(r.url) ?: WebResourceResponse("text/plain", "utf-8", null)
                    override fun onPageFinished(v: WebView, url: String) {
                        val args = listOf(JSONObject.quote(name), JSONObject.quote(source), page.toString()).joinToString(",")
                        v.evaluateJavascript("window.drawCard($args)", null)
                        fun poll() {
                            if (result.isDone) return
                            v.evaluateJavascript("window.cardState || null") { raw ->
                                if (result.isDone) return@evaluateJavascript
                                if (raw == "null") { main.postDelayed({ poll() }, 40); return@evaluateJavascript }
                                runCatching {
                                    val state = JSONObject(raw)
                                    check(!state.has("error")) { state.optString("error") }
                                    val height = state.getInt("height").coerceIn(1, 1200)
                                    val bitmap = Bitmap.createBitmap(720, height, Bitmap.Config.ARGB_8888)
                                    v.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) {
                                            if (result.isDone) { bitmap.recycle(); return }
                                            android.view.PixelCopy.request(host.window!!, android.graphics.Rect(0,0,720,height), bitmap, { status ->
                                                runCatching {
                                                    check(status == android.view.PixelCopy.SUCCESS) { "画面读取失败 $status" }
                                                    val bytes = ByteArrayOutputStream()
                                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                                                    val image = ChatImage(name, "image/png", Base64.getEncoder().encodeToString(bytes.toByteArray()))
                                                    result.complete(image to state.getInt("pages"))
                                                }.onFailure { result.completeExceptionally(it) }
                                                bitmap.recycle()
                                            }, main)
                                        }
                                    })
                                }.onFailure { result.completeExceptionally(it) }
                            }
                        }
                        poll()
                    }
                }
                web.loadUrl("https://appassets.androidplatform.net/assets/card-render.html")
            }.onFailure { result.completeExceptionally(it) }
        }
        return try { result.get(30, TimeUnit.SECONDS) } finally {
            result.cancel(false)
            main.post { presentation?.dismiss(); view?.destroy(); display?.release(); surface?.close() }
        }
    }
}
