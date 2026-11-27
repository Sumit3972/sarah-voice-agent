package com.voiceagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.voiceagent.accessibility.IdleWaiter
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.asBoolOrNull
import com.voiceagent.config.asIntOrNull
import com.voiceagent.config.asStrOrNull
import com.voiceagent.memory.MemoryDao
import com.voiceagent.shizuku.ShizukuBridge
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

class DeviceIntentTool(
    private val appContext: Context,
    private val dao: MemoryDao
) {
    companion object {
        private val APP_PACKAGE_ALIASES = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "youtube" to "com.google.android.youtube",
            "youtube music" to "com.google.android.apps.youtube.music",
            "yt music" to "com.google.android.apps.youtube.music",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "telegram" to "org.telegram.messenger",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "camera" to "com.android.camera",
            "settings" to "com.android.settings",
            "messages" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer"
        )
    }

    @Volatile
    private var lastTorchOn = false

    suspend fun execute(args: JsonObject): String {
        val action = args["action"].asStrOrNull()?.trim()?.lowercase().orEmpty()
        val target = args["target"].asStrOrNull()?.trim().orEmpty()
        val extraText = args["text"].asStrOrNull()?.trim().orEmpty()
        val pinArg = args["pin"].asStrOrNull()?.trim().orEmpty()
        val hour = args["hour"].asIntOrNull()
        val minute = args["minute"].asIntOrNull()
        val seconds = args["seconds"].asIntOrNull()
        val value = args["value"].asIntOrNull()
        val enabled = args["enabled"].asBoolOrNull()

        return try {
            when (action) {
                "open_app" -> openApp(target)
                "unlock_device", "unlock_phone", "unlock_screen", "unlock" ->
                    unlockDevice(pinArg.ifBlank { extraText.ifBlank { target } })
                "close_all_apps", "clear_background_apps", "close_them_all" -> closeAllBackgroundApps()
                "force_stop_app", "kill_app" -> forceStopSpecificApp(target)
                "clear_app_cache" -> clearAppCache(target)
                "show_recents", "open_recents" -> showRecentsAndVerify()
                "system_toggle" -> systemToggle(target, enabled, value)
                "take_screenshot" -> takeScreenshotToGallery()
                "screen_on", "wake_screen" -> wakeScreenOn()
                "screen_off", "lock_screen", "turn_off_screen" -> sleepScreenOff()
                "open_notifications", "open_notification_shade", "notification_shade" -> openNotificationsShade()
                "close_app" -> closeAppSmart(target)
                "go_home" -> goHomeAndVerify()
                "go_back" -> goBackAndObserve()
                "set_alarm" -> setAlarm(hour ?: 7, minute ?: 0, extraText.ifBlank { target })
                "set_timer" -> setTimer(seconds ?: ((value ?: 5) * 60), extraText.ifBlank { target })
                "place_call" -> placeCall(target)
                "open_whatsapp_chat" -> openWhatsAppChat(target, extraText)
                "play_media_query" -> playMediaQuery(target.ifBlank { extraText })
                "navigate_maps" -> navigateMaps(target.ifBlank { extraText })
                "open_camera" -> openCamera(target)
                "open_url" -> openUrl(target.ifBlank { extraText })
                "share_text" -> shareText(extraText.ifBlank { target })
                "media_control" -> mediaControl(target.ifBlank { extraText })
                "set_volume" -> setVolume(target, value ?: 50)
                "set_brightness" -> setBrightness(value ?: 60)
                "toggle_torch" -> toggleTorch(enabled ?: !lastTorchOn)
                "set_clipboard" -> setClipboard(extraText.ifBlank { target })
                "open_settings_panel" -> openSettingsPanel(target)
                else -> err("Unsupported device_intent action='$action'")
            }
        } catch (e: Exception) {
            Log.e(PipelineConfig.LOG_TAG, "[DeviceIntentTool] action=$action failed: ${e.message}", e)
            err("Action $action failed: ${e.message}")
        }
    }

    private suspend fun closeAppSmart(target: String): String {
        val tLower = target.lowercase(Locale.ROOT).trim()
        if (tLower in setOf("all", "them all", "everything", "all apps", "background apps")) {
            return closeAllBackgroundApps()
        }
        if (tLower.isNotBlank() && tLower !in setOf("this", "current", "app", "this app", "it")) {
            if (tLower.contains("sarah") || tLower.contains("voiceagent")) {
                return err("Refused: Sarah cannot close herself.")
            }
            if (ShizukuBridge.isPermissionGranted()) {
                return forceStopSpecificApp(target)
            }
        }
        return goHomeAndVerify()
    }

    private fun resolveTargetPackage(query: String): Pair<String, String>? {
        if (query.isBlank()) return null
        val pm = appContext.packageManager
        val qLower = query.lowercase(Locale.ROOT).trim()

        if (qLower.contains('.') && !qLower.contains(' ')) {
            val ai = runCatching { pm.getApplicationInfo(query, 0) }.getOrNull()
            if (ai != null) {
                return query to pm.getApplicationLabel(ai).toString()
            }
        }

        val aliasPkg = APP_PACKAGE_ALIASES[qLower]
        if (aliasPkg != null) {
            val ai = runCatching { pm.getApplicationInfo(aliasPkg, 0) }.getOrNull()
            if (ai != null) {
                return aliasPkg to pm.getApplicationLabel(ai).toString()
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(mainIntent, 0)
        val bestMatch = activities.firstOrNull {
            it.loadLabel(pm).toString().equals(query, ignoreCase = true)
        } ?: activities.firstOrNull {
            val lbl = it.loadLabel(pm).toString().lowercase(Locale.ROOT)
            lbl.contains(qLower) || qLower.contains(lbl)
        }
        if (bestMatch != null) {
            return bestMatch.activityInfo.packageName to bestMatch.loadLabel(pm).toString()
        }

        val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val appMatch = installed.firstOrNull { ai ->
            pm.getApplicationLabel(ai).toString().equals(query, ignoreCase = true)
        } ?: installed.firstOrNull { ai ->
            val lbl = pm.getApplicationLabel(ai).toString().lowercase(Locale.ROOT)
            lbl.contains(qLower) || (lbl.length >= 3 && qLower.contains(lbl))
        }
        if (appMatch != null) {
            return appMatch.packageName to pm.getApplicationLabel(appMatch).toString()
        }
        return null
    }

    private suspend fun openApp(query: String): String {
        if (query.isBlank()) return err("App name or package target is required")
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            val savedPin = resolveSavedLockPin("")
            if (savedPin.isNullOrBlank()) {
                return buildJsonObject {
                    put("ok", false)
                    put("device_locked", true)
                    put("pin_known", false)
                    put("error", "Phone is locked and no screen lock PIN is saved. Ask the user for their PIN first.")
                }.toString()
            }
            val unlockRes = unlockDevice(savedPin)
            if (unlockRes.contains("\"ok\":false")) {
                return unlockRes
            }
        }
        val pm = appContext.packageManager
        val qLower = query.lowercase(Locale.ROOT).trim()

        val resolved = resolveTargetPackage(query)
        if (resolved != null) {
            val (pkg, appName) = resolved
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                return launchAndObservePackage(launch, pkg, appName)
            }
        }

        if (qLower == "settings") {
            return launchAndObservePackage(Intent(Settings.ACTION_SETTINGS), "com.android.settings", "Settings")
        }

        return err("Could not find installed launchable app matching '$query'")
    }

    private suspend fun resolveSavedLockPin(explicitRaw: String): String? {
        val explicitDigits = explicitRaw.filter { it.isDigit() }
        if (explicitDigits.length in 4..8) return explicitDigits

        val pinned = runCatching { dao.getPinnedFacts() }.getOrDefault(emptyList())
        val pinFact = pinned.firstOrNull {
            it.factKey.equals("user.phone_lock_pin", ignoreCase = true) ||
                it.factKey.contains("lock_pin", ignoreCase = true) ||
                it.factKey.contains("phone_pin", ignoreCase = true)
        } ?: runCatching { dao.searchFactsLike("lock_pin", limit = 3) }.getOrDefault(emptyList()).firstOrNull()
            ?: runCatching { dao.searchFactsLike("pin", limit = 5) }.getOrDefault(emptyList())
                .firstOrNull { it.factKey.contains("lock", ignoreCase = true) || it.factKey.contains("pin", ignoreCase = true) }

        val storedDigits = pinFact?.factValue?.filter { it.isDigit() }.orEmpty()
        return if (storedDigits.length in 4..8) storedDigits else null
    }

    private suspend fun unlockDevice(rawPin: String): String {
        val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == false) {
            return ok("Phone is already unlocked", mapOf("unlocked" to "true"))
        }

        val pin = resolveSavedLockPin(rawPin)
            ?: return buildJsonObject {
                put("ok", false)
                put("device_locked", true)
                put("pin_known", false)
                put("error", "Phone is locked and no lock PIN is known. Ask the user for their PIN before attempting to unlock.")
            }.toString()

        val shizukuOk = ShizukuBridge.isPermissionGranted()
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive && shizukuOk) {
            ShizukuBridge.inputKeyEvent(KeyEvent.KEYCODE_WAKEUP)
            delay(200L)
        }

        val svc = SaraAccessibilityService.instance
        val statusBefore = svc?.readKeyguardStatusText().orEmpty()
        if (containsLockoutCountdown(statusBefore)) {
            return err("Device is temporarily locked: $statusBefore. Wait until the countdown finishes.")
        }

        var digitCoords = svc?.findKeyguardPinDigitCoords().orEmpty()
        if (digitCoords.size < 10) {
            if (shizukuOk) {
                ShizukuBridge.execShell("cmd statusbar collapse; input swipe 540 1850 540 500 200", 2500L)
            } else {
                svc?.swipe(540, 1850, 540, 500, 200L)
            }
            delay(260L)
            digitCoords = svc?.findKeyguardPinDigitCoords().orEmpty()
            if (digitCoords.size < 10 && shizukuOk) {
                digitCoords = fetchPinDigitCoordsViaUiautomator()
            }
        }

        val statusAfterSwipe = svc?.readKeyguardStatusText().orEmpty()
        if (containsLockoutCountdown(statusAfterSwipe)) {
            return err("Device is temporarily locked: $statusAfterSwipe. Wait until the countdown finishes.")
        }

        val missingDigit = pin.any { it !in digitCoords }
        if (!missingDigit) {
            if (shizukuOk) {
                val tapScript = pin.map { ch ->
                    val (cx, cy) = digitCoords.getValue(ch)
                    "input tap $cx $cy"
                }.joinToString("; sleep 0.08; ")
                ShizukuBridge.execShell(tapScript, 3500L)
            } else if (svc != null) {
                for (ch in pin) {
                    val (cx, cy) = digitCoords.getValue(ch)
                    svc.tapXy(cx, cy, longPress = false)
                    delay(90L)
                }
            }
        } else if (shizukuOk) {
            ShizukuBridge.execShell("input text $pin && input keyevent 66", 3500L)
        } else {
            return err("PIN keypad could not be read on screen. Enable Accessibility/Shizuku or unlock manually.")
        }

        var sentEnter = false
        for (attempt in 0 until 8) {
            delay(100L)
            if (km?.isKeyguardLocked == false) break
            if (!sentEnter && attempt >= 3 && shizukuOk) {
                sentEnter = true
                ShizukuBridge.inputKeyEvent(KeyEvent.KEYCODE_ENTER)
            }
        }

        val unlocked = km?.isKeyguardLocked == false
        if (unlocked) {
            IdleWaiter.waitForSettle(quietWindowMs = 200L, timeoutMs = 800L)
            val fg = svc?.currentPackageName() ?: "unknown"
            return ok("Unlocked the phone", mapOf("unlocked" to "true", "foreground_package" to fg))
        }

        val failStatus = svc?.readKeyguardStatusText().orEmpty().ifBlank { "PIN keypad still locked" }
        return err("Could not unlock phone ($failStatus). Stopped immediately to prevent lockout.")
    }

    private fun containsLockoutCountdown(text: String): Boolean {
        if (text.isBlank()) return false
        return Regex("(try again in|locked for|wait)\\s*\\d+|too many incorrect", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    private suspend fun fetchPinDigitCoordsViaUiautomator(): Map<Char, Pair<Int, Int>> {
        val res = ShizukuBridge.execShell(
            "uiautomator dump /sdcard/sara_pin.xml >/dev/null 2>&1 && cat /sdcard/sara_pin.xml; rm -f /sdcard/sara_pin.xml",
            4500L
        )
        if (!res.ok || res.stdout.isBlank()) return emptyMap()
        val map = mutableMapOf<Char, Pair<Int, Int>>()
        val nodeRegex = Regex("""content-desc="([0-9])"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"|bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="([0-9])"""")
        for (m in nodeRegex.findAll(res.stdout)) {
            val digitStr: String
            val x1: Int; val y1: Int; val x2: Int; val y2: Int
            if (m.groupValues[1].isNotEmpty()) {
                digitStr = m.groupValues[1]
                x1 = m.groupValues[2].toInt(); y1 = m.groupValues[3].toInt()
                x2 = m.groupValues[4].toInt(); y2 = m.groupValues[5].toInt()
            } else {
                digitStr = m.groupValues[10]
                x1 = m.groupValues[6].toInt(); y1 = m.groupValues[7].toInt()
                x2 = m.groupValues[8].toInt(); y2 = m.groupValues[9].toInt()
            }
            if (digitStr.length == 1) {
                map[digitStr[0]] = ((x1 + x2) / 2) to ((y1 + y2) / 2)
            }
        }
        return map
    }

    private suspend fun closeAllBackgroundApps(): String {
        val (shizukuOk, stopped) = ShizukuBridge.forceStopAllUserApps(appContext)
        if (!shizukuOk) {
            return err("Shizuku permission is required to close all background apps safely without closing Sarah.")
        }
        val svc = SaraAccessibilityService.instance
        svc?.performGlobalCommand("home")
        launchIntent(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
        )
        IdleWaiter.waitForSettle(quietWindowMs = 200L, timeoutMs = 800L)
        val fgPkg = svc?.currentPackageName() ?: "unknown"
        val names = stopped.joinToString(", ") { it.appName }.ifBlank { "none" }
        return ok(
            "Closed ${stopped.size} background app(s) while keeping Sarah active",
            mapOf(
                "closed_count" to stopped.size.toString(),
                "closed_apps" to names,
                "protected_self" to "Sarah (${appContext.packageName}) kept alive",
                "foreground_package" to fgPkg
            )
        )
    }

    private suspend fun forceStopSpecificApp(target: String): String {
        if (target.isBlank()) return err("App name or package is required to force-stop")
        val tLower = target.lowercase(Locale.ROOT).trim()
        if (tLower.contains("sarah") || tLower.contains("voiceagent") || tLower.contains("shizuku")) {
            return err("Refused: cannot force-stop protected assistant or Shizuku service")
        }
        val (pkg, appName) = resolveTargetPackage(target)
            ?: return err("Could not find installed app matching '$target'")
        if (ShizukuBridge.isProtectedPackage(appContext, pkg)) {
            return err("Refused: $appName ($pkg) is a protected system/assistant package")
        }
        val svc = SaraAccessibilityService.instance
        val wasForeground = svc?.currentPackageName() == pkg
        val res = ShizukuBridge.forceStopPackage(appContext, pkg)
        if (!res.ok) {
            return err(res.stderr.ifBlank { res.stdout.ifBlank { "Force-stop failed for $appName ($pkg). Ensure Shizuku is granted." } })
        }
        if (wasForeground) {
            svc.performGlobalCommand("home")
            IdleWaiter.waitForSettle(quietWindowMs = 180L, timeoutMs = 700L)
        }
        val fgAfter = svc?.currentPackageName() ?: "unknown"
        return ok(
            "Force-stopped $appName",
            mapOf(
                "package" to pkg,
                "app_name" to appName,
                "foreground_package" to fgAfter
            )
        )
    }

    private suspend fun clearAppCache(target: String): String {
        if (target.isBlank()) return err("App name or package is required to clear cache")
        val (pkg, appName) = resolveTargetPackage(target)
            ?: return err("Could not find installed app matching '$target'")
        val res = ShizukuBridge.clearAppCache(appContext, pkg)
        if (!res.ok) {
            return err(res.stderr.ifBlank { res.stdout.ifBlank { "Failed to clear cache for $appName ($pkg)" } })
        }
        return ok("Cleared cache for $appName", mapOf("package" to pkg))
    }

    private suspend fun showRecentsAndVerify(): String {
        val svc = SaraAccessibilityService.instance
        val fgBefore = svc?.currentPackageName() ?: "unknown"
        val triggered = svc?.performGlobalCommand("recents") ?: false
        delay(350L)
        val fgMid = svc?.currentPackageName() ?: "unknown"
        if ((!triggered || fgMid == fgBefore) && ShizukuBridge.isPermissionGranted()) {
            ShizukuBridge.inputKeyEvent(KeyEvent.KEYCODE_APP_SWITCH)
            delay(250L)
        }
        IdleWaiter.waitForSettle(quietWindowMs = 200L, timeoutMs = 900L)
        val snap = svc?.observeTree()
        val fgAfter = snap?.packageName ?: fgMid
        return ok(
            "Opened recent apps overview",
            mapOf(
                "fg_before" to fgBefore,
                "fg_after" to fgAfter,
                "note" to "Never tap 'Clear all' in Recents UI; use device_intent(action='close_all_apps') to close apps safely without closing Sarah.",
                "ui_tree" to (snap?.treeText ?: "")
            )
        )
    }

    private suspend fun systemToggle(target: String, enabled: Boolean?, value: Int?): String {
        if (target.isBlank()) {
            return err("Toggle target is required (wifi|mobile_data|bluetooth|hotspot|location|airplane|auto_rotate|dnd|dark_mode|battery_saver|screen_timeout)")
        }
        val state = enabled ?: true
        val res = ShizukuBridge.setSystemToggle(target, state, value)
        if (!res.ok) {
            return err(res.stderr.ifBlank { res.stdout.ifBlank { "System toggle '$target' failed via Shizuku" } })
        }
        val summary = if (target.equals("screen_timeout", true)) {
            "Set screen timeout to ${(value ?: 60).coerceIn(15, 1800)} seconds"
        } else {
            "Turned $target ${if (state) "on" else "off"}"
        }
        return ok(summary, mapOf("toggle" to target, "enabled" to state.toString()))
    }

    private suspend fun takeScreenshotToGallery(): String {
        val (okFlag, detail) = ShizukuBridge.captureScreenshotToGallery(appContext)
        return if (okFlag) {
            ok("Saved screenshot to Gallery", mapOf("file_path" to detail))
        } else {
            err(detail)
        }
    }

    private suspend fun wakeScreenOn(): String {
        if (!ShizukuBridge.isPermissionGranted()) {
            return err("Shizuku permission required to wake the screen")
        }
        val res = ShizukuBridge.inputKeyEvent(KeyEvent.KEYCODE_WAKEUP)
        return if (res.ok) {
            val snap = SaraAccessibilityService.instance?.observeTree()
            ok("Woke the screen up", mapOf("package" to (snap?.packageName ?: "unknown"), "ui_tree" to (snap?.treeText ?: "")))
        } else {
            err("Failed to wake screen: ${res.stderr}")
        }
    }

    private suspend fun sleepScreenOff(): String {
        if (!ShizukuBridge.isPermissionGranted()) {
            return err("Shizuku permission required to turn the screen off")
        }
        val res = ShizukuBridge.inputKeyEvent(KeyEvent.KEYCODE_SLEEP)
        return if (res.ok) {
            ok("Turned the screen off")
        } else {
            err("Failed to turn off screen: ${res.stderr}")
        }
    }

    private suspend fun openNotificationsShade(): String {
        val svc = SaraAccessibilityService.instance
            ?: return err("Accessibility service not connected to open notifications shade")
        svc.performGlobalCommand("notifications")
        IdleWaiter.waitForSettle(quietWindowMs = 200L, timeoutMs = 800L)
        val snap = svc.observeTree()
        return ok(
            "Opened notification shade",
            mapOf("package" to snap.packageName, "ui_tree" to snap.treeText)
        )
    }

    private suspend fun launchAndObservePackage(intent: Intent, expectedPkg: String, displayName: String): String {
        val svc = SaraAccessibilityService.instance
        val previousPkg = svc?.currentPackageName()
        launchIntent(intent)

        if (svc == null) {
            return ok("Opened $displayName", mapOf("package" to expectedPkg))
        }

        val startMs = System.currentTimeMillis()
        while ((System.currentTimeMillis() - startMs) < 2000L) {
            val cur = svc.currentPackageName()
            if (cur == expectedPkg || (cur != previousPkg && cur != "unknown" && cur != "com.android.systemui")) {
                break
            }
            delay(120L)
        }
        IdleWaiter.waitForSettle(quietWindowMs = 220L, timeoutMs = 900L)
        var snap = svc.observeTree()
        // Wait past initial splash screens (e.g. React Native / Flutter apps with <=5 placeholder nodes)
        if (snap.nodes.size <= 5 && snap.packageName == expectedPkg) {
            val splashStartMs = System.currentTimeMillis()
            while ((System.currentTimeMillis() - splashStartMs) < 2200L) {
                delay(300L)
                val nextSnap = svc.observeTree()
                snap = nextSnap
                if (nextSnap.nodes.size > 5 || nextSnap.packageName != expectedPkg) {
                    break
                }
            }
        }
        return ok(
            "Opened $displayName",
            mapOf(
                "package" to snap.packageName,
                "app_name" to displayName,
                "ui_tree" to snap.treeText
            )
        )
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        launchIntent(intent)
        return ok("Set alarm for %02d:%02d".format(h, m))
    }

    private fun setTimer(totalSeconds: Int, label: String): String {
        val secs = totalSeconds.coerceIn(1, 86_400)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, secs)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        launchIntent(intent)
        return ok("Started ${secs}-second timer")
    }

    @SuppressLint("MissingPermission")
    private suspend fun placeCall(target: String): String {
        if (target.isBlank()) return err("Phone number or contact name is required")
        val phone = resolvePhoneNumber(target)
            ?: return err("Could not resolve a phone number for '$target'. Check Contacts or ask user for the number.")
        val hasCallPerm = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        launchIntent(Intent(action, Uri.parse("tel:${Uri.encode(phone)}")))
        return ok(if (hasCallPerm) "Calling $target ($phone)" else "Opened dialer with $phone")
    }

    private suspend fun openWhatsAppChat(target: String, message: String): String {
        if (target.isBlank()) return err("Recipient phone number or contact name is required")
        val resolved = resolvePhoneNumber(target)
            ?: return err("No phone number found for '$target'. Ask the user or use observe_screen + ui_act inside WhatsApp.")
        val cleaned = resolved.replace(Regex("[^0-9+]"), "")
        val waDigits = cleaned.removePrefix("+").let { digits ->
            if (digits.length == 10) "91$digits" else digits
        }
        val url = buildString {
            append("https://wa.me/").append(waDigits)
            if (message.isNotBlank()) {
                append("?text=").append(Uri.encode(message))
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pm = appContext.packageManager
        val waPkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { pkg ->
            pm.getLaunchIntentForPackage(pkg) != null
        }
        if (waPkg != null) intent.setPackage(waPkg)
        launchIntent(intent)
        return ok("Opened WhatsApp chat for $target", mapOf("phone" to waDigits, "prefilled_message" to message, "note" to "If the message draft needs final Send tap, use observe_screen/ui_act to tap Send."))
    }

    private suspend fun goHomeAndVerify(): String {
        val svc = SaraAccessibilityService.instance
        svc?.performGlobalCommand("home")
        launchIntent(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
        )
        if (svc != null) {
            IdleWaiter.waitForSettle(quietWindowMs = 200L, timeoutMs = 900L)
            val snap = svc.observeTree()
            return ok(
                "Returned to home screen",
                mapOf("package" to snap.packageName, "ui_tree" to snap.treeText)
            )
        }
        return ok("Returned to home screen")
    }

    private suspend fun goBackAndObserve(): String {
        val svc = SaraAccessibilityService.instance
            ?: return err("Accessibility service not connected for global back")
        svc.performGlobalCommand("back")
        IdleWaiter.waitForSettle(quietWindowMs = 200L, timeoutMs = 900L)
        val snap = svc.observeTree()
        return ok(
            "Went back",
            mapOf("package" to snap.packageName, "ui_tree" to snap.treeText)
        )
    }

    private suspend fun playMediaQuery(query: String): String {
        if (query.isBlank()) return err("Media search query is required")
        val pm = appContext.packageManager
        val ytMusicPkg = "com.google.android.apps.youtube.music"
        val spotifyPkg = "com.spotify.music"
        val ytPkg = "com.google.android.youtube"

        val lower = query.lowercase(Locale.ROOT)
        val preferYoutubeVideo = lower.contains("youtube") || lower.contains("video") || lower.contains("movie")

        if (!preferYoutubeVideo && pm.getLaunchIntentForPackage(spotifyPkg) != null && lower.contains("spotify")) {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(spotifyPkg)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra("query", query)
            }
            launchIntent(intent)
            return ok("Searching and playing '$query' on Spotify")
        }

        val targetPkg = when {
            !preferYoutubeVideo && pm.getLaunchIntentForPackage(ytMusicPkg) != null && lower.contains("music") -> ytMusicPkg
            pm.getLaunchIntentForPackage(ytPkg) != null -> ytPkg
            pm.getLaunchIntentForPackage(spotifyPkg) != null -> spotifyPkg
            else -> null
        }

        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            if (targetPkg != null) setPackage(targetPkg)
            putExtra("query", query)
        }
        if (searchIntent.resolveActivity(pm) != null) {
            val svc = SaraAccessibilityService.instance
            launchIntent(searchIntent)
            if (svc != null && targetPkg == ytPkg) {
                val startMs = System.currentTimeMillis()
                while ((System.currentTimeMillis() - startMs) < 2000L) {
                    if (svc.currentPackageName() == ytPkg) break
                    delay(120L)
                }
                IdleWaiter.waitForSettle(quietWindowMs = 280L, timeoutMs = 1100L)
                val snap = svc.observeTree(query)
                return ok(
                    "Opened YouTube search results for '$query'",
                    mapOf(
                        "package" to snap.packageName,
                        "next_step" to "Call ui_act with tap_ref on the first matching video result in [MAIN] to start playback.",
                        "ui_tree" to snap.treeText
                    )
                )
            }
            return ok("Searching '$query'", mapOf("package" to (targetPkg ?: "default")))
        } else {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
            launchIntent(web)
            return ok("Opened YouTube search for '$query'")
        }
    }

    private fun navigateMaps(destination: String): String {
        if (destination.isBlank()) return err("Destination is required")
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
        if (appContext.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") != null) {
            navIntent.setPackage("com.google.android.apps.maps")
        }
        launchIntent(navIntent)
        return ok("Started navigation to $destination")
    }

    private fun openCamera(mode: String): String {
        val action = when (mode.lowercase(Locale.ROOT)) {
            "video" -> MediaStore.INTENT_ACTION_VIDEO_CAMERA
            else -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        val intent = Intent(action)
        if (mode.equals("selfie", true)) {
            intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            intent.putExtra("com.google.assistant.extra.USE_FRONT_CAMERA", true)
        }
        launchIntent(intent)
        return ok("Opened Camera ($mode)")
    }

    private fun openUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return err("URL is required")
        val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
        launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return ok("Opened $url")
    }

    private fun shareText(text: String): String {
        if (text.isBlank()) return err("Text to share is required")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        launchIntent(Intent.createChooser(send, "Share via"))
        return ok("Opened share sheet")
    }

    private fun mediaControl(cmd: String): String {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val norm = cmd.lowercase(Locale.ROOT).trim()
        if (norm == "mute") {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            return ok("Muted media volume")
        }
        if (norm == "unmute") {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            return ok("Unmuted media volume")
        }
        val keyCode = when (norm) {
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return ok("Dispatched media control: $cmd")
    }

    private fun setVolume(streamName: String, percent: Int): String {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName.lowercase(Locale.ROOT)) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val pct = percent.coerceIn(0, 100)
        val max = am.getStreamMaxVolume(stream)
        val targetLevel = ((max * pct) / 100.0).roundToInt().coerceIn(0, max)
        am.setStreamVolume(stream, targetLevel, 0)
        return ok("Set ${streamName.ifBlank { "media" }} volume to $pct percent")
    }

    private suspend fun setBrightness(percent: Int): String {
        val pct = percent.coerceIn(5, 100)
        val raw = ((pct * 255) / 100.0).roundToInt().coerceIn(8, 255)
        if (!Settings.System.canWrite(appContext)) {
            if (ShizukuBridge.isPermissionGranted()) {
                val res = ShizukuBridge.execShell(
                    "settings put system screen_brightness_mode 0 && settings put system screen_brightness $raw"
                )
                if (res.ok) {
                    return ok("Set screen brightness to $pct percent")
                }
            }
            launchIntent(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            return ok("Opened Display settings because Modify System Settings permission is not granted for direct brightness write")
        }
        Settings.System.putInt(
            appContext.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        return ok("Set screen brightness to $pct percent")
    }

    private fun toggleTorch(turnOn: Boolean): String {
        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull { id ->
            val chars = cm.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return err("No camera flashlight found on device")
        cm.setTorchMode(camId, turnOn)
        lastTorchOn = turnOn
        return ok(if (turnOn) "Turned flashlight on" else "Turned flashlight off")
    }

    private fun setClipboard(text: String): String {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sara_clip", text))
        return ok("Copied text to clipboard")
    }

    private fun openSettingsPanel(panel: String): String {
        val action = when (panel.lowercase(Locale.ROOT)) {
            "wifi", "internet" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            } else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "volume", "sound" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_VOLUME
            } else Settings.ACTION_SOUND_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications" -> "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launchIntent(Intent(action))
        return ok("Opened settings panel: $panel")
    }

    private suspend fun resolvePhoneNumber(target: String): String? {
        val digitsCount = target.count { it.isDigit() }
        if (digitsCount >= 6 && target.none { it.isLetter() }) {
            return target.filter { it.isDigit() || it == '+' }
        }
        // Check saved memory_facts contact aliases first
        val aliasMatches = dao.searchFactsLike(target, category = "contact_alias", limit = 3)
        for (fact in aliasMatches) {
            val foundNum = Regex("\\+?[0-9][0-9\\s-]{5,}").find(fact.factValue)?.value
            if (!foundNum.isNullOrBlank()) {
                return foundNum.replace(Regex("[\\s-]"), "")
            }
        }
        // Query ContactsContract if READ_CONTACTS is granted
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$target%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(1)?.trim()
                    if (!number.isNullOrBlank()) return number.replace(Regex("[\\s-]"), "")
                }
            }
        }
        return null
    }

    private fun launchIntent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun ok(message: String, extras: Map<String, String> = emptyMap()): String = buildJsonObject {
        put("ok", true)
        put("message", message)
        extras.forEach { (k, v) -> put(k, v) }
    }.toString()

    private fun err(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()
}
