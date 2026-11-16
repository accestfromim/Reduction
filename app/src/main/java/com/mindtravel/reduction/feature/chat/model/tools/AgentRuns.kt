package com.mindtravel.reduction.feature.chat.model.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The sub agent runs this app has started, each under a small number of its own: 1, 2, 3…
 * The numbers are given out here and nowhere else. The assistant speaks of "任务 2"; which
 * folder that run lives in and what the computer calls it is looked up here, so neither
 * has to pass through the assistant's hands. Kept in a file, so a run started before the
 * app was restarted can still be asked about.
 */
class AgentRuns(private val file: File? = null) {

    class Run(
        val number: Int,
        /** What the launcher on the computer calls this run. */
        val launcherId: String,
        val folderName: String,
        val folder: String,
        /** The first line of the task, to tell runs apart when reporting. */
        val title: String,
        /** The run may change the project: its report ends with what it changed. */
        val changesProject: Boolean = false,
        var finished: Boolean = false
    )

    private val runs = ArrayList<Run>()
    private var lastNumber = 0

    init {
        runCatching {
            val saved = JSONObject(file!!.readText())
            lastNumber = saved.optInt("last")
            val list = saved.optJSONArray("runs") ?: JSONArray()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                runs += Run(
                    item.getInt("number"), item.getString("id"), item.getString("folderName"),
                    item.getString("folder"), item.optString("title"), item.optBoolean("changes"), item.optBoolean("finished")
                )
            }
        }
    }

    @Synchronized
    fun add(launcherId: String, folderName: String, folder: String, task: String, changesProject: Boolean = false): Run {
        val title = task.trim().lineSequence().firstOrNull().orEmpty().take(TITLE_CHARS)
        val run = Run(++lastNumber, launcherId, folderName, folder, title, changesProject)
        runs += run
        while (runs.size > KEPT) runs.removeAt(0)
        save()
        return run
    }

    @Synchronized
    fun find(number: Int): Run? = runs.firstOrNull { it.number == number }

    /** Runs not yet known to be over, oldest first. */
    @Synchronized
    fun unfinished(): List<Run> = runs.filter { !it.finished }

    @Synchronized
    fun latest(): Run? = runs.lastOrNull()

    @Synchronized
    fun finish(run: Run) {
        if (run.finished) return
        run.finished = true
        save()
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val list = JSONArray()
            for (run in runs) {
                list.put(
                    JSONObject().put("number", run.number).put("id", run.launcherId).put("folderName", run.folderName)
                        .put("folder", run.folder).put("title", run.title).put("changes", run.changesProject).put("finished", run.finished)
                )
            }
            target.writeText(JSONObject().put("last", lastNumber).put("runs", list).toString())
        }
    }

    private companion object {
        const val KEPT = 30
        const val TITLE_CHARS = 40
    }
}
