package com.reddoorz.rdpulse

import android.app.Application
import com.reddoorz.rdpulse.BuildConfig

/**
 * RdPulse — debug performance overlay.
 *
 * Call [install] from Application.onCreate(). It is a no-op in non-debug builds.
 */
object RdPulse {

    /**
     * Installs the performance overlay. Must be called from [Application.onCreate].
     * Does nothing if the build is not DEBUG.
     */
    fun install(app: Application) {
        if (!BuildConfig.DEBUG) return
        PulseOverlay.install(app)
    }
}
