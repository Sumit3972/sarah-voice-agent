package com.voiceagent.memory

import android.util.Log
import com.voiceagent.config.PipelineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Manages clean per-session isolation:
 * - Always creates a fresh session on cold app start
 * - Rotates to a fresh session after PipelineConfig.SESSION_IDLE_TIMEOUT_MS (10 min)
 * - Supports explicit user/tool rotation ("start a new session")
 * - Auto-distills closing sessions into a 1-2 line title + digest + durable memory_facts
 * - Retains raw chat_messages for the latest 50 sessions while keeping digests & facts forever
 */
class SessionManager(
    private val dao: MemoryDao,
    private val asyncFactExtractor: AsyncFactExtractor
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    @Volatile
    private var currentSessionId: String = generateSessionId()

    @Volatile
    private var sessionStartedAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var lastActivityAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var sessionInitializedInDb: Boolean = false

    fun activeSessionId(): String = currentSessionId

    suspend fun resolveActiveSessionId(
        geminiQuickCall: (suspend (systemPrompt: String, userPrompt: String) -> String?)? = null
    ): String = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!sessionInitializedInDb) {
            closeStaleOpenSessionsExcept(currentSessionId, now, geminiQuickCall)
            dao.upsertSession(
                ChatSessionEntity(
                    sessionId = currentSessionId,
                    title = "Current conversation",
                    digest = "",
                    startedAt = sessionStartedAtMs,
                    lastActiveAt = now,
                    endedAt = null,
                    turnCount = 0,
                    isArchived = false
                )
            )
            sessionInitializedInDb = true
            lastActivityAtMs = now
            return@withLock currentSessionId
        }

        if ((now - lastActivityAtMs) >= PipelineConfig.SESSION_IDLE_TIMEOUT_MS) {
            val previousId = currentSessionId
            rotateSessionLocked(
                oldSessionId = previousId,
                now = now,
                reason = "10m_idle_timeout",
                geminiQuickCall = geminiQuickCall
            )
        }
        lastActivityAtMs = now
        return@withLock currentSessionId
    }

    suspend fun startNewSessionNow(
        reason: String = "manual",
        geminiQuickCall: (suspend (systemPrompt: String, userPrompt: String) -> String?)? = null
    ): String = mutex.withLock {
        val now = System.currentTimeMillis()
        val oldId = currentSessionId
        rotateSessionLocked(oldId, now, reason, geminiQuickCall)
        return@withLock currentSessionId
    }

    suspend fun switchToSession(sessionId: String) {
        mutex.withLock {
            val clean = sessionId.trim()
            if (clean.isEmpty()) return@withLock
            val now = System.currentTimeMillis()
            currentSessionId = clean
            sessionInitializedInDb = true
            lastActivityAtMs = now
            dao.updateSessionMetadata(clean, title = null, digest = null, updatedAt = now)
            Log.i(PipelineConfig.LOG_TAG, "[SessionManager] Switched to session: $clean")
        }
    }

    suspend fun touchTurn(sessionId: String, role: String, previewText: String) = mutex.withLock {
        val now = System.currentTimeMillis()
        lastActivityAtMs = now
        val existing = dao.getSessionById(sessionId) ?: ChatSessionEntity(
            sessionId = sessionId,
            startedAt = now,
            lastActiveAt = now
        )
        val cleanPreview = previewText.replace(Regex("\\s+"), " ").trim().take(54)
        val updatedTitle = if (
            (existing.title == "New conversation" || existing.title == "Current conversation") &&
            role == "user" && cleanPreview.isNotBlank()
        ) {
            cleanPreview
        } else {
            existing.title
        }
        val updatedDigest = if (existing.digest.isBlank() && role == "user" && cleanPreview.isNotBlank()) {
            "Started with: $cleanPreview"
        } else {
            existing.digest
        }
        dao.upsertSession(
            existing.copy(
                title = updatedTitle,
                digest = updatedDigest,
                lastActiveAt = now,
                turnCount = existing.turnCount + 1
            )
        )
    }

    private suspend fun rotateSessionLocked(
        oldSessionId: String,
        now: Long,
        reason: String,
        geminiQuickCall: (suspend (systemPrompt: String, userPrompt: String) -> String?)?
    ) {
        val oldEntity = dao.getSessionById(oldSessionId)
        if (oldEntity != null && oldEntity.turnCount > 0) {
            dao.markSessionEnded(oldSessionId, now)
            scheduleDistillAndPrune(oldSessionId, geminiQuickCall)
        } else if (oldEntity != null) {
            dao.deleteSessionById(oldSessionId)
        }

        val fresh = generateSessionId()
        currentSessionId = fresh
        sessionStartedAtMs = now
        lastActivityAtMs = now
        sessionInitializedInDb = true
        dao.upsertSession(
            ChatSessionEntity(
                sessionId = fresh,
                title = "New conversation",
                digest = "",
                startedAt = now,
                lastActiveAt = now,
                endedAt = null,
                turnCount = 0,
                isArchived = false
            )
        )
        Log.i(PipelineConfig.LOG_TAG, "[SessionManager] Rotated session: $oldSessionId -> $fresh (reason=$reason)")
    }

    private suspend fun closeStaleOpenSessionsExcept(
        activeId: String,
        now: Long,
        geminiQuickCall: (suspend (systemPrompt: String, userPrompt: String) -> String?)?
    ) {
        val stale = dao.getOpenSessionsExcept(activeId)
        for (sess in stale) {
            if (sess.turnCount <= 0) {
                dao.deleteSessionById(sess.sessionId)
            } else {
                dao.markSessionEnded(sess.sessionId, now)
                if (sess.digest.isBlank() || sess.digest.startsWith("Started with:")) {
                    scheduleDistillAndPrune(sess.sessionId, geminiQuickCall)
                }
            }
        }
        pruneOldRawMessages()
    }

    private fun scheduleDistillAndPrune(
        sessionId: String,
        geminiQuickCall: (suspend (systemPrompt: String, userPrompt: String) -> String?)?
    ) {
        scope.launch {
            runCatching {
                val msgs = dao.getMessagesForSessionAsc(sessionId, limit = 80)
                if (msgs.isNotEmpty() && geminiQuickCall != null) {
                    val transcript = msgs.joinToString("\n") { "${it.role}: ${it.content}" }.take(6000)
                    val sys = """
                        Summarize this closed voice assistant session in strict plain format:
                        Line 1: TITLE: <3 to 6 words>
                        Line 2: DIGEST: <1 to 2 concise sentences capturing what was discussed and accomplished>
                    """.trimIndent()
                    val reply = geminiQuickCall(sys, transcript)?.trim().orEmpty()
                    val title = Regex("TITLE:\\s*(.+)", RegexOption.IGNORE_CASE).find(reply)?.groupValues?.get(1)?.trim()
                    val digest = Regex("DIGEST:\\s*(.+)", RegexOption.IGNORE_CASE).find(reply)?.groupValues?.get(1)?.trim()
                    if (!title.isNullOrBlank() || !digest.isNullOrBlank()) {
                        dao.updateSessionMetadata(sessionId, title, digest)
                        Log.i(PipelineConfig.LOG_TAG, "[SessionManager] Distilled closed session $sessionId: $title")
                    }
                    // Also capture any remaining durable user facts before raw session retires
                    val lastUser = msgs.lastOrNull { it.role == "user" }
                    val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
                    if (lastUser != null) {
                        asyncFactExtractor.extractAfterTurn(
                            userText = lastUser.content,
                            assistantReply = lastAssistant?.content.orEmpty(),
                            sourceMessageId = lastUser.id,
                            geminiQuickCall = geminiQuickCall
                        )
                    }
                }
                pruneOldRawMessages()
            }
        }
    }

    private suspend fun pruneOldRawMessages() {
        val expiredSessionIds = dao.getOldestSessionIdsBeyondLimit(PipelineConfig.MAX_RETAINED_RAW_SESSIONS)
        for (sid in expiredSessionIds) {
            val removed = dao.deleteMessagesForSession(sid)
            dao.deleteSummariesForSession(sid)
            val sess = dao.getSessionById(sid)
            if (sess != null && !sess.isArchived) {
                dao.upsertSession(sess.copy(isArchived = true))
            }
            if (removed > 0) {
                Log.d(PipelineConfig.LOG_TAG, "[SessionManager] Pruned $removed old raw messages for archived session $sid")
            }
        }
    }

    private fun generateSessionId(): String {
        val shortId = UUID.randomUUID().toString().substring(0, 6)
        return "sess_${System.currentTimeMillis()}_$shortId"
    }
}
