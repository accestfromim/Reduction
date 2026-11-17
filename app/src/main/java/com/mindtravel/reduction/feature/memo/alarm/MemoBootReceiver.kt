package com.mindtravel.reduction.feature.memo.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindtravel.reduction.app.AppCore

/**
 * After a restart the system's alarms are gone; the to-dos are not, so the alarms are set
 * again from them.
 */
class MemoBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val core = runCatching { AppCore.get(context.applicationContext) }.getOrNull() ?: return
        MemoAlarms(context.applicationContext).reschedule(core.memoStore)
    }
}
