package com.plaincast.app

import android.app.Application
import com.plaincast.app.diagnostics.DiagnosticsRepository
import com.plaincast.app.rtc.RtcEngine

class PlainCastApplication : Application() {
    lateinit var diagnostics: DiagnosticsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        diagnostics = DiagnosticsRepository()
        RtcEngine.initialize(this, diagnostics)
    }
}
