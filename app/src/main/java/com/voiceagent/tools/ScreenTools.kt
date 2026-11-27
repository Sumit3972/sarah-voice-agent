package com.voiceagent.tools

import android.util.Log
import com.voiceagent.accessibility.IdleWaiter
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.accessibility.ScreenJournal
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.asArrOrNull
import com.voiceagent.config.asBoolOrNull
import com.voiceagent.config.asIntOrNull
import com.voiceagent.config.asObjOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.shizuku.ShizukuBridge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolExecutionOutput(
    val resultJson: String,
    val attachedImageBase64: String? = null,
    val attachedImageMimeType: String = "image/webp"
)

class ScreenTools {
    val screenJournal = ScreenJournal()

    @Volatile
    private var lastTreeTainted: Boolean = false

    @Volatile
    private var lastTapSignature: String = ""

    @Volatile
    private var consecutiveSameTapCount: Int = 0

    fun isScreenTainted(): Boolean = lastTreeTainted

    fun clearTurnState() {
        lastTreeTainted = false
        lastTapSignature = ""
        consecutiveSameTapCount = 0
        screenJournal.clear()
    }

    private suspend fun collapseAccidentalShadeAndCheckFocus(
        svc: SaraAccessibilityService,
        snap: com.voiceagent.accessibility.TreeSnapshot,
        focusQuery: String?
    ): Pair<Boolean, String> {
        val wantsNotifications = focusQuery?.contains("notif", true) == true ||
            focusQuery?.contains("shade", true) == true
        if (snap.packageName != "com.android.systemui" ||
            snap.nodes.size > 14 ||
            svc.isDeviceLocked() ||
            wantsNotifications ||
            !ShizukuBridge.isPermissionGranted()
        ) {
            return false to ""
        }
        val winCheck = ShizukuBridge.execShell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2", 2500L)
        if (winCheck.stdout.contains("NotificationShade")) {
            ShizukuBridge.execShell("cmd statusbar collapse", 2000L)
            delay(300L)
            return true to ""
        }
        return false to winCheck.stdout.take(200)
    }

    suspend fun observeScreen(args: JsonObject): ToolExecutionOutput {
        val svc = SaraAccessibilityService.instance ?: return ToolExecutionOutput(
            """{"ok":false,"error":"Accessibility service is not enabled. Enable Sarah Screen Control in Android Accessibility settings."}"""
        )
        val mode = args["mode"].asStrOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "tree" }
        val focusQuery = args["focus_query"].asStrOrNull()?.trim()

        if (mode == "vision") {
            var (snap, screenshot) = svc.captureAnnotatedScreenshot(focusQuery)
            val (collapsed, _) = collapseAccidentalShadeAndCheckFocus(svc, snap, focusQuery)
            if (collapsed) {
                val refreshed = svc.captureAnnotatedScreenshot(focusQuery)
                snap = refreshed.first
                screenshot = refreshed.second
            }
            if (ConfirmPolicyGate.detectScreenTaint(snap.treeText)) lastTreeTainted = true
            screenJournal.record(snap.packageName, "vision observed ${snap.nodes.size} nodes")
            val jsonOut = buildJsonObject {
                put("ok", true)
                put("mode", if (screenshot != null) "vision" else "tree_fallback")
                put("package_name", snap.packageName)
                put("node_count", snap.nodes.size)
                put("keyboard_visible", snap.keyboardVisible)
                snap.focusedRefId?.let { put("focused_ref", it) }
                put("ui_tree", snap.treeText)
                put("screen_journal", screenJournal.formatCompact())
            }.toString()
            return ToolExecutionOutput(
                resultJson = jsonOut,
                attachedImageBase64 = screenshot?.base64,
                attachedImageMimeType = screenshot?.mimeType ?: "image/webp"
            )
        } else {
            var snap = svc.observeTree(focusQuery)
            val (collapsed, fgHint) = collapseAccidentalShadeAndCheckFocus(svc, snap, focusQuery)
            if (collapsed) {
                snap = svc.observeTree(focusQuery)
            }
            if (ConfirmPolicyGate.detectScreenTaint(snap.treeText)) lastTreeTainted = true
            screenJournal.record(snap.packageName, "observed ${snap.nodes.size} nodes")
            val jsonOut = buildJsonObject {
                put("ok", true)
                put("mode", "tree")
                put("package_name", snap.packageName)
                put("node_count", snap.nodes.size)
                put("keyboard_visible", snap.keyboardVisible)
                snap.focusedRefId?.let { put("focused_ref", it) }
                if (fgHint.isNotBlank()) put("foreground_window_hint", fgHint)
                put("ui_tree", snap.treeText)
                put("screen_journal", screenJournal.formatCompact())
            }.toString()
            return ToolExecutionOutput(resultJson = jsonOut)
        }
    }

    suspend fun uiAct(args: JsonObject, userSpokenText: String): ToolExecutionOutput {
        val svc = SaraAccessibilityService.instance ?: return ToolExecutionOutput(
            """{"ok":false,"error":"Accessibility service is not enabled. Enable Sarah Screen Control in Android Accessibility settings."}"""
        )
        val confirmed = args["confirmed"].asBoolOrNull() ?: false
        val expectedPkg = args["expected_package"].asStrOrNull()?.trim()
        val observeAfter = args["observe_screen_after"].asBoolOrNull() ?: false
        val rawSteps = args["steps"].asArrOrNull()
        val steps: List<JsonObject> = if (!rawSteps.isNullOrEmpty()) {
            rawSteps.mapNotNull { el -> normalizeStepObj(el.asObjOrNull()) }
        } else {
            listOfNotNull(normalizeStepObj(args))
        }
        if (steps.isEmpty()) {
            return ToolExecutionOutput("""{"ok":false,"error":"steps array must contain at least one UI action"}""")
        }

        val initialSnap = svc.lastSnapshot ?: svc.observeTree()
        val fgBefore = initialSnap.packageName
        val treeBefore = initialSnap.treeText

        if (!expectedPkg.isNullOrBlank()) {
            val livePkg = svc.currentPackageName()
            if (livePkg != expectedPkg) {
                val fresh = svc.observeTree()
                return ToolExecutionOutput(
                    buildJsonObject {
                        put("ok", false)
                        put("fg_before", fgBefore)
                        put("fg_after", fresh.packageName)
                        put("screen_changed", false)
                        put("aborted_reason", "package_mismatch: expected=$expectedPkg actual=$livePkg")
                        put("ui_tree", fresh.treeText)
                    }.toString()
                )
            }
        }

        var completedCount = 0
        var anyNonWaitStep = false
        for ((idx, step) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val type = step["type"].asStrOrNull()?.trim()?.lowercase().orEmpty()
            val refId = step["ref_id"].asIntOrNull()
            val labelQuery = step["label"].asStrOrNull()?.trim().orEmpty()
            val textArg = step["text"].asStrOrNull().orEmpty()
            val direction = step["direction"].asStrOrNull()?.trim().orEmpty().ifBlank { "down" }
            val actionArg = step["action"].asStrOrNull()?.trim().orEmpty()
            val x = step["x"].asIntOrNull()
            val y = step["y"].asIntOrNull()
            val pressEnter = step["press_enter"].asBoolOrNull() ?: false

            val currentSnap = svc.lastSnapshot ?: initialSnap
            val preStepPkg = svc.currentPackageName()
            val preStepTree = currentSnap.treeText
            val targetNode = when {
                refId != null -> currentSnap.nodes.firstOrNull { it.refId == refId }
                    ?: if (labelQuery.isNotBlank()) findNodeByQuery(currentSnap.nodes, labelQuery) else null
                labelQuery.isNotBlank() -> findNodeByQuery(currentSnap.nodes, labelQuery)
                else -> null
            }

            val policy = ConfirmPolicyGate.evaluateUiAction(
                packageName = preStepPkg,
                actionDesc = "$type $textArg",
                targetLabel = targetNode?.label ?: labelQuery,
                userSpokenText = userSpokenText,
                confirmedByUser = confirmed,
                isPasswordField = targetNode?.isPassword == true,
                screenTainted = lastTreeTainted
            )
            if (!policy.allowed) {
                val interactivelyApproved = if (policy.requiresVoiceConfirmation) {
                    ConfirmPolicyGate.requestInteractiveConfirmation(
                        packageName = preStepPkg,
                        actionDesc = "$type $textArg".trim(),
                        targetLabel = targetNode?.label ?: labelQuery,
                        reason = policy.reason
                    )
                } else {
                    false
                }
                if (!interactivelyApproved) {
                    return ToolExecutionOutput(
                        buildJsonObject {
                            put("ok", false)
                            put("requires_confirmation", policy.requiresVoiceConfirmation)
                            put("completed_steps", completedCount)
                            put("fg_before", fgBefore)
                            put("fg_after", preStepPkg)
                            put("screen_changed", false)
                            put("error", policy.reason)
                        }.toString()
                    )
                }
            }

            if (type != "wait_settle") anyNonWaitStep = true
            var resolvedTapNode = targetNode
            val isKeyguardOrPinStep = (preStepPkg == "com.android.systemui" && svc.isDeviceLocked()) ||
                targetNode?.isPassword == true
            val allowFallbackTap = !isKeyguardOrPinStep

            val stepOk = when (type) {
                "tap_ref", "long_press_ref" -> {
                    val node = targetNode ?: return ToolExecutionOutput(
                        """{"ok":false,"error":"Ref #$refId ($labelQuery) not found on screen; call observe_screen again","completed_steps":$completedCount}"""
                    )
                    svc.tapOrLongPressRef(node, longPress = (type == "long_press_ref"), allowFallbackTap = allowFallbackTap)
                }
                "tap_label" -> {
                    val liveSnap = svc.observeTree(labelQuery)
                    val matched = findNodeByQuery(liveSnap.nodes, labelQuery)
                        ?: return ToolExecutionOutput(
                            """{"ok":false,"error":"Label '$labelQuery' not visible on screen","completed_steps":$completedCount}"""
                        )
                    resolvedTapNode = matched
                    svc.tapOrLongPressRef(matched, longPress = false, allowFallbackTap = allowFallbackTap)
                }
                "tap_xy" -> {
                    if (x == null || y == null) false else svc.tapXy(x, y)
                }
                "type_text" -> {
                    val (typed, reason) = svc.typeIntoRef(targetNode, textArg, pressEnter)
                    if (!typed) {
                        return ToolExecutionOutput(
                            """{"ok":false,"error":"type_text failed: $reason","completed_steps":$completedCount}"""
                        )
                    }
                    true
                }
                "scroll" -> svc.scrollDirection(direction, targetNode)
                "swipe" -> {
                    val x2 = step["x2"].asIntOrNull()
                    val y2 = step["y2"].asIntOrNull()
                    if (x != null && y != null && x2 != null && y2 != null) {
                        svc.swipe(x, y, x2, y2)
                    } else {
                        svc.scrollDirection(direction, null)
                    }
                }
                "global_action" -> {
                    val mapped = when (actionArg.ifBlank { direction }.lowercase()) {
                        "notification_shade", "shade", "status_bar", "notification_panel" -> "notifications"
                        else -> actionArg.ifBlank { direction }
                    }
                    svc.performGlobalCommand(mapped)
                }
                "wait_settle" -> {
                    IdleWaiter.waitForSettle()
                    true
                }
                else -> false
            }

            if (!stepOk) {
                val refreshed = svc.observeTree()
                return ToolExecutionOutput(
                    buildJsonObject {
                        put("ok", false)
                        put("failed_step_index", idx)
                        put("failed_type", type)
                        put("completed_steps", completedCount)
                        put("fg_before", fgBefore)
                        put("fg_after", refreshed.packageName)
                        put("screen_changed", refreshed.packageName != fgBefore || refreshed.treeText != treeBefore)
                        put("ui_tree", refreshed.treeText)
                    }.toString()
                )
            }

            // Closed-loop post-step verification & automatic Shizuku kernel input recovery if UI didn't change
            // NEVER double-tap on Keyguard PIN / masked password screens
            if (allowFallbackTap && type in setOf("tap_ref", "tap_label", "tap_xy", "scroll", "swipe")) {
                delay(240L)
                val postStepSnap = svc.observeTree()
                if (postStepSnap.packageName == preStepPkg &&
                    postStepSnap.treeText == preStepTree &&
                    ShizukuBridge.isPermissionGranted()
                ) {
                    val recovered = when (type) {
                        "tap_ref", "tap_label" -> resolvedTapNode?.let { ShizukuBridge.inputTap(it.cx, it.cy).ok } ?: false
                        "tap_xy" -> if (x != null && y != null) ShizukuBridge.inputTap(x, y).ok else false
                        "scroll" -> {
                            val c = svc.scrollCoordsForDirection(direction)
                            ShizukuBridge.inputSwipe(c[0], c[1], c[2], c[3], 320L).ok
                        }
                        "swipe" -> {
                            val x2 = step["x2"].asIntOrNull()
                            val y2 = step["y2"].asIntOrNull()
                            if (x != null && y != null && x2 != null && y2 != null) {
                                ShizukuBridge.inputSwipe(x, y, x2, y2, 320L).ok
                            } else {
                                val c = svc.scrollCoordsForDirection(direction)
                                ShizukuBridge.inputSwipe(c[0], c[1], c[2], c[3], 320L).ok
                            }
                        }
                        else -> false
                    }
                    if (recovered) {
                        Log.i(PipelineConfig.LOG_TAG, "[ScreenTools] Shizuku fallback input triggered for $type (screen was unchanged)")
                        delay(260L)
                        svc.observeTree()
                    }
                }
            } else if (idx < steps.size - 1) {
                delay(240L)
                svc.observeTree()
            }

            completedCount++
            if (type in setOf("tap_ref", "tap_label", "tap_xy")) {
                val tapSig = "$preStepPkg:${resolvedTapNode?.cx ?: x},${resolvedTapNode?.cy ?: y}:${resolvedTapNode?.label ?: labelQuery}"
                if (tapSig == lastTapSignature) {
                    consecutiveSameTapCount++
                } else {
                    lastTapSignature = tapSig
                    consecutiveSameTapCount = 1
                }
            } else {
                lastTapSignature = ""
                consecutiveSameTapCount = 0
            }
            screenJournal.record(svc.currentPackageName(), "did $type(${resolvedTapNode?.label?.take(24) ?: labelQuery.ifBlank { actionArg }})")
        }

        IdleWaiter.waitForSettle(quietWindowMs = 240L, timeoutMs = 1500L)
        val shouldAttachVision = observeAfter || consecutiveSameTapCount >= 2
        var attachedScreenshot: com.voiceagent.accessibility.EncodedSoMScreenshot? = null
        var afterSnap = if (shouldAttachVision) {
            val (snap, shot) = svc.captureAnnotatedScreenshot()
            attachedScreenshot = shot
            snap
        } else {
            svc.observeTree()
        }
        var screenChanged = afterSnap.packageName != fgBefore ||
            stripFocusHeader(afterSnap.treeText) != stripFocusHeader(treeBefore)
        if (!screenChanged && anyNonWaitStep && attachedScreenshot == null) {
            val (snap, shot) = svc.captureAnnotatedScreenshot()
            afterSnap = snap
            attachedScreenshot = shot
            screenChanged = afterSnap.packageName != fgBefore ||
                stripFocusHeader(afterSnap.treeText) != stripFocusHeader(treeBefore)
        }
        Log.i(
            PipelineConfig.LOG_TAG,
            "[ScreenTools] ui_act completed $completedCount/${steps.size} steps on ${afterSnap.packageName} (screenChanged=$screenChanged, vision=${attachedScreenshot != null})"
        )

        return ToolExecutionOutput(
            resultJson = buildJsonObject {
                put("ok", true)
                put("completed_steps", completedCount)
                put("fg_before", fgBefore)
                put("fg_after", afterSnap.packageName)
                put("package_name", afterSnap.packageName)
                put("keyboard_visible", afterSnap.keyboardVisible)
                afterSnap.focusedRefId?.let { put("focused_ref", it) }
                put("screen_changed", screenChanged)
                if (consecutiveSameTapCount >= 3) {
                    put(
                        "loop_warning",
                        "You tapped the same control $consecutiveSameTapCount times in a row. Stop repeating that tap; inspect the annotated screenshot or look for 'Send', 'OK', 'Apply', 'Done', 'Cancel', or use tap_label."
                    )
                }
                if (!screenChanged && anyNonWaitStep) {
                    put(
                        "verification_warning",
                        "Screen state did not change after action. Do NOT claim 'Done' yet; inspect the attached screenshot, use tap_label, scroll, or device_intent."
                    )
                }
                put("ui_tree", afterSnap.treeText)
                put("screen_journal", screenJournal.formatCompact())
            }.toString(),
            attachedImageBase64 = attachedScreenshot?.base64,
            attachedImageMimeType = attachedScreenshot?.mimeType ?: "image/webp"
        )
    }

    private fun stripFocusHeader(treeText: String): String =
        treeText.replace(Regex(" focused=\\[\\d+]"), "")

    private fun findNodeByQuery(nodes: List<com.voiceagent.accessibility.UiNodeRef>, rawQuery: String): com.voiceagent.accessibility.UiNodeRef? {
        val q = rawQuery.trim().lowercase()
        if (q.isBlank()) return null

        val synonymGroups = listOf(
            setOf("inbox", "dm", "dms", "messages", "messenger", "direct", "chat"),
            setOf("search", "find", "explore", "lookup"),
            setOf("back", "close", "dismiss", "cancel", "navigate"),
            setOf("send", "submit", "post", "reply", "done"),
            setOf("menu", "more", "options", "drawer"),
            setOf("settings", "preferences", "config"),
            setOf("profile", "account", "me"),
            setOf("home", "feed"),
            setOf("reels", "shorts", "clips", "video")
        )

        val rawTokens = q.split(Regex("[\\s_]+")).filter { it.length >= 2 }
        val expandedTerms = linkedSetOf(q)
        expandedTerms.addAll(rawTokens)
        for (group in synonymGroups) {
            if (rawTokens.any { it in group } || q in group) {
                expandedTerms.addAll(group)
            }
        }

        // Prefer exact phrase or full token match over partial substring matches
        return nodes.maxByOrNull { n ->
            val lbl = n.label.lowercase()
            val vidNorm = n.viewId.replace('_', ' ')
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .lowercase()
            when {
                lbl == q -> 100
                lbl.contains(q) -> 60
                expandedTerms.any { it in lbl.split(Regex("\\W+")) || it in vidNorm.split(Regex("\\W+")) } -> 40
                expandedTerms.any { lbl.contains(it) || vidNorm.contains(it) } -> 20
                else -> 0
            }
        }?.takeIf { n ->
            val lbl = n.label.lowercase()
            val vid = n.viewId.lowercase()
            expandedTerms.any { lbl.contains(it) || vid.contains(it) }
        }
    }

    private fun normalizeStepObj(raw: JsonObject?): JsonObject? {
        if (raw == null) return null
        val explicitType = raw["type"].asStrOrNull()?.trim().orEmpty()
        if (explicitType.isNotBlank()) return raw

        val ga = raw["global_action"].asStrOrNull()?.trim()
        if (!ga.isNullOrBlank()) {
            val normGa = when (ga.lowercase()) {
                "notification_shade", "shade", "status_bar", "notification_panel" -> "notifications"
                else -> ga
            }
            return buildJsonObject {
                put("type", "global_action")
                put("action", normGa)
            }
        }

        val nestedSwipe = raw["swipe"].asObjOrNull()
        if (nestedSwipe != null) {
            return buildJsonObject {
                put("type", "swipe")
                (nestedSwipe["x"].asIntOrNull() ?: nestedSwipe["start_x"].asIntOrNull())?.let { put("x", it) }
                (nestedSwipe["y"].asIntOrNull() ?: nestedSwipe["start_y"].asIntOrNull())?.let { put("y", it) }
                (nestedSwipe["x2"].asIntOrNull() ?: nestedSwipe["end_x"].asIntOrNull())?.let { put("x2", it) }
                (nestedSwipe["y2"].asIntOrNull() ?: nestedSwipe["end_y"].asIntOrNull())?.let { put("y2", it) }
                nestedSwipe["direction"].asStrOrNull()?.let { put("direction", it) }
            }
        }
        return null
    }
}
