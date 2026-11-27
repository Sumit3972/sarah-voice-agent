package com.voiceagent.tools

import com.voiceagent.autonomous.AutonomousTaskEngine
import com.voiceagent.autonomous.AutonomousTaskHelpers
import com.voiceagent.config.asBoolOrNull
import com.voiceagent.config.asDoubleOrNull
import com.voiceagent.config.asIntOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.memory.AutonomousTaskEntity
import com.voiceagent.memory.MemoryDao
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

class AutonomousTaskTool(
    private val dao: MemoryDao
) {
    @Volatile
    var engine: AutonomousTaskEngine? = null

    suspend fun execute(args: JsonObject): String {
        val eng = engine
            ?: return """{"ok":false,"error":"AutonomousTaskEngine not initialized"}"""

        val action = args["action"].asStrOrNull()?.trim()?.lowercase().orEmpty()
        val taskId = args["task_id"].asIntOrNull()?.toLong()
        val query = args["query"].asStrOrNull()?.trim().orEmpty()
        val title = args["title"].asStrOrNull()?.trim().orEmpty()
        val goalPrompt = args["goal_prompt"].asStrOrNull()?.trim().orEmpty()
        val watchPackage = args["watch_package"].asStrOrNull()?.trim().orEmpty()
        val watchSender = args["watch_sender"].asStrOrNull()?.trim().orEmpty()
        val initialMessage = args["initial_message"].asStrOrNull()?.trim().orEmpty()
        val inMinutes = args["in_minutes"].asDoubleOrNull()
        val intervalMinutes = args["interval_minutes"].asDoubleOrNull()
        val startIso = args["start_iso"].asStrOrNull()?.trim().orEmpty()
        val cooldownSeconds = args["cooldown_seconds"].asIntOrNull() ?: 5
        val maxRuns = args["max_runs"].asIntOrNull()
        val needsScreenUi = args["needs_screen_ui"].asBoolOrNull() ?: false
        val speakOnMilestone = args["speak_on_milestone"].asBoolOrNull() ?: true
        val speakEveryTick = args["speak_every_tick"].asBoolOrNull() ?: false

        return when (action) {
            "create_scheduled_task" -> createScheduledTask(
                eng, title, goalPrompt, inMinutes, startIso, needsScreenUi, speakOnMilestone
            )
            "create_watch_loop" -> createWatchLoop(
                eng, title, goalPrompt, intervalMinutes, watchPackage, watchSender,
                maxRuns ?: 24, needsScreenUi, speakOnMilestone, speakEveryTick
            )
            "create_chat_loop" -> createChatLoop(
                eng, title, goalPrompt, watchSender, watchPackage, initialMessage,
                cooldownSeconds, maxRuns ?: 40, needsScreenUi, speakOnMilestone
            )
            "list_tasks" -> listTasks()
            "pause_task" -> {
                val target = findTask(taskId, query)
                    ?: return """{"ok":false,"error":"No matching autonomous task found"}"""
                eng.pauseTask(target.id)
                buildJsonObject {
                    put("ok", true)
                    put("task_id", target.id)
                    put("title", target.title)
                    put("status", AutonomousTaskHelpers.STATUS_PAUSED)
                }.toString()
            }
            "resume_task" -> {
                val target = findTask(taskId, query)
                    ?: return """{"ok":false,"error":"No matching autonomous task found"}"""
                eng.resumeTask(target.id)
                buildJsonObject {
                    put("ok", true)
                    put("task_id", target.id)
                    put("title", target.title)
                    put("status", AutonomousTaskHelpers.STATUS_ACTIVE)
                }.toString()
            }
            "stop_task" -> {
                val target = findTask(taskId, query)
                    ?: return """{"ok":false,"error":"No matching active autonomous task found"}"""
                eng.stopTask(target.id)
                buildJsonObject {
                    put("ok", true)
                    put("task_id", target.id)
                    put("title", target.title)
                    put("status", AutonomousTaskHelpers.STATUS_STOPPED)
                }.toString()
            }
            "stop_all_tasks" -> {
                val count = eng.stopAllTasks()
                buildJsonObject {
                    put("ok", true)
                    put("stopped_count", count)
                }.toString()
            }
            "run_task_now" -> {
                val target = findTask(taskId, query)
                    ?: return """{"ok":false,"error":"No matching autonomous task found"}"""
                eng.triggerTaskNow(target.id)
                buildJsonObject {
                    put("ok", true)
                    put("task_id", target.id)
                    put("title", target.title)
                    put("triggered", true)
                }.toString()
            }
            else -> """{"ok":false,"error":"Unknown autonomous_task action='$action'."}"""
        }
    }

    private suspend fun createScheduledTask(
        eng: AutonomousTaskEngine,
        title: String,
        goalPrompt: String,
        inMinutes: Double?,
        startIso: String,
        needsScreenUi: Boolean,
        speakOnMilestone: Boolean
    ): String {
        if (goalPrompt.isBlank()) return """{"ok":false,"error":"goal_prompt is required"}"""
        val triggerAtMs = resolveTriggerTimeMs(inMinutes, startIso)
            ?: return """{"ok":false,"error":"Provide in_minutes or start_iso for scheduled task"}"""

        val created = eng.createTask(
            title = title.ifBlank { goalPrompt.take(40) },
            mode = AutonomousTaskHelpers.MODE_ONE_SHOT,
            goalPrompt = goalPrompt,
            nextTriggerAtMs = triggerAtMs,
            maxRuns = 1,
            needsScreenUi = needsScreenUi,
            speakOnMilestone = speakOnMilestone,
            speakEveryTick = false
        )
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        return buildJsonObject {
            put("ok", true)
            put("task_id", created.id)
            put("title", created.title)
            put("mode", created.mode)
            put("fires_at", fmt.format(Date(triggerAtMs)))
        }.toString()
    }

    private suspend fun createWatchLoop(
        eng: AutonomousTaskEngine,
        title: String,
        goalPrompt: String,
        intervalMinutes: Double?,
        watchPackage: String,
        watchSender: String,
        maxRuns: Int,
        needsScreenUi: Boolean,
        speakOnMilestone: Boolean,
        speakEveryTick: Boolean
    ): String {
        if (goalPrompt.isBlank()) return """{"ok":false,"error":"goal_prompt is required"}"""
        val interval = (intervalMinutes ?: 5.0).coerceAtLeast(0.5)
        val created = eng.createTask(
            title = title.ifBlank { "Watch: ${goalPrompt.take(36)}" },
            mode = AutonomousTaskHelpers.MODE_INTERVAL_WATCH,
            goalPrompt = goalPrompt,
            watchPackage = watchPackage,
            watchSender = watchSender,
            intervalMinutes = interval,
            maxRuns = maxRuns,
            needsScreenUi = needsScreenUi,
            speakOnMilestone = speakOnMilestone,
            speakEveryTick = speakEveryTick,
            triggerImmediately = true
        )
        return buildJsonObject {
            put("ok", true)
            put("task_id", created.id)
            put("title", created.title)
            put("mode", created.mode)
            put("interval_minutes", interval)
            put("max_runs", created.maxRuns)
        }.toString()
    }

    private suspend fun createChatLoop(
        eng: AutonomousTaskEngine,
        title: String,
        goalPrompt: String,
        watchSender: String,
        watchPackage: String,
        initialMessage: String,
        cooldownSeconds: Int,
        maxRuns: Int,
        needsScreenUi: Boolean,
        speakOnMilestone: Boolean
    ): String {
        if (watchSender.isBlank()) return """{"ok":false,"error":"watch_sender (contact/person name) is required for create_chat_loop"}"""
        val fullGoal = buildString {
            append(goalPrompt.ifBlank { "Reply warmly and naturally to $watchSender on behalf of the user until told to stop." })
            if (initialMessage.isNotBlank()) {
                append("\n\nIf you are starting the conversation right now (START trigger), send this initial message first: \"$initialMessage\"")
            }
        }

        val created = eng.createTask(
            title = title.ifBlank { "Chat with $watchSender" },
            mode = AutonomousTaskHelpers.MODE_EVENT_CHAT_LOOP,
            goalPrompt = fullGoal,
            watchPackage = watchPackage,
            watchSender = watchSender,
            cooldownSeconds = cooldownSeconds,
            maxRuns = maxRuns,
            needsScreenUi = needsScreenUi,
            speakOnMilestone = speakOnMilestone,
            speakEveryTick = false,
            triggerImmediately = initialMessage.isNotBlank()
        )
        return buildJsonObject {
            put("ok", true)
            put("task_id", created.id)
            put("title", created.title)
            put("mode", created.mode)
            put("watch_sender", created.watchSender)
            put("watch_package", created.watchPackage.ifBlank { "any" })
            put("initiated_immediately", initialMessage.isNotBlank())
        }.toString()
    }

    private suspend fun listTasks(): String {
        val tasks = dao.getAllAutonomousTasks().take(20)
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.US)
        return buildJsonObject {
            put("ok", true)
            put("tasks", buildJsonArray {
                tasks.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id)
                        put("title", t.title)
                        put("mode", t.mode)
                        put("status", t.status)
                        put("run_count", t.runCount)
                        put("max_runs", t.maxRuns)
                        if (t.watchSender.isNotBlank()) put("watch_sender", t.watchSender)
                        t.nextTriggerAtMs?.let { put("next_trigger", fmt.format(Date(it))) }
                        if (t.lastResultSummary.isNotBlank()) put("last_result", t.lastResultSummary.take(140))
                    })
                }
            })
        }.toString()
    }

    private suspend fun findTask(taskId: Long?, query: String): AutonomousTaskEntity? {
        if (taskId != null && taskId > 0L) return dao.getAutonomousTaskById(taskId)
        val all = dao.getAllAutonomousTasks()
        if (query.isBlank()) {
            return all.firstOrNull { it.status == AutonomousTaskHelpers.STATUS_ACTIVE } ?: all.firstOrNull()
        }
        val q = query.lowercase(Locale.ROOT)
        return all.firstOrNull {
            it.status == AutonomousTaskHelpers.STATUS_ACTIVE &&
                (it.title.lowercase(Locale.ROOT).contains(q) ||
                    it.watchSender.lowercase(Locale.ROOT).contains(q) ||
                    it.goalPrompt.lowercase(Locale.ROOT).contains(q))
        } ?: all.firstOrNull {
            it.title.lowercase(Locale.ROOT).contains(q) ||
                it.watchSender.lowercase(Locale.ROOT).contains(q)
        }
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
