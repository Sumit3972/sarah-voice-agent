package com.voiceagent.tools

import android.content.Context
import android.util.Log
import com.voiceagent.accessibility.AppShortcutStore
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.autonomous.AutonomousTaskEngine
import com.voiceagent.autonomous.SwarmOrchestrator
import com.voiceagent.config.PipelineConfig
import com.voiceagent.debug.ToolTraceLogger
import com.voiceagent.memory.FactMemoryTools
import com.voiceagent.memory.MemoryDao
import com.voiceagent.memory.SessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Unified 10-Tool Daily-User + Shizuku Power Catalog:
 * 1. device_intent       (Tier 1 Zero-UI Android Intents, Settings, Alarm/Timer, Call, WhatsApp Deep Link, Media, Maps)
 * 2. device_status       (Tier 1 Battery, Network, Bluetooth, DND, Volume, Clipboard, Installed Apps, RAM, Storage)
 * 3. notification_center (Tier 1 Read active notifications, OTP extract, inline RemoteInput reply, dismiss)
 * 4. personal_scheduler  (Tier 1 Contacts lookup, Calendar events, Scheduled spoken voice reminders)
 * 5. live_info           (Tier 2 Headless live weather, web search snippets, currency/unit convert, math)
 * 6. observe_screen      (Tier 2 Compressed Accessibility UI Tree + optional Set-of-Marks Vision screenshot)
 * 7. ui_act              (Tier 2 Single or batched Accessibility UI actions with EdgeGuard + ConfirmPolicyGate)
 * 8. shizuku_shell       (Tier 2 Policy-gated Shizuku single/batch shell command runner with verification)
 * 9. memory_facts        (Tier 3 Full CRUD durable user memory_facts)
 * 10. session_store      (Tier 3 Isolated conversation session management)
 */
class SaraToolRegistry(
    appContext: Context,
    dao: MemoryDao,
    sessionManager: SessionManager,
    val factTools: FactMemoryTools
) {
    companion object {
        val ALL_TOOL_NAMES = listOf(
            "device_intent",
            "device_status",
            "notification_center",
            "personal_scheduler",
            "autonomous_task",
            "swarm_dispatch",
            "live_info",
            "observe_screen",
            "ui_act",
            "shizuku_shell",
            "memory_facts",
            "session_store"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    val deviceIntentTool = DeviceIntentTool(appContext, dao)
    val deviceStatusTool = DeviceStatusTool(appContext)
    val notificationCenterTool = NotificationCenterTool(appContext)
    val personalSchedulerTool = PersonalSchedulerTool(appContext, dao)
    val autonomousTaskTool = AutonomousTaskTool(dao)
    val swarmTool = SwarmTool()
    val liveInfoTool = LiveInfoTool()
    val appShortcutStore = AppShortcutStore(dao)
    val screenTools = ScreenTools(appShortcutStore)
    val shizukuShellTool = ShizukuShellTool(appContext)

    fun attachAutonomousEngine(engine: AutonomousTaskEngine) {
        autonomousTaskTool.engine = engine
    }

    fun attachSwarmOrchestrator(orchestrator: SwarmOrchestrator) {
        swarmTool.orchestrator = orchestrator
    }

    fun clearPerTurnState() {
        screenTools.clearTurnState()
    }

    fun openAiToolsJson(selectedNames: Set<String>): JsonArray {
        val active = normalizeToolSelection(selectedNames)
        return buildJsonArray {
            if ("device_intent" in active) add(deviceIntentOpenAi())
            if ("device_status" in active) add(deviceStatusOpenAi())
            if ("notification_center" in active) add(notificationCenterOpenAi())
            if ("personal_scheduler" in active) add(personalSchedulerOpenAi())
            if ("autonomous_task" in active) add(autonomousTaskOpenAi())
            if ("swarm_dispatch" in active) add(swarmDispatchOpenAi())
            if ("live_info" in active) add(liveInfoOpenAi())
            if ("observe_screen" in active) add(observeScreenOpenAi())
            if ("ui_act" in active) add(uiActOpenAi())
            if ("shizuku_shell" in active) add(shizukuShellOpenAi())
            if ("memory_facts" in active) add(factTools.memoryFactsOpenAiSpec())
            if ("session_store" in active) add(factTools.sessionStoreOpenAiSpec())
        }
    }

    fun geminiFunctionDeclarationsJson(selectedNames: Set<String>): JsonArray {
        val active = normalizeToolSelection(selectedNames)
        return buildJsonArray {
            if ("device_intent" in active) add(deviceIntentGemini())
            if ("device_status" in active) add(deviceStatusGemini())
            if ("notification_center" in active) add(notificationCenterGemini())
            if ("personal_scheduler" in active) add(personalSchedulerGemini())
            if ("autonomous_task" in active) add(autonomousTaskGemini())
            if ("swarm_dispatch" in active) add(swarmDispatchGemini())
            if ("live_info" in active) add(liveInfoGemini())
            if ("observe_screen" in active) add(observeScreenGemini())
            if ("ui_act" in active) add(uiActGemini())
            if ("shizuku_shell" in active) add(shizukuShellGemini())
            if ("memory_facts" in active) add(factTools.memoryFactsGeminiSpec())
            if ("session_store" in active) add(factTools.sessionStoreGeminiSpec())
        }
    }

    suspend fun executeTool(
        name: String,
        argsJson: String,
        userSpokenText: String,
        sourceMessageId: Long? = null
    ): ToolExecutionOutput {
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return executeTool(name, args, userSpokenText, sourceMessageId)
    }

    suspend fun executeTool(
        name: String,
        args: JsonObject,
        userSpokenText: String,
        sourceMessageId: Long? = null
    ): ToolExecutionOutput {
        val fgBefore = SaraAccessibilityService.instance?.currentPackageName() ?: "unknown"
        val argsStr = args.toString()
        val t0 = System.currentTimeMillis()
        ToolTraceLogger.recordToolCall(
            toolName = name,
            argsJson = argsStr,
            fgPkgBefore = fgBefore
        )
        return try {
            val out = when (name) {
                "device_intent" -> ToolExecutionOutput(deviceIntentTool.execute(args))
                "device_status" -> ToolExecutionOutput(deviceStatusTool.execute(args))
                "notification_center" -> ToolExecutionOutput(notificationCenterTool.execute(args))
                "personal_scheduler" -> ToolExecutionOutput(personalSchedulerTool.execute(args))
                "autonomous_task" -> ToolExecutionOutput(autonomousTaskTool.execute(args))
                "swarm_dispatch" -> ToolExecutionOutput(swarmTool.execute(args))
                "live_info" -> ToolExecutionOutput(liveInfoTool.execute(args))
                "observe_screen" -> screenTools.observeScreen(args)
                "ui_act" -> screenTools.uiAct(args, userSpokenText)
                "shizuku_shell" -> ToolExecutionOutput(
                    shizukuShellTool.execute(args, userSpokenText, screenTools.isScreenTainted())
                )
                "memory_facts", "session_store",
                "save_memory_fact", "search_memory_facts", "delete_memory_fact" ->
                    ToolExecutionOutput(factTools.execute(name, args, sourceMessageId))
                else -> ToolExecutionOutput("""{"ok":false,"error":"Unknown tool '$name'"}""")
            }
            val fgAfter = SaraAccessibilityService.instance?.currentPackageName() ?: "unknown"
            val elapsed = System.currentTimeMillis() - t0
            val okFlag = !out.resultJson.contains("\"ok\":false")
            ToolTraceLogger.recordToolResult(
                toolName = name,
                ok = okFlag,
                elapsedMs = elapsed,
                fgPkgAfter = fgAfter,
                resultPreview = out.resultJson
            )
            out
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            ToolTraceLogger.recordException(
                stage = "tool_exec",
                component = name,
                error = e,
                message = e.message ?: "Tool execution failed"
            )
            val errJson = """{"ok":false,"error":"Tool $name exception (${elapsed}ms): ${e.message}"}"""
            ToolExecutionOutput(errJson)
        }
    }

    private fun normalizeToolSelection(selected: Set<String>): Set<String> {
        if (selected.isEmpty()) return emptySet()
        return selected.filter { it in ALL_TOOL_NAMES }.toSet()
    }

    // ---- Tool Schemas (Concise 1-line descriptions to save input tokens & preserve sub-second TTFT) ----

    private fun deviceIntentOpenAi() = openAiTool(
        name = "device_intent",
        description = "Fast zero-UI Android phone intents & Shizuku actions: open_app, unlock_device (safely enters screen PIN once per digit), close_app, close_all_apps (safe background clear protecting Sarah), force_stop_app, clear_app_cache, show_recents, open_notifications, screen_on, screen_off, go_home, go_back, system_toggle (wifi|mobile_data|bluetooth|hotspot|location|airplane|auto_rotate|dnd|dark_mode|battery_saver|screen_timeout), take_screenshot, set_alarm, set_timer, place_call, open_whatsapp_chat, play_media_query, navigate_maps, open_camera, open_url, share_text, media_control, set_volume, set_brightness, toggle_torch, set_clipboard, open_settings_panel.",
        properties = buildJsonObject {
            put("action", prop("string", "Action name"))
            put("target", prop("string", "App name, package, contact/phone, destination, URL, toggle name, stream (music|ring|alarm), settings panel, or media command"))
            put("pin", prop("string", "Optional screen lock PIN digits for unlock_device (omit to use saved PIN)"))
            put("text", prop("string", "Optional message, alarm label, or clipboard text"))
            put("hour", prop("integer", "Alarm hour 0-23"))
            put("minute", prop("integer", "Alarm minute 0-59"))
            put("seconds", prop("integer", "Timer duration in seconds"))
            put("value", prop("integer", "Percentage 0-100 for volume/brightness or seconds for screen_timeout"))
            put("enabled", prop("boolean", "On/off toggle for torch or system_toggle"))
        },
        required = listOf("action")
    )

    private fun deviceStatusOpenAi() = openAiTool(
        name = "device_status",
        description = "Read live phone status: overview (battery, wifi, bluetooth, DND, ringer, volume, RAM, Shizuku status, foreground app), running_apps (real background user apps & RAM usage), storage_info (free/total GB & battery health), clipboard, or installed_apps.",
        properties = buildJsonObject {
            put("action", prop("string", "overview | running_apps | storage_info | clipboard | installed_apps"))
            put("query", prop("string", "Optional filter for installed_apps"))
        },
        required = listOf("action")
    )

    private fun notificationCenterOpenAi() = openAiTool(
        name = "notification_center",
        description = "Read or act on status bar notifications: list_recent, extract_latest_otp (finds recent OTP/code & copies to clipboard), reply_notification (fast inline RemoteInput reply without opening app), dismiss, toggle_speak_incoming.",
        properties = buildJsonObject {
            put("action", prop("string", "list_recent | extract_latest_otp | reply_notification | dismiss | toggle_speak_incoming"))
            put("query", prop("string", "Sender name or app filter"))
            put("message", prop("string", "Reply message text"))
            put("limit", prop("integer", "Max notifications"))
            put("enabled", prop("boolean", "Enable/disable speaking incoming notifications"))
        },
        required = listOf("action")
    )

    private fun personalSchedulerOpenAi() = openAiTool(
        name = "personal_scheduler",
        description = "Personal scheduler & contacts: find_contact, list_calendar, create_event, set_voice_reminder (wakes Sara to speak reminder), list_reminders, cancel_reminder.",
        properties = buildJsonObject {
            put("action", prop("string", "find_contact | list_calendar | create_event | set_voice_reminder | list_reminders | cancel_reminder"))
            put("query", prop("string", "Contact name or reminder keyword"))
            put("title", prop("string", "Calendar event title"))
            put("spoken_message", prop("string", "Exact reminder message to speak aloud"))
            put("in_minutes", prop("number", "Minutes from now"))
            put("start_iso", prop("string", "Optional ISO timestamp"))
            put("end_iso", prop("string", "Optional end ISO timestamp"))
            put("reminder_id", prop("integer", "Reminder ID to cancel"))
        },
        required = listOf("action")
    )

    private fun autonomousTaskOpenAi() = openAiTool(
        name = "autonomous_task",
        description = "Manage autonomous background tasks, scheduled wakeups, recurring watch loops, and continuous chat-on-behalf loops until stopped: create_scheduled_task, create_watch_loop, create_chat_loop, list_tasks, pause_task, resume_task, stop_task, stop_all_tasks, run_task_now.",
        properties = buildJsonObject {
            put("action", prop("string", "create_scheduled_task | create_watch_loop | create_chat_loop | list_tasks | pause_task | resume_task | stop_task | stop_all_tasks | run_task_now"))
            put("task_id", prop("integer", "Task ID for pause/resume/stop/run_task_now"))
            put("query", prop("string", "Task title or sender name to match when task_id omitted"))
            put("title", prop("string", "Short human-readable task title"))
            put("goal_prompt", prop("string", "Full autonomous instructions, tone, watch condition, or stop criteria"))
            put("watch_sender", prop("string", "Person/contact name to watch & reply to in create_chat_loop"))
            put("watch_package", prop("string", "Optional app package/label filter e.g. com.whatsapp"))
            put("initial_message", prop("string", "Optional opening message to send immediately upon creating a chat loop"))
            put("in_minutes", prop("number", "Delay in minutes for create_scheduled_task"))
            put("interval_minutes", prop("number", "Repeat interval in minutes for create_watch_loop"))
            put("start_iso", prop("string", "Optional ISO timestamp for create_scheduled_task"))
            put("cooldown_seconds", prop("integer", "Minimum seconds between consecutive chat loop replies (default 2)"))
            put("max_runs", prop("integer", "Safety cap on total autonomous ticks (default 40 chat, 24 watch)"))
            put("needs_screen_ui", prop("boolean", "True if task requires opening on-screen UI rather than headless tools/notifications"))
            put("speak_on_milestone", prop("boolean", "Speak aloud on completion/alerts (default true)"))
            put("speak_every_tick", prop("boolean", "Speak on every routine tick (default false)"))
        },
        required = listOf("action")
    )

    private fun swarmDispatchOpenAi() = openAiTool(
        name = "swarm_dispatch",
        description = "Spawn multiple concurrent AI worker copies in parallel for multi-part jobs (e.g., check weather + device status + notifications + UI action simultaneously) and collect all results in one turn.",
        properties = buildJsonObject {
            put("tasks", buildJsonObject {
                put("type", "array")
                put("description", "Up to 6 subtasks executed concurrently")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("id", prop("string", "Short subtask identifier"))
                        put("role", prop("string", "fast_responder | research_worker | ui_operator | speculative_drafter"))
                        put("goal", prop("string", "Exact instruction for this worker"))
                        put("thinking_level", prop("string", "Optional LOW | MEDIUM | HIGH"))
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("goal")) })
                })
            })
        },
        required = listOf("tasks")
    )

    private fun liveInfoOpenAi() = openAiTool(
        name = "live_info",
        description = "Headless live internet lookup & math without opening a browser: weather, web_search, convert (units/currency), calculate.",
        properties = buildJsonObject {
            put("action", prop("string", "weather | web_search | convert | calculate"))
            put("query", prop("string", "Search question, math expression, or conversion like '100 usd to inr'"))
            put("location", prop("string", "City or place for weather"))
        },
        required = listOf("action")
    )

    private fun observeScreenOpenAi() = openAiTool(
        name = "observe_screen",
        description = "Inspect current Android screen: mode='tree' returns compressed numbered UI elements [1]..[N]; mode='vision' also captures an annotated Set-of-Marks screenshot when visual inspection (Reels, images, unlabeled icons) is needed.",
        properties = buildJsonObject {
            put("mode", prop("string", "tree | vision"))
            put("focus_query", prop("string", "Optional keyword to prioritize matching elements"))
        },
        required = listOf("mode")
    )

    private fun uiActOpenAi() = openAiTool(
        name = "ui_act",
        description = "Execute single or batched UI steps on screen: reply_or_send (1-turn quote-reply/type/send macro), tap_ref, tap_label, tap_xy, long_press_ref, type_text, scroll, swipe, global_action (back|home|recents|notifications), wait_settle.",
        properties = buildJsonObject {
            put("steps", buildJsonObject {
                put("type", "array")
                put("description", "Ordered steps array of objects with type, ref_id?, reply_to_ref?, reply_to_label?, input_ref?, send_ref?, label?, text?, direction?, action?, x?, y?, press_enter?")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("type", prop("string", "reply_or_send|tap_ref|tap_label|tap_xy|long_press_ref|type_text|scroll|swipe|global_action|wait_settle"))
                        put("ref_id", prop("integer", "Node [N] from observe_screen"))
                        put("reply_to_ref", prop("integer", "Message node [N] to quote-reply to before typing in reply_or_send"))
                        put("reply_to_label", prop("string", "Message text/sender snippet to quote-reply to when reply_to_ref omitted"))
                        put("input_ref", prop("integer", "Optional input EditText node [N] for reply_or_send"))
                        put("send_ref", prop("integer", "Optional Send button node [N] for reply_or_send"))
                        put("label", prop("string", "Element label text"))
                        put("text", prop("string", "Text to type"))
                        put("direction", prop("string", "up|down|left|right"))
                        put("action", prop("string", "back|home|recents|notifications"))
                        put("x", prop("integer", "Screen X coordinate"))
                        put("y", prop("integer", "Screen Y coordinate"))
                        put("press_enter", prop("boolean", "Press IME enter after typing"))
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("type")) })
                })
            })
            put("confirmed", prop("boolean", "Set true only after user explicitly confirmed high-risk or public post action"))
            put("expected_package", prop("string", "Optional package guard"))
        },
        required = listOf("steps")
    )

    private fun shizukuShellOpenAi() = openAiTool(
        name = "shizuku_shell",
        description = "Execute safe Shizuku shell command(s) (UID 2000) for single or multi-step batch tasks: system/CPU/thermal/network diagnostics (dumpsys thermalservice, dumpsys cpuinfo, getprop, wm size, settings get), deep-link intents (am start), package queries (pm list), or UI fallback input macros (input tap/swipe/text/keyevent). Sensitive mutations require confirmed=true after asking the user.",
        properties = buildJsonObject {
            put("command", prop("string", "Single safe Android shell command to run via Shizuku"))
            put("commands", buildJsonObject {
                put("type", "array")
                put("description", "Ordered batch of up to 8 shell commands to execute sequentially in one call")
                put("items", buildJsonObject { put("type", "string") })
            })
            put("stop_on_error", prop("boolean", "Stop remaining commands if any fails (default true)"))
            put("delay_ms", prop("integer", "Delay in ms between batch commands (0-2000, default 150)"))
            put("observe_screen_after", prop("boolean", "Capture refreshed Accessibility UI tree after running (default false)"))
            put("confirmed", prop("boolean", "Keep false initially; set true ONLY on a subsequent turn AFTER the user explicitly said yes/confirm"))
        },
        required = emptyList()
    )

    private fun deviceIntentGemini() = toGeminiSchema(deviceIntentOpenAi())
    private fun deviceStatusGemini() = toGeminiSchema(deviceStatusOpenAi())
    private fun notificationCenterGemini() = toGeminiSchema(notificationCenterOpenAi())
    private fun personalSchedulerGemini() = toGeminiSchema(personalSchedulerOpenAi())
    private fun autonomousTaskGemini() = toGeminiSchema(autonomousTaskOpenAi())
    private fun swarmDispatchGemini(): JsonObject = buildJsonObject {
        put("name", "swarm_dispatch")
        put("description", "Spawn multiple concurrent AI worker copies in parallel for multi-part jobs and collect all results in one turn.")
        put("parameters", buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("tasks", buildJsonObject {
                    put("type", "ARRAY")
                    put("description", "Up to 6 subtasks executed concurrently")
                    put("items", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("id", prop("STRING", "Subtask ID"))
                            put("role", prop("STRING", "fast_responder | research_worker | ui_operator | speculative_drafter"))
                            put("goal", prop("STRING", "Exact instruction for this worker"))
                            put("thinking_level", prop("STRING", "Optional LOW | MEDIUM | HIGH"))
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("goal")) })
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("tasks")) })
        })
    }
    private fun liveInfoGemini() = toGeminiSchema(liveInfoOpenAi())
    private fun observeScreenGemini() = toGeminiSchema(observeScreenOpenAi())

    private fun shizukuShellGemini(): JsonObject = buildJsonObject {
        put("name", "shizuku_shell")
        put("description", "Execute safe Shizuku shell command(s) for single or multi-step batch tasks: CPU/thermal/system diagnostics (dumpsys thermalservice, dumpsys cpuinfo, getprop, settings get), deep-link am start, pm queries, or input macros. Sensitive mutations require confirmed=true after user confirmation.")
        put("parameters", buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("command", prop("STRING", "Single safe shell command"))
                put("commands", buildJsonObject {
                    put("type", "ARRAY")
                    put("description", "Ordered batch of up to 8 shell commands")
                    put("items", buildJsonObject { put("type", "STRING") })
                })
                put("stop_on_error", prop("BOOLEAN", "Stop on first error (default true)"))
                put("delay_ms", prop("INTEGER", "Delay between commands in ms"))
                put("observe_screen_after", prop("BOOLEAN", "Observe UI tree after running"))
                put("confirmed", prop("BOOLEAN", "Set true only after explicit user confirmation"))
            })
        })
    }

    private fun uiActGemini(): JsonObject = buildJsonObject {
        put("name", "ui_act")
        put("description", "Execute single or batched UI steps on screen: reply_or_send, tap_ref, tap_label, tap_xy, long_press_ref, type_text, scroll, swipe, global_action, wait_settle.")
        put("parameters", buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("steps", buildJsonObject {
                    put("type", "ARRAY")
                    put("description", "Ordered UI steps")
                    put("items", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("type", prop("STRING", "reply_or_send|tap_ref|tap_label|tap_xy|long_press_ref|type_text|scroll|swipe|global_action|wait_settle"))
                            put("ref_id", prop("INTEGER", "Node [N]"))
                            put("reply_to_ref", prop("INTEGER", "Message node [N] to quote-reply to"))
                            put("reply_to_label", prop("STRING", "Message text/sender to quote-reply to"))
                            put("input_ref", prop("INTEGER", "Optional input box [N]"))
                            put("send_ref", prop("INTEGER", "Optional Send button [N]"))
                            put("label", prop("STRING", "Target label"))
                            put("text", prop("STRING", "Text to type"))
                            put("direction", prop("STRING", "up|down|left|right"))
                            put("action", prop("STRING", "back|home|recents|notifications"))
                            put("x", prop("INTEGER", "X coord"))
                            put("y", prop("INTEGER", "Y coord"))
                            put("press_enter", prop("BOOLEAN", "Submit enter"))
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("type")) })
                    })
                })
                put("confirmed", prop("BOOLEAN", "Explicit user confirmation for sensitive/public post"))
                put("expected_package", prop("STRING", "Package guard"))
            })
            put("required", buildJsonArray { add(JsonPrimitive("steps")) })
        })
    }

    private fun toGeminiSchema(openAiObj: JsonObject): JsonObject {
        val fn = openAiObj["function"]!!.jsonObject
        val params = fn["parameters"]!!.jsonObject
        val props = params["properties"]!!.jsonObject
        val req = params["required"] as? JsonArray ?: JsonArray(emptyList())
        return buildJsonObject {
            put("name", fn["name"]!!)
            put("description", fn["description"]!!)
            put("parameters", buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    for ((k, v) in props) {
                        val vo = v.jsonObject
                        val rawType = vo["type"]!!.toString().trim('"').uppercase()
                        put(k, buildJsonObject {
                            put("type", rawType)
                            vo["description"]?.let { put("description", it) }
                        })
                    }
                })
                put("required", req)
            })
        }
    }

    private fun openAiTool(
        name: String,
        description: String,
        properties: JsonObject,
        required: List<String>
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            })
        })
    }

    private fun prop(type: String, description: String): JsonObject = buildJsonObject {
        put("type", type)
        put("description", description)
    }
}
