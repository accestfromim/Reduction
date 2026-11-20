package com.mindtravel.reduction.feature.memo

import com.mindtravel.reduction.feature.memo.data.DueParser
import com.mindtravel.reduction.feature.memo.data.MemoStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The to-dos as the review card draws them: the three blocks, and for each to-do what it
 * says, the day it is due, and how it stands. The page draws this; it holds no to-dos of its
 * own.
 */
object MemoView {

    /**
     * [exact] says the morning alarm may be set to the minute; [notify] says the app may show
     * notifications. Without either the reminders are degraded, and the card says so.
     */
    fun json(store: MemoStore, exact: Boolean, notify: Boolean): String {
        val today = store.today()
        val view = store.view(today)
        fun block(memos: List<MemoStore.Memo>) = JSONArray().apply {
            for (memo in memos) put(JSONObject().apply {
                put("id", memo.id)
                put("text", memo.text)
                put("done", memo.done)
                put("due", memo.due?.let { DueParser.describe(it, memo.part, today) } ?: DueParser.EMPTY)
                put("part", memo.part?.label.orEmpty())
                put("empty", memo.due == null)
                put("overdue", store.isOverdue(memo, today))
            })
        }
        return JSONObject().apply {
            put("today", block(view.today))
            put("future", block(view.future))
            put("undated", block(view.undated))
            put("exact", exact)
            put("notify", notify)
        }.toString()
    }
}
