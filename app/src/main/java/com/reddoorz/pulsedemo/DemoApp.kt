package com.reddoorz.pulsedemo

import android.app.Application
import com.reddoorz.rdpulse.RdPulse

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RdPulse.install(this)
    }
}
