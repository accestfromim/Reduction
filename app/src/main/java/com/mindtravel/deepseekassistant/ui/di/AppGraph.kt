package com.mindtravel.deepseekassistant.ui.di

import android.webkit.WebView
import com.mindtravel.deepseekassistant.api.ApiGraph
import com.mindtravel.deepseekassistant.api.update.UpdateClient
import com.mindtravel.deepseekassistant.feature.chat.data.HistoryRepository
import com.mindtravel.deepseekassistant.feature.chat.model.ChatEngine
import com.mindtravel.deepseekassistant.feature.chat.model.ChatSession
import com.mindtravel.deepseekassistant.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.deepseekassistant.feature.chat.ui.ChatController
import com.mindtravel.deepseekassistant.feature.pc.ui.PcController
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import com.mindtravel.deepseekassistant.feature.update.ui.UpdateController
import com.mindtravel.deepseekassistant.feature.voice.data.AndroidTtsEngine
import com.mindtravel.deepseekassistant.feature.voice.model.RollingAsr
import com.mindtravel.deepseekassistant.feature.voice.model.TtsEngine
import com.mindtravel.deepseekassistant.feature.voice.ui.HandsFreeSession
import com.mindtravel.deepseekassistant.feature.voice.ui.PushToTalkSession
import com.mindtravel.deepseekassistant.feature.voice.ui.RecordingController
import com.mindtravel.deepseekassistant.feature.voice.ui.TtsController
import com.mindtravel.deepseekassistant.platform.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.platform.audio.HeadsetMicController
import com.mindtravel.deepseekassistant.platform.audio.TtsCache
import com.mindtravel.deepseekassistant.platform.audio.TtsPipeline
import com.mindtravel.deepseekassistant.platform.audio.TtsPlayback
import com.mindtravel.deepseekassistant.platform.audio.TtsSynthesizer
import com.mindtravel.deepseekassistant.platform.audio.WavRecorder
import com.mindtravel.deepseekassistant.platform.audio.QuickAckPlayer
import com.mindtravel.deepseekassistant.platform.update.AssetUpdater
import com.mindtravel.deepseekassistant.ui.activity.MainActivity
import com.mindtravel.deepseekassistant.ui.bridge.AndroidBridge
import com.mindtravel.deepseekassistant.ui.bridge.ChatBridge
import com.mindtravel.deepseekassistant.ui.bridge.JsEmitter
import com.mindtravel.deepseekassistant.ui.bridge.PcBridge
import com.mindtravel.deepseekassistant.ui.bridge.SettingsBridge
import com.mindtravel.deepseekassistant.ui.bridge.SystemBridge
import com.mindtravel.deepseekassistant.ui.bridge.UpdateBridge
import com.mindtravel.deepseekassistant.ui.bridge.VoiceBridge
import kotlinx.coroutines.CoroutineScope

class AppGraph(
    activity: MainActivity,
    webView: WebView,
    scope: CoroutineScope
) {
    val settingsRepository = SettingsRepository(activity)
    val historyRepository = HistoryRepository(activity)

    private val api = ApiGraph(activity)
    private val emitter = JsEmitter(webView)
    private val chatEngine: ChatEngine = ChatSession(api.chatGateway)
    private val toolFactory = ChatToolFactory(api.searchGateway, api.pcGateway, activity, emitter)

    private val ttsCache = TtsCache(activity)
    private val ttsPipeline = TtsPipeline(
        synthesizer = TtsSynthesizer(api.audioGateway, ttsCache),
        playback = TtsPlayback(activity, scope),
        scope = scope
    )
    private val ttsEngine: TtsEngine = AndroidTtsEngine(ttsPipeline, ttsCache)
    private val rollingAsr = RollingAsr(api.audioGateway, scope)
    private val wavRecorder = WavRecorder()
    private val handsFreeRecorder = HandsFreeRecorder()
    private val headsetMic = HeadsetMicController(activity)
    private val wakeAck = QuickAckPlayer(activity, emitter)

    private val chatController = ChatController(
        settingsRepository = settingsRepository,
        chatEngine = chatEngine,
        toolFactory = toolFactory,
        emitter = emitter,
        scope = scope
    )
    private val pushToTalk = PushToTalkSession(
        settingsRepository = settingsRepository,
        audioGateway = api.audioGateway,
        wavRecorder = wavRecorder,
        headsetMic = headsetMic,
        emitter = emitter,
        scope = scope
    )
    private val handsFree = HandsFreeSession(
        settingsRepository = settingsRepository,
        audioGateway = api.audioGateway,
        handsFreeRecorder = handsFreeRecorder,
        headsetMic = headsetMic,
        rollingAsr = rollingAsr,
        emitter = emitter,
        scope = scope
    )
    private val recordingController = RecordingController(
        host = activity,
        pushToTalk = pushToTalk,
        handsFree = handsFree,
        emitter = emitter
    )
    private val ttsController = TtsController(
        settingsRepository = settingsRepository,
        ttsEngine = ttsEngine,
        emitter = emitter
    )
    private val updateClient = UpdateClient()
    private val assetUpdater = AssetUpdater(activity, updateClient, emitter)
    private val updateController = UpdateController(
        host = activity,
        client = updateClient,
        assetUpdater = assetUpdater,
        emitter = emitter,
        scope = scope
    )
    private val updateBridge = UpdateBridge(updateController)

    private val pcController = PcController(
        settingsRepository = settingsRepository,
        pcGateway = api.pcGateway,
        emitter = emitter,
        scope = scope,
        updateManifestUrl = UpdateController.MANIFEST_URL,
        onUpdateSignal = { updateController.check() }
    )

    private val settingsBridge = SettingsBridge(settingsRepository, historyRepository)
    private val voiceBridge = VoiceBridge(recordingController, ttsController)
    private val chatBridge = ChatBridge(chatController)
    private val pcBridge = PcBridge(pcController)
    private val systemBridge = SystemBridge(activity, settingsRepository, voiceBridge, emitter, wakeAck)

    val bridge = AndroidBridge(
        settings = settingsBridge,
        voice = voiceBridge,
        chat = chatBridge,
        pc = pcBridge,
        update = updateBridge,
        system = systemBridge
    )

    fun onStartup() {
        pcController.connect()
    }

    fun onResume() {
        pcController.connect()
    }
}
