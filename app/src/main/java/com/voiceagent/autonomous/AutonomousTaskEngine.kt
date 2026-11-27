package com.voiceagent.autonomous

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.voiceagent.MainActivity
import com.voiceagent.R
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.llm.AndroidAccessibilitySkillPrompt
import com.voiceagent.llm.AutonomousAgentSkillPrompt
import com.voiceagent.llm.LlmStreamEvent
import com.voiceagent.llm.StreamingLlmRouter
import com.voiceagent.llm.SystemPromptProvider
import com.voiceagent.memory.AssembledLlmContext
import com.voiceagent.memory.AutonomousTaskEntity
import com.voiceagent.memory.AutonomousTaskLogEntity
import com.voiceagent.memory.MemoryDao
import com.voiceagent.notifications.CachedNotification
import com.voiceagent.notifications.SaraNotificationListenerService
import com.voiceagent.tools.SaraToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class DirectiveType {
    COMPLETE,
    NOTIFY,
    NEED_USER,
    IDLE,
    CONTINUE
}

data class ParsedTickDirective(
    val type: DirectiveType,
    val message: String,
    val cleanBody: String
)

object AutonomousTaskHelpers {
    private val DIRECTIVE_COMPLETE_REGEX = Regex("""\[TASK_COMPLETE:\s*(.*?)\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DIRECTIVE_NOTIFY_REGEX = Regex("""\[TASK_NOTIFY:\s*(.*?)\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DIRECTIVE_NEED_USER_REGEX = Regex("""\[TASK_NEED_USER:\s*(.*?)\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DIRECTIVE_IDLE_REGEX = Regex("""\[TASK_IDLE\]""", RegexOption.IGNORE_CASE)

    fun matchesNotification(task: AutonomousTaskEntity, notif: CachedNotification): Boolean {
        if (!task.mode.equals(MODE_EVENT_CHAT_LOOP, ignoreCase = true)) return false
        if (!task.status.equals(STATUS_ACTIVE, ignoreCase = true)) return false

        val pkgFilter = task.watchPackage.trim().lowercase(Locale.ROOT)
        if (pkgFilter.isNotEmpty()) {
            val pkg = notif.packageName.lowercase(Locale.ROOT)
            val app = notif.appLabel.lowercase(Locale.ROOT)
            if (!pkg.contains(pkgFilter) && !app.contains(pkgFilter)) {
                return false
            }
        }

        val senderFilter = task.watchSender.trim()
        if (senderFilter.isEmpty()) return true

        val aliases = senderFilter.split(",", "|")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        if (aliases.isEmpty()) return true

        val titleLower = notif.title.lowercase(Locale.ROOT)
        val textLower = notif.text.lowercase(Locale.ROOT)
        return aliases.any { alias ->
            titleLower.contains(alias) || textLower.startsWith("$alias:")
        }
    }

    fun parseDirective(rawResponse: String): ParsedTickDirective {
        val trimmed = rawResponse.trim()
        DIRECTIVE_COMPLETE_REGEX.find(trimmed)?.let { m ->
            val msg = m.groupValues[1].trim()
            val clean = trimmed.replace(DIRECTIVE_COMPLETE_REGEX, "").trim()
            return ParsedTickDirective(DirectiveType.COMPLETE, msg.ifBlank { clean }, clean)
        }
        DIRECTIVE_NEED_USER_REGEX.find(trimmed)?.let { m ->
            val msg = m.groupValues[1].trim()
            val clean = trimmed.replace(DIRECTIVE_NEED_USER_REGEX, "").trim()
            return ParsedTickDirective(DirectiveType.NEED_USER, msg.ifBlank { clean }, clean)
        }
        DIRECTIVE_NOTIFY_REGEX.find(trimmed)?.let { m ->
            val msg = m.groupValues[1].trim()
            val clean = trimmed.replace(DIRECTIVE_NOTIFY_REGEX, "").trim()
            return ParsedTickDirective(DirectiveType.NOTIFY, msg.ifBlank { clean }, clean)
        }
        if (DIRECTIVE_IDLE_REGEX.containsMatchIn(trimmed)) {
            val clean = trimmed.replace(DIRECTIVE_IDLE_REGEX, "").trim()
            return ParsedTickDirective(DirectiveType.IDLE, "", clean)
        }
        return ParsedTickDirective(DirectiveType.CONTINUE, trimmed, trimmed)
    }

    fun appendScratchpad(existing: String, entry: String, maxChars: Int = 3000): String {
        val cleanEntry = entry.trim()
        if (cleanEntry.isBlank()) return existing.takeLast(maxChars)
        val combined = if (existing.isBlank()) cleanEntry else "$existing\n$cleanEntry"
        if (combined.length <= maxChars) return combined
        val sliced = combined.takeLast(maxChars)
        val firstNewline = sliced.indexOf('\n')
        return if (firstNewline in 1 until sliced.length - 1) {
            sliced.substring(firstNewline + 1)
        } else {
            sliced
        }
    }

    fun shouldPauseForMaxRuns(task: AutonomousTaskEntity): Boolean {
        return task.maxRuns > 0 && task.runCount >= task.maxRuns
    }

    const val MODE_ONE_SHOT = "ONE_SHOT_SCHEDULED"
    const val MODE_INTERVAL_WATCH = "INTERVAL_WATCH"
    const val MODE_EVENT_CHAT_LOOP = "EVENT_CHAT_LOOP"

    const val STATUS_ACTIVE = "ACTIVE"
    const val STATUS_PAUSED = "PAUSED"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_STOPPED = "STOPPED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_WAITING_UNLOCK = "WAITING_UNLOCK"
}

class AutonomousTaskEngine(
    private val appContext: Context,
    private val dao: MemoryDao,
    private val toolRegistry: SaraToolRegistry,
    private val llmRouter: StreamingLlmRouter,
    private val speakProactive: suspend (String) -> Boolean
) {
    companion object {
        const val CHANNEL_ONGOING = "sarah_autonomous_tasks"
        const val CHANNEL_ALERTS = "sarah_autonomous_alerts"
        const val ONGOING_NOTIFICATION_ID = 4205
        private const val DEBOUNCE_BURST_MS = 2200L
        private const val DEDUP_WINDOW_MS = 60_000L

        private val AUTONOMOUS_TOOL_SET = setOf(
            "notification_center",
            "device_intent",
            "device_status",
            "live_info",
            "observe_screen",
            "ui_act",
            "shizuku_shell",
            "personal_scheduler",
            "memory_facts"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executionMutex = Mutex()
    private val pendingNotifBuffers = ConcurrentHashMap<Long, MutableList<CachedNotification>>()
    private val debounceJobs = ConcurrentHashMap<Long, Job>()
    private val recentNotifSignatures = ConcurrentHashMap<Long, Pair<String, Long>>()

    @Volatile
    private var started = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                scope.launch {
                    resumeWaitingUnlockTasks()
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        createNotificationChannels()

        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                unlockReceiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            Log.w(PipelineConfig.LOG_TAG, "[AutonomousEngine] Failed to register USER_PRESENT receiver: ${it.message}")
        }

        scope.launch {
            restoreScheduledAlarmsAndLoops()
        }

        scope.launch {
            SaraNotificationListenerService.incomingEvents.collect { notif ->
                handleIncomingNotification(notif)
            }
        }
    }

    suspend fun restoreScheduledAlarmsAndLoops() {
        val active = dao.getActiveAutonomousTasks()
        val now = System.currentTimeMillis()
        for (task in active) {
            val nextMs = task.nextTriggerAtMs
            if (nextMs != null) {
                if (nextMs <= now) {
                    if (task.mode == AutonomousTaskHelpers.MODE_ONE_SHOT) {
                        scope.launch { executeTaskTick(task.id, "ALARM", null) }
                    } else if (task.intervalMinutes != null && task.intervalMinutes > 0.0) {
                        val rescheduled = now + (task.intervalMinutes * 60_000.0).toLong().coerceAtLeast(15_000L)
                        dao.updateAutonomousTask(task.copy(nextTriggerAtMs = rescheduled, updatedAt = now))
                        scheduleAlarm(task.id, rescheduled)
                    }
                } else {
                    scheduleAlarm(task.id, nextMs)
                }
            }
        }
        refreshOngoingNotification()
        Log.i(PipelineConfig.LOG_TAG, "[AutonomousEngine] Restored ${active.size} active autonomous task(s)")
    }

    private suspend fun resumeWaitingUnlockTasks() {
        val waiting = dao.getRunnableOrWaitingAutonomousTasks()
            .filter { it.status == AutonomousTaskHelpers.STATUS_WAITING_UNLOCK }
        if (waiting.isEmpty()) return
        Log.i(PipelineConfig.LOG_TAG, "[AutonomousEngine] Device unlocked -> resuming ${waiting.size} waiting task(s)")
        for (task in waiting) {
            dao.updateAutonomousTaskStatus(task.id, AutonomousTaskHelpers.STATUS_ACTIVE)
            scope.launch { executeTaskTick(task.id, "UNLOCK_RESUME", null) }
        }
        refreshOngoingNotification()
    }

    private suspend fun handleIncomingNotification(notif: CachedNotification) {
        val active = dao.getActiveAutonomousTasks()
        if (active.isEmpty()) return
        val now = System.currentTimeMillis()

        for (task in active) {
            if (!AutonomousTaskHelpers.matchesNotification(task, notif)) continue

            val sig = "${notif.title}|${notif.text}"
            val prevSig = recentNotifSignatures[task.id]
            if (prevSig != null && prevSig.first == sig && (now - prevSig.second) < DEDUP_WINDOW_MS) {
                continue
            }
            recentNotifSignatures[task.id] = sig to now

            val buf = pendingNotifBuffers.getOrPut(task.id) { java.util.Collections.synchronizedList(mutableListOf()) }
            synchronized(buf) {
                buf += notif
            }

            debounceJobs[task.id]?.cancel()
            debounceJobs[task.id] = scope.launch {
                val cooldownMs = (task.cooldownSeconds.coerceAtLeast(1) * 1000L)
                val sinceLastRun = now - (task.lastRunAtMs ?: 0L)
                val waitMs = maxOf(DEBOUNCE_BURST_MS, (cooldownMs - sinceLastRun).coerceAtLeast(0L))
                delay(waitMs)

                val batch = synchronized(buf) {
                    val copy = buf.toList()
                    buf.clear()
                    copy
                }
                if (batch.isNotEmpty()) {
                    val payload = batch.joinToString("\n") { n ->
                        "App: ${n.appLabel} (${n.packageName}) | From: ${n.title} | HasInlineReply: ${n.hasReplyAction} | Message: ${n.text}"
                    }
                    executeTaskTick(task.id, "NOTIFICATION", payload)
                }
            }
        }
    }

    fun onAlarmFired(taskId: Long) {
        scope.launch {
            executeTaskTick(taskId, "ALARM", null)
        }
    }

    fun triggerTaskNow(taskId: Long) {
        scope.launch {
            executeTaskTick(taskId, "MANUAL", null)
        }
    }

    suspend fun createTask(
        title: String,
        mode: String,
        goalPrompt: String,
        watchPackage: String = "",
        watchSender: String = "",
        nextTriggerAtMs: Long? = null,
        intervalMinutes: Double? = null,
        cooldownSeconds: Int = 5,
        maxRuns: Int = if (mode == AutonomousTaskHelpers.MODE_EVENT_CHAT_LOOP) 40 else 24,
        needsScreenUi: Boolean = false,
        speakOnMilestone: Boolean = true,
        speakEveryTick: Boolean = false,
        triggerImmediately: Boolean = false,
        initialEventPayload: String? = null
    ): AutonomousTaskEntity {
        val now = System.currentTimeMillis()
        val effectiveNextTrigger = when {
            nextTriggerAtMs != null -> nextTriggerAtMs
            mode == AutonomousTaskHelpers.MODE_INTERVAL_WATCH && intervalMinutes != null && intervalMinutes > 0.0 ->
                now + (intervalMinutes * 60_000.0).toLong().coerceAtLeast(15_000L)
            else -> null
        }

        val entity = AutonomousTaskEntity(
            title = title.trim().ifBlank { "Autonomous Task" },
            mode = mode,
            goalPrompt = goalPrompt.trim(),
            status = AutonomousTaskHelpers.STATUS_ACTIVE,
            watchPackage = watchPackage.trim(),
            watchSender = watchSender.trim(),
            nextTriggerAtMs = effectiveNextTrigger,
            intervalMinutes = intervalMinutes,
            cooldownSeconds = cooldownSeconds.coerceIn(1, 300),
            maxRuns = maxRuns.coerceIn(1, 500),
            runCount = 0,
            needsScreenUi = needsScreenUi,
            speakOnMilestone = speakOnMilestone,
            speakEveryTick = speakEveryTick,
            createdAt = now,
            updatedAt = now
        )
        val id = dao.insertAutonomousTask(entity)
        val saved = entity.copy(id = id)

        if (effectiveNextTrigger != null) {
            scheduleAlarm(id, effectiveNextTrigger)
        }
        dao.insertAutonomousTaskLog(
            AutonomousTaskLogEntity(
                taskId = id,
                triggerSource = "START",
                summary = "Task created (${saved.mode}): ${saved.title}",
                outcome = "CONTINUED",
                createdAt = now
            )
        )
        refreshOngoingNotification()

        if (triggerImmediately) {
            scope.launch {
                executeTaskTick(id, "START", initialEventPayload)
            }
        }
        return saved
    }

    suspend fun pauseTask(taskId: Long): Boolean {
        val task = dao.getAutonomousTaskById(taskId) ?: return false
        debounceJobs.remove(taskId)?.cancel()
        cancelAlarm(taskId)
        dao.updateAutonomousTaskStatus(taskId, AutonomousTaskHelpers.STATUS_PAUSED)
        dao.insertAutonomousTaskLog(
            AutonomousTaskLogEntity(
                taskId = taskId,
                triggerSource = "MANUAL",
                summary = "Task paused",
                outcome = "CONTINUED"
            )
        )
        refreshOngoingNotification()
        return true
    }

    suspend fun resumeTask(taskId: Long): Boolean {
        val task = dao.getAutonomousTaskById(taskId) ?: return false
        val now = System.currentTimeMillis()
        val nextMs = when {
            task.mode == AutonomousTaskHelpers.MODE_INTERVAL_WATCH && task.intervalMinutes != null && task.intervalMinutes > 0.0 ->
                now + (task.intervalMinutes * 60_000.0).toLong().coerceAtLeast(15_000L)
            task.nextTriggerAtMs != null && task.nextTriggerAtMs > now -> task.nextTriggerAtMs
            else -> null
        }
        dao.updateAutonomousTask(
            task.copy(
                status = AutonomousTaskHelpers.STATUS_ACTIVE,
                nextTriggerAtMs = nextMs,
                updatedAt = now
            )
        )
        if (nextMs != null) scheduleAlarm(taskId, nextMs)
        refreshOngoingNotification()
        return true
    }

    suspend fun stopTask(taskId: Long): Boolean {
        val task = dao.getAutonomousTaskById(taskId) ?: return false
        debounceJobs.remove(taskId)?.cancel()
        pendingNotifBuffers.remove(taskId)
        cancelAlarm(taskId)
        dao.updateAutonomousTask(
            task.copy(
                status = AutonomousTaskHelpers.STATUS_STOPPED,
                nextTriggerAtMs = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        dao.insertAutonomousTaskLog(
            AutonomousTaskLogEntity(
                taskId = taskId,
                triggerSource = "MANUAL",
                summary = "Task stopped by user",
                outcome = "COMPLETED"
            )
        )
        refreshOngoingNotification()
        return true
    }

    suspend fun stopAllTasks(): Int {
        val runnable = dao.getRunnableOrWaitingAutonomousTasks()
        for (task in runnable) {
            debounceJobs.remove(task.id)?.cancel()
            pendingNotifBuffers.remove(task.id)
            cancelAlarm(task.id)
            dao.updateAutonomousTask(
                task.copy(
                    status = AutonomousTaskHelpers.STATUS_STOPPED,
                    nextTriggerAtMs = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            dao.insertAutonomousTaskLog(
                AutonomousTaskLogEntity(
                    taskId = task.id,
                    triggerSource = "MANUAL",
                    summary = "Stopped via Stop All",
                    outcome = "COMPLETED"
                )
            )
        }
        refreshOngoingNotification()
        return runnable.size
    }

    suspend fun pauseAllTasks(): Int {
        val active = dao.getActiveAutonomousTasks()
        for (task in active) {
            debounceJobs.remove(task.id)?.cancel()
            cancelAlarm(task.id)
            dao.updateAutonomousTaskStatus(task.id, AutonomousTaskHelpers.STATUS_PAUSED)
        }
        refreshOngoingNotification()
        return active.size
    }

    suspend fun deleteTask(taskId: Long) {
        debounceJobs.remove(taskId)?.cancel()
        pendingNotifBuffers.remove(taskId)
        cancelAlarm(taskId)
        dao.deleteLogsForTask(taskId)
        dao.deleteAutonomousTaskById(taskId)
        refreshOngoingNotification()
    }

    private suspend fun executeTaskTick(
        taskId: Long,
        triggerSource: String,
        eventPayload: String?
    ) = executionMutex.withLock {
        val task = dao.getAutonomousTaskById(taskId) ?: return@withLock
        if (task.status != AutonomousTaskHelpers.STATUS_ACTIVE) {
            Log.d(PipelineConfig.LOG_TAG, "[AutonomousEngine] Skipping tick for #$taskId (status=${task.status})")
            return@withLock
        }

        val now = System.currentTimeMillis()
        if (AutonomousTaskHelpers.shouldPauseForMaxRuns(task)) {
            val msg = "Paused '${task.title}' after reaching the ${task.maxRuns}-step safety limit."
            dao.updateAutonomousTask(
                task.copy(
                    status = AutonomousTaskHelpers.STATUS_PAUSED,
                    nextTriggerAtMs = null,
                    lastResultSummary = msg,
                    updatedAt = now
                )
            )
            cancelAlarm(taskId)
            dao.insertAutonomousTaskLog(
                AutonomousTaskLogEntity(
                    taskId = taskId,
                    triggerSource = triggerSource,
                    summary = msg,
                    outcome = "BLOCKED"
                )
            )
            postAlertNotification(taskId, task.title, msg)
            if (task.speakOnMilestone) speakProactive(msg)
            refreshOngoingNotification()
            return@withLock
        }

        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLocked = km.isKeyguardLocked
        val requiresUiNow = task.needsScreenUi || (triggerSource == "START" && task.mode == AutonomousTaskHelpers.MODE_EVENT_CHAT_LOOP && eventPayload.isNullOrBlank())
        var didAutoUnlock = false

        if (isLocked && requiresUiNow) {
            val autoUnlockEnabled = SecretsProvider.isAutonomousAutoUnlockEnabled(appContext)
            val savedPin = dao.getFactByKey("user.phone_lock_pin")?.factValue?.trim().orEmpty()
            if (autoUnlockEnabled && savedPin.isNotBlank()) {
                val unlockOut = runCatching {
                    toolRegistry.deviceIntentTool.execute(buildJsonObject { put("action", "unlock_device") })
                }.getOrDefault("")
                if (unlockOut.contains("\"ok\":true")) {
                    didAutoUnlock = true
                    delay(450L)
                } else {
                    markWaitingUnlock(task, triggerSource, "Could not auto-unlock screen for '${task.title}'. Unlock your phone to resume.")
                    return@withLock
                }
            } else {
                markWaitingUnlock(task, triggerSource, "'${task.title}' needs screen access while locked. Unlock your phone (or enable Auto-Unlock in Settings) to continue.")
                return@withLock
            }
        }

        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val tickWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SarahVoice::AutonomousTick").apply {
            setReferenceCounted(false)
            runCatching { acquire(90_000L) }
        }

        try {
            val facts = (dao.getPinnedFacts() + dao.getRecentFacts(12))
                .distinctBy { it.factKey }
                .take(18)
            val factsSummary = if (facts.isEmpty()) {
                ""
            } else {
                facts.joinToString("\n") { "- ${it.factKey}: ${it.factValue}" }
            }

            val sysPrompt = buildString {
                append(SystemPromptProvider.SARAH_VOICE_PROMPT)
                append("\n\n")
                append(AutonomousAgentSkillPrompt.TICK_INSTRUCTIONS)
                if (requiresUiNow || task.mode == AutonomousTaskHelpers.MODE_EVENT_CHAT_LOOP) {
                    append("\n\n")
                    append(AndroidAccessibilitySkillPrompt.SKILL_INSTRUCTIONS)
                }
                if (factsSummary.isNotBlank()) {
                    append("\n\n[KNOWN USER FACTS]\n")
                    append(factsSummary)
                }
            }

            val context = AssembledLlmContext(
                sessionId = "autonomous_task_$taskId",
                systemPrompt = sysPrompt,
                factsSummary = factsSummary,
                recentSessionsDigest = "",
                rollingSummary = "",
                turns = emptyList(),
                estimatedTokens = 0,
                factsInjectedCount = facts.size
            )

            val timeLabel = SimpleDateFormat("EEE, MMM d h:mm a", Locale.US).format(Date(now))
            val tickUserPrompt = buildString {
                append("[AUTONOMOUS TASK WAKEUP]\n")
                append("Task ID: #${task.id} | Title: ${task.title}\n")
                append("Mode: ${task.mode} | Trigger: $triggerSource | Run: ${task.runCount + 1}/${task.maxRuns} | Time: $timeLabel\n")
                if (task.watchSender.isNotBlank()) append("Target Person: ${task.watchSender}\n")
                if (task.watchPackage.isNotBlank()) append("Target App Filter: ${task.watchPackage}\n")
                append("Device Locked Right Now: ${km.isKeyguardLocked} (autoUnlocked=$didAutoUnlock)\n\n")
                append("[TASK GOAL]\n${task.goalPrompt}\n\n")
                if (task.scratchpad.isNotBlank()) {
                    append("[TASK SCRATCHPAD — RECENT HISTORY]\n${task.scratchpad}\n\n")
                }
                if (!eventPayload.isNullOrBlank()) {
                    append("<untrusted_notification_data>\n$eventPayload\n</untrusted_notification_data>\n\n")
                }
                append("Execute the next autonomous step now using tools if needed, and end with your control directive.")
            }

            val toolsUsed = linkedSetOf<String>()
            val textBuf = StringBuilder()
            var completedText = ""
            val thinkingLevel = if (requiresUiNow) "MEDIUM" else "LOW"

            Log.i(PipelineConfig.LOG_TAG, "[AutonomousEngine] Executing tick #${task.runCount + 1} for task #$taskId (${task.title}) via Gemini Smart ($thinkingLevel)")

            llmRouter.antigravityProvider.streamTurn(
                context = context,
                userText = tickUserPrompt,
                selectedTools = AUTONOMOUS_TOOL_SET,
                thinkingLevel = thinkingLevel,
                trackActiveCall = false
            ).collect { ev ->
                when (ev) {
                    is LlmStreamEvent.ToolExecuted -> toolsUsed += ev.name
                    is LlmStreamEvent.TextDelta -> textBuf.append(ev.delta)
                    is LlmStreamEvent.Completed -> completedText = ev.fullText
                    else -> {}
                }
            }

            val rawOut = completedText.ifBlank { textBuf.toString() }.trim()
            val directive = AutonomousTaskHelpers.parseDirective(rawOut)
            val stamp = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))
            val summaryText = directive.message.ifBlank { directive.cleanBody }.ifBlank { "Tick executed ($triggerSource)" }
            val scratchEntry = "[$stamp $triggerSource tools=${toolsUsed.joinToString(",")}] ${summaryText.take(260)}"
            val nextScratchpad = AutonomousTaskHelpers.appendScratchpad(task.scratchpad, scratchEntry)

            var nextStatus = AutonomousTaskHelpers.STATUS_ACTIVE
            var nextTriggerMs: Long? = null
            var outcome = "CONTINUED"
            var shouldSpeak = false
            var spokenText = ""

            when {
                directive.type == DirectiveType.COMPLETE || task.mode == AutonomousTaskHelpers.MODE_ONE_SHOT -> {
                    nextStatus = AutonomousTaskHelpers.STATUS_COMPLETED
                    outcome = "COMPLETED"
                    cancelAlarm(taskId)
                    if (task.speakOnMilestone) {
                        shouldSpeak = true
                        spokenText = summaryText
                    }
                    postAlertNotification(taskId, "${task.title} · Done", summaryText)
                }
                directive.type == DirectiveType.NEED_USER -> {
                    nextStatus = AutonomousTaskHelpers.STATUS_PAUSED
                    outcome = "BLOCKED"
                    cancelAlarm(taskId)
                    if (task.speakOnMilestone) {
                        shouldSpeak = true
                        spokenText = summaryText
                    }
                    postAlertNotification(taskId, "${task.title} · Needs You", summaryText)
                }
                directive.type == DirectiveType.NOTIFY -> {
                    if (task.speakOnMilestone) {
                        shouldSpeak = true
                        spokenText = summaryText
                    }
                    postAlertNotification(taskId, task.title, summaryText)
                    nextTriggerMs = computeNextIntervalTrigger(task, now)
                }
                else -> {
                    // IDLE or routine CONTINUE: silent unless speakEveryTick is explicitly true
                    if (task.speakEveryTick && directive.type != DirectiveType.IDLE && summaryText.isNotBlank()) {
                        shouldSpeak = true
                        spokenText = summaryText
                    }
                    nextTriggerMs = computeNextIntervalTrigger(task, now)
                }
            }

            if (nextStatus == AutonomousTaskHelpers.STATUS_ACTIVE && nextTriggerMs != null) {
                scheduleAlarm(taskId, nextTriggerMs)
            }

            dao.updateAutonomousTask(
                task.copy(
                    status = nextStatus,
                    nextTriggerAtMs = nextTriggerMs,
                    runCount = task.runCount + 1,
                    scratchpad = nextScratchpad,
                    lastResultSummary = summaryText.take(400),
                    lastRunAtMs = now,
                    updatedAt = now
                )
            )
            dao.insertAutonomousTaskLog(
                AutonomousTaskLogEntity(
                    taskId = taskId,
                    triggerSource = triggerSource,
                    summary = summaryText.take(500),
                    toolsUsed = toolsUsed.joinToString(","),
                    outcome = outcome,
                    createdAt = now
                )
            )
            dao.pruneOldLogsForTask(taskId, 30)
            refreshOngoingNotification()

            if (shouldSpeak && spokenText.isNotBlank()) {
                speakProactive(spokenText)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(PipelineConfig.LOG_TAG, "[AutonomousEngine] Tick failed for #$taskId: ${e.message}", e)
            val errMsg = "Error: ${e.message?.take(200) ?: "unknown error"}"
            val nextStatus = if (task.mode == AutonomousTaskHelpers.MODE_ONE_SHOT) {
                AutonomousTaskHelpers.STATUS_FAILED
            } else {
                AutonomousTaskHelpers.STATUS_ACTIVE
            }
            val nextTriggerMs = if (nextStatus == AutonomousTaskHelpers.STATUS_ACTIVE) {
                computeNextIntervalTrigger(task, now)
            } else {
                null
            }
            if (nextTriggerMs != null) scheduleAlarm(taskId, nextTriggerMs)
            dao.updateAutonomousTask(
                task.copy(
                    status = nextStatus,
                    nextTriggerAtMs = nextTriggerMs,
                    runCount = task.runCount + 1,
                    lastResultSummary = errMsg,
                    lastRunAtMs = now,
                    updatedAt = now
                )
            )
            dao.insertAutonomousTaskLog(
                AutonomousTaskLogEntity(
                    taskId = taskId,
                    triggerSource = triggerSource,
                    summary = errMsg,
                    outcome = "ERROR",
                    createdAt = now
                )
            )
            refreshOngoingNotification()
        } finally {
            if (didAutoUnlock) {
                delay(500L)
                runCatching {
                    toolRegistry.deviceIntentTool.execute(buildJsonObject { put("action", "screen_off") })
                }
            }
            if (tickWakeLock.isHeld) {
                runCatching { tickWakeLock.release() }
            }
        }
    }

    private suspend fun markWaitingUnlock(task: AutonomousTaskEntity, triggerSource: String, reason: String) {
        val now = System.currentTimeMillis()
        dao.updateAutonomousTask(
            task.copy(
                status = AutonomousTaskHelpers.STATUS_WAITING_UNLOCK,
                lastResultSummary = reason,
                updatedAt = now
            )
        )
        dao.insertAutonomousTaskLog(
            AutonomousTaskLogEntity(
                taskId = task.id,
                triggerSource = triggerSource,
                summary = reason,
                outcome = "WAITING_UNLOCK",
                createdAt = now
            )
        )
        postAlertNotification(task.id, "${task.title} · Unlock Needed", reason)
        if (task.speakOnMilestone) {
            speakProactive(reason)
        }
        refreshOngoingNotification()
    }

    private fun computeNextIntervalTrigger(task: AutonomousTaskEntity, now: Long): Long? {
        val mins = task.intervalMinutes ?: return null
        if (mins <= 0.0) return null
        return now + (mins * 60_000.0).toLong().coerceAtLeast(15_000L)
    }

    fun scheduleAlarm(taskId: Long, triggerAtMs: Long) {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appContext, AutonomousAlarmReceiver::class.java).apply {
            action = AutonomousAlarmReceiver.ACTION_AUTONOMOUS_TICK
            putExtra(AutonomousAlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        val pi = PendingIntent.getBroadcast(
            appContext,
            30000 + (taskId.toInt() and 0x7FFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancelAlarm(taskId: Long) {
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appContext, AutonomousAlarmReceiver::class.java).apply {
            action = AutonomousAlarmReceiver.ACTION_AUTONOMOUS_TICK
        }
        val pi = PendingIntent.getBroadcast(
            appContext,
            30000 + (taskId.toInt() and 0x7FFF),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) am.cancel(pi)
    }

    private suspend fun refreshOngoingNotification() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = dao.getRunnableOrWaitingAutonomousTasks()
        if (active.isEmpty()) {
            nm.cancel(ONGOING_NOTIFICATION_ID)
            return
        }

        val openPi = PendingIntent.getActivity(
            appContext,
            4205,
            Intent(appContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseAllPi = PendingIntent.getBroadcast(
            appContext,
            4206,
            Intent(appContext, AutonomousAlarmReceiver::class.java).apply {
                action = AutonomousAlarmReceiver.ACTION_PAUSE_ALL_TASKS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAllPi = PendingIntent.getBroadcast(
            appContext,
            4207,
            Intent(appContext, AutonomousAlarmReceiver::class.java).apply {
                action = AutonomousAlarmReceiver.ACTION_STOP_ALL_TASKS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val head = active.first()
        val title = if (active.size == 1) {
            "Autonomous: ${head.title}"
        } else {
            "${active.size} Autonomous Loops Active"
        }
        val subtitle = head.lastResultSummary.ifBlank {
            "Mode: ${head.mode.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} · Run ${head.runCount}/${head.maxRuns}"
        }

        val notif = NotificationCompat.Builder(appContext, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_sarah_notif)
            .setColor(0xFFC96442.toInt())
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Pause All", pauseAllPi)
            .addAction(0, "Stop All", stopAllPi)
            .build()

        nm.notify(ONGOING_NOTIFICATION_ID, notif)
    }

    private fun postAlertNotification(taskId: Long, title: String, message: String) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = 4300 + (taskId.toInt() and 0x7FF)
        val openPi = PendingIntent.getActivity(
            appContext,
            notifId,
            Intent(appContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getBroadcast(
            appContext,
            notifId + 2000,
            Intent(appContext, AutonomousAlarmReceiver::class.java).apply {
                action = AutonomousAlarmReceiver.ACTION_STOP_TASK
                putExtra(AutonomousAlarmReceiver.EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(appContext, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_sarah_notif)
            .setColor(0xFFC96442.toInt())
            .setSubText("Sarah Autonomous")
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Stop Task", stopPi)
            .build()

        nm.notify(notifId, notif)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "Sarah Autonomous Background Loops",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows currently active autonomous tasks and quick Stop/Pause controls."
                    setShowBadge(false)
                    setSound(null, null)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Sarah Autonomous Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Milestones, watch triggers, and completion updates from autonomous tasks."
                }
            )
        }
    }
}
