package com.voiceagent.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.voiceagent.config.asBoolOrNull
import com.voiceagent.config.asIntOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.memory.ContextWindowManager
import com.voiceagent.notifications.SaraNotificationListenerService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NotificationCenterTool(
    private val appContext: Context
) {
    private val otpKeywordRegex = Regex(
        "\\b(otp|code|verification|verify|passcode|pin|login|auth|security|one[-\\s]?time|bank|upi)\\b",
        RegexOption.IGNORE_CASE
    )
    private val otpDigitsRegex = Regex("\\b([0-9]{4,8})\\b")

    fun execute(args: JsonObject): String {
        val action = args["action"].asStrOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "list_recent" }
        val query = args["query"].asStrOrNull()?.trim().orEmpty()
        val message = args["message"].asStrOrNull()?.trim().orEmpty()
        val limit = (args["limit"].asIntOrNull() ?: 8).coerceIn(1, 20)
        val enabled = args["enabled"].asBoolOrNull()

        if (!SaraNotificationListenerService.isConnected(appContext) && action != "toggle_speak_incoming") {
            return buildJsonObject {
                put("ok", false)
                put("error", "Notification access is not enabled yet for Sarah. Ask the user or grant BIND_NOTIFICATION_LISTENER_SERVICE.")
            }.toString()
        }

        return when (action) {
            "list_recent", "list", "read", "notifications", "recent", "get", "all" -> {
                val items = SaraNotificationListenerService.listRecent(limit).let { all ->
                    if (query.isBlank()) all else all.filter {
                        it.appLabel.contains(query, true) ||
                            it.title.contains(query, true) ||
                            it.text.contains(query, true)
                    }
                }
                buildJsonObject {
                    put("ok", true)
                    put("count", items.size)
                    put("notifications", buildJsonArray {
                        items.forEach { n ->
                            add(buildJsonObject {
                                put("key", n.key)
                                put("app", n.appLabel)
                                put("sender", "<untrusted_notification_data>${n.title}</untrusted_notification_data>")
                                put("preview", "<untrusted_notification_data>${n.text.take(240)}</untrusted_notification_data>")
                                put("can_inline_reply", n.hasReplyAction)
                                put("ago", ContextWindowManager.formatRelativeTime(n.postTime))
                            })
                        }
                    })
                }.toString()
            }

            "extract_latest_otp" -> extractLatestOtp(query)

            "reply_notification" -> {
                if (message.isBlank()) {
                    return """{"ok":false,"error":"Reply message text is required"}"""
                }
                val svc = SaraNotificationListenerService.instance
                    ?: return """{"ok":false,"error":"Notification listener service not active"}"""
                val (ok, msg) = svc.sendInlineReply(query, message)
                buildJsonObject {
                    put("ok", ok)
                    if (ok) put("message", msg) else put("error", msg)
                }.toString()
            }

            "dismiss" -> {
                val svc = SaraNotificationListenerService.instance
                    ?: return """{"ok":false,"error":"Notification listener service not active"}"""
                val (ok, msg) = svc.dismissMatching(query)
                buildJsonObject {
                    put("ok", ok)
                    if (ok) put("message", msg) else put("error", msg)
                }.toString()
            }

            "toggle_speak_incoming" -> {
                val next = enabled ?: !SaraNotificationListenerService.speakIncomingEnabled
                SaraNotificationListenerService.speakIncomingEnabled = next
                buildJsonObject {
                    put("ok", true)
                    put("speak_incoming_enabled", next)
                    put("message", if (next) "Enabled reading incoming notifications aloud" else "Muted automatic notification reading")
                }.toString()
            }

            else -> """{"ok":false,"error":"Unknown notification_center action='$action'"}"""
        }
    }

    private fun extractLatestOtp(query: String): String {
        val candidates = SaraNotificationListenerService.listRecent(15).let { all ->
            if (query.isBlank()) all else all.filter {
                it.appLabel.contains(query, true) ||
                    it.title.contains(query, true) ||
                    it.text.contains(query, true)
            }
        }

        val prioritized = candidates.sortedByDescending { n ->
            val combined = "${n.title} ${n.text}"
            val hasKeyword = otpKeywordRegex.containsMatchIn(combined)
            val hasCode = otpDigitsRegex.containsMatchIn(combined)
            when {
                hasKeyword && hasCode -> 2
                hasCode -> 1
                else -> 0
            }
        }

        for (n in prioritized) {
            val combined = "${n.title} ${n.text}"
            val matches = otpDigitsRegex.findAll(combined).map { it.groupValues[1] }.toList()
            val code = matches.firstOrNull { candidate ->
                otpKeywordRegex.containsMatchIn(combined) || candidate.length in 4..6
            }
            if (!code.isNullOrBlank()) {
                val copied = runCatching {
                    val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("sara_otp", code))
                    true
                }.getOrDefault(false)

                return buildJsonObject {
                    put("ok", true)
                    put("otp_code", code)
                    put("copied_to_clipboard", copied)
                    put("app", n.appLabel)
                    put("sender", "<untrusted_notification_data>${n.title}</untrusted_notification_data>")
                    put("preview", "<untrusted_notification_data>${n.text.take(200)}</untrusted_notification_data>")
                    put("ago", ContextWindowManager.formatRelativeTime(n.postTime))
                }.toString()
            }
        }

        return buildJsonObject {
            put("ok", false)
            put("error", "No recent OTP or verification code found in active notifications.")
        }.toString()
    }
}
