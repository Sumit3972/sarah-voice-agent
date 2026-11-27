package com.voiceagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.voiceagent.config.asDoubleOrNull
import com.voiceagent.config.asIntOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.memory.MemoryDao
import com.voiceagent.memory.ScheduledReminderEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ReminderScheduler {
    fun scheduleAlarm(context: Context, id: Long, triggerAtMs: Long, spokenMessage: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_VOICE_REMINDER
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id)
            putExtra(ReminderReceiver.EXTRA_SPOKEN_MESSAGE, spokenMessage)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancelAlarm(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_VOICE_REMINDER
        }
        val pi = PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) am.cancel(pi)
    }
}

class PersonalSchedulerTool(
    private val appContext: Context,
    private val dao: MemoryDao
) {
    suspend fun execute(args: JsonObject): String {
        val action = args["action"].asStrOrNull()?.trim()?.lowercase().orEmpty()
        val query = args["query"].asStrOrNull()?.trim().orEmpty()
        val title = args["title"].asStrOrNull()?.trim().orEmpty()
        val spokenMessage = args["spoken_message"].asStrOrNull()?.trim().orEmpty()
        val startIso = args["start_iso"].asStrOrNull()?.trim().orEmpty()
        val endIso = args["end_iso"].asStrOrNull()?.trim().orEmpty()
        val inMinutes = args["in_minutes"].asDoubleOrNull()
        val reminderId = args["reminder_id"].asIntOrNull()?.toLong()

        return when (action) {
            "find_contact" -> findContact(query)
            "list_calendar" -> listCalendarEvents()
            "create_event" -> createCalendarEvent(title.ifBlank { spokenMessage }, startIso, endIso, inMinutes)
            "set_voice_reminder" -> setVoiceReminder(spokenMessage.ifBlank { title }, inMinutes, startIso)
            "list_reminders" -> listReminders()
            "cancel_reminder" -> cancelReminder(reminderId, query)
            else -> """{"ok":false,"error":"Unknown personal_scheduler action='$action'. Use find_contact|list_calendar|create_event|set_voice_reminder|list_reminders|cancel_reminder."}"""
        }
    }

    private suspend fun findContact(query: String): String {
        if (query.isBlank()) return """{"ok":false,"error":"Contact query name is required"}"""
        val results = mutableListOf<JsonObject>()

        val aliasFacts = dao.searchFactsLike(query, category = "contact_alias", limit = 5)
        for (f in aliasFacts) {
            results += buildJsonObject {
                put("source", "memory_fact")
                put("name", f.factKey)
                put("detail", f.factValue)
            }
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                null
            )?.use { c ->
                while (c.moveToNext() && results.size < 10) {
                    val name = c.getString(0).orEmpty()
                    val num = c.getString(1).orEmpty()
                    results += buildJsonObject {
                        put("source", "contacts")
                        put("name", name)
                        put("phone", num)
                    }
                }
            }
        }

        return buildJsonObject {
            put("ok", true)
            put("matches", buildJsonArray { results.forEach { add(it) } })
        }.toString()
    }

    private fun listCalendarEvents(): String {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return """{"ok":false,"error":"READ_CALENDAR permission not granted yet"}"""
        }
        val now = System.currentTimeMillis()
        val endRange = now + 7 * 24 * 3600_000L
        val events = mutableListOf<JsonObject>()
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)

        appContext.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.EVENT_LOCATION
            ),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(now.toString(), endRange.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { c ->
            while (c.moveToNext() && events.size < 12) {
                val title = c.getString(0).orEmpty()
                val startMs = c.getLong(1)
                val loc = c.getString(2).orEmpty()
                events += buildJsonObject {
                    put("title", title)
                    put("starts_at", fmt.format(Date(startMs)))
                    if (loc.isNotBlank()) put("location", loc)
                }
            }
        }

        return buildJsonObject {
            put("ok", true)
            put("events", buildJsonArray { events.forEach { add(it) } })
        }.toString()
    }

    @SuppressLint("MissingPermission")
    private fun createCalendarEvent(title: String, startIso: String, endIso: String, inMinutes: Double?): String {
        if (title.isBlank()) return """{"ok":false,"error":"Event title is required"}"""
        val startMs = resolveTriggerTimeMs(inMinutes, startIso)
            ?: (System.currentTimeMillis() + 3600_000L)
        val endMs = resolveTriggerTimeMs(null, endIso) ?: (startMs + 3600_000L)

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            return """{"ok":true,"message":"Opened Calendar pre-filled with '$title'"}"""
        }

        var calendarId = 1L
        appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) calendarId = c.getLong(0)
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        return buildJsonObject {
            put("ok", uri != null)
            put("title", title)
            put("starts_at", fmt.format(Date(startMs)))
        }.toString()
    }

    private suspend fun setVoiceReminder(message: String, inMinutes: Double?, startIso: String): String {
        if (message.isBlank()) return """{"ok":false,"error":"spoken_message for reminder is required"}"""
        val triggerAt = resolveTriggerTimeMs(inMinutes, startIso)
            ?: return """{"ok":false,"error":"Provide in_minutes (e.g. 15) or start_iso timestamp"}"""

        val id = dao.insertReminder(
            ScheduledReminderEntity(
                triggerAtMs = triggerAt,
                spokenMessage = message,
                status = "pending",
                createdAt = System.currentTimeMillis()
            )
        )

        ReminderScheduler.scheduleAlarm(appContext, id, triggerAt, message)

        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        return buildJsonObject {
            put("ok", true)
            put("reminder_id", id)
            put("spoken_message", message)
            put("fires_at", fmt.format(Date(triggerAt)))
        }.toString()
    }

    private suspend fun listReminders(): String {
        val list = dao.getPendingReminders()
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        return buildJsonObject {
            put("ok", true)
            put("reminders", buildJsonArray {
                list.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("spoken_message", r.spokenMessage)
                        put("fires_at", fmt.format(Date(r.triggerAtMs)))
                    })
                }
            })
        }.toString()
    }

    private suspend fun cancelReminder(id: Long?, query: String): String {
        val target = when {
            id != null -> dao.getReminderById(id)
            query.isNotBlank() -> dao.getPendingReminders().firstOrNull {
                it.spokenMessage.contains(query, ignoreCase = true)
            }
            else -> dao.getPendingReminders().firstOrNull()
        } ?: return """{"ok":false,"error":"No pending reminder matched"}"""

        dao.updateReminderStatus(target.id, "cancelled")
        ReminderScheduler.cancelAlarm(appContext, target.id)

        return buildJsonObject {
            put("ok", true)
            put("cancelled_id", target.id)
            put("message", "Cancelled reminder '${target.spokenMessage}'")
        }.toString()
    }

    private fun resolveTriggerTimeMs(inMinutes: Double?, startIso: String): Long? {
        if (inMinutes != null && inMinutes > 0.0) {
            return System.currentTimeMillis() + (inMinutes * 60_000.0).toLong()
        }
        if (startIso.isNotBlank()) {
            runCatching { return Instant.parse(startIso).toEpochMilli() }
            runCatching {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                return fmt.parse(startIso)?.time
            }
            runCatching {
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                return fmt.parse(startIso)?.time
            }
        }
        return null
    }
}
