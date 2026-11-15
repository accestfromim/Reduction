package com.mindtravel.entropy.ui.bridge

import com.mindtravel.entropy.feature.chat.data.CardRepository

class CardBridge(private val cards: CardRepository) {
    fun list(): String = cards.directoryJson()
    fun read(name: String): String = cards.read(name).orEmpty()
    fun purpose(name: String): String = cards.purpose(name)
    fun save(name: String, purpose: String, content: String): Boolean = cards.save(name, content, purpose)
    fun delete(name: String): Boolean = cards.delete(name)
}
