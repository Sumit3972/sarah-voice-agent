package com.voiceagent.llm

import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.memory.AssembledLlmContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class StreamingLlmRouter(
    private val decideRouter: MercuryDecideRouter,
    private val mercuryProvider: Mercury3StreamingProvider,
    val antigravityProvider: AntigravityCodeAssistProvider
) {
    private class SelfEscalateSignal : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private class IgnoreNoiseSignal : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    @Volatile
    private var mercuryCircuitOpenUntilMs: Long = 0L

    fun cancelActive() {
        mercuryProvider.cancelActive()
        antigravityProvider.cancelActive()
    }

    fun cancelAllCalls() {
        mercuryProvider.cancelAllCalls()
        antigravityProvider.cancelAllCalls()
    }

    suspend fun generateDynamicAck(userUtterance: String, activePackage: String): String? {
        return mercuryProvider.generateDynamicAck(userUtterance, activePackage)
    }

    suspend fun decideMidSpeechInterruption(
        heardText: String,
        assistantSpokenText: String,
        activePackage: String = ""
    ): BargeInDecision = decideRouter.decideMidSpeechInterruption(
        heardText = heardText,
        assistantSpokenText = assistantSpokenText,
        activePackage = activePackage
    )

    suspend fun decideActionRoute(
        userText: String,
        context: AssembledLlmContext,
        activePackage: String = "",
        hasCameraFrame: Boolean = false,
        fromFollowUp: Boolean = false,
        lastAssistantReply: String = ""
    ): RoutingDecision {
        val recentSummary = context.turns.takeLast(4).joinToString("\n") { "${it.role}: ${it.content}" }
        val decision = decideRouter.decide(
            userUtterance = userText,
            recentContextSummary = recentSummary,
            activePackage = activePackage,
            hasCameraFrame = hasCameraFrame,
            fromFollowUp = fromFollowUp,
            lastAssistantReply = lastAssistantReply
        )

        if (decision.target == RouteDecisionTarget.MERCURY_FAST &&
            System.currentTimeMillis() < mercuryCircuitOpenUntilMs
        ) {
            return decision.copy(
                target = RouteDecisionTarget.GEMINI_SMART,
                reason = "mercury_circuit_breaker_open"
            )
        }
        return decision
    }

    fun streamWithDecision(
        decision: RoutingDecision,
        context: AssembledLlmContext,
        userText: String,
        imageBase64: String? = null,
        imageMimeType: String = "image/webp",
        sourceMessageId: Long? = null,
        fromFollowUp: Boolean = false
    ): Flow<LlmStreamEvent> = flow {
        val needsA11ySkill = "observe_screen" in decision.selectedTools || "ui_act" in decision.selectedTools
        val needsAutonomousGuide = "autonomous_task" in decision.selectedTools
        val extraPrompt = buildString {
            if (needsA11ySkill) {
                append("\n\n")
                append(AndroidAccessibilitySkillPrompt.SKILL_INSTRUCTIONS)
            }
            if (needsAutonomousGuide) {
                append("\n\n")
                append(AutonomousAgentSkillPrompt.CREATION_GUIDE)
            }
        }
        val effectiveContext = if (extraPrompt.isNotEmpty()) {
            context.copy(
                systemPrompt = context.systemPrompt + extraPrompt
            )
        } else {
            context
        }
        var handoffImage: String? = imageBase64
        var handoffMime = imageMimeType
        var handoffDigest = ""
        val ignoreNoiseToken = "[IGNORE_NOISE]"
        val trigger = "[ESCALATE_GEMINI]"

        if (decision.target == RouteDecisionTarget.MERCURY_FAST && handoffImage.isNullOrBlank()) {
            var emittedAnyText = false
            var escalated = false
            val headBuf = StringBuilder()
            var prefixResolved = decision.selectedTools.isNotEmpty() && !fromFollowUp

            try {
                mercuryProvider.streamTurn(
                    context = effectiveContext,
                    userText = userText,
                    selectedTools = decision.selectedTools,
                    sourceMessageId = sourceMessageId,
                    reasoningEffort = if (decision.reasoningEffort.equals("high", ignoreCase = true)) "high" else "instant",
                    visionDescriber = { b64, mime, prompt ->
                        antigravityProvider.describeScreenImage(b64, mime, prompt)
                    }
                ).collect { event ->
                    when (event) {
                        is LlmStreamEvent.VisionHandoff -> {
                            handoffImage = event.imageBase64
                            handoffMime = event.mimeType
                            handoffDigest = event.screenDigest
                            escalated = true
                            Log.i(PipelineConfig.LOG_TAG, "[LlmRouter] Vision screenshot captured -> handing off to Gemini")
                        }
                        is LlmStreamEvent.IgnoredNoise -> {
                            emit(event)
                            return@collect
                        }
                        is LlmStreamEvent.ToolExecuted -> emit(event)
                        is LlmStreamEvent.TextDelta -> {
                            if (prefixResolved) {
                                emittedAnyText = true
                                emit(event)
                            } else {
                                headBuf.append(event.delta)
                                val trimmed = headBuf.toString().trimStart()
                                val canEscalate = decision.selectedTools.isEmpty()
                                val couldBeIgnore = ignoreNoiseToken.startsWith(trimmed, ignoreCase = true)
                                val couldBeEscalate = canEscalate && trigger.startsWith(trimmed, ignoreCase = true)
                                when {
                                    trimmed.startsWith(ignoreNoiseToken, ignoreCase = true) -> {
                                        if (fromFollowUp) {
                                            Log.i(PipelineConfig.LOG_TAG, "[LlmRouter] Mercury 3 flagged follow-up as $ignoreNoiseToken")
                                            emit(LlmStreamEvent.IgnoredNoise)
                                            throw IgnoreNoiseSignal()
                                        }
                                        val cleaned = trimmed.substring(ignoreNoiseToken.length).trimStart()
                                        headBuf.clear()
                                        if (cleaned.isNotEmpty()) {
                                            prefixResolved = true
                                            emittedAnyText = true
                                            emit(LlmStreamEvent.TextDelta(cleaned))
                                        }
                                    }
                                    canEscalate && trimmed.startsWith(trigger, ignoreCase = true) -> {
                                        escalated = true
                                        throw SelfEscalateSignal()
                                    }
                                    trimmed.isNotEmpty() && (!trimmed.startsWith("[") ||
                                        (!couldBeEscalate && !couldBeIgnore &&
                                            (trimmed.contains(']') || trimmed.length >= maxOf(trigger.length, ignoreNoiseToken.length)))) -> {
                                        prefixResolved = true
                                        val out = headBuf.toString()
                                        headBuf.clear()
                                        emittedAnyText = true
                                        emit(LlmStreamEvent.TextDelta(out))
                                    }
                                }
                            }
                        }
                        is LlmStreamEvent.Completed -> {
                            if (!prefixResolved && headBuf.isNotEmpty()) {
                                val out = headBuf.toString()
                                headBuf.clear()
                                val trimmed = out.trimStart()
                                if (trimmed.startsWith(ignoreNoiseToken, ignoreCase = true)) {
                                    if (fromFollowUp) {
                                        Log.i(PipelineConfig.LOG_TAG, "[LlmRouter] Mercury 3 flagged completed follow-up as $ignoreNoiseToken")
                                        emit(LlmStreamEvent.IgnoredNoise)
                                        throw IgnoreNoiseSignal()
                                    }
                                    val cleaned = trimmed.substring(ignoreNoiseToken.length).trimStart()
                                    if (cleaned.isNotEmpty()) {
                                        emittedAnyText = true
                                        emit(LlmStreamEvent.TextDelta(cleaned))
                                        emit(LlmStreamEvent.Completed(cleaned, SecretsProvider.inceptionChatModel()))
                                    }
                                    return@collect
                                }
                                if (decision.selectedTools.isEmpty() && trimmed.startsWith(trigger, ignoreCase = true)) {
                                    escalated = true
                                    throw SelfEscalateSignal()
                                }
                                emittedAnyText = true
                                emit(LlmStreamEvent.TextDelta(out))
                            }
                            if (handoffImage.isNullOrBlank()) {
                                emit(event)
                            }
                        }
                    }
                }
                if (handoffImage.isNullOrBlank() && !escalated) return@flow
            } catch (_: IgnoreNoiseSignal) {
                return@flow
            } catch (_: SelfEscalateSignal) {
                escalated = true
                Log.i(PipelineConfig.LOG_TAG, "[LlmRouter] Mercury 3 self-escalated to Gemini ($trigger)")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w(
                    PipelineConfig.LOG_TAG,
                    "[LlmRouter] Mercury 3 failed (emittedAny=$emittedAnyText): ${e.message}. Failing over to Gemini."
                )
                mercuryCircuitOpenUntilMs = System.currentTimeMillis() + 60_000L
                if (emittedAnyText && !escalated && handoffImage.isNullOrBlank()) throw e
            }
        }

        val escalatedPrompt = if (handoffDigest.isNotBlank()) {
            "$userText\n\n[LATEST CAPTURED SCREEN STATE FOR VISUAL INSPECTION]\n$handoffDigest"
        } else {
            userText
        }

        val geminiThinkingLevel = when (decision.reasoningEffort.trim().uppercase()) {
            "HIGH" -> "HIGH"
            "LOW" -> "LOW"
            else -> "MEDIUM"
        }

        val geminiFlow = antigravityProvider.streamTurn(
            context = effectiveContext,
            userText = escalatedPrompt,
            selectedTools = decision.selectedTools,
            imageBase64 = handoffImage,
            imageMimeType = handoffMime,
            sourceMessageId = sourceMessageId,
            thinkingLevel = geminiThinkingLevel
        )

        if (!fromFollowUp) {
            emitAll(geminiFlow)
            return@flow
        }

        val geminiHead = StringBuilder()
        var geminiPrefixResolved = false
        try {
            geminiFlow.collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        if (geminiPrefixResolved) {
                            emit(event)
                        } else {
                            geminiHead.append(event.delta)
                            val trimmed = geminiHead.toString().trimStart()
                            val couldBeIgnore = ignoreNoiseToken.startsWith(trimmed, ignoreCase = true)
                            when {
                                trimmed.startsWith(ignoreNoiseToken, ignoreCase = true) -> {
                                    Log.i(PipelineConfig.LOG_TAG, "[LlmRouter] Gemini flagged follow-up as $ignoreNoiseToken")
                                    emit(LlmStreamEvent.IgnoredNoise)
                                    throw IgnoreNoiseSignal()
                                }
                                trimmed.isNotEmpty() && (!trimmed.startsWith("[") ||
                                    (!couldBeIgnore && (trimmed.contains(']') || trimmed.length >= ignoreNoiseToken.length))) -> {
                                    geminiPrefixResolved = true
                                    val out = geminiHead.toString()
                                    geminiHead.clear()
                                    emit(LlmStreamEvent.TextDelta(out))
                                }
                            }
                        }
                    }
                    is LlmStreamEvent.Completed -> {
                        if (!geminiPrefixResolved && geminiHead.isNotEmpty()) {
                            val out = geminiHead.toString()
                            geminiHead.clear()
                            if (out.trimStart().startsWith(ignoreNoiseToken, ignoreCase = true)) {
                                Log.i(PipelineConfig.LOG_TAG, "[LlmRouter] Gemini flagged completed follow-up as $ignoreNoiseToken")
                                emit(LlmStreamEvent.IgnoredNoise)
                                throw IgnoreNoiseSignal()
                            }
                            emit(LlmStreamEvent.TextDelta(out))
                        }
                        emit(event)
                    }
                    else -> emit(event)
                }
            }
        } catch (_: IgnoreNoiseSignal) {
            return@flow
        }
    }

    fun streamDirectFastPath(
        context: AssembledLlmContext,
        userText: String,
        selectedTools: Set<String> = emptySet(),
        imageBase64: String? = null,
        imageMimeType: String = "image/webp",
        sourceMessageId: Long? = null
    ): Flow<LlmStreamEvent> {
        val syntheticDecision = RoutingDecision(
            target = if (imageBase64.isNullOrBlank()) RouteDecisionTarget.MERCURY_FAST else RouteDecisionTarget.GEMINI_SMART,
            intentMode = if (selectedTools.isNotEmpty()) IntentMode.TOOL_ACTION else IntentMode.PURE_CHAT,
            selectedTools = selectedTools,
            ackCategory = "none",
            spokenAckText = "",
            needsVision = !imageBase64.isNullOrBlank(),
            reason = "direct_fast_path",
            latencyMs = 0L
        )
        return streamWithDecision(syntheticDecision, context, userText, imageBase64, imageMimeType, sourceMessageId)
    }
}
