package com.mindtravel.deepseekassistant.ui.di

import android.webkit.WebView
import com.mindtravel.deepseekassistant.feature.chat.data.DeepSeekChatGateway
import com.mindtravel.deepseekassistant.feature.chat.data.DeepSeekSearchApi
import com.mindtravel.deepseekassistant.feature.chat.data.HistoryRepository
import com.mindtravel.deepseekassistant.feature.chat.model.ChatSession
import com.mindtravel.deepseekassistant.feature.chat.ui.ChatController
import com.mindtravel.deepseekassistant.feature.pc.data.PcClient
import com.mindtravel.deepseekassistant.feature.pc.ui.PcController
import com.mindtravel.deepseekassistant.feature.pc.ui.UpdateController
import com.mindtravel.deepseekassistant.feature.settings.data.SettingsRepository
import com.mindtravel.deepseekassistant.feature.voice.data.DeepSeekAudioApi
import com.mindtravel.deepseekassistant.platform.audio.TtsCache
import com.mindtravel.deepseekassistant.feature.voice.ui.RecordingController
import com.mindtravel.deepseekassistant.feature.voice.ui.TtsController
import com.mindtravel.deepseekassistant.network.DeepSeekHttp
import com.mindtravel.deepseekassistant.platform.audio.HandsFreeRecorder
import com.mindtravel.deepseekassistant.platform.audio.HeadsetMicController
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

    private val http = DeepSeekHttp()
    private val chatSession = ChatSession(DeepSeekChatGateway(http))
    private val searchApi = DeepSeekSearchApi(http)
    private val audioApi = DeepSeekAudioApi(http)

    private val ttsCache = TtsCache(activity)
    private val ttsPlayer = TtsPlayer(activity, audioApi, ttsCache, scope)
    private val wavRecorder = WavRecorder()
    private val handsFreeRecorder = HandsFreeRecorder()
    private val headsetMic = HeadsetMicController(activity)
    private val emitter = JsEmitter(webView)
    private val pcClient = PcClient()

    private val chatController = ChatController(
        settingsRepository = settingsRepository,
        chatSession = chatSession,
        searchApi = searchApi,
        emitter = emitter,
        scope = scope
    )
    private val recordingController = RecordingController(
        activity = activity,
        settingsRepository = settingsRepository,
        audioGateway = audioApi,
        wavRecorder = wavRecorder,
        handsFreeRecorder = handsFreeRecorder,
        headsetMic = headsetMic,
        emitter = emitter,
        scope = scope
    )
    private val ttsController = TtsController(
        activity = activity,
        settingsRepository = settingsRepository,
        ttsPlayer = ttsPlayer,
        ttsCache = ttsCache,
        emitter = emitter
    )
    private val pcController = PcController(
        settingsRepository = settingsRepository,
        pcClient = pcClient,
        emitter = emitter,
        scope = scope
    )
    private val updateController = UpdateController(
        activity = activity,
        settingsRepository = settingsRepository,
        pcClient = pcClient,
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
