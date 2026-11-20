package com.mindtravel.reduction.desktop

import java.io.File
import org.json.JSONObject

internal fun runCardWorker(script: String, request: JSONObject): String {
    val root = File(System.getenv("REDUCTION_ROOT") ?: System.getProperty("user.dir"))
    val process = ProcessBuilder("python3", File(root, "desktop/card/$script").path)
        .directory(root).redirectErrorStream(true).start()
    process.outputStream.bufferedWriter().use { it.write(request.toString()) }
    val result = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { result.takeLast(1500) }
    return result
}
