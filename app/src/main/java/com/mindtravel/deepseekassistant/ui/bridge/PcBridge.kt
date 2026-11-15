package com.mindtravel.deepseekassistant.ui.bridge

import com.mindtravel.deepseekassistant.feature.pc.ui.PcController
import com.mindtravel.deepseekassistant.feature.pc.ui.UpdateController

class PcBridge(
    private val pc: PcController,
    private val update: UpdateController
) {
    fun info(id: String) = pc.info(id)
    fun exec(id: String, command: String) = pc.exec(id, command)
    fun checkUpdate(id: String) = update.check(id)
}
