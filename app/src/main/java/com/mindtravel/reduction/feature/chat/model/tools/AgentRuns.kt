package com.mindtravel.reduction.feature.chat.model.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The sub agent runs this app has started, each under a small number of its own: 1, 2, 3…
 * The numbers are given out here and nowhere else. The assistant speaks of "任务 2"; which
 * folder that run lives in and what the computer calls it is looked up here, so neither
 * has to pass through the assistant's hands.
 *
 * Each run is also a record of what happened, in the words that were actually used: the
 * task as it was handed over, when, and what came back. Kept in a file, so it survives
 * the app being restarted.
 */
class AgentRuns(private val file: File? = null, private val now: () -> Long = System::currentTimeMillis) {

    class Run(
        val number: Int,
        /** What the launcher on the computer calls this run. */
        val launcherId: String,
        val folderName: String,
        val folder: String,
        /** The task exactly as it was handed over. */
        val task: String,
        val write: Boolean,
        /** The run may change the project: its record ends with what it changed. */
        val changesProject: Boolean,
        val startedAt: Long,
        /** Who did the work: "opencode" or "claude". */
        val engine: String = "opencode",
        /** The card the task was read from and the result is written back to; blank when there is none. */
        var card: String = "",
        /** Stands for exactly what was asked, where, and of whom: the same request has the same one. */
        val fingerprint: String = "",
        var endedAt: Long = 0,
        /** How it ended: 完成, 失败, 中断 or 已停止. Empty while running. */
        var outcome: String = "",
        /** What the sub agent handed back, word for word. */
        var result: String = "",
        var diff: String = "",
        val host: String = "",
        val remoteDir: String = "",
        var phase: String = "running",
        var replyId: String = "",
        var reply: String = ""
    ) {
        val finished: Boolean get() = outcome.isNotEmpty()

        /** The first line of the task, to tell runs apart. */
        val title: String get() = task.trim().lineSequence().firstOrNull().orEmpty().take(TITLE_CHARS)
    }

    /**
     * Pictures of runs whose end was learnt by asking, not by the watch: a tool's answer is
     * words only, so they wait here for the watch to hand them to the assistant. Not kept
     * across a restart.
     */
    private val waitingPictures = ArrayList<Triple<Int, String, List<com.mindtravel.reduction.model.chat.ChatImage>>>()

    @Synchronized
    fun holdPictures(number: Int, report: String, images: List<com.mindtravel.reduction.model.chat.ChatImage>) {
        waitingPictures += Triple(number, report, images)
    }

    @Synchronized
    fun takePictures(): List<Triple<Int, String, List<com.mindtravel.reduction.model.chat.ChatImage>>> =
        waitingPictures.toList().also { waitingPictures.clear() }

    /** Told after every change, so that what is shown of the runs can follow. */
    @Volatile
    var onChange: () -> Unit = {}

    private val runs = ArrayList<Run>()
    private val cards = HashMap<String, Run>()
    private var lastNumber = 0

    init {
        runCatching {
            val saved = JSONObject(file!!.readText())
            lastNumber = saved.optInt("last")
            val list = saved.optJSONArray("runs") ?: JSONArray()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                runs += Run(
                    number = item.getInt("number"), launcherId = item.getString("id"),
                    folderName = item.getString("folderName"), folder = item.getString("folder"),
                    task = item.optString("task"), write = item.optBoolean("write"),
                    changesProject = item.optBoolean("changes"), startedAt = item.optLong("startedAt"),
                    engine = item.optString("engine", "opencode"), card = item.optString("card"),
                    fingerprint = item.optString("fingerprint"),
                    endedAt = item.optLong("endedAt"), outcome = item.optString("outcome"),
                    result = item.optString("result"), diff = item.optString("diff"), host = item.optString("host"), remoteDir = item.optString("remoteDir"), phase = item.optString("phase", "running"),
                    replyId = item.optString("replyId"), reply = item.optString("reply")
                )
            }
        }
        for (run in runs) if (run.host.isNotEmpty() && run.card.isNotEmpty()) cards[run.card] = run
    }

    @Synchronized
    fun add(
        launcherId: String, folderName: String, folder: String, task: String, write: Boolean = false,
        changesProject: Boolean = false, engine: String = "opencode", card: String = "", fingerprint: String = "", host: String = "", remoteDir: String = ""
    ): Run {
        val run = Run(
            ++lastNumber, launcherId, folderName, folder, task.trim(), write, changesProject, now(),
            engine, card, fingerprint, host = host, remoteDir = remoteDir
        )
        runs += run
        if (host.isNotEmpty() && card.isNotEmpty()) cards[card] = run
        save()
        return run
    }

    @Synchronized
    fun find(number: Int): Run? = runs.firstOrNull { it.number == number }

    /** The run that was given this very request before, if there was one. */
    @Synchronized
    fun sameRequest(fingerprint: String): Run? = runs.lastOrNull { it.fingerprint.isNotEmpty() && it.fingerprint == fingerprint }

    /** Runs not yet known to be over, oldest first. */
    @Synchronized
    fun unfinished(): List<Run> = runs.filter { !it.finished }

    @Synchronized
    fun latest(): Run? = runs.lastOrNull()

    /** Every run on record, newest first. */
    @Synchronized
    fun all(): List<Run> = runs.reversed()

    /** Records how [run] ended and what it handed back. The first ending recorded is the one that stays. */
    @Synchronized
    fun end(run: Run, outcome: String, result: String = "", diff: String = "") {
        if (run.finished) return
        run.outcome = outcome
        run.result = result.trim()
        run.diff = diff.trim()
        run.endedAt = now()
        save()
    }

    @Synchronized
    fun reply(run: Run, id: String, text: String) {
        run.replyId = id
        run.reply = text
        save()
    }

    @Synchronized
    fun byCard(name: String): Run? = cards[name]

    @Synchronized
    fun card(run: Run, name: String) {
        if (run.card == name) return
        cards.remove(run.card)
        run.card = name
        if (name.isNotEmpty() && run.host.isNotEmpty()) cards[name] = run
        save()
    }

    @Synchronized
    fun phase(run: Run, value: String) {
        if (run.phase == value && run.host.isNotEmpty()) return
        run.phase = value
        run.outcome = when (value) {
            "done" -> DONE
            "failed" -> FAILED
            "stopped" -> STOPPED
            else -> ""
        }
        run.endedAt = if (run.finished) now() else 0
        save()
    }

    @Synchronized
    fun remove(number: Int) {
        val run = find(number) ?: return
        runs.remove(run)
        cards.remove(run.card)
        save()
    }

    private fun save() {
        val target = file
        if (target != null) {
            target.parentFile?.mkdirs()
            val list = JSONArray()
            for (run in runs) {
                list.put(
                    JSONObject().put("number", run.number).put("id", run.launcherId).put("folderName", run.folderName)
                        .put("folder", run.folder).put("task", run.task).put("write", run.write)
                        .put("changes", run.changesProject).put("startedAt", run.startedAt).put("endedAt", run.endedAt)
                        .put("engine", run.engine).put("card", run.card).put("fingerprint", run.fingerprint)
                        .put("replyId", run.replyId).put("reply", run.reply).put("host", run.host).put("remoteDir", run.remoteDir).put("phase", run.phase).put("outcome", run.outcome).put("result", run.result).put("diff", run.diff)
                )
            }
            val next = File(target.parentFile, target.name + ".tmp")
            next.writeText(JSONObject().put("last", lastNumber).put("runs", list).toString())
            check(next.renameTo(target)) { "无法保存 sub agent 目录" }
        }
        runCatching { onChange() }
    }

    companion object {
        const val DONE = "完成"
        const val FAILED = "失败"
        const val LOST = "中断"
        const val STOPPED = "已停止"
        const val TIMED_OUT = "超时"
        const val EMPTY = "没有拿到内容"

        private const val TITLE_CHARS = 40
    }
}
