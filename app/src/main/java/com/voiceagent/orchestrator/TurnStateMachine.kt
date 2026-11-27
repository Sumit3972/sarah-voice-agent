package com.voiceagent.orchestrator

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.audio.AudioFrameAnalysis
import com.voiceagent.audio.AudioPipeline
import com.voiceagent.audio.CallAndAudioFocusCoordinator
import com.voiceagent.audio.LocalWakeWordEngine
import com.voiceagent.audio.LowLatencyAudioPlayer
import com.voiceagent.audio.PreRollRingBuffer
import com.voiceagent.audio.SmartTurnDetector
import com.voiceagent.audio.WakeCuePlayer
import com.voiceagent.autonomous.SwarmOrchestrator
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.config.WakeCueMode
import com.voiceagent.config.WakeSensitivity
import com.voiceagent.debug.ToolTraceLogger
import com.voiceagent.llm.IntentMode
import com.voiceagent.llm.LlmStreamEvent
import com.voiceagent.llm.RouteDecisionTarget
import com.voiceagent.llm.StreamingLlmRouter
import com.voiceagent.memory.AsyncFactExtractor
import com.voiceagent.memory.ContextWindowManager
import com.voiceagent.memory.MemoryDao
import com.voiceagent.memory.SessionManager
import com.voiceagent.stt.DeepgramRealtimeWsClient
import com.voiceagent.stt.SttEvent
import com.voiceagent.tools.ConfirmPolicyGate
import com.voiceagent.tools.PendingSafetyConfirmation
import com.voiceagent.tools.SaraToolRegistry
import com.voiceagent.tts.ClauseStreamBuffer
import com.voiceagent.tts.EmotionTagNormalizer
import com.voiceagent.tts.FishAudioLiveWsClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

enum class ConversationPhase {
    STOPPED,
    IDLE_STANDBY,
    WAKE_CHECK,
    LISTENING,
    SPEAKING,
    PAUSE_WAIT,
    THINKING,
    SPEAKING_REPLY
}

object VoiceCommandFilters {
    private val STOP_PHRASES = setOf(
        "stop", "stop it", "stop talking", "stop speaking", "stop please", "please stop",
        "quiet", "be quiet", "shut up", "cancel", "pause", "enough",
        "never mind", "nevermind", "forget it", "leave it", "ok stop", "okay stop",
        "bas", "ruko", "chup", "band karo", "बस", "रुको", "चुप"
    )

    private val WAIT_INTERRUPT_PHRASES = setOf(
        "wait", "wait a second", "wait a minute", "hold on", "hang on",
        "excuse me", "listen", "suno", "सुनो", "एक मिनट"
    )

    private val LEADING_GREETINGS = setOf(
        "hey", "hi", "hello", "ok", "okay", "listen", "yo", "arey", "sun"
    )

    fun normalizeText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s\\u0900-\\u0963\\u0966-\\u097F]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun isWordChar(c: Char): Boolean {
        if (c.isLetterOrDigit()) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    fun stripWakeWord(text: String): String? {
        val lower = text.lowercase()
        for (variant in PipelineConfig.WAKE_VARIANTS) {
            var searchFrom = 0
            while (searchFrom < lower.length) {
                val idx = lower.indexOf(variant, startIndex = searchFrom)
                if (idx < 0) break
                val beforeOk = idx == 0 || !isWordChar(lower[idx - 1])
                val afterIdx = idx + variant.length
                val afterOk = afterIdx >= lower.length || !isWordChar(lower[afterIdx])
                if (beforeOk && afterOk) {
                    val beforeRaw = text.substring(0, idx).trim(' ', ',', '.', '!', '?', ':', ';', '-')
                    val beforeTokens = beforeRaw.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val keptBefore = beforeTokens.dropWhile { it.lowercase().trim(',', '.', '!', '?') in LEADING_GREETINGS }
                    val beforeClean = keptBefore.joinToString(" ")
                    val afterClean = text.substring(afterIdx).trimStart(',', ' ', '.', '!', '?', ':', ';', '-')
                    return listOf(beforeClean, afterClean).filter { it.isNotEmpty() }.joinToString(" ")
                }
                searchFrom = idx + variant.length
            }
        }
        return null
    }

    fun isStopCommand(text: String): Boolean {
        val afterWake = stripWakeWord(text) ?: text
        val norm = normalizeText(afterWake)
        return norm in STOP_PHRASES
    }

    fun isDirectBargeInStop(text: String, assistantReplyText: String): Boolean {
        if (!isStopCommand(text)) return false
        if (stripWakeWord(text) != null) return true
        val norm = normalizeText(text)
        val normReply = normalizeText(assistantReplyText)
        return norm.isNotEmpty() && !normReply.contains(norm)
    }

    fun isWaitInterruptCommand(text: String, assistantReplyText: String): Boolean {
        val afterWake = stripWakeWord(text) ?: text
        val norm = normalizeText(afterWake)
        if (norm !in WAIT_INTERRUPT_PHRASES) return false
        if (stripWakeWord(text) != null) return true
        val normReply = normalizeText(assistantReplyText)
        return norm.isNotEmpty() && !normReply.contains(norm)
    }

    /**
     * Strict keyword gate for barge-in during SPEAKING_REPLY / THINKING.
     * Returns "STOP", "WAIT", "WAKE", or null (ignore background chatter/echo).
     */
    fun shouldAcceptBargeInTranscript(heardText: String, assistantReplyText: String): String? {
        if (isDirectBargeInStop(heardText, assistantReplyText)) return "STOP"
        if (isWaitInterruptCommand(heardText, assistantReplyText)) return "WAIT"
        if (isLikelySelfEcho(heardText, assistantReplyText)) return null
        if (stripWakeWord(heardText) != null) return "WAKE"
        return null
    }

    fun isLikelySelfEcho(heardText: String, assistantReplyText: String): Boolean {
        if (assistantReplyText.isBlank()) return false
        if (isStopCommand(heardText)) return false

        val normHeard = normalizeText(heardText)
        if (normHeard.isBlank()) return false
        val normReply = normalizeText(assistantReplyText)
        if (normReply.isBlank()) return false

        val afterWake = stripWakeWord(heardText).orEmpty().trim()
        val normAfter = normalizeText(afterWake)
        if (stripWakeWord(heardText) != null && normAfter.length >= 4 && !normReply.contains(normAfter)) {
            return false
        }

        if (normHeard.length >= 4 && normReply.contains(normHeard)) {
            return true
        }

        val replyTokens = normReply.split(' ').filter { it.length >= 2 }.toSet()
        val heardTokens = normHeard.split(' ').filter { it.length >= 2 }
        if (heardTokens.isNotEmpty() && replyTokens.isNotEmpty()) {
            val matchedCount = heardTokens.count { it in replyTokens }
            val overlap = matchedCount.toDouble() / heardTokens.size.toDouble()
            if (heardTokens.size == 1 && matchedCount == 1) return true
            if (heardTokens.size >= 2 && overlap >= 0.65 && normHeard.length >= 6) return true
        }

        if (stripWakeWord(heardText) != null && normAfter.isBlank() && PipelineConfig.WAKE_VARIANTS.any { v ->
                Regex("(^|\\s)${Regex.escape(v)}(\\s|$)").containsMatchIn(normReply)
            }
        ) {
            return true
        }
        return false
    }

    private val FOLLOW_UP_FILLER_WORDS = setOf(
        "ok", "okay", "ओके", "haan", "हाँ", "han", "ji", "जी", "pe", "पे", "par", "पर",
        "hai", "है", "hun", "हूं", "hm", "hmm", "hmmm", "uh", "um", "oh", "ah", "case",
        "toh", "तो", "bas", "बस", "theek", "ठीक", "achha", "अच्छा", "accha", "sir", "सर"
    )

    fun isLowValueFollowUpNoise(text: String, assistantAskedQuestion: Boolean): Boolean {
        val norm = normalizeText(text)
        if (norm.isBlank()) return true
        val tokens = norm.split(' ').filter { it.isNotBlank() }
        if (assistantAskedQuestion) {
            if (norm.length < 2 && norm.none { it.isDigit() }) return true
            // Ignore pure conversational filler fragments even after a prompt question, while keeping yes/PIN/commands
            if (tokens.size <= 2 && tokens.all { it in FOLLOW_UP_FILLER_WORDS } && tokens.none { it in setOf("haan", "हाँ") && tokens.size == 1 }) {
                return true
            }
            return false
        }
        if (tokens.size <= 2 && tokens.all { it in FOLLOW_UP_FILLER_WORDS }) {
            return true
        }
        if (tokens.size == 1 && tokens.first().length < 3 && tokens.first().none { it.isDigit() }) {
            return true
        }
        return false
    }

    fun isWakeRescuedByPartialTail(partialTail: String, finalRaw: String): Boolean {
        val tail = normalizeText(partialTail)
        if (tail.length < 2) return false
        val finalNorm = normalizeText(finalRaw)
        return finalNorm.startsWith(tail)
    }

    private val WAKE_LOOKALIKE_REGEX = Regex(
        "\\b(saraswati|saravana|saratoga|saransh|sarang|sarathi|sarafa|सरस्वती|सराहना)\\b",
        RegexOption.IGNORE_CASE
    )

    fun containsWakeLookalikeWord(text: String): Boolean = WAKE_LOOKALIKE_REGEX.containsMatchIn(text)
}

data class LocalFastPathAction(
    val toolName: String,
    val action: String,
    val spokenConfirm: String
)

internal object LocalFastPathMatcher {
    private val POLITE_PREFIX_REGEX = Regex(
        "^(hey|hi|ok|okay|sarah|sara|please|kindly|can you|could you|would you|just|now)([,\\s]+|$)",
        RegexOption.IGNORE_CASE
    )
    private val MULTI_STEP_GUARD_REGEX = Regex(
        "\\b(and|then|after|also|before|while|because|if|when|except|but)\\b",
        RegexOption.IGNORE_CASE
    )

    fun match(userText: String): LocalFastPathAction? {
        var norm = userText.trim()
            .replace(Regex("[.!?,]+$"), "")
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
        repeat(5) {
            norm = POLITE_PREFIX_REGEX.replaceFirst(norm, "").trimStart(',', ' ')
        }
        norm = norm.removeSuffix(" please").removeSuffix(" now").trim()
        if (norm.isBlank() || MULTI_STEP_GUARD_REGEX.containsMatchIn(norm)) return null

        return when {
            Regex("^(turn off (the |my )?screen|screen off|lock (the |my )?(phone|screen|device))$").matches(norm) ->
                LocalFastPathAction("device_intent", "screen_off", "Screen turned off.")
            Regex("^(turn on (the |my )?screen|screen on|wake( up)? (the |my )?(phone|screen|device))$").matches(norm) ->
                LocalFastPathAction("device_intent", "screen_on", "Screen is on.")
            Regex("^unlock (the |my )?(phone|screen|device)$").matches(norm) ->
                LocalFastPathAction("device_intent", "unlock_device", "Phone unlocked.")
            Regex("^(go home|go to home screen|home screen)$").matches(norm) ->
                LocalFastPathAction("device_intent", "go_home", "Went home.")
            Regex("^(close all apps|close them all|clear (all )?background apps)$").matches(norm) ->
                LocalFastPathAction("device_intent", "close_all_apps", "Closed background apps.")
            Regex("^stop all (background )?(tasks?|loops?|routines?|jobs?|automations?)$").matches(norm) ->
                LocalFastPathAction("autonomous_task", "stop_all_tasks", "Stopped all autonomous tasks.")
            else -> null
        }
    }
}

data class PipelineUiSnapshot(
    val phase: ConversationPhase = ConversationPhase.STOPPED,
    val liveTranscript: String = "",
    val assistantReply: String = "",
    val activeModelBadge: String = "Standby",
    val routingReason: String = "",
    val activeSessionId: String = "",
    val wakeCueMode: WakeCueMode = WakeCueMode.CUSTOM_CHIME,
    val micMuted: Boolean = false,
    val lastLatencySummary: String = "",
    val noiseFloorDbfs: Double = PipelineConfig.INITIAL_NOISE_FLOOR_DBFS,
    val currentSnrDb: Double = 0.0,
    val isSpeechActive: Boolean = false,
    val sttLanguageCode: String = PipelineConfig.DEFAULT_LANGUAGE_CODE,
    val recentActionSteps: List<String> = emptyList(),
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.BALANCED,
    val pendingConfirmation: PendingSafetyConfirmation? = null,
    val callPaused: Boolean = false
)

class TurnStateMachine(
    private val appContext: Context,
    private val dao: MemoryDao,
    private val sessionManager: SessionManager,
    private val contextManager: ContextWindowManager,
    private val asyncFactExtractor: AsyncFactExtractor,
    private val toolRegistry: SaraToolRegistry,
    private val llmRouter: StreamingLlmRouter,
    private val sttClient: DeepgramRealtimeWsClient,
    private val fishClient: FishAudioLiveWsClient,
    private val audioPlayer: LowLatencyAudioPlayer,
    private val swarmOrchestrator: SwarmOrchestrator? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val audioPipeline = AudioPipeline(appContext)
    private val preRollRing = PreRollRingBuffer(PipelineConfig.PRE_ROLL_SAMPLES)
    private val smartTurn = SmartTurnDetector()
    private val wakeCuePlayer = WakeCuePlayer(appContext)
    private val callCoordinator = CallAndAudioFocusCoordinator(appContext)
    private val localWakeEngine = LocalWakeWordEngine(appContext)

    private val _state = MutableStateFlow(
        PipelineUiSnapshot(
            activeSessionId = sessionManager.activeSessionId(),
            wakeCueMode = SecretsProvider.getWakeCueMode(appContext),
            sttLanguageCode = SecretsProvider.getLanguageMode(appContext),
            wakeSensitivity = SecretsProvider.getWakeSensitivity(appContext)
        )
    )
    val state: StateFlow<PipelineUiSnapshot> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private val injectingTestAudio = AtomicBoolean(false)
    private val muteLock = ReentrantLock()
    private val muteCondition = muteLock.newCondition()
    private var captureThread: Thread? = null
    private var activeReplyJob: Job? = null
    private var sttCollectorJob: Job? = null
    private var aecEffect: AcousticEchoCanceler? = null
    private var nsEffect: NoiseSuppressor? = null

    @Volatile
    private var suppressBargeIn: Boolean = false

    @Volatile
    private var suppressVadUntilMs: Long = 0L

    @Volatile
    private var phaseEnteredAtMs: Long = System.currentTimeMillis()

    @Volatile
    private var currentPartialText: String = ""

    @Volatile
    private var finalTranscriptWaiter: kotlinx.coroutines.CompletableDeferred<String>? = null

    @Volatile
    private var dynamicWaitTargetMs: Long = PipelineConfig.T_WAIT_MID_CONF_MS

    // Acoustic tracking counters (accessed strictly on the single audio thread)
    private var consecutiveSpeechFrames = 0
    private var silenceAccumulatorMs = 0L
    private var utteranceDurationMs = 0L
    private var consecutivePeakVadFrames = 0
    @Volatile
    private var sttTurnOpen = false
    private val pendingSttFrames = java.util.concurrent.ConcurrentLinkedQueue<ShortArray>()
    private val sttTurnReady = AtomicBoolean(false)
    private val sttSendLock = Any()
    private val turnTranscriptSegments = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile
    private var wakeEarlyFlushSent = false
    @Volatile
    private var wakeResolving = false
    @Volatile
    private var wakeCuePlayedForTurn = false
    @Volatile
    private var lastStartedTurnText: String = ""
    @Volatile
    private var lastStartedTurnAtMs: Long = 0L
    @Volatile
    private var activeSpokenTtsText: String = ""
    @Volatile
    private var currentTurnFromFollowUp: Boolean = false
    @Volatile
    private var turnConfirmedByLocalWake: Boolean = false
    @Volatile
    private var wakeConfirmedBySttFinal: Boolean = false
    @Volatile
    private var partialWakeTailSnapshot: String = ""
    @Volatile
    private var pendingPostWakeCommand: Boolean = false
    private val speechTailRing = PreRollRingBuffer(16000)
    private val bargeInPreRoll = PreRollRingBuffer(PipelineConfig.PRE_ROLL_SAMPLES)
    @Volatile
    private var bargeInProbeOpen = false
    private var bargeInSpeechFrames = 0
    private var bargeInSilenceMs = 0L
    private var bargeInProbeElapsedMs = 0L
    private var bargeInLastFlushMs = 0L
    private val bargeInLock = Any()
    @Volatile
    private var bargeInDecideJob: Job? = null
    @Volatile
    private var lastBargeInCheckedText: String = ""
    @Volatile
    private var latestBargeInText: String = ""

    init {
        localWakeEngine.reconfigure(SecretsProvider.getWakeSensitivity(appContext))
        sttCollectorJob = scope.launch {
            sttClient.events.collect { ev ->
                when (ev) {
                    is SttEvent.Partial -> handleIncomingPartial(ev.text)
                    is SttEvent.Final -> handleIncomingFinal(ev.text)
                    is SttEvent.EndOfTurn -> handleIncomingEndOfTurn(ev.text, ev.confidence)
                    is SttEvent.Error -> Log.w(PipelineConfig.LOG_TAG, "[STT Event Error] ${ev.message}")
                    else -> {}
                }
            }
        }
        scope.launch {
            ConfirmPolicyGate.pendingConfirmation.collect { pending ->
                _state.update { it.copy(pendingConfirmation = pending) }
            }
        }
        scope.launch(Dispatchers.IO) {
            val activeId = sessionManager.resolveActiveSessionId { sys, prompt ->
                llmRouter.antigravityProvider.quickComplete(sys, prompt)
            }
            _state.update { it.copy(activeSessionId = activeId) }
        }
    }

    fun startAudioLoop() {
        if (running.getAndSet(true)) return
        setPhase(ConversationPhase.IDLE_STANDBY)
        scope.launch(Dispatchers.IO) {
            sttClient.ensureConnected(_state.value.sttLanguageCode)
        }
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runAudioCaptureLoop()
        }, "SarahAudioCapture").apply { start() }
    }

    fun stopAudioLoop() {
        running.set(false)
        signalMuteWaiters()
        cancelActiveReply()
        closeSttTurnIfOpen()
        runCatching { captureThread?.join(500) }
        captureThread = null
        setPhase(ConversationPhase.STOPPED)
    }

    fun toggleMute(): Boolean {
        val next = !muted.get()
        muted.set(next)
        signalMuteWaiters()
        _state.update { it.copy(micMuted = next) }
        if (next) {
            closeSttTurnIfOpen()
            setPhase(ConversationPhase.IDLE_STANDBY)
        }
        return next
    }

    private fun signalMuteWaiters() {
        muteLock.lock()
        try {
            muteCondition.signalAll()
        } finally {
            muteLock.unlock()
        }
    }

    fun setWakeCueMode(mode: WakeCueMode) {
        SecretsProvider.setWakeCueMode(appContext, mode)
        _state.update { it.copy(wakeCueMode = mode) }
    }

    fun setWakeSensitivity(sensitivity: WakeSensitivity) {
        SecretsProvider.setWakeSensitivity(appContext, sensitivity)
        _state.update { it.copy(wakeSensitivity = sensitivity) }
        localWakeEngine.reconfigure(sensitivity)
    }

    fun resolvePendingConfirmation(approved: Boolean) {
        ConfirmPolicyGate.resolveConfirmation(approved)
    }

    fun speakAssistantReply(text: String) {
        scope.launch {
            speakProactiveMessage(text)
        }
    }

    fun setSttLanguage(langCode: String) {
        SecretsProvider.setLanguageMode(appContext, langCode)
        _state.update { it.copy(sttLanguageCode = langCode) }
        scope.launch(Dispatchers.IO) {
            sttClient.disconnect()
        }
    }

    fun triggerManualWake() {
        if (muted.get()) {
            muted.set(false)
            signalMuteWaiters()
            _state.update { it.copy(micMuted = false) }
        }
        cancelActiveReply()
        closeSttTurnIfOpen()
        turnTranscriptSegments.clear()
        val gateMs = wakeCuePlayer.playWakeCueIfEnabled()
        suppressVadUntilMs = System.currentTimeMillis() + gateMs
        currentPartialText = ""
        _state.update { it.copy(liveTranscript = "", assistantReply = "", recentActionSteps = emptyList()) }
        setPhase(ConversationPhase.LISTENING)
    }

    fun stopActiveTurn() {
        cancelActiveReply()
        closeSttTurnIfOpen()
        turnConfirmedByLocalWake = false
        wakeConfirmedBySttFinal = false
        partialWakeTailSnapshot = ""
        pendingPostWakeCommand = false
        localWakeEngine.resetStream()
        currentPartialText = ""
        turnTranscriptSegments.clear()
        _state.update { it.copy(routingReason = "stopped") }
        setPhase(if (running.get()) ConversationPhase.IDLE_STANDBY else ConversationPhase.STOPPED)
    }

    fun startNewSession() {
        cancelActiveReply()
        closeSttTurnIfOpen()
        scope.launch(Dispatchers.IO) {
            val sid = sessionManager.startNewSessionNow("ui_new_chat") { sys, prompt ->
                llmRouter.antigravityProvider.quickComplete(sys, prompt)
            }
            ToolTraceLogger.setActiveSession(sid)
            _state.update {
                it.copy(
                    activeSessionId = sid,
                    liveTranscript = "",
                    assistantReply = "",
                    recentActionSteps = emptyList(),
                    routingReason = "ready"
                )
            }
        }
    }

    fun selectSession(sessionId: String) {
        cancelActiveReply()
        closeSttTurnIfOpen()
        scope.launch(Dispatchers.IO) {
            sessionManager.switchToSession(sessionId)
            ToolTraceLogger.setActiveSession(sessionId)
            _state.update {
                it.copy(
                    activeSessionId = sessionId,
                    liveTranscript = "",
                    assistantReply = "",
                    recentActionSteps = emptyList(),
                    routingReason = "session loaded"
                )
            }
        }
    }

    fun submitTextTurn(text: String, imageBase64: String? = null, imageMimeType: String = "image/jpeg") {
        val clean = text.trim()
        if (clean.isEmpty() && imageBase64.isNullOrBlank()) return
        val prompt = clean.ifEmpty { "Please inspect this image and help me with it." }
        cancelActiveReply()
        closeSttTurnIfOpen()
        turnConfirmedByLocalWake = false
        startAssistantTurn(
            userText = prompt,
            attachedImageBase64 = imageBase64,
            attachedImageMimeType = imageMimeType
        )
    }

    fun simulateIncomingStt(text: String, isFinal: Boolean = true) {
        val phase = _state.value.phase
        if ((phase == ConversationPhase.SPEAKING_REPLY || phase == ConversationPhase.THINKING) && !bargeInProbeOpen) {
            bargeInProbeOpen = true
            sttTurnOpen = true
        }
        if (isFinal) {
            handleIncomingFinal(text)
        } else {
            handleIncomingPartial(text)
        }
    }

    suspend fun speakProactiveMessage(text: String): Boolean {
        val clean = EmotionTagNormalizer.sanitizeForSpeech(text)
        if (clean.isBlank()) return false
        val phase = _state.value.phase
        if (phase == ConversationPhase.SPEAKING || phase == ConversationPhase.PAUSE_WAIT || phase == ConversationPhase.THINKING) {
            delay(1500L)
        }
        cancelActiveReply()
        suppressBargeIn = true
        try {
            setPhase(ConversationPhase.SPEAKING_REPLY)
            _state.update { it.copy(assistantReply = clean, activeModelBadge = "Reminder Voice") }
            val opened = fishClient.startSession(latencyMode = "balanced")
            if (!opened) {
                setPhase(ConversationPhase.IDLE_STANDBY)
                return false
            }
            fishClient.sendTextClause(clean)
            fishClient.finishAndAwaitPlayback()
            suppressVadUntilMs = System.currentTimeMillis() + 300L
            preRollRing.snapshotAndClear()
            setPhase(if (running.get()) ConversationPhase.IDLE_STANDBY else ConversationPhase.STOPPED)
            return true
        } finally {
            suppressBargeIn = false
        }
    }

    suspend fun testFishAudioStandalone(text: String): Boolean {
        cancelActiveReply()
        suppressBargeIn = true
        try {
            setPhase(ConversationPhase.SPEAKING_REPLY)
            _state.update {
                it.copy(
                    activeModelBadge = "Fish Audio (${SecretsProvider.fishAudioModel()})",
                    assistantReply = text
                )
            }
            fishClient.verifyVoiceModel()
            val opened = fishClient.startSession()
            if (!opened) {
                setPhase(ConversationPhase.IDLE_STANDBY)
                return false
            }
            val clauses = ClauseStreamBuffer()
            for (c in clauses.appendDelta("$text.")) {
                fishClient.sendTextClause(c)
            }
            clauses.drainRemainder()?.let { fishClient.sendTextClause(it) }
            fishClient.finishAndAwaitPlayback()
            suppressVadUntilMs = System.currentTimeMillis() + 300L
            preRollRing.snapshotAndClear()
            setPhase(ConversationPhase.IDLE_STANDBY)
            return true
        } finally {
            suppressBargeIn = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun runAudioCaptureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            PipelineConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(PipelineConfig.FRAME_SAMPLES * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            PipelineConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, PipelineConfig.SAMPLE_RATE_HZ)
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(PipelineConfig.LOG_TAG, "AudioRecord failed to initialize")
            running.set(false)
            setPhase(ConversationPhase.STOPPED)
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aecEffect = runCatching {
                AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        // Keep hardware NoiseSuppressor disabled so soft / whispered wake words aren't attenuated
        nsEffect = null
        Log.i(PipelineConfig.LOG_TAG, "Mic source=VOICE_RECOGNITION, Hardware AEC attached=${aecEffect != null}, NS disabled for soft voice")

        val frameShorts = ShortArray(PipelineConfig.FRAME_SAMPLES)
        var micRecording = false
        if (!muted.get()) {
            record.startRecording()
            micRecording = true
        }
        Log.i(PipelineConfig.LOG_TAG, "Mic AudioRecord loop started (16kHz, 512-sample frames)")

        var lastUiMeterUpdateMs = 0L
        var lastUiSpeechActive = false
        var cachedHeadsetConnected = false
        var lastHeadsetCheckMs = 0L
        var cachedCallActive = false
        var lastCallCheckMs = 0L

        try {
            while (running.get()) {
                val loopNow = System.currentTimeMillis()
                if (loopNow - lastCallCheckMs >= 400L) {
                    lastCallCheckMs = loopNow
                    cachedCallActive = callCoordinator.isPhoneOrVoipCallActive()
                }
                if (cachedCallActive) {
                    if (micRecording) {
                        runCatching { record.stop() }
                        micRecording = false
                        preRollRing.snapshotAndClear()
                        bargeInPreRoll.snapshotAndClear()
                        speechTailRing.clear()
                        consecutiveSpeechFrames = 0
                        closeSttTurnIfOpen()
                        localWakeEngine.resetStream()
                    }
                    if (!_state.value.callPaused) {
                        _state.update { it.copy(callPaused = true, routingReason = "call active") }
                        if (_state.value.phase != ConversationPhase.IDLE_STANDBY && _state.value.phase != ConversationPhase.STOPPED) {
                            setPhase(ConversationPhase.IDLE_STANDBY)
                        }
                    }
                    runCatching { Thread.sleep(350L) }
                    continue
                } else if (_state.value.callPaused) {
                    _state.update { it.copy(callPaused = false, routingReason = "call ended") }
                }

                if (muted.get()) {
                    if (micRecording) {
                        runCatching { record.stop() }
                        micRecording = false
                        preRollRing.snapshotAndClear()
                        bargeInPreRoll.snapshotAndClear()
                        speechTailRing.clear()
                        consecutiveSpeechFrames = 0
                    }
                    muteLock.lock()
                    try {
                        if (running.get() && muted.get()) {
                            runCatching { muteCondition.await(2000L, TimeUnit.MILLISECONDS) }
                        }
                    } finally {
                        muteLock.unlock()
                    }
                    continue
                }
                if (!micRecording) {
                    record.startRecording()
                    micRecording = true
                }

                if (injectingTestAudio.get()) {
                    runCatching { Thread.sleep(32L) }
                    continue
                }

                val read = record.read(frameShorts, 0, frameShorts.size)
                if (read <= 0) continue
                if (injectingTestAudio.get()) continue

                val now = System.currentTimeMillis()
                val phase = _state.value.phase
                if (now - lastHeadsetCheckMs >= 1500L) {
                    lastHeadsetCheckMs = now
                    cachedHeadsetConnected = isExternalHeadsetConnected()
                }
                val externalHeadset = cachedHeadsetConnected

                val postTtsReverbCooldown = !externalHeadset &&
                    phase != ConversationPhase.SPEAKING_REPLY &&
                    phase != ConversationPhase.THINKING &&
                    (audioPlayer.isActivelyOutputtingAudio() || now < suppressVadUntilMs)

                if (postTtsReverbCooldown || now < suppressVadUntilMs) {
                    preRollRing.snapshotAndClear()
                    bargeInPreRoll.snapshotAndClear()
                    speechTailRing.clear()
                    consecutiveSpeechFrames = 0
                    continue
                }

                val analysis = audioPipeline.processFrame(frameShorts, read)

                if (phase == ConversationPhase.IDLE_STANDBY || phase == ConversationPhase.LISTENING) {
                    preRollRing.push(analysis.samples, read)
                } else if (phase == ConversationPhase.SPEAKING_REPLY || phase == ConversationPhase.THINKING) {
                    bargeInPreRoll.push(analysis.samples, read)
                }

                if (phase != ConversationPhase.STOPPED &&
                    (analysis.vadSpeech != lastUiSpeechActive || (now - lastUiMeterUpdateMs) >= 250L)
                ) {
                    lastUiMeterUpdateMs = now
                    lastUiSpeechActive = analysis.vadSpeech
                    _state.update {
                        it.copy(
                            noiseFloorDbfs = analysis.noiseFloorDbfs,
                            currentSnrDb = analysis.snrDb,
                            isSpeechActive = analysis.vadSpeech
                        )
                    }
                }

                handleAudioFrameForPhase(phase, analysis, now)
            }
        } catch (e: Exception) {
            Log.e(PipelineConfig.LOG_TAG, "Audio capture loop crashed: ${e.message}", e)
        } finally {
            runCatching { aecEffect?.release() }
            runCatching { nsEffect?.release() }
            aecEffect = null
            nsEffect = null
            if (micRecording) {
                runCatching { record.stop() }
            }
            runCatching { record.release() }
            Log.i(PipelineConfig.LOG_TAG, "Mic AudioRecord loop stopped")
        }
    }

    private fun handleAudioFrameForPhase(
        phase: ConversationPhase,
        frame: AudioFrameAnalysis,
        now: Long
    ) {
        val foregroundSpeech = frame.vadSpeech && frame.snrDb >= PipelineConfig.MIN_FOREGROUND_SNR_DB

        when (phase) {
            ConversationPhase.IDLE_STANDBY -> {
                if (localWakeEngine.isAvailable) {
                    val wakeAudioActive = frame.vadSpeech || frame.snrDb >= 2.2
                    val match = localWakeEngine.processFrame(frame.samples, frame.samples.size, wakeAudioActive, now)
                    if (match != null) {
                        consecutiveSpeechFrames = 0
                        currentTurnFromFollowUp = false
                        turnConfirmedByLocalWake = true
                        currentPartialText = ""
                        utteranceDurationMs = 0L
                        silenceAccumulatorMs = 0L
                        consecutivePeakVadFrames = 5
                        speechTailRing.clear()
                        _state.update { it.copy(liveTranscript = "", routingReason = "wake:${match.keyword}") }
                        setPhase(ConversationPhase.SPEAKING)
                        openSttTurnWithPreRoll(frame.samples)
                        val gateMs = playWakeCueOnce()
                        if (gateMs > 0L) suppressVadUntilMs = now + gateMs
                    }
                }
                return
            }

            ConversationPhase.WAKE_CHECK -> {
                utteranceDurationMs += PipelineConfig.FRAME_DURATION_MS
                streamFrameToStt(frame.samples)
                speechTailRing.push(frame.samples)

                if (frame.vadSpeech) {
                    silenceAccumulatorMs = 0L
                } else {
                    silenceAccumulatorMs += PipelineConfig.FRAME_DURATION_MS
                }

                if (!wakeEarlyFlushSent &&
                    sttTurnReady.get() &&
                    utteranceDurationMs >= 256L &&
                    silenceAccumulatorMs >= 288L
                ) {
                    wakeEarlyFlushSent = true
                    scope.launch(Dispatchers.IO) {
                        sttClient.sendFlushAndContinue()
                    }
                }

                if (!wakeResolving && (silenceAccumulatorMs >= 480L || utteranceDurationMs >= 4500L)) {
                    wakeResolving = true
                    resolveWakeCheckTurn()
                }
            }

            ConversationPhase.LISTENING -> {
                if (foregroundSpeech) {
                    consecutiveSpeechFrames++
                    if (consecutiveSpeechFrames >= PipelineConfig.ONSET_CONSECUTIVE_FRAMES) {
                        consecutiveSpeechFrames = 0
                        utteranceDurationMs = 0L
                        silenceAccumulatorMs = 0L
                        consecutivePeakVadFrames = 0
                        currentTurnFromFollowUp = !pendingPostWakeCommand
                        turnConfirmedByLocalWake = pendingPostWakeCommand
                        pendingPostWakeCommand = false
                        speechTailRing.clear()
                        setPhase(ConversationPhase.SPEAKING)
                        openSttTurnWithPreRoll(frame.samples)
                    }
                } else {
                    consecutiveSpeechFrames = 0
                    if (now - phaseEnteredAtMs > PipelineConfig.FOLLOW_UP_WINDOW_MS) {
                        Log.d(PipelineConfig.LOG_TAG, "Follow-up listening timeout -> IDLE_STANDBY")
                        setPhase(ConversationPhase.IDLE_STANDBY)
                    }
                }
            }

            ConversationPhase.SPEAKING -> {
                utteranceDurationMs += PipelineConfig.FRAME_DURATION_MS
                streamFrameToStt(frame.samples)
                speechTailRing.push(frame.samples)

                if (frame.vadSpeech || frame.snrDb >= 2.8) {
                    silenceAccumulatorMs = 0L
                    if (frame.vadSpeech || frame.snrDb >= 2.8) {
                        consecutivePeakVadFrames++
                    }
                } else {
                    silenceAccumulatorMs += PipelineConfig.FRAME_DURATION_MS
                }

                if (utteranceDurationMs > PipelineConfig.LOW_CONFIDENCE_DRIFT_GUARD_MS && consecutivePeakVadFrames < 8) {
                    Log.w(PipelineConfig.LOG_TAG, "Low-confidence drift guard triggered -> recalibrating & resetting")
                    audioPipeline.recalibrateFloor(frame.rmsDbfs)
                    closeSttTurnIfOpen()
                    setPhase(ConversationPhase.IDLE_STANDBY)
                    return
                }

                if (utteranceDurationMs >= PipelineConfig.MAX_UTTERANCE_MS) {
                    commitCurrentCommand()
                    return
                }

                if (silenceAccumulatorMs >= PipelineConfig.PAUSE_EVAL_TRIGGER_MS) {
                    val afterWake = (stripWakeWordIfPresent(currentPartialText) ?: currentPartialText).trim()
                    if (afterWake.isBlank() && currentPartialText.isNotBlank()) {
                        playWakeCueOnce()
                        closeSttTurnIfOpen()
                        currentPartialText = ""
                        turnTranscriptSegments.clear()
                        pendingPostWakeCommand = true
                        setPhase(ConversationPhase.LISTENING)
                        return
                    }
                    val tailSnap = speechTailRing.snapshotAndClear()
                    val eval = smartTurn.evaluatePause(tailSnap, afterWake)
                    dynamicWaitTargetMs = eval.recommendedWaitMs
                    consecutiveSpeechFrames = 0
                    Log.i(
                        PipelineConfig.LOG_TAG,
                        "[EoT:PAUSE_WAIT] P=${"%.2f".format(eval.completionProbability)} target=${dynamicWaitTargetMs}ms (${eval.reason})"
                    )
                    setPhase(ConversationPhase.PAUSE_WAIT)
                }
            }

            ConversationPhase.PAUSE_WAIT -> {
                streamFrameToStt(frame.samples)
                if (frame.vadSpeech || frame.snrDb >= 2.8) {
                    consecutiveSpeechFrames++
                    if (frame.vadSpeech || consecutiveSpeechFrames >= 2) {
                        consecutiveSpeechFrames = 0
                        silenceAccumulatorMs = 0L
                        Log.i(PipelineConfig.LOG_TAG, "[EoT] User resumed speaking -> back to SPEAKING")
                        setPhase(ConversationPhase.SPEAKING)
                        return
                    }
                } else {
                    consecutiveSpeechFrames = 0
                    silenceAccumulatorMs += PipelineConfig.FRAME_DURATION_MS
                }

                if (silenceAccumulatorMs >= dynamicWaitTargetMs) {
                    commitCurrentCommand()
                }
            }

            ConversationPhase.THINKING,
            ConversationPhase.SPEAKING_REPLY -> {
                if (suppressBargeIn) {
                    consecutiveSpeechFrames = 0
                    bargeInSpeechFrames = 0
                    return
                }
                handleLoudspeakerBargeInFrame(frame)
                return
            }

            ConversationPhase.STOPPED -> {}
        }
    }

    private fun isExternalHeadsetConnected(): Boolean {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.any { d ->
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    private fun openSttTurnWithPreRoll(
        currentFrame: ShortArray,
        sourceRing: PreRollRingBuffer = preRollRing
    ) {
        pendingSttFrames.clear()
        turnTranscriptSegments.clear()
        wakeEarlyFlushSent = false
        wakeResolving = false
        wakeCuePlayedForTurn = false
        wakeConfirmedBySttFinal = false
        partialWakeTailSnapshot = ""
        sttTurnReady.set(false)
        sttTurnOpen = true

        val preRoll = sourceRing.snapshotAndClear()
        if (preRoll.isNotEmpty()) {
            pendingSttFrames.offer(preRoll)
        }
        pendingSttFrames.offer(currentFrame.copyOf())

        val lang = _state.value.sttLanguageCode
        scope.launch(Dispatchers.IO) {
            val ok = sttClient.ensureConnected(lang)
            if (!ok || !sttTurnOpen) {
                Log.e(PipelineConfig.LOG_TAG, "Failed connecting Deepgram Realtime WS (ok=$ok, open=$sttTurnOpen)")
                sttTurnOpen = false
                sttTurnReady.set(false)
                pendingSttFrames.clear()
                return@launch
            }
            synchronized(sttSendLock) {
                if (!sttTurnOpen) return@synchronized
                sttClient.sendSpeechStart()
                while (true) {
                    val queued = pendingSttFrames.poll() ?: break
                    sttClient.sendAudioShorts(queued)
                }
                sttTurnReady.set(true)
            }
        }
    }

    private fun handleLoudspeakerBargeInFrame(frame: AudioFrameAnalysis) {
        val vocalBurst = frame.vadSpeech && frame.snrDb >= PipelineConfig.BARGE_IN_SNR_DB
        if (!bargeInProbeOpen) {
            if (vocalBurst) {
                bargeInSpeechFrames++
                if (bargeInSpeechFrames >= PipelineConfig.BARGE_IN_CONSECUTIVE_FRAMES) {
                    bargeInSpeechFrames = 0
                    bargeInSilenceMs = 0L
                    bargeInProbeElapsedMs = 0L
                    bargeInLastFlushMs = 0L
                    lastBargeInCheckedText = ""
                    latestBargeInText = ""
                    bargeInDecideJob?.cancel()
                    bargeInDecideJob = null
                    bargeInProbeOpen = true
                    openSttTurnWithPreRoll(frame.samples, sourceRing = bargeInPreRoll)
                }
            } else {
                bargeInSpeechFrames = 0
            }
            return
        }

        streamFrameToStt(frame.samples)
        bargeInProbeElapsedMs += PipelineConfig.FRAME_DURATION_MS
        if (frame.vadSpeech) {
            bargeInSilenceMs = 0L
        } else {
            bargeInSilenceMs += PipelineConfig.FRAME_DURATION_MS
        }

        if (sttTurnReady.get() && (bargeInProbeElapsedMs - bargeInLastFlushMs) >= 320L) {
            bargeInLastFlushMs = bargeInProbeElapsedMs
            scope.launch(Dispatchers.IO) {
                sttClient.sendFlushAndContinue()
            }
        }

        if (bargeInSilenceMs >= 800L) {
            bargeInProbeOpen = false
            lastBargeInCheckedText = ""
            latestBargeInText = ""
            turnTranscriptSegments.clear()
            currentPartialText = ""
            closeSttTurnIfOpen()
        }
    }

    private fun streamFrameToStt(samples: ShortArray) {
        if (!sttTurnOpen) return
        pendingSttFrames.offer(samples.copyOf())
        if (!sttTurnReady.get()) return
        synchronized(sttSendLock) {
            if (!sttTurnOpen || !sttTurnReady.get()) return@synchronized
            while (true) {
                val queued = pendingSttFrames.poll() ?: break
                sttClient.sendAudioShorts(queued)
            }
        }
    }

    private fun closeSttTurnIfOpen() {
        if (sttTurnOpen) {
            sttTurnOpen = false
            sttTurnReady.set(false)
            pendingSttFrames.clear()
            scope.launch(Dispatchers.IO) {
                synchronized(sttSendLock) {
                    sttClient.sendSpeechEndAndFlush()
                }
            }
        }
    }

    private fun playWakeCueOnce(): Long {
        if (wakeCuePlayedForTurn) return 0L
        wakeCuePlayedForTurn = true
        return wakeCuePlayer.playWakeCueIfEnabled()
    }

    private fun combineSegmentsWithPartial(segments: List<String>, tail: String): String {
        val cleanTail = tail.trim()
        if (segments.isEmpty()) return cleanTail
        val prefix = segments.joinToString(" ").trim()
        if (cleanTail.isBlank()) return prefix
        if (cleanTail.startsWith(prefix, ignoreCase = true)) return cleanTail
        if (prefix.endsWith(cleanTail, ignoreCase = true)) return prefix
        return "$prefix $cleanTail".trim()
    }

    private fun handleBargeInTranscriptIfWake(combined: String, isFinal: Boolean): Boolean {
        val phase = _state.value.phase
        if ((phase != ConversationPhase.SPEAKING_REPLY && phase != ConversationPhase.THINKING) || !bargeInProbeOpen) {
            return false
        }

        val spokenWindow = "$activeSpokenTtsText ${_state.value.assistantReply}".trim()
        val action = VoiceCommandFilters.shouldAcceptBargeInTranscript(combined, spokenWindow)
        if (action == null) {
            val normCombined = VoiceCommandFilters.normalizeText(combined)
            val wordCount = normCombined.split(' ').count { it.isNotBlank() }
            val notEcho = !VoiceCommandFilters.isLikelySelfEcho(combined, spokenWindow)
            val notFiller = !VoiceCommandFilters.isLowValueFollowUpNoise(combined, assistantAskedQuestion = false)
            if (phase == ConversationPhase.SPEAKING_REPLY &&
                notEcho && notFiller &&
                (isFinal || wordCount >= 2) &&
                !normCombined.equals(lastBargeInCheckedText, ignoreCase = true)
            ) {
                latestBargeInText = combined.trim()
                if (bargeInDecideJob?.isActive != true) {
                    val spokenSnapshot = spokenWindow
                    bargeInDecideJob = scope.launch(Dispatchers.IO) {
                        delay(260L)
                        val coalesced = latestBargeInText.ifBlank { combined.trim() }
                        val coalescedNorm = VoiceCommandFilters.normalizeText(coalesced)
                        if (coalescedNorm.isBlank() || coalescedNorm.equals(lastBargeInCheckedText, ignoreCase = true)) {
                            return@launch
                        }
                        lastBargeInCheckedText = coalescedNorm
                        val activePkg = SaraAccessibilityService.instance?.currentPackageName().orEmpty()
                        val bDecision = llmRouter.decideMidSpeechInterruption(
                            heardText = coalesced,
                            assistantSpokenText = spokenSnapshot,
                            activePackage = activePkg
                        )
                        if (!bDecision.shouldInterrupt) {
                            Log.i(PipelineConfig.LOG_TAG, "[Barge-In:Smart] Ignored non-important mid-speech (${bDecision.reason}): '$coalesced'")
                            return@launch
                        }
                        synchronized(bargeInLock) {
                            val curPhase = _state.value.phase
                            if (curPhase != ConversationPhase.SPEAKING_REPLY && curPhase != ConversationPhase.THINKING) {
                                return@synchronized
                            }
                            val finalInterruptText = latestBargeInText.ifBlank { coalesced }
                            val userStillSpeaking = bargeInSilenceMs < 400L && sttTurnOpen
                            bargeInProbeOpen = false
                            bargeInSpeechFrames = 0
                            lastBargeInCheckedText = ""
                            latestBargeInText = ""
                            bargeInDecideJob = null
                            Log.i(PipelineConfig.LOG_TAG, "[Barge-In:Smart] Real interruption (${bDecision.reason}, stillSpeaking=$userStillSpeaking) -> '$finalInterruptText'")
                            cancelActiveReply()
                            if (userStillSpeaking) {
                                currentTurnFromFollowUp = false
                                turnConfirmedByLocalWake = true
                                currentPartialText = finalInterruptText
                                utteranceDurationMs = 0L
                                silenceAccumulatorMs = 0L
                                consecutivePeakVadFrames = 5
                                wakeResolving = false
                                suppressVadUntilMs = 0L
                                _state.update { it.copy(liveTranscript = finalInterruptText) }
                                setPhase(ConversationPhase.SPEAKING)
                            } else {
                                turnTranscriptSegments.clear()
                                currentPartialText = ""
                                startAssistantTurn(
                                    userText = finalInterruptText,
                                    fromFollowUp = false,
                                    lastAssistantReply = spokenSnapshot
                                )
                            }
                        }
                    }
                }
            } else {
                Log.d(PipelineConfig.LOG_TAG, "[Barge-In] Ignored non-keyword/echo speech: '$combined'")
                if (isFinal) {
                    turnTranscriptSegments.clear()
                    currentPartialText = ""
                }
            }
            return false
        }

        bargeInDecideJob?.cancel()
        bargeInDecideJob = null
        latestBargeInText = ""

        when (action) {
            "STOP" -> {
                synchronized(bargeInLock) {
                    val curPhase = _state.value.phase
                    if (curPhase != ConversationPhase.SPEAKING_REPLY && curPhase != ConversationPhase.THINKING) {
                        return false
                    }
                    bargeInProbeOpen = false
                    bargeInSpeechFrames = 0
                    lastBargeInCheckedText = ""
                    Log.i(PipelineConfig.LOG_TAG, "[Barge-In] Direct stop triggered by '$combined'")
                    handleStopInterrupt(combined)
                    return true
                }
            }
            "WAIT" -> {
                synchronized(bargeInLock) {
                    val curPhase = _state.value.phase
                    if ((curPhase != ConversationPhase.SPEAKING_REPLY && curPhase != ConversationPhase.THINKING) || !bargeInProbeOpen) {
                        return false
                    }
                    bargeInProbeOpen = false
                    bargeInSpeechFrames = 0
                    lastBargeInCheckedText = ""
                    Log.i(PipelineConfig.LOG_TAG, "[Barge-In:Wait] User paused Sarah ($curPhase) with '$combined' -> LISTENING")
                    cancelActiveReply()
                    closeSttTurnIfOpen()
                    turnTranscriptSegments.clear()
                    currentPartialText = ""
                    val gateMs = wakeCuePlayer.playWakeCueIfEnabled()
                    suppressVadUntilMs = System.currentTimeMillis() + gateMs
                    _state.update { it.copy(liveTranscript = "") }
                    setPhase(ConversationPhase.LISTENING)
                    return true
                }
            }
            else -> {
                val effectiveSpeech = stripWakeWordIfPresent(combined).orEmpty().trim()
                synchronized(bargeInLock) {
                    val curPhase = _state.value.phase
                    if ((curPhase != ConversationPhase.SPEAKING_REPLY && curPhase != ConversationPhase.THINKING) || !bargeInProbeOpen) {
                        return false
                    }
                    bargeInProbeOpen = false
                    bargeInSpeechFrames = 0
                    lastBargeInCheckedText = ""
                    Log.i(PipelineConfig.LOG_TAG, "[Barge-In:Wake] Wake keyword interrupted Sarah ($curPhase) with '$combined'")
                    cancelActiveReply()

                    if (effectiveSpeech.isNotBlank() && VoiceCommandFilters.isStopCommand(effectiveSpeech)) {
                        handleStopInterrupt(effectiveSpeech)
                        return true
                    }

                    if (effectiveSpeech.isBlank() && bargeInSilenceMs >= 200L) {
                        playWakeCueOnce()
                    }
                    currentTurnFromFollowUp = false
                    currentPartialText = effectiveSpeech
                    utteranceDurationMs = 0L
                    silenceAccumulatorMs = 0L
                    consecutivePeakVadFrames = 5
                    wakeResolving = false
                    suppressVadUntilMs = 0L
                    _state.update { it.copy(liveTranscript = effectiveSpeech) }
                    setPhase(ConversationPhase.SPEAKING)
                    return true
                }
            }
        }
    }

    private fun handleStopInterrupt(spokenStop: String) {
        Log.i(PipelineConfig.LOG_TAG, "[Barge-In:Stop] User stopped Sarah ('$spokenStop') -> IDLE_STANDBY")
        ConfirmPolicyGate.clearPendingConfirmation()
        closeSttTurnIfOpen()
        cancelActiveReply()
        turnConfirmedByLocalWake = false
        localWakeEngine.resetStream()
        currentPartialText = ""
        turnTranscriptSegments.clear()
        bargeInPreRoll.snapshotAndClear()
        preRollRing.snapshotAndClear()
        suppressVadUntilMs = System.currentTimeMillis() + 500L
        _state.update {
            it.copy(
                liveTranscript = spokenStop.ifBlank { "Stop" },
                assistantReply = "Stopped.",
                routingReason = "voice stop"
            )
        }
        setPhase(ConversationPhase.IDLE_STANDBY)
        if (!sttClient.isSessionConnected()) {
            scope.launch(Dispatchers.IO) { sttClient.ensureConnected(_state.value.sttLanguageCode) }
        }
    }

    private fun handleIncomingPartial(text: String) {
        val combined = combineSegmentsWithPartial(turnTranscriptSegments, text)
        if (handleBargeInTranscriptIfWake(combined, isFinal = false)) return

        currentPartialText = combined
        val phase = _state.value.phase
        if (phase == ConversationPhase.WAKE_CHECK) {
            val stripped = stripWakeWordIfPresent(combined)
            if (stripped != null && stripped.length >= 2) {
                Log.i(PipelineConfig.LOG_TAG, "[WakeWord] Detected 'Sarah' + command in partial: '$combined'")
                partialWakeTailSnapshot = VoiceCommandFilters.normalizeText(stripped)
                utteranceDurationMs = 0L
                silenceAccumulatorMs = 0L
                wakeResolving = false
                _state.update { it.copy(liveTranscript = stripped) }
                setPhase(ConversationPhase.SPEAKING)
            }
        } else if (phase == ConversationPhase.SPEAKING || phase == ConversationPhase.PAUSE_WAIT) {
            val cleaned = stripWakeWordIfPresent(combined) ?: combined
            _state.update { it.copy(liveTranscript = cleaned) }
        }
    }

    private fun handleIncomingFinal(text: String) {
        val phase = _state.value.phase
        val clean = text.trim()
        if (clean.isNotEmpty() && (
                phase == ConversationPhase.WAKE_CHECK ||
                    phase == ConversationPhase.SPEAKING ||
                    phase == ConversationPhase.PAUSE_WAIT ||
                    phase == ConversationPhase.THINKING ||
                    (phase == ConversationPhase.SPEAKING_REPLY && bargeInProbeOpen)
                )
        ) {
            val existingPrefix = turnTranscriptSegments.joinToString(" ").trim()
            if (existingPrefix.isBlank()) {
                turnTranscriptSegments.add(clean)
            } else if (clean.startsWith(existingPrefix, ignoreCase = true)) {
                turnTranscriptSegments.clear()
                turnTranscriptSegments.add(clean)
            } else if (!turnTranscriptSegments.last().equals(clean, ignoreCase = true)) {
                turnTranscriptSegments.add(clean)
            }
            currentPartialText = turnTranscriptSegments.joinToString(" ").trim()
        }

        val resolvedText = currentPartialText.ifBlank { clean }
        if (stripWakeWordIfPresent(clean) != null || stripWakeWordIfPresent(resolvedText) != null) {
            wakeConfirmedBySttFinal = true
        }
        if (tryHandleVoiceSafetyConfirmation(resolvedText)) {
            turnTranscriptSegments.clear()
            currentPartialText = ""
            finalTranscriptWaiter?.complete("")
            return
        }
        if (handleBargeInTranscriptIfWake(resolvedText, isFinal = true)) {
            return
        }
        finalTranscriptWaiter?.complete(resolvedText)

        if (_state.value.phase == ConversationPhase.WAKE_CHECK && !wakeResolving) {
            val stripped = stripWakeWordIfPresent(resolvedText)
            if (stripped != null) {
                Log.i(PipelineConfig.LOG_TAG, "[WakeWord] Detected 'Sarah' in final: '$resolvedText'")
                if (stripped.length >= 2 && silenceAccumulatorMs >= 350L) {
                    wakeResolving = true
                    closeSttTurnIfOpen()
                    startAssistantTurn(stripped)
                } else {
                    if (stripped.isBlank()) {
                        playWakeCueOnce()
                    }
                    utteranceDurationMs = 0L
                    silenceAccumulatorMs = 0L
                    wakeResolving = false
                    _state.update { it.copy(liveTranscript = stripped) }
                    setPhase(ConversationPhase.SPEAKING)
                }
            }
        }
    }

    private fun handleIncomingEndOfTurn(text: String, confidence: Double) {
        handleIncomingFinal(text)
        val phase = _state.value.phase
        if ((phase == ConversationPhase.SPEAKING || phase == ConversationPhase.PAUSE_WAIT) &&
            confidence >= 0.85 &&
            silenceAccumulatorMs >= PipelineConfig.PAUSE_EVAL_TRIGGER_MS &&
            text.isNotBlank()
        ) {
            val combined = currentPartialText.ifBlank { text }.trim()
            val afterWake = (stripWakeWordIfPresent(combined) ?: combined).trim()
            if (afterWake.isNotBlank()) {
                Log.i(PipelineConfig.LOG_TAG, "[Flux:EndOfTurn] conf=${"%.2f".format(confidence)} sil=${silenceAccumulatorMs}ms -> committing turn")
                commitCurrentCommand()
            }
        }
    }

    private fun resolveWakeCheckTurn() {
        val waiter = kotlinx.coroutines.CompletableDeferred<String>()
        finalTranscriptWaiter = waiter
        scope.launch {
            sttClient.sendFlushOnly()
            val resolved = withTimeoutOrNull(850L) { waiter.await() } ?: currentPartialText
            if (finalTranscriptWaiter === waiter) {
                finalTranscriptWaiter = null
            }
            if (_state.value.phase != ConversationPhase.WAKE_CHECK) {
                return@launch
            }
            val combined = currentPartialText.ifBlank { resolved }
            val stripped = stripWakeWordIfPresent(combined)
            if (stripped != null) {
                Log.i(PipelineConfig.LOG_TAG, "[WakeWord] Resolved 'Sarah' in wake turn: '$combined'")
                if (stripped.length >= 2) {
                    closeSttTurnIfOpen()
                    startAssistantTurn(stripped)
                } else {
                    val userResumedSpeaking = silenceAccumulatorMs < 160L
                    utteranceDurationMs = 0L
                    silenceAccumulatorMs = 0L
                    if (userResumedSpeaking) {
                        setPhase(ConversationPhase.SPEAKING)
                    } else {
                        playWakeCueOnce()
                        currentPartialText = ""
                        turnTranscriptSegments.clear()
                        closeSttTurnIfOpen()
                        pendingPostWakeCommand = true
                        setPhase(ConversationPhase.LISTENING)
                    }
                }
            } else {
                closeSttTurnIfOpen()
                setPhase(ConversationPhase.IDLE_STANDBY)
            }
        }
    }

    private fun tryHandleVoiceSafetyConfirmation(spokenText: String): Boolean {
        if (ConfirmPolicyGate.pendingConfirmation.value == null) return false
        val cleaned = (stripWakeWordIfPresent(spokenText) ?: spokenText).trim()
        val norm = VoiceCommandFilters.normalizeText(cleaned)
        if (norm.isBlank()) return false
        val approvePhrases = setOf("yes", "yes please", "confirm", "approve", "approved", "proceed", "go ahead", "haan", "हाँ", "ok", "okay", "sure", "do it")
        val denyPhrases = setOf("no", "cancel", "stop", "deny", "dont", "don t", "never mind", "nevermind", "नहीं", "रुको", "मत करो")
        return when {
            norm in approvePhrases -> {
                Log.i(PipelineConfig.LOG_TAG, "[SafetyGate] Voice approved pending confirmation ('$norm')")
                ConfirmPolicyGate.resolveConfirmation(true)
                true
            }
            norm in denyPhrases -> {
                Log.i(PipelineConfig.LOG_TAG, "[SafetyGate] Voice denied pending confirmation ('$norm')")
                ConfirmPolicyGate.resolveConfirmation(false)
                true
            }
            else -> false
        }
    }

    private fun stripWakeWordIfPresent(text: String): String? = VoiceCommandFilters.stripWakeWord(text)

    private fun commitCurrentCommand() {
        val waiter = kotlinx.coroutines.CompletableDeferred<String>()
        finalTranscriptWaiter = waiter
        closeSttTurnIfOpen()
        val fallbackPartial = currentPartialText
        val wasFollowUp = currentTurnFromFollowUp
        val confirmedByLocalWake = turnConfirmedByLocalWake
        val confirmedBySttFinal = wakeConfirmedBySttFinal
        val partialTail = partialWakeTailSnapshot
        val lastReplySnapshot = _state.value.assistantReply
        val lastReplyAskedQuestion = lastReplySnapshot.trimEnd().endsWith("?")
        currentTurnFromFollowUp = false
        turnConfirmedByLocalWake = false
        wakeConfirmedBySttFinal = false
        partialWakeTailSnapshot = ""
        setPhase(ConversationPhase.THINKING)

        scope.launch {
            val finalRaw = withTimeoutOrNull(750L) { waiter.await() } ?: fallbackPartial
            finalTranscriptWaiter = null
            if (tryHandleVoiceSafetyConfirmation(finalRaw)) {
                setPhase(if (activeReplyJob?.isActive == true) ConversationPhase.THINKING else ConversationPhase.IDLE_STANDBY)
                return@launch
            }
            val strippedWake = stripWakeWordIfPresent(finalRaw)
            val rescuedByPartialTail = VoiceCommandFilters.isWakeRescuedByPartialTail(partialTail, finalRaw)
            val hadExplicitWake = strippedWake != null
            val wakeConfirmed = hadExplicitWake || confirmedByLocalWake || confirmedBySttFinal || rescuedByPartialTail
            val cleaned = (strippedWake ?: finalRaw).trim()
            if (cleaned.length < 2) {
                Log.d(PipelineConfig.LOG_TAG, "Empty transcript after commit -> LISTENING")
                setPhase(ConversationPhase.LISTENING)
                return@launch
            }
            if (VoiceCommandFilters.isStopCommand(cleaned)) {
                handleStopInterrupt(cleaned)
                return@launch
            }
            if (wasFollowUp && !wakeConfirmed && VoiceCommandFilters.isLowValueFollowUpNoise(cleaned, lastReplyAskedQuestion)) {
                Log.d(PipelineConfig.LOG_TAG, "[Turn] Suppressed follow-up noise fragment: '$cleaned' -> IDLE_STANDBY")
                setPhase(ConversationPhase.IDLE_STANDBY)
                return@launch
            }
            // Reject false partial wake promotions whose finalized transcript never actually said 'Sarah'
            if (!wasFollowUp && !wakeConfirmed) {
                Log.i(PipelineConfig.LOG_TAG, "[Turn] Final transcript lacked wake word ('$cleaned') -> IDLE_STANDBY")
                setPhase(ConversationPhase.IDLE_STANDBY)
                return@launch
            }
            if (!wasFollowUp && !hadExplicitWake && confirmedByLocalWake && VoiceCommandFilters.containsWakeLookalikeWord(finalRaw)) {
                Log.i(PipelineConfig.LOG_TAG, "[Turn] Suppressed wake lookalike false trigger ('$cleaned') -> IDLE_STANDBY")
                setPhase(ConversationPhase.IDLE_STANDBY)
                return@launch
            }
            startAssistantTurn(
                userText = cleaned,
                fromFollowUp = (wasFollowUp && !wakeConfirmed),
                lastAssistantReply = lastReplySnapshot
            )
        }
    }

    private fun cancelActiveReply() {
        ConfirmPolicyGate.clearPendingConfirmation()
        bargeInDecideJob?.cancel()
        bargeInDecideJob = null
        lastBargeInCheckedText = ""
        latestBargeInText = ""
        wakeConfirmedBySttFinal = false
        partialWakeTailSnapshot = ""
        pendingPostWakeCommand = false
        activeReplyJob?.cancel()
        activeReplyJob = null
        activeSpokenTtsText = ""
        llmRouter.cancelActive()
        swarmOrchestrator?.cancelAllWorkers("reply_cancelled")
        fishClient.abortNow()
        audioPlayer.abortImmediate()
    }

    private fun startAssistantTurn(
        userText: String,
        fromFollowUp: Boolean = false,
        lastAssistantReply: String = "",
        attachedImageBase64: String? = null,
        attachedImageMimeType: String = "image/jpeg"
    ) {
        val cleanTurn = userText.trim()
        val nowMs = System.currentTimeMillis()
        if (cleanTurn.equals(lastStartedTurnText, ignoreCase = true) && (nowMs - lastStartedTurnAtMs) < 1000L) {
            Log.w(PipelineConfig.LOG_TAG, "[Turn] Suppressed duplicate wake/final turn within 1000ms: '$cleanTurn'")
            return
        }
        lastStartedTurnText = cleanTurn
        lastStartedTurnAtMs = nowMs

        closeSttTurnIfOpen()
        bargeInDecideJob?.cancel()
        bargeInDecideJob = null
        lastBargeInCheckedText = ""
        latestBargeInText = ""
        preRollRing.snapshotAndClear()
        speechTailRing.clear()
        toolRegistry.clearPerTurnState()
        activeReplyJob?.cancel()
        activeSpokenTtsText = ""
        activeReplyJob = scope.launch {
            val tTurnStart = System.currentTimeMillis()
            _state.update {
                it.copy(
                    liveTranscript = if (fromFollowUp) "" else userText,
                    assistantReply = if (fromFollowUp) it.assistantReply else "",
                    recentActionSteps = if (fromFollowUp) it.recentActionSteps else emptyList(),
                    routingReason = if (fromFollowUp) "checking relevance..." else "routing..."
                )
            }
            setPhase(ConversationPhase.THINKING)

            try {
                // 1. Resolve isolated active session (rotates cleanly after 10 min idle or on cold start)
                val activeSessionId = sessionManager.resolveActiveSessionId { sys, prompt ->
                    llmRouter.antigravityProvider.quickComplete(sys, prompt)
                }
                ToolTraceLogger.setActiveSession(activeSessionId)
                _state.update { it.copy(activeSessionId = activeSessionId) }

                // 1b. Zero-LLM local fast-path for unambiguous 1-shot commands (~200-400ms instead of 5-13s)
                val fastPath = if (!fromFollowUp && attachedImageBase64.isNullOrBlank()) {
                    LocalFastPathMatcher.match(userText)
                } else null
                if (fastPath != null) {
                    if (fastPath.action == "stop_all_tasks") {
                        swarmOrchestrator?.cancelAllWorkers("stop_all_tasks")
                    }
                    val ttsDeferredFast = async(Dispatchers.IO) {
                        fishClient.startSession(latencyMode = "balanced")
                    }
                    val toolOut = withContext(Dispatchers.IO) {
                        toolRegistry.executeTool(
                            name = fastPath.toolName,
                            args = buildJsonObject { put("action", fastPath.action) },
                            userSpokenText = userText
                        )
                    }
                    val okFlag = !toolOut.resultJson.contains("\"ok\":false")
                    val replyText = if (okFlag) {
                        fastPath.spokenConfirm
                    } else {
                        Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(toolOut.resultJson)?.groupValues?.getOrNull(1)
                            ?: "Couldn't complete that action."
                    }
                    val totalMs = System.currentTimeMillis() - tTurnStart
                    Log.i(PipelineConfig.LOG_TAG, "[Turn] LocalFastPath (${totalMs}ms, action=${fastPath.action}, ok=$okFlag): $replyText")
                    _state.update {
                        it.copy(
                            activeModelBadge = "Local Fast-Path",
                            routingReason = "local_fast_path(${fastPath.action})",
                            assistantReply = replyText,
                            lastLatencySummary = "Turn=${totalMs}ms | Decide=0ms (LocalFastPath)"
                        )
                    }
                    val sttModelTag = sttClient.activeModelTag()
                    contextManager.recordTurn("user", userText, sttModelTag, activeSessionId)
                    contextManager.recordTurn("assistant", replyText, "local-fast-path", activeSessionId)
                    val openedFastTts = ttsDeferredFast.await()
                    if (openedFastTts) {
                        setPhase(ConversationPhase.SPEAKING_REPLY)
                        activeSpokenTtsText = replyText
                        fishClient.sendTextClause(replyText)
                        fishClient.finishAndAwaitPlayback()
                        turnTranscriptSegments.clear()
                        currentPartialText = ""
                        bargeInPreRoll.snapshotAndClear()
                        suppressVadUntilMs = System.currentTimeMillis() + 300L
                        preRollRing.snapshotAndClear()
                    }
                    if (running.get()) setPhase(ConversationPhase.LISTENING)
                    return@launch
                }

                // 2. Assemble token-budgeted context window BEFORE recording user turn so Decide-ignored noise never pollutes memory
                val ctxWindow = contextManager.buildContextWindow(userText, activeSessionId)
                val activePkg = SaraAccessibilityService.instance?.currentPackageName().orEmpty()

                // 3. For wake-free follow-ups, gate via Mercury Decide first so background noise makes ZERO LLM/TTS calls
                val (decision, ttsOpened) = if (fromFollowUp) {
                    val decideResult = withContext(Dispatchers.IO) {
                        llmRouter.decideActionRoute(
                            userText = userText,
                            context = ctxWindow,
                            activePackage = activePkg,
                            fromFollowUp = true,
                            lastAssistantReply = lastAssistantReply
                        )
                    }
                    if (decideResult.shouldIgnore) {
                        Log.i(
                            PipelineConfig.LOG_TAG,
                            "[Turn] Ignored background follow-up without LLM/TTS call (addressed=${"%.2f".format(decideResult.addressedScore)}): '$userText'"
                        )
                        ToolTraceLogger.recordDecide(
                            sessionId = activeSessionId,
                            userText = userText,
                            intentMode = "IGNORED_BACKGROUND",
                            target = decideResult.target.name,
                            tools = emptySet(),
                            ackCategory = "none",
                            latencyMs = decideResult.latencyMs
                        )
                        _state.update { it.copy(liveTranscript = "", routingReason = decideResult.reason) }
                        if (running.get()) setPhase(ConversationPhase.IDLE_STANDBY)
                        return@launch
                    }
                    _state.update { it.copy(liveTranscript = userText, assistantReply = "", recentActionSteps = emptyList()) }
                    val opened = withContext(Dispatchers.IO) {
                        fishClient.startSession(latencyMode = "balanced")
                    }
                    decideResult to opened
                } else {
                    coroutineScope {
                        val decideDeferred = async(Dispatchers.IO) {
                            llmRouter.decideActionRoute(userText, ctxWindow, activePkg)
                        }
                        val ttsDeferred = async(Dispatchers.IO) {
                            fishClient.startSession(latencyMode = "balanced")
                        }
                        decideDeferred.await() to ttsDeferred.await()
                    }
                }

                val sttModelTag = sttClient.activeModelTag()
                var recordedUserMsgId: Long? = if (!fromFollowUp) {
                    contextManager.recordTurn("user", userText, sttModelTag, activeSessionId)
                } else {
                    null
                }
                suspend fun ensureUserTurnRecorded(): Long {
                    return recordedUserMsgId ?: contextManager.recordTurn("user", userText, sttModelTag, activeSessionId)
                        .also { recordedUserMsgId = it }
                }

                ToolTraceLogger.recordDecide(
                    sessionId = activeSessionId,
                    userText = userText,
                    intentMode = decision.intentMode.name,
                    target = decision.target.name,
                    tools = decision.selectedTools,
                    ackCategory = decision.ackCategory,
                    latencyMs = decision.latencyMs
                )

                val targetLabel = if (decision.target.name.contains("GEMINI")) "Gemini" else "Mercury 3"
                _state.update {
                    it.copy(
                        activeModelBadge = "$targetLabel (${decision.selectedTools.size} tools)",
                        routingReason = decision.reason
                    )
                }

                val clauseBuffer = ClauseStreamBuffer()
                val phaseBReply = StringBuilder()
                val executedToolNames = mutableListOf<String>()
                var actualModelUsed = SecretsProvider.inceptionChatModel()
                var firstClauseSent = false
                var ackSpeechSpoken = ""

                // 4. Phase A Dynamic AI-Generated Acknowledgment & Optional Pre-Attached Vision Image
                val effectiveDecision = if (!attachedImageBase64.isNullOrBlank()) {
                    decision.copy(
                        target = RouteDecisionTarget.GEMINI_SMART,
                        needsVision = true,
                        reason = "user_attached_image"
                    )
                } else {
                    decision
                }

                var extraPromptContext = ""
                var preCapturedImageBase64: String? = null
                var preCapturedMimeType = "image/webp"
                if (!attachedImageBase64.isNullOrBlank()) {
                    preCapturedImageBase64 = attachedImageBase64
                    preCapturedMimeType = attachedImageMimeType
                    extraPromptContext += "\n\n[USER ATTACHED PHOTO / SHARED IMAGE INCLUDED FOR VISUAL ANALYSIS]"
                } else if (effectiveDecision.needsVision) {
                    val visionCap = runCatching { SaraAccessibilityService.instance?.captureAnnotatedScreenshot() }.getOrNull()
                    if (visionCap != null) {
                        val (snap, encoded) = visionCap
                        extraPromptContext += "\n\n[LIVE FOREGROUND SCREEN + ANNOTATED IMAGE ATTACHED: package=${snap.packageName}]\n${snap.treeText}"
                        if (encoded != null) {
                            preCapturedImageBase64 = encoded.base64
                        }
                    }
                } else if (effectiveDecision.intentMode == IntentMode.TOOL_ACTION &&
                    ("observe_screen" in effectiveDecision.selectedTools || "ui_act" in effectiveDecision.selectedTools || "device_intent" in effectiveDecision.selectedTools)
                ) {
                    val snap = runCatching { SaraAccessibilityService.instance?.observeTree() }.getOrNull()
                    if (snap != null) {
                        extraPromptContext += "\n\n[LIVE FOREGROUND SCREEN AT TURN START: package=${snap.packageName}]\n${snap.treeText}"
                    }
                }

                val ackMutex = Mutex()
                var phaseBStarted = false
                val dynamicAckJob = if (decision.intentMode == IntentMode.TOOL_ACTION && ttsOpened) {
                    scope.launch(Dispatchers.IO) {
                        val generated = runCatching {
                            llmRouter.generateDynamicAck(userText, activePkg)
                        }.getOrNull().orEmpty()
                        val cleanAck = EmotionTagNormalizer.sanitizeForSpeech(generated)
                        if (cleanAck.isNotBlank() && !phaseBStarted) {
                            ackMutex.withLock {
                                if (!phaseBStarted && !firstClauseSent) {
                                    ackSpeechSpoken = cleanAck
                                    activeSpokenTtsText = "$activeSpokenTtsText $cleanAck".trim()
                                    firstClauseSent = true
                                    setPhase(ConversationPhase.SPEAKING_REPLY)
                                    fishClient.sendTextClause(cleanAck)
                                    _state.update { it.copy(assistantReply = cleanAck) }
                                    Log.d(PipelineConfig.LOG_TAG, "[DynamicAck] Spoke AI cue: $cleanAck")
                                }
                            }
                        }
                    }
                } else null

                if (decision.intentMode == IntentMode.TOOL_ACTION) {
                    val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val isLockedNow = km?.isKeyguardLocked == true
                    extraPromptContext += "\n\n[LIVE DEVICE STATE: locked=$isLockedNow, foreground_app=${activePkg.ifBlank { "unknown" }}]"
                    extraPromptContext += "\n\n[SPEECH FLOW RULE: A short live acknowledgment cue may already be playing while your tools run. Do NOT start your final answer with 'Checking...', 'Looking...', 'Opening...', or 'Let me check...'; jump straight to the verified result.]"
                }
                if (fromFollowUp) {
                    extraPromptContext += "\n\n[FOLLOW-UP MODE: User spoke without wake word after your last reply. If this looks like background noise, TV/room chatter, or third-party speech not meant for you, reply ONLY with [IGNORE_NOISE].]"
                }

                val llmContext = if (extraPromptContext.isNotEmpty()) {
                    ctxWindow.copy(systemPrompt = ctxWindow.systemPrompt + extraPromptContext)
                } else {
                    ctxWindow
                }

                // 5. Stream Phase B LLM + Tool Loop -> strip any duplicate Phase A ack -> Fish Audio WS
                var duplicateAckChecked = false
                val duplicateBuffer = StringBuilder()
                var ignoredByLlm = false

                llmRouter.streamWithDecision(
                    decision = effectiveDecision,
                    context = llmContext,
                    userText = userText,
                    imageBase64 = preCapturedImageBase64,
                    imageMimeType = preCapturedMimeType,
                    sourceMessageId = recordedUserMsgId,
                    fromFollowUp = fromFollowUp
                ).collect { event ->
                    when (event) {
                        is LlmStreamEvent.IgnoredNoise -> {
                            ignoredByLlm = true
                            dynamicAckJob?.cancel()
                            fishClient.abortNow()
                            Log.i(PipelineConfig.LOG_TAG, "[Turn] LLM flagged wake-free follow-up as [IGNORE_NOISE]: '$userText'")
                        }
                        is LlmStreamEvent.TextDelta -> {
                            ensureUserTurnRecorded()
                            if (!phaseBStarted) {
                                ackMutex.withLock {
                                    phaseBStarted = true
                                }
                                dynamicAckJob?.cancel()
                                if (ackSpeechSpoken.isBlank()) {
                                    duplicateAckChecked = true
                                }
                            }
                            var emitDelta = event.delta
                            if (!duplicateAckChecked) {
                                duplicateBuffer.append(emitDelta)
                                val bufStr = duplicateBuffer.toString()
                                val ackPlain = ackSpeechSpoken.replace(Regex("\\[.*?\\]"), "").trim()
                                if (bufStr.length >= ackSpeechSpoken.length + 12 || bufStr.contains('\n')) {
                                    duplicateAckChecked = true
                                    var trimmed = bufStr.trimStart()
                                    if (ackSpeechSpoken.isNotBlank() && trimmed.startsWith(ackSpeechSpoken, ignoreCase = true)) {
                                        trimmed = trimmed.removePrefix(ackSpeechSpoken).trimStart()
                                    } else if (ackPlain.isNotBlank() && trimmed.startsWith(ackPlain, ignoreCase = true)) {
                                        trimmed = trimmed.substring(ackPlain.length).trimStart()
                                    }
                                    emitDelta = trimmed
                                    duplicateBuffer.clear()
                                } else {
                                    emitDelta = ""
                                }
                            }

                            if (emitDelta.isNotEmpty()) {
                                phaseBReply.append(emitDelta)
                                val displayReply = if (ackSpeechSpoken.isNotBlank()) {
                                    "$ackSpeechSpoken ${phaseBReply.toString().trim()}".trim()
                                } else {
                                    phaseBReply.toString().trim()
                                }
                                _state.update { it.copy(assistantReply = displayReply) }
                                if (ttsOpened) {
                                    val clauses = clauseBuffer.appendDelta(emitDelta)
                                    for (c in clauses) {
                                        if (!firstClauseSent) {
                                            firstClauseSent = true
                                            setPhase(ConversationPhase.SPEAKING_REPLY)
                                        }
                                        activeSpokenTtsText = "$activeSpokenTtsText $c".trim()
                                        fishClient.sendTextClause(c)
                                    }
                                }
                            }
                        }
                        is LlmStreamEvent.ToolExecuted -> {
                            ensureUserTurnRecorded()
                            executedToolNames += event.name
                            val friendlyStep = humanFriendlyToolStep(event.name, event.result)
                            _state.update { snap ->
                                snap.copy(
                                    recentActionSteps = (snap.recentActionSteps + friendlyStep).distinct().takeLast(6)
                                )
                            }
                        }
                        is LlmStreamEvent.VisionHandoff -> {
                            Log.i(PipelineConfig.LOG_TAG, "[Turn] Escalating SoM screenshot to Gemini")
                            _state.update { it.copy(activeModelBadge = "Gemini Vision") }
                        }
                        is LlmStreamEvent.Completed -> {
                            actualModelUsed = event.modelUsed
                            _state.update { it.copy(activeModelBadge = actualModelUsed) }
                        }
                    }
                }
                dynamicAckJob?.cancel()

                if (ignoredByLlm) {
                    bargeInPreRoll.snapshotAndClear()
                    preRollRing.snapshotAndClear()
                    _state.update { it.copy(liveTranscript = "", routingReason = "ignored_noise") }
                    if (running.get()) setPhase(ConversationPhase.IDLE_STANDBY)
                    return@launch
                }

                // Flush any held duplicate-check buffer if final reply was short
                if (!duplicateAckChecked && duplicateBuffer.isNotEmpty()) {
                    var rem = duplicateBuffer.toString().trimStart()
                    val ackPlain = ackSpeechSpoken.replace(Regex("\\[.*?\\]"), "").trim()
                    if (rem.startsWith(ackSpeechSpoken, ignoreCase = true)) {
                        rem = rem.removePrefix(ackSpeechSpoken).trimStart()
                    } else if (ackPlain.isNotBlank() && rem.startsWith(ackPlain, ignoreCase = true)) {
                        rem = rem.substring(ackPlain.length).trimStart()
                    }
                    if (rem.isNotEmpty()) {
                        phaseBReply.append(rem)
                        val displayReply = if (ackSpeechSpoken.isNotBlank()) {
                            "$ackSpeechSpoken ${phaseBReply.toString().trim()}".trim()
                        } else {
                            phaseBReply.toString().trim()
                        }
                        _state.update { it.copy(assistantReply = displayReply) }
                        if (ttsOpened) {
                            clauseBuffer.appendDelta(rem).forEach { c ->
                                activeSpokenTtsText = "$activeSpokenTtsText $c".trim()
                                fishClient.sendTextClause(c)
                            }
                        }
                    }
                }

                if (ttsOpened) {
                    clauseBuffer.drainRemainder()?.let { rem ->
                        if (!firstClauseSent) setPhase(ConversationPhase.SPEAKING_REPLY)
                        activeSpokenTtsText = "$activeSpokenTtsText $rem".trim()
                        fishClient.sendTextClause(rem)
                    }
                    fishClient.finishAndAwaitPlayback()
                    if (bargeInProbeOpen) {
                        bargeInProbeOpen = false
                        lastBargeInCheckedText = ""
                        latestBargeInText = ""
                        bargeInDecideJob?.cancel()
                        bargeInDecideJob = null
                        closeSttTurnIfOpen()
                    }
                    turnTranscriptSegments.clear()
                    currentPartialText = ""
                    bargeInPreRoll.snapshotAndClear()
                    suppressVadUntilMs = System.currentTimeMillis() + 300L
                    preRollRing.snapshotAndClear()
                }

                val savedAssistantText = phaseBReply.toString().trim()
                val totalMs = System.currentTimeMillis() - tTurnStart
                Log.i(
                    PipelineConfig.LOG_TAG,
                    "[Turn] Complete (${totalMs}ms, Decide=${decision.latencyMs}ms, selected=${decision.selectedTools}, executed=$executedToolNames, model=$actualModelUsed): $savedAssistantText"
                )
                _state.update {
                    it.copy(
                        lastLatencySummary = "Turn=${totalMs}ms | Decide=${decision.latencyMs}ms (${executedToolNames.size} exec) | Ctx=${ctxWindow.estimatedTokens}tok | $actualModelUsed"
                    )
                }

                // 6. Persist clean Phase B reply (without Phase A ack cluttering future few-shot history)
                if (savedAssistantText.isNotEmpty()) {
                    val userMsgIdForFact = ensureUserTurnRecorded()
                    contextManager.recordTurn("assistant", savedAssistantText, actualModelUsed, activeSessionId)
                    val refreshedSid = sessionManager.activeSessionId()
                    _state.update { it.copy(activeSessionId = refreshedSid) }
                    scope.launch(Dispatchers.IO) {
                        asyncFactExtractor.extractAfterTurn(
                            userText = userText,
                            assistantReply = savedAssistantText,
                            sourceMessageId = userMsgIdForFact,
                            geminiQuickCall = { sys, prompt ->
                                llmRouter.antigravityProvider.quickComplete(sys, prompt)
                            }
                        )
                        contextManager.maybeCompactWith(
                            summarizer = { prompt ->
                                llmRouter.antigravityProvider.quickComplete(
                                    "Summarize the following conversation turns into concise bulletless prose under 250 words preserving all user preferences, names, and key topics.",
                                    prompt
                                )
                            },
                            sessionId = refreshedSid
                        )
                    }
                }

                if (running.get()) {
                    setPhase(ConversationPhase.LISTENING)
                }
            } catch (ce: CancellationException) {
                Log.d(PipelineConfig.LOG_TAG, "Assistant turn cancelled (barge-in or new turn)")
                throw ce
            } catch (e: Exception) {
                Log.e(PipelineConfig.LOG_TAG, "Assistant turn failed: ${e.message}", e)
                ToolTraceLogger.recordException(
                    stage = "turn",
                    component = "TurnStateMachine",
                    error = e,
                    message = e.message ?: "Assistant turn failed"
                )
                _state.update { it.copy(assistantReply = "Sorry, I hit a quick connection issue.") }
                if (running.get()) setPhase(ConversationPhase.LISTENING)
            }
        }
    }

    private fun humanFriendlyToolStep(toolName: String, resultJson: String): String {
        val msg = Regex("\"message\"\\s*:\\s*\"([^\"]{2,48})\"").find(resultJson)?.groupValues?.getOrNull(1)
        return when (toolName) {
            "device_intent" -> msg ?: "Phone action completed"
            "device_status" -> "Checked device status"
            "notification_center" -> "Checked notifications"
            "personal_scheduler" -> msg ?: "Updated reminder or contact"
            "live_info" -> "Checked live info"
            "observe_screen" -> "Looked at screen"
            "ui_act" -> "Interacted with screen"
            "shizuku_shell" -> "Ran system action"
            "memory_facts" -> "Updated memory"
            "session_store" -> "Checked past chats"
            else -> toolName.replace('_', ' ')
        }
    }

    private fun setPhase(newPhase: ConversationPhase) {
        phaseEnteredAtMs = System.currentTimeMillis()
        _state.update { it.copy(phase = newPhase) }
    }

    fun release() {
        stopAudioLoop()
        sttCollectorJob?.cancel()
        localWakeEngine.release()
        audioPipeline.close()
        wakeCuePlayer.release()
        audioPlayer.release()
    }

    suspend fun injectWavFileForDebug(filePath: String): Boolean = withContext(Dispatchers.IO) {
        val file = java.io.File(filePath)
        if (!file.exists() || !file.isFile) {
            Log.e(PipelineConfig.LOG_TAG, "[ADB_DEBUG] inject_wav file missing: $filePath")
            return@withContext false
        }
        val rawBytes = file.readBytes()
        if (rawBytes.size <= 44) {
            Log.e(PipelineConfig.LOG_TAG, "[ADB_DEBUG] inject_wav too small (${rawBytes.size}B): $filePath")
            return@withContext false
        }
        val dataOffset = run {
            var pos = 12
            var found = 44
            while (pos + 8 <= rawBytes.size) {
                val id = String(rawBytes, pos, 4, Charsets.US_ASCII)
                val size = (rawBytes[pos + 4].toInt() and 0xFF) or
                    ((rawBytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((rawBytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((rawBytes[pos + 7].toInt() and 0xFF) shl 24)
                if (id == "data") {
                    found = pos + 8
                    break
                }
                pos += 8 + maxOf(0, size)
            }
            found
        }
        val totalSamples = (rawBytes.size - dataOffset) / 2
        val allShorts = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val lo = rawBytes[dataOffset + i * 2].toInt() and 0xFF
            val hi = rawBytes[dataOffset + i * 2 + 1].toInt()
            allShorts[i] = ((hi shl 8) or lo).toShort()
        }

        Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] Injecting WAV ${file.name}: $totalSamples samples (${totalSamples / 16}ms) in phase=${_state.value.phase}")
        injectingTestAudio.set(true)
        localWakeEngine.resetStream()
        suppressVadUntilMs = 0L
        try {
            val frame = ShortArray(PipelineConfig.FRAME_SAMPLES)
            var idx = 0
            while (idx < allShorts.size) {
                val count = minOf(PipelineConfig.FRAME_SAMPLES, allShorts.size - idx)
                frame.fill(0)
                allShorts.copyInto(frame, destinationOffset = 0, startIndex = idx, endIndex = idx + count)
                val now = System.currentTimeMillis()
                val phase = _state.value.phase
                val analysis = audioPipeline.processFrame(frame, PipelineConfig.FRAME_SAMPLES)
                if (phase == ConversationPhase.IDLE_STANDBY || phase == ConversationPhase.LISTENING) {
                    preRollRing.push(analysis.samples, PipelineConfig.FRAME_SAMPLES)
                } else if (phase == ConversationPhase.SPEAKING_REPLY || phase == ConversationPhase.THINKING) {
                    bargeInPreRoll.push(analysis.samples, PipelineConfig.FRAME_SAMPLES)
                }
                handleAudioFrameForPhase(phase, analysis, now)
                idx += count
                delay(PipelineConfig.FRAME_DURATION_MS)
            }
            // Append ~1.1s trailing silence so EoT detectors and barge-in silence timers settle cleanly
            val silentFrame = ShortArray(PipelineConfig.FRAME_SAMPLES)
            repeat(35) {
                val now = System.currentTimeMillis()
                val phase = _state.value.phase
                if (phase == ConversationPhase.SPEAKING ||
                    phase == ConversationPhase.PAUSE_WAIT ||
                    phase == ConversationPhase.WAKE_CHECK ||
                    phase == ConversationPhase.SPEAKING_REPLY ||
                    phase == ConversationPhase.THINKING
                ) {
                    val analysis = audioPipeline.processFrame(silentFrame, PipelineConfig.FRAME_SAMPLES)
                    handleAudioFrameForPhase(phase, analysis, now)
                }
                delay(PipelineConfig.FRAME_DURATION_MS)
            }
            true
        } finally {
            injectingTestAudio.set(false)
        }
    }
}
