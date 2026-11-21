package com.mindtravel.reduction.feature.chat.data

import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Shares the attachment layout used by desktop imports and ZIP exports. */
fun CardRepository.attachFile(card: String, name: String, bytes: ByteArray): String = synchronized(this) {
    require(read(card) != null && !isLocked(card) && !isProgramCard(card)) { "请先保存卡片，并确认可以编辑" }
    require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "附件名无效" }
    require(bytes.size <= CardRepository.MAX_MEDIA_BYTES) { "附件超过 20 MB" }
    val root = attachmentDirectory(card)
    root.mkdirs()
    val id = UUID.randomUUID().toString().replace("-","")
    val tmp = File(root,".partial-$id")
    check(tmp.mkdir())
    try {
        File(tmp,"original").writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(tmp,"meta.json").writeText(JSONObject().put("id",id).put("name",name).put("size",bytes.size)
            .put("sha256",hash).put("type",when (name.substringAfterLast('.').lowercase()) {
                "md","markdown" -> "text/markdown"
                "txt" -> "text/plain"
                else -> java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
            }).toString())
        check(tmp.renameTo(File(root,id))) { "保存附件失败" }
    } finally { tmp.deleteRecursively() }
    id
}

fun CardRepository.textAttachments(card: String): List<Pair<String,File>> =
    attachmentDirectory(card).listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{32}")) }
        .mapNotNull { dir ->
            val name = JSONObject(File(dir,"meta.json").readText()).getString("name")
            if (name.substringAfterLast('.').lowercase() in setOf("md","markdown","txt")) name to File(dir,"original") else null
        }
