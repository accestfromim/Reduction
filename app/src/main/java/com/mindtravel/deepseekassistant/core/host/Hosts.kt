package com.mindtravel.deepseekassistant.core.host

import java.io.File

interface PermissionHost {
    fun hasMicPermission(): Boolean
    fun requestMicPermission(callback: (Boolean) -> Unit)
    fun requestBluetoothPermission(callback: (Boolean) -> Unit = {})
}

interface AudioOutputHost {
    fun hasHeadsetOutput(): Boolean
}

interface UpdateHost {
    fun cacheDirectory(): File
    fun installedApk(): File
    fun installApk(file: File)
    fun showUpdateReady(version: String, apk: File)
    fun showAssetsReady(version: String)
}

interface ToolApprovalHost {
    fun requestToolApproval(title: String, message: String, callback: (Boolean) -> Unit)
}
