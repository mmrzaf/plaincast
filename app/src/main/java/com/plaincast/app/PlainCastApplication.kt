package com.plaincast.app

import android.app.Application
import com.plaincast.app.rtc.RtcEngine

class PlainCastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RtcEngine.initialize(this)
    }
}
