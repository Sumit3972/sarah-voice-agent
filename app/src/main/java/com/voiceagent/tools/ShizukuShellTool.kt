package com.voiceagent.tools

import com.voiceagent.accessibility.IdleWaiter
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.config.asArrOrNull
import com.voiceagent.config.asBoolOrNull
import com.voiceagent.config.asIntOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.shizuku.ShizukuBridge
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ShizukuShellTool(
    private val appContext: android.content.Context
) {
    suspend fun execute(
        args: JsonObject,
        userSpokenText: String,
        screenTainted: Boolean
    ): String {
        val singleCmd = args["command"].asStrOrNull()?.trim().orEmpty()
        val batchCmds = args["commands"].asArrOrNull()
            ?.mapNotNull { (it as? JsonPrimitive)?.asStrOrNull()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .orEmpty()

        val cmdList = when {
            batchCmds.isNotEmpty() -> batchCmds.take(8)
            singleCmd.isNotBlank() -> listOf(singleCmd)
            else -> return buildJsonObject {
                put("ok", false)
                put("error", "Provide either 'command' (string) or 'commands' (non-empty array of shell commands).")
            }.toString()
        }

        if (!ShizukuBridge.isBinderAlive()) {
            return buildJsonObject {
                put("ok", false)
                put("error", "Shizuku daemon is not running. Start Shizuku app first.")
            }.toString()
        }
        if (!ShizukuBridge.isPermissionGranted()) {
            ShizukuBridge.requestPermissionIfNeeded()
            return buildJsonObject {
                put("ok", false)
                put("error", "Shizuku permission is not granted yet for Sarah.")
            }.toString()
        }

        val confirmed = args["confirmed"].asBoolOrNull() ?: false
        val stopOnError = args["stop_on_error"].asBoolOrNull() ?: true
        val delayMs = (args["delay_ms"].asIntOrNull() ?: if (cmdList.size > 1) 150 else 0).coerceIn(0, 2000).toLong()
        val observeAfter = args["observe_screen_after"].asBoolOrNull() ?: false

        // Pre-flight policy evaluation across ALL commands in the batch before executing step 0
        for ((idx, cmd) in cmdList.withIndex()) {
            val decision = ConfirmPolicyGate.evaluateShellCommand(
                command = cmd,
                userSpokenText = userSpokenText,
                confirmedByUser = confirmed,
                screenTainted = screenTainted
            )
            if (!decision.allowed) {
                return buildJsonObject {
                    put("ok", false)
                    put("policy_blocked_index", idx)
                    put("policy_blocked_command", cmd)
                    put("requires_confirmation", decision.requiresVoiceConfirmation)
                    put("error", decision.reason)
                }.toString()
            }
        }

        val svc = SaraAccessibilityService.instance
        val fgBefore = svc?.currentPackageName() ?: "unknown"
        val results = mutableListOf<JsonObject>()
        var completedCount = 0
        var allOk = true
        var failedIdx = -1

        for ((idx, cmd) in cmdList.withIndex()) {
            val res = ShizukuBridge.execShell(cmd, timeoutMs = 6000L)
            val isBenignSearchMiss = !res.ok &&
                res.exitCode == 1 &&
                res.stderr.isBlank() &&
                isSearchFilterCommand(cmd)
            val cmdOk = res.ok || isBenignSearchMiss
            results += buildJsonObject {
                put("index", idx)
                put("command", cmd)
                put("ok", cmdOk)
                put("exit_code", res.exitCode)
                if (res.stdout.isNotBlank()) {
                    put("stdout", res.stdout.take(1200))
                } else if (isBenignSearchMiss) {
                    put("stdout", "(no match)")
                }
                if (res.stderr.isNotBlank()) put("stderr", res.stderr.take(400))
            }
            if (cmdOk) {
                completedCount++
            } else {
                allOk = false
                if (failedIdx < 0) failedIdx = idx
                if (stopOnError) break
            }
            if (idx < cmdList.lastIndex && delayMs > 0L) {
                delay(delayMs)
            }
        }

        var uiTreeAfter: String? = null
        val fgAfter: String
        if (observeAfter && svc != null) {
            IdleWaiter.waitForSettle(quietWindowMs = 200L, timeoutMs = 900L)
            val snap = svc.observeTree()
            fgAfter = snap.packageName
            uiTreeAfter = snap.treeText
        } else {
            fgAfter = svc?.currentPackageName() ?: "unknown"
        }

        return buildJsonObject {
            put("ok", allOk)
            put("total_commands", cmdList.size)
            put("completed_count", completedCount)
            if (failedIdx >= 0) put("failed_index", failedIdx)
            put("fg_before", fgBefore)
            put("fg_after", fgAfter)
            put("results", buildJsonArray { results.forEach { add(it) } })
            if (uiTreeAfter != null) put("ui_tree", uiTreeAfter)
        }.toString()
    }

    private fun isSearchFilterCommand(cmd: String): Boolean {
        val lastPipeSegment = cmd.substringAfterLast('|').trim().lowercase()
        return lastPipeSegment.startsWith("grep ") ||
            lastPipeSegment.startsWith("egrep ") ||
            lastPipeSegment.startsWith("fgrep ") ||
            lastPipeSegment.startsWith("pgrep ") ||
            lastPipeSegment.startsWith("pidof ")
    }
}
