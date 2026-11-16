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
 *
 * A name may contain "/": what comes before the last one is the folder the card is shown
 * in. Folders are nothing but that — there is nothing to create or delete.
 *
 * A card may show pictures and play sound. Those are kept beside the cards as media files,
 * each under a short id, and the card's text refers to one as `![说明](media:id)`. A media
 * file that no card refers to any more is removed.
 */
class CardRepository(private val dir: File) {

    class Info(val name: String, val purpose: String, val chars: Int, val updatedAt: Long)

    /** Told the name of every card that has just been written or deleted. */
    @Volatile
    var onChange: (name: String) -> Unit = {}

    /**
     * Cards written by the program alone. Neither the assistant nor the screen can change
     * or delete them, which is what makes their content worth trusting.
     */
    private val programCards = HashSet<String>()

    @Synchronized
    fun isProgramCard(name: String): Boolean = cleanName(name) in programCards

    /**
     * A prompt is a card that has been read through and locked by the user: from then on its
     * text is exactly what was approved. Nobody can change, rename or delete it — not the
     * assistant, not the screen — until the user unlocks it again. Only a locked card can be
     * handed to an agent as its task.
     */
    @Synchronized
    fun isLocked(name: String): Boolean = lockFile(name)?.isFile == true

    /** Locks or unlocks a card. This is the user's act alone: nothing the assistant can reach calls it. */
    @Synchronized
    fun setLocked(name: String, locked: Boolean): Boolean {
        val clean = cleanName(name) ?: return false
        if (isProgramCard(clean) || read(clean) == null) return false
        val file = lockFile(clean) ?: return false
        val done = if (locked) runCatching { file.writeText(""); true }.getOrDefault(false) else (!file.exists() || file.delete())
        if (done) runCatching { onChange(clean) }
        return done
    }

    private fun lockFile(name: String): File? = cleanName(name)?.let { File(dir, encode(it) + LOCK) }

    /** Writes a card that only the program may write. */
    @Synchronized
    fun writeProgramCard(name: String, content: String, purpose: String): Boolean {
        val clean = cleanName(name) ?: return false
        programCards += clean
        return save(clean, content, purpose, byProgram = true)
    }

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
    fun save(name: String, content: String, purpose: String? = null): Boolean = save(name, content, purpose, byProgram = false)

    private fun save(name: String, content: String, purpose: String?, byProgram: Boolean): Boolean {
        val file = textFile(name) ?: return false
        if (!byProgram && (isProgramCard(name) || isLocked(name))) return false
        dir.mkdirs()
        if (purpose != null) writePurpose(name, purpose)
        return write(file, content.take(MAX_CHARS)).also {
            if (it) {
                runCatching { sweepMedia() }
                runCatching { onChange(name.trim()) }
            }
        }
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
            if (isProgramCard(op.name)) throw CardPatch.Invalid("卡片“${op.name}”由程序写入，不能修改；它的内容是原始记录")
            if (isLocked(op.name)) throw CardPatch.Invalid("卡片“${op.name}”是用户审核后锁定的 prompt，不能修改、改名或删除；只有用户能在界面上解锁")
            if (op is CardPatch.Move && isLocked(op.to)) throw CardPatch.Invalid("“${op.to}”是一张锁定的 prompt 的名称，不能占用")
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
                is CardPatch.Move -> {
                    val text = currentText(op.name)
                        ?: throw CardPatch.Invalid("没有名为“${op.name}”的卡片可改名")
                    if (isProgramCard(op.to)) throw CardPatch.Invalid("“${op.to}”是程序写入的卡片的名称，不能占用")
                    if (currentText(op.to) != null) throw CardPatch.Invalid("已经有一张叫“${op.to}”的卡片")
                    val purpose = writes[op.name]?.second ?: purposeOf(op.name)
                    writes -= op.name
                    deletes += op.name
                    deletes -= op.to
                    writes[op.to] = text to purpose
                    summary += "“${op.name}”改名为“${op.to}”"
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

    /** Gives a card another name, which is also how it is put into a folder. False when that cannot be done. */
    @Synchronized
    fun rename(from: String, to: String): Boolean {
        val source = cleanName(from) ?: return false
        val target = cleanName(to) ?: return false
        if (source == target) return true
        if (isProgramCard(source) || isProgramCard(target) || isLocked(source) || read(target) != null) return false
        val text = read(source) ?: return false
        if (!save(target, text, purposeOf(source))) return false
        return delete(source)
    }

    // ── pictures and sound ──────────────────────────────────────────────────

    private val mediaDir: File get() = File(dir, "media")

    /** Keeps [bytes] as a media file and returns its id, or null when it is not a kind or size that is kept. */
    @Synchronized
    fun saveMedia(bytes: ByteArray, extension: String): String? {
        val kind = extension.lowercase().trim('.')
        if (kind !in MEDIA_KINDS || bytes.isEmpty() || bytes.size > MAX_MEDIA_BYTES) return null
        mediaDir.mkdirs()
        val id = "m" + java.util.UUID.randomUUID().toString().replace("-", "").take(12) + "." + kind
        return runCatching { File(mediaDir, id).writeBytes(bytes); id }.getOrNull()
    }

    /** The file behind a media id; null when there is none. */
    fun mediaFile(id: String): File? = id.takeIf { MEDIA_ID.matches(it) }?.let { File(mediaDir, it) }?.takeIf { it.isFile }

    /** Removes media no card refers to. What was added in the last hour is left: its card may not be saved yet. */
    private fun sweepMedia() {
        val files = mediaDir.listFiles() ?: return
        if (files.isEmpty()) return
        val used = HashSet<String>()
        for (file in dir.listFiles().orEmpty()) {
            if (file.isFile && file.name.endsWith(TEXT)) {
                MEDIA_REF.findAll(runCatching { file.readText() }.getOrDefault("")).forEach { used += it.groupValues[1] }
            }
        }
        val cutoff = System.currentTimeMillis() - MEDIA_GRACE_MS
        for (file in files) if (file.name !in used && file.lastModified() < cutoff) file.delete()
    }

    @Synchronized
    fun delete(name: String): Boolean {
        if (isProgramCard(name) || isLocked(name)) return false
        purposeFile(name)?.delete()
        return (textFile(name)?.takeIf { it.isFile }?.delete() ?: false).also {
            if (it) {
                runCatching { sweepMedia() }
                runCatching { onChange(name.trim()) }
            }
        }
    }

    fun directoryJson(): String = JSONArray().apply {
        for (card in list()) {
            put(JSONObject().apply {
                put("name", card.name)
                put("purpose", card.purpose)
                put("chars", card.chars)
                put("updatedAt", card.updatedAt)
                put("locked", isProgramCard(card.name))
                put("prompt", isLocked(card.name))
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
        private const val LOCK = ".lock"
        const val MAX_CHARS = 60_000
        const val MAX_NAME = 60
        const val MAX_MEDIA_BYTES = 20 * 1024 * 1024
        private const val MEDIA_GRACE_MS = 3_600_000L
        val MEDIA_KINDS = setOf("jpg", "jpeg", "png", "webp", "gif", "m4a", "mp3", "wav", "ogg", "aac", "webm", "flac")
        private val MEDIA_ID = Regex("^m[0-9a-f]{12}\\.[a-z0-9]{2,5}$")
        private val MEDIA_REF = Regex("media:(m[0-9a-f]{12}\\.[a-z0-9]{2,5})")
        const val MAX_PURPOSE = 120

        /** A usable card name, or null: one line, not blank, not too long. */
        fun cleanName(raw: String): String? {
            val name = raw.trim()
            if (name.isEmpty() || name.length > MAX_NAME) return null
            if (name.any { it.isISOControl() }) return null
            // Folder and name are told apart by "/": tidied, so that the same place is always written the same way.
            val parts = name.split('/').map { it.trim() }
            if (parts.any { it.isEmpty() }) return null
            return parts.joinToString("/")
        }
    }
}
