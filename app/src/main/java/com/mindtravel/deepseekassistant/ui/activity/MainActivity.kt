package com.mindtravel.deepseekassistant.ui.activity

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import com.mindtravel.deepseekassistant.core.host.AudioOutputHost
import com.mindtravel.deepseekassistant.core.host.PermissionHost
import com.mindtravel.deepseekassistant.core.host.ToolApprovalHost
import com.mindtravel.deepseekassistant.core.host.UpdateHost
import com.mindtravel.deepseekassistant.platform.update.UpdateInstaller
import com.mindtravel.deepseekassistant.platform.web.HotAssetPathHandler
import com.mindtravel.deepseekassistant.ui.di.AppGraph
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import com.mindtravel.deepseekassistant.ui.bridge.AndroidBridge
import com.mindtravel.deepseekassistant.platform.wake.WakeWordBus
import com.mindtravel.deepseekassistant.platform.wake.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), PermissionHost, AudioOutputHost, UpdateHost, ToolApprovalHost {

    private lateinit var webView: WebView
    private lateinit var graph: AppGraph
    private lateinit var bridge: AndroidBridge
    private lateinit var settingsRepository: SettingsRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var micCallback: ((Boolean) -> Unit)? = null
    private var bluetoothCallback: ((Boolean) -> Unit)? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micCallback?.invoke(granted)
            micCallback = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            bluetoothCallback?.invoke(granted)
            bluetoothCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#000000"))
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
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
        }
        setContentView(webView)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        graph = AppGraph(this, webView, uiScope)
        settingsRepository = graph.settingsRepository
        bridge = graph.bridge
        webView.addJavascriptInterface(bridge, "Android")
        graph.onStartup()

        WakeWordBus.listener = { runOnUiThread { bridge.onWakeWord() } }
        if (settingsRepository.load().wakeWordEnabled) {
            setWakeWordEnabled(true)
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                HotAssetPathHandler(
                    this,
                    WebViewAssetLoader.AssetsPathHandler(this)
                )
            )
            .build()
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                    return true
                }
                return true
            }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun requestMicPermission(callback: (Boolean) -> Unit) {
        runOnUiThread {
            if (hasMicPermission()) {
                callback(true)
                return@runOnUiThread
            }
            micCallback = callback
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun requestBluetoothPermission(callback: (Boolean) -> Unit) {
        runOnUiThread {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                callback(true)
                return@runOnUiThread
            }
            bluetoothCallback = callback
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun hasHeadsetOutput(): Boolean {
        val manager = getSystemService(AudioManager::class.java) ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE -> true
                else -> false
            }
        }
    }

    override fun cacheDirectory(): File = cacheDir

    override fun installedApk(): File = File(
        packageManager.getApplicationInfo(packageName, 0).sourceDir
    )

    override fun installApk(file: File) {
        UpdateInstaller.installApk(this, file)
    }

    override fun showUpdateReady(version: String, apk: File) {
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("DSAssist $version")
                .setMessage("新版本已下载，是否现在安装？")
                .setPositiveButton("安装") { _, _ ->
                    runCatching { UpdateInstaller.installApk(this, apk) }
                        .onFailure {
                            android.widget.Toast.makeText(
                                this,
                                it.message ?: "无法打开安装窗口",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    override fun showAssetsReady(version: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("界面资源已更新")
                .setMessage("资源版本 $version 已安装，是否立即重新加载界面？")
                .setPositiveButton("立即重载") { _, _ ->
                    webView.reload()
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    override fun requestToolApproval(title: String, message: String, callback: (Boolean) -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                callback(false)
                return@runOnUiThread
            }
            val answered = AtomicBoolean(false)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message.take(2_000))
                .setPositiveButton("允许本次") { _, _ ->
                    if (answered.compareAndSet(false, true)) callback(true)
                }
                .setNegativeButton("拒绝") { _, _ ->
                    if (answered.compareAndSet(false, true)) callback(false)
                }
                .setOnCancelListener {
                    if (answered.compareAndSet(false, true)) callback(false)
                }
                .create()
            dialog.setOnDismissListener {
                if (answered.compareAndSet(false, true)) callback(false)
            }
            dialog.show()
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

    override fun onResume() {
        super.onResume()
        if (::graph.isInitialized) graph.onResume()
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
