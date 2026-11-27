package com.voiceagent.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ---- Chat Messages ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentMessagesDesc(sessionId: String = "default", limit: Int = 80): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY id ASC LIMIT :limit")
    suspend fun getMessagesForSessionAsc(sessionId: String, limit: Int = 100): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    suspend fun getLatestMessagesAcrossSessionsDesc(limit: Int = 30): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId AND id > :afterId ORDER BY id ASC")
    suspend fun getMessagesAfterAsc(sessionId: String = "default", afterId: Long): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId AND id > :afterId")
    suspend fun countUncompactedMessages(sessionId: String = "default", afterId: Long): Int

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId AND role IN ('user', 'assistant') ORDER BY id DESC LIMIT 60")
    fun observeRecentMessages(sessionId: String = "default"): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT 30")
    fun observeAllRecentMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun countAllMessages(): Int

    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String): Int

    // ---- Conversation Summaries ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSummary(summary: ConversationSummaryEntity): Long

    @Query("SELECT * FROM conversation_summaries WHERE session_id = :sessionId ORDER BY id DESC LIMIT 1")
    suspend fun getLatestSummary(sessionId: String = "default"): ConversationSummaryEntity?

    @Query("DELETE FROM conversation_summaries WHERE session_id = :sessionId")
    suspend fun deleteSummariesForSession(sessionId: String): Int

    // ---- Isolated Chat Sessions ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE session_id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions ORDER BY last_active_at DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int = 15): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE session_id != :excludeSessionId AND turn_count > 0 ORDER BY last_active_at DESC LIMIT :limit")
    suspend fun getRecentPastSessions(excludeSessionId: String, limit: Int = 5): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE ended_at IS NULL AND session_id != :excludeSessionId")
    suspend fun getOpenSessionsExcept(excludeSessionId: String): List<ChatSessionEntity>

    @Query(
        """
        SELECT * FROM chat_sessions
        WHERE LOWER(title) LIKE '%' || LOWER(:keyword) || '%'
           OR LOWER(digest) LIKE '%' || LOWER(:keyword) || '%'
        ORDER BY last_active_at DESC
        LIMIT :limit
        """
    )
    suspend fun searchSessionsByKeyword(keyword: String, limit: Int = 10): List<ChatSessionEntity>

    @Query("UPDATE chat_sessions SET title = COALESCE(:title, title), digest = COALESCE(:digest, digest), last_active_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateSessionMetadata(sessionId: String, title: String?, digest: String?, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE chat_sessions SET ended_at = :endedAt WHERE session_id = :sessionId")
    suspend fun markSessionEnded(sessionId: String, endedAt: Long)

    @Query("DELETE FROM chat_sessions WHERE session_id = :sessionId")
    suspend fun deleteSessionById(sessionId: String): Int

    @Query("SELECT session_id FROM chat_sessions ORDER BY last_active_at DESC LIMIT -1 OFFSET :keepLatest")
    suspend fun getOldestSessionIdsBeyondLimit(keepLatest: Int): List<String>

    @Query("SELECT * FROM chat_sessions ORDER BY last_active_at DESC LIMIT 20")
    fun observeRecentSessions(): Flow<List<ChatSessionEntity>>

    // ---- Separate Memory Facts Table + FTS4 Search ----

    @Query(
        """
        INSERT INTO memory_facts (category, fact_key, fact_value, confidence, is_pinned, source_message_id, updated_at)
        VALUES (:category, :factKey, :factValue, :confidence, :isPinned, :sourceMessageId, :updatedAt)
        ON CONFLICT(fact_key) DO UPDATE SET
            category = excluded.category,
            fact_value = excluded.fact_value,
            confidence = excluded.confidence,
            is_pinned = CASE WHEN excluded.is_pinned = 1 THEN 1 ELSE memory_facts.is_pinned END,
            source_message_id = COALESCE(excluded.source_message_id, memory_facts.source_message_id),
            updated_at = excluded.updated_at
        """
    )
    suspend fun upsertFact(
        category: String,
        factKey: String,
        factValue: String,
        confidence: Float,
        isPinned: Boolean,
        sourceMessageId: Long?,
        updatedAt: Long
    )

    @Query("SELECT * FROM memory_facts WHERE fact_key = :factKey LIMIT 1")
    suspend fun getFactByKey(factKey: String): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts ORDER BY is_pinned DESC, updated_at DESC LIMIT :limit")
    suspend fun listAllFacts(limit: Int = 50): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE is_pinned = 1 ORDER BY updated_at DESC LIMIT 16")
    suspend fun getPinnedFacts(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getRecentFacts(limit: Int = 20): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY is_pinned DESC, updated_at DESC")
    fun observeAllFacts(): Flow<List<MemoryFactEntity>>

    @Query(
        """
        SELECT memory_facts.* FROM memory_facts
        JOIN memory_facts_fts ON memory_facts.id = memory_facts_fts.rowid
        WHERE memory_facts_fts MATCH :ftsQuery
        ORDER BY memory_facts.is_pinned DESC, memory_facts.updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun searchFactsFts(ftsQuery: String, limit: Int = 10): List<MemoryFactEntity>

    @Query(
        """
        SELECT * FROM memory_facts
        WHERE (:category IS NULL OR category = :category)
          AND (LOWER(fact_key) LIKE '%' || LOWER(:keyword) || '%'
            OR LOWER(fact_value) LIKE '%' || LOWER(:keyword) || '%')
        ORDER BY is_pinned DESC, updated_at DESC
        LIMIT :limit
        """
    )
    suspend fun searchFactsLike(keyword: String, category: String? = null, limit: Int = 10): List<MemoryFactEntity>

    @Query("DELETE FROM memory_facts WHERE fact_key = :factKey")
    suspend fun deleteFactByKey(factKey: String): Int

    @Query("DELETE FROM memory_facts")
    suspend fun clearAllFacts(): Int

    @Query("SELECT fact_key FROM memory_facts ORDER BY updated_at DESC LIMIT 60")
    suspend fun getExistingFactKeys(): List<String>

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()

    @Query("DELETE FROM conversation_summaries")
    suspend fun clearAllSummaries()

    @Query("DELETE FROM chat_sessions")
    suspend fun clearAllSessions()

    // ---- Scheduled Voice Reminders ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ScheduledReminderEntity): Long

    @Query("SELECT * FROM scheduled_reminders WHERE status = 'pending' ORDER BY trigger_at_ms ASC")
    suspend fun getPendingReminders(): List<ScheduledReminderEntity>

    @Query("SELECT * FROM scheduled_reminders ORDER BY CASE WHEN status = 'pending' THEN 0 ELSE 1 END, trigger_at_ms ASC")
    fun observeAllReminders(): Flow<List<ScheduledReminderEntity>>

    @Query("DELETE FROM scheduled_reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Long): Int

    @Query("SELECT * FROM scheduled_reminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: Long): ScheduledReminderEntity?

    @Query("UPDATE scheduled_reminders SET status = :status WHERE id = :id")
    suspend fun updateReminderStatus(id: Long, status: String): Int

    // ---- Autonomous Tasks & Continuous Loops ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutonomousTask(task: AutonomousTaskEntity): Long

    @androidx.room.Update
    suspend fun updateAutonomousTask(task: AutonomousTaskEntity): Int

    @Query("SELECT * FROM autonomous_tasks WHERE id = :id LIMIT 1")
    suspend fun getAutonomousTaskById(id: Long): AutonomousTaskEntity?

    @Query("SELECT * FROM autonomous_tasks WHERE status = 'ACTIVE' ORDER BY created_at DESC")
    suspend fun getActiveAutonomousTasks(): List<AutonomousTaskEntity>

    @Query("SELECT * FROM autonomous_tasks WHERE status IN ('ACTIVE', 'WAITING_UNLOCK') ORDER BY created_at DESC")
    suspend fun getRunnableOrWaitingAutonomousTasks(): List<AutonomousTaskEntity>

    @Query("SELECT * FROM autonomous_tasks ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 WHEN status = 'WAITING_UNLOCK' THEN 1 WHEN status = 'PAUSED' THEN 2 ELSE 3 END, updated_at DESC")
    suspend fun getAllAutonomousTasks(): List<AutonomousTaskEntity>

    @Query("SELECT * FROM autonomous_tasks ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 WHEN status = 'WAITING_UNLOCK' THEN 1 WHEN status = 'PAUSED' THEN 2 ELSE 3 END, updated_at DESC")
    fun observeAllAutonomousTasks(): Flow<List<AutonomousTaskEntity>>

    @Query("SELECT * FROM autonomous_tasks WHERE status = 'ACTIVE' ORDER BY updated_at DESC")
    fun observeActiveAutonomousTasks(): Flow<List<AutonomousTaskEntity>>

    @Query("UPDATE autonomous_tasks SET status = :status, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateAutonomousTaskStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM autonomous_tasks WHERE id = :id")
    suspend fun deleteAutonomousTaskById(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutonomousTaskLog(log: AutonomousTaskLogEntity): Long

    @Query("SELECT * FROM autonomous_task_logs WHERE task_id = :taskId ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentLogsForTask(taskId: Long, limit: Int = 20): List<AutonomousTaskLogEntity>

    @Query("SELECT * FROM autonomous_task_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecentAutonomousLogs(limit: Int = 60): Flow<List<AutonomousTaskLogEntity>>

    @Query("DELETE FROM autonomous_task_logs WHERE task_id = :taskId")
    suspend fun deleteLogsForTask(taskId: Long): Int

    @Query("DELETE FROM autonomous_task_logs WHERE task_id = :taskId AND id NOT IN (SELECT id FROM autonomous_task_logs WHERE task_id = :taskId ORDER BY id DESC LIMIT :keepCount)")
    suspend fun pruneOldLogsForTask(taskId: Long, keepCount: Int = 30): Int
}
