package com.voiceagent.audio

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.voiceagent.MainActivity
import com.voiceagent.R
import com.voiceagent.config.PipelineConfig
import com.voiceagent.orchestrator.ConversationPhase
import com.voiceagent.orchestrator.SarahRuntime
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class VoiceCaptureForegroundService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "sarah_always_on_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START_OR_RESTORE = "com.voiceagent.ACTION_START_OR_RESTORE"
        const val ACTION_TOGGLE_MUTE = "com.voiceagent.ACTION_TOGGLE_MUTE"
        const val ACTION_TRIGGER_WAKE = "com.voiceagent.ACTION_TRIGGER_WAKE"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceCaptureForegroundService::class.java).apply {
                action = ACTION_START_OR_RESTORE
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.w(PipelineConfig.LOG_TAG, "[Service] startForegroundService skipped/deferred: ${it.message}")
            }
        }

        fun startIfPermitted(context: Context) {
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasMic) return
            if (!isServiceRunning) {
                start(context)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var runtime: SarahRuntime

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        runtime = SarahRuntime.get(this)
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SarahVoice::AudioEngineLock").apply {
            setReferenceCounted(false)
        }

        lifecycleScope.launch {
            runtime.stateMachine.state
                .map { Triple(it.phase, it.micMuted, it.activeModelBadge) }
                .distinctUntilChanged()
                .collect { (phase, micMuted, modelBadge) ->
                    syncWakeLock(phase, micMuted)
                    updateNotification(phase, micMuted, modelBadge)
                }
        }
    }

    private fun syncWakeLock(phase: ConversationPhase, micMuted: Boolean) {
        val wl = wakeLock ?: return
        val shouldHold = !micMuted && phase != ConversationPhase.STOPPED
        if (shouldHold) {
            if (!wl.isHeld || phase == ConversationPhase.LISTENING || phase == ConversationPhase.SPEAKING) {
                runCatching { wl.acquire(60 * 60 * 1000L) }
            }
        } else {
            if (wl.isHeld) {
                runCatching { wl.release() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureForegroundNotification()

        when (intent?.action) {
            ACTION_TOGGLE_MUTE -> runtime.stateMachine.toggleMute()
            ACTION_TRIGGER_WAKE -> runtime.stateMachine.triggerManualWake()
            else -> {
                if (hasMicPermission()) {
                    runtime.stateMachine.startAudioLoop()
                } else {
                    Log.w(PipelineConfig.LOG_TAG, "Waiting for RECORD_AUDIO permission before starting mic loop")
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(PipelineConfig.LOG_TAG, "[Service] App swiped from Recents — keeping Sarah alive headlessly!")
        ensureForegroundNotification()
        scheduleRecoveryBurst()
    }

    private fun scheduleRecoveryBurst() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 1. Direct ForegroundService PendingIntent restart in case UI task swipe tore down the service
        val fgsPi = PendingIntent.getForegroundService(
            applicationContext,
            9900,
            Intent(applicationContext, VoiceCaptureForegroundService::class.java).apply {
                action = ACTION_START_OR_RESTORE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        scheduleExactOrWindow(alarm, SystemClock.elapsedRealtime() + 500L, fgsPi)

        // 2. BroadcastReceiver fail-safes at +1.4s and +3.5s if an OEM ROM kills the process shortly after swipe
        for ((reqCode, delayMs) in listOf(9901 to 1400L, 9902 to 3500L)) {
            val pi = PendingIntent.getBroadcast(
                applicationContext,
                reqCode,
                Intent(applicationContext, ServiceRecoveryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            scheduleExactOrWindow(alarm, SystemClock.elapsedRealtime() + delayMs, pi)
        }
    }

    private fun scheduleExactOrWindow(alarm: AlarmManager, triggerAtElapsedMs: Long, pi: PendingIntent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtElapsedMs, pi)
            }
        }
    }

    override fun onDestroy() {
        isServiceRunning = false
        val currentPhase = runCatching { runtime.stateMachine.state.value.phase }.getOrNull()
        if (currentPhase != ConversationPhase.STOPPED && hasMicPermission()) {
            scheduleRecoveryBurst()
        }
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureForegroundNotification() {
        val snap = runtime.stateMachine.state.value
        val notif = buildNotification(snap.phase, snap.micMuted, snap.activeModelBadge)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, fgsType)
        }.onFailure {
            Log.e(PipelineConfig.LOG_TAG, "startForeground failed: ${it.message}")
        }
    }

    private fun updateNotification(phase: ConversationPhase, muted: Boolean, modelBadge: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(phase, muted, modelBadge))
    }

    private fun buildNotification(
        phase: ConversationPhase,
        muted: Boolean,
        modelBadge: String
    ): Notification {
        val title = if (muted) "Sarah · Mic paused" else when (phase) {
            ConversationPhase.STOPPED -> "Sarah is off"
            ConversationPhase.IDLE_STANDBY -> "Sarah is listening for her name"
            ConversationPhase.WAKE_CHECK -> "Sarah is waking up..."
            ConversationPhase.LISTENING, ConversationPhase.SPEAKING -> "Sarah is listening..."
            ConversationPhase.PAUSE_WAIT -> "Understanding your request..."
            ConversationPhase.THINKING -> "Sarah is working on your request..."
            ConversationPhase.SPEAKING_REPLY -> "Sarah is speaking"
        }

        val subtitle = if (muted) {
            "Tap Resume mic to turn hands-free back on"
        } else {
            "Hands-free active · Room noise filtered"
        }

        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeIntent = PendingIntent.getService(
            this,
            103,
            Intent(this, VoiceCaptureForegroundService::class.java).apply { action = ACTION_TRIGGER_WAKE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, VoiceCaptureForegroundService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreOnSwipe = PendingIntent.getBroadcast(
            this,
            102,
            Intent(this, NotificationRestoreReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sarah_notif)
            .setColor(0xFFC96442.toInt())
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openIntent)
            .setDeleteIntent(restoreOnSwipe)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Speak now", wakeIntent)
            .addAction(0, if (muted) "Resume mic" else "Pause mic", muteIntent)
            .build()

        notif.flags = notif.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return notif
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sarah Always-On Voice Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Sarah active in the background so she wakes when you say Sarah."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }
}
