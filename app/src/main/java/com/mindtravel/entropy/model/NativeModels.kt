package com.mindtravel.entropy.model

import android.os.Build

/** The on-device speech models ship as arm64 native code only. */
object NativeModels {

    /**
     * False on a device whose own architecture is not arm64. Such a device can sometimes
     * run arm64 code through translation, but the model runtime does not survive it, so
     * the models are not loaded there and their fallbacks are used.
     */
    val available: Boolean by lazy { Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" }
}
