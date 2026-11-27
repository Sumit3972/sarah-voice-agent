package com.voiceagent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voiceagent.config.WakeCueMode
import com.voiceagent.config.WakeSensitivity
import com.voiceagent.memory.AutonomousTaskEntity
import com.voiceagent.memory.AutonomousTaskLogEntity
import com.voiceagent.memory.ScheduledReminderEntity
import com.voiceagent.orchestrator.PipelineUiSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CompanionSettingsScreen(
    palette: SarahPalette,
    snapshot: PipelineUiSnapshot,
    permissions: PermissionSummary,
    credentials: CredentialsUiState,
    factsCount: Int,
    pendingRemindersCount: Int,
    activeAutonomousCount: Int,
    autonomousAutoUnlock: Boolean,
    darkTheme: Boolean,
    devDiagnostics: Boolean,
    onBack: () -> Unit,
    onOpenSystemShizuku: () -> Unit,
    onOpenAiCredentials: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenAutonomousTasks: () -> Unit,
    onAutonomousAutoUnlockChanged: (Boolean) -> Unit,
    onOpenMemory: () -> Unit,
    onWakeCueChanged: (WakeCueMode) -> Unit,
    onWakeSensitivityChanged: (WakeSensitivity) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    onDevDiagnosticsChanged: (Boolean) -> Unit,
    onClearAllMemory: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        EditorialTopBar(
            palette = palette,
            title = "Companion Settings",
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(Modifier.height(2.dp))
                // Reassurance Banner matching Stitch
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.stone,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.siennaSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, null, tint = palette.sienna, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Sarah is ready on this device",
                                color = palette.ink,
                                fontFamily = CleanSans,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                "Ignoring background room noise automatically",
                                color = palette.muted,
                                fontSize = 12.sp
                            )
                        }
                        StatusPill(
                            label = if (permissions.activeCoreCount == 6) "All Ready" else "${permissions.activeCoreCount}/6 Ready",
                            fg = if (permissions.activeCoreCount == 6) palette.sage else palette.sienna,
                            bg = if (permissions.activeCoreCount == 6) palette.sageSoft else palette.siennaSoft
                        )
                    }
                }
            }

            item {
                SectionHeading(palette, Icons.Default.GraphicEq, "Voice & Natural Interruption")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        SettingChoiceRow(
                            palette = palette,
                            title = "Wake Word Engine",
                            subtitle = "Offline BC-ResNet-8 \"Sarah\" (16-bit PCM) · zero idle cloud data",
                            pillText = "Offline Only",
                            onClick = {}
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingChoiceRow(
                            palette = palette,
                            title = "Wake Sensitivity",
                            subtitle = "Adjust how easily offline \"Sarah\" pickup triggers",
                            pillText = snapshot.wakeSensitivity.label,
                            onClick = {
                                val next = when (snapshot.wakeSensitivity) {
                                    WakeSensitivity.STRICT -> WakeSensitivity.BALANCED
                                    WakeSensitivity.BALANCED -> WakeSensitivity.HIGH_PICKUP
                                    WakeSensitivity.HIGH_PICKUP -> WakeSensitivity.STRICT
                                }
                                onWakeSensitivityChanged(next)
                            }
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingChoiceRow(
                            palette = palette,
                            title = "Wake Sound",
                            subtitle = "Audio cue when Sarah hears her name",
                            pillText = if (snapshot.wakeCueMode == WakeCueMode.CUSTOM_CHIME) "Soft Chime" else "Silent",
                            onClick = {
                                val next = if (snapshot.wakeCueMode == WakeCueMode.CUSTOM_CHIME) {
                                    WakeCueMode.SILENT
                                } else {
                                    WakeCueMode.CUSTOM_CHIME
                                }
                                onWakeCueChanged(next)
                            }
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingChoiceRow(
                            palette = palette,
                            title = "Speech Language",
                            subtitle = "Speech pickup for commands (replies always in English)",
                            pillText = if (snapshot.sttLanguageCode == "auto") "Auto: EN & Hinglish" else "English (IN)",
                            onClick = {
                                onLanguageChanged(if (snapshot.sttLanguageCode == "auto") "en-IN" else "auto")
                            }
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingSwitchRow(
                            palette = palette,
                            title = "Obsidian Dark Appearance",
                            subtitle = "Switch between Warm Paper Light and Obsidian Dark",
                            checked = darkTheme,
                            onCheckedChange = onThemeChanged
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Natural Interruptions", color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "Say \"Stop\" or \"Wait\", or speak naturally to interrupt. Room noise is ignored automatically.",
                                    color = palette.muted,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionHeading(palette, Icons.Default.Bolt, "System, Shizuku & AI Keys")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenSystemShizuku)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(palette.stone),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Security, null, tint = palette.sienna, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("System & Shizuku Control", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Shizuku bridge, Accessibility, Notification Reader, Battery", color = palette.muted, fontSize = 12.sp)
                            }
                            StatusPill(
                                label = if (permissions.shizukuRunning && permissions.shizukuGranted) "Connected" else "Setup",
                                fg = if (permissions.shizukuRunning && permissions.shizukuGranted) palette.sage else palette.sienna,
                                bg = if (permissions.shizukuRunning && permissions.shizukuGranted) palette.sageSoft else palette.siennaSoft
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = palette.muted)
                        }

                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenAiCredentials)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(palette.stone),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Key, null, tint = palette.sienna, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("AI Services & Keys", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(
                                    credentials.geminiQuotaSummary.ifBlank { "Deepgram, Mercury, Gemini token & Fish voice" },
                                    color = palette.muted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = palette.muted)
                        }
                    }
                }
            }

            item {
                SectionHeading(palette, Icons.Default.Psychology, "Personal Memory & Privacy")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenAutonomousTasks)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bolt, null, tint = palette.sienna, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Autonomous Tasks & Loops", color = palette.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("Scheduled actions, watch loops & continuous chat autopilot", color = palette.muted, fontSize = 12.sp)
                            }
                            StatusPill(
                                label = "$activeAutonomousCount active",
                                fg = if (activeAutonomousCount > 0) palette.sage else palette.muted,
                                bg = if (activeAutonomousCount > 0) palette.sageSoft else palette.stone
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = palette.muted)
                        }

                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))

                        SettingSwitchRow(
                            palette = palette,
                            title = "Auto-Unlock for Autonomous UI",
                            subtitle = "Use saved PIN to unlock when a background task needs screen UI, then lock back",
                            checked = autonomousAutoUnlock,
                            onCheckedChange = onAutonomousAutoUnlockChanged
                        )

                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenReminders)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Alarm, null, tint = palette.sienna, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Scheduled Reminders", color = palette.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("View, create, snooze, or cancel voice alarms", color = palette.muted, fontSize = 12.sp)
                            }
                            Text("$pendingRemindersCount pending", color = palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = palette.muted)
                        }

                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenMemory)
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Stored Memories", color = palette.ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("Preferences and details Sarah remembers", color = palette.muted, fontSize = 12.sp)
                            }
                            Text("$factsCount saved", color = palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = palette.muted)
                        }

                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { confirmClear = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Clear Sarah's Memory", color = palette.sienna, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text("Remove all saved personal preferences and routines", color = palette.muted, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.DeleteOutline, null, tint = palette.sienna, modifier = Modifier.size(20.dp))
                        }

                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))

                        SettingSwitchRow(
                            palette = palette,
                            title = "Developer Diagnostics",
                            subtitle = "Show audio SNR dB, model names, and turn latency on Home",
                            checked = devDiagnostics,
                            onCheckedChange = onDevDiagnosticsChanged
                        )
                    }
                }
            }

            item {
                Text(
                    text = "Saved facts and screen lock PIN stay strictly on your phone.",
                    color = palette.muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = palette.card,
            title = { Text("Clear all saved memories?", color = palette.ink, fontFamily = EditorialSerif) },
            text = { Text("Sarah will forget your saved preferences, names, and screen PIN.", color = palette.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearAllMemory()
                }) {
                    Text("Clear", color = palette.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel", color = palette.ink)
                }
            }
        )
    }
}

@Composable
internal fun SystemShizukuScreen(
    palette: SarahPalette,
    permissions: PermissionSummary,
    onBack: () -> Unit,
    onRequestShizukuAccess: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onRequestMicAndNotif: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRequestOverlayPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        EditorialTopBar(palette = palette, title = "System & Shizuku", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.siennaSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Bolt, null, tint = palette.sienna, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Shizuku Power Engine", color = palette.ink, fontFamily = EditorialSerif, fontSize = 19.sp)
                                Text(
                                    when {
                                        permissions.shizukuRunning && permissions.shizukuGranted -> "Running & Authorized"
                                        permissions.shizukuRunning -> "Running — permission needed"
                                        else -> "Service not running"
                                    },
                                    color = palette.muted,
                                    fontSize = 12.sp
                                )
                            }
                            StatusPill(
                                label = when {
                                    permissions.shizukuRunning && permissions.shizukuGranted -> "Connected ✓"
                                    permissions.shizukuRunning -> "Allow Access"
                                    else -> "Start Shizuku"
                                },
                                fg = if (permissions.shizukuRunning && permissions.shizukuGranted) palette.sage else palette.sienna,
                                bg = if (permissions.shizukuRunning && permissions.shizukuGranted) palette.sageSoft else palette.siennaSoft
                            )
                        }

                        Text(
                            "Enables hands-free screen unlock, closing background apps, system toggles, and fast device commands without root.",
                            color = palette.muted,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onRequestShizukuAccess,
                                colors = ButtonDefaults.buttonColors(containerColor = palette.ink, contentColor = palette.paper),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = if (permissions.shizukuGranted) "Re-check" else "Grant Shizuku",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = onOpenShizukuApp,
                                border = BorderStroke(1.dp, palette.hairline),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = "Open Shizuku",
                                    color = palette.ink,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionHeading(palette, Icons.Default.Security, "Android Agent Permissions")
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        PermissionItemRow(
                            palette = palette,
                            icon = Icons.Default.Mic,
                            title = "Microphone Access",
                            subtitle = "Always-on wake word and live voice commands",
                            granted = permissions.hasMic,
                            onAction = onRequestMicAndNotif
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        PermissionItemRow(
                            palette = palette,
                            icon = Icons.Default.Notifications,
                            title = "Post Notifications",
                            subtitle = "Keeps Sarah active in background & delivers voice reminders",
                            granted = permissions.hasNotifications,
                            onAction = onRequestMicAndNotif
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        PermissionItemRow(
                            palette = palette,
                            icon = Icons.Default.TouchApp,
                            title = "Screen Control (Accessibility)",
                            subtitle = "Lets Sarah see screens, scroll, and tap buttons for you",
                            granted = permissions.hasAccessibility,
                            onAction = onOpenAccessibilitySettings
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        PermissionItemRow(
                            palette = palette,
                            icon = Icons.Default.Notifications,
                            title = "Notification & OTP Reader",
                            subtitle = "Reads unread messages and extracts verification OTPs aloud",
                            granted = permissions.hasNotificationListener,
                            onAction = onOpenNotificationListenerSettings
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        PermissionItemRow(
                            palette = palette,
                            icon = Icons.Default.BatteryChargingFull,
                            title = "Unrestricted Battery",
                            subtitle = "Prevents Android from putting Sarah to sleep in background",
                            granted = permissions.ignoringBattery,
                            onAction = onRequestBatteryOptimization
                        )
                        HorizontalDivider(color = palette.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        PermissionItemRow(
                            palette = palette,
                            icon = Icons.Default.Layers,
                            title = "Display Over Other Apps",
                            subtitle = "Allows launching apps cleanly while working in background",
                            granted = permissions.hasOverlay,
                            onAction = onRequestOverlayPermission
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
internal fun AiCredentialsScreen(
    palette: SarahPalette,
    credentials: CredentialsUiState,
    onBack: () -> Unit,
    onSaveCredentials: (deepgram: String, mercury: String, fishOverride: String, email: String, token: String, lockPin: String?) -> Unit,
    onImportAccountsJson: () -> Unit,
    onSyncGeminiQuota: () -> Unit,
    onClearLockPin: () -> Unit,
    onSignInWithGoogle: () -> Unit = {},
    onManualOAuthCallback: (String) -> Unit = {},
    onRemoveGoogleAccount: (String) -> Unit = {}
) {
    var geminiEmail by remember(credentials.geminiEmail) { mutableStateOf(credentials.geminiEmail) }
    var geminiToken by remember(credentials.geminiRefreshToken) { mutableStateOf(credentials.geminiRefreshToken) }
    var deepgramKey by remember(credentials.deepgramKey) { mutableStateOf(credentials.deepgramKey) }
    var inceptionKey by remember(credentials.mercuryKey) { mutableStateOf(credentials.mercuryKey) }
    var fishOverride by remember(credentials.fishOverrideKey) { mutableStateOf(credentials.fishOverrideKey) }
    var lockPin by remember(credentials.lockPinConfigured) { mutableStateOf("") }
    var expandFishOverride by remember { mutableStateOf(false) }
    var showManualOAuthDialog by remember { mutableStateOf(false) }
    var manualOAuthInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.ink)
            }
            Text(
                text = "AI Services & Keys",
                color = palette.ink,
                fontFamily = EditorialSerif,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { onSaveCredentials(deepgramKey, inceptionKey, fishOverride, geminiEmail, geminiToken, lockPin) },
                colors = ButtonDefaults.buttonColors(containerColor = palette.sienna, contentColor = palette.card),
                shape = RoundedCornerShape(50)
            ) {
                Text("Save", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                // Fish Audio built-in card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.stone,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sarah Voice (Fish Audio)", color = palette.ink, fontFamily = EditorialSerif, fontSize = 18.sp)
                                Text("Pre-configured speech synthesis is ready automatically—no key needed.", color = palette.muted, fontSize = 12.sp)
                            }
                            StatusPill(
                                label = if (credentials.fishBuiltInActive) "Active · Built-in ✓" else "Key Needed",
                                fg = if (credentials.fishBuiltInActive) palette.sage else palette.sienna,
                                bg = if (credentials.fishBuiltInActive) palette.sageSoft else palette.siennaSoft
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandFishOverride = !expandFishOverride }
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Override with custom Fish Audio key",
                                color = palette.sienna,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (expandFishOverride) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null,
                                tint = palette.sienna,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (expandFishOverride) {
                            MaskedSecretField(
                                palette = palette,
                                label = "Custom Fish Audio API Key (optional)",
                                value = fishOverride,
                                onValueChange = { fishOverride = it }
                            )
                        }
                    }
                }
            }

            item {
                // Gemini & Reasoning OAuth Refresh Token Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Gemini & Reasoning", color = palette.ink, fontFamily = EditorialSerif, fontSize = 18.sp)
                                Text("Google OAuth refresh token for Hydrogen reasoning & vision", color = palette.muted, fontSize = 12.sp)
                            }
                            StatusPill(
                                label = if (geminiToken.isNotBlank()) "Synced ✓" else "Required",
                                fg = if (geminiToken.isNotBlank()) palette.sage else palette.sienna,
                                bg = if (geminiToken.isNotBlank()) palette.sageSoft else palette.siennaSoft
                            )
                        }
                        if (credentials.geminiQuotaSummary.isNotBlank()) {
                            Text(credentials.geminiQuotaSummary, color = palette.sage, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }

                        Button(
                            onClick = onSignInWithGoogle,
                            enabled = !credentials.oauthInProgress,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.sienna, contentColor = palette.card),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        ) {
                            Icon(Icons.Default.Login, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (credentials.oauthInProgress) "Waiting for Google Sign-In..." else "Sign in with Google",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (credentials.oauthStatusMessage.isNotBlank()) {
                            Text(
                                text = credentials.oauthStatusMessage,
                                color = palette.muted,
                                fontSize = 11.sp
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showManualOAuthDialog = true },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Redirect didn't return automatically? Paste callback URL / code",
                                color = palette.sienna,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (credentials.accounts.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Connected Google Accounts", color = palette.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                credentials.accounts.forEach { acc ->
                                    Surface(
                                        color = palette.stone,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, palette.hairline),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = acc.email,
                                                    color = palette.ink,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Gemini ${acc.geminiPercent}% · Claude ${acc.claudePercent}%",
                                                    color = palette.muted,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            StatusPill(
                                                label = acc.tier,
                                                fg = if (acc.tier == "FREE") palette.ink else palette.sage,
                                                bg = if (acc.tier == "FREE") palette.paper else palette.sageSoft
                                            )
                                            IconButton(
                                                onClick = { onRemoveGoogleAccount(acc.email) },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, "Remove account", tint = palette.muted, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = geminiEmail,
                            onValueChange = { geminiEmail = it },
                            label = { Text("Account Email", fontSize = 12.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = sarahFieldColors(palette),
                            modifier = Modifier.fillMaxWidth()
                        )
                        MaskedSecretField(
                            palette = palette,
                            label = "OAuth Refresh Token (1//...)",
                            value = geminiToken,
                            onValueChange = { geminiToken = it }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = onImportAccountsJson,
                                border = BorderStroke(1.dp, palette.hairline),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.UploadFile, null, tint = palette.ink, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Import JSON",
                                    color = palette.ink,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = {
                                    onSaveCredentials(deepgramKey, inceptionKey, fishOverride, geminiEmail, geminiToken, lockPin)
                                    onSyncGeminiQuota()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.ink, contentColor = palette.paper),
                                shape = RoundedCornerShape(50),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Sync Quota",
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            item {
                // Deepgram Speech Recognition Key
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Deepgram Speech Recognition", color = palette.ink, fontFamily = EditorialSerif, fontSize = 18.sp)
                                Text("Nova-3 Multi (EN & Hinglish) + Flux English via api.in.deepgram.com", color = palette.muted, fontSize = 12.sp)
                            }
                            val ready = deepgramKey.isNotBlank() || credentials.deepgramConfigured
                            StatusPill(
                                label = if (deepgramKey.isNotBlank()) "Custom ✓" else if (ready) "Ready ✓" else "Required",
                                fg = if (ready) palette.sage else palette.sienna,
                                bg = if (ready) palette.sageSoft else palette.siennaSoft
                            )
                        }
                        MaskedSecretField(
                            palette = palette,
                            label = if (credentials.deepgramConfigured && deepgramKey.isBlank()) "Deepgram API Key (Active default — enter to override)" else "Deepgram API Key",
                            value = deepgramKey,
                            onValueChange = { deepgramKey = it }
                        )
                    }
                }
            }

            item {
                // Mercury / Inception Key
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Mercury Fast Brain & Decide", color = palette.ink, fontFamily = EditorialSerif, fontSize = 18.sp)
                                Text("Fast intent routing, room-noise filtering & instant chat", color = palette.muted, fontSize = 12.sp)
                            }
                            val ready = inceptionKey.isNotBlank() || credentials.mercuryConfigured
                            StatusPill(
                                label = if (inceptionKey.isNotBlank()) "Custom ✓" else if (ready) "Ready ✓" else "Required",
                                fg = if (ready) palette.sage else palette.sienna,
                                bg = if (ready) palette.sageSoft else palette.siennaSoft
                            )
                        }
                        MaskedSecretField(
                            palette = palette,
                            label = if (credentials.mercuryConfigured && inceptionKey.isBlank()) "Mercury API Key (Active default — enter to override)" else "Mercury / Inception API Key",
                            value = inceptionKey,
                            onValueChange = { inceptionKey = it }
                        )
                    }
                }
            }

            item {
                // Optional Screen Lock PIN
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, null, tint = palette.sienna, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Hands-Free Screen Unlock PIN",
                                color = palette.ink,
                                fontFamily = EditorialSerif,
                                fontSize = 18.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (credentials.lockPinConfigured) {
                                StatusPill(label = "Saved ✓", fg = palette.sage, bg = palette.sageSoft)
                                Spacer(Modifier.width(6.dp))
                            }
                            if (credentials.lockPinConfigured || lockPin.isNotBlank()) {
                                TextButton(onClick = {
                                    lockPin = ""
                                    onClearLockPin()
                                }) {
                                    Text("Clear", color = palette.danger, fontSize = 12.sp)
                                }
                            }
                        }
                        Text(
                            "Stored privately on your phone so Sarah can unlock via Shizuku when you ask.",
                            color = palette.muted,
                            fontSize = 12.sp
                        )
                        MaskedSecretField(
                            palette = palette,
                            label = if (credentials.lockPinConfigured) "Enter new PIN to replace saved PIN" else "Screen Lock PIN (optional)",
                            value = lockPin,
                            onValueChange = { if (it.length <= 12) lockPin = it },
                            keyboardType = KeyboardType.NumberPassword
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showManualOAuthDialog) {
        AlertDialog(
            onDismissRequest = { showManualOAuthDialog = false },
            containerColor = palette.card,
            title = { Text("Paste Google Callback", color = palette.ink, fontFamily = EditorialSerif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "If Chrome did not automatically return to Sarah, copy the failed localhost address (http://127.0.0.1:.../oauth-callback?code=...) or authorization code and paste it below.",
                        color = palette.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    OutlinedTextField(
                        value = manualOAuthInput,
                        onValueChange = { manualOAuthInput = it },
                        label = { Text("Callback URL or Code") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = sarahFieldColors(palette),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = manualOAuthInput.trim()
                        if (trimmed.isNotEmpty()) {
                            onManualOAuthCallback(trimmed)
                            manualOAuthInput = ""
                            showManualOAuthDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.sienna, contentColor = palette.card)
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualOAuthDialog = false }) {
                    Text("Cancel", color = palette.muted)
                }
            }
        )
    }
}

@Composable
internal fun EditorialTopBar(
    palette: SarahPalette,
    title: String,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.ink)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            color = palette.ink,
            fontFamily = EditorialSerif,
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun SectionHeading(palette: SarahPalette, icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    ) {
        Icon(icon, null, tint = palette.sienna, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = palette.ink, fontFamily = EditorialSerif, fontSize = 18.sp)
    }
}

@Composable
private fun SettingChoiceRow(
    palette: SarahPalette,
    title: String,
    subtitle: String,
    pillText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(label = pillText, fg = palette.ink, bg = palette.stone)
    }
}

@Composable
private fun SettingSwitchRow(
    palette: SarahPalette,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.card,
                checkedTrackColor = palette.sienna,
                uncheckedThumbColor = palette.muted,
                uncheckedTrackColor = palette.stone
            )
        )
    }
}

@Composable
private fun PermissionItemRow(
    palette: SarahPalette,
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(palette.stone),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = palette.muted, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(palette.sageSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Check, "Active", tint = palette.sage, modifier = Modifier.size(16.dp))
            }
        } else {
            StatusPill(label = "Enable →", fg = palette.sienna, bg = palette.siennaSoft)
        }
    }
}

@Composable
internal fun StatusPill(label: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = bg
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun MaskedSecretField(
    palette: SarahPalette,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                    tint = palette.muted
                )
            }
        },
        shape = RoundedCornerShape(14.dp),
        colors = sarahFieldColors(palette),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun sarahFieldColors(palette: SarahPalette) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = palette.ink,
    unfocusedTextColor = palette.ink,
    focusedBorderColor = palette.sienna,
    unfocusedBorderColor = palette.hairline,
    focusedContainerColor = palette.paper,
    unfocusedContainerColor = palette.paper,
    focusedLabelColor = palette.sienna,
    unfocusedLabelColor = palette.muted,
    cursorColor = palette.sienna
)

@Composable
internal fun RemindersScreen(
    palette: SarahPalette,
    reminders: List<ScheduledReminderEntity>,
    onBack: () -> Unit,
    onCreateReminder: (message: String, delayMinutes: Long) -> Unit,
    onSnoozeReminder: (id: Long, addMinutes: Long) -> Unit,
    onDeleteReminder: (id: Long) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var reminderMsg by remember { mutableStateOf("") }
    var delayMinutesText by remember { mutableStateOf("15") }
    val timeFmt = remember { SimpleDateFormat("EEE, h:mm a", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.ink)
            }
            Text(
                text = "Reminders & Routines",
                color = palette.ink,
                fontFamily = EditorialSerif,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = palette.sienna, contentColor = palette.card),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (reminders.isEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = palette.stone,
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Alarm, null, tint = palette.sienna, modifier = Modifier.size(28.dp))
                            Text("No reminders yet", color = palette.ink, fontFamily = EditorialSerif, fontSize = 20.sp)
                            Text(
                                "Say \"Sarah, remind me in 20 minutes to stretch\" or tap New above to create a spoken voice reminder.",
                                color = palette.muted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            } else {
                items(reminders, key = { it.id }) { rem ->
                    val isPending = rem.status == "pending"
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = palette.card,
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = rem.spokenMessage,
                                    color = palette.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = timeFmt.format(Date(rem.triggerAtMs)),
                                        color = palette.muted,
                                        fontSize = 12.sp
                                    )
                                    StatusPill(
                                        label = rem.status.replaceFirstChar { it.uppercase() },
                                        fg = if (isPending) palette.sage else palette.muted,
                                        bg = if (isPending) palette.sageSoft else palette.stone
                                    )
                                }
                            }
                            if (isPending) {
                                IconButton(onClick = { onSnoozeReminder(rem.id, 15L) }) {
                                    Icon(Icons.Default.Snooze, "Snooze 15 min", tint = palette.sienna, modifier = Modifier.size(20.dp))
                                }
                            }
                            IconButton(onClick = { onDeleteReminder(rem.id) }) {
                                Icon(Icons.Default.DeleteOutline, "Delete reminder", tint = palette.muted, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = palette.card,
            title = { Text("New Voice Reminder", color = palette.ink, fontFamily = EditorialSerif) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = reminderMsg,
                        onValueChange = { reminderMsg = it },
                        label = { Text("What should Sarah remind you?") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = sarahFieldColors(palette),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = delayMinutesText,
                        onValueChange = { delayMinutesText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("In how many minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        colors = sarahFieldColors(palette),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5", "15", "30", "60").forEach { preset ->
                            Surface(
                                color = if (delayMinutesText == preset) palette.siennaSoft else palette.stone,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.clickable { delayMinutesText = preset }
                            ) {
                                Text(
                                    text = if (preset == "60") "1h" else "${preset}m",
                                    color = if (delayMinutesText == preset) palette.sienna else palette.ink,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = delayMinutesText.toLongOrNull()?.coerceAtLeast(1L) ?: 15L
                        if (reminderMsg.isNotBlank()) {
                            onCreateReminder(reminderMsg.trim(), mins)
                            reminderMsg = ""
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.sienna, contentColor = palette.card)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel", color = palette.muted)
                }
            }
        )
    }
}

@Composable
internal fun AutonomousTasksScreen(
    palette: SarahPalette,
    tasks: List<AutonomousTaskEntity>,
    logs: List<AutonomousTaskLogEntity>,
    autoUnlockEnabled: Boolean,
    onBack: () -> Unit,
    onAutoUnlockChanged: (Boolean) -> Unit,
    onPauseTask: (Long) -> Unit,
    onResumeTask: (Long) -> Unit,
    onStopTask: (Long) -> Unit,
    onStopAll: () -> Unit,
    onRunNow: (Long) -> Unit,
    onDeleteTask: (Long) -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("EEE, h:mm a", Locale.US) }
    var expandedTaskId by remember { mutableStateOf<Long?>(null) }
    val activeCount = tasks.count { it.status == "ACTIVE" || it.status == "WAITING_UNLOCK" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.ink)
            }
            Text(
                text = "Autonomous Tasks",
                color = palette.ink,
                fontFamily = EditorialSerif,
                fontSize = 22.sp,
                modifier = Modifier.weight(1f)
            )
            if (activeCount > 0) {
                Button(
                    onClick = onStopAll,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.danger, contentColor = palette.card),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Stop All ($activeCount)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.hairline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingSwitchRow(
                        palette = palette,
                        title = "Auto-Unlock for UI Tasks",
                        subtitle = "Allows background tasks needing screen control to unlock with your saved PIN and lock back afterward",
                        checked = autoUnlockEnabled,
                        onCheckedChange = onAutoUnlockChanged
                    )
                }
            }

            if (tasks.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = palette.stone,
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("No autonomous tasks yet", color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                "Try saying: \"Chat with Priya on WhatsApp on behalf of me until I say stop\" or \"Wake up in 10 minutes and check the weather\".",
                                color = palette.muted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            } else {
                items(tasks, key = { it.id }) { task ->
                    val isActive = task.status == "ACTIVE"
                    val isPaused = task.status == "PAUSED" || task.status == "WAITING_UNLOCK"
                    val isExpanded = expandedTaskId == task.id
                    val taskLogs = logs.filter { it.taskId == task.id }.take(8)
                    val modeLabel = when (task.mode) {
                        "EVENT_CHAT_LOOP" -> "Chat Loop"
                        "INTERVAL_WATCH" -> "Watch Loop"
                        else -> "Scheduled"
                    }

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = palette.card,
                        border = BorderStroke(1.dp, if (isActive) palette.sage.copy(alpha = 0.45f) else palette.hairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.title, color = palette.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    Text(
                                        text = buildString {
                                            append(modeLabel)
                                            append(" · Run ${task.runCount}/${task.maxRuns}")
                                            if (task.watchSender.isNotBlank()) append(" · Target: ${task.watchSender}")
                                            task.nextTriggerAtMs?.let { append(" · Next: ${timeFmt.format(Date(it))}") }
                                        },
                                        color = palette.muted,
                                        fontSize = 11.sp
                                    )
                                }
                                StatusPill(
                                    label = task.status,
                                    fg = when (task.status) {
                                        "ACTIVE" -> palette.sage
                                        "WAITING_UNLOCK", "PAUSED" -> palette.sienna
                                        "FAILED" -> palette.danger
                                        else -> palette.muted
                                    },
                                    bg = when (task.status) {
                                        "ACTIVE" -> palette.sageSoft
                                        "WAITING_UNLOCK", "PAUSED" -> palette.siennaSoft
                                        else -> palette.stone
                                    }
                                )
                            }

                            Text(
                                text = task.goalPrompt,
                                color = palette.ink.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (task.lastResultSummary.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = palette.stone,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = task.lastResultSummary,
                                        color = palette.muted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isActive) {
                                    OutlinedButton(
                                        onClick = { onPauseTask(task.id) },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("Pause", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { onRunNow(task.id) },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("Run Now", fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { onStopTask(task.id) },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("Stop", color = palette.danger, fontSize = 11.sp) }
                                } else if (isPaused) {
                                    OutlinedButton(
                                        onClick = { onResumeTask(task.id) },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("Resume", color = palette.sage, fontSize = 11.sp) }
                                    OutlinedButton(
                                        onClick = { onStopTask(task.id) },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) { Text("Stop", color = palette.danger, fontSize = 11.sp) }
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { expandedTaskId = if (isExpanded) null else task.id }) {
                                    Icon(
                                        if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = "Logs",
                                        tint = palette.muted
                                    )
                                }
                                IconButton(onClick = { onDeleteTask(task.id) }) {
                                    Icon(Icons.Default.DeleteOutline, "Delete task", tint = palette.muted, modifier = Modifier.size(19.dp))
                                }
                            }

                            if (isExpanded && taskLogs.isNotEmpty()) {
                                HorizontalDivider(color = palette.hairline)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    taskLogs.forEach { log ->
                                        Text(
                                            text = "${timeFmt.format(Date(log.createdAt))} · [${log.triggerSource}/${log.outcome}] ${log.summary}",
                                            color = palette.muted,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
