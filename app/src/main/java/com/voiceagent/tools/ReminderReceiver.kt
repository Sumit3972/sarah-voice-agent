package com.voiceagent.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voiceagent.MainActivity
import com.voiceagent.R
import com.voiceagent.config.PipelineConfig
import com.voiceagent.orchestrator.SarahRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_VOICE_REMINDER = "com.voiceagent.ACTION_VOICE_REMINDER"
        const val ACTION_REMINDER_DONE = "com.voiceagent.ACTION_REMINDER_DONE"
        const val ACTION_REMINDER_SNOOZE = "com.voiceagent.ACTION_REMINDER_SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_SPOKEN_MESSAGE = "spoken_message"
        private const val REMINDER_CHANNEL_ID = "sarah_reminders"

        private fun notifIdFor(id: Long): Int = 20000 + (id.toInt() and 0x7FFF)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, -1L)
        val message = intent.getStringExtra(EXTRA_SPOKEN_MESSAGE).orEmpty().trim()
            .ifBlank { "This is your scheduled reminder." }
        val action = intent.action.orEmpty()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notifId = notifIdFor(id)
                val runtime = SarahRuntime.get(context)

                when (action) {
                    ACTION_REMINDER_DONE -> {
                        nm.cancel(notifId)
                        if (id > 0L) runtime.dao.updateReminderStatus(id, "done")
                        Log.i(PipelineConfig.LOG_TAG, "[ReminderReceiver] Marked reminder #$id done")
                    }
                    ACTION_REMINDER_SNOOZE -> {
                        nm.cancel(notifId)
                        scheduleSnooze(context, id, message)
                        if (id > 0L) runtime.dao.updateReminderStatus(id, "pending")
                        Log.i(PipelineConfig.LOG_TAG, "[ReminderReceiver] Snoozed reminder #$id for 15 minutes")
                    }
                    else -> {
                        if (id > 0L) runtime.dao.updateReminderStatus(id, "fired")
                        showReminderNotification(context, id, message)
                        Log.i(PipelineConfig.LOG_TAG, "[ReminderReceiver] Speaking voice reminder #$id: $message")
                        runtime.stateMachine.speakProactiveMessage("Quick reminder: $message")
                    }
                }
            } catch (e: Exception) {
                Log.e(PipelineConfig.LOG_TAG, "[ReminderReceiver] Error: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun scheduleSnooze(context: Context, id: Long, message: String) {
        val triggerAtMs = System.currentTimeMillis() + 15 * 60_000L
        val pi = PendingIntent.getBroadcast(
            context,
            ((id.toInt() and 0x7FFF) + 5000),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_VOICE_REMINDER
                putExtra(EXTRA_REMINDER_ID, id)
                putExtra(EXTRA_SPOKEN_MESSAGE, message)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            alarm.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    private fun showReminderNotification(context: Context, id: Long, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                REMINDER_CHANNEL_ID,
                "Sarah Voice Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Scheduled voice reminders and follow-ups from Sarah."
            }
            nm.createNotificationChannel(ch)
        }

        val notifId = notifIdFor(id)
        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val donePi = PendingIntent.getBroadcast(
            context,
            notifId + 1000,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_REMINDER_DONE
                putExtra(EXTRA_REMINDER_ID, id)
                putExtra(EXTRA_SPOKEN_MESSAGE, message)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozePi = PendingIntent.getBroadcast(
            context,
            notifId + 2000,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_REMINDER_SNOOZE
                putExtra(EXTRA_REMINDER_ID, id)
                putExtra(EXTRA_SPOKEN_MESSAGE, message)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sarah_notif)
            .setColor(0xFFC96442.toInt())
            .setSubText("Sarah Reminder")
            .setContentTitle(message)
            .setContentText("Scheduled voice reminder · Tap Mark done or Snooze")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Mark done", donePi)
            .addAction(0, "Snooze 15m", snoozePi)
            .build()
        nm.notify(notifId, notif)
    }
}
