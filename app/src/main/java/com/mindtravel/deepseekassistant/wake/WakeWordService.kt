package com.mindtravel.deepseekassistant.wake

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
import com.mindtravel.deepseekassistant.core.Logs
import com.mindtravel.deepseekassistant.data.SettingsRepository
import com.mindtravel.deepseekassistant.ui.MainActivity

class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_START = "com.mindtravel.deepseekassistant.START_WAKE"
        private const val ACTION_PAUSE = "com.mindtravel.deepseekassistant.PAUSE_WAKE"
        private const val ACTION_RESUME = "com.mindtravel.deepseekassistant.RESUME_WAKE"
        private const val ACTION_STOP = "com.mindtravel.deepseekassistant.STOP_WAKE"

        @Volatile
        var isRunning = false
            private set

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
            ACTION_RESUME -> {
                startForegroundCompat(buildNotification("正在监听“你好助手”"))
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
        updateNotification("正在监听“你好助手”")
        val current = detector ?: WakeWordDetector(
            context = this,
            onWake = {
                updateNotification("已唤醒，等待回答")
                WakeWordBus.onWake()
            },
            onListening = { updateNotification("正在监听“你好助手”") },
            onError = { message ->
                Logs.add("Wake word error: $message")
                updateNotification(message)
            }
        ).also { detector = it }
        current.start()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "语音唤醒",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "常驻监听“你好助手”"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeepSeek 助手")
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
        Logs.add("Wake word service destroyed")
        super.onDestroy()
    }
}
