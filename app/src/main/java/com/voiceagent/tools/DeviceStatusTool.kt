package com.voiceagent.tools

import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.voiceagent.accessibility.SaraAccessibilityService
import com.voiceagent.config.asStrOrNull
import com.voiceagent.notifications.SaraNotificationListenerService
import com.voiceagent.shizuku.ShizukuBridge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.roundToInt

class DeviceStatusTool(
    private val appContext: Context
) {
    suspend fun execute(args: JsonObject): String {
        val action = args["action"].asStrOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "overview" }
        val query = args["query"].asStrOrNull()?.trim().orEmpty()
        return when (action) {
            "overview" -> getOverview()
            "running_apps", "recent_apps", "background_apps" -> getRunningApps()
            "storage_info", "storage", "disk" -> getStorageInfo()
            "clipboard" -> getClipboard()
            "installed_apps" -> getInstalledApps(query)
            else -> buildJsonObject {
                put("ok", false)
                put(
                    "error",
                    "Unknown device_status action='$action'. Use overview|running_apps|storage_info|clipboard|installed_apps."
                )
            }.toString()
        }
    }

    private fun getOverview(): String {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellularConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val btEnabled = runCatching {
            val btm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            btm.adapter?.isEnabled == true
        }.getOrNull()

        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val dndActive = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mediaPct = ((am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f) /
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)).toInt()
        val alarmPct = ((am.getStreamVolume(AudioManager.STREAM_ALARM) * 100f) /
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)).toInt()
        val ringerMode = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }

        val brightnessRaw = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)

        val memInfo = readMemoryInfo()
        val a11yConnected = SaraAccessibilityService.isConnected()
        val notifConnected = SaraNotificationListenerService.isConnected(appContext)
        val shizukuReady = ShizukuBridge.isPermissionGranted()
        val fgPkg = SaraAccessibilityService.instance?.currentPackageName() ?: "unknown"

        return buildJsonObject {
            put("ok", true)
            put("battery_percent", batteryPct)
            put("is_charging", isCharging)
            put("wifi_connected", wifiConnected)
            put("cellular_connected", cellularConnected)
            put("bluetooth_enabled", btEnabled?.toString() ?: "unknown")
            put("dnd_active", dndActive)
            put("ringer_mode", ringerMode)
            put("media_volume_percent", mediaPct)
            put("alarm_volume_percent", alarmPct)
            if (brightnessRaw >= 0) put("brightness_percent", (brightnessRaw * 100) / 255)
            put("ram_available_mb", memInfo.first)
            put("ram_total_mb", memInfo.second)
            put("foreground_package", fgPkg)
            put("accessibility_connected", a11yConnected)
            put("notification_listener_connected", notifConnected)
            put("shizuku_ready", shizukuReady)
        }.toString()
    }

    private suspend fun getRunningApps(): String {
        val (availMb, totalMb, usedPct) = readMemoryInfo()
        val fgPkg = SaraAccessibilityService.instance?.currentPackageName() ?: "unknown"
        val shizukuGranted = ShizukuBridge.isPermissionGranted()
        val running = if (shizukuGranted) {
            ShizukuBridge.getRecentRunningUserApps(appContext)
        } else {
            emptyList()
        }

        return buildJsonObject {
            put("ok", true)
            put("shizuku_used", shizukuGranted)
            if (!shizukuGranted) {
                put("warning", "Shizuku permission is not granted yet; grant Shizuku for live background app inspection.")
            }
            put("foreground_package", fgPkg)
            put("ram_available_mb", availMb)
            put("ram_total_mb", totalMb)
            put("ram_used_percent", usedPct)
            put("running_count", running.size)
            put("running_user_apps", buildJsonArray {
                running.forEach { entry ->
                    add(buildJsonObject {
                        put("name", entry.appName)
                        put("package", entry.packageName)
                    })
                }
            })
            put("protected_assistant", appContext.packageName)
        }.toString()
    }

    private fun getStorageInfo(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes.toDouble().coerceAtLeast(1.0)
        val availBytes = stat.availableBytes.toDouble()
        val freeGb = ((availBytes / 1_073_741_824.0) * 10.0).roundToInt() / 10.0
        val totalGb = ((totalBytes / 1_073_741_824.0) * 10.0).roundToInt() / 10.0
        val usedPct = (((totalBytes - availBytes) * 100.0) / totalBytes).roundToInt().coerceIn(0, 100)

        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val healthCode = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val healthLabel = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }

        val (availMb, totalMb, ramUsedPct) = readMemoryInfo()

        return buildJsonObject {
            put("ok", true)
            put("storage_free_gb", freeGb)
            put("storage_total_gb", totalGb)
            put("storage_used_percent", usedPct)
            put("ram_available_mb", availMb)
            put("ram_total_mb", totalMb)
            put("ram_used_percent", ramUsedPct)
            if (tempTenths >= 0) put("battery_temp_celsius", tempTenths / 10.0)
            put("battery_health", healthLabel)
        }.toString()
    }

    private fun readMemoryInfo(): Triple<Long, Long, Int> {
        val actMgr = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(mi)
        val availMb = mi.availMem / (1024L * 1024L)
        val totalMb = (mi.totalMem / (1024L * 1024L)).coerceAtLeast(1L)
        val usedPct = (((totalMb - availMb).toDouble() * 100.0) / totalMb.toDouble()).roundToInt().coerceIn(0, 100)
        return Triple(availMb, totalMb, usedPct)
    }

    private fun getClipboard(): String {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
        return buildJsonObject {
            put("ok", true)
            put("clipboard_text", text.take(1500))
        }.toString()
    }

    private fun getInstalledApps(query: String): String {
        val pm = appContext.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(mainIntent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { (label, pkg) ->
                query.isBlank() ||
                    label.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT)) ||
                    pkg.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))
            }
            .sortedBy { it.first }
            .take(35)

        return buildJsonObject {
            put("ok", true)
            put("count", list.size)
            put("apps", buildJsonArray {
                list.forEach { (label, pkg) ->
                    add(buildJsonObject {
                        put("name", label)
                        put("package", pkg)
                    })
                }
            })
        }.toString()
    }
}
