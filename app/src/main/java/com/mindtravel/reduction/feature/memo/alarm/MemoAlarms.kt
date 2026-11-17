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
 * Remembers the user of a to-do, through the system's alarms. An alarm goes out at the
 * to-do's time; one that was missed, or that came and went unfinished, is brought up
 * again each morning at [DAILY_HOUR] until it is finished or given a new time. A to-do
 * whose date is empty counts as tomorrow's: it comes up every morning too, asking for its
 * time, until it has one. Finishing
 * a to-do takes it out of the queue; the record stays.
 *
 * Alarms are set again from the to-dos whenever they change, and after a restart. Without
 * the exact-alarm permission they are still set, only not to the minute; without the
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

    /** Sets every alarm the to-dos call for, replacing what was set before. */
    fun reschedule(store: MemoStore) {
        val alarm = manager ?: return
        cancelAll()
        val now = System.currentTimeMillis()
        val today = store.today()

        // The reminder at each to-do's own time, as far ahead as the system likes to plan.
        for (memo in store.all()) {
            if (memo.done) continue
            val due = memo.dueAt ?: continue
            if (memo.dueReminded && due <= now) continue
            // A to-do far ahead is still set now: the system holds it, and anything that
            // changes the to-dos sets the alarms again anyway.
            val at = if (due > now) due else nextMorning(today)
            set(alarm, at, memo.id, exact = status().exact)
        }

        // The morning reminder: whatever is overdue, until it is finished or given a new
        // time, and whatever has an empty date, which counts as tomorrow until it gets one.
        val plan = store.plan(now)
        if (plan.morning.isNotEmpty()) set(alarm, nextMorning(today), OVERDUE_ID, exact = status().exact)
        Logs.add("Memo alarms set: ${store.open().count { it.dueAt != null }} with times, overdue=${plan.overdue.size}, empty=${plan.empty.size}")
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

    private fun set(alarm: AlarmManager, at: Long, memoId: Int, exact: Boolean) {
        val pending = pending(memoId)
        runCatching {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }.onFailure { Logs.add("Memo alarm not set: ${it.message}") }
    }

    private fun pending(id: Int): PendingIntent {
        val intent = Intent(context, MemoAlarmReceiver::class.java)
            .setAction(MemoAlarmReceiver.ACTION_REMIND)
            .putExtra(MemoAlarmReceiver.EXTRA_ID, if (id == OVERDUE_ID) OVERDUE_ID else id)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelAll() {
        val alarm = manager ?: return
        for (memoId in 1..MAX_ID) alarm.cancel(pending(memoId))
        alarm.cancel(pending(OVERDUE_ID))
    }

    /** Shows the reminder for [memos] now, one notification for the lot. */
    fun notify(memos: List<MemoStore.Memo>, store: MemoStore) {
        if (memos.isEmpty()) return
        val managerNotification = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到点提醒，以及过期未完成时每天早上的提醒"
            enableVibration(true)
        }
        managerNotification.createNotificationChannel(channel)

        val today = store.today()
        val unfilled = memos.count { it.dueAt == null }
        val title = when {
            unfilled == memos.size -> ASK_TO_FILL
            memos.size == 1 -> "到点了"
            unfilled > 0 -> "${memos.size} 件待办，$unfilled 件没有标记时间，你要补齐吗？"
            else -> "${memos.size} 件待办"
        }
        val text = memos.joinToString("\n") { memo ->
            memo.text + (memo.dueAt?.let { " · " + DueParser.describe(it, today) } ?: " · 没有标记时间")
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
        /** When the daily reminder for unfinished to-dos goes out. */
        const val DAILY_HOUR = 9
        const val DAILY_MINUTE = 0

        /** The id of the daily alarm, distinct from any to-do's number. */
        const val OVERDUE_ID = -9

        /** To-do numbers are given out from 1; this bounds what is cancelled. */
        private const val MAX_ID = 100_000

        /** What the reminder says of to-dos whose date is empty. */
        const val ASK_TO_FILL = "这些没有标记时间，你要补齐吗？"

        private const val CHANNEL_ID = "memo_reminder"
        private const val NOTIFICATION_ID = 4201
    }
}
