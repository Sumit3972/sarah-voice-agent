package com.voiceagent.orchestrator

/**
 * Clean Phase-2 extension seam for Android device automation (AccessibilityService / Intents).
 * Phase 1 registers SQLite FactMemoryTools only; Phase 2 plugins attach here without altering
 * the core voice loop or LLM router.
 */
interface AgentToolPlugin {
    val pluginId: String
    suspend fun executeTool(name: String, argsJson: String): String
}
