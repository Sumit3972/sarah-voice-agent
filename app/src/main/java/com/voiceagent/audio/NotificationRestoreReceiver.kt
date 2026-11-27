package com.voiceagent.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.voiceagent.config.PipelineConfig

/**
 * Re-attaches the ongoing foreground notification immediately if swiped away on Android 14+,
 * preventing the OS from downgrading background priority.
 */
class NotificationRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(PipelineConfig.LOG_TAG, "[NotificationRestore] Restoring persistent notification")
        runCatching { VoiceCaptureForegroundService.start(context) }
    }
}
