package com.mindtravel.entropy.feature.voice.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mindtravel.entropy.core.Logs
import com.mindtravel.entropy.feature.settings.data.SettingsRepository
import com.mindtravel.entropy.model.wake.KeywordSpotterModel

class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_START = "com.mindtravel.entropy.START_WAKE"
        private const val ACTION_PAUSE = "com.mindtravel.entropy.PAUSE_WAKE"
        private const val ACTION_RESUME = "com.mindtravel.entropy.RESUME_WAKE"
        private const val ACTION_RESTART = "com.mindtravel.entropy.RESTART_WAKE"
        private const val ACTION_STOP = "com.mindtravel.entropy.STOP_WAKE"

        @Volatile
        var isRunning = false
            private set

        /**
         * The service is running but hears nothing: the system gives a service it restarted
         * in the background no microphone. Bringing the app to the front and restarting the
         * listener from there gets the microphone back.
         */
        @Volatile
        var deaf = false
            private set

        /** Stops and starts the listener. Call while the app is in the foreground. */
        fun restartListening(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, WakeWordService::class.java).setAction(ACTION_RESTART)
                )
            }
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun pause(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, WakeWordService::class.java).setAction(ACTION_PAUSE)
                )
            }
        }

        fun resume(context: Context) {
            if (!isRunning) {
                start(context)
                return
            }
            runCatching {
                context.startService(
                    Intent(context, WakeWordService::class.java).setAction(ACTION_RESUME)
                )
            }
        }
    }

    private var detector: WakeWordDetector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SettingsRepository(this).update { it.copy(wakeWordEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> {
                detector?.stop()
                updateNotification("已唤醒，等待回答")
            }
            ACTION_RESTART -> {
                Logs.add("Wake word listener restarted from the foreground")
                detector?.stop()
                startForegroundCompat(buildNotification("正在监听“Hi Entropy”"))
                startDetector()
            }
            ACTION_RESUME -> {
                startForegroundCompat(buildNotification("正在监听“Hi Entropy”"))
                startDetector()
            }
            else -> {
                startForegroundCompat(buildNotification("正在启动唤醒词…"))
                startDetector()
            }
        }
        return START_STICKY
    }

    private fun startDetector() {
        updateNotification("正在监听“Hi Entropy”")
        val current = detector ?: WakeWordDetector(
            model = KeywordSpotterModel(this, KeywordSpotterModel.Language.ENGLISH),
            onWake = {
                WakeWordBus.onWake()
                updateNotification("已唤醒，正在打开 Entropy…")
                launchApp()
            },
            onListening = {
                deaf = false
                updateNotification("正在监听“Hi Entropy”")
            },
            onDeaf = { nowDeaf ->
                deaf = nowDeaf
                updateNotification(if (nowDeaf) "麦克风被系统收回，点此恢复监听" else "正在监听“Hi Entropy”")
            },
            onError = { message ->
                Logs.add("Wake word error: $message")
                updateNotification(message)
            }
        ).also { detector = it }
        current.start()
    }

    private fun launchApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        } ?: return
        runCatching { startActivity(intent) }
            .onSuccess {
                Logs.add("Wake launch requested")
                updateNotification("已唤醒，等待回答")
            }
            .onFailure {
                Logs.add("Wake launch blocked: ${it.message}")
                updateNotification("已唤醒，点此打开 Entropy")
            }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "语音唤醒",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "常驻监听“Hi Entropy”"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Entropy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "停止监听", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        detector?.release()
        detector = null
        isRunning = false
        deaf = false
        Logs.add("Wake word service destroyed")
        super.onDestroy()
    }
}
