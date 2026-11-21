package com.mindtravel.reduction.desktop

import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.model.chat.ToolParam
import com.mindtravel.reduction.model.chat.ToolSpec
import org.json.JSONArray
import org.json.JSONObject
import com.mindtravel.reduction.model.chat.ChatImage
import java.util.Base64
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class CardFiles(private val cards: CardRepository, private val exports: File) {
    private val drafts = ConcurrentHashMap<String, Pair<String, String>>()

    fun draft(args: JSONArray): Boolean {
        drafts[args.getString(0)] = args.getString(1) to args.getString(2)
        return true
    }

    fun pending(name: String, saved: String): Boolean = drafts[name]?.let { it.first == saved && it.second != saved } == true

    fun clearDraft(name: String) { drafts.remove(name) }

    fun attachedFile(id: String): Pair<File, JSONObject>? {
        if (!Regex("[0-9a-f]{32}").matches(id)) return null
        for (card in cards.list()) {
            val folder = File(cards.attachmentDirectory(card.name), id)
            val blob = File(folder, "original")
            if (blob.isFile) return blob to JSONObject(File(folder, "meta.json").readText())
        }
        return null
    }

    fun preview(name: String, source: String, page: Int, author: Boolean): Pair<ChatImage,Int> {
        if (!author) cards.requireShareable(name)
        val dir = java.nio.file.Files.createTempDirectory("card-preview-").toFile()
        try {
            val legacy = JSONObject()
            for (id in cards.mediaIn(source)) legacy.put(id,cards.mediaFile(id)?.absolutePath ?: JSONObject.NULL)
            val request = JSONObject().put("action","preview").put("format","png").put("author",author)
                .put("name",name).put("text",source).put("exports",dir).put("dir",cards.attachmentDirectory(name)).put("legacy",legacy)
            val path = JSONArray("[" + runCardWorker("files.py",request) + "]").getString(0)
            return cardPngPage(File(path),page)
        } finally { dir.deleteRecursively() }
    }

    fun tool(settings: AppSettings) = object : ChatTool {
        private var image: ChatImage? = null
        override fun pictures(): List<ChatImage> = listOfNotNull(image).also { image = null }
        override val spec = ToolSpec("card_files", "CARD 电脑端文件工具。挂载原件、列出或读取附件，或导出卡片并返回交付文件的绝对路径。", listOf(
            ToolParam("action", "操作", choices=listOf("import","list","read","export")),
            ToolParam("card", "现有卡片全名"),
            ToolParam("path", "导入文件的绝对路径（位于已授权目录）", false),
            ToolParam("id", "读取附件时传 list 返回的 ID", false),
            ToolParam("format", "导出格式", false, listOf("html","markdown","pdf","png","jpg","zip"))
        ))
        override fun execute(argumentsJson: String): String {
            image = null
            val args = JSONObject(argumentsJson)
            val name = args.getString("card")
            val action = args.getString("action")
            if (action == "export") cards.requireShareable(name)
            val saved = cards.document(name) ?: error("卡片不存在")
            val text = drafts[name]?.takeIf { it.first == saved }?.second ?: saved
            val request = JSONObject().put("action", action).put("name", name).put("text", text)
                .put("dir", cards.attachmentDirectory(name)).put("exports", exports)
                .put("id", args.optString("id")).put("format", args.optString("format"))
            val legacy = JSONObject()
            for (id in cards.mediaIn(text)) legacy.put(id, cards.mediaFile(id)?.absolutePath ?: JSONObject.NULL)
            request.put("legacy", legacy)
            if (action == "import") {
                check(!cards.isLocked(name) && !cards.isProgramCard(name)) { "卡片已锁定" }
                val raw = args.getString("path")
                val path = if (Regex("""^[A-Za-z]:[\\/].*""").matches(raw))
                    "/mnt/" + raw[0].lowercaseChar() + "/" + raw.substring(3).replace('\\','/') else raw
                require(File(path).isAbsolute)
                val real = File(path).toPath().toRealPath()
                require(settings.agentDirectories.any { real.startsWith(File(it.path.replace("~/", System.getProperty("user.home") + "/")).canonicalFile.toPath()) }) { "文件在授权目录之外" }
                require(real.toFile().isFile)
                request.put("path", real.toString())
            }
            val result = runCardWorker("files.py", request)
            if (action == "read") {
                val id = args.getString("id")
                val blob = if (Regex("[0-9a-f]{32}").matches(id))
                    File(File(cards.attachmentDirectory(name), id), "original") else cards.mediaFile(id)
                val meta = runCatching { JSONObject(result).optJSONObject("original") }.getOrNull()
                if (meta?.optString("type") in setOf("image/jpeg","image/png","image/webp","image/gif") &&
                    blob != null && blob.isFile && blob.length() <= 8_000_000) {
                    image = ChatImage(meta!!.getString("name"), meta.getString("type"),
                        Base64.getEncoder().encodeToString(blob.readBytes()))
                }
            }
            return result
        }
    }
}
