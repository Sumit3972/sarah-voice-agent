package com.voiceagent.tools

import com.voiceagent.autonomous.SwarmOrchestrator
import com.voiceagent.autonomous.SwarmSubtask
import com.voiceagent.autonomous.SwarmWorkerRole
import com.voiceagent.config.asArrOrNull
import com.voiceagent.config.asObjOrNull
import com.voiceagent.config.asStrOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SwarmTool {
    @Volatile
    var orchestrator: SwarmOrchestrator? = null

    suspend fun execute(args: JsonObject): String {
        val orch = orchestrator
            ?: return """{"ok":false,"error":"SwarmOrchestrator not initialized"}"""

        val tasksArr = args["tasks"].asArrOrNull()
        if (tasksArr.isNullOrEmpty()) {
            return """{"ok":false,"error":"tasks array is required for swarm_dispatch"}"""
        }

        val subtasks = tasksArr.mapIndexedNotNull { idx, el ->
            val obj = el.asObjOrNull() ?: return@mapIndexedNotNull null
            val goal = obj["goal"].asStrOrNull()?.trim().orEmpty()
            if (goal.isBlank()) return@mapIndexedNotNull null
            val id = obj["id"].asStrOrNull()?.trim().orEmpty().ifBlank { "task_${idx + 1}" }
            val role = SwarmWorkerRole.fromString(obj["role"].asStrOrNull())
            val thinking = obj["thinking_level"].asStrOrNull()?.trim()?.uppercase()?.takeIf {
                it in setOf("LOW", "MEDIUM", "HIGH")
            }
            SwarmSubtask(
                id = id,
                role = role,
                goal = goal,
                thinkingLevel = thinking
            )
        }.take(6)

        if (subtasks.isEmpty()) {
            return """{"ok":false,"error":"No valid subtasks with non-empty goal found"}"""
        }

        val t0 = System.currentTimeMillis()
        val results = orch.runParallel(subtasks)
        val totalMs = System.currentTimeMillis() - t0
        val allOk = results.all { it.ok }

        return buildJsonObject {
            put("ok", allOk || results.any { it.ok })
            put("worker_count", results.size)
            put("total_elapsed_ms", totalMs)
            put("results", buildJsonArray {
                results.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.id)
                        put("role", r.role.name.lowercase())
                        put("ok", r.ok)
                        put("elapsed_ms", r.elapsedMs)
                        if (r.toolsUsed.isNotEmpty()) {
                            put("tools_used", r.toolsUsed.joinToString(","))
                        }
                        if (r.output.isNotBlank()) {
                            put("output", r.output)
                        }
                        if (!r.error.isNullOrBlank()) {
                            put("error", r.error)
                        }
                    })
                }
            })
        }.toString()
    }
}
