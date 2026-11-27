package com.voiceagent.debug

import android.content.Context
import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.memory.ChatMessageEntity
import com.voiceagent.memory.MemoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ToolTraceLogger {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fileLock = Any()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dao: MemoryDao? = null

    @Volatile
    private var currentSessionId: String = "default"

    fun init(context: Context, memoryDao: MemoryDao) {
        appContext = context.applicationContext
        dao = memoryDao
    }

    fun setActiveSession(sessionId: String) {
        currentSessionId = sessionId
    }

    fun recordDecide(
        sessionId: String,
        userText: String,
        intentMode: String,
        target: String,
        tools: Set<String>,
        ackCategory: String,
        latencyMs: Long
    ) {
        val jsonLine = buildJsonObject {
            put("ts", timeFmt.format(Date()))
            put("type", "DECIDE")
            put("session", sessionId)
            put("latency_ms", latencyMs)
            put("intent_mode", intentMode)
            put("target", target)
            put("tools", tools.joinToString(","))
            put("ack", ackCategory)
            put("user", userText.take(180))
        }.toString()
        appendLine(jsonLine)
        Log.i(PipelineConfig.LOG_TAG, "[TOOL_TRACE] $jsonLine")
    }

    private fun redactSensitiveArgs(raw: String): String {
        return raw
            .replace(Regex("\"pin\"\\s*:\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "\"pin\":\"***\"")
            .replace(Regex("(\"fact_key\"\\s*:\\s*\"user\\.phone_lock_pin\"[^}]*\"fact_value\"\\s*:\\s*\")[^\"]*\""), "$1***\"")
            .replace(Regex("(\"fact_value\"\\s*:\\s*\")[^\"]*(\"[^}]*\"user\\.phone_lock_pin\")"), "$1***$2")
    }

    fun recordToolCall(
        sessionId: String = currentSessionId,
        round: Int = 0,
        toolName: String,
        argsJson: String,
        fgPkgBefore: String
    ) {
        val cleanArgs = redactSensitiveArgs(argsJson).replace('\n', ' ').take(400)
        val jsonLine = buildJsonObject {
            put("ts", timeFmt.format(Date()))
            put("type", "TOOL_CALL")
            put("session", sessionId)
            put("round", round)
            put("tool", toolName)
            put("fg_before", fgPkgBefore)
            put("args", cleanArgs)
        }.toString()
        appendLine(jsonLine)
        Log.i(PipelineConfig.LOG_TAG, "[TOOL_TRACE] CALL $toolName($cleanArgs) fg=$fgPkgBefore")

        insertDbRow(
            sessionId = sessionId,
            role = "tool",
            content = "CALL $toolName args=$cleanArgs fg_before=$fgPkgBefore",
            modelUsed = toolName
        )
    }

    fun recordToolResult(
        sessionId: String = currentSessionId,
        toolName: String,
        ok: Boolean,
        elapsedMs: Long,
        fgPkgAfter: String,
        resultPreview: String
    ) {
        val preview = redactSensitiveArgs(resultPreview).replace('\n', ' ').take(400)
        val jsonLine = buildJsonObject {
            put("ts", timeFmt.format(Date()))
            put("type", "TOOL_RESULT")
            put("session", sessionId)
            put("tool", toolName)
            put("ok", ok)
            put("elapsed_ms", elapsedMs)
            put("fg_after", fgPkgAfter)
            put("result", preview)
        }.toString()
        appendLine(jsonLine)
        Log.i(PipelineConfig.LOG_TAG, "[TOOL_TRACE] RESULT $toolName ok=$ok ${elapsedMs}ms fg=$fgPkgAfter -> ${preview.take(200)}")

        insertDbRow(
            sessionId = sessionId,
            role = "tool",
            content = "RESULT $toolName ok=$ok (${elapsedMs}ms, fg_after=$fgPkgAfter): $preview",
            modelUsed = toolName
        )
    }

    fun recordException(
        sessionId: String = currentSessionId,
        stage: String,
        component: String,
        error: Throwable?,
        message: String
    ) {
        val errClass = error?.javaClass?.simpleName ?: "RuntimeWarning"
        val cleanMsg = message.replace('\n', ' ').take(450)
        val jsonLine = buildJsonObject {
            put("ts", timeFmt.format(Date()))
            put("type", "EXCEPTION")
            put("session", sessionId)
            put("stage", stage)
            put("component", component)
            put("error_class", errClass)
            put("message", cleanMsg)
        }.toString()
        appendLine(jsonLine)
        Log.w(PipelineConfig.LOG_TAG, "[EXCEPTION_TRACE] [$stage/$component] $errClass: $cleanMsg")

        insertDbRow(
            sessionId = sessionId,
            role = "error",
            content = "[$stage/$component] $errClass: $cleanMsg",
            modelUsed = component
        )
    }

    fun readRecentTraceLines(limit: Int = 60): List<String> {
        val ctx = appContext ?: return emptyList()
        val f = File(ctx.filesDir, "tool_trace.jsonl")
        if (!f.exists()) return emptyList()
        return synchronized(fileLock) {
            runCatching {
                f.readLines().takeLast(limit.coerceIn(1, 200))
            }.getOrDefault(emptyList())
        }
    }

    private fun insertDbRow(sessionId: String, role: String, content: String, modelUsed: String) {
        val d = dao ?: return
        scope.launch {
            runCatching {
                d.insertMessage(
                    ChatMessageEntity(
                        sessionId = sessionId,
                        role = role,
                        content = content,
                        modelUsed = modelUsed,
                        tokenEstimate = (content.length + 3) / 4,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun appendLine(line: String) {
        val ctx = appContext ?: return
        scope.launch {
            synchronized(fileLock) {
                runCatching {
                    val f = File(ctx.filesDir, "tool_trace.jsonl")
                    f.appendText(line + "\n")
                    if (f.length() > 160_000L) {
                        val kept = f.readLines().takeLast(300)
                        f.writeText(kept.joinToString("\n") + "\n")
                    }
                }
            }
        }
    }
}
