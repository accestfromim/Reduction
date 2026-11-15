package com.mindtravel.deepseekassistant.feature.update.model

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String
)
