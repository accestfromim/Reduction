package com.mindtravel.entropy.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mindtravel.entropy.R
import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.feature.chat.data.CardRepository
import com.mindtravel.entropy.feature.chat.data.HistoryRepository
import com.mindtravel.entropy.feature.chat.model.ChatEngine
import com.mindtravel.entropy.feature.chat.model.ChatSession
import com.mindtravel.entropy.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.entropy.feature.chat.ui.ChatController
import com.mindtravel.entropy.feature.chat.ui.Conversation
import com.mindtravel.entropy.feature.pc.ui.PcController
import com.mindtravel.entropy.feature.settings.data.SettingsRepository
import com.mindtravel.entropy.feature.update.ui.UpdateController
import com.mindtravel.entropy.feature.voice.AckCue
import com.mindtravel.entropy.feature.voice.PushToTalkSession
import com.mindtravel.entropy.feature.voice.RecordingController
import com.mindtravel.entropy.feature.voice.ReplyStreamer
import com.mindtravel.entropy.feature.voice.SpeechController
import com.mindtravel.entropy.feature.voice.TurnListener
import com.mindtravel.entropy.feature.voice.TurnRecorder
import com.mindtravel.entropy.feature.voice.VoiceRuntime
import com.mindtravel.entropy.feature.voice.WakeControl
import com.mindtravel.entropy.feature.voice.wake.WakeWordBus
import com.mindtravel.entropy.feature.voice.wake.WakeWordService
import com.mindtravel.entropy.hardware.audio.AudioQueue
import com.mindtravel.entropy.hardware.audio.AudioRoutes
import com.mindtravel.entropy.hardware.audio.AudioTrackSink
import com.mindtravel.entropy.hardware.audio.HeadsetMicController
import com.mindtravel.entropy.hardware.audio.PcmPlayer
import com.mindtravel.entropy.hardware.audio.WavPcm
import com.mindtravel.entropy.hardware.audio.WavRecorder
import com.mindtravel.entropy.hardware.pc.PcGateway
import com.mindtravel.entropy.hardware.pc.SshPcClient
import com.mindtravel.entropy.model.ModelGraph
import com.mindtravel.entropy.model.voice.RollingAsr
import com.mindtravel.entropy.model.voice.TtsCache
import com.mindtravel.entropy.model.voice.TtsSynthesizer
import com.mindtravel.entropy.model.voice.VoiceDetector
import com.mindtravel.entropy.model.wake.KeywordSpotterModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.LocalDate

/**
 * Everything a conversation needs, living as long as the process does — not as long as
 * a screen does. The wake word service keeps the process alive; this is what answers
 * when the wake word is heard, whether or not any UI exists at that moment.
 *
 * A UI attaches through [ui] to show what is going on and to send the user's taps. It
 * owns nothing here and can come and go.
 */
class AppCore private constructor(context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val ui = UiLink(context)

    val settingsRepository = SettingsRepository(context)
    val cardRepository = CardRepository(File(context.filesDir, "cards"))
    private val historyRepository = HistoryRepository(context)

    // ── hardware ────────────────────────────────────────────────────────────
    private val headsetMic = HeadsetMicController(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioQueue = AudioQueue(AudioTrackSink()) { callback -> mainHandler.post(callback) }
    private val pcmPlayer = PcmPlayer(context, audioQueue, scope)
    private val pcGateway: PcGateway = SshPcClient(context)

    // ── models ──────────────────────────────────────────────────────────────
    private val models = ModelGraph()
    private val ttsCache = TtsCache(context)
    private val ttsSynthesizer = TtsSynthesizer(models.audioGateway, ttsCache)

    // ── features: voice ─────────────────────────────────────────────────────
    private val ackCue = AckCue(
        queue = audioQueue,
        sounds = AckCue.Sounds(
            acknowledge = cueSound(context, R.raw.ack_done),
            hum = cueSound(context, R.raw.ack_hum)
        ),
        scope = scope,
        describeOutput = { AudioRoutes.describe(context) }
    )
    val speech = SpeechController(settingsRepository, ReplyStreamer(ttsSynthesizer, pcmPlayer, scope), ttsCache, ui)
    private val pushToTalk = PushToTalkSession(
        settingsRepository = settingsRepository,
        audioGateway = models.audioGateway,
        wavRecorder = WavRecorder(),
        headsetMic = headsetMic,
        emitter = ui,
        scope = scope
    )
    val recording = RecordingController(ui, pushToTalk, ui)

    // ── features: chat ──────────────────────────────────────────────────────
    private val chatEngine: ChatEngine = ChatSession(models.chatGateway)
    private val chatController = ChatController(
        settingsRepository = settingsRepository,
        chatEngine = chatEngine,
        toolFactory = ChatToolFactory(
            searchGateway = models.searchGateway,
            pcGateway = pcGateway,
            approvalHost = ui,
            emitter = ui,
            cards = cardRepository,
            agentLauncherScript = {
                context.assets.open("harness/run-agent.sh").bufferedReader().use { it.readText() }
            }
        ),
        speech = speech,
        emitter = ui,
        scope = scope
    )
    val conversation = Conversation(
        settingsRepository = settingsRepository,
        history = historyRepository,
        cards = cardRepository,
        chat = chatController,
        emitter = ui,
        today = { LocalDate.now().toString() }
    )

    private val turnListener = TurnListener(
        audioGateway = models.audioGateway,
        recorder = TurnRecorder(
            voice = VoiceDetector.create(context),
            endWordSpotter = KeywordSpotterModel(context, KeywordSpotterModel.Language.CHINESE)
        ),
        headsetMic = headsetMic,
        rollingAsr = RollingAsr(models.audioGateway, scope),
        scope = scope
    )

    val voice = VoiceRuntime(
        settingsRepository = settingsRepository,
        permissions = ui,
        hasHeadsetOutput = { AudioRoutes.hasHeadsetOutput(context) },
        wake = object : WakeControl {
            override fun pause() {
                mainHandler.post { WakeWordService.pause(context) }
            }

            override fun resume() {
                mainHandler.post { WakeWordService.resume(context) }
            }
        },
        cue = ackCue,
        capture = turnListener,
        isPushToTalkActive = pushToTalk::isActive,
        submit = conversation::submit,
        abortReply = chatController::cancel,
        emitter = ui,
        scope = scope
    ).also { chatController.observer = it }

    // ── features: pc ────────────────────────────────────────────────────────
    val pc = PcController(
        settingsRepository = settingsRepository,
        pcGateway = pcGateway,
        emitter = ui,
        scope = scope,
        updateManifestUrl = UpdateController.MANIFEST_URL
    )

    init {
        // The wake word reaches the conversation directly: no screen is involved.
        WakeWordBus.listener = {
            WakeWordBus.consumePending()
            voice.onWakeWord()
        }
        if (WakeWordBus.consumePending()) voice.onWakeWord()
        Logs.add("Core started")
    }

    /** A bundled cue as PCM. The cues are 24 kHz mono, the audio queue's own format. */
    private fun cueSound(context: Context, resId: Int): ByteArray {
        val pcm = context.resources.openRawResource(resId).use { WavPcm.parse(it.readBytes()) }
        check(pcm.sampleRate == AudioQueue.SAMPLE_RATE && pcm.channels == 1) { "cue $resId is not 24 kHz mono" }
        return pcm.data
    }

    companion object {
        @Volatile
        private var instance: AppCore? = null

        /** The one core of this process, created on first use. Call from the main thread. */
        fun get(context: Context): AppCore = instance ?: synchronized(this) {
            instance ?: AppCore(context.applicationContext).also { instance = it }
        }
    }
}
