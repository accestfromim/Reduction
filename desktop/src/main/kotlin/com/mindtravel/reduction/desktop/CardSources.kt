package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Path

class CardSources(private val config: File) {
    private fun mounts() = if (config.isFile) JSONObject(config.readText()) else JSONObject()
    fun folders(): String = JSONArray(mounts().keySet().sorted()).toString()

    fun shortcuts(): String {
        val bindings = mounts()
        val entries = bindings.keySet().map { folder ->
            val drive = Regex("^/mnt/([a-z])(?:/|$)").find(bindings.getString(folder))?.groupValues?.get(1)
            val leaf = folder.substringAfterLast('/')
            JSONObject().put("folder", folder).put("label", if (drive == null) leaf else drive.uppercase() + " 盘 · " + leaf)
        }
        return JSONArray(entries.sortedBy { it.getString("label") }).toString()
    }

    fun resolve(folder: String, relative: String, settings: AppSettings): File {
        val raw = File(mounts().getString(folder))
        require(raw.isAbsolute)
        val root = raw.toPath().toRealPath()
        require(settings.agentDirectories.any { root.startsWith(File(it.path).canonicalFile.toPath()) }) { "目录未获读取权限" }
        val path = Path.of(relative)
        require(!path.isAbsolute && path.none { it.toString().startsWith(".") } && !relative.contains('\\')) { "需要挂载目录内的相对路径" }
        val real = root.resolve(path).toRealPath()
        require(real.startsWith(root) && root.relativize(real).none { it.toString().startsWith(".") }) { "路径越界" }
        return real.toFile()
    }

    fun list(folder: String, relative: String, settings: AppSettings): String {
        val dir = resolve(folder, relative, settings)
        val entries = requireNotNull(dir.listFiles()) { "目录不可读" }
        val visible = entries.filter { !it.name.startsWith(".") && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
        return JSONArray(visible.map { JSONObject().put("name", it.name).put("directory", it.isDirectory) }).toString()
    }
}
