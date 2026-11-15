package com.mindtravel.deepseekassistant.ui.bridge

import com.mindtravel.deepseekassistant.feature.update.ui.UpdateController

class UpdateBridge(private val update: UpdateController) {
    fun check() = update.check()
}
