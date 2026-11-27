package com.voiceagent

import android.media.AudioManager
import com.voiceagent.audio.CallAndAudioFocusCoordinator
import com.voiceagent.audio.LocalWakeWordEngine
import com.voiceagent.audio.PreRollRingBuffer
import com.voiceagent.audio.SmartTurnDetector
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.llm.AntigravityOAuthManager
import com.voiceagent.llm.MercuryDecideRouter
import com.voiceagent.llm.OAuthAccountPool
import com.voiceagent.llm.RouteDecisionTarget
import com.voiceagent.memory.ChatMessageEntity
import com.voiceagent.orchestrator.ConversationPhase
import com.voiceagent.orchestrator.LocalFastPathMatcher
import com.voiceagent.orchestrator.VoiceCommandFilters
import com.voiceagent.stt.DeepgramMode
import com.voiceagent.stt.DeepgramRealtimeWsClient
import com.voiceagent.stt.SttEvent
import com.voiceagent.tools.ConfirmPolicyGate
import com.voiceagent.tools.ImageAttachmentEncoder
import com.voiceagent.tts.ClauseStreamBuffer
import com.voiceagent.tts.EmotionTagNormalizer
import com.voiceagent.ui.friendlyPhaseLabel
import com.voiceagent.ui.prepareVisibleChatMessages
import com.voiceagent.ui.shouldShowLiveTurnOverlay
import com.voiceagent.ui.shouldShowLiveUserBubble
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePipelineUnitTest {

    @Test
    fun preRollRingBuffer_preservesLatestOrderAcrossWrapAround() {
        val ring = PreRollRingBuffer(5)
        ring.push(shortArrayOf(1, 2, 3))
        ring.push(shortArrayOf(4, 5, 6, 7))
        val snap = ring.snapshotAndClear()
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), snap)
        assertEquals(0, ring.snapshotAndClear().size)
    }

    @Test
    fun smartTurnDetector_extendsTimeoutOnHesitationAndDanglingConjunctions() {
        val detector = SmartTurnDetector()
        val speech = ShortArray(8000) { 1200 }
        val hesitation = detector.evaluatePause(speech, "Open WhatsApp and uh")
        assertEquals(PipelineConfig.T_WAIT_HESITATION_MS, hesitation.recommendedWaitMs)
        assertTrue(hesitation.completionProbability < 0.5)

        val complete = detector.evaluatePause(speech, "Tell me a quick joke about space.")
        assertEquals(PipelineConfig.T_WAIT_HIGH_CONF_MS, complete.recommendedWaitMs)
        assertTrue(complete.completionProbability >= 0.8)
    }

    @Test
    fun emotionTagNormalizer_stripsMarkdownAndEmojisAndPreservesInlineTags() {
        val out = EmotionTagNormalizer.sanitizeForSpeech(
            "[excited] **Hello!** 🌟 Here is your *update*, [laughing] that was fast!",
            allowLeadingEmotionTag = true,
            allowInlineEmotionTags = true
        )
        assertEquals("[excited] Hello! Here is your update, [laughing] that was fast!", out)

        val buf = ClauseStreamBuffer()
        val first = buf.appendDelta("Let me tell you a quick secret, [whispering] I already saved it. ")
        assertEquals(2, first.size)
        assertEquals("Let me tell you a quick secret,", first[0])
        assertEquals("[whispering] I already saved it.", first[1])

        val second = buf.appendDelta("That was honestly [laughing] so funny. ")
        assertTrue(second.isNotEmpty())
        assertEquals("That was honestly [laughing] so funny.", second.first())
    }

    @Test
    fun clauseStreamBuffer_emitsCleanSpokenClauses() {
        val buf = ClauseStreamBuffer()
        val first = buf.appendDelta("Hello Sumit, how is your day going? ")
        assertTrue(first.isNotEmpty())
        assertEquals("Hello Sumit, how is your day going?", first.first())
        val second = buf.appendDelta("Let me know if you need help")
        assertTrue(second.isEmpty())
        assertEquals("Let me know if you need help", buf.drainRemainder())
    }

    @Test
    fun voiceCommandFilters_wakeBargeInStopVsContinueAndSelfEchoGuard() {
        assertEquals("stop", VoiceCommandFilters.stripWakeWord("Sarah, stop"))
        assertEquals("Stop", VoiceCommandFilters.stripWakeWord("Stop sara."))
        assertEquals("explain me about life", VoiceCommandFilters.stripWakeWord("Sara, explain me about life"))
        assertEquals("tell me about Mars", VoiceCommandFilters.stripWakeWord("Hey Sarah tell me about Mars"))
        assertNull(VoiceCommandFilters.stripWakeWord("Open Saraswati temple map"))

        assertTrue(VoiceCommandFilters.isStopCommand("Sarah stop"))
        assertTrue(VoiceCommandFilters.isStopCommand("Stop sara."))
        assertTrue(VoiceCommandFilters.isDirectBargeInStop("Stop sara.", "Yes I am ready"))
        assertTrue(VoiceCommandFilters.isDirectBargeInStop("stop", "Yes I am ready"))
        assertTrue(VoiceCommandFilters.isStopCommand("please stop"))
        assertFalse(VoiceCommandFilters.isStopCommand("stop talking about TCP and tell me about UDP"))

        assertTrue(VoiceCommandFilters.isLikelySelfEcho("Hi my name is Sarah", "Hi my name is Sarah and I am ready."))
        assertTrue(VoiceCommandFilters.isLikelySelfEcho("my name is Sara", "Hi my name is Sarah and I am ready."))
        assertFalse(VoiceCommandFilters.isLikelySelfEcho("Actually check December leave", "Hi my name is Sarah and I am ready."))
        assertFalse(VoiceCommandFilters.isLikelySelfEcho("Sarah tell me about Mars", "TCP uses congestion control in the kernel."))
        assertFalse(VoiceCommandFilters.isLikelySelfEcho("Sarah stop", "Hi my name is Sarah"))

        assertTrue(VoiceCommandFilters.isLowValueFollowUpNoise("हाँ", assistantAskedQuestion = false))
        assertTrue(VoiceCommandFilters.isLowValueFollowUpNoise("पे।", assistantAskedQuestion = false))
        assertTrue(VoiceCommandFilters.isLowValueFollowUpNoise("ओके ।", assistantAskedQuestion = false))
        assertTrue(VoiceCommandFilters.isLowValueFollowUpNoise("जी। पर", assistantAskedQuestion = true))
        assertTrue(VoiceCommandFilters.isLowValueFollowUpNoise("ओके ।", assistantAskedQuestion = true))
        assertNull(VoiceCommandFilters.stripWakeWord("सराहना करना अच्छी बात है"))
        assertNull(VoiceCommandFilters.stripWakeWord("तो सर आपने कोई ईमेल किया था"))
        assertFalse(VoiceCommandFilters.isLowValueFollowUpNoise("yes", assistantAskedQuestion = true))
        assertFalse(VoiceCommandFilters.isLowValueFollowUpNoise("181818", assistantAskedQuestion = true))
        assertFalse(VoiceCommandFilters.isLowValueFollowUpNoise("open instagram", assistantAskedQuestion = false))

        val stopEval = SmartTurnDetector().evaluatePause(ShortArray(8000) { 800 }, "stop")
        assertEquals(180L, stopEval.recommendedWaitMs)
    }

    @Test
    fun securityPolicyAndModelConstants_enforceSingleShotAndNoLegacyLiteModels() {
        assertEquals("gemini-4-hydrogen-sonar", OAuthAccountPool.MODEL_GEMINI_4_HYDROGEN)
        assertEquals("gemini-3.8-flash-high", OAuthAccountPool.MODEL_GEMINI_38_FLASH_HIGH)
        assertEquals(60, PipelineConfig.MAX_TOOL_ROUNDS)
        assertEquals(9.5, PipelineConfig.BARGE_IN_SNR_DB, 0.01)
        assertTrue(ConfirmPolicyGate.evaluateShellCommand("pm list packages | grep -i dune", "", false, false).allowed)
        assertFalse(ConfirmPolicyGate.evaluateShellCommand("rm -rf /sdcard", "", false, false).allowed)
    }

    @Test
    fun keywordGatedBargeIn_blocksAmbientNoiseAndAcceptsKeywords() {
        val reply = "Your battery is currently at sixty percent and Wi-Fi is connected."
        // Ambient background speech without wake/stop/wait keyword must NOT interrupt Sarah
        assertNull(VoiceCommandFilters.shouldAcceptBargeInTranscript("the weather is nice today", reply))
        assertNull(VoiceCommandFilters.shouldAcceptBargeInTranscript("hello how are you doing", reply))
        assertNull(VoiceCommandFilters.shouldAcceptBargeInTranscript("your battery is currently", reply))

        // Wake keyword interrupts cleanly
        assertEquals("WAKE", VoiceCommandFilters.shouldAcceptBargeInTranscript("Sarah open WhatsApp", reply))
        assertEquals("WAKE", VoiceCommandFilters.shouldAcceptBargeInTranscript("Hey Sarah", reply))

        // Stop keywords interrupt unless echoed by Sarah
        assertEquals("STOP", VoiceCommandFilters.shouldAcceptBargeInTranscript("Stop", reply))
        assertEquals("STOP", VoiceCommandFilters.shouldAcceptBargeInTranscript("ruko", reply))
        assertNull(VoiceCommandFilters.shouldAcceptBargeInTranscript("stop", "Please wait or stop talking now."))

        // Wait keywords pause Sarah to LISTENING unless echoed by Sarah
        assertEquals("WAIT", VoiceCommandFilters.shouldAcceptBargeInTranscript("Wait", reply))
        assertEquals("WAIT", VoiceCommandFilters.shouldAcceptBargeInTranscript("hold on", reply))
        assertEquals("WAIT", VoiceCommandFilters.shouldAcceptBargeInTranscript("suno", reply))
        assertNull(VoiceCommandFilters.shouldAcceptBargeInTranscript("wait", "Please wait a moment while I check."))
    }

    @Test
    fun mercuryDecideRouter_assignsInstantMediumAndHighReasoningTiers() {
        val fastChat = MercuryDecideRouter.resolveTargetAndLevel(
            bundle = "pure_chat",
            rawLevel = "instant",
            mergedTools = emptySet(),
            needsVision = false,
            activePackage = "com.voiceagent",
            userUtterance = "Tell me a one line joke"
        )
        assertEquals(RouteDecisionTarget.MERCURY_FAST to "instant", fastChat)

        val quickIntent = MercuryDecideRouter.resolveTargetAndLevel(
            bundle = "device_intent_only",
            rawLevel = "instant",
            mergedTools = setOf("device_intent"),
            needsVision = false,
            activePackage = "com.android.launcher",
            userUtterance = "Turn on flashlight"
        )
        assertEquals(RouteDecisionTarget.MERCURY_FAST to "instant", quickIntent)

        val uiAutomation = MercuryDecideRouter.resolveTargetAndLevel(
            bundle = "screen_automation",
            rawLevel = "medium",
            mergedTools = setOf("observe_screen", "ui_act"),
            needsVision = false,
            activePackage = "com.android.settings",
            userUtterance = "Open Wi-Fi settings"
        )
        assertEquals(RouteDecisionTarget.GEMINI_SMART to "MEDIUM", uiAutomation)

        val deepReasoning = MercuryDecideRouter.resolveTargetAndLevel(
            bundle = "deep_reasoning",
            rawLevel = "high",
            mergedTools = emptySet(),
            needsVision = false,
            activePackage = "com.voiceagent",
            userUtterance = "Explain step by step why TCP uses a three way handshake"
        )
        assertEquals(RouteDecisionTarget.GEMINI_SMART to "HIGH", deepReasoning)

        val lockscreenLow = MercuryDecideRouter.resolveTargetAndLevel(
            bundle = "device_intent_only",
            rawLevel = "instant",
            mergedTools = setOf("device_intent"),
            needsVision = false,
            activePackage = "com.android.systemui",
            userUtterance = "Unlock my phone"
        )
        assertEquals(RouteDecisionTarget.GEMINI_SMART to "LOW", lockscreenLow)

        val fgAppUiTools = MercuryDecideRouter.applyActiveAppUiTools(
            userUtterance = "Search weather in Chrome and add another word",
            activePackage = "com.android.chrome",
            tools = setOf("live_info")
        )
        assertTrue("observe_screen" in fgAppUiTools)
        assertTrue("ui_act" in fgAppUiTools)

        assertEquals("screen_off", LocalFastPathMatcher.match("Hey Sarah, please turn off my screen.")?.action)
        assertEquals("unlock_device", LocalFastPathMatcher.match("Please unlock my phone")?.action)
        assertEquals("close_all_apps", LocalFastPathMatcher.match("Close all apps now")?.action)
        assertEquals("stop_all_tasks", LocalFastPathMatcher.match("Stop all background tasks")?.action)
        assertNull(LocalFastPathMatcher.match("Unlock my phone and open Chrome"))
    }

    @Test
    fun followUpDecideGate_ignoresBackgroundNoiseAndAllowsRealFollowUps() {
        assertFalse(MercuryDecideRouter.shouldIgnoreFollowUp(fromFollowUp = false, addressedScore = 0.05, assistantAskedQuestion = false))
        assertTrue(MercuryDecideRouter.shouldIgnoreFollowUp(fromFollowUp = true, addressedScore = 0.22, assistantAskedQuestion = false))
        assertTrue(MercuryDecideRouter.shouldIgnoreFollowUp(fromFollowUp = true, addressedScore = 0.42, assistantAskedQuestion = true))
        assertTrue(MercuryDecideRouter.shouldIgnoreFollowUp(fromFollowUp = true, addressedScore = 0.65, assistantAskedQuestion = true, chatterScore = 0.72))
        assertFalse(MercuryDecideRouter.shouldIgnoreFollowUp(fromFollowUp = true, addressedScore = 0.78, assistantAskedQuestion = false, chatterScore = 0.12))
        assertFalse(MercuryDecideRouter.shouldIgnoreFollowUp(fromFollowUp = true, addressedScore = 0.74, assistantAskedQuestion = true, chatterScore = 0.10))
        assertTrue(
            MercuryDecideRouter.shouldIgnoreFollowUpOnFallback(
                fromFollowUp = true,
                userUtterance = "जी। पर",
                fallbackTools = emptyList(),
                needsVision = false,
                assistantAskedQuestion = true
            )
        )
        assertFalse(
            MercuryDecideRouter.shouldIgnoreFollowUpOnFallback(
                fromFollowUp = true,
                userUtterance = "181818",
                fallbackTools = emptyList(),
                needsVision = false,
                assistantAskedQuestion = true
            )
        )
        assertFalse(
            MercuryDecideRouter.shouldIgnoreFollowUpOnFallback(
                fromFollowUp = true,
                userUtterance = "Open Wi-Fi settings",
                fallbackTools = listOf("device_intent"),
                needsVision = false,
                assistantAskedQuestion = false
            )
        )
    }

    @Test
    fun chatUiHelpers_stripTagsHideToolTraceAndDeduplicateLiveUserBubble() {
        val cleaned = EmotionTagNormalizer.stripTagsForDisplay(
            "[warmly] Pulling up your latest code. <untrusted_notification_data></untrusted_notification_data>"
        )
        assertEquals("Pulling up your latest code.", cleaned)

        val dbDesc = listOf(
            ChatMessageEntity(id = 4, role = "assistant", content = "[warmly] All set."),
            ChatMessageEntity(id = 3, role = "tool", content = "RESULT notification_center {\"can_inline_reply\":false}"),
            ChatMessageEntity(id = 2, role = "assistant", content = "w\":\"<untrusted_notification_data></untrusted_notification_data>\",\"can_inline_reply\":false}"),
            ChatMessageEntity(id = 1, role = "user", content = "Check my latest OTP")
        )
        val visible = prepareVisibleChatMessages(dbDesc)
        assertEquals(2, visible.size)
        assertEquals("user", visible[0].role)
        assertEquals("Check my latest OTP", visible[0].content)
        assertEquals("assistant", visible[1].role)
        assertEquals("All set.", visible[1].content)

        val userAlreadySaved = listOf(ChatMessageEntity(id = 10, role = "user", content = "Check my latest OTP"))
        assertFalse(shouldShowLiveUserBubble(userAlreadySaved, "Check my latest OTP"))
        assertTrue(shouldShowLiveTurnOverlay(ConversationPhase.THINKING, "Check my latest OTP", "routing..."))
        assertFalse(shouldShowLiveTurnOverlay(ConversationPhase.THINKING, "room chatter", "checking relevance..."))
        assertFalse(shouldShowLiveTurnOverlay(ConversationPhase.IDLE_STANDBY, "Check my latest OTP", "ready"))
    }

    @Test
    fun secretsProviderAndUiHelpers_parseOAuthJsonAndMapFriendlyStates() {
        val arrayParsed = SecretsProvider.parseAntigravityAccountsJson(
            """[{"email":"pro@example.com","refresh_token":"1//abc123"}]"""
        )
        assertEquals(1, arrayParsed.size)
        assertEquals("pro@example.com", arrayParsed.first().email)
        assertEquals("1//abc123", arrayParsed.first().refresh_token)

        val singleParsed = SecretsProvider.parseAntigravityAccountsJson(
            """{"email":"single@example.com","refresh_token":"1//xyz789"}"""
        )
        assertEquals(1, singleParsed.size)
        assertEquals("1//xyz789", singleParsed.first().refresh_token)

        assertEquals("Ready · Say 'Sarah'", friendlyPhaseLabel(ConversationPhase.IDLE_STANDBY, micMuted = false))
        assertEquals("Paused · On call", friendlyPhaseLabel(ConversationPhase.IDLE_STANDBY, micMuted = false, callPaused = true))
        assertEquals("Working on it...", friendlyPhaseLabel(ConversationPhase.THINKING, micMuted = false))
        assertEquals("Mic paused", friendlyPhaseLabel(ConversationPhase.IDLE_STANDBY, micMuted = true))
    }

    @Test
    fun antigravityOAuthManager_buildsValidAuthUrlAndParsesCallbacks() {
        val url = AntigravityOAuthManager.buildAuthorizationUrl(
            redirectUri = "http://127.0.0.1:45123/oauth-callback",
            state = "state-xyz",
            clientId = "1071006060591-test.apps.googleusercontent.com"
        )
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("client_id=1071006060591-test.apps.googleusercontent.com"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
        assertTrue(url.contains("aicode"))

        val parsedUrl = AntigravityOAuthManager.parseManualCallbackInput(
            "http://127.0.0.1:45123/oauth-callback?code=4%2F0Abc123&state=state-xyz"
        )
        assertTrue(parsedUrl.isSuccess)
        assertEquals("4/0Abc123", parsedUrl.getOrThrow().first)
        assertEquals("state-xyz", parsedUrl.getOrThrow().second)

        val parsedRaw = AntigravityOAuthManager.parseManualCallbackInput("4%2F0RawCode")
        assertTrue(parsedRaw.isSuccess)
        assertEquals("4/0RawCode", parsedRaw.getOrThrow().first)
        assertNull(parsedRaw.getOrThrow().second)

        val parsedError = AntigravityOAuthManager.parseManualCallbackInput(
            "http://127.0.0.1:45123/oauth-callback?error=access_denied"
        )
        assertTrue(parsedError.isFailure)
    }

    @Test
    fun roadmapV2Helpers_normalizeTiersDetectCallsScaleImagesAndResolveSafety() {
        assertEquals("ULTRA", OAuthAccountPool.normalizeTierLabel("g1-ultra-tier"))
        assertEquals("PRO", OAuthAccountPool.normalizeTierLabel("g1-pro-tier"))
        assertEquals("FREE", OAuthAccountPool.normalizeTierLabel("free-tier"))
        assertEquals("FREE", OAuthAccountPool.normalizeTierLabel(null))

        assertTrue(CallAndAudioFocusCoordinator.isCallAudioMode(AudioManager.MODE_IN_CALL))
        assertTrue(CallAndAudioFocusCoordinator.isCallAudioMode(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(CallAndAudioFocusCoordinator.isCallAudioMode(AudioManager.MODE_RINGTONE))
        assertFalse(CallAndAudioFocusCoordinator.isCallAudioMode(AudioManager.MODE_NORMAL))

        assertEquals(1024 to 512, ImageAttachmentEncoder.computeScaledDimensions(2048, 1024, 1024))
        assertEquals(800 to 600, ImageAttachmentEncoder.computeScaledDimensions(800, 600, 1024))

        assertTrue(LocalWakeWordEngine.matchesKeywordText("sarah"))
        assertFalse(LocalWakeWordEngine.matchesKeywordText("   "))
        assertTrue(
            com.voiceagent.config.WakeSensitivity.STRICT.threshold >
                com.voiceagent.config.WakeSensitivity.BALANCED.threshold
        )
        assertTrue(
            com.voiceagent.config.WakeSensitivity.BALANCED.threshold >
                com.voiceagent.config.WakeSensitivity.HIGH_PICKUP.threshold
        )
        assertEquals(1.0f, LocalWakeWordEngine.computeAdaptiveGain(0.15f), 0.01f)
        assertEquals(1.0f, LocalWakeWordEngine.computeAdaptiveGain(0.05f), 0.01f)
        assertEquals(3.5f, LocalWakeWordEngine.computeAdaptiveGain(0.003f), 0.01f)
        assertEquals(1.0f, LocalWakeWordEngine.computeAdaptiveGain(0.0005f), 0.01f)
        val boosted = LocalWakeWordEngine.applyGainClamped(floatArrayOf(0.01f, -0.3f), 5.0f)
        assertEquals(0.05f, boosted[0], 0.001f)
        assertEquals(-0.95f, boosted[1], 0.001f)

        val probHigh = LocalWakeWordEngine.softmaxWakeProb(-2.0f, 2.0f)
        val probLow = LocalWakeWordEngine.softmaxWakeProb(2.0f, -2.0f)
        assertTrue(probHigh > 0.95f)
        assertTrue(probLow < 0.05f)

        val extractor = com.voiceagent.audio.LogMelExtractor()
        val sine = FloatArray(com.voiceagent.audio.LogMelExtractor.WINDOW_SAMPLES) { i ->
            (0.25 * kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16000.0)).toFloat()
        }
        val mel = extractor.computeLogMel(sine)
        assertEquals(
            com.voiceagent.audio.LogMelExtractor.N_MELS * com.voiceagent.audio.LogMelExtractor.EXPECTED_FRAMES,
            mel.size
        )
        assertTrue(mel.all { it.isFinite() })
        val mean = mel.average()
        val std = kotlin.math.sqrt(mel.map { (it - mean) * (it - mean) }.average())
        assertEquals(0.0, mean, 1e-3)
        assertEquals(1.0, std, 1e-2)
        assertEquals(2.40351f, mel[0], 0.01f)
        assertEquals(1.39925f, mel[1], 0.01f)
        assertEquals(-0.53306f, mel[mel.size - 10], 0.01f)
        assertEquals(0.31765f, mel[mel.size - 1], 0.01f)

        ConfirmPolicyGate.clearPendingConfirmation()
        assertNull(ConfirmPolicyGate.pendingConfirmation.value)
    }

    @Test
    fun deepgramClient_routesLanguageModesAndParsesNova3AndFluxFrames() {
        assertEquals(DeepgramMode.NOVA3_MULTI, DeepgramRealtimeWsClient.modeForLanguage("auto"))
        assertEquals(DeepgramMode.FLUX_EN, DeepgramRealtimeWsClient.modeForLanguage("en-IN"))

        val novaUrl = DeepgramRealtimeWsClient.buildWebSocketUrl("auto")
        assertTrue(novaUrl.startsWith("wss://api.in.deepgram.com/v1/listen"))
        assertTrue(novaUrl.contains("model=nova-3"))
        assertTrue(novaUrl.contains("language=multi"))
        assertTrue(novaUrl.contains("language_hint=en&language_hint=hi"))
        assertTrue(novaUrl.contains("keyterm=Sarah"))

        val fluxUrl = DeepgramRealtimeWsClient.buildWebSocketUrl("en-IN")
        assertTrue(fluxUrl.startsWith("wss://api.in.deepgram.com/v2/listen"))
        assertTrue(fluxUrl.contains("model=flux-general-en"))
        assertTrue(fluxUrl.contains("keyterm=Sarah"))
        assertFalse(fluxUrl.contains("smart_format"))

        val novaInterim = DeepgramRealtimeWsClient.parseMessage(
            """{"type":"Results","is_final":false,"speech_final":false,"channel":{"alternatives":[{"transcript":"Sarah open"}]}}""",
            DeepgramMode.NOVA3_MULTI
        )
        assertEquals(listOf(SttEvent.Partial("Sarah open")), novaInterim)

        val novaFinal = DeepgramRealtimeWsClient.parseMessage(
            """{"type":"Results","is_final":true,"speech_final":true,"channel":{"alternatives":[{"transcript":"Sarah open WhatsApp."}]}}""",
            DeepgramMode.NOVA3_MULTI
        )
        assertEquals(listOf(SttEvent.Final("Sarah open WhatsApp."), SttEvent.SpeechEnd), novaFinal)

        assertEquals(
            listOf(SttEvent.SpeechStart),
            DeepgramRealtimeWsClient.parseMessage("""{"type":"SpeechStarted"}""", DeepgramMode.NOVA3_MULTI)
        )
        assertEquals(
            listOf(SttEvent.SpeechEnd),
            DeepgramRealtimeWsClient.parseMessage("""{"type":"UtteranceEnd"}""", DeepgramMode.NOVA3_MULTI)
        )

        val fluxStart = DeepgramRealtimeWsClient.parseMessage(
            """{"type":"TurnInfo","event":"StartOfTurn","transcript":"Sarah"}""",
            DeepgramMode.FLUX_EN
        )
        assertEquals(listOf(SttEvent.SpeechStart, SttEvent.Partial("Sarah")), fluxStart)

        val fluxEnd = DeepgramRealtimeWsClient.parseMessage(
            """{"type":"TurnInfo","event":"EndOfTurn","transcript":"Sarah, check my battery.","end_of_turn_confidence":0.84}""",
            DeepgramMode.FLUX_EN
        )
        assertEquals(
            listOf(
                SttEvent.Final("Sarah, check my battery."),
                SttEvent.EndOfTurn("Sarah, check my battery.", 0.84)
            ),
            fluxEnd
        )
    }

    @Test
    fun smartMidSpeechBargeIn_interruptsOnCommandsAndIgnoresBackchannels() {
        assertTrue(MercuryDecideRouter.shouldInterruptMidSpeech(actionableScore = 0.88, noiseScore = 0.08))
        assertFalse(MercuryDecideRouter.shouldInterruptMidSpeech(actionableScore = 0.80, noiseScore = 0.55))
        assertFalse(MercuryDecideRouter.shouldInterruptMidSpeech(actionableScore = 0.40, noiseScore = 0.15))

        assertTrue(VoiceCommandFilters.isWakeRescuedByPartialTail("what", "what is my battery percentage?"))
        assertFalse(VoiceCommandFilters.isWakeRescuedByPartialTail("s y", "Saraswati Temple is open today."))
        assertTrue(VoiceCommandFilters.containsWakeLookalikeWord("Saraswati Temple is open today."))
        assertFalse(VoiceCommandFilters.containsWakeLookalikeWord("what is my battery percentage?"))
    }
}
