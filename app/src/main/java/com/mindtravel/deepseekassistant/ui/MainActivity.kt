package com.mindtravel.deepseekassistant.ui

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.mindtravel.deepseekassistant.BuildConfig
import com.mindtravel.deepseekassistant.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.audio.TtsCache
import com.mindtravel.deepseekassistant.audio.TtsPlayer
import com.mindtravel.deepseekassistant.audio.WavRecorder
import com.mindtravel.deepseekassistant.data.HistoryRepository
import com.mindtravel.deepseekassistant.data.SettingsRepository
import com.mindtravel.deepseekassistant.network.DeepSeekHttp
import com.mindtravel.deepseekassistant.network.audio.AudioApi
import com.mindtravel.deepseekassistant.network.chat.ChatSession
import com.mindtravel.deepseekassistant.network.chat.DeepSeekChatApi
import com.mindtravel.deepseekassistant.network.pc.PcClient
import com.mindtravel.deepseekassistant.network.search.SearchApi
import com.mindtravel.deepseekassistant.ui.controller.AudioController
import com.mindtravel.deepseekassistant.ui.controller.ChatController
import com.mindtravel.deepseekassistant.ui.controller.PcController
import com.mindtravel.deepseekassistant.ui.controller.UpdateController
import com.mindtravel.deepseekassistant.wake.WakeWordBus
import com.mindtravel.deepseekassistant.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private lateinit var settingsRepository: SettingsRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var micCallback: ((Boolean) -> Unit)? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micCallback?.invoke(granted)
            micCallback = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F14"))
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportZoom(false)
                textZoom = 100
                cacheMode = WebSettings.LOAD_DEFAULT
            }
        }
        setContentView(webView)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        settingsRepository = SettingsRepository(this)
        val emitter = JsEmitter(webView)
        val historyRepository = HistoryRepository(this)
        val http = DeepSeekHttp()
        val chatSession = ChatSession(DeepSeekChatApi(http))
        val searchApi = SearchApi(http)
        val audioApi = AudioApi(http)
        val ttsCache = TtsCache(this)
        val ttsPlayer = TtsPlayer(this, audioApi, ttsCache, uiScope)
        val wavRecorder = WavRecorder()
        val handsFreeRecorder = HandsFreeRecorder()
        val pcClient = PcClient()

        val chatController = ChatController(
            settingsRepository = settingsRepository,
            chatSession = chatSession,
            searchApi = searchApi,
            emitter = emitter,
            scope = uiScope
        )
        val audioController = AudioController(
            activity = this,
            settingsRepository = settingsRepository,
            audioApi = audioApi,
            ttsPlayer = ttsPlayer,
            ttsCache = ttsCache,
            wavRecorder = wavRecorder,
            handsFreeRecorder = handsFreeRecorder,
            emitter = emitter,
            scope = uiScope
        )
        val pcController = PcController(
            settingsRepository = settingsRepository,
            pcClient = pcClient,
            emitter = emitter,
            scope = uiScope
        )
        val updateController = UpdateController(
            activity = this,
            settingsRepository = settingsRepository,
            pcClient = pcClient,
            emitter = emitter,
            scope = uiScope
        )

        bridge = AndroidBridge(
            activity = this,
            settingsRepository = settingsRepository,
            historyRepository = historyRepository,
            chatController = chatController,
            audioController = audioController,
            pcController = pcController,
            updateController = updateController,
            emitter = emitter
        )
        webView.addJavascriptInterface(bridge, "Android")

        WakeWordBus.listener = { runOnUiThread { bridge.onWakeWord() } }
        if (settingsRepository.load().wakeWordEnabled) {
            setWakeWordEnabled(true)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun requestMicPermission(callback: (Boolean) -> Unit) {
        runOnUiThread {
            if (hasMicPermission()) {
                callback(true)
                return@runOnUiThread
            }
            micCallback = callback
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            WakeWordBus.reset()
            WakeWordService.start(this)
        } else {
            WakeWordService.stop(this)
            WakeWordBus.reset()
        }
    }

    override fun onPause() {
        bridge.onPause()
        super.onPause()
    }

    override fun onBackPressed() {
        webView.evaluateJavascript("window.onAndroidBack ? window.onAndroidBack() : false") { result ->
            if (result != "true") {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    override fun onDestroy() {
        WakeWordBus.listener = null
        bridge.dispose()
        uiScope.cancel()
        webView.removeJavascriptInterface("Android")
        webView.destroy()
        super.onDestroy()
    }
}
