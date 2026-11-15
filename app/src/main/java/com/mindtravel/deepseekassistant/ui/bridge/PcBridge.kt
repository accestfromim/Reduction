package com.mindtravel.deepseekassistant.ui.bridge

import com.mindtravel.deepseekassistant.feature.pc.ui.PcController

class PcBridge(private val pc: PcController) {
    fun info(id: String) = pc.info(id)
    fun exec(id: String, command: String) = pc.exec(id, command)
    fun publicKey(): String = pc.publicKey()

    fun close() = pc.close()
}
