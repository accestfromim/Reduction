package com.mindtravel.deepseekassistant.ui.di

import android.webkit.WebView
import com.mindtravel.deepseekassistant.api.ApiGraph
import com.mindtravel.deepseekassistant.feature.chat.data.HistoryRepository
import com.mindtravel.deepseekassistant.feature.chat.model.ChatEngine
import com.mindtravel.deepseekassistant.feature.chat.model.ChatSession
import com.mindtravel.deepseekassistant.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.deepseekassistant.feature.chat.ui.ChatController
import com.mindtravel.deepseekassistant.feature.pc.ui.PcController
import com.mindtravel.deepseekassistant.feature.pc.ui.UpdateController
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import com.mindtravel.deepseekassistant.feature.voice.data.AndroidTtsEngine
import com.mindtravel.deepseekassistant.feature.voice.model.RollingAsr
import com.mindtravel.deepseekassistant.feature.voice.model.TtsEngine
import com.mindtravel.deepseekassistant.feature.voice.ui.RecordingController
import com.mindtravel.deepseekassistant.feature.voice.ui.TtsController
import com.mindtravel.deepseekassistant.platform.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.platform.audio.HeadsetMicController
import com.mindtravel.deepseekassistant.platform.audio.TtsCache
import com.mindtravel.deepseekassistant.platform.audio.TtsPlayer
import com.mindtravel.deepseekassistant.platform.audio.WavRecorder
import com.mindtravel.deepseekassistant.ui.activity.MainActivity
import com.mindtravel.deepseekassistant.ui.bridge.AndroidBridge
import com.mindtravel.deepseekassistant.ui.bridge.JsEmitter
import kotlinx.coroutines.CoroutineScope

class AppGraph(
    activity: MainActivity,
    webView: WebView,
    scope: CoroutineScope
) {
    val settingsRepository = SettingsRepository(activity)
    val historyRepository = HistoryRepository(activity)

    private val api = ApiGraph()
    private val chatEngine: ChatEngine = ChatSession(api.chatGateway)
    private val toolFactory = ChatToolFactory(api.searchGateway)

    private val ttsCache = TtsCache(activity)
    private val ttsPlayer = TtsPlayer(activity, api.audioGateway, ttsCache, scope)
    private val ttsEngine: TtsEngine = AndroidTtsEngine(ttsPlayer, ttsCache)
    private val rollingAsr = RollingAsr(api.audioGateway, scope)
    private val wavRecorder = WavRecorder()
    private val handsFreeRecorder = HandsFreeRecorder()
    private val headsetMic = HeadsetMicController(activity)
    private val emitter = JsEmitter(webView)

    private val chatController = ChatController(
        settingsRepository = settingsRepository,
        chatEngine = chatEngine,
        toolFactory = toolFactory,
        emitter = emitter,
        scope = scope
    )
    private val recordingController = RecordingController(
        host = activity,
        settingsRepository = settingsRepository,
        audioGateway = api.audioGateway,
        wavRecorder = wavRecorder,
        handsFreeRecorder = handsFreeRecorder,
        headsetMic = headsetMic,
        rollingAsr = rollingAsr,
        emitter = emitter,
        scope = scope
    )
    private val ttsController = TtsController(
        permissionHost = activity,
        audioOutputHost = activity,
        settingsRepository = settingsRepository,
        ttsEngine = ttsEngine,
        emitter = emitter
    )
    private val pcController = PcController(
        settingsRepository = settingsRepository,
        pcGateway = api.pcGateway,
        emitter = emitter,
        scope = scope
    )
    private val updateController = UpdateController(
        host = activity,
        settingsRepository = settingsRepository,
        pcGateway = api.pcGateway,
        emitter = emitter,
        scope = scope
    )

    val bridge = AndroidBridge(
        activity = activity,
        settingsRepository = settingsRepository,
        historyRepository = historyRepository,
        chatController = chatController,
        recordingController = recordingController,
        ttsController = ttsController,
        pcController = pcController,
        updateController = updateController,
        emitter = emitter
    )
}
