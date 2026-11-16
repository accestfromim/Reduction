package com.mindtravel.reduction.ui.bridge

import com.mindtravel.reduction.feature.update.ui.UpdateController

class UpdateBridge(private val update: UpdateController) {
    fun check() = update.check()
    fun install() = update.install()
    fun state(): String = update.state()
    fun version(): String = update.version()
}
