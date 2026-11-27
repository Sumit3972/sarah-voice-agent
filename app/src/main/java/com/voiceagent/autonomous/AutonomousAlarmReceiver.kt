package com.voiceagent.autonomous

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.orchestrator.SarahRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AutonomousAlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_AUTONOMOUS_TICK = "com.voiceagent.ACTION_AUTONOMOUS_TICK"
        const val ACTION_STOP_ALL_TASKS = "com.voiceagent.ACTION_STOP_ALL_TASKS"
        const val ACTION_PAUSE_ALL_TASKS = "com.voiceagent.ACTION_PAUSE_ALL_TASKS"
        const val ACTION_STOP_TASK = "com.voiceagent.ACTION_STOP_TASK"
        const val EXTRA_TASK_ID = "task_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val action = intent.action.orEmpty()
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SarahVoice::AutonomousAlarm").apply {
            setReferenceCounted(false)
            runCatching { acquire(30_000L) }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val runtime = SarahRuntime.get(context)
                when (action) {
                    ACTION_STOP_ALL_TASKS -> {
                        val count = runtime.autonomousEngine.stopAllTasks()
                        Log.i(PipelineConfig.LOG_TAG, "[AutonomousReceiver] Stopped all ($count) tasks")
                    }
                    ACTION_PAUSE_ALL_TASKS -> {
                        val count = runtime.autonomousEngine.pauseAllTasks()
                        Log.i(PipelineConfig.LOG_TAG, "[AutonomousReceiver] Paused all ($count) tasks")
                    }
                    ACTION_STOP_TASK -> {
                        if (taskId > 0L) {
                            runtime.autonomousEngine.stopTask(taskId)
                            Log.i(PipelineConfig.LOG_TAG, "[AutonomousReceiver] Stopped task #$taskId")
                        }
                    }
                    ACTION_AUTONOMOUS_TICK -> {
                        if (taskId > 0L) {
                            Log.i(PipelineConfig.LOG_TAG, "[AutonomousReceiver] Alarm fired for task #$taskId")
                            runtime.autonomousEngine.onAlarmFired(taskId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(PipelineConfig.LOG_TAG, "[AutonomousReceiver] Error: ${e.message}", e)
            } finally {
                if (wakeLock.isHeld) {
                    runCatching { wakeLock.release() }
                }
                pending.finish()
            }
        }
    }
}
