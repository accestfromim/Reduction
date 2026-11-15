package com.mindtravel.deepseekassistant.feature.pc.model

import java.io.File

interface PcGateway {
    fun info(base: String, token: String): PcInfo
    fun exec(base: String, token: String, command: String): String
    fun updateManifest(base: String, token: String): UpdateManifest
    fun downloadApk(base: String, token: String, destination: File): File
}
