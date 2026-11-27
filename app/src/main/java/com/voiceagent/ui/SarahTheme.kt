package com.voiceagent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voiceagent.memory.ChatMessageEntity
import com.voiceagent.orchestrator.ConversationPhase
import com.voiceagent.tts.EmotionTagNormalizer

@Immutable
data class SarahPalette(
    val isDark: Boolean,
    val paper: Color,
    val stone: Color,
    val card: Color,
    val hairline: Color,
    val ink: Color,
    val muted: Color,
    val sienna: Color,
    val siennaSoft: Color,
    val sage: Color,
    val sageSoft: Color,
    val danger: Color,
    val dangerSoft: Color
)

val WarmAlabasterLight = SarahPalette(
    isDark = false,
    paper = Color(0xFFFAF9F6),
    stone = Color(0xFFF2F0EA),
    card = Color(0xFFFFFFFF),
    hairline = Color(0xFFE5E1D8),
    ink = Color(0xFF1A1918),
    muted = Color(0xFF6F6D66),
    sienna = Color(0xFFC96442),
    siennaSoft = Color(0xFFF5EBE6),
    sage = Color(0xFF2E7D5B),
    sageSoft = Color(0xFFE9F4EE),
    danger = Color(0xFFB3261E),
    dangerSoft = Color(0xFFFCECEC)
)

val ObsidianDark = SarahPalette(
    isDark = true,
    paper = Color(0xFF141413),
    stone = Color(0xFF1F1E1C),
    card = Color(0xFF262523),
    hairline = Color(0xFF34322E),
    ink = Color(0xFFF5F4EF),
    muted = Color(0xFF9B9990),
    sienna = Color(0xFFD97757),
    siennaSoft = Color(0xFF33231D),
    sage = Color(0xFF56B881),
    sageSoft = Color(0xFF1C2D24),
    danger = Color(0xFFF28B82),
    dangerSoft = Color(0xFF3A201E)
)

val EditorialSerif = FontFamily.Serif
val CleanSans = FontFamily.SansSerif

data class PermissionSummary(
    val hasMic: Boolean = true,
    val hasNotifications: Boolean = true,
    val hasOverlay: Boolean = true,
    val ignoringBattery: Boolean = true,
    val hasAccessibility: Boolean = false,
    val hasNotificationListener: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuGranted: Boolean = false
) {
    val activeCoreCount: Int
        get() = listOf(
            hasMic,
            hasNotifications,
            hasAccessibility,
            hasNotificationListener,
            ignoringBattery,
            shizukuGranted
        ).count { it }
}

data class PendingMediaUi(
    val label: String,
    val base64: String,
    val mimeType: String = "image/jpeg"
)

data class CredentialsUiState(
    val deepgramKey: String = "",
    val deepgramConfigured: Boolean = false,
    val mercuryKey: String = "",
    val mercuryConfigured: Boolean = false,
    val fishOverrideKey: String = "",
    val fishBuiltInActive: Boolean = true,
    val geminiEmail: String = "",
    val geminiRefreshToken: String = "",
    val geminiQuotaSummary: String = "Not synced",
    val lockPinConfigured: Boolean = false,
    val accounts: List<com.voiceagent.llm.AccountSummary> = emptyList(),
    val oauthInProgress: Boolean = false,
    val oauthStatusMessage: String = ""
)

fun friendlyPhaseLabel(phase: ConversationPhase, micMuted: Boolean, callPaused: Boolean = false): String {
    if (callPaused) return "Paused · On call"
    if (micMuted) return "Mic paused"
    return when (phase) {
        ConversationPhase.STOPPED -> "Tap to start"
        ConversationPhase.IDLE_STANDBY -> "Ready · Say 'Sarah'"
        ConversationPhase.WAKE_CHECK -> "Hearing Sarah..."
        ConversationPhase.LISTENING -> "Listening..."
        ConversationPhase.SPEAKING -> "Listening..."
        ConversationPhase.PAUSE_WAIT -> "Processing..."
        ConversationPhase.THINKING -> "Working on it..."
        ConversationPhase.SPEAKING_REPLY -> "Speaking"
    }
}

/**
 * Pure Jetpack Compose vector rendering of Sarah's 3-ribbon acoustic "S" brand mark.
 */
@Composable
fun SarahBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    outerColor: Color = Color(0xFF1A1918),
    centerColor: Color = Color(0xFFC96442)
) {
    Canvas(modifier = modifier.size(size)) {
        val sx = this.size.width / 108f
        val sy = this.size.height / 108f

        val left = Path().apply {
            moveTo(53.5f * sx, 27.0f * sy)
            cubicTo(39.5f * sx, 31.5f * sy, 34.5f * sx, 41.5f * sy, 39.0f * sx, 51.0f * sy)
            cubicTo(42.8f * sx, 59.0f * sy, 49.0f * sx, 64.8f * sy, 46.2f * sx, 74.5f * sy)
            cubicTo(45.2f * sx, 77.8f * sy, 43.2f * sx, 80.0f * sy, 41.2f * sx, 81.4f * sy)
            cubicTo(46.8f * sx, 78.5f * sy, 51.0f * sx, 73.5f * sy, 51.2f * sx, 66.2f * sy)
            cubicTo(51.4f * sx, 57.5f * sy, 44.5f * sx, 51.8f * sy, 43.6f * sx, 43.8f * sy)
            cubicTo(42.8f * sx, 36.8f * sy, 46.8f * sx, 31.2f * sy, 53.5f * sx, 27.0f * sy)
            close()
        }
        drawPath(left, color = outerColor)

        val mid = Path().apply {
            moveTo(54.8f * sx, 32.5f * sy)
            cubicTo(46.8f * sx, 37.8f * sy, 45.8f * sx, 45.8f * sy, 50.5f * sx, 53.2f * sy)
            cubicTo(54.5f * sx, 59.5f * sy, 57.5f * sx, 64.2f * sy, 54.0f * sx, 71.8f * sy)
            cubicTo(52.8f * sx, 74.5f * sy, 50.8f * sx, 76.8f * sy, 48.8f * sx, 78.2f * sy)
            cubicTo(55.2f * sx, 75.2f * sy, 60.5f * sx, 68.8f * sy, 59.5f * sx, 61.0f * sy)
            cubicTo(58.5f * sx, 53.5f * sy, 52.8f * sx, 48.5f * sy, 52.5f * sx, 41.8f * sy)
            cubicTo(52.3f * sx, 37.8f * sy, 53.2f * sx, 34.8f * sy, 54.8f * sx, 32.5f * sy)
            close()
        }
        drawPath(mid, color = centerColor)

        val right = Path().apply {
            moveTo(62.8f * sx, 26.8f * sy)
            cubicTo(54.8f * sx, 32.8f * sy, 54.0f * sx, 41.5f * sy, 59.2f * sx, 49.5f * sy)
            cubicTo(63.8f * sx, 56.5f * sy, 67.2f * sx, 62.5f * sy, 63.0f * sx, 71.2f * sy)
            cubicTo(60.5f * sx, 76.5f * sy, 55.2f * sx, 79.8f * sy, 50.2f * sx, 81.2f * sy)
            cubicTo(60.5f * sx, 80.5f * sy, 69.5f * sx, 73.2f * sy, 71.0f * sx, 62.5f * sy)
            cubicTo(72.4f * sx, 52.8f * sy, 64.2f * sx, 46.2f * sy, 62.0f * sx, 39.2f * sy)
            cubicTo(60.5f * sx, 34.5f * sy, 61.2f * sx, 30.2f * sy, 62.8f * sx, 26.8f * sy)
            close()
        }
        drawPath(right, color = outerColor)
    }
}

internal fun isRawToolOrTraceContent(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.startsWith("CALL ") || trimmed.startsWith("RESULT ") || trimmed.startsWith("[")) {
        if (trimmed.startsWith("CALL ") || trimmed.startsWith("RESULT ")) return true
    }
    if (trimmed.contains("<untrusted_notification_data>", ignoreCase = true) ||
        trimmed.contains("<untrusted_screen_data>", ignoreCase = true)
    ) {
        return true
    }
    if ((trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("w\":\"")) &&
        (trimmed.contains("\"tool_calls\"") ||
            trimmed.contains("\"can_inline_reply\"") ||
            trimmed.contains("\"fg_after\"") ||
            trimmed.contains("\"status\":\"ok\"") ||
            trimmed.contains("\"package_name\"") ||
            trimmed.contains("\"ref_id\""))
    ) {
        return true
    }
    return false
}

internal fun prepareVisibleChatMessages(messages: List<ChatMessageEntity>): List<ChatMessageEntity> {
    val ordered = messages.asReversed()
    val cleaned = mutableListOf<ChatMessageEntity>()
    for (msg in ordered) {
        if (msg.role != "user" && msg.role != "assistant") continue
        if (isRawToolOrTraceContent(msg.content)) continue
        val display = if (msg.role == "assistant") {
            EmotionTagNormalizer.stripTagsForDisplay(msg.content)
        } else {
            msg.content.trim()
        }
        if (display.isBlank()) continue
        val prev = cleaned.lastOrNull()
        if (msg.role == "user" && prev?.role == "user" && prev.content.equals(display, ignoreCase = true)) {
            continue
        }
        cleaned += msg.copy(content = display)
    }
    return cleaned
}

internal fun shouldShowLiveTurnOverlay(
    phase: ConversationPhase,
    liveTranscript: String,
    routingReason: String
): Boolean {
    val inProgress = phase == ConversationPhase.SPEAKING ||
        phase == ConversationPhase.PAUSE_WAIT ||
        phase == ConversationPhase.THINKING ||
        phase == ConversationPhase.SPEAKING_REPLY
    if (!inProgress) return false
    if (routingReason == "checking relevance...") return false
    return liveTranscript.isNotBlank()
}

internal fun shouldShowLiveUserBubble(
    visibleMessages: List<ChatMessageEntity>,
    liveTranscript: String
): Boolean {
    val cleanLive = liveTranscript.trim()
    if (cleanLive.isEmpty()) return false
    val lastMsg = visibleMessages.lastOrNull() ?: return true
    if (lastMsg.role == "user" && lastMsg.content.trim().equals(cleanLive, ignoreCase = true)) {
        return false
    }
    // Check if DB already saved both user + reply? Safe guard: look back past an immediate assistant bubble too only if needed
    return true
}
