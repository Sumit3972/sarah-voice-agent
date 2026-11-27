package com.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.audio.VoiceCaptureForegroundService
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.config.WakeCueMode
import com.voiceagent.notifications.CachedNotification
import com.voiceagent.notifications.SaraNotificationListenerService
import com.voiceagent.orchestrator.SarahRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AdbDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val text = intent.getStringExtra("text").orEmpty().trim()
        val mode = intent.getStringExtra("mode").orEmpty().trim()
        val runtime = SarahRuntime.get(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] cmd='$cmd' text='$text' mode='$mode'")
                when (cmd) {
                    "test_tts" -> {
                        val sample = text.ifBlank {
                            "[happy] Hello Sumit, [laughing] inline emotion tags and Phase two tools are live!"
                        }
                        val ok = runtime.stateMachine.testFishAudioStandalone(sample)
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] test_tts result=$ok")
                    }

                    "test_stt" -> {
                        val connected = runtime.sttClient.connect()
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] Deepgram WS connect=$connected (${runtime.sttClient.activeModelTag()})")
                        if (connected) {
                            runtime.sttClient.sendSpeechStart()
                            val silenceFrame = ShortArray(PipelineConfig.FRAME_SAMPLES)
                            repeat(5) {
                                runtime.sttClient.sendAudioShorts(silenceFrame)
                                delay(32)
                            }
                            runtime.sttClient.sendSpeechEndAndFlush()
                            delay(500)
                        }
                    }

                    "wake" -> {
                        VoiceCaptureForegroundService.start(context)
                        runtime.stateMachine.triggerManualWake()
                    }

                    "toggle_mute" -> {
                        val nextMuted = runtime.stateMachine.toggleMute()
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] toggle_mute -> muted=$nextMuted")
                    }

                    "say" -> {
                        if (text.isNotBlank()) {
                            runtime.stateMachine.submitTextTurn(text)
                        }
                    }

                    "simulate_stt" -> {
                        if (text.isNotBlank()) {
                            val isFinal = !mode.equals("partial", true)
                            runtime.stateMachine.simulateIncomingStt(text, isFinal)
                        }
                    }

                    "set_lang" -> {
                        if (text.isNotBlank()) {
                            runtime.stateMachine.setSttLanguage(text)
                            Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] set_lang -> '$text'")
                        }
                    }

                    "set_wake_engine" -> {
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] Wake-word engine is offline-only (BC-ResNet-8 ONNX)")
                    }

                    "inject_wav" -> {
                        if (text.isNotBlank()) {
                            val resolvedPath = if (text.startsWith("/")) {
                                text
                            } else {
                                java.io.File(context.getExternalFilesDir("test_wavs"), text).absolutePath
                            }
                            val ok = runtime.stateMachine.injectWavFileForDebug(resolvedPath)
                            Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] inject_wav done=$ok file='$resolvedPath'")
                        }
                    }

                    "cue" -> {
                        val nextMode = if (mode.equals("silent", true)) {
                            WakeCueMode.SILENT
                        } else {
                            WakeCueMode.CUSTOM_CHIME
                        }
                        runtime.stateMachine.setWakeCueMode(nextMode)
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_DEBUG] WakeCueMode=$nextMode")
                    }

                    "sync_quotas" -> {
                        runtime.oauthPool.syncAllQuotas()
                    }

                    "shizuku_status" -> {
                        val alive = com.voiceagent.shizuku.ShizukuBridge.isBinderAlive()
                        val granted = com.voiceagent.shizuku.ShizukuBridge.isPermissionGranted()
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_SHIZUKU] alive=$alive granted=$granted")
                        if (alive && !granted) {
                            com.voiceagent.shizuku.ShizukuBridge.requestPermissionIfNeeded()
                        } else if (granted) {
                            com.voiceagent.shizuku.ShizukuBridge.autoHealOwnPermissions(context)
                        }
                    }

                    "new_session" -> {
                        val sid = runtime.sessionManager.startNewSessionNow("adb_debug") { sys, prompt ->
                            runtime.antigravityProvider.quickComplete(sys, prompt)
                        }
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_SESSION] Started fresh session: $sid")
                    }

                    "dump_sessions" -> {
                        val active = runtime.sessionManager.activeSessionId()
                        val list = runtime.dao.getRecentSessions(20)
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_SESSION] Active=$active | TotalStored=${list.size} | A11y=${SaraAccessibilityService.isConnected()} | Notif=${SaraNotificationListenerService.isConnected(context)}")
                        list.forEach { s ->
                            Log.i(PipelineConfig.LOG_TAG, "  SESS ${s.sessionId} turns=${s.turnCount} title='${s.title}' digest='${s.digest}' archived=${s.isArchived}")
                        }
                    }

                    "dump_tools" -> {
                        val lines = ToolTraceLogger.readRecentTraceLines(60)
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_TOOLS] TraceLines=${lines.size}")
                        lines.forEach { line ->
                            Log.i(PipelineConfig.LOG_TAG, "  TRACE $line")
                        }
                    }

                    "dump_memory" -> {
                        val active = runtime.sessionManager.activeSessionId()
                        val facts = runtime.dao.getRecentFacts(50)
                        val msgs = runtime.dao.getRecentMessagesDesc(sessionId = active, limit = 50)
                        val summary = runtime.dao.getLatestSummary(active)
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_MEMORY] Session=$active Facts=${facts.size}, RecentMsgs=${msgs.size}, HasSummary=${summary != null}")
                        facts.forEach { f ->
                            Log.i(PipelineConfig.LOG_TAG, "  FACT [${f.category}] ${f.factKey} = ${f.factValue} (pinned=${f.isPinned})")
                        }
                        msgs.reversed().forEach { m ->
                            Log.i(PipelineConfig.LOG_TAG, "  MSG #${m.id} (${m.sessionId}/${m.role}/${m.modelUsed}): ${m.content.take(180)}")
                        }
                    }

                    "clear_memory" -> {
                        runtime.dao.clearAllMessages()
                        runtime.dao.clearAllSummaries()
                        runtime.dao.clearAllSessions()
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_MEMORY] Cleared chat_messages, conversation_summaries & chat_sessions")
                    }

                    "dump_autonomous" -> {
                        val tasks = runtime.dao.getAllAutonomousTasks()
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_AUTONOMOUS] Tasks=${tasks.size} AutoUnlock=${SecretsProvider.isAutonomousAutoUnlockEnabled(context)}")
                        tasks.forEach { t ->
                            Log.i(
                                PipelineConfig.LOG_TAG,
                                "  TASK #${t.id} [${t.status}] mode=${t.mode} title='${t.title}' sender='${t.watchSender}' runs=${t.runCount}/${t.maxRuns} last='${t.lastResultSummary.take(120)}'"
                            )
                            val logs = runtime.dao.getRecentLogsForTask(t.id, 3)
                            logs.forEach { l ->
                                Log.i(PipelineConfig.LOG_TAG, "    LOG #${l.id} [${l.triggerSource}/${l.outcome}] tools=${l.toolsUsed} :: ${l.summary.take(120)}")
                            }
                        }
                    }

                    "simulate_notif" -> {
                        val sender = intent.getStringExtra("sender").orEmpty().ifBlank { "TestPriya" }
                        val pkg = intent.getStringExtra("pkg").orEmpty().ifBlank { "com.whatsapp" }
                        val body = text.ifBlank { "Hey! Are you free right now?" }
                        val notif = CachedNotification(
                            key = "adb_sim_${pkg}_${sender}_${System.currentTimeMillis()}",
                            packageName = pkg,
                            appLabel = if (pkg.contains("whatsapp")) "WhatsApp" else pkg.substringAfterLast('.'),
                            title = sender,
                            text = body,
                            postTime = System.currentTimeMillis(),
                            hasReplyAction = false,
                            replyAction = null
                        )
                        SaraNotificationListenerService.injectDebugNotification(notif)
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_AUTONOMOUS] Injected simulated notification from '$sender' ($pkg): '$body'")
                    }

                    "trigger_task" -> {
                        val explicitId = intent.getLongExtra("task_id", -1L)
                        val targetId = if (explicitId > 0L) {
                            explicitId
                        } else {
                            runtime.dao.getActiveAutonomousTasks().firstOrNull()?.id ?: -1L
                        }
                        if (targetId > 0L) {
                            runtime.autonomousEngine.triggerTaskNow(targetId)
                            Log.i(PipelineConfig.LOG_TAG, "[ADB_AUTONOMOUS] Triggered immediate tick for task #$targetId")
                        } else {
                            Log.w(PipelineConfig.LOG_TAG, "[ADB_AUTONOMOUS] No active task found to trigger")
                        }
                    }

                    "stop_all_tasks" -> {
                        val count = runtime.autonomousEngine.stopAllTasks()
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_AUTONOMOUS] Stopped all ($count) autonomous tasks")
                    }

                    "set_auto_unlock" -> {
                        val enabled = mode.equals("on", true) || mode.equals("true", true) || text.equals("on", true) || text.equals("true", true)
                        SecretsProvider.setAutonomousAutoUnlockEnabled(context, enabled)
                        Log.i(PipelineConfig.LOG_TAG, "[ADB_AUTONOMOUS] set_auto_unlock -> $enabled")
                    }
                }
            } catch (e: Exception) {
                Log.e(PipelineConfig.LOG_TAG, "[ADB_DEBUG] Error: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}
