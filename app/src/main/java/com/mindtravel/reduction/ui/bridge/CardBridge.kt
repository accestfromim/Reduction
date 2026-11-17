package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.feature.article.ArticleBuilder
import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.ui.overlay.CardOverlay

class CardBridge(
    private val cards: CardRepository,
    private val overlay: CardOverlay,
    /** Takes the user to the system page where drawing over other apps is switched on. */
    private val askToAllowOverlay: () -> Unit,
    private val articles: ArticleBuilder
) {
    /** The article template and stylesheet in use, and why the user's own could not be used, if so. */
    fun articleTemplate(): String = org.json.JSONObject().put("template", articles.template()).put("style", articles.style())
        .put("problem", articles.templateProblem() ?: org.json.JSONObject.NULL).toString()

    fun list(): String = cards.directoryJson()
    fun read(name: String): String = cards.read(name).orEmpty()
    fun purpose(name: String): String = cards.purpose(name)
    fun save(name: String, purpose: String, content: String): Boolean = cards.save(name, content, purpose)
    fun delete(name: String): Boolean = cards.delete(name)
    fun rename(from: String, to: String): Boolean = cards.rename(from, to)

    /** The user has read the card through and locks it as a prompt, or takes the lock off again. */
    fun setLocked(name: String, locked: Boolean): Boolean = cards.setLocked(name, locked)

    /** Keeps a picture or a recording the user picked; returns its id, or blank when it is not kept. */
    fun saveMedia(base64: String, extension: String): String =
        runCatching { cards.saveMedia(java.util.Base64.getDecoder().decode(base64), extension) }.getOrNull().orEmpty()

    /** Keeps one page of a PDF as the page drew it. */
    fun savePage(pdfId: String, page: Int, base64: String): Boolean =
        runCatching { cards.savePage(pdfId, page, java.util.Base64.getDecoder().decode(base64)) }.getOrDefault(false)

    fun pages(pdfId: String): Int = cards.pages(pdfId).size

    /** The card that floats over other apps, or blank. */
    fun floating(): String = overlay.pinned()

    /** Floats [name]; a blank name puts the floating card away. False: the user has to allow it first, and was shown where. */
    fun float(name: String): Boolean {
        if (name.isBlank()) {
            overlay.unpin()
            return true
        }
        val shown = overlay.pin(name)
        if (!shown) askToAllowOverlay()
        return shown
    }
}
