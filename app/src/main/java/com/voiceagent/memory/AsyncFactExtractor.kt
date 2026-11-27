package com.voiceagent.memory

import android.util.Log
import com.voiceagent.config.PipelineConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExtractedFactItem(
    val category: String = "world_fact",
    val fact_key: String,
    val fact_value: String,
    val confidence: Float = 0.9f,
    val is_pinned: Boolean = false
)

class AsyncFactExtractor(
    private val dao: MemoryDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun extractAfterTurn(
        userText: String,
        assistantReply: String,
        sourceMessageId: Long?,
        geminiQuickCall: suspend (systemPrompt: String, userPrompt: String) -> String?
    ) {
        if (userText.isBlank()) return
        try {
            val existingKeys = dao.getExistingFactKeys().joinToString(", ")
            val sys = """
                You extract ONLY durable, reusable user facts (names, preferences, relationships, routines, locations, goals, projects) from the latest conversation turn.
                Skip ephemeral questions like weather, jokes, or casual greetings.
                Return ONLY a valid JSON array of objects:
                [{"category":"user_profile|preference|relationship|routine|world_fact|task_note","fact_key":"normalized.dot_slug","fact_value":"concise value","confidence":0.95,"is_pinned":true}]
                If there are no new durable facts, return [].
            """.trimIndent()
            val prompt = buildString {
                if (existingKeys.isNotBlank()) append("Existing fact keys (reuse if updating): $existingKeys\n")
                append("User: $userText\n")
                append("Assistant: $assistantReply\n")
            }
            val raw = geminiQuickCall(sys, prompt)?.trim() ?: return
            val cleaned = raw
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (!cleaned.startsWith("[")) return

            val items = json.decodeFromString<List<ExtractedFactItem>>(cleaned)
            for (item in items) {
                if (item.fact_key.isBlank() || item.fact_value.isBlank() || item.confidence < 0.7f) continue
                val key = item.fact_key.lowercase().replace(Regex("[^a-z0-9._]+"), "_").trim('_', '.')
                dao.upsertFact(
                    category = item.category,
                    factKey = key,
                    factValue = item.fact_value.trim(),
                    confidence = item.confidence,
                    isPinned = item.is_pinned,
                    sourceMessageId = sourceMessageId,
                    updatedAt = System.currentTimeMillis()
                )
                Log.i(PipelineConfig.LOG_TAG, "[AsyncFactExtractor] Saved background fact: $key = ${item.fact_value}")
            }
        } catch (e: Exception) {
            Log.d(PipelineConfig.LOG_TAG, "[AsyncFactExtractor] Skipped/non-JSON output: ${e.message}")
        }
    }
}
