package com.voiceagent.llm

import android.util.Log
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.asArrOrNull
import com.voiceagent.config.asObjOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.debug.ToolTraceLogger
import com.voiceagent.memory.AssembledLlmContext
import com.voiceagent.tools.SaraToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

class AntigravityCodeAssistProvider(
    private val pool: OAuthAccountPool,
    private val toolRegistry: SaraToolRegistry
) {
    companion object {
        private val PARALLEL_HEADLESS_TOOLS = setOf(
            "device_status",
            "live_info",
            "notification_center",
            "memory_facts",
            "session_store",
            "personal_scheduler"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 12
        })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
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

    private val modelHierarchy = listOf(
        OAuthAccountPool.MODEL_GEMINI_4_HYDROGEN,
        OAuthAccountPool.MODEL_GEMINI_38_FLASH_HIGH,
        OAuthAccountPool.MODEL_GEMINI_38_FLASH_MEDIUM,
        OAuthAccountPool.MODEL_CLAUDE_SONNET,
        OAuthAccountPool.MODEL_CLAUDE_OPUS
    )

    fun streamTurn(
        context: AssembledLlmContext,
        userText: String,
        selectedTools: Set<String> = emptySet(),
        imageBase64: String? = null,
        imageMimeType: String = "image/webp",
        sourceMessageId: Long? = null,
        thinkingLevel: String = "MEDIUM",
        trackActiveCall: Boolean = true,
        callTag: String = "foreground"
    ): Flow<LlmStreamEvent> = flow {
        val accounts = pool.getOrderedAccounts("gemini")
        if (accounts.isEmpty()) throw IllegalStateException("No Antigravity OAuth accounts configured")

        val baseContents = mutableListOf<JsonObject>()
        for (turn in context.turns) {
            val role = if (turn.role == "assistant") "model" else "user"
            baseContents += buildJsonObject {
                put("role", role)
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", turn.content) })
                })
            }
        }
        baseContents += buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray {
                if (!imageBase64.isNullOrBlank()) {
                    add(buildJsonObject {
                        put("inlineData", buildJsonObject {
                            put("mimeType", imageMimeType)
                            put("data", imageBase64)
                        })
                    })
                }
                add(buildJsonObject { put("text", userText) })
            })
        }

        val toolDecls = toolRegistry.geminiFunctionDeclarationsJson(selectedTools)
        var lastError: Throwable? = null
        val executedProgressNotes = mutableListOf<String>()

        for (acc in accounts) {
            val token = try {
                pool.getAccessToken(acc)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w(PipelineConfig.LOG_TAG, "[Antigravity] Auth error for ${acc.email}: ${e.message}")
                acc.putOnCooldown("auth", 15 * 60_000L)
                lastError = e
                continue
            }
            val projectId = pool.getProjectId(acc, token)

            for (model in modelHierarchy) {
                val isClaude = model.contains("claude")
                if (isClaude && acc.isOnCooldown("claude")) continue
                if (!isClaude && acc.isOnCooldown("gemini")) continue
                currentCoroutineContext().ensureActive()

                pool.incrementInFlight(acc)
                try {
                    Log.i(PipelineConfig.LOG_TAG, "[Antigravity] Trying $model (thinking=$thinkingLevel, tag=$callTag) on ${acc.email} (tools=${selectedTools.size})...")
                    val fullText = StringBuilder()
                    val workingContents = baseContents.toMutableList()
                    if (executedProgressNotes.isNotEmpty()) {
                        val liveSnap = runCatching { SaraAccessibilityService.instance?.observeTree() }.getOrNull()
                        val resumeText = buildString {
                            append("[PRIOR PROGRESS BEFORE MODEL FAILOVER]:\n")
                            append(executedProgressNotes.takeLast(8).joinToString("\n"))
                            if (liveSnap != null) {
                                append("\n\n[CURRENT LIVE SCREEN NOW: package=${liveSnap.packageName}]\n")
                                append(liveSnap.treeText)
                            }
                        }
                        workingContents += buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", resumeText) }) })
                        }
                    }

                    var consecutiveNoChangeUiRounds = 0
                    for (round in 0 until PipelineConfig.MAX_TOOL_ROUNDS) {
                        currentCoroutineContext().ensureActive()
                        val roundResult = executeSseRound(
                            token = token,
                            projectId = projectId,
                            model = model,
                            systemPrompt = context.systemPrompt,
                            contents = workingContents,
                            tools = if (toolDecls.isNotEmpty()) toolDecls else null,
                            forceToolCall = (round == 0 && toolDecls.isNotEmpty() && !model.contains("claude") && model != OAuthAccountPool.MODEL_GEMINI_4_HYDROGEN),
                            thinkingLevel = thinkingLevel,
                            trackActiveCall = trackActiveCall,
                            callTag = callTag,
                            onDelta = { delta ->
                                fullText.append(delta)
                                emit(LlmStreamEvent.TextDelta(delta))
                            }
                        )

                        if (roundResult.functionCalls.isEmpty()) {
                            if (toolDecls.isNotEmpty() && roundResult.text.isNotEmpty()) {
                                fullText.append(roundResult.text)
                                emit(LlmStreamEvent.TextDelta(roundResult.text))
                            }
                            val finalStr = fullText.toString().trim()
                            if (finalStr.isBlank()) {
                                throw IllegalStateException("Empty response from $model")
                            }
                            emit(LlmStreamEvent.Completed(finalStr, model))
                            return@flow
                        }

                        // Append model functionCall parts verbatim so Gemini thoughtSignature & Claude tool_use.id stay intact
                        workingContents += buildJsonObject {
                            put("role", "model")
                            put("parts", buildJsonArray {
                                for (fc in roundResult.functionCalls) {
                                    if (fc.rawPart != null) {
                                        add(fc.rawPart)
                                    } else {
                                        add(buildJsonObject {
                                            put("functionCall", buildJsonObject {
                                                put("name", fc.name)
                                                put("args", fc.args)
                                                if (!fc.id.isNullOrBlank()) put("id", fc.id)
                                            })
                                            if (!fc.thoughtSignature.isNullOrBlank()) {
                                                put("thoughtSignature", fc.thoughtSignature)
                                            }
                                        })
                                    }
                                }
                            })
                        }

                        // Execute headless tools in parallel when multiple are requested; serialize UI/state-mutating tools
                        val indexedCalls = roundResult.functionCalls.withIndex().toList()
                        val headlessCalls = indexedCalls.filter { it.value.name in PARALLEL_HEADLESS_TOOLS }
                        val serialCalls = indexedCalls.filter { it.value.name !in PARALLEL_HEADLESS_TOOLS }
                        val orderedOutputs = arrayOfNulls<com.voiceagent.tools.ToolExecutionOutput>(indexedCalls.size)

                        if (headlessCalls.size > 1) {
                            coroutineScope {
                                headlessCalls.map { (idx, fc) ->
                                    async {
                                        currentCoroutineContext().ensureActive()
                                        idx to toolRegistry.executeTool(
                                            name = fc.name,
                                            args = fc.args,
                                            userSpokenText = userText,
                                            sourceMessageId = sourceMessageId
                                        )
                                    }
                                }.awaitAll().forEach { (idx, out) ->
                                    orderedOutputs[idx] = out
                                }
                            }
                        } else {
                            for ((idx, fc) in headlessCalls) {
                                currentCoroutineContext().ensureActive()
                                orderedOutputs[idx] = toolRegistry.executeTool(
                                    name = fc.name,
                                    args = fc.args,
                                    userSpokenText = userText,
                                    sourceMessageId = sourceMessageId
                                )
                            }
                        }

                        for ((idx, fc) in serialCalls) {
                            currentCoroutineContext().ensureActive()
                            orderedOutputs[idx] = toolRegistry.executeTool(
                                name = fc.name,
                                args = fc.args,
                                userSpokenText = userText,
                                sourceMessageId = sourceMessageId
                            )
                        }

                        val respParts = mutableListOf<JsonObject>()
                        var latestImage: Pair<String, String>? = null
                        var roundSawUiNoChange = false
                        for ((idx, fc) in indexedCalls) {
                            val toolOut = orderedOutputs[idx]
                                ?: com.voiceagent.tools.ToolExecutionOutput("""{"ok":false,"error":"Missing tool output"}""")
                            executedProgressNotes += "${fc.name}(${fc.args}) -> ${toolOut.resultJson.take(140)}"
                            if (fc.name == "ui_act" && toolOut.resultJson.contains("\"screen_changed\":false")) {
                                roundSawUiNoChange = true
                            }
                            emit(LlmStreamEvent.ToolExecuted(fc.name, toolOut.resultJson))
                            val parsedResp = runCatching { json.parseToJsonElement(toolOut.resultJson).asObjOrNull() }
                                .getOrNull() ?: buildJsonObject { put("result", toolOut.resultJson) }
                            respParts += buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    if (!fc.id.isNullOrBlank()) put("id", fc.id)
                                    put("name", fc.name)
                                    put("response", parsedResp)
                                })
                            }
                            if (!toolOut.attachedImageBase64.isNullOrBlank()) {
                                latestImage = toolOut.attachedImageBase64 to toolOut.attachedImageMimeType
                            }
                        }
                        if (roundSawUiNoChange) {
                            consecutiveNoChangeUiRounds++
                        } else {
                            consecutiveNoChangeUiRounds = 0
                        }
                        if (consecutiveNoChangeUiRounds >= 3) {
                            consecutiveNoChangeUiRounds = 0
                            respParts += buildJsonObject {
                                put(
                                    "text",
                                    "STAGNATION DETECTED: The last 3 UI actions did not change the screen. Do NOT repeat the same tap. Try observe_screen(mode=\"vision\"), global_action=\"back\", an alternate clickable ref, or ask the user briefly."
                                )
                            }
                        }
                        if (latestImage != null) {
                            respParts += buildJsonObject {
                                put("inlineData", buildJsonObject {
                                    put("mimeType", latestImage.second)
                                    put("data", latestImage.first)
                                })
                            }
                        }
                        workingContents += buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(respParts))
                        }
                    }

                    if (fullText.isBlank()) {
                        workingContents += buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    put("text", "Max UI steps reached. Tell the user concisely in 1-2 short spoken sentences what is visible on the current screen right now and what step remains.")
                                })
                            })
                        }
                        runCatching {
                            executeSseRound(
                                token = token,
                                projectId = projectId,
                                model = model,
                                systemPrompt = context.systemPrompt,
                                contents = workingContents,
                                tools = null,
                                forceToolCall = false,
                                thinkingLevel = thinkingLevel,
                                trackActiveCall = trackActiveCall,
                                onDelta = { delta ->
                                    fullText.append(delta)
                                    emit(LlmStreamEvent.TextDelta(delta))
                                }
                            )
                        }
                    }
                    val finalStr = fullText.toString().trim().ifBlank {
                        val fallback = "I got part of the way through that screen, let me know how you'd like me to proceed."
                        emit(LlmStreamEvent.TextDelta(fallback))
                        fallback
                    }
                    emit(LlmStreamEvent.Completed(finalStr, model))
                    return@flow
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (e is java.io.IOException && e.message?.contains("canceled", ignoreCase = true) == true) {
                        throw kotlinx.coroutines.CancellationException("Antigravity call canceled", e)
                    }
                    lastError = e
                    val msg = e.message.orEmpty()
                    Log.w(PipelineConfig.LOG_TAG, "[Antigravity] $model failed on ${acc.email}: $msg")
                    ToolTraceLogger.recordException(
                        sessionId = context.sessionId,
                        stage = "antigravity",
                        component = "$model (${acc.email})",
                        error = e,
                        message = msg
                    )
                    if (isAuthError(msg)) {
                        acc.putOnCooldown("auth", 15 * 60_000L)
                        break
                    }
                    if (isQuotaError(msg)) {
                        val isClaude = model.contains("claude")
                        acc.putOnCooldown(if (isClaude) "claude" else "gemini", 15 * 60_000L)
                        if (!isClaude && accounts.size > 1) {
                            Log.i(PipelineConfig.LOG_TAG, "[Antigravity] Gemini quota hit on ${acc.email}; failing over to next account immediately")
                            break
                        }
                    }
                } finally {
                    pool.decrementInFlight(acc)
                }
            }

            if (accounts.size > 1) {
                acc.putOnCooldown("gemini", 3 * 60_000L)
            }
        }

        throw lastError ?: IllegalStateException("All Antigravity accounts and models failed")
    }.flowOn(Dispatchers.IO)

    suspend fun quickComplete(systemPrompt: String, userPrompt: String): String? = withContext(Dispatchers.IO) {
        val accounts = pool.getOrderedAccounts("gemini")
        val quickModels = listOf(
            OAuthAccountPool.MODEL_GEMINI_38_FLASH_LOW,
            OAuthAccountPool.MODEL_GEMINI_38_FLASH_MEDIUM
        )
        for (acc in accounts.take(2)) {
            val token = runCatching { pool.getAccessToken(acc) }.getOrNull() ?: continue
            val projectId = pool.getProjectId(acc, token)
            val contents = listOf(
                buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", userPrompt) }) })
                }
            )
            for (model in quickModels) {
                val out = StringBuilder()
                val ok = runCatching {
                    executeSseRound(
                        token = token,
                        projectId = projectId,
                        model = model,
                        systemPrompt = systemPrompt,
                        contents = contents,
                        tools = null,
                        forceToolCall = false,
                        onDelta = { out.append(it) }
                    )
                }.isSuccess
                if (ok && out.isNotBlank()) return@withContext out.toString()
            }
        }
        null
    }

    suspend fun describeScreenImage(
        imageBase64: String,
        mimeType: String,
        prompt: String
    ): String? = withContext(Dispatchers.IO) {
        val accounts = pool.getOrderedAccounts("gemini")
        val visionModels = listOf(
            OAuthAccountPool.MODEL_GEMINI_4_HYDROGEN,
            OAuthAccountPool.MODEL_GEMINI_38_FLASH_HIGH,
            OAuthAccountPool.MODEL_GEMINI_38_FLASH_MEDIUM
        )
        for (acc in accounts.take(3)) {
            val token = runCatching { pool.getAccessToken(acc) }.getOrNull() ?: continue
            val projectId = pool.getProjectId(acc, token)
            val contents = listOf(
                buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", mimeType)
                                put("data", imageBase64)
                            })
                        })
                        add(buildJsonObject { put("text", prompt) })
                    })
                }
            )
            for (model in visionModels) {
                val out = StringBuilder()
                val ok = runCatching {
                    executeSseRound(
                        token = token,
                        projectId = projectId,
                        model = model,
                        systemPrompt = "You are a precise Android screen-vision helper. Reply strictly in English. Concisely identify ONLY task-relevant UI state, dates/status text, and which red Set-of-Marks numbered boxes [N] match target buttons. Be factual and ultra-brief (<60 words).",
                        contents = contents,
                        tools = null,
                        forceToolCall = false,
                        thinkingLevel = "MEDIUM",
                        onDelta = { out.append(it) }
                    )
                }.isSuccess
                if (ok && out.isNotBlank()) {
                    Log.i(PipelineConfig.LOG_TAG, "[Antigravity] Screen vision described by $model (${out.length} chars)")
                    return@withContext out.toString().trim()
                }
            }
        }
        null
    }

    private data class GeminiFuncCall(
        val name: String,
        val args: JsonObject,
        val id: String? = null,
        val thoughtSignature: String? = null,
        val rawPart: JsonObject? = null
    )
    private data class SseRoundResult(val text: String, val functionCalls: List<GeminiFuncCall>)

    private fun normalizeHydrogenThinkingLevel(raw: String?): String? {
        val clean = raw?.trim()?.uppercase().orEmpty()
        return when (clean) {
            "LOW", "INSTANT", "MINIMAL" -> "LOW"
            "MEDIUM" -> "MEDIUM"
            "HIGH", "EXTRA_HIGH", "XHIGH" -> "HIGH"
            else -> null
        }
    }

    private suspend fun executeSseRound(
        token: String,
        projectId: String,
        model: String,
        systemPrompt: String,
        contents: List<JsonObject>,
        tools: JsonArray?,
        forceToolCall: Boolean = false,
        thinkingLevel: String? = null,
        trackActiveCall: Boolean = true,
        callTag: String = "foreground",
        onDelta: suspend (String) -> Unit
    ): SseRoundResult {
        val normalizedHydrogenLevel = if (model == OAuthAccountPool.MODEL_GEMINI_4_HYDROGEN) {
            normalizeHydrogenThinkingLevel(thinkingLevel)
        } else {
            null
        }
        val payload = buildJsonObject {
            put("project", projectId)
            put("model", model)
            put("userAgent", "antigravity")
            put("requestId", "req-${System.currentTimeMillis()}")
            put("request", buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", systemPrompt) }) })
                })
                put("contents", JsonArray(contents))
                if (tools != null && tools.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("functionDeclarations", tools)
                        })
                    })
                    if (forceToolCall) {
                        put("toolConfig", buildJsonObject {
                            put("functionCallingConfig", buildJsonObject {
                                put("mode", "ANY")
                            })
                        })
                    }
                }
                put("generationConfig", buildJsonObject {
                    put("temperature", 0.5)
                    val maxOutTokens = when (normalizedHydrogenLevel) {
                        "HIGH" -> 16384
                        "MEDIUM" -> 8192
                        "LOW" -> 4096
                        else -> 8192
                    }
                    put("maxOutputTokens", maxOutTokens)
                    if (normalizedHydrogenLevel != null) {
                        put("thinkingConfig", buildJsonObject {
                            put("thinkingLevel", normalizedHydrogenLevel)
                        })
                    }
                })
                put("safetySettings", buildJsonArray {
                    listOf(
                        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                        "HARM_CATEGORY_HARASSMENT",
                        "HARM_CATEGORY_HATE_SPEECH",
                        "HARM_CATEGORY_DANGEROUS_CONTENT",
                        "HARM_CATEGORY_CIVIC_INTEGRITY"
                    ).forEach { cat ->
                        add(buildJsonObject {
                            put("category", cat)
                            put("threshold", "BLOCK_NONE")
                        })
                    }
                })
            })
        }.toString()

        val reqBuilder = Request.Builder()
            .url("${OAuthAccountPool.BASE_URL_DAILY}:streamGenerateContent?alt=sse")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("User-Agent", OAuthAccountPool.USER_AGENT)
            .addHeader("Content-Type", "application/json")
            .addHeader("x-client-name", "antigravity")
            .addHeader("x-client-version", "4.3.0")

        if (model.contains("claude")) {
            reqBuilder.addHeader(
                "anthropic-beta",
                "claude-code-20250219,interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14"
            )
        }

        val call = client.newCall(reqBuilder.post(payload.toRequestBody("application/json".toMediaType())).build())
        val shouldTrack = trackActiveCall || callTag != "foreground"
        if (shouldTrack) {
            activeCalls[callTag] = call
        }

        val textBuf = StringBuilder()
        val funcCalls = mutableListOf<GeminiFuncCall>()
        var lastFinishReason = ""
        var lastUsageSummary = ""

        try {
            call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val errStr = resp.body.string()
                throw IllegalStateException("HTTP ${resp.code}: ${errStr.take(300)}")
            }

            val reader = BufferedReader(InputStreamReader(resp.body.byteStream(), Charsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                currentCoroutineContext().ensureActive()
                val rawLine = line?.trim().orEmpty()
                if (!rawLine.startsWith("data:")) continue
                val dataStr = rawLine.removePrefix("data:").trim()
                if (dataStr == "[DONE]") break
                if (dataStr.isEmpty()) continue

                val chunk = runCatching { json.parseToJsonElement(dataStr).asObjOrNull() }.getOrNull() ?: continue
                val respObj = chunk["response"].asObjOrNull() ?: chunk
                respObj["usageMetadata"]?.asObjOrNull()?.let { lastUsageSummary = it.toString() }
                val candidates = respObj["candidates"].asArrOrNull() ?: continue
                if (candidates.isEmpty()) continue
                val cand0 = candidates[0].asObjOrNull() ?: continue
                val finishReason = cand0["finishReason"].asStrOrNull().orEmpty()
                if (finishReason.isNotBlank()) lastFinishReason = finishReason
                if (finishReason.equals("SAFETY", ignoreCase = true)) {
                    throw IllegalStateException("SAFETY block on $model")
                }
                val parts = cand0["content"].asObjOrNull()?.get("parts").asArrOrNull() ?: continue
                for (pEl in parts) {
                    val pObj = pEl.asObjOrNull() ?: continue
                    val isThought = pObj["thought"]?.toString()?.equals("true", ignoreCase = true) == true
                    if (isThought) continue
                    val t = pObj["text"].asStrOrNull()
                    if (!t.isNullOrEmpty()) {
                        textBuf.append(t)
                        if (tools == null || tools.isEmpty()) {
                            onDelta(t)
                        }
                    }
                    val fc = pObj["functionCall"].asObjOrNull()
                    if (fc != null) {
                        val fname = fc["name"].asStrOrNull().orEmpty()
                        val fargs = fc["args"].asObjOrNull() ?: JsonObject(emptyMap())
                        val fid = fc["id"].asStrOrNull()
                        val sig = pObj["thoughtSignature"].asStrOrNull()
                        if (fname.isNotBlank()) {
                            funcCalls += GeminiFuncCall(
                                name = fname,
                                args = fargs,
                                id = fid,
                                thoughtSignature = sig,
                                rawPart = pObj
                            )
                        }
                    }
                }
            }
            }
        } finally {
            if (shouldTrack) {
                activeCalls.remove(callTag, call)
            }
        }

        if (textBuf.isEmpty() && funcCalls.isEmpty()) {
            Log.w(PipelineConfig.LOG_TAG, "[Antigravity] Empty SSE output on $model (finish=$lastFinishReason, usage=$lastUsageSummary)")
        }
        return SseRoundResult(textBuf.toString(), funcCalls)
    }

    private fun isAuthError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("http 401") || lower.contains("invalid_grant") || lower.contains("unauthorized")
    }

    private fun isQuotaError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("http 429") || lower.contains("resource_exhausted") || lower.contains("quota")
    }
}
