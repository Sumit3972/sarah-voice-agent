package com.voiceagent.memory

import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.llm.SystemPromptProvider

data class ContextTurn(
    val role: String, // "user" | "assistant"
    val content: String
)

data class AssembledLlmContext(
    val sessionId: String,
    val systemPrompt: String,
    val factsSummary: String,
    val recentSessionsDigest: String,
    val rollingSummary: String,
    val turns: List<ContextTurn>,
    val estimatedTokens: Int,
    val factsInjectedCount: Int
)

class ContextWindowManager(
    private val dao: MemoryDao,
    private val sessionManager: SessionManager? = null
) {
    companion object {
        fun estimateTokens(text: String): Int {
            if (text.isBlank()) return 0
            return (text.length + 3) / 4
        }

        private val STOP_WORDS = setOf(
            "the", "and", "for", "that", "this", "with", "you", "your", "are", "was",
            "were", "what", "when", "where", "how", "who", "why", "can", "could",
            "would", "should", "tell", "about", "sarah", "hey", "please", "have",
            "has", "had", "from", "into", "just", "like", "know"
        )

        fun formatRelativeTime(timestampMs: Long): String {
            val diffSec = ((System.currentTimeMillis() - timestampMs) / 1000L).coerceAtLeast(0L)
            return when {
                diffSec < 60L -> "just now"
                diffSec < 3600L -> "${diffSec / 60L}m ago"
                diffSec < 86400L -> "${diffSec / 3600L}h ago"
                else -> "${diffSec / 86400L}d ago"
            }
        }
    }

    suspend fun recordTurn(
        role: String,
        content: String,
        modelUsed: String,
        sessionId: String = sessionManager?.activeSessionId() ?: "default"
    ): Long {
        val tokens = estimateTokens(content)
        val id = dao.insertMessage(
            ChatMessageEntity(
                sessionId = sessionId,
                role = role,
                content = content.trim(),
                modelUsed = modelUsed,
                tokenEstimate = tokens,
                createdAt = System.currentTimeMillis()
            )
        )
        sessionManager?.touchTurn(sessionId, role, content)
        return id
    }

    suspend fun buildContextWindow(
        currentQuery: String,
        sessionId: String = sessionManager?.activeSessionId() ?: "default",
        maxContextTokens: Int = PipelineConfig.MAX_ACTIVE_CONTEXT_TOKENS
    ): AssembledLlmContext {
        val baseSystem = SystemPromptProvider.SARAH_VOICE_PROMPT
        var usedTokens = estimateTokens(baseSystem)

        // 1. Pinned facts + FTS4-matched relevant facts from memory_facts table
        val pinned = dao.getPinnedFacts()
        val ftsMatched = queryRelevantFacts(currentQuery)
        val mergedFacts = (pinned + ftsMatched).distinctBy { it.factKey }

        val factsBuilder = StringBuilder()
        var factsTokens = 0
        var factsCount = 0
        for (fact in mergedFacts) {
            val line = "- [${fact.category}] ${fact.factKey}: ${fact.factValue}\n"
            val t = estimateTokens(line)
            if (factsTokens + t > PipelineConfig.FACTS_BUDGET_TOKENS) break
            factsBuilder.append(line)
            factsTokens += t
            factsCount++
        }
        val factsBlock = factsBuilder.toString().trim()
        usedTokens += factsTokens

        // 2. Last 5 closed session 1-2 line digests (for cross-session continuity without context flooding)
        val pastSessions = dao.getRecentPastSessions(excludeSessionId = sessionId, limit = 5)
        val sessionsBuilder = StringBuilder()
        var sessionsTokens = 0
        for (sess in pastSessions) {
            val desc = sess.digest.ifBlank { sess.title }
            val line = "- ${sess.sessionId} (${formatRelativeTime(sess.lastActiveAt)}, ${sess.turnCount} turns) \"${sess.title}\": $desc\n"
            val t = estimateTokens(line)
            if (sessionsTokens + t > PipelineConfig.RECENT_SESSION_DIGESTS_BUDGET_TOKENS) break
            sessionsBuilder.append(line)
            sessionsTokens += t
        }
        val recentSessionsBlock = sessionsBuilder.toString().trim()
        usedTokens += sessionsTokens

        // 3. Current session rolling summary (if active session exceeded COMPACTION_TRIGGER_TURNS)
        val summary = dao.getLatestSummary(sessionId)
        var summaryText = ""
        if (summary != null && summary.summaryText.isNotBlank()) {
            val maxChars = PipelineConfig.SUMMARY_BUDGET_TOKENS * 4
            summaryText = if (summary.summaryText.length > maxChars) {
                summary.summaryText.take(maxChars) + "..."
            } else {
                summary.summaryText
            }
            usedTokens += estimateTokens(summaryText)
        }

        val fullSystemPrompt = buildString {
            append(baseSystem)
            append("\n\n[ACTIVE SESSION ID]\n$sessionId")
            if (factsBlock.isNotEmpty()) {
                append("\n\n[KNOWN USER FACTS]\n")
                append(factsBlock)
            }
            if (recentSessionsBlock.isNotEmpty()) {
                append("\n\n[RECENT PAST SESSIONS (Call session_store action='read' with session_id for full transcript)]\n")
                append(recentSessionsBlock)
            }
            if (summaryText.isNotEmpty()) {
                append("\n\n[CURRENT SESSION EARLIER SUMMARY]\n")
                append(summaryText)
            }
        }

        // 4. Sliding window of recent turns from ONLY the current isolated session
        val remainingBudget = (maxContextTokens - usedTokens - estimateTokens(currentQuery) - 256)
            .coerceAtLeast(600)

        val recentDesc = dao.getRecentMessagesDesc(sessionId, limit = 80)
        val chosenDesc = mutableListOf<ContextTurn>()
        var turnsTokens = 0

        for (msg in recentDesc) {
            if (msg.role != "user" && msg.role != "assistant") continue
            val t = msg.tokenEstimate.takeIf { it > 0 } ?: estimateTokens(msg.content)
            if (turnsTokens + t > remainingBudget) break
            chosenDesc.add(ContextTurn(role = msg.role, content = msg.content))
            turnsTokens += t
        }

        val chronological = chosenDesc.reversed()
        val totalTokens = usedTokens + turnsTokens + estimateTokens(currentQuery)

        Log.d(
            PipelineConfig.LOG_TAG,
            "[ContextWindow] session=$sessionId turns=${chronological.size} facts=$factsCount pastSess=${pastSessions.size} estTokens=$totalTokens/$maxContextTokens (hardLimit=${PipelineConfig.MERCURY_3_HARD_CONTEXT_LIMIT_TOKENS})"
        )

        return AssembledLlmContext(
            sessionId = sessionId,
            systemPrompt = fullSystemPrompt,
            factsSummary = factsBlock,
            recentSessionsDigest = recentSessionsBlock,
            rollingSummary = summaryText,
            turns = chronological,
            estimatedTokens = totalTokens,
            factsInjectedCount = factsCount
        )
    }

    private suspend fun queryRelevantFacts(query: String): List<MemoryFactEntity> {
        val cleanTerms = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
            .take(6)

        if (cleanTerms.isEmpty()) {
            return dao.getRecentFacts(limit = 8)
        }

        val ftsQuery = cleanTerms.joinToString(" OR ") { "$it*" }
        return try {
            val ftsHits = dao.searchFactsFts(ftsQuery, limit = 8)
            if (ftsHits.isNotEmpty()) ftsHits else dao.getRecentFacts(limit = 6)
        } catch (_: Exception) {
            val likeHits = mutableListOf<MemoryFactEntity>()
            for (term in cleanTerms.take(3)) {
                likeHits += dao.searchFactsLike(term, limit = 3)
            }
            likeHits.distinctBy { it.factKey }.ifEmpty { dao.getRecentFacts(limit = 6) }
        }
    }

    suspend fun maybeCompactWith(
        summarizer: suspend (String) -> String?,
        sessionId: String = sessionManager?.activeSessionId() ?: "default"
    ) {
        val latest = dao.getLatestSummary(sessionId)
        val watermark = latest?.coveredUpToMessageId ?: 0L
        val uncompactedCount = dao.countUncompactedMessages(sessionId, watermark)
        if (uncompactedCount < PipelineConfig.COMPACTION_TRIGGER_TURNS) return

        val uncompacted = dao.getMessagesAfterAsc(sessionId, watermark)
        if (uncompacted.size <= PipelineConfig.COMPACTION_BATCH_TURNS) return

        val batch = uncompacted.take(PipelineConfig.COMPACTION_BATCH_TURNS)
        val promptInput = buildString {
            if (latest != null && latest.summaryText.isNotBlank()) {
                append("Existing summary:\n${latest.summaryText}\n\n")
            }
            append("New conversation turns to merge concisely:\n")
            for (m in batch) {
                append("${m.role}: ${m.content}\n")
            }
        }

        val newSummary = summarizer(promptInput)?.trim()
        if (!newSummary.isNullOrBlank()) {
            val upToId = batch.last().id
            dao.insertSummary(
                ConversationSummaryEntity(
                    sessionId = sessionId,
                    summaryText = newSummary,
                    coveredUpToMessageId = upToId,
                    tokenEstimate = estimateTokens(newSummary),
                    createdAt = System.currentTimeMillis()
                )
            )
            Log.i(PipelineConfig.LOG_TAG, "[ContextWindow] Compacted ${batch.size} turns in $sessionId up to msg #$upToId")
        }
    }
}
