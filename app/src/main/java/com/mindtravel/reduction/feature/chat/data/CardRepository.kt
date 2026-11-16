package com.mindtravel.reduction.feature.chat.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Memo cards kept on the phone. A card is a Markdown text that collects one kind of
 * information over time — a line of research ideas, a to-do memo, the current task —
 * with a one-line purpose saying what belongs in it. It is edited like any text, by
 * patches (see [CardPatch]). The
 * assistant works from the directory (names and purposes) and reads a card's text only
 * when it needs it.
 */
class CardRepository(private val dir: File) {

    class Info(val name: String, val purpose: String, val chars: Int, val updatedAt: Long)

    @Synchronized
    fun list(): List<Info> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(TEXT) }
        .mapNotNull { file ->
            val name = decode(file.name.removeSuffix(TEXT)) ?: return@mapNotNull null
            val text = runCatching { file.readText() }.getOrDefault("")
            Info(name, purposeOf(name), text.length, file.lastModified())
        }
        .sortedByDescending { it.updatedAt }

    @Synchronized
    fun read(name: String): String? =
        textFile(name)?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    @Synchronized
    fun purpose(name: String): String = cleanName(name)?.let { purposeOf(it) }.orEmpty()

    /**
     * Creates a card or replaces its whole text. A null [purpose] leaves the purpose as it
     * is. Returns false when the name is not usable.
     */
    @Synchronized
    fun save(name: String, content: String, purpose: String? = null): Boolean {
        val file = textFile(name) ?: return false
        dir.mkdirs()
        if (purpose != null) writePurpose(name, purpose)
        return write(file, content.take(MAX_CHARS))
    }

    /**
     * Applies a patch over the cards. All of it takes effect or none of it does.
     * Returns one line per card changed; throws [CardPatch.Invalid] when the patch does not fit.
     */
    @Synchronized
    fun patch(text: String): List<String> {
        val ops = CardPatch.parse(text)
        // Work everything out first, so a patch that fails half way has changed nothing.
        val writes = LinkedHashMap<String, Pair<String, String?>>()
        val deletes = LinkedHashSet<String>()
        val summary = ArrayList<String>()
        fun currentText(name: String): String? =
            if (name in deletes) null else writes[name]?.first ?: read(name)
        for (op in ops) {
            when (op) {
                is CardPatch.Add -> {
                    if (currentText(op.name) != null) {
                        throw CardPatch.Invalid("已经有一张叫“${op.name}”的卡片，改它要用 *** Update Card:")
                    }
                    deletes -= op.name
                    writes[op.name] = op.text.trimEnd() .let { if (it.isEmpty()) "" else it + "\n" } to op.purpose
                    summary += "新建“${op.name}”"
                }
                is CardPatch.Update -> {
                    val before = currentText(op.name)
                        ?: throw CardPatch.Invalid("没有名为“${op.name}”的卡片。现有卡片：" + list().joinToString("、") { it.name }.ifBlank { "无" })
                    val after = CardPatch.apply(op.name, before, op.hunks)
                    if (after.length > MAX_CHARS) throw CardPatch.Invalid("卡片“${op.name}”会超过 $MAX_CHARS 字，先整理压缩")
                    if (after == before && op.purpose == null) throw CardPatch.Invalid("对卡片“${op.name}”的改动没有改变任何内容")
                    writes[op.name] = after to (op.purpose ?: writes[op.name]?.second)
                    summary += "修改“${op.name}”（${op.hunks.size} 处）"
                }
                is CardPatch.Delete -> {
                    if (currentText(op.name) == null) throw CardPatch.Invalid("没有名为“${op.name}”的卡片可删")
                    writes -= op.name
                    deletes += op.name
                    summary += "删除“${op.name}”"
                }
            }
        }
        for (name in deletes) delete(name)
        for ((name, change) in writes) {
            if (!save(name, change.first, change.second)) throw CardPatch.Invalid("卡片“$name”写入失败")
        }
        return summary
    }

    @Synchronized
    fun delete(name: String): Boolean {
        purposeFile(name)?.delete()
        return textFile(name)?.takeIf { it.isFile }?.delete() ?: false
    }

    fun directoryJson(): String = JSONArray().apply {
        for (card in list()) {
            put(JSONObject().apply {
                put("name", card.name)
                put("purpose", card.purpose)
                put("chars", card.chars)
                put("updatedAt", card.updatedAt)
            })
        }
    }.toString()

    private fun write(file: File, text: String): Boolean {
        val tmp = File(dir, "${file.name}.tmp")
        return runCatching {
            tmp.writeText(text)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.getOrDefault(false)
    }

    private fun writePurpose(name: String, purpose: String) {
        val file = purposeFile(name) ?: return
        val clean = purpose.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(MAX_PURPOSE)
        runCatching { if (clean.isEmpty()) file.delete() else file.writeText(clean) }
    }

    private fun purposeOf(cleanName: String): String =
        File(dir, encode(cleanName) + PURPOSE).takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            .orEmpty()

    private fun textFile(name: String): File? = cleanName(name)?.let { File(dir, encode(it) + TEXT) }

    private fun purposeFile(name: String): File? = cleanName(name)?.let { File(dir, encode(it) + PURPOSE) }

    private fun encode(cleanName: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(cleanName.toByteArray(Charsets.UTF_8))

    private fun decode(encoded: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()?.let { cleanName(it) }

    companion object {
        private const val TEXT = ".md"
        private const val PURPOSE = ".purpose"
        const val MAX_CHARS = 60_000
        const val MAX_NAME = 40
        const val MAX_PURPOSE = 120

        /** A usable card name, or null: one line, not blank, not too long. */
        fun cleanName(raw: String): String? {
            val name = raw.trim()
            if (name.isEmpty() || name.length > MAX_NAME) return null
            if (name.any { it.isISOControl() }) return null
            return name
        }
    }
}
