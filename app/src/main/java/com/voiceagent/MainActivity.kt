package com.voiceagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.audio.VoiceCaptureForegroundService
import com.voiceagent.config.SecretsProvider
import com.voiceagent.llm.AntigravityOAuthManager
import com.voiceagent.memory.ScheduledReminderEntity
import com.voiceagent.notifications.SaraNotificationListenerService
import com.voiceagent.orchestrator.SarahRuntime
import com.voiceagent.shizuku.ShizukuBridge
import com.voiceagent.tools.ImageAttachmentEncoder
import com.voiceagent.tools.ReminderScheduler
import com.voiceagent.ui.CredentialsUiState
import com.voiceagent.ui.PendingMediaUi
import com.voiceagent.ui.PermissionSummary
import com.voiceagent.ui.VoiceAssistantScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var runtime: SarahRuntime
    private var permissionState by mutableStateOf(PermissionSummary())
    private var credentialsState by mutableStateOf(CredentialsUiState())
    private var darkThemeState by mutableStateOf(false)
    private var devDiagnosticsState by mutableStateOf(false)
    private var autoUnlockState by mutableStateOf(false)
    private var pendingMediaState by mutableStateOf<PendingMediaUi?>(null)
    private var pendingCameraUri: Uri? = null
    private var oauthSignInJob: Job? = null
    private var hasAutoRequestedPermissions = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionsAndStart()
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        encodeSelectedImage(uri, "Photo Library")
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            encodeSelectedImage(pendingCameraUri, "Camera Photo")
        }
    }

    private val importJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val raw = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            val imported = SecretsProvider.importAntigravityAccountsJson(this@MainActivity, raw)
            if (imported > 0) {
                runtime.oauthPool.reloadAccounts()
                runtime.oauthPool.syncAllQuotas()
                refreshCredentialsState()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Imported $imported Google account(s)", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No valid refresh_token found in JSON", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runtime = SarahRuntime.get(this)
        darkThemeState = SecretsProvider.isDarkTheme(this)
        devDiagnosticsState = SecretsProvider.isDevDiagnosticsEnabled(this)
        autoUnlockState = SecretsProvider.isAutonomousAutoUnlockEnabled(this)

        setContent {
            val snap by runtime.stateMachine.state.collectWhileStarted(runtime.stateMachine.state.value)
            val facts by runtime.dao.observeAllFacts().collectWhileStarted(emptyList())
            val sessions by runtime.dao.observeRecentSessions().collectWhileStarted(emptyList())
            val reminders by runtime.dao.observeAllReminders().collectWhileStarted(emptyList())
            val autonomousTasks by runtime.dao.observeAllAutonomousTasks().collectWhileStarted(emptyList())
            val autonomousLogs by runtime.dao.observeRecentAutonomousLogs(60).collectWhileStarted(emptyList())
            val activeSessionId = snap.activeSessionId.ifBlank { "default" }
            val sessionMessages by runtime.dao.observeRecentMessages(activeSessionId)
                .collectWhileStarted(emptyList())

            val pinConfigured = facts.any { it.factKey == "user.phone_lock_pin" && it.factValue.isNotBlank() }
            val effectiveCredentials = credentialsState.copy(lockPinConfigured = pinConfigured)

            VoiceAssistantScreen(
                snapshot = snap,
                sessions = sessions,
                facts = facts,
                recentMessages = sessionMessages,
                permissions = permissionState,
                credentials = effectiveCredentials,
                isDarkTheme = darkThemeState,
                devDiagnosticsEnabled = devDiagnosticsState,
                onStartService = { VoiceCaptureForegroundService.start(this) },
                onManualWake = { runtime.stateMachine.triggerManualWake() },
                onStopActiveTurn = { runtime.stateMachine.stopActiveTurn() },
                onToggleMute = { runtime.stateMachine.toggleMute() },
                onWakeCueChanged = { runtime.stateMachine.setWakeCueMode(it) },
                onLanguageChanged = { runtime.stateMachine.setSttLanguage(it) },
                onDarkThemeChanged = { dark ->
                    darkThemeState = dark
                    SecretsProvider.setDarkTheme(this, dark)
                },
                onDevDiagnosticsChanged = { enabled ->
                    devDiagnosticsState = enabled
                    SecretsProvider.setDevDiagnosticsEnabled(this, enabled)
                },
                onSubmitText = { text ->
                    val media = pendingMediaState
                    pendingMediaState = null
                    runtime.stateMachine.submitTextTurn(
                        text = text,
                        imageBase64 = media?.base64,
                        imageMimeType = media?.mimeType ?: "image/jpeg"
                    )
                },
                onNewSession = { runtime.stateMachine.startNewSession() },
                onSelectSession = { runtime.stateMachine.selectSession(it) },
                onSaveFact = { category, key, value ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val slug = key.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "_")
                        val cleanKey = if (slug.startsWith("user.")) slug else "user.$slug"
                        if (cleanKey.isNotBlank() && value.isNotBlank()) {
                            runtime.dao.upsertFact(
                                category = category.ifBlank { "preference" },
                                factKey = cleanKey,
                                factValue = value.trim(),
                                confidence = 1.0f,
                                isPinned = true,
                                sourceMessageId = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                    }
                },
                onDeleteFact = { key ->
                    lifecycleScope.launch(Dispatchers.IO) { runtime.dao.deleteFactByKey(key) }
                },
                onClearAllMemory = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runtime.dao.clearAllFacts()
                        refreshCredentialsState()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Personal memory cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onRequestMicAndNotifications = { requestRuntimePermissionsOnly() },
                onRequestAccessibility = {
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                },
                onRequestNotificationListener = {
                    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                },
                onRequestBatteryIgnore = {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    }
                },
                onRequestOverlay = {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                        )
                    }
                },
                onRequestShizukuPermission = {
                    if (ShizukuBridge.isBinderAlive()) {
                        ShizukuBridge.requestPermissionIfNeeded()
                        lifecycleScope.launch {
                            delay(1000L)
                            refreshPermissionsAndStart()
                        }
                    } else {
                        openShizukuApp()
                    }
                },
                onOpenShizukuApp = { openShizukuApp() },
                onSaveCredentials = { deepgram, mercury, fish, email, token, lockPin ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        SecretsProvider.setUserDeepgramKey(this@MainActivity, deepgram)
                        SecretsProvider.setUserInceptionKey(this@MainActivity, mercury)
                        SecretsProvider.setUserFishKey(this@MainActivity, fish)
                        SecretsProvider.setUserAntigravityAccount(this@MainActivity, email, token)
                        runtime.oauthPool.reloadAccounts()
                        runtime.oauthPool.syncAllQuotas()
                        runtime.sttClient.disconnect()
                        val cleanPin = lockPin.trim()
                        if (cleanPin.isNotEmpty()) {
                            runtime.dao.upsertFact(
                                category = "security",
                                factKey = "user.phone_lock_pin",
                                factValue = cleanPin,
                                confidence = 1.0f,
                                isPinned = true,
                                sourceMessageId = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        }
                        refreshCredentialsState()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "AI credentials saved", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onClearLockPin = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runtime.dao.deleteFactByKey("user.phone_lock_pin")
                        refreshCredentialsState()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Screen lock PIN cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onImportAccountsJson = {
                    runCatching { importJsonLauncher.launch(arrayOf("application/json", "*/*")) }
                },
                onSyncQuotasNow = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runtime.oauthPool.reloadAccounts()
                        runtime.oauthPool.syncAllQuotas()
                        refreshCredentialsState()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Quota synced", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                reminders = reminders,
                pendingMedia = pendingMediaState,
                onWakeSensitivityChanged = { runtime.stateMachine.setWakeSensitivity(it) },
                onPickGallery = {
                    runCatching {
                        pickImageLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }.onFailure {
                        Toast.makeText(this, "Photo picker unavailable", Toast.LENGTH_SHORT).show()
                    }
                },
                onTakePhoto = { launchCameraCapture() },
                onClearPendingMedia = { pendingMediaState = null },
                onSpeakReply = { runtime.stateMachine.speakAssistantReply(it) },
                onResolveSafetyConfirmation = { runtime.stateMachine.resolvePendingConfirmation(it) },
                onCreateReminder = { message, delayMinutes ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val triggerAt = System.currentTimeMillis() + delayMinutes.coerceAtLeast(1L) * 60_000L
                        val id = runtime.dao.insertReminder(
                            ScheduledReminderEntity(
                                triggerAtMs = triggerAt,
                                spokenMessage = message,
                                status = "pending",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        ReminderScheduler.scheduleAlarm(this@MainActivity, id, triggerAt, message)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Reminder set", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSnoozeReminder = { id, addMinutes ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val rem = runtime.dao.getReminderById(id) ?: return@launch
                        val base = maxOf(System.currentTimeMillis(), rem.triggerAtMs)
                        val newTrigger = base + addMinutes.coerceAtLeast(1L) * 60_000L
                        runtime.dao.insertReminder(rem.copy(triggerAtMs = newTrigger, status = "pending"))
                        ReminderScheduler.scheduleAlarm(this@MainActivity, rem.id, newTrigger, rem.spokenMessage)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Snoozed +${addMinutes}m", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDeleteReminder = { id ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        ReminderScheduler.cancelAlarm(this@MainActivity, id)
                        runtime.dao.deleteReminderById(id)
                    }
                },
                onSignInWithGoogle = {
                    oauthSignInJob?.cancel()
                    credentialsState = credentialsState.copy(
                        oauthInProgress = true,
                        oauthStatusMessage = "Opening Google Sign-In in Chrome..."
                    )
                    oauthSignInJob = lifecycleScope.launch {
                        val outcome = AntigravityOAuthManager.launchAndAwaitGoogleSignIn(
                            context = this@MainActivity,
                            oauthPool = runtime.oauthPool
                        )
                        credentialsState = credentialsState.copy(
                            oauthInProgress = false,
                            oauthStatusMessage = outcome.message
                        )
                        refreshCredentialsState()
                        Toast.makeText(this@MainActivity, outcome.message.ifBlank { if (outcome.ok) "Signed in!" else "Sign-in failed" }, Toast.LENGTH_LONG).show()
                    }
                },
                onManualOAuthCallback = { rawInput ->
                    credentialsState = credentialsState.copy(
                        oauthInProgress = true,
                        oauthStatusMessage = "Exchanging authorization code..."
                    )
                    lifecycleScope.launch {
                        val outcome = AntigravityOAuthManager.completeManualFallback(
                            context = this@MainActivity,
                            rawInput = rawInput,
                            oauthPool = runtime.oauthPool
                        )
                        credentialsState = credentialsState.copy(
                            oauthInProgress = false,
                            oauthStatusMessage = outcome.message
                        )
                        refreshCredentialsState()
                        Toast.makeText(this@MainActivity, outcome.message, Toast.LENGTH_LONG).show()
                    }
                },
                onRemoveGoogleAccount = { email ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        SecretsProvider.removeAntigravityAccount(this@MainActivity, email)
                        runtime.oauthPool.reloadAccounts()
                        refreshCredentialsState()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Removed $email", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                autonomousTasks = autonomousTasks,
                autonomousLogs = autonomousLogs,
                autonomousAutoUnlock = autoUnlockState,
                onAutonomousAutoUnlockChanged = { enabled ->
                    autoUnlockState = enabled
                    SecretsProvider.setAutonomousAutoUnlockEnabled(this, enabled)
                },
                onPauseAutonomousTask = { id ->
                    lifecycleScope.launch(Dispatchers.IO) { runtime.autonomousEngine.pauseTask(id) }
                },
                onResumeAutonomousTask = { id ->
                    lifecycleScope.launch(Dispatchers.IO) { runtime.autonomousEngine.resumeTask(id) }
                },
                onStopAutonomousTask = { id ->
                    lifecycleScope.launch(Dispatchers.IO) { runtime.autonomousEngine.stopTask(id) }
                },
                onStopAllAutonomousTasks = {
                    lifecycleScope.launch(Dispatchers.IO) { runtime.autonomousEngine.stopAllTasks() }
                },
                onRunAutonomousTaskNow = { id ->
                    runtime.autonomousEngine.triggerTaskNow(id)
                },
                onDeleteAutonomousTask = { id ->
                    lifecycleScope.launch(Dispatchers.IO) { runtime.autonomousEngine.deleteTask(id) }
                }
            )
        }

        refreshPermissionsAndStart()
        handleIncomingShareIntent(intent)
        lifecycleScope.launch(Dispatchers.IO) {
            delay(800L)
            refreshCredentialsState()
        }
    }

    @Composable
    private fun <T> Flow<T>.collectWhileStarted(initial: T): State<T> =
        produceState(initialValue = initial, this, this@MainActivity.lifecycle) {
            this@MainActivity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { value = it }
            }
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val mime = incoming.type.orEmpty()
        if (mime.startsWith("image/")) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            encodeSelectedImage(streamUri, "Shared Image")
        } else if (mime == "text/plain") {
            val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (sharedText.isNotBlank()) {
                runtime.stateMachine.submitTextTurn(sharedText)
            }
        }
        incoming.action = null
    }

    private fun encodeSelectedImage(uri: Uri?, sourceLabel: String) {
        uri ?: return
        lifecycleScope.launch {
            val encoded = ImageAttachmentEncoder.encodeUri(this@MainActivity, uri)
            if (encoded != null) {
                pendingMediaState = PendingMediaUi(
                    label = "$sourceLabel (${encoded.width}x${encoded.height})",
                    base64 = encoded.base64,
                    mimeType = encoded.mimeType
                )
            } else {
                Toast.makeText(this@MainActivity, "Could not read selected image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCameraCapture() {
        runCatching {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("sarah_photo_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingCameraUri = uri
            takePictureLauncher.launch(uri)
        }.onFailure {
            Toast.makeText(this, "Camera unavailable: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        oauthSignInJob?.cancel()
        AntigravityOAuthManager.cancelActiveFlow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsAndStart()
        lifecycleScope.launch(Dispatchers.IO) {
            refreshCredentialsState()
        }
    }

    private fun refreshCredentialsState() {
        val acc = SecretsProvider.getUserAntigravityAccount(this)
        credentialsState = credentialsState.copy(
            deepgramKey = SecretsProvider.getUserDeepgramKey(this),
            deepgramConfigured = SecretsProvider.deepgramApiKey().isNotBlank(),
            mercuryKey = SecretsProvider.getUserInceptionKey(this),
            mercuryConfigured = SecretsProvider.inceptionApiKey().isNotEmpty(),
            fishOverrideKey = SecretsProvider.getUserFishKey(this),
            fishBuiltInActive = SecretsProvider.fishAudioKeys().isNotEmpty(),
            geminiEmail = acc?.email.orEmpty(),
            geminiRefreshToken = acc?.refresh_token.orEmpty(),
            geminiQuotaSummary = runtime.oauthPool.quotaSummaryText(),
            accounts = runtime.oauthPool.accountSummaries(),
            lockPinConfigured = credentialsState.lockPinConfigured
        )
    }

    private fun refreshPermissionsAndStart() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val hasOverlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(packageName)

        permissionState = PermissionSummary(
            hasMic = hasMic,
            hasNotifications = hasNotif,
            hasOverlay = hasOverlay,
            ignoringBattery = ignoringBattery,
            hasAccessibility = SaraAccessibilityService.isConnected(),
            hasNotificationListener = SaraNotificationListenerService.isConnected(this),
            shizukuRunning = ShizukuBridge.isBinderAlive(),
            shizukuGranted = ShizukuBridge.isPermissionGranted()
        )

        if (hasMic) {
            VoiceCaptureForegroundService.start(this)
        } else if (!hasAutoRequestedPermissions) {
            hasAutoRequestedPermissions = true
            requestRuntimePermissionsOnly()
        }
    }

    private fun openShizukuApp() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) {
            runCatching { startActivity(launch) }
        } else {
            Toast.makeText(this, "Shizuku app is not installed on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestRuntimePermissionsOnly() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        permLauncher.launch(perms.toTypedArray())
    }
}
