package com.mindtravel.reduction.feature.memo

import com.mindtravel.reduction.feature.memo.data.DueParser
import com.mindtravel.reduction.feature.memo.data.MemoStore
import java.time.LocalDate

/**
 * The review card: the memo plugin's contents as a card, in its three blocks — today,
 * later, and without a date. The program writes it from [MemoStore] whenever the to-dos
 * change, so what the card says is always what the plugin holds. The assistant edits the
 * to-dos through its tool and never this card; the user ticks a to-do off on the card.
 */
object MemoCard {
    const val CARD = "计划"
    const val PURPOSE = "程序写入的待办与日期：今天、未来、没日期。助手用 todo 工具增改，用户在卡片上勾掉"

    fun render(store: MemoStore): String {
        val view = store.view()
        val today = store.today()
        return buildString {
            appendLine("这张卡片和待办是同一份数据，由程序写入。点一下就能完成一条；助手的增改通过 todo 工具。")
            // Today's finished to-dos are listed, so a day's work can be read back. Later
            // ones and undated ones are only counted: what matters there is what is still open.
            section("今天", view.today, today, store, showFinished = true)
            section("未来", view.future, today, store, showFinished = false)
            section("没日期", view.undated, today, store, showFinished = false)
        }.trimEnd() + "\n"
    }

    private fun StringBuilder.section(
        title: String,
        memos: List<MemoStore.Memo>,
        today: LocalDate,
        store: MemoStore,
        showFinished: Boolean
    ) {
        appendLine("## $title")
        appendLine()
        if (memos.isEmpty()) {
            appendLine("（没有）")
            appendLine()
            return
        }
        for (memo in memos) {
            if (memo.done && !showFinished) continue
            append("- [")
            append(if (memo.done) "x" else " ")
            append("] #")
            append(memo.id)
            append(' ')
            append(memo.text)
            val day = memo.due
            if (day != null) {
                append(" · ")
                append(DueParser.describe(day, today))
                if (store.isOverdue(memo)) append(" · 已过期")
            }
            appendLine()
        }
        if (!showFinished) {
            val finished = memos.count { it.done }
            if (finished > 0) appendLine("- 已完成 $finished 条（记录保留）")
        }
        appendLine()
    }
}
