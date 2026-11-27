package com.voiceagent

import android.app.Application
import com.voiceagent.orchestrator.SarahRuntime

class SarahApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SarahRuntime.get(this)
    }
}
