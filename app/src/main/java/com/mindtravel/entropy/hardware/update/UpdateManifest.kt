package com.mindtravel.entropy.hardware.update

data class UpdatePatch(
    val fromVersionCode: Int,
    val apk: String,
    val sha256: String
)

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String,
    val assetsVersion: String = "",
    val assetsZip: String = "",
    val assetsSha256: String = "",
    val assetsMinVersionCode: Int = 0,
    val patch: UpdatePatch? = null
)
