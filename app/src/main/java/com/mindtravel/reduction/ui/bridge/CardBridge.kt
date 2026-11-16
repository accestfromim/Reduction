package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.feature.chat.data.CardRepository
import com.mindtravel.reduction.ui.overlay.CardOverlay

class CardBridge(
    private val cards: CardRepository,
    private val overlay: CardOverlay,
    /** Takes the user to the system page where drawing over other apps is switched on. */
    private val askToAllowOverlay: () -> Unit
) {
    fun list(): String = cards.directoryJson()
    fun read(name: String): String = cards.read(name).orEmpty()
    fun purpose(name: String): String = cards.purpose(name)
    fun save(name: String, purpose: String, content: String): Boolean = cards.save(name, content, purpose)
    fun delete(name: String): Boolean = cards.delete(name)

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
