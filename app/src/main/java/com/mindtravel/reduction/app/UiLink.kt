package com.mindtravel.reduction.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.host.PermissionHost
import com.mindtravel.reduction.core.host.ToolApprovalHost

/**
 * The core's view of the screen, which may or may not be there. While a UI is attached,
 * events reach it and it is asked for permissions and approvals. While none is, events
 * are dropped — the UI reads the current state when it attaches — and anything that
 * needs a person to tap is refused.
 */
class UiLink(private val context: Context) : EventSink, PermissionHost, ToolApprovalHost {

    private class Attached(val events: EventSink, val permissions: PermissionHost, val approvals: ToolApprovalHost)

    @Volatile
    private var attached: Attached? = null

    val isAttached: Boolean get() = attached != null

    fun attach(events: EventSink, permissions: PermissionHost, approvals: ToolApprovalHost) {
        attached = Attached(events, permissions, approvals)
        Logs.add("UI attached")
    }

    /** Detaches [events] if it is the one attached; a newer UI is left alone. */
    fun detach(events: EventSink) {
        if (attached?.events === events) {
            attached = null
            Logs.add("UI detached")
        }
    }

    override fun emit(function: String, vararg args: Any?) {
        attached?.events?.emit(function, *args)
    }

    override fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun requestMicPermission(callback: (Boolean) -> Unit) {
        val ui = attached
        if (ui != null) ui.permissions.requestMicPermission(callback) else callback(hasMicPermission())
    }

    override fun requestBluetoothPermission(callback: (Boolean) -> Unit) {
        val ui = attached
        if (ui != null) ui.permissions.requestBluetoothPermission(callback) else callback(false)
    }

    override fun requestToolApproval(title: String, message: String, callback: (Boolean) -> Unit) {
        val ui = attached
        if (ui == null) {
            Logs.add("Approval refused: no screen to ask on ($title)")
            callback(false)
        } else {
            ui.approvals.requestToolApproval(title, message, callback)
        }
    }
}
