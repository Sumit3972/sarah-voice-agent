package com.voiceagent.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.debug.ToolTraceLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ShizukuExecResult(
    val ok: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

data class RunningAppEntry(
    val packageName: String,
    val appName: String
)

object ShizukuBridge {
    private const val REQ_CODE_SHIZUKU = 1001
    private val VALID_PKG_REGEX = Regex("^[a-zA-Z0-9_.]+$")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val destructivePatternRegexes = listOf(
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
        Regex("\\bam\\s+kill-all\\b")
    )
    private val selfHarmPatternRegex = Regex(
        "(com\\.voiceagent|moe\\.shizuku\\.privileged\\.api).*(force-stop|\\bkill\\b|pm\\s+(clear|uninstall|disable|hide|revoke)|\\brm\\b)|" +
            "(force-stop|\\bkill\\b|pm\\s+(clear|uninstall|disable|hide|revoke)|\\brm\\b).*(com\\.voiceagent|moe\\.shizuku\\.privileged\\.api)"
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var permissionRequestedAtMs = 0L

    private val binderListener = Shizuku.OnBinderReceivedListener {
        Log.i(PipelineConfig.LOG_TAG, "[Shizuku] Binder received (granted=${isPermissionGranted()})")
        if (isPermissionGranted()) {
            scope.launch { appContext?.let { autoHealOwnPermissions(it) } }
        } else {
            requestPermissionIfNeeded()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(PipelineConfig.LOG_TAG, "[Shizuku] Binder died")
    }

    private val permResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_CODE_SHIZUKU) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(PipelineConfig.LOG_TAG, "[Shizuku] Permission result: granted=$granted")
            if (granted) {
                scope.launch { appContext?.let { autoHealOwnPermissions(it) } }
            }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permResultListener)
        }.onFailure {
            Log.w(PipelineConfig.LOG_TAG, "[Shizuku] Listener init skipped: ${it.message}")
        }
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean {
        if (!isBinderAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermissionIfNeeded(): Boolean {
        if (!isBinderAlive()) return false
        if (isPermissionGranted()) return true
        val now = System.currentTimeMillis()
        if (now - permissionRequestedAtMs < 5000L) return false
        permissionRequestedAtMs = now
        Handler(Looper.getMainLooper()).post {
            runCatching {
                if (Shizuku.shouldShowRequestPermissionRationale() || true) {
                    Shizuku.requestPermission(REQ_CODE_SHIZUKU)
                }
            }.onFailure {
                Log.w(PipelineConfig.LOG_TAG, "[Shizuku] requestPermission error: ${it.message}")
            }
        }
        return false
    }

    suspend fun execShell(command: String, timeoutMs: Long = 4500L): ShizukuExecResult = withContext(Dispatchers.IO) {
        val cleanCmd = command.trim()
        val lower = cleanCmd.lowercase()
        if (cleanCmd.isBlank()) {
            return@withContext ShizukuExecResult(false, -1, "", "Empty command")
        }
        if (destructivePatternRegexes.any { it.containsMatchIn(lower) } || selfHarmPatternRegex.containsMatchIn(lower)) {
            ToolTraceLogger.recordException(stage = "shizuku", component = "safety_guard", error = null, message = "Blocked destructive/self-harm command: $cleanCmd")
            return@withContext ShizukuExecResult(false, -1, "", "Blocked destructive or self-harm shell command")
        }
        if (!isBinderAlive()) {
            return@withContext ShizukuExecResult(false, -1, "", "Shizuku daemon not running")
        }
        if (!isPermissionGranted()) {
            requestPermissionIfNeeded()
            return@withContext ShizukuExecResult(false, -1, "", "Shizuku permission not granted yet")
        }

        val t0 = System.currentTimeMillis()
        try {
            val newProcMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = newProcMethod.invoke(
                null,
                arrayOf("sh", "-c", cleanCmd),
                null,
                null
            ) as Process

            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val outThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                        lines.take(300).forEach { outBuf.appendLine(it) }
                    }
                }
            }.apply { start() }
            val errThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                        lines.take(100).forEach { errBuf.appendLine(it) }
                    }
                }
            }.apply { start() }

            var waitExitCode: Int? = null
            var waitErr: Throwable? = null
            val waitThread = Thread {
                try {
                    waitExitCode = process.waitFor()
                } catch (t: Throwable) {
                    waitErr = t
                }
            }.apply { start() }

            waitThread.join(timeoutMs)
            if (waitThread.isAlive) {
                runCatching { process.destroy() }
                waitThread.join(250)
                return@withContext ShizukuExecResult(false, -1, outBuf.toString().trim(), "Command timed out after ${timeoutMs}ms")
            }
            waitErr?.let { throw it }
            outThread.join(350)
            errThread.join(350)

            val code = waitExitCode ?: process.exitValue()
            val stdout = outBuf.toString().trim()
            val stderr = errBuf.toString().trim()
            val elapsed = System.currentTimeMillis() - t0
            Log.i(PipelineConfig.LOG_TAG, "[Shizuku] ($elapsed ms, exit=$code) '$cleanCmd' -> ${stdout.take(160)}")
            ShizukuExecResult(code == 0, code, stdout, stderr)
        } catch (e: Exception) {
            ToolTraceLogger.recordException(stage = "shizuku", component = "execShell", error = e, message = e.message ?: cleanCmd)
            ShizukuExecResult(false, -1, "", e.message ?: "Shizuku execution failed")
        }
    }

    suspend fun autoHealOwnPermissions(context: Context) {
        if (!isPermissionGranted()) return
        val pkg = context.packageName
        val cmds = listOf(
            "dumpsys deviceidle whitelist +$pkg",
            "cmd activity set-standby-bucket $pkg active",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
            "pm grant $pkg android.permission.CALL_PHONE",
            "pm grant $pkg android.permission.READ_CONTACTS",
            "pm grant $pkg android.permission.READ_CALENDAR",
            "pm grant $pkg android.permission.WRITE_CALENDAR",
            "pm grant $pkg android.permission.CAMERA",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg WRITE_SETTINGS allow",
            "settings put secure enabled_accessibility_services $pkg/com.voiceagent.accessibility.SaraAccessibilityService",
            "settings put secure accessibility_enabled 1",
            "cmd notification allow_listener $pkg/com.voiceagent.notifications.SaraNotificationListenerService"
        )
        for (c in cmds) {
            execShell(c, 2500L)
        }
        Log.i(PipelineConfig.LOG_TAG, "[Shizuku] Auto-healed Sarah whitelist, accessibility, notification listener, and appops")
    }

    fun isProtectedPackage(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return true
        return pkg in buildExcludedPackages(context) || isCoreSystemUiPkg(pkg)
    }

    suspend fun getRecentRunningUserApps(context: Context): List<RunningAppEntry> {
        val res = execShell("dumpsys activity recents | grep -E 'Recent #|realActivity|baseIntent'", 3500L)
        if (!res.ok) return emptyList()

        val excluded = buildExcludedPackages(context)
        val pm = context.packageManager
        val found = linkedMapOf<String, String>()
        val pkgRegex = Regex("(?:realActivity=\\{?|cmp=)([a-zA-Z0-9_.]+)/")

        for (line in res.stdout.lineSequence()) {
            for (m in pkgRegex.findAll(line)) {
                val pkg = m.groupValues[1]
                if (pkg in excluded || isCoreSystemUiPkg(pkg)) continue
                if (!found.containsKey(pkg)) {
                    val label = resolveAppLabel(pm, pkg)
                    found[pkg] = label
                }
            }
        }
        return found.entries.map { (pkg, name) -> RunningAppEntry(pkg, name) }
    }

    suspend fun forceStopPackage(context: Context, pkg: String): ShizukuExecResult {
        if (!VALID_PKG_REGEX.matches(pkg)) {
            return ShizukuExecResult(false, -1, "", "Invalid package name '$pkg'")
        }
        if (isProtectedPackage(context, pkg)) {
            return ShizukuExecResult(false, -1, "", "Refused: $pkg is a protected system/assistant package")
        }
        return execShell("am force-stop $pkg")
    }

    suspend fun forceStopAllUserApps(context: Context): Pair<Boolean, List<RunningAppEntry>> {
        if (!isBinderAlive() || !isPermissionGranted()) {
            requestPermissionIfNeeded()
            return false to emptyList()
        }
        val running = getRecentRunningUserApps(context)
        if (running.isEmpty()) {
            return true to emptyList()
        }
        val stopped = mutableListOf<RunningAppEntry>()
        for (app in running) {
            if (isProtectedPackage(context, app.packageName)) continue
            val res = forceStopPackage(context, app.packageName)
            if (res.ok) {
                stopped += app
            }
        }
        return true to stopped
    }

    suspend fun clearAppCache(context: Context, pkg: String): ShizukuExecResult {
        if (!VALID_PKG_REGEX.matches(pkg)) {
            return ShizukuExecResult(false, -1, "", "Invalid package name '$pkg'")
        }
        if (isProtectedPackage(context, pkg)) {
            return ShizukuExecResult(false, -1, "", "Refused: cannot clear cache for protected package $pkg")
        }
        return execShell("find /sdcard/Android/data/$pkg/cache -mindepth 1 -delete 2>/dev/null; pm trim-caches 128M")
    }

    suspend fun captureScreenshotToGallery(context: Context): Pair<Boolean, String> {
        if (!isBinderAlive() || !isPermissionGranted()) {
            requestPermissionIfNeeded()
            return false to "Shizuku permission not granted for headless screenshot capture"
        }
        val path = "/sdcard/Pictures/Screenshots/Sarah_${System.currentTimeMillis()}.png"
        val res = execShell("mkdir -p /sdcard/Pictures/Screenshots && screencap -p $path", 5000L)
        if (!res.ok) {
            return false to res.stderr.ifBlank { res.stdout.ifBlank { "screencap failed" } }
        }
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(path), arrayOf("image/png"), null)
        }
        return true to path
    }

    suspend fun setSystemToggle(toggle: String, enabled: Boolean, value: Int? = null): ShizukuExecResult {
        val cmd = when (toggle.lowercase()) {
            "wifi", "wi-fi" -> if (enabled) "svc wifi enable" else "svc wifi disable"
            "mobile_data", "data", "cellular" -> if (enabled) "svc data enable" else "svc data disable"
            "bluetooth", "bt" -> if (enabled) "cmd bluetooth_manager enable" else "cmd bluetooth_manager disable"
            "hotspot", "tethering" -> if (enabled) "cmd connectivity start-tethering wifi" else "cmd connectivity stop-tethering wifi"
            "location", "gps" -> "settings put secure location_mode ${if (enabled) 3 else 0}"
            "airplane", "airplane_mode" -> "cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}"
            "auto_rotate", "rotation" -> "settings put system accelerometer_rotation ${if (enabled) 1 else 0}"
            "dnd", "do_not_disturb" -> if (enabled) "cmd notification set_dnd priority" else "cmd notification set_dnd off"
            "dark_mode", "dark", "night_mode" -> if (enabled) "cmd uimode night yes" else "cmd uimode night no"
            "battery_saver", "power_save" -> "cmd power set-mode ${if (enabled) 1 else 0}"
            "screen_timeout" -> "settings put system screen_off_timeout ${(value ?: 60).coerceIn(15, 1800) * 1000}"
            else -> return ShizukuExecResult(false, -1, "", "Unknown toggle '$toggle'")
        }
        return execShell(cmd)
    }

    suspend fun inputTap(x: Int, y: Int): ShizukuExecResult = execShell("input tap $x $y")

    suspend fun inputSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): ShizukuExecResult =
        execShell("input swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun inputKeyEvent(keyCode: Int): ShizukuExecResult = execShell("input keyevent $keyCode")

    private fun buildExcludedPackages(context: Context): Set<String> {
        val set = mutableSetOf(
            context.packageName,
            "moe.shizuku.privileged.api",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom"
        )
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName?.let { set += it }
        }
        runCatching {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage?.takeIf { it.isNotBlank() }?.let { set += it }
        }
        runCatching {
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.trim()
            if (!ime.isNullOrBlank()) set += ime
        }
        return set
    }

    private fun isCoreSystemUiPkg(pkg: String): Boolean {
        return pkg.startsWith("com.android.internal") ||
            pkg.contains("inputmethod") ||
            pkg.contains(".launcher")
    }

    private fun resolveAppLabel(pm: PackageManager, pkg: String): String {
        return runCatching {
            val ai = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            pm.getApplicationLabel(ai).toString()
        }.getOrElse { pkg.substringAfterLast('.') }
    }
}
