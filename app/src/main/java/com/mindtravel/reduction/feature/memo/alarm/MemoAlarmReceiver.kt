package com.mindtravel.reduction.feature.memo.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mindtravel.reduction.app.AppCore
import com.mindtravel.reduction.core.Logs

/**
 * An alarm has come due: a to-do reached its time, or the morning reminder for what is
 * overdue is due. What is remembered is worked out afresh from the to-dos, so an alarm
 * that fires late, or for a to-do that has since been finished or moved, does nothing.
 */
class MemoAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val app = context.applicationContext
        val core = runCatching { AppCore.get(app) }.getOrNull() ?: return
        val store = core.memoStore
        val alarms = MemoAlarms(app)
        val now = System.currentTimeMillis()
        val id = intent.getIntExtra(EXTRA_ID, 0)

        if (id == MemoAlarms.OVERDUE_ID) {
            // Morning: everything still unfinished whose time has passed, once a day.
            val today = store.today().toString()
            val overdue = store.open().filter { it.dueAt != null && it.dueAt!! <= now && it.overdueRemindedOn != today }
            if (overdue.isNotEmpty()) {
                alarms.notify(overdue, store)
                store.markOverdueReminded(overdue)
                Logs.add("Memo: reminded of ${overdue.size} overdue")
            }
            return
        }

        val memo = store.find(id)
        if (memo != null && !memo.done && memo.dueAt != null) {
            if (memo.dueAt!! <= now && !memo.dueReminded) {
                alarms.notify(listOf(memo), store)
                store.markDueReminded(memo)
                Logs.add("Memo: reminded of #$id at its time")
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.mindtravel.reduction.MEMO_REMIND"
        const val EXTRA_ID = "id"
        const val ACTION_RESCHEDULE = "com.mindtravel.reduction.MEMO_RESCHEDULE"
    }
}
