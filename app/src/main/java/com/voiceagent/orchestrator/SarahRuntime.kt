package com.voiceagent.orchestrator

import android.content.Context
import com.voiceagent.audio.LowLatencyAudioPlayer
import com.voiceagent.autonomous.AutonomousTaskEngine
import com.voiceagent.config.SecretsProvider
import com.voiceagent.debug.ToolTraceLogger
import com.voiceagent.llm.AntigravityCodeAssistProvider
import com.voiceagent.llm.Mercury3StreamingProvider
import com.voiceagent.llm.MercuryDecideRouter
import com.voiceagent.llm.OAuthAccountPool
import com.voiceagent.llm.StreamingLlmRouter
import com.voiceagent.memory.AsyncFactExtractor
import com.voiceagent.memory.ContextWindowManager
import com.voiceagent.memory.FactMemoryTools
import com.voiceagent.memory.SessionManager
import com.voiceagent.memory.VoiceMemoryDatabase
import com.voiceagent.shizuku.ShizukuBridge
import com.voiceagent.stt.DeepgramRealtimeWsClient
import com.voiceagent.tools.SaraToolRegistry
import com.voiceagent.tts.FishAudioLiveWsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SarahRuntime private constructor(context: Context) {
    val appContext: Context = context.applicationContext.also { SecretsProvider.init(it) }

    init {
        SecretsProvider.init(appContext)
    }

    val database: VoiceMemoryDatabase = VoiceMemoryDatabase.getInstance(appContext)
    val dao = database.memoryDao()
    val asyncFactExtractor = AsyncFactExtractor(dao)
    val sessionManager = SessionManager(dao, asyncFactExtractor)
    val contextManager = ContextWindowManager(dao, sessionManager)
    val factTools = FactMemoryTools(dao, sessionManager)
    val toolRegistry = SaraToolRegistry(appContext, dao, sessionManager, factTools)

    val oauthPool = OAuthAccountPool()
    val decideRouter = MercuryDecideRouter()
    val mercuryProvider = Mercury3StreamingProvider(toolRegistry)
    val antigravityProvider = AntigravityCodeAssistProvider(oauthPool, toolRegistry)
    val llmRouter = StreamingLlmRouter(decideRouter, mercuryProvider, antigravityProvider)

    val sttClient = DeepgramRealtimeWsClient()
    val callCoordinator = com.voiceagent.audio.CallAndAudioFocusCoordinator(appContext)
    val audioPlayer = LowLatencyAudioPlayer(callCoordinator)
    val fishClient = FishAudioLiveWsClient(audioPlayer)

    val stateMachine = TurnStateMachine(
        appContext = appContext,
        dao = dao,
        sessionManager = sessionManager,
        contextManager = contextManager,
        asyncFactExtractor = asyncFactExtractor,
        toolRegistry = toolRegistry,
        llmRouter = llmRouter,
        sttClient = sttClient,
        fishClient = fishClient,
        audioPlayer = audioPlayer
    )

    val autonomousEngine = AutonomousTaskEngine(
        appContext = appContext,
        dao = dao,
        toolRegistry = toolRegistry,
        llmRouter = llmRouter,
        speakProactive = { msg -> stateMachine.speakProactiveMessage(msg) }
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        ToolTraceLogger.init(appContext, dao)
        ShizukuBridge.init(appContext)
        toolRegistry.attachAutonomousEngine(autonomousEngine)
        autonomousEngine.start()
        scope.launch {
            oauthPool.syncAllQuotas()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SarahRuntime? = null

        fun get(context: Context): SarahRuntime {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SarahRuntime(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
