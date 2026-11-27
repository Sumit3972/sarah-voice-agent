package com.voiceagent.llm

import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.config.asArrOrNull
import com.voiceagent.config.asIntOrNull
import com.voiceagent.config.asObjOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.debug.ToolTraceLogger
import com.voiceagent.memory.AssembledLlmContext
import com.voiceagent.tools.SaraToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class LlmStreamEvent {
    data class TextDelta(val delta: String) : LlmStreamEvent()
    data class ToolExecuted(val name: String, val result: String) : LlmStreamEvent()
    data object IgnoredNoise : LlmStreamEvent()
    data class VisionHandoff(
        val imageBase64: String,
        val mimeType: String,
        val screenDigest: String
    ) : LlmStreamEvent()
    data class Completed(val fullText: String, val modelUsed: String) : LlmStreamEvent()
}

class Mercury3StreamingProvider(
    private val toolRegistry: SaraToolRegistry
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 12
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val ackClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1000, TimeUnit.MILLISECONDS)
        .callTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun cancelActive() {
        cancelCall("foreground")
    }

    fun cancelCall(callTag: String) {
        activeCalls.remove(callTag)?.let { call ->
            runCatching { call.cancel() }
        }
    }

    fun cancelAllCalls() {
        val keys = activeCalls.keys.toList()
        for (k in keys) {
            activeCalls.remove(k)?.let { call ->
                runCatching { call.cancel() }
            }
        }
    }

    suspend fun generateDynamicAck(userUtterance: String, activePackage: String): String? = withContext(Dispatchers.IO) {
        val apiKey = SecretsProvider.inceptionApiKey()
        if (apiKey.isBlank()) return@withContext null
        val url = "${SecretsProvider.inceptionBaseUrl().trimEnd('/')}/chat/completions"
        val bodyJson = buildJsonObject {
            put("model", SecretsProvider.inceptionChatModel())
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "Output ONLY one fresh, varied, natural 3-to-6 word spoken progress cue STRICTLY in English while Sarah runs a phone task for the user. " +
                            "Tailor it specifically to what the user asked and the active app. Never repeat stock clichés like 'Checking your screen now' or 'Checking that now'. " +
                            "You may optionally start with one Fish Audio tag like [warmly], [curious], or [happy]. " +
                            "NEVER use Hindi/Devanagari and NEVER say the task is already finished ('Done', 'Closed', 'Opened'). Pure spoken English prose only."
                    )
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "Active app: ${activePackage.ifBlank { "Home" }}\nUser request: $userUtterance")
                })
            })
            put("stream", false)
            put("realtime", true)
            put("diffusing", false)
            put("reasoning_effort", "instant")
            put("max_completion_tokens", 28)
            put("temperature", 0.85)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            ackClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = json.parseToJsonElement(resp.body.string()).asObjOrNull() ?: return@use null
                val content = root["choices"]?.asArrOrNull()?.firstOrNull()?.asObjOrNull()
                    ?.get("message")?.asObjOrNull()?.get("content")?.asStrOrNull()?.trim().orEmpty()
                if (content.isBlank()) null else content.take(90)
            }
        }.getOrNull()
    }

    fun streamTurn(
        context: AssembledLlmContext,
        userText: String,
        selectedTools: Set<String> = emptySet(),
        sourceMessageId: Long? = null,
        reasoningEffort: String = "high",
        callTag: String = "foreground",
        visionDescriber: (suspend (String, String, String) -> String?)? = null
    ): Flow<LlmStreamEvent> = flow {
        val apiKey = SecretsProvider.inceptionApiKey()
        require(apiKey.isNotBlank()) { "INCEPTION_API_KEY is blank" }

        val messages = mutableListOf<JsonObject>()
        messages += buildJsonObject {
            put("role", "system")
            put("content", context.systemPrompt)
        }
        for (turn in context.turns) {
            messages += buildJsonObject {
                put("role", turn.role)
                put("content", turn.content)
            }
        }
        messages += buildJsonObject {
            put("role", "user")
            put("content", userText)
        }

        val tools = toolRegistry.openAiToolsJson(selectedTools)
        val streamImmediately = tools.isEmpty()
        val accumulatedText = StringBuilder()

        try {
            var retriedRoundZero = false
            var stagnantUiRounds = 0
            var round = 0
            while (round < PipelineConfig.MAX_TOOL_ROUNDS) {
                currentCoroutineContext().ensureActive()
                val roundResult = executeStreamRound(
                    apiKey = apiKey,
                    messages = messages,
                    tools = tools,
                    streamDeltasImmediately = streamImmediately,
                    reasoningEffort = if (round > 0) "high" else reasoningEffort,
                    callTag = callTag,
                    onDelta = { delta ->
                        accumulatedText.append(delta)
                        emit(LlmStreamEvent.TextDelta(delta))
                    }
                )

                if (roundResult.toolCalls.isEmpty()) {
                    val isLegitimateRefusalOrClarify = roundResult.text.isNotBlank() && (
                        Regex(
                            "\\b(confirm|log you out|delete|erase|not allowed|cannot|can't|won't|unable|are you sure|want me to|which|did you mean|could you|please share|what is your|pin|password|clarify|specify)\\b",
                            RegexOption.IGNORE_CASE
                        ).containsMatchIn(roundResult.text) ||
                            roundResult.text.trim().endsWith("?")
                        )
                    // Guard: on TOOL_ACTION turns, never allow Round 0 to fake completion with 0 tool calls
                    if (!streamImmediately && round == 0 && !retriedRoundZero && !isLegitimateRefusalOrClarify) {
                        retriedRoundZero = true
                        ToolTraceLogger.recordException(
                            sessionId = context.sessionId,
                            stage = "mercury3_round0_no_tool",
                            component = SecretsProvider.inceptionChatModel(),
                            error = null,
                            message = "Returned 0 tools on TOOL_ACTION turn (draft='${roundResult.text.take(80)}'); retrying Round 0 with strict mandate"
                        )
                        if (roundResult.text.isNotBlank()) {
                            messages += buildJsonObject {
                                put("role", "assistant")
                                put("content", roundResult.text)
                            }
                        }
                        messages += buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                "MANDATORY SYSTEM DIRECTIVE: You called 0 tools. Do NOT answer or say 'Done' in plain text yet. Call the required tool from your tool list NOW to actually perform the user's request."
                            )
                        }
                        continue
                    }

                    if (!streamImmediately && round == 0 && retriedRoundZero) {
                        ToolTraceLogger.recordException(
                            sessionId = context.sessionId,
                            stage = "mercury3_retry_no_tool",
                            component = SecretsProvider.inceptionChatModel(),
                            error = null,
                            message = "Mercury 3 returned 0 tools after retry; escalating turn to Gemini"
                        )
                        throw IllegalStateException("Mercury3 returned 0 tool calls on TOOL_ACTION turn")
                    }

                    if (!streamImmediately && roundResult.text.isNotEmpty()) {
                        accumulatedText.append(roundResult.text)
                        emit(LlmStreamEvent.TextDelta(roundResult.text))
                    }
                    val finalStr = accumulatedText.toString().trim()
                    if (finalStr.isEmpty()) {
                        throw IllegalStateException("Mercury 3 returned empty content")
                    }
                    emit(LlmStreamEvent.Completed(finalStr, SecretsProvider.inceptionChatModel()))
                    return@flow
                }

                // Append assistant tool-call message
                messages += buildJsonObject {
                    put("role", "assistant")
                    if (roundResult.text.isNotBlank()) put("content", roundResult.text)
                    put("tool_calls", buildJsonArray {
                        for (tc in roundResult.toolCalls) {
                            add(buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }

                // Execute selected tools and append results; keep Mercury 3 in control via inline visionDescriber when available
                for (tc in roundResult.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    val toolOut = toolRegistry.executeTool(
                        name = tc.name,
                        argsJson = tc.arguments,
                        userSpokenText = userText,
                        sourceMessageId = sourceMessageId
                    )
                    emit(LlmStreamEvent.ToolExecuted(tc.name, toolOut.resultJson))
                    var finalToolContent = toolOut.resultJson
                    if (!toolOut.attachedImageBase64.isNullOrBlank()) {
                        val visualDesc = visionDescriber?.invoke(
                            toolOut.attachedImageBase64,
                            toolOut.attachedImageMimeType,
                            "User goal: $userText\nScreen tree summary: ${toolOut.resultJson.take(900)}\nList which numbered red boxes [N] match visible icons/buttons, visible dates/status cards, and the clearest next action."
                        )
                        if (!visualDesc.isNullOrBlank()) {
                            finalToolContent = buildJsonObject {
                                put("tool_output", toolOut.resultJson)
                                put("visual_analysis", visualDesc)
                            }.toString()
                        } else {
                            emit(
                                LlmStreamEvent.VisionHandoff(
                                    imageBase64 = toolOut.attachedImageBase64,
                                    mimeType = toolOut.attachedImageMimeType,
                                    screenDigest = toolOut.resultJson
                                )
                            )
                            return@flow
                        }
                    }
                    messages += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", tc.id)
                        put("content", finalToolContent)
                    }
                    if (tc.name == "ui_act") {
                        if (toolOut.resultJson.contains("\"screen_changed\":false") || toolOut.resultJson.contains("\"ok\":false")) {
                            stagnantUiRounds++
                        } else {
                            stagnantUiRounds = 0
                        }
                    }
                }
                if (stagnantUiRounds >= 3) {
                    stagnantUiRounds = 0
                    messages += buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "SYSTEM RECOVERY HINT: Your last 3 UI steps did not advance the screen. Call observe_screen(mode='vision') or use global_action='back' / shizuku_shell / ask the user one brief question instead of repeating the same tap."
                        )
                    }
                }
                round++
            }
            if (accumulatedText.isBlank()) {
                messages += buildJsonObject {
                    put("role", "user")
                    put("content", "Max UI steps reached. Tell the user concisely in 1-2 short spoken sentences what you see on the screen right now and what remains.")
                }
                val summaryRound = executeStreamRound(
                    apiKey = apiKey,
                    messages = messages,
                    tools = JsonArray(emptyList()),
                    streamDeltasImmediately = true,
                    reasoningEffort = "instant",
                    callTag = callTag,
                    onDelta = { delta ->
                        accumulatedText.append(delta)
                        emit(LlmStreamEvent.TextDelta(delta))
                    }
                )
                if (accumulatedText.isBlank() && summaryRound.text.isNotBlank()) {
                    accumulatedText.append(summaryRound.text)
                    emit(LlmStreamEvent.TextDelta(summaryRound.text))
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true) {
                throw kotlinx.coroutines.CancellationException("Mercury3 call canceled", e)
            }
            ToolTraceLogger.recordException(
                sessionId = context.sessionId,
                stage = "mercury3",
                component = SecretsProvider.inceptionChatModel(),
                error = e,
                message = e.message ?: "Mercury3 failed"
            )
            throw e
        }

        val finalStr = accumulatedText.toString().trim().ifBlank {
            val fallback = "I got part of the way through that, let me know how you'd like me to continue."
            emit(LlmStreamEvent.TextDelta(fallback))
            fallback
        }
        emit(LlmStreamEvent.Completed(finalStr, SecretsProvider.inceptionChatModel()))
    }.flowOn(Dispatchers.IO)

    private data class ParsedToolCall(val id: String, val name: String, val arguments: String)
    private data class StreamRoundResult(val text: String, val toolCalls: List<ParsedToolCall>)

    private suspend fun executeStreamRound(
        apiKey: String,
        messages: List<JsonObject>,
        tools: JsonArray,
        streamDeltasImmediately: Boolean,
        reasoningEffort: String = "high",
        callTag: String = "foreground",
        onDelta: suspend (String) -> Unit
    ): StreamRoundResult {
        val url = "${SecretsProvider.inceptionBaseUrl().trimEnd('/')}/chat/completions"
        val bodyJson = buildJsonObject {
            put("model", SecretsProvider.inceptionChatModel())
            put("messages", JsonArray(messages))
            if (tools.isNotEmpty()) {
                put("tools", tools)
            }
            put("stream", true)
            put("realtime", true)
            put("diffusing", false) // Never enable diffusing when piping into TTS
            put("reasoning_effort", reasoningEffort)
            put("max_completion_tokens", 3000)
            put("temperature", 0.65)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(req)
        activeCalls[callTag] = call

        val roundText = StringBuilder()
        val tcIds = mutableMapOf<Int, String>()
        val tcNames = mutableMapOf<Int, String>()
        val tcArgs = mutableMapOf<Int, StringBuilder>()

        try {
            call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body.string()
                throw IllegalStateException("Mercury3 HTTP ${resp.code}: ${errBody.take(300)}")
            }

            val reader = BufferedReader(InputStreamReader(resp.body.byteStream(), Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                currentCoroutineContext().ensureActive()
                val trimmed = line?.trim().orEmpty()
                if (!trimmed.startsWith("data:")) continue
                val dataStr = trimmed.removePrefix("data:").trim()
                if (dataStr == "[DONE]") break
                if (dataStr.isEmpty()) continue

                val chunk = runCatching { json.parseToJsonElement(dataStr).asObjOrNull() }.getOrNull() ?: continue
                val errEl = chunk["error"]
                if (errEl != null && errEl !is JsonNull) {
                    throw IllegalStateException("Mercury3 SSE error: $errEl")
                }
                val choices = chunk["choices"].asArrOrNull() ?: continue
                if (choices.isEmpty()) continue
                val choice0 = choices[0].asObjOrNull() ?: continue
                val delta = choice0["delta"].asObjOrNull() ?: continue

                val deltaContent = delta["content"].asStrOrNull()
                if (!deltaContent.isNullOrEmpty()) {
                    roundText.append(deltaContent)
                    if (streamDeltasImmediately) {
                        onDelta(deltaContent)
                    }
                }

                val toolCallsArr = delta["tool_calls"].asArrOrNull()
                if (toolCallsArr != null) {
                    for (tcEl in toolCallsArr) {
                        val tcObj = tcEl.asObjOrNull() ?: continue
                        val idx = tcObj["index"].asIntOrNull() ?: 0
                        tcObj["id"].asStrOrNull()?.takeIf { it.isNotBlank() }?.let { tcIds[idx] = it }
                        val funcObj = tcObj["function"].asObjOrNull()
                        if (funcObj != null) {
                            funcObj["name"].asStrOrNull()?.takeIf { it.isNotBlank() }?.let { tcNames[idx] = it }
                            funcObj["arguments"].asStrOrNull()?.let { part ->
                                tcArgs.getOrPut(idx) { StringBuilder() }.append(part)
                            }
                        }
                    }
                }
            }
            }
        } finally {
            activeCalls.remove(callTag, call)
        }

        val parsedTools = tcNames.keys.sorted().mapNotNull { idx ->
            val name = tcNames[idx] ?: return@mapNotNull null
            ParsedToolCall(
                id = tcIds[idx] ?: "call_${System.currentTimeMillis()}_$idx",
                name = name,
                arguments = tcArgs[idx]?.toString().orEmpty().ifBlank { "{}" }
            )
        }
        Log.d(PipelineConfig.LOG_TAG, "[Mercury3] Round complete: chars=${roundText.length}, toolCalls=${parsedTools.size}")
        return StreamRoundResult(roundText.toString(), parsedTools)
    }
}
