package com.mindtravel.reduction.feature.memo.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mindtravel.reduction.R
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.feature.memo.data.DueParser
import com.mindtravel.reduction.feature.memo.data.MemoStore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reminds the user of the day's to-dos, through one system alarm each morning at
 * [DAILY_HOUR]. The reminder is about that day: what is due today, and what was due
 * earlier and is still open — brought up again every morning until it is finished or
 * given a new day. To-dos whose date is empty count as tomorrow's and come with the
 * question whether to fill it in. Finishing a to-do takes it out; the record stays.
 *
 * The alarm is set again from the to-dos whenever they change, and after a restart.
 * Without the exact-alarm permission it is still set, only not to the minute; without the
 * notification permission nothing can be shown, and the review card says so.
 */
class MemoAlarms(private val context: Context) {

    /** What the review card tells the user about the reminders as they stand. */
    class Status(
        /** Alarms go out to the minute. */
        val exact: Boolean,
        /** The app may show notifications at all. */
        val notify: Boolean,
        /** The app may keep working after the phone restarts the process. */
        val boot: Boolean
    ) {
        val degraded: Boolean get() = !exact || !notify
    }

    private val manager: AlarmManager? get() = context.getSystemService(AlarmManager::class.java)

    fun status(): Status {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager?.canScheduleExactAlarms() == true else true
        val canNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return Status(exact = canExact, notify = canNotify, boot = true)
    }

    /** The system page where exact alarms are allowed, for a user who wants them to the minute. */
    fun exactAlarmSettingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
        else null

    /**
     * Sets the morning alarm the to-dos call for, replacing what was set before. It is set
     * whenever any to-do is open: one due on a later day needs it on its day.
     */
    fun reschedule(store: MemoStore) {
        val alarm = manager ?: return
        alarm.cancel(pending())
        val open = store.open().size
        if (open > 0) set(alarm, nextMorning(store.today()), exact = status().exact)
        Logs.add("Memo alarm: $open open, ${if (open > 0) "set for the next morning" else "none set"}")
    }

    private fun nextMorning(today: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        var day = today
        var at = day.atTime(LocalTime.of(DAILY_HOUR, DAILY_MINUTE)).atZone(zone).toInstant().toEpochMilli()
        if (at <= System.currentTimeMillis()) {
            day = day.plusDays(1)
            at = day.atTime(LocalTime.of(DAILY_HOUR, DAILY_MINUTE)).atZone(zone).toInstant().toEpochMilli()
        }
        return at
    }

    private fun set(alarm: AlarmManager, at: Long, exact: Boolean) {
        runCatching {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending())
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending())
        }.onFailure { Logs.add("Memo alarm not set: ${it.message}") }
    }

    private fun pending(): PendingIntent {
        val intent = Intent(context, MemoAlarmReceiver::class.java).setAction(MemoAlarmReceiver.ACTION_REMIND)
        return PendingIntent.getBroadcast(
            context, MORNING_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Shows the reminder for [memos] now, one notification for the lot. */
    fun notify(memos: List<MemoStore.Memo>, store: MemoStore) {
        if (memos.isEmpty()) return
        val managerNotification = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "每天早上提醒当天的待办"
            enableVibration(true)
        }
        managerNotification.createNotificationChannel(channel)

        val today = store.today()
        val unfilled = memos.count { it.due == null }
        val title = when {
            unfilled == memos.size -> ASK_TO_FILL
            memos.size == 1 -> "今天的待办"
            unfilled > 0 -> "${memos.size} 件待办，$unfilled 件没有标记时间，你要补齐吗？"
            else -> "今天 ${memos.size} 件待办"
        }
        val text = memos.joinToString("\n") { memo ->
            memo.text + when {
                memo.due == null -> " · 没有标记时间"
                memo.due!! < today -> " · " + DueParser.describe(memo.due!!, today) + " · 已过期"
                else -> memo.part?.let { " · " + it.label }.orEmpty()
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val open = PendingIntent.getActivity(
            context, 0, launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(memos.first().text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { managerNotification.notify(NOTIFICATION_ID, notification) }
            .onFailure { Logs.add("Memo notification not shown: ${it.message}") }
    }

    companion object {
        /** When the day's reminder goes out. */
        const val DAILY_HOUR = 9
        const val DAILY_MINUTE = 0

        /** The request code of the one alarm there is. */
        private const val MORNING_ID = 9

        /** What the reminder says of to-dos whose date is empty. */
        const val ASK_TO_FILL = "这些没有标记时间，你要补齐吗？"

        private const val CHANNEL_ID = "memo_reminder"
        private const val NOTIFICATION_ID = 4201
    }
}
