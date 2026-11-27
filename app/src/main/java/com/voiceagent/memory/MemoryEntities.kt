package com.voiceagent.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["session_id", "created_at"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String = "default",
    @ColumnInfo(name = "role") val role: String, // "user" | "assistant" | "tool"
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "model_used") val modelUsed: String = "",
    @ColumnInfo(name = "token_estimate") val tokenEstimate: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memory_facts",
    indices = [Index(value = ["fact_key"], unique = true)]
)
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "fact_key") val factKey: String,
    @ColumnInfo(name = "fact_value") val factValue: String,
    @ColumnInfo(name = "confidence") val confidence: Float = 1.0f,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Fts4(contentEntity = MemoryFactEntity::class)
@Entity(tableName = "memory_facts_fts")
data class MemoryFactFtsEntity(
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "fact_key") val factKey: String,
    @ColumnInfo(name = "fact_value") val factValue: String
)

@Entity(
    tableName = "conversation_summaries",
    indices = [Index(value = ["session_id", "created_at"])]
)
data class ConversationSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String = "default",
    @ColumnInfo(name = "summary_text") val summaryText: String,
    @ColumnInfo(name = "covered_up_to_message_id") val coveredUpToMessageId: Long,
    @ColumnInfo(name = "token_estimate") val tokenEstimate: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["last_active_at"])]
)
data class ChatSessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "title") val title: String = "New conversation",
    @ColumnInfo(name = "digest") val digest: String = "",
    @ColumnInfo(name = "started_at") val startedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "turn_count") val turnCount: Int = 0,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false
)

@Entity(
    tableName = "scheduled_reminders",
    indices = [Index(value = ["status", "trigger_at_ms"])]
)
data class ScheduledReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trigger_at_ms") val triggerAtMs: Long,
    @ColumnInfo(name = "spoken_message") val spokenMessage: String,
    @ColumnInfo(name = "status") val status: String = "pending", // "pending" | "fired" | "cancelled"
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "autonomous_tasks",
    indices = [Index(value = ["status", "next_trigger_at_ms"])]
)
data class AutonomousTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "mode") val mode: String, // "ONE_SHOT_SCHEDULED" | "INTERVAL_WATCH" | "EVENT_CHAT_LOOP"
    @ColumnInfo(name = "goal_prompt") val goalPrompt: String,
    @ColumnInfo(name = "status") val status: String = "ACTIVE", // "ACTIVE" | "PAUSED" | "COMPLETED" | "STOPPED" | "FAILED" | "WAITING_UNLOCK"
    @ColumnInfo(name = "watch_package") val watchPackage: String = "",
    @ColumnInfo(name = "watch_sender") val watchSender: String = "",
    @ColumnInfo(name = "next_trigger_at_ms") val nextTriggerAtMs: Long? = null,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Double? = null,
    @ColumnInfo(name = "cooldown_seconds") val cooldownSeconds: Int = 5,
    @ColumnInfo(name = "max_runs") val maxRuns: Int = 40,
    @ColumnInfo(name = "run_count") val runCount: Int = 0,
    @ColumnInfo(name = "needs_screen_ui") val needsScreenUi: Boolean = false,
    @ColumnInfo(name = "speak_on_milestone") val speakOnMilestone: Boolean = true,
    @ColumnInfo(name = "speak_every_tick") val speakEveryTick: Boolean = false,
    @ColumnInfo(name = "scratchpad") val scratchpad: String = "",
    @ColumnInfo(name = "last_result_summary") val lastResultSummary: String = "",
    @ColumnInfo(name = "last_run_at_ms") val lastRunAtMs: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "autonomous_task_logs",
    indices = [Index(value = ["task_id", "created_at"])]
)
data class AutonomousTaskLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: Long,
    @ColumnInfo(name = "trigger_source") val triggerSource: String, // "ALARM" | "NOTIFICATION" | "MANUAL" | "START"
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "tools_used") val toolsUsed: String = "",
    @ColumnInfo(name = "outcome") val outcome: String, // "CONTINUED" | "COMPLETED" | "WAITING_UNLOCK" | "RATE_LIMITED" | "BLOCKED" | "ERROR"
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
