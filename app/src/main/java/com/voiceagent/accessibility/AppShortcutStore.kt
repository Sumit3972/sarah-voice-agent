package com.voiceagent.accessibility

import com.voiceagent.memory.MemoryDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AppUiShortcut(
    val packageName: String,
    val inputViewId: String = "",
    val sendViewId: String = "",
    val sendLabel: String = "",
    val replyMode: String = "swipe_right"
)

/**
 * Self-evolving per-package UI shortcut store backed by existing MemoryDao (`ui_shortcut` category)
 * with an in-memory cache so repeat chat ticks skip vision discovery.
 */
class AppShortcutStore(
    private val dao: MemoryDao? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, AppUiShortcut>()

    suspend fun get(packageName: String): AppUiShortcut? {
        if (packageName.isBlank()) return null
        cache[packageName]?.let { return it }
        val fact = dao?.getFactByKey(factKey(packageName)) ?: return null
        val parsed = runCatching { json.decodeFromString<AppUiShortcut>(fact.factValue) }.getOrNull()
        if (parsed != null) {
            cache[packageName] = parsed
        }
        return parsed
    }

    suspend fun recordSendShortcut(
        packageName: String,
        inputViewId: String? = null,
        sendViewId: String? = null,
        sendLabel: String? = null,
        replyMode: String? = null
    ) {
        if (packageName.isBlank()) return
        val existing = get(packageName) ?: AppUiShortcut(packageName = packageName)
        val updated = existing.copy(
            inputViewId = inputViewId?.takeIf { it.isNotBlank() } ?: existing.inputViewId,
            sendViewId = sendViewId?.takeIf { it.isNotBlank() } ?: existing.sendViewId,
            sendLabel = sendLabel?.takeIf { it.isNotBlank() } ?: existing.sendLabel,
            replyMode = replyMode?.takeIf { it.isNotBlank() } ?: existing.replyMode
        )
        cache[packageName] = updated
        dao?.let { d ->
            runCatching {
                d.upsertFact(
                    category = "ui_shortcut",
                    factKey = factKey(packageName),
                    factValue = json.encodeToString(updated),
                    confidence = 0.9f,
                    isPinned = false,
                    sourceMessageId = null,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    private fun factKey(packageName: String): String = "ui_shortcut.${packageName.trim()}"
}
