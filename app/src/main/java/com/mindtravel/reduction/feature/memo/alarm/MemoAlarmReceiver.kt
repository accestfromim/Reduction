package com.mindtravel.reduction.feature.memo.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindtravel.reduction.app.AppCore
import com.mindtravel.reduction.core.Logs

/**
 * The morning alarm has come due. What the reminder says is worked out afresh from the
 * to-dos: what is due today, what is overdue, and what has an empty date. Each is brought
 * up once a day, so an alarm that fires twice says nothing the second time.
 */
class MemoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val app = context.applicationContext
        val core = runCatching { AppCore.get(app) }.getOrNull() ?: return
        val store = core.memoStore
        val alarms = MemoAlarms(app)
        val owed = store.morningReminder()
        if (owed.isNotEmpty()) {
            alarms.notify(owed, store)
            store.markReminded(owed)
            Logs.add("Memo: morning reminder for ${owed.size} (${owed.count { it.due == null }} with an empty date)")
        }
        // Tomorrow morning's alarm.
        alarms.reschedule(store)
    }

    companion object {
        const val ACTION_REMIND = "com.mindtravel.reduction.MEMO_REMIND"
    }
}
