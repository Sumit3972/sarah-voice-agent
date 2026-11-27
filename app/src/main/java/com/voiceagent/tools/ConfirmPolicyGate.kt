package com.voiceagent.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

data class PendingSafetyConfirmation(
    val id: String,
    val packageName: String,
    val actionDesc: String,
    val targetLabel: String,
    val reason: String
)

data class PolicyDecision(
    val allowed: Boolean,
    val requiresVoiceConfirmation: Boolean = false,
    val reason: String = ""
)

/**
 * Smart daily-user safety policy:
 * - Blocks password fields permanently.
 * - Flags prompt-injection attempts originating inside screen trees or notifications.
 * - Auto-executes phone calls & WhatsApp messages when explicitly spoken by user and run is untainted.
 * - Gates public social comments/posts, deletions, payments, and security toggles behind voice confirmation.
 */
object ConfirmPolicyGate {
    private val INJECTION_MARKERS = listOf(
        "ignore previous instructions",
        "ignore all previous",
        "system prompt",
        "send all contacts",
        "transfer money",
        "upi pin",
        "override safety"
    )

    private val HIGH_RISK_LABELS = listOf(
        "pay now", "confirm payment", "send money", "upi", "place order",
        "delete account", "remove account", "erase", "factory reset",
        "post comment", "add comment", "publish", "submit review"
    )

    private val SOCIAL_COMMENT_PACKAGES = setOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.facebook.katana",
        "com.zhiliaoapp.musically",
        "com.twitter.android",
        "com.reddit.frontpage"
    )

    fun detectScreenTaint(untrustedText: String): Boolean {
        val lower = untrustedText.lowercase(Locale.ROOT)
        return INJECTION_MARKERS.any { lower.contains(it) }
    }

    fun evaluateUiAction(
        packageName: String,
        actionDesc: String,
        targetLabel: String,
        userSpokenText: String,
        confirmedByUser: Boolean,
        isPasswordField: Boolean,
        screenTainted: Boolean
    ): PolicyDecision {
        if (isPasswordField) {
            return PolicyDecision(
                allowed = false,
                reason = "Blocked: cannot interact with password or PIN input fields."
            )
        }
        if (confirmedByUser) {
            return PolicyDecision(allowed = true)
        }

        val combined = "$actionDesc $targetLabel".lowercase(Locale.ROOT)
        val userLower = userSpokenText.lowercase(Locale.ROOT)

        if (screenTainted) {
            return PolicyDecision(
                allowed = false,
                requiresVoiceConfirmation = true,
                reason = "Confirmation required: suspicious instruction detected inside untrusted screen content."
            )
        }

        if (HIGH_RISK_LABELS.any { combined.contains(it) }) {
            return PolicyDecision(
                allowed = false,
                requiresVoiceConfirmation = true,
                reason = "Ask user voice confirmation before completing '$targetLabel'."
            )
        }

        // Public social comments/posting require confirmation unless user already said "confirm/yes post"
        val isSocialCommentPost = packageName in SOCIAL_COMMENT_PACKAGES &&
            (combined.contains("post") || combined.contains("send") || combined.contains("comment"))
        val explicitlyConfirmedSocial = userLower.contains("yes post") || userLower.contains("confirm post")
        if (isSocialCommentPost && !explicitlyConfirmedSocial) {
            return PolicyDecision(
                allowed = false,
                requiresVoiceConfirmation = true,
                reason = "Ask the user to confirm before posting a public comment on $packageName."
            )
        }

        return PolicyDecision(allowed = true)
    }

    private val _pendingConfirmation = MutableStateFlow<PendingSafetyConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingSafetyConfirmation?> = _pendingConfirmation.asStateFlow()

    @Volatile
    private var activeDeferred: CompletableDeferred<Boolean>? = null

    suspend fun requestInteractiveConfirmation(
        packageName: String,
        actionDesc: String,
        targetLabel: String,
        reason: String,
        timeoutMs: Long = 22_000L
    ): Boolean {
        activeDeferred?.cancel()
        val deferred = CompletableDeferred<Boolean>()
        activeDeferred = deferred
        _pendingConfirmation.value = PendingSafetyConfirmation(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            actionDesc = actionDesc,
            targetLabel = targetLabel.ifBlank { actionDesc },
            reason = reason
        )
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            if (activeDeferred === deferred) {
                activeDeferred = null
                _pendingConfirmation.value = null
            }
        }
    }

    fun resolveConfirmation(approved: Boolean) {
        val d = activeDeferred
        activeDeferred = null
        _pendingConfirmation.value = null
        d?.complete(approved)
    }

    fun clearPendingConfirmation() {
        resolveConfirmation(false)
    }

    private val HARD_BLOCKED_SHELL_REGEXES = listOf(
        Regex("\\brm\\s+-[a-z]*[rf]"),
        Regex("\\bmkfs\\b"),
        Regex("\\bdd\\s+if="),
        Regex("\\breboot\\b"),
        Regex("\\bshutdown\\b"),
        Regex("\\brecovery\\b"),
        Regex("\\bfastboot\\b"),
        Regex("\\bwipe\\b"),
        Regex("\\bformat\\s+"),
        Regex(">\\s*/dev/"),
        Regex("/system/|/vendor/"),
        Regex("\\b(su|magisk)\\b"),
        Regex("\\blocksettings\\b"),
        Regex("\\bam\\s+kill-all\\b"),
        Regex("sqlite3.*(accounts|locksettings|keystore)")
    )

    private val SELF_PROTECT_SHELL_REGEX = Regex(
        "(com\\.voiceagent|moe\\.shizuku\\.privileged\\.api).*(force-stop|\\bkill\\b|pm\\s+(clear|uninstall|disable|hide|revoke)|\\brm\\b)|" +
            "(force-stop|\\bkill\\b|pm\\s+(clear|uninstall|disable|hide|revoke)|\\brm\\b).*(com\\.voiceagent|moe\\.shizuku\\.privileged\\.api)"
    )

    private val CONFIRM_REQUIRED_SHELL_REGEXES = listOf(
        Regex("\\bpm\\s+(uninstall|clear|disable|disable-user|hide|revoke|grant)\\b"),
        Regex("\\bappops\\s+set\\b"),
        Regex("\\b(rm|unlink|rmdir)\\b\\s+"),
        Regex("\\bmv\\s+.*\\s+/dev/null"),
        Regex("\\bsettings\\s+(put|delete)\\s+(secure|global)\\b"),
        Regex("\\bcmd\\s+netpolicy\\b")
    )

    private val AFFIRMATIVE_CONFIRM_REGEX = Regex(
        "^(yes|yeah|yep|yup|sure|ok|okay|confirm|confirmed|go ahead|proceed|do it|run it|execute it|yes confirm|yes do it|yes please)\\b",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    private var pendingShellCommand: String = ""

    @Volatile
    private var pendingShellAtMs: Long = 0L

    fun clearPendingShellConfirmation() {
        pendingShellCommand = ""
        pendingShellAtMs = 0L
    }

    fun evaluateShellCommand(
        command: String,
        userSpokenText: String,
        confirmedByUser: Boolean,
        screenTainted: Boolean
    ): PolicyDecision {
        val clean = command.trim()
        if (clean.isBlank()) {
            return PolicyDecision(allowed = false, reason = "Blocked: empty shell command.")
        }
        val lower = clean.lowercase(Locale.ROOT)

        if (SELF_PROTECT_SHELL_REGEX.containsMatchIn(lower)) {
            return PolicyDecision(
                allowed = false,
                requiresVoiceConfirmation = false,
                reason = "Hard-blocked: cannot stop, modify, or uninstall Sarah (com.voiceagent) or Shizuku."
            )
        }
        if (HARD_BLOCKED_SHELL_REGEXES.any { it.containsMatchIn(lower) }) {
            return PolicyDecision(
                allowed = false,
                requiresVoiceConfirmation = false,
                reason = "Hard-blocked: destructive system, partition, reboot, or lockscreen command is not allowed."
            )
        }
        if (INJECTION_MARKERS.any { lower.contains(it) }) {
            return PolicyDecision(
                allowed = false,
                requiresVoiceConfirmation = false,
                reason = "Hard-blocked: suspicious command content."
            )
        }

        val now = System.currentTimeMillis()
        val hasValidPending = pendingShellCommand.isNotBlank() &&
            (now - pendingShellAtMs) <= 60_000L &&
            (pendingShellCommand.equals(clean, ignoreCase = true) ||
                clean.lowercase(Locale.ROOT).contains(pendingShellCommand.lowercase(Locale.ROOT)))
        val userExplicitlyConfirmed = hasValidPending &&
            confirmedByUser &&
            AFFIRMATIVE_CONFIRM_REGEX.containsMatchIn(userSpokenText.trim())

        if (screenTainted && !userExplicitlyConfirmed) {
            pendingShellCommand = clean
            pendingShellAtMs = now
            return PolicyDecision(
                allowed = false,
                requiresVoiceConfirmation = true,
                reason = "Voice confirmation required: suspicious text detected on screen. Ask the user aloud before running '$clean'."
            )
        }

        if (CONFIRM_REQUIRED_SHELL_REGEXES.any { it.containsMatchIn(lower) }) {
            if (!userExplicitlyConfirmed) {
                pendingShellCommand = clean
                pendingShellAtMs = now
                return PolicyDecision(
                    allowed = false,
                    requiresVoiceConfirmation = true,
                    reason = "Confirmation required before executing '$clean'. Ask the user aloud first; only retry after the user replies 'yes' or 'confirm'."
                )
            }
            clearPendingShellConfirmation()
        }

        return PolicyDecision(allowed = true)
    }
}
