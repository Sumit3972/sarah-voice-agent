package com.voiceagent.config

object PipelineConfig {
    const val LOG_TAG = "VoiceAgent"

    // Audio Capture & Ring Buffer Geometry
    const val SAMPLE_RATE_HZ = 16000
    const val FRAME_SAMPLES = 512 // 32ms frame at 16kHz (native Silero VAD v5 window)
    const val FRAME_DURATION_MS = 32L
    const val PRE_ROLL_MS = 1100
    const val PRE_ROLL_SAMPLES = (SAMPLE_RATE_HZ * PRE_ROLL_MS) / 1000 // 17,600 samples (~1.1s for post-KWS pre-roll)

    // Noise Floor & Anti-Disturbance Thresholds
    const val NOISE_FLOOR_EMA_ALPHA = 0.995
    const val INITIAL_NOISE_FLOOR_DBFS = -55.0
    const val MIN_FOREGROUND_SNR_DB = 6.0
    const val BARGE_IN_SNR_DB = 9.5
    const val ONSET_CONSECUTIVE_FRAMES = 2 // 64ms fast pickup + 600ms pre-roll ring
    const val BARGE_IN_CONSECUTIVE_FRAMES = 5 // 160ms sustained speech guard against transient noise
    const val LOW_CONFIDENCE_DRIFT_GUARD_MS = 4000L
    const val MAX_UTTERANCE_MS = 45000L
    const val FOLLOW_UP_WINDOW_MS = 6500L

    // End-of-Turn Dynamic Silence Windows (patient enough for multi-clause / long speech)
    const val PAUSE_EVAL_TRIGGER_MS = 750L
    const val T_WAIT_HIGH_CONF_MS = 1350L
    const val T_WAIT_MID_CONF_MS = 1850L
    const val T_WAIT_HESITATION_MS = 3200L

    // Wake Word ("Sarah")
    const val WAKE_PREFIX_WINDOW_MS = 1800L
    val WAKE_VARIANTS = listOf(
        "sarah", "sara", "sarrah", "sera", "sahra",
        "saara", "saarah", "saraa", "saira", "serah",
        "सारा", "सेरा", "सायरा", "सरा"
    )

    // Deepgram Realtime STT (India regional endpoint)
    const val DEEPGRAM_V1_WS_BASE = "wss://api.in.deepgram.com/v1/listen"
    const val DEEPGRAM_V2_WS_BASE = "wss://api.in.deepgram.com/v2/listen"
    const val DEEPGRAM_NOVA_MODEL = "nova-3"
    const val DEEPGRAM_FLUX_MODEL = "flux-general-en"
    const val DEFAULT_LANGUAGE_CODE = "auto"

    // Fish Audio Live WebSocket TTS (S2.1 Pro)
    const val FISH_WS_URL = "wss://api.fish.audio/v1/tts/live"
    const val FISH_MODEL_INFO_URL = "https://api.fish.audio/model"
    const val FISH_TTS_SAMPLE_RATE = 24000
    const val FISH_CHUNK_LENGTH = 150

    // Verified Hard Model Context Window Limits
    const val MERCURY_3_HARD_CONTEXT_LIMIT_TOKENS = 1_000_000
    const val GEMINI_FLASH_HARD_CONTEXT_LIMIT_TOKENS = 1_000_000
    const val MERCURY_DECIDE_HARD_CONTEXT_LIMIT_TOKENS = 65_536

    // Calibrated Voice-Agent Active Context Budgets (fast TTFT + multi-step UI/session headroom)
    const val MAX_ACTIVE_CONTEXT_TOKENS = 24_000
    const val DEEP_LOOKUP_MAX_CONTEXT_TOKENS = 128_000
    const val SYSTEM_PROMPT_RESERVE_TOKENS = 900
    const val FACTS_BUDGET_TOKENS = 1_500
    const val RECENT_SESSION_DIGESTS_BUDGET_TOKENS = 600
    const val SUMMARY_BUDGET_TOKENS = 1_500
    const val DECIDE_STATE_MAX_CHARS = 1_600
    const val COMPACTION_TRIGGER_TURNS = 36
    const val COMPACTION_BATCH_TURNS = 18

    // Isolated Session Lifecycle & Retention
    const val SESSION_IDLE_TIMEOUT_MS = 10 * 60_000L // 10 minutes inactivity starts a fresh session
    const val MAX_RETAINED_RAW_SESSIONS = 50
    const val MAX_TOOL_ROUNDS = 60

    // Latency & Circuit Breaker
    const val MERCURY_DECIDE_TIMEOUT_MS = 2500L
    const val MERCURY_CHAT_TTFT_TIMEOUT_MS = 2500L
}
