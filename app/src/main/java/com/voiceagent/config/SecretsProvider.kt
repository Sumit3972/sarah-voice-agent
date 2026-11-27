package com.voiceagent.config

import android.content.Context
import android.util.Base64
import com.voiceagent.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GoogleAccountRecord(
    val email: String,
    val refresh_token: String,
    val tier: String = "",
    val project_id: String = ""
)

object SecretsProvider {
    private const val PREFS_NAME = "sarah_runtime_prefs"
    private const val KEY_WAKE_CUE = "wake_cue_mode"
    private const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
    private const val KEY_LANG = "stt_language_code"
    private const val KEY_DEEPGRAM = "user_deepgram_api_key"
    private const val KEY_INCEPTION = "user_inception_api_key"
    private const val KEY_FISH = "user_fish_audio_api_key"
    private const val KEY_ACCOUNTS_JSON = "user_antigravity_accounts_json"
    private const val KEY_DARK_THEME = "ui_dark_theme"
    private const val KEY_DEV_DIAGNOSTICS = "ui_dev_diagnostics"
    private const val KEY_AUTONOMOUS_AUTO_UNLOCK = "autonomous_auto_unlock_enabled"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(context: Context? = appContext) =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun deepgramApiKey(): String {
        val userKey = prefs()?.getString(KEY_DEEPGRAM, "").orEmpty().trim()
        return userKey.ifEmpty { BuildConfig.DEEPGRAM_API_KEY.trim() }
    }

    fun fishAudioKeys(): List<String> {
        val userKey = prefs()?.getString(KEY_FISH, "").orEmpty().trim()
        val defaults = listOf(
            BuildConfig.FISH_AUDIO_API_KEY,
            BuildConfig.FISH_AUDIO_BACKUP_KEY
        ).map { it.trim() }.filter { it.isNotEmpty() }
        return (if (userKey.isNotEmpty()) listOf(userKey) + defaults else defaults).distinct()
    }

    fun fishAudioModel(): String = BuildConfig.FISH_AUDIO_MODEL.ifBlank { "s2.1-pro-free" }
    fun fishAudioVoiceId(): String = BuildConfig.FISH_AUDIO_VOICE_ID.ifBlank { "933563129e564b19a115bedd57b7406a" }

    fun inceptionApiKey(): String {
        val userKey = prefs()?.getString(KEY_INCEPTION, "").orEmpty().trim()
        return userKey.ifEmpty { BuildConfig.INCEPTION_API_KEY.trim() }
    }

    fun inceptionBaseUrl(): String = BuildConfig.INCEPTION_BASE_URL.trim().ifBlank { "https://api.inceptionlabs.ai/v1" }
    fun inceptionRouterModel(): String = BuildConfig.INCEPTION_ROUTER_MODEL.trim().ifBlank { "mercury-decide" }
    fun inceptionChatModel(): String = BuildConfig.INCEPTION_CHAT_MODEL.trim().ifBlank { "mercury-3" }

    fun antigravityClientId(): String = BuildConfig.ANTIGRAVITY_CLIENT_ID.trim()
    fun antigravityClientSecret(): String = BuildConfig.ANTIGRAVITY_CLIENT_SECRET.trim()

    fun antigravityAccounts(): List<GoogleAccountRecord> {
        val userJson = prefs()?.getString(KEY_ACCOUNTS_JSON, "").orEmpty().trim()
        if (userJson.isNotEmpty()) {
            val parsed = parseAntigravityAccountsJson(userJson)
            if (parsed.isNotEmpty()) return parsed
        }
        val b64 = BuildConfig.ANTIGRAVITY_ACCOUNTS_B64.trim()
        if (b64.isEmpty()) return emptyList()
        return try {
            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            parseAntigravityAccountsJson(decoded)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseAntigravityAccountsJson(rawJson: String): List<GoogleAccountRecord> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            val el = json.parseToJsonElement(trimmed)
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> {
                    val nested = el["accounts"]
                    if (nested is JsonArray) nested else JsonArray(listOf(el))
                }
                else -> return emptyList()
            }
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val email = obj["email"]?.jsonPrimitive?.content.orEmpty().trim()
                val token = obj["refresh_token"]?.jsonPrimitive?.content.orEmpty().trim()
                val tier = obj["tier"]?.jsonPrimitive?.content.orEmpty().trim()
                val projectId = obj["project_id"]?.jsonPrimitive?.content.orEmpty().trim()
                if (token.isBlank()) null else GoogleAccountRecord(
                    email = email.ifBlank { "google-oauth-user" },
                    refresh_token = token,
                    tier = tier,
                    project_id = projectId
                )
            }.distinctBy { it.refresh_token }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getUserDeepgramKey(context: Context): String =
        prefs(context)?.getString(KEY_DEEPGRAM, "").orEmpty()

    fun setUserDeepgramKey(context: Context, key: String) {
        prefs(context)?.edit()?.putString(KEY_DEEPGRAM, key.trim())?.apply()
    }

    fun getUserInceptionKey(context: Context): String =
        prefs(context)?.getString(KEY_INCEPTION, "").orEmpty()

    fun setUserInceptionKey(context: Context, key: String) {
        prefs(context)?.edit()?.putString(KEY_INCEPTION, key.trim())?.apply()
    }

    fun getUserFishKey(context: Context): String =
        prefs(context)?.getString(KEY_FISH, "").orEmpty()

    fun setUserFishKey(context: Context, key: String) {
        prefs(context)?.edit()?.putString(KEY_FISH, key.trim())?.apply()
    }

    fun getAllAntigravityAccounts(context: Context? = appContext): List<GoogleAccountRecord> {
        val ctx = context ?: appContext
        val saved = if (ctx != null) prefs(ctx)?.getString(KEY_ACCOUNTS_JSON, "").orEmpty().trim() else ""
        if (saved.isNotEmpty()) {
            val parsed = parseAntigravityAccountsJson(saved)
            if (parsed.isNotEmpty()) return parsed
        }
        return antigravityAccounts()
    }

    fun addOrUpdateAntigravityAccount(context: Context, record: GoogleAccountRecord) {
        val cleanToken = record.refresh_token.trim()
        if (cleanToken.isEmpty()) return
        val cleanEmail = record.email.trim().ifBlank { "google-oauth-user" }
        val existing = getAllAntigravityAccounts(context)
            .filterNot {
                it.refresh_token == cleanToken ||
                    (!cleanEmail.equals("google-oauth-user", true) && it.email.equals(cleanEmail, true))
            }
        val updated = listOf(record.copy(email = cleanEmail, refresh_token = cleanToken)) + existing
        prefs(context)?.edit()?.putString(KEY_ACCOUNTS_JSON, json.encodeToString(updated))?.apply()
    }

    fun removeAntigravityAccount(context: Context, emailOrToken: String) {
        val clean = emailOrToken.trim()
        val remaining = getAllAntigravityAccounts(context).filterNot {
            it.email.equals(clean, ignoreCase = true) || it.refresh_token == clean
        }
        if (remaining.isEmpty()) {
            prefs(context)?.edit()?.remove(KEY_ACCOUNTS_JSON)?.apply()
        } else {
            prefs(context)?.edit()?.putString(KEY_ACCOUNTS_JSON, json.encodeToString(remaining))?.apply()
        }
    }

    fun getUserAntigravityAccount(context: Context): GoogleAccountRecord? {
        val saved = prefs(context)?.getString(KEY_ACCOUNTS_JSON, "").orEmpty().trim()
        if (saved.isNotEmpty()) {
            return parseAntigravityAccountsJson(saved).firstOrNull()
        }
        return antigravityAccounts().firstOrNull()
    }

    fun setUserAntigravityAccount(context: Context, email: String, refreshToken: String) {
        val cleanToken = refreshToken.trim()
        val cleanEmail = email.trim()
        if (cleanToken.isEmpty()) {
            prefs(context)?.edit()?.remove(KEY_ACCOUNTS_JSON)?.apply()
            return
        }
        addOrUpdateAntigravityAccount(
            context,
            GoogleAccountRecord(email = cleanEmail.ifBlank { "google-oauth-user" }, refresh_token = cleanToken)
        )
    }

    fun importAntigravityAccountsJson(context: Context, rawJson: String): Int {
        val parsed = parseAntigravityAccountsJson(rawJson)
        if (parsed.isEmpty()) return 0
        prefs(context)?.edit()?.putString(KEY_ACCOUNTS_JSON, json.encodeToString(parsed))?.apply()
        return parsed.size
    }

    fun getWakeCueMode(context: Context): WakeCueMode {
        val raw = prefs(context)?.getString(KEY_WAKE_CUE, WakeCueMode.CUSTOM_CHIME.name)
        return runCatching { WakeCueMode.valueOf(raw ?: WakeCueMode.CUSTOM_CHIME.name) }
            .getOrDefault(WakeCueMode.CUSTOM_CHIME)
    }

    fun setWakeCueMode(context: Context, mode: WakeCueMode) {
        prefs(context)?.edit()?.putString(KEY_WAKE_CUE, mode.name)?.apply()
    }

    fun getLanguageMode(context: Context): String {
        return prefs(context)?.getString(KEY_LANG, PipelineConfig.DEFAULT_LANGUAGE_CODE)
            ?: PipelineConfig.DEFAULT_LANGUAGE_CODE
    }

    fun setLanguageMode(context: Context, langCode: String) {
        prefs(context)?.edit()?.putString(KEY_LANG, langCode)?.apply()
    }

    fun isDarkTheme(context: Context): Boolean =
        prefs(context)?.getBoolean(KEY_DARK_THEME, false) ?: false

    fun setDarkTheme(context: Context, dark: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_DARK_THEME, dark)?.apply()
    }

    fun isDevDiagnosticsEnabled(context: Context): Boolean =
        prefs(context)?.getBoolean(KEY_DEV_DIAGNOSTICS, false) ?: false

    fun setDevDiagnosticsEnabled(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_DEV_DIAGNOSTICS, enabled)?.apply()
    }

    fun isAutonomousAutoUnlockEnabled(context: Context): Boolean =
        prefs(context)?.getBoolean(KEY_AUTONOMOUS_AUTO_UNLOCK, false) ?: false

    fun setAutonomousAutoUnlockEnabled(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_AUTONOMOUS_AUTO_UNLOCK, enabled)?.apply()
    }

    fun getWakeSensitivity(context: Context): WakeSensitivity {
        val raw = prefs(context)?.getString(KEY_WAKE_SENSITIVITY, WakeSensitivity.BALANCED.name)
        return runCatching { WakeSensitivity.valueOf(raw ?: WakeSensitivity.BALANCED.name) }
            .getOrDefault(WakeSensitivity.BALANCED)
    }

    fun setWakeSensitivity(context: Context, sensitivity: WakeSensitivity) {
        prefs(context)?.edit()?.putString(KEY_WAKE_SENSITIVITY, sensitivity.name)?.apply()
    }
}

enum class WakeCueMode {
    CUSTOM_CHIME,
    SILENT
}

enum class WakeSensitivity(val threshold: Float, val consecutiveHits: Int, val label: String) {
    STRICT(0.86f, 3, "Strict"),
    BALANCED(0.75f, 2, "Balanced"),
    HIGH_PICKUP(0.60f, 2, "High Pickup")
}
