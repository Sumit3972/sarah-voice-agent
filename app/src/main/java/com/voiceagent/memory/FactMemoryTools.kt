package com.voiceagent.memory

import android.util.Log
import com.voiceagent.config.PipelineConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tier 3 Tools: Unified AI-Editable Long-Term Memory Facts (`memory_facts`) and Isolated Session Store (`session_store`).
 * Also preserves execution compatibility for legacy Phase 1 fact tool names if invoked.
 */
class FactMemoryTools(
    private val dao: MemoryDao,
    private val sessionManager: SessionManager? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun memoryFactsOpenAiSpec(): JsonObject = openAiTool(
        name = "memory_facts",
        description = "Full CRUD tool for durable user memory facts (names, preferences, contact_alias, routines, notes). Actions: list, search, add, update (can rename fact_key or edit value/category/pin), delete.",
        properties = buildJsonObject {
            put("action", prop("string", "One of: list, search, add, update, delete"))
            put("category", prop("string", "user_profile, preference, relationship, routine, contact_alias, world_fact, or task_note"))
            put("fact_key", prop("string", "Normalized key e.g. user.favorite_drink or contact_alias.mom"))
            put("new_fact_key", prop("string", "Optional new normalized key when renaming in update action"))
            put("fact_value", prop("string", "Fact value to store or update"))
            put("is_pinned", prop("boolean", "Keep pinned in active context"))
            put("query", prop("string", "Search query keywords for action='search'"))
        },
        required = listOf("action")
    )

    fun sessionStoreOpenAiSpec(): JsonObject = openAiTool(
        name = "session_store",
        description = "Manage isolated conversation sessions in SQLite. Actions: list_recent, read (full transcript by session_id or keyword search), save_summary, edit_metadata, delete, start_new.",
        properties = buildJsonObject {
            put("action", prop("string", "One of: list_recent, read, save_summary, edit_metadata, delete, start_new"))
            put("session_id", prop("string", "Target session ID; omit on save_summary/edit_metadata to update active session"))
            put("query", prop("string", "Keyword search across past session titles and digests"))
            put("title", prop("string", "Updated short session title"))
            put("digest", prop("string", "Updated 1-2 sentence session summary"))
            put("limit", prop("integer", "Max items/turns to return"))
        },
        required = listOf("action")
    )

    fun memoryFactsGeminiSpec(): JsonObject = buildJsonObject {
        put("name", "memory_facts")
        put("description", "Full CRUD tool for durable user memory facts (names, preferences, contact_alias, routines, notes). Actions: list, search, add, update, delete.")
        put("parameters", buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("action", prop("STRING", "One of: list, search, add, update, delete"))
                put("category", prop("STRING", "user_profile, preference, relationship, routine, contact_alias, world_fact, or task_note"))
                put("fact_key", prop("STRING", "Normalized key"))
                put("new_fact_key", prop("STRING", "Optional renamed key"))
                put("fact_value", prop("STRING", "Fact value"))
                put("is_pinned", prop("BOOLEAN", "Keep pinned in prompt"))
                put("query", prop("STRING", "Search query"))
            })
            put("required", buildJsonArray { add(JsonPrimitive("action")) })
        })
    }

    fun sessionStoreGeminiSpec(): JsonObject = buildJsonObject {
        put("name", "session_store")
        put("description", "Manage isolated conversation sessions in SQLite. Actions: list_recent, read, save_summary, edit_metadata, delete, start_new.")
        put("parameters", buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("action", prop("STRING", "One of: list_recent, read, save_summary, edit_metadata, delete, start_new"))
                put("session_id", prop("STRING", "Target session ID"))
                put("query", prop("STRING", "Keyword search across past sessions"))
                put("title", prop("STRING", "Session title"))
                put("digest", prop("STRING", "Session digest"))
                put("limit", prop("INTEGER", "Max rows/turns to return"))
            })
            put("required", buildJsonArray { add(JsonPrimitive("action")) })
        })
    }

    fun openAiToolsJson(): JsonArray = buildJsonArray {
        add(memoryFactsOpenAiSpec())
        add(sessionStoreOpenAiSpec())
    }

    fun geminiFunctionDeclarationsJson(): JsonArray = buildJsonArray {
        add(memoryFactsGeminiSpec())
        add(sessionStoreGeminiSpec())
    }

    suspend fun execute(name: String, argsJson: String, sourceMessageId: Long? = null): String {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return execute(name, args, sourceMessageId)
    }

    suspend fun execute(name: String, args: JsonObject, sourceMessageId: Long? = null): String {
        return when (name) {
            "memory_facts" -> executeMemoryFacts(args, sourceMessageId)
            "session_store" -> executeSessionStore(args)
            // Legacy Phase 1 compatibility aliases
            "save_memory_fact" -> addOrUpdateFact(args, sourceMessageId, isUpdate = false)
            "search_memory_facts" -> searchFacts(args)
            "delete_memory_fact" -> deleteFact(args)
            else -> """{"ok":false,"error":"Unknown memory/session tool: $name"}"""
        }
    }

    private suspend fun executeMemoryFacts(args: JsonObject, sourceMessageId: Long?): String {
        val action = args["action"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        return when (action) {
            "list" -> {
                val category = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 60)
                val rows = dao.listAllFacts(limit).let { list ->
                    if (category != null) list.filter { it.category.equals(category, true) } else list
                }
                buildJsonObject {
                    put("ok", true)
                    put("count", rows.size)
                    put("facts", buildJsonArray {
                        rows.forEach { add(factToJson(it)) }
                    })
                }.toString()
            }
            "search" -> searchFacts(args)
            "add" -> addOrUpdateFact(args, sourceMessageId, isUpdate = false)
            "update" -> addOrUpdateFact(args, sourceMessageId, isUpdate = true)
            "delete" -> deleteFact(args)
            else -> """{"ok":false,"error":"Unsupported memory_facts action='$action'. Use list|search|add|update|delete."}"""
        }
    }

    private suspend fun addOrUpdateFact(args: JsonObject, sourceMessageId: Long?, isUpdate: Boolean): String {
        val oldKey = normalizeKey(args["fact_key"]?.jsonPrimitive?.content?.trim().orEmpty())
        val newKey = normalizeKey(args["new_fact_key"]?.jsonPrimitive?.content?.trim().orEmpty())
        val existing = if (oldKey.isNotBlank()) dao.getFactByKey(oldKey) else null
        val targetKey = newKey.ifBlank { oldKey }
        val category = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.category ?: "world_fact"
        val factValue = args["fact_value"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.factValue.orEmpty()
        val isPinned = args["is_pinned"]?.jsonPrimitive?.booleanOrNull ?: existing?.isPinned ?: false

        if (targetKey.isBlank() || factValue.isBlank()) {
            return """{"ok":false,"error":"fact_key and fact_value are required"}"""
        }
        if (isUpdate && newKey.isNotBlank() && oldKey.isNotBlank() && newKey != oldKey) {
            dao.deleteFactByKey(oldKey)
        }
        dao.upsertFact(
            category = category,
            factKey = targetKey,
            factValue = factValue,
            confidence = 1.0f,
            isPinned = isPinned,
            sourceMessageId = sourceMessageId,
            updatedAt = System.currentTimeMillis()
        )
        Log.i(PipelineConfig.LOG_TAG, "[MemoryTool:${if (isUpdate) "update" else "add"}] $targetKey = $factValue")
        return buildJsonObject {
            put("ok", true)
            put("action", if (isUpdate) "updated" else "saved")
            put("fact_key", targetKey)
            put("fact_value", factValue)
            put("category", category)
            put("is_pinned", isPinned)
        }.toString()
    }

    private suspend fun searchFacts(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val category = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
        val hits = if (query.isBlank()) {
            dao.getRecentFacts(limit = 10)
        } else {
            val ftsQuery = query.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" OR ") { "$it*" }
            val fts = runCatching {
                if (ftsQuery.isNotBlank()) dao.searchFactsFts(ftsQuery, limit = 10) else emptyList()
            }.getOrDefault(emptyList())
            val filtered = if (category != null) fts.filter { it.category == category } else fts
            filtered.ifEmpty { dao.searchFactsLike(query, category, limit = 10) }
        }
        Log.i(PipelineConfig.LOG_TAG, "[MemoryTool:search] query='$query' -> ${hits.size} hit(s)")
        return buildJsonObject {
            put("ok", true)
            put("matches", buildJsonArray { hits.forEach { add(factToJson(it)) } })
        }.toString()
    }

    private suspend fun deleteFact(args: JsonObject): String {
        val factKey = normalizeKey(args["fact_key"]?.jsonPrimitive?.content?.trim().orEmpty())
        val deleted = if (factKey.isNotBlank()) dao.deleteFactByKey(factKey) else 0
        Log.i(PipelineConfig.LOG_TAG, "[MemoryTool:delete] key=$factKey deleted=$deleted")
        return buildJsonObject {
            put("ok", true)
            put("deleted_rows", deleted)
            put("fact_key", factKey)
        }.toString()
    }

    private suspend fun executeSessionStore(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
        val targetId = args["session_id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            ?: sessionManager?.activeSessionId()
            ?: "default"

        return when (action) {
            "list_recent" -> {
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
                val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
                val sessions = if (query.isNotBlank()) {
                    dao.searchSessionsByKeyword(query, limit)
                } else {
                    dao.getRecentSessions(limit)
                }
                buildJsonObject {
                    put("ok", true)
                    put("active_session_id", sessionManager?.activeSessionId().orEmpty())
                    put("sessions", buildJsonArray {
                        sessions.forEach { s ->
                            add(buildJsonObject {
                                put("session_id", s.sessionId)
                                put("title", s.title)
                                put("digest", s.digest)
                                put("turn_count", s.turnCount)
                                put("last_active_ago", ContextWindowManager.formatRelativeTime(s.lastActiveAt))
                                put("is_archived", s.isArchived)
                            })
                        }
                    })
                }.toString()
            }

            "read" -> {
                val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
                val resolvedId = if (args["session_id"]?.jsonPrimitive?.content.isNullOrBlank() && query.isNotBlank()) {
                    dao.searchSessionsByKeyword(query, 1).firstOrNull()?.sessionId ?: targetId
                } else {
                    targetId
                }
                val session = dao.getSessionById(resolvedId)
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 40).coerceIn(1, 100)
                val messages = dao.getMessagesForSessionAsc(resolvedId, limit)
                buildJsonObject {
                    put("ok", session != null || messages.isNotEmpty())
                    put("session_id", resolvedId)
                    put("title", session?.title ?: "Session")
                    put("digest", session?.digest.orEmpty())
                    put("messages", buildJsonArray {
                        messages.forEach { m ->
                            add(buildJsonObject {
                                put("role", m.role)
                                put("content", m.content)
                                put("model", m.modelUsed)
                            })
                        }
                    })
                }.toString()
            }

            "save_summary", "edit_metadata" -> {
                val title = args["title"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val digest = args["digest"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                val updated = dao.updateSessionMetadata(targetId, title, digest)
                if (updated == 0) {
                    dao.upsertSession(
                        ChatSessionEntity(
                            sessionId = targetId,
                            title = title ?: "Saved session",
                            digest = digest.orEmpty()
                        )
                    )
                }
                buildJsonObject {
                    put("ok", true)
                    put("session_id", targetId)
                    if (title != null) put("title", title)
                    if (digest != null) put("digest", digest)
                }.toString()
            }

            "delete" -> {
                val msgDel = dao.deleteMessagesForSession(targetId)
                dao.deleteSummariesForSession(targetId)
                val sessDel = dao.deleteSessionById(targetId)
                buildJsonObject {
                    put("ok", true)
                    put("deleted_session_id", targetId)
                    put("deleted_sessions", sessDel)
                    put("deleted_messages", msgDel)
                }.toString()
            }

            "start_new" -> {
                val newId = sessionManager?.startNewSessionNow("tool_command")
                    ?: "sess_${System.currentTimeMillis()}"
                buildJsonObject {
                    put("ok", true)
                    put("new_session_id", newId)
                    put("message", "Started a fresh isolated conversation session.")
                }.toString()
            }

            else -> """{"ok":false,"error":"Unsupported session_store action='$action'. Use list_recent|read|save_summary|edit_metadata|delete|start_new."}"""
        }
    }

    private fun factToJson(f: MemoryFactEntity): JsonObject = buildJsonObject {
        put("category", f.category)
        put("fact_key", f.factKey)
        put("fact_value", f.factValue)
        put("is_pinned", f.isPinned)
    }

    private fun normalizeKey(raw: String): String {
        return raw.lowercase()
            .replace(Regex("[^a-z0-9._]+"), "_")
            .trim('_', '.')
    }

    private fun openAiTool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String>
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            })
        })
    }

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }
}
