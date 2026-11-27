package com.voiceagent.autonomous

import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.llm.LlmStreamEvent
import com.voiceagent.llm.StreamingLlmRouter
import com.voiceagent.llm.SystemPromptProvider
import com.voiceagent.memory.AssembledLlmContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

enum class SwarmWorkerRole {
    FAST_RESPONDER,
    RESEARCH_WORKER,
    UI_OPERATOR,
    SPECULATIVE_DRAFTER;

    companion object {
        fun fromString(raw: String?): SwarmWorkerRole {
            return when (raw?.trim()?.lowercase()) {
                "fast_responder", "fast", "reply", "responder" -> FAST_RESPONDER
                "research_worker", "research", "headless", "lookup" -> RESEARCH_WORKER
                "ui_operator", "ui", "screen" -> UI_OPERATOR
                "speculative_drafter", "drafter", "draft" -> SPECULATIVE_DRAFTER
                else -> RESEARCH_WORKER
            }
        }
    }
}

data class SwarmSubtask(
    val id: String,
    val role: SwarmWorkerRole,
    val goal: String,
    val systemPromptExtra: String = "",
    val thinkingLevel: String? = null,
    val toolsOverride: Set<String>? = null
)

data class SwarmWorkerResult(
    val id: String,
    val role: SwarmWorkerRole,
    val ok: Boolean,
    val output: String,
    val toolsUsed: List<String>,
    val elapsedMs: Long,
    val error: String? = null
)

/**
 * Coordinates parallel worker copies backed by Gemini 4 Hydrogen (via StreamingLlmRouter)
 * across single or multiple OAuth accounts. Headless workers run concurrently, while UI_OPERATOR
 * workers serialize cleanly on [screenLease] so foreground screen actions never collide.
 */
class SwarmOrchestrator(
    private val llmRouter: StreamingLlmRouter?,
    maxConcurrency: Int = 4,
    private val streamTurnOverride: ((AssembledLlmContext, String, Set<String>, String, String) -> Flow<LlmStreamEvent>)? = null
) {
    companion object {
        private val FAST_RESPONDER_TOOLS = setOf("notification_center", "memory_facts")
        private val RESEARCH_WORKER_TOOLS = setOf(
            "live_info",
            "device_status",
            "notification_center",
            "personal_scheduler",
            "shizuku_shell",
            "memory_facts",
            "session_store"
        )
        private val UI_OPERATOR_TOOLS = setOf(
            "observe_screen",
            "ui_act",
            "device_intent",
            "shizuku_shell",
            "notification_center"
        )
    }

    val screenLease = Mutex()
    private val concurrencyLimiter = Semaphore(maxConcurrency.coerceIn(1, 8))
    private val activeWorkerJobs = ConcurrentHashMap<String, Job>()
    @Suppress("unused")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun defaultToolsForRole(role: SwarmWorkerRole): Set<String> = when (role) {
        SwarmWorkerRole.FAST_RESPONDER -> FAST_RESPONDER_TOOLS
        SwarmWorkerRole.RESEARCH_WORKER -> RESEARCH_WORKER_TOOLS
        SwarmWorkerRole.UI_OPERATOR -> UI_OPERATOR_TOOLS
        SwarmWorkerRole.SPECULATIVE_DRAFTER -> emptySet()
    }

    suspend fun runParallel(
        subtasks: List<SwarmSubtask>,
        baseContext: AssembledLlmContext? = null
    ): List<SwarmWorkerResult> = coroutineScope {
        if (subtasks.isEmpty()) return@coroutineScope emptyList()

        val rootContext = baseContext ?: AssembledLlmContext(
            sessionId = "swarm_${System.currentTimeMillis()}",
            systemPrompt = SystemPromptProvider.SARAH_VOICE_PROMPT,
            factsSummary = "",
            recentSessionsDigest = "",
            rollingSummary = "",
            turns = emptyList(),
            estimatedTokens = 0,
            factsInjectedCount = 0
        )

        subtasks.mapIndexed { idx, task ->
            async(Dispatchers.IO) {
                executeWorker(task, idx, rootContext)
            }
        }.awaitAll()
    }

    private suspend fun executeWorker(
        subtask: SwarmSubtask,
        index: Int,
        baseContext: AssembledLlmContext
    ): SwarmWorkerResult {
        val workerKey = "${subtask.id.ifBlank { "w$index" }}_${System.nanoTime()}"
        val job = currentCoroutineContext()[Job]
        if (job != null) {
            activeWorkerJobs[workerKey] = job
        }

        val t0 = System.currentTimeMillis()
        val callTag = "swarm_$workerKey"
        val tools = subtask.toolsOverride ?: defaultToolsForRole(subtask.role)
        val thinking = subtask.thinkingLevel ?: if (subtask.role == SwarmWorkerRole.UI_OPERATOR) "MEDIUM" else "LOW"
        val workerPrompt = buildString {
            append(baseContext.systemPrompt)
            append("\n\n[SWARM WORKER ROLE: ${subtask.role.name} | ID: ${subtask.id}]\n")
            append("Complete ONLY your assigned subtask concisely and factually. Return the verified result directly.")
            if (subtask.systemPromptExtra.isNotBlank()) {
                append("\n")
                append(subtask.systemPromptExtra)
            }
        }
        val workerContext = baseContext.copy(
            sessionId = "${baseContext.sessionId}_$workerKey",
            systemPrompt = workerPrompt,
            turns = emptyList()
        )

        return try {
            concurrencyLimiter.withPermit {
                val runBlock: suspend () -> SwarmWorkerResult = {
                    val toolsUsed = linkedSetOf<String>()
                    val buf = StringBuilder()
                    var completed = ""
                    val stream = streamTurnOverride?.invoke(workerContext, subtask.goal, tools, thinking, callTag)
                        ?: llmRouter?.antigravityProvider?.streamTurn(
                            context = workerContext,
                            userText = subtask.goal,
                            selectedTools = tools,
                            thinkingLevel = thinking,
                            trackActiveCall = true,
                            callTag = callTag
                        )
                        ?: emptyFlow()
                    stream.collect { ev ->
                        when (ev) {
                            is LlmStreamEvent.ToolExecuted -> toolsUsed += ev.name
                            is LlmStreamEvent.TextDelta -> buf.append(ev.delta)
                            is LlmStreamEvent.Completed -> completed = ev.fullText
                            else -> {}
                        }
                    }
                    val finalOut = completed.ifBlank { buf.toString() }.trim()
                    SwarmWorkerResult(
                        id = subtask.id,
                        role = subtask.role,
                        ok = finalOut.isNotBlank(),
                        output = finalOut,
                        toolsUsed = toolsUsed.toList(),
                        elapsedMs = System.currentTimeMillis() - t0
                    )
                }

                if (subtask.role == SwarmWorkerRole.UI_OPERATOR) {
                    screenLease.withLock {
                        runBlock()
                    }
                } else {
                    runBlock()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            runCatching {
                Log.w(PipelineConfig.LOG_TAG, "[Swarm] Worker ${subtask.id} (${subtask.role}) failed (${elapsed}ms): ${e.message}")
            }
            SwarmWorkerResult(
                id = subtask.id,
                role = subtask.role,
                ok = false,
                output = "",
                toolsUsed = emptyList(),
                elapsedMs = elapsed,
                error = e.message ?: "Worker failed"
            )
        } finally {
            activeWorkerJobs.remove(workerKey)
            llmRouter?.antigravityProvider?.cancelCall(callTag)
        }
    }

    fun cancelAllWorkers(reason: String = "user_stop") {
        val count = activeWorkerJobs.size
        val keys = activeWorkerJobs.keys.toList()
        for (k in keys) {
            activeWorkerJobs.remove(k)?.cancel(CancellationException("Swarm cancelled: $reason"))
        }
        llmRouter?.cancelAllCalls()
        if (count > 0) {
            runCatching {
                Log.i(PipelineConfig.LOG_TAG, "[Swarm] Cancelled $count active worker(s) ($reason)")
            }
        }
    }
}
