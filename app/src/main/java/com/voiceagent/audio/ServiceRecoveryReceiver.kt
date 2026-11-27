package com.voiceagent.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.voiceagent.config.PipelineConfig
import com.voiceagent.orchestrator.SarahRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ServiceRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    SarahRuntime.get(context).autonomousEngine.restoreScheduledAlarmsAndLoops()
                }
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    Log.i(PipelineConfig.LOG_TAG, "[ServiceRecovery] Ensuring VoiceCaptureForegroundService is active")
                    VoiceCaptureForegroundService.startIfPermitted(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
