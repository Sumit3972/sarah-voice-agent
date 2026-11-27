package com.voiceagent.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceagent.config.WakeCueMode
import com.voiceagent.config.WakeSensitivity
import com.voiceagent.tts.EmotionTagNormalizer
import com.voiceagent.memory.AutonomousTaskEntity
import com.voiceagent.memory.AutonomousTaskLogEntity
import com.voiceagent.memory.ChatMessageEntity
import com.voiceagent.memory.ChatSessionEntity
import com.voiceagent.memory.MemoryFactEntity
import com.voiceagent.memory.ScheduledReminderEntity
import com.voiceagent.orchestrator.ConversationPhase
import com.voiceagent.orchestrator.PipelineUiSnapshot
import kotlinx.coroutines.launch
import java.util.Calendar

private enum class SarahRoute {
    HOME,
    VOICE_MODE,
    SETTINGS,
    SYSTEM_SHIZUKU,
    AI_CREDENTIALS,
    REMINDERS,
    AUTONOMOUS_TASKS
}

@Composable
fun VoiceAssistantScreen(
    snapshot: PipelineUiSnapshot,
    sessions: List<ChatSessionEntity>,
    facts: List<MemoryFactEntity>,
    recentMessages: List<ChatMessageEntity>,
    permissions: PermissionSummary,
    credentials: CredentialsUiState,
    isDarkTheme: Boolean,
    devDiagnosticsEnabled: Boolean,
    onStartService: () -> Unit,
    onManualWake: () -> Unit,
    onStopActiveTurn: () -> Unit,
    onToggleMute: () -> Unit,
    onWakeCueChanged: (WakeCueMode) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onDarkThemeChanged: (Boolean) -> Unit,
    onDevDiagnosticsChanged: (Boolean) -> Unit,
    onSubmitText: (String) -> Unit,
    onNewSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSaveFact: (category: String, key: String, value: String) -> Unit,
    onDeleteFact: (String) -> Unit,
    onClearAllMemory: () -> Unit,
    onRequestMicAndNotifications: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestNotificationListener: () -> Unit,
    onRequestBatteryIgnore: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onSaveCredentials: (deepgramKey: String, mercuryKey: String, fishKey: String, geminiEmail: String, geminiToken: String, lockPin: String) -> Unit,
    onClearLockPin: () -> Unit,
    onImportAccountsJson: () -> Unit,
    onSyncQuotasNow: () -> Unit,
    reminders: List<ScheduledReminderEntity> = emptyList(),
    pendingMedia: PendingMediaUi? = null,
    onWakeSensitivityChanged: (WakeSensitivity) -> Unit = {},
    onPickGallery: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onClearPendingMedia: () -> Unit = {},
    onSpeakReply: (String) -> Unit = {},
    onResolveSafetyConfirmation: (Boolean) -> Unit = {},
    onCreateReminder: (String, Long) -> Unit = { _, _ -> },
    onSnoozeReminder: (Long, Long) -> Unit = { _, _ -> },
    onDeleteReminder: (Long) -> Unit = {},
    onSignInWithGoogle: () -> Unit = {},
    onManualOAuthCallback: (String) -> Unit = {},
    onRemoveGoogleAccount: (String) -> Unit = {},
    autonomousTasks: List<AutonomousTaskEntity> = emptyList(),
    autonomousLogs: List<AutonomousTaskLogEntity> = emptyList(),
    autonomousAutoUnlock: Boolean = false,
    onAutonomousAutoUnlockChanged: (Boolean) -> Unit = {},
    onPauseAutonomousTask: (Long) -> Unit = {},
    onResumeAutonomousTask: (Long) -> Unit = {},
    onStopAutonomousTask: (Long) -> Unit = {},
    onStopAllAutonomousTasks: () -> Unit = {},
    onRunAutonomousTaskNow: (Long) -> Unit = {},
    onDeleteAutonomousTask: (Long) -> Unit = {}
) {
    val palette = if (isDarkTheme) ObsidianDark else WarmAlabasterLight
    var route by remember { mutableStateOf(SarahRoute.HOME) }
    var drawerTabMemory by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    when (route) {
        SarahRoute.VOICE_MODE -> {
            LiveVoiceImmersionScreen(
                palette = palette,
                snapshot = snapshot,
                onClose = { route = SarahRoute.HOME },
                onToggleMute = onToggleMute,
                onStopActiveTurn = onStopActiveTurn,
                onManualWake = onManualWake,
                onStartService = onStartService,
                onSwitchToKeyboard = { route = SarahRoute.HOME }
            )
        }

        SarahRoute.SETTINGS -> {
            CompanionSettingsScreen(
                palette = palette,
                snapshot = snapshot,
                permissions = permissions,
                credentials = credentials,
                factsCount = facts.size,
                pendingRemindersCount = reminders.count { it.status == "pending" },
                activeAutonomousCount = autonomousTasks.count { it.status == "ACTIVE" || it.status == "WAITING_UNLOCK" },
                autonomousAutoUnlock = autonomousAutoUnlock,
                darkTheme = isDarkTheme,
                devDiagnostics = devDiagnosticsEnabled,
                onBack = { route = SarahRoute.HOME },
                onOpenSystemShizuku = { route = SarahRoute.SYSTEM_SHIZUKU },
                onOpenAiCredentials = { route = SarahRoute.AI_CREDENTIALS },
                onOpenReminders = { route = SarahRoute.REMINDERS },
                onOpenAutonomousTasks = { route = SarahRoute.AUTONOMOUS_TASKS },
                onAutonomousAutoUnlockChanged = onAutonomousAutoUnlockChanged,
                onOpenMemory = {
                    drawerTabMemory = true
                    route = SarahRoute.HOME
                    scope.launch { drawerState.open() }
                },
                onWakeCueChanged = onWakeCueChanged,
                onWakeSensitivityChanged = onWakeSensitivityChanged,
                onLanguageChanged = onLanguageChanged,
                onThemeChanged = onDarkThemeChanged,
                onDevDiagnosticsChanged = onDevDiagnosticsChanged,
                onClearAllMemory = onClearAllMemory
            )
        }

        SarahRoute.SYSTEM_SHIZUKU -> {
            SystemShizukuScreen(
                palette = palette,
                permissions = permissions,
                onBack = { route = SarahRoute.SETTINGS },
                onRequestShizukuAccess = onRequestShizukuPermission,
                onOpenShizukuApp = onOpenShizukuApp,
                onRequestMicAndNotif = onRequestMicAndNotifications,
                onOpenAccessibilitySettings = onRequestAccessibility,
                onOpenNotificationListenerSettings = onRequestNotificationListener,
                onRequestBatteryOptimization = onRequestBatteryIgnore,
                onRequestOverlayPermission = onRequestOverlay
            )
        }

        SarahRoute.AI_CREDENTIALS -> {
            AiCredentialsScreen(
                palette = palette,
                credentials = credentials,
                onBack = { route = SarahRoute.SETTINGS },
                onSaveCredentials = { deepgram, mercury, fish, email, token, lockPin ->
                    onSaveCredentials(deepgram, mercury, fish, email, token, lockPin.orEmpty())
                },
                onImportAccountsJson = onImportAccountsJson,
                onSyncGeminiQuota = onSyncQuotasNow,
                onClearLockPin = onClearLockPin,
                onSignInWithGoogle = onSignInWithGoogle,
                onManualOAuthCallback = onManualOAuthCallback,
                onRemoveGoogleAccount = onRemoveGoogleAccount
            )
        }

        SarahRoute.REMINDERS -> {
            RemindersScreen(
                palette = palette,
                reminders = reminders,
                onBack = { route = SarahRoute.SETTINGS },
                onCreateReminder = onCreateReminder,
                onSnoozeReminder = onSnoozeReminder,
                onDeleteReminder = onDeleteReminder
            )
        }

        SarahRoute.AUTONOMOUS_TASKS -> {
            AutonomousTasksScreen(
                palette = palette,
                tasks = autonomousTasks,
                logs = autonomousLogs,
                autoUnlockEnabled = autonomousAutoUnlock,
                onBack = { route = SarahRoute.HOME },
                onAutoUnlockChanged = onAutonomousAutoUnlockChanged,
                onPauseTask = onPauseAutonomousTask,
                onResumeTask = onResumeAutonomousTask,
                onStopTask = onStopAutonomousTask,
                onStopAll = onStopAllAutonomousTasks,
                onRunNow = onRunAutonomousTaskNow,
                onDeleteTask = onDeleteAutonomousTask
            )
        }

        SarahRoute.HOME -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                scrimColor = Color.Black.copy(alpha = 0.28f),
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = palette.stone,
                        modifier = Modifier.widthIn(max = 316.dp)
                    ) {
                        SarahNavigationDrawerContent(
                            palette = palette,
                            sessions = sessions,
                            activeSessionId = snapshot.activeSessionId,
                            facts = facts,
                            initialMemoryTab = drawerTabMemory,
                            onTabChanged = { drawerTabMemory = it },
                            onNewConversation = {
                                onNewSession()
                                scope.launch { drawerState.close() }
                            },
                            onSelectSession = { sid ->
                                onSelectSession(sid)
                                scope.launch { drawerState.close() }
                            },
                            onSaveFact = onSaveFact,
                            onDeleteFact = onDeleteFact,
                            onOpenSettings = {
                                scope.launch { drawerState.close() }
                                route = SarahRoute.SETTINGS
                            }
                        )
                    }
                }
            ) {
                HomeConversationScreen(
                    palette = palette,
                    snapshot = snapshot,
                    facts = facts,
                    recentMessages = recentMessages,
                    permissions = permissions,
                    devDiagnosticsEnabled = devDiagnosticsEnabled,
                    pendingMedia = pendingMedia,
                    activeAutonomousTasks = autonomousTasks.filter { it.status == "ACTIVE" || it.status == "WAITING_UNLOCK" },
                    onOpenAutonomousTasks = { route = SarahRoute.AUTONOMOUS_TASKS },
                    onStopAllAutonomousTasks = onStopAllAutonomousTasks,
                    onOpenDrawer = {
                        drawerTabMemory = false
                        scope.launch { drawerState.open() }
                    },
                    onNewChat = onNewSession,
                    onOpenSettings = { route = SarahRoute.SETTINGS },
                    onOpenSystemShizuku = { route = SarahRoute.SYSTEM_SHIZUKU },
                    onOpenReminders = { route = SarahRoute.REMINDERS },
                    onOpenVoiceMode = {
                        if (snapshot.phase == ConversationPhase.STOPPED) onStartService()
                        route = SarahRoute.VOICE_MODE
                    },
                    onToggleMute = onToggleMute,
                    onStopActiveTurn = onStopActiveTurn,
                    onSubmitText = onSubmitText,
                    onPickGallery = onPickGallery,
                    onTakePhoto = onTakePhoto,
                    onClearPendingMedia = onClearPendingMedia,
                    onSpeakReply = onSpeakReply
                )
            }
        }
    }

    val pendingConfirm = snapshot.pendingConfirmation
    if (pendingConfirm != null) {
        AlertDialog(
            onDismissRequest = { onResolveSafetyConfirmation(false) },
            containerColor = palette.card,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Shield, null, tint = palette.sienna, modifier = Modifier.size(22.dp))
                    Text("Confirm Sensitive Action", color = palette.ink, fontFamily = EditorialSerif)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = palette.stone,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = pendingConfirm.targetLabel,
                                color = palette.ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${pendingConfirm.packageName} · ${pendingConfirm.actionDesc}",
                                color = palette.muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = pendingConfirm.reason,
                        color = palette.ink,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Text(
                        text = "Tap Approve & Continue, or say \"Yes\" / \"Cancel\" aloud.",
                        color = palette.muted,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onResolveSafetyConfirmation(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.sienna, contentColor = Color.White),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Approve & Continue", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { onResolveSafetyConfirmation(false) },
                    border = BorderStroke(1.dp, palette.hairline),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Cancel", color = palette.ink)
                }
            }
        )
    }
}

@Composable
private fun HomeConversationScreen(
    palette: SarahPalette,
    snapshot: PipelineUiSnapshot,
    facts: List<MemoryFactEntity>,
    recentMessages: List<ChatMessageEntity>,
    permissions: PermissionSummary,
    devDiagnosticsEnabled: Boolean,
    pendingMedia: PendingMediaUi?,
    activeAutonomousTasks: List<AutonomousTaskEntity>,
    onOpenAutonomousTasks: () -> Unit,
    onStopAllAutonomousTasks: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSystemShizuku: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenVoiceMode: () -> Unit,
    onToggleMute: () -> Unit,
    onStopActiveTurn: () -> Unit,
    onSubmitText: (String) -> Unit,
    onPickGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onClearPendingMedia: () -> Unit,
    onSpeakReply: (String) -> Unit
) {
    var composerText by remember { mutableStateOf("") }
    var actionsExpanded by remember { mutableStateOf(false) }
    var attachMenuOpen by remember { mutableStateOf(false) }
    val chronologicalMessages = remember(recentMessages) { prepareVisibleChatMessages(recentMessages) }
    val listState = rememberLazyListState()

    val savedUserName = remember(facts) {
        facts.firstOrNull {
            it.factKey.contains("user.name", ignoreCase = true) ||
                it.factKey.equals("name", ignoreCase = true)
        }?.factValue?.takeIf { it.isNotBlank() }
    }

    val showLiveTurnOverlay = shouldShowLiveTurnOverlay(
        phase = snapshot.phase,
        liveTranscript = snapshot.liveTranscript,
        routingReason = snapshot.routingReason
    )
    val showLiveUserBubble = showLiveTurnOverlay &&
        shouldShowLiveUserBubble(chronologicalMessages, snapshot.liveTranscript)
    val cleanedLiveReply = remember(snapshot.assistantReply) {
        EmotionTagNormalizer.stripTagsForDisplay(snapshot.assistantReply)
    }

    LaunchedEffect(chronologicalMessages.size, cleanedLiveReply, snapshot.liveTranscript, showLiveTurnOverlay) {
        val totalItems = chronologicalMessages.size + (if (showLiveTurnOverlay) 1 else 0) + 1
        if (totalItems > 1) {
            runCatching { listState.animateScrollToItem(totalItems - 1) }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = palette.paper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
        ) {
            // Minimal Claude-style Top Bar (fixed-width action icons so center status pill never squishes them)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onOpenDrawer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Rounded.Menu,
                        contentDescription = "Chats & Memory",
                        tint = palette.ink,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .clickable { onOpenVoiceMode() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        SarahBrandMark(size = 18.dp, outerColor = palette.ink, centerColor = palette.sienna)
                        Text(
                            text = "Sarah",
                            color = palette.ink,
                            fontFamily = EditorialSerif,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                        Surface(
                            color = if (snapshot.micMuted) palette.dangerSoft else palette.stone,
                            shape = RoundedCornerShape(99.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (snapshot.micMuted) palette.danger else palette.sage)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = friendlyPhaseLabel(snapshot.phase, snapshot.micMuted, snapshot.callPaused),
                                    color = if (snapshot.micMuted) palette.danger else palette.muted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Rounded.EditNote,
                        contentDescription = "New Chat",
                        tint = palette.ink,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        tint = palette.ink,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

            // Conversation + Editorial Welcome Feed
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (activeAutonomousTasks.isNotEmpty()) {
                    item(key = "autonomous_live_banner") {
                        val headTask = activeAutonomousTasks.first()
                        Surface(
                            color = palette.sageSoft,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, palette.sage.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenAutonomousTasks() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = palette.sage, modifier = Modifier.size(20.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (activeAutonomousTasks.size == 1) {
                                            "Autonomous: ${headTask.title}"
                                        } else {
                                            "${activeAutonomousTasks.size} Autonomous Loops Active"
                                        },
                                        color = palette.ink,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = headTask.lastResultSummary.ifBlank {
                                            "Run ${headTask.runCount}/${headTask.maxRuns} · Tap to view logs"
                                        },
                                        color = palette.muted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = onStopAllAutonomousTasks,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("Stop All", color = palette.sienna, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (permissions.activeCoreCount < 6) {
                    item(key = "setup_banner") {
                        Surface(
                            color = palette.siennaSoft,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, palette.sienna.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSystemShizuku() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Rounded.Shield, contentDescription = null, tint = palette.sienna, modifier = Modifier.size(20.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Finish phone setup (${permissions.activeCoreCount}/6 active)",
                                        color = palette.ink,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Tap to review Shizuku, Accessibility, notifications & battery.",
                                        color = palette.muted,
                                        fontSize = 11.sp
                                    )
                                }
                                Text("Setup →", color = palette.sienna, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (chronologicalMessages.isEmpty() && !showLiveTurnOverlay) {
                    item(key = "editorial_welcome") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 34.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "AMBIENT COMPANION",
                                color = palette.sienna,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = timeGreeting(savedUserName),
                                color = palette.ink,
                                fontFamily = EditorialSerif,
                                fontSize = 32.sp,
                                lineHeight = 38.sp
                            )
                            Text(
                                text = "Always on. Speak naturally, say \"Sarah\" anytime, or type below.",
                                color = palette.muted,
                                fontSize = 15.sp,
                                lineHeight = 22.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SuggestionChip(palette, "Check my latest OTP") { onSubmitText("Check my latest OTP") }
                                SuggestionChip(palette, "Turn on Do Not Disturb") { onSubmitText("Turn on Do Not Disturb") }
                                SuggestionChip(palette, "Open Wi-Fi settings") { onSubmitText("Open Wi-Fi settings") }
                                SuggestionChip(palette, "What is my battery level?") { onSubmitText("What is my battery level?") }
                            }
                        }
                    }
                } else {
                    items(chronologicalMessages, key = { it.id }) { msg ->
                        if (msg.role == "user") {
                            UserMessagePill(palette = palette, text = msg.content)
                        } else {
                            val isLatestSaved = msg == chronologicalMessages.lastOrNull { it.role == "assistant" }
                            val attachSteps = if (isLatestSaved && !showLiveTurnOverlay) snapshot.recentActionSteps else emptyList()
                            SarahMessageEditorial(
                                palette = palette,
                                text = msg.content,
                                actionSteps = attachSteps,
                                actionsExpanded = actionsExpanded,
                                onToggleActions = { actionsExpanded = !actionsExpanded },
                                showQuickActions = true,
                                onSpeakText = onSpeakReply
                            )
                        }
                    }

                    if (showLiveTurnOverlay) {
                        item(key = "live_turn") {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (showLiveUserBubble) {
                                    UserMessagePill(palette = palette, text = snapshot.liveTranscript.trim())
                                }
                                SarahMessageEditorial(
                                    palette = palette,
                                    text = cleanedLiveReply.ifBlank { friendlyPhaseLabel(snapshot.phase, snapshot.micMuted, snapshot.callPaused) },
                                    actionSteps = snapshot.recentActionSteps,
                                    actionsExpanded = actionsExpanded,
                                    onToggleActions = { actionsExpanded = !actionsExpanded }
                                )
                            }
                        }
                    }
                }

                if (devDiagnosticsEnabled && (snapshot.lastLatencySummary.isNotBlank() || snapshot.routingReason.isNotBlank())) {
                    item(key = "dev_diag") {
                        Surface(
                            color = palette.stone,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, palette.hairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "DEV: ${snapshot.activeModelBadge} • ${snapshot.routingReason}",
                                    color = palette.muted,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Floor ${"%.1f".format(snapshot.noiseFloorDbfs)} dBFS | SNR ${"%.1f".format(snapshot.currentSnrDb)} dB",
                                    color = palette.muted,
                                    fontSize = 11.sp
                                )
                                if (snapshot.lastLatencySummary.isNotBlank()) {
                                    Text(snapshot.lastLatencySummary, color = palette.sienna, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (pendingMedia != null) {
                Surface(
                    color = palette.stone,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                    ) {
                        Icon(Icons.Rounded.Image, contentDescription = null, tint = palette.sienna, modifier = Modifier.size(16.dp))
                        Text(
                            text = pendingMedia.label,
                            color = palette.ink,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(onClick = onClearPendingMedia, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove attachment", tint = palette.muted, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Floating Editorial Composer Pill (No Bottom Tab Bar!)
            Surface(
                color = palette.card,
                shape = RoundedCornerShape(99.dp),
                border = BorderStroke(1.dp, palette.hairline),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (snapshot.micMuted) palette.dangerSoft else palette.siennaSoft)
                    ) {
                        Icon(
                            imageVector = if (snapshot.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = if (snapshot.micMuted) "Resume mic" else "Mute mic",
                            tint = if (snapshot.micMuted) palette.danger else palette.sienna,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { attachMenuOpen = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Attach photo or reminders",
                                tint = palette.muted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onDismissRequest = { attachMenuOpen = false },
                            containerColor = palette.card
                        ) {
                            DropdownMenuItem(
                                text = { Text("Take Photo", color = palette.ink, fontSize = 14.sp) },
                                leadingIcon = { Icon(Icons.Rounded.CameraAlt, null, tint = palette.sienna) },
                                onClick = {
                                    attachMenuOpen = false
                                    onTakePhoto()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Photo Library", color = palette.ink, fontSize = 14.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Image, null, tint = palette.sienna) },
                                onClick = {
                                    attachMenuOpen = false
                                    onPickGallery()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reminders", color = palette.ink, fontSize = 14.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Alarm, null, tint = palette.sienna) },
                                onClick = {
                                    attachMenuOpen = false
                                    onOpenReminders()
                                }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (composerText.isEmpty()) {
                            Text(
                                text = if (pendingMedia != null) "Ask about this photo..." else "Ask or say 'Sarah'...",
                                color = palette.muted,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = composerText,
                            onValueChange = { composerText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = palette.ink, fontSize = 15.sp),
                            cursorBrush = SolidColor(palette.sienna),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (composerText.isNotBlank() || pendingMedia != null) {
                                        onSubmitText(composerText)
                                        composerText = ""
                                    }
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val hasReadyInput = composerText.isNotBlank() || pendingMedia != null
                    val isBusy = snapshot.phase == ConversationPhase.THINKING ||
                        snapshot.phase == ConversationPhase.SPEAKING_REPLY
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    hasReadyInput -> palette.sienna
                                    isBusy -> palette.danger
                                    else -> palette.ink
                                }
                            )
                            .clickable {
                                when {
                                    hasReadyInput -> {
                                        onSubmitText(composerText)
                                        composerText = ""
                                    }
                                    isBusy -> onStopActiveTurn()
                                    else -> onOpenVoiceMode()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                hasReadyInput -> Icons.AutoMirrored.Rounded.Send
                                isBusy -> Icons.Rounded.Stop
                                else -> Icons.Rounded.GraphicEq
                            },
                            contentDescription = when {
                                hasReadyInput -> "Send"
                                isBusy -> "Stop Sarah"
                                else -> "Live Voice Mode"
                            },
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(palette: SarahPalette, label: String, onClick: () -> Unit) {
    Surface(
        color = palette.stone,
        shape = RoundedCornerShape(99.dp),
        border = BorderStroke(1.dp, palette.hairline),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = palette.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun UserMessagePill(palette: SarahPalette, text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = palette.stone,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = text,
                color = palette.ink,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
            )
        }
    }
}

@Composable
private fun SarahMessageEditorial(
    palette: SarahPalette,
    text: String,
    actionSteps: List<String>,
    actionsExpanded: Boolean,
    onToggleActions: () -> Unit,
    showQuickActions: Boolean = false,
    onSpeakText: ((String) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    var copiedRecently by remember(text) { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SarahBrandMark(size = 16.dp, outerColor = palette.ink, centerColor = palette.sienna)
            Text("Sarah", color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        if (actionSteps.isNotEmpty()) {
            Surface(
                color = palette.stone,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.hairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleActions() }
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = palette.sage, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${actionSteps.size} phone action${if (actionSteps.size > 1) "s" else ""} · ${actionSteps.last()}",
                            color = palette.ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (actionsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Toggle details",
                            tint = palette.muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AnimatedVisibility(visible = actionsExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            actionSteps.forEachIndexed { idx, step ->
                                Text(
                                    text = "${idx + 1}. $step",
                                    color = palette.muted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = text,
            color = palette.ink,
            fontFamily = EditorialSerif,
            fontSize = 19.sp,
            lineHeight = 28.sp
        )

        if (showQuickActions && text.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(text))
                            copiedRecently = true
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (copiedRecently) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = "Copy reply",
                        tint = if (copiedRecently) palette.sage else palette.muted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (copiedRecently) "Copied" else "Copy",
                        color = if (copiedRecently) palette.sage else palette.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (onSpeakText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSpeakText(text) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VolumeUp,
                            contentDescription = "Read aloud",
                            tint = palette.muted,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Read aloud",
                            color = palette.muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveVoiceImmersionScreen(
    palette: SarahPalette,
    snapshot: PipelineUiSnapshot,
    onClose: () -> Unit,
    onToggleMute: () -> Unit,
    onStopActiveTurn: () -> Unit,
    onManualWake: () -> Unit,
    onStartService: () -> Unit,
    onSwitchToKeyboard: () -> Unit
) {
    val active = !snapshot.micMuted &&
        snapshot.phase != ConversationPhase.IDLE_STANDBY &&
        snapshot.phase != ConversationPhase.STOPPED

    val transition = rememberInfiniteTransition(label = "auroraOrb")
    val breath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = if (active) 1.08f else 0.99f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "breath"
    )
    val tintCore by animateColorAsState(
        targetValue = when {
            snapshot.micMuted -> palette.muted.copy(alpha = 0.35f)
            snapshot.phase == ConversationPhase.THINKING -> palette.sienna.copy(alpha = 0.55f)
            snapshot.phase == ConversationPhase.SPEAKING_REPLY -> Color(0xFF9AC8C3).copy(alpha = 0.65f)
            snapshot.phase == ConversationPhase.LISTENING || snapshot.phase == ConversationPhase.SPEAKING -> palette.sienna.copy(alpha = 0.48f)
            else -> palette.sienna.copy(alpha = 0.32f)
        },
        label = "tintCore"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = palette.paper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(44.dp))
                Spacer(Modifier.weight(1f))
                Surface(color = palette.stone, shape = RoundedCornerShape(99.dp), border = BorderStroke(1.dp, palette.hairline)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (snapshot.micMuted) palette.danger else palette.sienna)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("Hands-free · Room noise filtered", color = palette.muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(palette.stone)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = palette.ink)
                }
            }

            Spacer(Modifier.weight(0.9f))

            // Pearlescent Liquid-Silk Aurora Orb
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .clip(CircleShape)
                    .clickable {
                        if (snapshot.phase == ConversationPhase.STOPPED) onStartService() else onManualWake()
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val radius = (size.minDimension / 2f) * breath
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(tintCore, Color.Transparent),
                            center = center,
                            radius = radius
                        ),
                        radius = radius
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFFDF9), palette.siennaSoft.copy(alpha = 0.85f), tintCore),
                            center = Offset(center.x - radius * 0.16f, center.y - radius * 0.18f),
                            radius = radius * 0.72f
                        ),
                        radius = radius * 0.68f
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = "Voice Orb",
                    tint = palette.ink.copy(alpha = 0.7f),
                    modifier = Modifier.size(34.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            Surface(color = palette.siennaSoft, shape = RoundedCornerShape(99.dp)) {
                Text(
                    text = friendlyPhaseLabel(snapshot.phase, snapshot.micMuted).uppercase(),
                    color = palette.sienna,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            val cleanedVoiceReply = EmotionTagNormalizer.stripTagsForDisplay(snapshot.assistantReply)
            val primaryCaption = when {
                cleanedVoiceReply.isNotBlank() -> cleanedVoiceReply
                snapshot.liveTranscript.isNotBlank() -> "\"${snapshot.liveTranscript}\""
                snapshot.micMuted -> "Microphone is paused."
                else -> "Say \"Sarah\" or tap the orb to speak."
            }

            Text(
                text = primaryCaption,
                color = palette.ink,
                fontFamily = EditorialSerif,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            if (snapshot.recentActionSteps.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = palette.stone,
                    shape = RoundedCornerShape(99.dp),
                    border = BorderStroke(1.dp, palette.hairline)
                ) {
                    Text(
                        text = snapshot.recentActionSteps.last(),
                        color = palette.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1.1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (snapshot.micMuted) palette.dangerSoft else palette.stone)
                ) {
                    Icon(
                        imageVector = if (snapshot.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = "Mute",
                        tint = if (snapshot.micMuted) palette.danger else palette.ink
                    )
                }

                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.ink)
                        .clickable { onStopActiveTurn() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop", tint = palette.paper, modifier = Modifier.size(30.dp))
                }

                IconButton(
                    onClick = onSwitchToKeyboard,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(palette.stone)
                ) {
                    Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard", tint = palette.ink)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Say \"Sarah\", \"Wait\", or \"Stop\" anytime",
                color = palette.muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SarahNavigationDrawerContent(
    palette: SarahPalette,
    sessions: List<ChatSessionEntity>,
    activeSessionId: String,
    facts: List<MemoryFactEntity>,
    initialMemoryTab: Boolean,
    onTabChanged: (Boolean) -> Unit,
    onNewConversation: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSaveFact: (category: String, key: String, value: String) -> Unit,
    onDeleteFact: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var showAddMemoryDialog by remember { mutableStateOf(false) }

    if (showAddMemoryDialog) {
        var factCategory by remember { mutableStateOf("preference") }
        var factLabel by remember { mutableStateOf("") }
        var factValue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddMemoryDialog = false },
            containerColor = palette.card,
            titleContentColor = palette.ink,
            title = { Text("Remember something new", fontFamily = EditorialSerif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = factLabel,
                        onValueChange = { factLabel = it },
                        label = { Text("Topic (e.g. Favorite coffee)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.sienna,
                            unfocusedBorderColor = palette.hairline,
                            focusedTextColor = palette.ink,
                            unfocusedTextColor = palette.ink
                        )
                    )
                    OutlinedTextField(
                        value = factValue,
                        onValueChange = { factValue = it },
                        label = { Text("Detail (e.g. Iced Americano)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.sienna,
                            unfocusedBorderColor = palette.hairline,
                            focusedTextColor = palette.ink,
                            unfocusedTextColor = palette.ink
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (factLabel.isNotBlank() && factValue.isNotBlank()) {
                            onSaveFact(factCategory, factLabel, factValue)
                            showAddMemoryDialog = false
                        }
                    }
                ) {
                    Text("Save", color = palette.sienna, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemoryDialog = false }) {
                    Text("Cancel", color = palette.ink)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            SarahBrandMark(size = 20.dp, outerColor = palette.ink, centerColor = palette.sienna)
            Text("Sarah", color = palette.ink, fontFamily = EditorialSerif, fontSize = 22.sp)
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            color = palette.card,
            shape = RoundedCornerShape(99.dp),
            border = BorderStroke(1.dp, palette.hairline),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNewConversation() }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = palette.sienna, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New conversation", color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Segmented Chats | Memory switcher
        Surface(
            color = palette.paper,
            shape = RoundedCornerShape(99.dp),
            border = BorderStroke(1.dp, palette.hairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(4.dp)) {
                DrawerSegment(
                    label = "Chats (${sessions.size})",
                    selected = !initialMemoryTab,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { onTabChanged(false) }
                )
                DrawerSegment(
                    label = "Memory (${facts.size})",
                    selected = initialMemoryTab,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { onTabChanged(true) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!initialMemoryTab) {
                val visibleSessions = sessions.filter { it.turnCount > 0 || it.sessionId == activeSessionId }
                if (visibleSessions.isEmpty()) {
                    item {
                        Text(
                            "Your recent conversations will appear here.",
                            color = palette.muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    items(visibleSessions, key = { it.sessionId }) { sess ->
                        val isCurrent = sess.sessionId == activeSessionId
                        Surface(
                            color = if (isCurrent) palette.card else Color.Transparent,
                            shape = RoundedCornerShape(14.dp),
                            border = if (isCurrent) BorderStroke(1.dp, palette.hairline) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSession(sess.sessionId) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = if (isCurrent) palette.sienna else palette.muted,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sess.title.ifBlank { "Conversation" },
                                        color = palette.ink,
                                        fontSize = 14.sp,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = DateUtils.getRelativeTimeSpanString(sess.lastActiveAt).toString(),
                                        color = palette.muted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "What Sarah remembers",
                            color = palette.muted,
                            fontSize = 12.sp
                        )
                        TextButton(onClick = { showAddMemoryDialog = true }) {
                            Text("+ Add", color = palette.sienna, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (facts.isEmpty()) {
                    item {
                        Text(
                            text = "Nothing saved yet. Tell Sarah your preferences or tap + Add.",
                            color = palette.muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    items(facts, key = { it.id }) { fact ->
                        val isPin = fact.factKey.contains("pin", ignoreCase = true)
                        Surface(
                            color = palette.card,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, palette.hairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Psychology, contentDescription = null, tint = palette.sienna, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = humanizeFactKey(fact.factKey),
                                        color = palette.muted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (isPin) "••••••" else fact.factValue,
                                        color = palette.ink,
                                        fontSize = 13.sp
                                    )
                                }
                                IconButton(onClick = { onDeleteFact(fact.factKey) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove memory", tint = palette.muted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = palette.hairline, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpenSettings() }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = palette.ink, modifier = Modifier.size(20.dp))
            Text("Settings & Permissions", color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DrawerSegment(
    label: String,
    selected: Boolean,
    palette: SarahPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) palette.card else Color.Transparent,
        shape = RoundedCornerShape(99.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            color = if (selected) palette.ink else palette.muted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

private fun timeGreeting(userName: String?): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val base = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    return if (!userName.isNullOrBlank()) "$base, $userName" else "$base, what can I handle for you?"
}

private fun humanizeFactKey(rawKey: String): String {
    val clean = rawKey.removePrefix("user.").replace('_', ' ').replace('.', ' ').trim()
    return clean.replaceFirstChar { it.uppercase() }.ifBlank { "Preference" }
}

@Preview(showBackground = true)
@Composable
private fun PreviewEditorialHome() {
    VoiceAssistantScreen(
        snapshot = PipelineUiSnapshot(
            phase = ConversationPhase.IDLE_STANDBY,
            liveTranscript = "Turn on Wi-Fi and check notifications",
            assistantReply = "Done — I switched on Wi-Fi and checked your alerts.",
            recentActionSteps = listOf("Turned on Wi-Fi", "Checked notifications")
        ),
        sessions = listOf(
            ChatSessionEntity("s1", "Morning battery & Wi-Fi check", "", 0L, System.currentTimeMillis(), null, 4, false)
        ),
        facts = listOf(
            MemoryFactEntity(1, "preference", "user.favorite_drink", "Masala Chai", 1f, true, null, 0L)
        ),
        recentMessages = emptyList(),
        permissions = PermissionSummary(hasAccessibility = true, hasNotificationListener = true, shizukuRunning = true, shizukuGranted = true),
        credentials = CredentialsUiState(deepgramConfigured = true, mercuryConfigured = true, geminiQuotaSummary = "Gemini 99%"),
        isDarkTheme = false,
        devDiagnosticsEnabled = false,
        onStartService = {},
        onManualWake = {},
        onStopActiveTurn = {},
        onToggleMute = {},
        onWakeCueChanged = {},
        onLanguageChanged = {},
        onDarkThemeChanged = {},
        onDevDiagnosticsChanged = {},
        onSubmitText = {},
        onNewSession = {},
        onSelectSession = {},
        onSaveFact = { _, _, _ -> },
        onDeleteFact = {},
        onClearAllMemory = {},
        onRequestMicAndNotifications = {},
        onRequestAccessibility = {},
        onRequestNotificationListener = {},
        onRequestBatteryIgnore = {},
        onRequestOverlay = {},
        onRequestShizukuPermission = {},
        onOpenShizukuApp = {},
        onSaveCredentials = { _, _, _, _, _, _ -> },
        onClearLockPin = {},
        onImportAccountsJson = {},
        onSyncQuotasNow = {}
    )
}
