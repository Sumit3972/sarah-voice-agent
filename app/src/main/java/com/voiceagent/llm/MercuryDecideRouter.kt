package com.voiceagent.llm

import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.orchestrator.VoiceCommandFilters
import com.voiceagent.tools.SaraToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class RouteDecisionTarget {
    MERCURY_FAST,
    GEMINI_SMART
}

enum class IntentMode {
    PURE_CHAT,
    TOOL_ACTION
}

data class RoutingDecision(
    val target: RouteDecisionTarget,
    val intentMode: IntentMode,
    val selectedTools: Set<String>,
    val ackCategory: String,
    val spokenAckText: String,
    val needsVision: Boolean,
    val reason: String,
    val latencyMs: Long,
    val reasoningEffort: String = "instant",
    val shouldIgnore: Boolean = false,
    val addressedScore: Double = 1.0,
    val chatterScore: Double = 0.0
)

data class BargeInDecision(
    val shouldInterrupt: Boolean,
    val actionableScore: Double,
    val noiseScore: Double,
    val reason: String,
    val latencyMs: Long
)

class MercuryDecideRouter {
    companion object {
        internal fun shouldInterruptMidSpeech(
            actionableScore: Double,
            noiseScore: Double
        ): Boolean {
            if (noiseScore >= 0.45) return false
            return actionableScore >= 0.65
        }

        internal fun shouldIgnoreFollowUp(
            fromFollowUp: Boolean,
            addressedScore: Double,
            assistantAskedQuestion: Boolean,
            chatterScore: Double = 0.0
        ): Boolean {
            if (!fromFollowUp) return false
            if (chatterScore >= 0.50) return true
            val minScore = if (assistantAskedQuestion) 0.55 else 0.68
            return addressedScore < minScore
        }

        internal fun isComplexOrUnlockQuery(text: String): Boolean {
            val lower = text.lowercase(Locale.ROOT)
            return Regex("\\b(explain why|explain step by step|step by step|compare|architecture|debug|troubleshoot|fix|derive|proof|analyze deeply)\\b")
                .containsMatchIn(lower)
        }

        internal fun shouldIgnoreFollowUpOnFallback(
            fromFollowUp: Boolean,
            userUtterance: String,
            fallbackTools: List<String>,
            needsVision: Boolean,
            assistantAskedQuestion: Boolean
        ): Boolean {
            if (!fromFollowUp) return false
            if (fallbackTools.isNotEmpty() || needsVision) return false
            val trimmed = userUtterance.trim()
            if (assistantAskedQuestion && trimmed.matches(Regex("^\\d{4,8}$"))) return false
            return true
        }

        internal fun applyActiveAppUiTools(
            userUtterance: String,
            activePackage: String,
            tools: Set<String>
        ): Set<String> {
            val lower = userUtterance.lowercase(Locale.ROOT)
            val isExplicitAppUi = Regex("\\b(in|on)\\s+(chrome|browser|app|instagram|whatsapp|youtube|telegram|maps|spotify|settings|search bar)\\b|\\b(type|write|incognito|search bar|new tab|add another word|hit enter|press enter|click|tap|scroll)\\b")
                .containsMatchIn(lower)
            val isUserAppForeground = activePackage.isNotBlank() &&
                activePackage != "com.voiceagent" &&
                activePackage != "com.android.systemui" &&
                !activePackage.contains("launcher", ignoreCase = true)
            val hasUiIntentInForeground = isUserAppForeground &&
                Regex("\\b(search|find|open|type|write|tap|click|scroll|close|back|next|select|filter|add|play|send|reply|tab)\\b")
                    .containsMatchIn(lower)
            if (!isExplicitAppUi && !hasUiIntentInForeground) return tools
            return (tools + listOf("observe_screen", "ui_act"))
                .distinct()
                .take(6)
                .toSet()
        }

        internal fun resolveTargetAndLevel(
            bundle: String,
            rawLevel: String,
            mergedTools: Set<String>,
            needsVision: Boolean,
            activePackage: String,
            userUtterance: String
        ): Pair<RouteDecisionTarget, String> {
            val level = rawLevel.trim().lowercase(Locale.ROOT)
            val isLockscreenToolTurn = activePackage == "com.android.systemui" && mergedTools.isNotEmpty()
            if (bundle == "deep_reasoning" ||
                (bundle != "pure_chat" && level == "high") ||
                isComplexOrUnlockQuery(userUtterance)
            ) {
                return RouteDecisionTarget.GEMINI_SMART to "HIGH"
            }
            if (needsVision ||
                bundle == "screen_automation" ||
                (bundle != "pure_chat" && level == "medium") ||
                "observe_screen" in mergedTools ||
                "ui_act" in mergedTools
            ) {
                return RouteDecisionTarget.GEMINI_SMART to "MEDIUM"
            }
            if (bundle == "autonomous_automation" ||
                "autonomous_task" in mergedTools ||
                "swarm_dispatch" in mergedTools ||
                "shizuku_shell" in mergedTools ||
                "notification_center" in mergedTools ||
                isLockscreenToolTurn
            ) {
                return RouteDecisionTarget.GEMINI_SMART to "LOW"
            }
            return RouteDecisionTarget.MERCURY_FAST to "instant"
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastSelectedTools: Set<String> = emptySet()

    @Volatile
    private var lastActionTurnAtMs: Long = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(PipelineConfig.MERCURY_DECIDE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PipelineConfig.MERCURY_DECIDE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(PipelineConfig.MERCURY_DECIDE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    suspend fun decide(
        userUtterance: String,
        recentContextSummary: String,
        activePackage: String = "",
        hasCameraFrame: Boolean = false,
        fromFollowUp: Boolean = false,
        lastAssistantReply: String = ""
    ): RoutingDecision = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val fallbackTools = inferKeywordTools(userUtterance)
        val assistantAskedQuestion = lastAssistantReply.trimEnd().endsWith("?")

        if (hasCameraFrame) {
            return@withContext RoutingDecision(
                target = RouteDecisionTarget.GEMINI_SMART,
                intentMode = IntentMode.TOOL_ACTION,
                selectedTools = fallbackTools.take(2).toSet(),
                ackCategory = "checking_screen",
                spokenAckText = "Looking at that right now.",
                needsVision = true,
                reason = "camera_frame_attached",
                latencyMs = System.currentTimeMillis() - t0,
                reasoningEffort = "MEDIUM"
            )
        }

        val apiKey = SecretsProvider.inceptionApiKey()
        if (apiKey.isBlank()) {
            return@withContext buildHeuristicDecision(
                userUtterance, fallbackTools, activePackage, System.currentTimeMillis() - t0, "no_inception_key",
                fromFollowUp = fromFollowUp, assistantAskedQuestion = assistantAskedQuestion
            )
        }

        if (!fromFollowUp && isConversationalOrMicCheck(userUtterance)) {
            val elapsed = System.currentTimeMillis() - t0
            Log.i(PipelineConfig.LOG_TAG, "[MercuryDecide] ${elapsed}ms -> mode=PURE_CHAT (conversational_guard)")
            return@withContext RoutingDecision(
                target = RouteDecisionTarget.MERCURY_FAST,
                intentMode = IntentMode.PURE_CHAT,
                selectedTools = emptySet(),
                ackCategory = "none",
                spokenAckText = "",
                needsVision = false,
                reason = "conversational_guard",
                latencyMs = elapsed,
                reasoningEffort = "instant"
            )
        }

        val url = "${SecretsProvider.inceptionBaseUrl().trimEnd('/')}/decisions"
        val payload = buildJsonObject {
            put("model", SecretsProvider.inceptionRouterModel())
            put("state", buildJsonObject {
                put("active_screen_package", activePackage.ifBlank { "unknown" })
                put("recent_history", recentContextSummary.take(PipelineConfig.DECIDE_STATE_MAX_CHARS))
                put("user_utterance", userUtterance)
                put("is_followup_without_wakeword", fromFollowUp)
                if (lastAssistantReply.isNotBlank()) {
                    put("last_assistant_reply", lastAssistantReply.take(300))
                }
                put("assistant_asked_question", assistantAskedQuestion)
            })
            put("questions", buildJsonObject {
                put("action_bundle", buildJsonObject {
                    put("type", "choice")
                    put(
                        "instructions",
                        "Classify the user request into the exact minimal tool bundle needed, or pure_chat if zero phone/memory/web tools are needed."
                    )
                    put("criteria", buildJsonObject {
                        put("pure_chat", "Normal conversation, storytelling, conceptual explanation, jokes, advice, greetings, general knowledge needing no live lookup or phone action")
                        put("deep_reasoning", "Complex multi-step math derivation, tricky coding architecture, or multi-step reasoning")
                        put("device_intent_only", "Open app, screen on/off, lock screen, close all background apps, force-stop app, clear app cache, show recents, take screenshot, toggle wifi/mobile data/bluetooth/hotspot/location/airplane/auto-rotate/DND/dark mode/battery saver, set alarm/timer, play music/YouTube, Maps navigation, camera, volume, brightness, flashlight, clipboard write, settings panel, or multi-step shell batch")
                        put("contacts_and_device", "Call or WhatsApp message a named person, lookup contact, read/create calendar event, set/list/cancel spoken voice reminder")
                        put("autonomous_automation", "Start/stop/pause/list autonomous background loops, continuous chat-on-behalf until stopped ('chat with X until I say stop', 'auto reply'), recurring interval watches ('check every N minutes'), or scheduled future autonomous actions ('wake up at X and do Y')")
                        put("screen_automation", "Scroll Instagram/YouTube/Reels, read screen content, tap on-screen buttons, comment on posts, navigate inside an open app UI, retry a screen action, or run UI shell fallback")
                        put("notification_ops", "Check recent notifications, extract latest OTP or verification code, read incoming WhatsApp/SMS alerts, send inline quick reply to a notification, dismiss notifications")
                        put("live_info_lookup", "Today's weather, forecast, live news, sports score, stock price, web search, unit/currency conversion, exact math calculation")
                        put("device_status_check", "Check battery level, CPU temperature/usage, thermal status, hardware/Android info, charging, running background apps, RAM memory usage, storage space, wifi, bluetooth, DND, ringer, current volume, clipboard text, or installed apps")
                        put("memory_and_session", "Remember, list, edit, or forget personal facts, OR recall, list, summarize, rename, delete, or start a new chat session")
                    })
                })
                put("needs_vision", buildJsonObject {
                    put("type", "noul")
                    put(
                        "instructions",
                        "Does the request require visually understanding an image, video/Reel frame, unlabeled icon, map, or photo on screen?"
                    )
                })
                put("reasoning_level", buildJsonObject {
                    put("type", "choice")
                    put(
                        "instructions",
                        "Select reasoning effort: instant for fast chat or simple 1-shot actions, medium for standard UI navigation, shell checks, or vision, high for deep reasoning, complex multi-step planning, or lockscreen/troubleshooting workflows."
                    )
                    put("criteria", buildJsonObject {
                        put("instant", "Pure chat, quick Q&A, greetings, weather/web lookup, memory save/read, simple battery/status check, or simple 1-shot device toggle/volume/brightness/alarm/single app launch")
                        put("medium", "Standard Android UI automation (observe_screen/ui_act), reading notifications/OTPs, standard shell diagnostics, or screen vision analysis")
                        put("high", "Deep multi-step reasoning, step-by-step explanations, complex cross-app workflows, debugging/troubleshooting, math/coding derivation, or lockscreen unlock + multi-step task")
                    })
                })
                if (fromFollowUp) {
                    put("addressed_to_assistant", buildJsonObject {
                        put("type", "noul")
                        put(
                            "instructions",
                            "The voice assistant just finished speaking and is briefly listening without a wake word. Return high ONLY if the speaker is clearly talking directly TO the phone assistant (a follow-up request, phone command, or direct short answer to last_assistant_reply). Strictly ignore ambient background speech, TV/YouTube audio, phone/room chatter, incomplete fragments, or overheard multi-person conversation even if it contains words like no/yes/नहीं/हाँ."
                        )
                    })
                    put("is_room_chatter_or_third_party", buildJsonObject {
                        put("type", "noul")
                        put(
                            "instructions",
                            "Is user_utterance likely ambient room chatter, TV/video dialogue, garbled mic noise, or someone talking to another person nearby (for example naming another person or saying 'tell him/her', 'उसको बोलो', side-discussion) rather than talking clearly and intentionally to the phone assistant?"
                        )
                    })
                }
                put("ack_category", buildJsonObject {
                    put("type", "choice")
                    put("instructions", "Choose a quick spoken acknowledgment category while action tools execute, or none for pure_chat.")
                    put("criteria", buildJsonObject {
                        put("none", "Pure chat requiring zero tool execution or instant 1-word chat")
                        put("opening_app", "Opening an app, camera, maps navigation, or media player")
                        put("sending_message", "Sending or replying to a WhatsApp or SMS message")
                        put("placing_call", "Calling a contact or dialing a number")
                        put("setting_alarm", "Setting an alarm, timer, calendar event, or voice reminder")
                        put("checking_screen", "Looking at the screen, scrolling Reels, tapping UI buttons, or checking notifications")
                        put("checking_info", "Fetching weather, web search, battery, or device status")
                        put("updating_setting", "Changing volume, brightness, flashlight, or device settings")
                        put("checking_memory", "Saving, searching, updating, or deleting saved memory facts or past sessions")
                    })
                })
            })
        }.toString()

        try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Log.w(PipelineConfig.LOG_TAG, "[MercuryDecide] HTTP ${resp.code} in ${elapsed}ms: ${body.take(180)}")
                    return@withContext buildHeuristicDecision(
                        userUtterance, fallbackTools, activePackage, elapsed, "decide_http_${resp.code}",
                        fromFollowUp = fromFollowUp, assistantAskedQuestion = assistantAskedQuestion
                    )
                }

                val root = json.parseToJsonElement(body).jsonObject
                val answers = root["answers"]?.jsonObject
                    ?: return@withContext buildHeuristicDecision(
                        userUtterance, fallbackTools, activePackage, elapsed, "decide_no_answers",
                        fromFollowUp = fromFollowUp, assistantAskedQuestion = assistantAskedQuestion
                    )

                val addressedScore = if (fromFollowUp) {
                    answers["addressed_to_assistant"]?.jsonObject?.get("noul")?.jsonPrimitive?.doubleOrNull ?: 1.0
                } else {
                    1.0
                }
                val chatterScore = if (fromFollowUp) {
                    answers["is_room_chatter_or_third_party"]?.jsonObject?.get("noul")?.jsonPrimitive?.doubleOrNull ?: 0.0
                } else {
                    0.0
                }
                if (shouldIgnoreFollowUp(fromFollowUp, addressedScore, assistantAskedQuestion, chatterScore)) {
                    Log.i(
                        PipelineConfig.LOG_TAG,
                        "[MercuryDecide] ${elapsed}ms -> IGNORE background follow-up (addressed=${"%.2f".format(addressedScore)}, chatter=${"%.2f".format(chatterScore)}): '$userUtterance'"
                    )
                    return@withContext RoutingDecision(
                        target = RouteDecisionTarget.MERCURY_FAST,
                        intentMode = IntentMode.PURE_CHAT,
                        selectedTools = emptySet(),
                        ackCategory = "none",
                        spokenAckText = "",
                        needsVision = false,
                        reason = "ignored_background_followup(${"%.2f".format(addressedScore)}/${"%.2f".format(chatterScore)})",
                        latencyMs = elapsed,
                        reasoningEffort = "instant",
                        shouldIgnore = true,
                        addressedScore = addressedScore,
                        chatterScore = chatterScore
                    )
                }

                val bundle = answers["action_bundle"]?.jsonObject?.get("choice")?.jsonPrimitive?.content ?: "pure_chat"
                val needsVisionScore = answers["needs_vision"]?.jsonObject?.get("noul")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val needsVision = needsVisionScore >= 0.85 || isExplicitVisualQuery(userUtterance)
                val rawLevel = answers["reasoning_level"]?.jsonObject?.get("choice")?.jsonPrimitive?.content ?: "instant"
                val rawAck = answers["ack_category"]?.jsonObject?.get("choice")?.jsonPrimitive?.content ?: "none"

                val bundleTools = mapBundleToTools(bundle, needsVision, userUtterance)
                val combinedTools = (bundleTools + fallbackTools)
                    .filter { it in SaraToolRegistry.ALL_TOOL_NAMES }
                    .distinct()
                    .take(6)
                    .toSet()
                val mergedTools = applyActiveAppUiTools(
                    userUtterance = userUtterance,
                    activePackage = activePackage,
                    tools = applyStickyFollowUpTools(userUtterance, combinedTools)
                )

                val intentMode = if (mergedTools.isNotEmpty() || needsVision) IntentMode.TOOL_ACTION else IntentMode.PURE_CHAT
                val (target, effort) = resolveTargetAndLevel(
                    bundle = bundle,
                    rawLevel = rawLevel,
                    mergedTools = mergedTools,
                    needsVision = needsVision,
                    activePackage = activePackage,
                    userUtterance = userUtterance
                )

                val resolvedAckCat = if (intentMode == IntentMode.PURE_CHAT) {
                    "none"
                } else if (rawAck == "none") {
                    inferAckFromTools(mergedTools, userUtterance)
                } else {
                    rawAck
                }

                val decision = RoutingDecision(
                    target = target,
                    intentMode = intentMode,
                    selectedTools = mergedTools,
                    ackCategory = resolvedAckCat,
                    spokenAckText = "",
                    needsVision = needsVision,
                    reason = "$bundle($effort) tools=${mergedTools.joinToString(",")}",
                    latencyMs = elapsed,
                    reasoningEffort = effort
                )
                Log.i(
                    PipelineConfig.LOG_TAG,
                    "[MercuryDecide] ${elapsed}ms -> mode=$intentMode target=$target effort=$effort tools=${decision.selectedTools} ack='$resolvedAckCat' vision=$needsVision"
                )
                return@withContext decision
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            Log.w(PipelineConfig.LOG_TAG, "[MercuryDecide] Fallback in ${elapsed}ms: ${e.message}")
            return@withContext buildHeuristicDecision(
                userUtterance, fallbackTools, activePackage, elapsed, "decide_timeout_fallback",
                fromFollowUp = fromFollowUp, assistantAskedQuestion = assistantAskedQuestion
            )
        }
    }

    suspend fun decideMidSpeechInterruption(
        heardText: String,
        assistantSpokenText: String,
        activePackage: String = ""
    ): BargeInDecision = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val clean = heardText.trim()
        if (clean.isBlank() || VoiceCommandFilters.isLowValueFollowUpNoise(clean, assistantAskedQuestion = false)) {
            return@withContext BargeInDecision(
                shouldInterrupt = false,
                actionableScore = 0.0,
                noiseScore = 1.0,
                reason = "local_filler_noise",
                latencyMs = System.currentTimeMillis() - t0
            )
        }

        val apiKey = SecretsProvider.inceptionApiKey()
        if (apiKey.isBlank()) {
            val heuristicTools = inferKeywordTools(clean)
            val wordCount = clean.split(Regex("\\s+")).count { it.isNotBlank() }
            val interrupt = heuristicTools.isNotEmpty() || wordCount >= 4
            return@withContext BargeInDecision(
                shouldInterrupt = interrupt,
                actionableScore = if (interrupt) 0.75 else 0.25,
                noiseScore = if (interrupt) 0.15 else 0.70,
                reason = if (interrupt) "heuristic_interrupt" else "heuristic_ignore",
                latencyMs = System.currentTimeMillis() - t0
            )
        }

        val url = "${SecretsProvider.inceptionBaseUrl().trimEnd('/')}/decisions"
        val payload = buildJsonObject {
            put("model", SecretsProvider.inceptionRouterModel())
            put("state", buildJsonObject {
                put("active_screen_package", activePackage.ifBlank { "unknown" })
                put("assistant_currently_speaking", assistantSpokenText.take(300))
                put("user_mid_speech_utterance", clean)
            })
            put("questions", buildJsonObject {
                put("is_actionable_interruption", buildJsonObject {
                    put("type", "noul")
                    put(
                        "instructions",
                        "The voice assistant is currently speaking aloud. Return high ONLY if user_mid_speech_utterance is a real interruption directed at the assistant: a correction, new command, follow-up question, or request to change/shorten/stop what the assistant is saying."
                    )
                })
                put("is_backchannel_or_noise", buildJsonObject {
                    put("type", "noul")
                    put(
                        "instructions",
                        "Is user_mid_speech_utterance just a passive backchannel (hmm, okay, right, achha, haan, yeah), laughter/cough, TV/video dialogue, assistant self-echo, or someone talking to another person in the room?"
                    )
                })
            })
        }.toString()

        try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - t0
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Log.w(PipelineConfig.LOG_TAG, "[MercuryDecide:BargeIn] HTTP ${resp.code} in ${elapsed}ms")
                    val interrupt = inferKeywordTools(clean).isNotEmpty()
                    return@withContext BargeInDecision(
                        shouldInterrupt = interrupt,
                        actionableScore = if (interrupt) 0.70 else 0.20,
                        noiseScore = if (interrupt) 0.20 else 0.65,
                        reason = "bargein_http_${resp.code}",
                        latencyMs = elapsed
                    )
                }

                val root = json.parseToJsonElement(body).jsonObject
                val answers = root["answers"]?.jsonObject
                val actionableScore = answers?.get("is_actionable_interruption")
                    ?.jsonObject?.get("noul")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val noiseScore = answers?.get("is_backchannel_or_noise")
                    ?.jsonObject?.get("noul")?.jsonPrimitive?.doubleOrNull ?: 0.5
                val interrupt = shouldInterruptMidSpeech(actionableScore, noiseScore)
                val reason = "${if (interrupt) "interrupt" else "ignore"}(act=${"%.2f".format(actionableScore)},noise=${"%.2f".format(noiseScore)})"
                Log.i(PipelineConfig.LOG_TAG, "[MercuryDecide:BargeIn] ${elapsed}ms -> $reason: '$clean'")
                return@withContext BargeInDecision(
                    shouldInterrupt = interrupt,
                    actionableScore = actionableScore,
                    noiseScore = noiseScore,
                    reason = reason,
                    latencyMs = elapsed
                )
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            Log.w(PipelineConfig.LOG_TAG, "[MercuryDecide:BargeIn] Fallback in ${elapsed}ms: ${e.message}")
            val interrupt = inferKeywordTools(clean).isNotEmpty()
            return@withContext BargeInDecision(
                shouldInterrupt = interrupt,
                actionableScore = if (interrupt) 0.70 else 0.20,
                noiseScore = if (interrupt) 0.20 else 0.65,
                reason = "bargein_fallback",
                latencyMs = elapsed
            )
        }
    }

    private fun isConversationalOrMicCheck(userUtterance: String): Boolean {
        val lower = userUtterance.lowercase(Locale.ROOT).trim()
        val micOrChat = Regex(
            "^((hey|hi|hello|ok|okay|yo|arey)\\s*)?(can you (still )?hear me|do you hear me|are you (still )?there|you there|how are you|who are you|what's up|wassup|thank you|thanks|good morning|good night|good afternoon|good evening)([?.!,\\s]*)$"
        )
        return micOrChat.matches(lower)
    }

    private fun applyStickyFollowUpTools(userUtterance: String, candidateTools: Set<String>): Set<String> {
        val now = System.currentTimeMillis()
        if (candidateTools.isNotEmpty()) {
            lastSelectedTools = candidateTools
            lastActionTurnAtMs = now
            return candidateTools
        }
        if (lastSelectedTools.isEmpty() || (now - lastActionTurnAtMs) > 90_000L || isConversationalOrMicCheck(userUtterance)) {
            return emptySet()
        }
        val lower = userUtterance.lowercase(Locale.ROOT).trim()
        val pureSocial = Regex("^(ok|okay|thanks|thank you|cool|nice|great|awesome|got it|bye|good bye|goodbye|good night|good morning|haha|lol|hm+|hmm+|yes|no|yeah|nope)([!.?\\s]*)$")
        if (pureSocial.matches(lower)) {
            return emptySet()
        }
        val hasActionFollowUp = Regex("\\b(it|them|that|those|try again|not closed|still open|still there|still running|can't see|cannot see|didn't work|didnt work|do it|check it|open it|close it|close them|tap it|press it|show me|what happened)\\b")
            .containsMatchIn(lower)
        if (hasActionFollowUp) {
            lastActionTurnAtMs = now
            return (lastSelectedTools + listOf("device_intent", "device_status", "observe_screen", "ui_act"))
                .distinct()
                .take(6)
                .toSet()
        }
        return emptySet()
    }

    internal fun mapBundleToTools(bundle: String, needsVision: Boolean, userUtterance: String): List<String> {
        val lower = userUtterance.lowercase(Locale.ROOT)
        val isUiOrShellIntent = Regex("\\b(screen|tap|click|scroll|unlock|pin|password|passcode|shell|adb|shizuku|uninstall|clear data|disable|enable app|macro|batch|search and|and then|after that|filter|date|options|menu|settings)\\b")
            .containsMatchIn(lower)
        val isShellStatus = Regex("\\b(cpu|processor|thermal|temp|heating|gpu|hardware|dumpsys|getprop|top processes|ip address|refresh rate|dpi|resolution)\\b")
            .containsMatchIn(lower)

        val base = when (bundle) {
            "device_intent_only" -> if (isUiOrShellIntent) {
                listOf("device_intent", "observe_screen", "ui_act", "shizuku_shell")
            } else {
                listOf("device_intent")
            }
            "contacts_and_device" -> listOf("personal_scheduler", "device_intent", "observe_screen", "ui_act")
            "autonomous_automation" -> listOf("autonomous_task", "swarm_dispatch", "personal_scheduler", "notification_center", "device_intent")
            "screen_automation" -> listOf("observe_screen", "ui_act", "device_intent", "shizuku_shell")
            "notification_ops" -> listOf("notification_center", "observe_screen", "ui_act", "device_status")
            "live_info_lookup" -> listOf("live_info")
            "device_status_check" -> if (isShellStatus) listOf("device_status", "shizuku_shell") else listOf("device_status")
            "memory_and_session" -> listOf("memory_facts", "session_store")
            else -> emptyList()
        }
        return if (needsVision && "observe_screen" !in base) {
            listOf("observe_screen", "ui_act") + base
        } else {
            base
        }
    }

    internal fun inferKeywordTools(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        if (isConversationalOrMicCheck(text)) return emptyList()
        val out = linkedSetOf<String>()

        if (Regex("\\b(whatsapp|call|dial|alarm|timer|open |launch |navigate|directions|play |youtube|spotify|insta|instagram|flashlight|torch|brightness|volume|clipboard copy|close app|close all|close them|clear all|clear background|kill |force stop|clear cache|recents|recent apps|screenshot|hotspot|tethering|location|gps|airplane|auto rotate|rotation|dark mode|battery saver|power save|screen timeout|turn on|turn off|enable |disable |close this|exit app|come back|go back|screen\\s+(on|off)|turn\\s+.*screen\\s+(on|off)|lock\\s+(my\\s+)?(phone|screen)|unlock|passcode|pin)\\b").containsMatchIn(lower)) {
            out += "device_intent"
        }
        if (Regex("\\b(call |whatsapp |remind|reminder|calendar|schedule|meeting|event|contact)\\b").containsMatchIn(lower)) {
            out += "personal_scheduler"
        }
        if (Regex("\\b(on behalf of me|until i say stop|keep chatting|auto[- ]?reply|chat with .* on behalf|every \\d+ (min|minute|minutes|hour|hours)|watch for|monitor .* every|wake up (at|in)|background tasks?|background loops?|autonomous|stop chatting|stop all tasks?|stop watching|pause tasks?|resume tasks?|list tasks?)\\b").containsMatchIn(lower)) {
            out += "autonomous_task"
        }
        if (Regex("\\b(swarm|parallel|simultaneously|at the same time|in parallel|all at once|multiple workers)\\b").containsMatchIn(lower)) {
            out += "swarm_dispatch"
        }
        if (Regex("\\b(notification|notifications|unread|who messaged|reply to|dismiss notification|otp|verification code|passcode|code from)\\b").containsMatchIn(lower)) {
            out += "notification_center"
        }
        if (Regex("\\b(battery|charging|wifi|wi-fi|bluetooth|dnd|do not disturb|installed app|clipboard|running|background|ram|memory usage|storage|disk|space|what apps are open|which apps|open apps|temperature|temp|heating|cpu|processor)\\b").containsMatchIn(lower)) {
            out += "device_status"
        }
        if (Regex("\\b(cpu|processor|thermal|gpu|hardware|refresh rate|hz|resolution|dpi|ip address|network info|dumpsys|getprop|appops|permissions?|shell|shizuku|adb|command|uninstall|clear data|disable app|enable app|macro|batch|top processes|android version|temperature|heating)\\b").containsMatchIn(lower)) {
            out += "shizuku_shell"
        }
        if (Regex("\\b(weather|forecast|temperature|rain|score|news|search|google |convert|calculate|plus|minus|divided by)\\b").containsMatchIn(lower)) {
            out += "live_info"
        }
        if (Regex("\\b(screen|reel|reels|scroll|tap|click|comment|look at|what is shown|go back|come back|home screen|inbox|dm|dms|tab|profile|explore|feed|close app|close this|not close|can't see|message|recents|recent apps|options|what options)\\b").containsMatchIn(lower)) {
            out += "observe_screen"
            out += "ui_act"
        }
        if (Regex("\\b(not closed|still open|still running|can't see|cannot see|didn't work|didnt work|try again|do it|close it|show me|close them|you check)\\b").containsMatchIn(lower)) {
            out += "device_intent"
            out += "device_status"
            out += "observe_screen"
            out += "ui_act"
        }
        if (Regex("\\b(remember|forget|memory|facts?|what do you know about me)\\b").containsMatchIn(lower)) {
            out += "memory_facts"
        }
        if (Regex("\\b(session|sessions|previous chat|last conversation|new session|fresh session|delete session)\\b").containsMatchIn(lower)) {
            out += "session_store"
        }
        return out.toList()
    }

    private fun isExplicitVisualQuery(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return Regex("\\b(look at the screen|look at my screen|see my screen|what do you see|see on my screen|see that real text|read my screen|process it|this reel|what is in this photo|read this image)\\b")
            .containsMatchIn(lower)
    }

    private fun inferAckFromTools(tools: Set<String>, text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            "observe_screen" in tools || "ui_act" in tools || "notification_center" in tools -> "checking_screen"
            "personal_scheduler" in tools && (lower.contains("call") || lower.contains("dial")) -> "placing_call"
            "personal_scheduler" in tools && (lower.contains("whatsapp") || lower.contains("message")) -> "sending_message"
            "personal_scheduler" in tools || lower.contains("alarm") || lower.contains("timer") -> "setting_alarm"
            "live_info" in tools || "device_status" in tools -> "checking_info"
            "memory_facts" in tools || "session_store" in tools -> "checking_memory"
            "device_intent" in tools && (lower.contains("volume") || lower.contains("brightness") || lower.contains("torch") || lower.contains("flashlight")) -> "updating_setting"
            "device_intent" in tools -> "opening_app"
            else -> "checking_info"
        }
    }

    private fun buildHeuristicDecision(
        userUtterance: String,
        fallbackTools: List<String>,
        activePackage: String,
        elapsedMs: Long,
        reason: String,
        fromFollowUp: Boolean = false,
        assistantAskedQuestion: Boolean = false
    ): RoutingDecision {
        val selected = applyActiveAppUiTools(
            userUtterance = userUtterance,
            activePackage = activePackage,
            tools = applyStickyFollowUpTools(userUtterance, fallbackTools.take(6).toSet())
        )
        val needsVision = isExplicitVisualQuery(userUtterance)
        val ignoreOnFallback = shouldIgnoreFollowUpOnFallback(
            fromFollowUp = fromFollowUp,
            userUtterance = userUtterance,
            fallbackTools = selected.toList(),
            needsVision = needsVision,
            assistantAskedQuestion = assistantAskedQuestion
        )
        val mode = if (selected.isNotEmpty() || needsVision) IntentMode.TOOL_ACTION else IntentMode.PURE_CHAT
        val (target, effort) = resolveTargetAndLevel(
            bundle = "",
            rawLevel = "",
            mergedTools = selected,
            needsVision = needsVision,
            activePackage = activePackage,
            userUtterance = userUtterance
        )
        val ackCat = if (mode == IntentMode.TOOL_ACTION) inferAckFromTools(selected, userUtterance) else "none"
        return RoutingDecision(
            target = target,
            intentMode = mode,
            selectedTools = selected,
            ackCategory = ackCat,
            spokenAckText = "",
            needsVision = needsVision,
            reason = if (ignoreOnFallback) "${reason}_ignored_followup" else "$reason($effort)",
            latencyMs = elapsedMs,
            reasoningEffort = effort,
            shouldIgnore = ignoreOnFallback
        )
    }
}
