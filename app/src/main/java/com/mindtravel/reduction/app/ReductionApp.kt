package com.mindtravel.reduction.app

import android.app.Application

/**
 * Creates the core as soon as the process exists, whatever brought it up: the user
 * opening the app, the wake word service starting, or the system restarting that
 * service. So the wake word always has something to answer it.
 */
class ReductionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCore.get(this)
    }
}
