package com.mindtravel.deepseekassistant.feature.pc.model

import org.json.JSONObject
import java.io.File

interface PcGateway {
    fun info(base: String, token: String): JSONObject
    fun exec(base: String, token: String, command: String): String
    fun updateManifest(base: String, token: String): JSONObject
    fun downloadApk(base: String, token: String, destination: File): File
}
