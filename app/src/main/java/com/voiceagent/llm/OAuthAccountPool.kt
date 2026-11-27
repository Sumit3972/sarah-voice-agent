package com.voiceagent.llm

import android.util.Log
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.config.asStrOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class AccountSummary(
    val email: String,
    val tier: String,
    val geminiPercent: Int,
    val claudePercent: Int,
    val refreshToken: String
)

data class AccountState(
    val email: String,
    val refreshToken: String,
    var accessToken: String = "",
    var tokenExpiryMs: Long = 0L,
    var projectId: String = "",
    var tierLabel: String = "FREE",
    var authCooldownUntilMs: Long = 0L,
    var geminiCooldownUntilMs: Long = 0L,
    var claudeCooldownUntilMs: Long = 0L,
    var geminiQuotaFraction: Double = 0.9,
    var claudeQuotaFraction: Double = 1.0,
    val mutex: Mutex = Mutex(),
    val inFlightCount: AtomicInteger = AtomicInteger(0)
) {
    fun isOnCooldown(taskType: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < authCooldownUntilMs) return true
        return when (taskType) {
            "claude" -> now < claudeCooldownUntilMs
            else -> now < geminiCooldownUntilMs
        }
    }

    fun putOnCooldown(taskType: String, durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        when (taskType) {
            "auth" -> authCooldownUntilMs = until
            "claude" -> claudeCooldownUntilMs = until
            else -> geminiCooldownUntilMs = until
        }
    }
}

class OAuthAccountPool {
    companion object {
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val BASE_URL_DAILY = "https://daily-cloudcode-pa.googleapis.com/v1internal"
        const val BASE_URL_SANDBOX = "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal"
        const val BASE_URL_PROD = "https://cloudcode-pa.googleapis.com/v1internal"
        const val USER_AGENT =
            "Antigravity/4.3.0 (Windows NT 10.0; Win64; x64) Chrome/132.0.6834.160 Electron/39.2.3"

        internal fun normalizeTierLabel(rawTier: String?): String {
            val lower = rawTier.orEmpty().lowercase()
            return when {
                lower.contains("ultra") -> "ULTRA"
                lower.contains("pro") || lower.contains("enterprise") -> "PRO"
                else -> "FREE"
            }
        }

        const val MODEL_GEMINI_4_HYDROGEN = "gemini-4-hydrogen-sonar"
        const val MODEL_GEMINI_38_FLASH_HIGH = "gemini-3.8-flash-high"
        const val MODEL_GEMINI_38_FLASH_MEDIUM = "gemini-3.8-flash-medium"
        const val MODEL_GEMINI_38_FLASH_LOW = "gemini-3.8-flash-low"
        const val MODEL_GEMINI_38_FLASH = "gemini-3.8-flash-tiered"
        const val MODEL_GEMINI_3_FLASH = "gemini-3-flash"
        const val MODEL_CLAUDE_SONNET = "claude-sonnet-4-6"
        const val MODEL_CLAUDE_OPUS = "claude-opus-4-6-thinking"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val poolMutex = Mutex()
    private var roundRobin = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var accounts: List<AccountState> = loadAccountList()

    private fun loadAccountList(): List<AccountState> = SecretsProvider.getAllAntigravityAccounts()
        .distinctBy { it.refresh_token.trim() }
        .map {
            AccountState(
                email = it.email,
                refreshToken = it.refresh_token.trim(),
                projectId = it.project_id,
                tierLabel = it.tier.ifBlank { "FREE" }
            )
        }

    fun reloadAccounts() {
        accounts = loadAccountList()
    }

    fun accountSummaries(): List<AccountSummary> = accounts.map { acc ->
        AccountSummary(
            email = acc.email,
            tier = acc.tierLabel,
            geminiPercent = (acc.geminiQuotaFraction * 100).toInt().coerceIn(0, 100),
            claudePercent = (acc.claudeQuotaFraction * 100).toInt().coerceIn(0, 100),
            refreshToken = acc.refreshToken
        )
    }

    fun quotaSummaryText(): String {
        val first = accounts.firstOrNull() ?: return "Account not set"
        val gPct = (first.geminiQuotaFraction * 100).toInt().coerceIn(0, 100)
        val cPct = (first.claudeQuotaFraction * 100).toInt().coerceIn(0, 100)
        val extra = if (accounts.size > 1) " (+${accounts.size - 1})" else ""
        return "${first.email} [${first.tierLabel}]$extra · Gemini $gPct% · Claude $cPct%"
    }

    fun accountCount(): Int = accounts.size

    suspend fun getOrderedAccounts(taskType: String = "gemini"): List<AccountState> = poolMutex.withLock {
        if (accounts.isEmpty()) return emptyList()
        var available = accounts.filter { !it.isOnCooldown(taskType) }.toMutableList()
        if (available.isEmpty()) {
            Log.w(PipelineConfig.LOG_TAG, "[OAuthPool] All ${accounts.size} accounts on cooldown; resetting cooldowns")
            for (acc in accounts) {
                acc.geminiCooldownUntilMs = 0L
                acc.claudeCooldownUntilMs = 0L
            }
            available = accounts.toMutableList()
        }

        available.sortWith(
            compareBy<AccountState> { it.inFlightCount.get() }
                .thenByDescending {
                    if (taskType == "claude") it.claudeQuotaFraction else it.geminiQuotaFraction
                }
        )

        val count = available.size
        val startIdx = if (count > 1 && available.all { it.inFlightCount.get() == available.first().inFlightCount.get() }) {
            (roundRobin++ and Int.MAX_VALUE) % count
        } else {
            0
        }
        List(count) { offset ->
            available[(startIdx + offset) % count]
        }
    }

    fun incrementInFlight(acc: AccountState): Int = acc.inFlightCount.incrementAndGet()

    fun decrementInFlight(acc: AccountState): Int =
        acc.inFlightCount.updateAndGet { cur -> (cur - 1).coerceAtLeast(0) }

    suspend fun getAccessToken(acc: AccountState): String = acc.mutex.withLock {
        val now = System.currentTimeMillis()
        if (acc.accessToken.isNotBlank() && now + 5 * 60_000L < acc.tokenExpiryMs) {
            return acc.accessToken
        }

        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("client_id", SecretsProvider.antigravityClientId())
                .add("client_secret", SecretsProvider.antigravityClientSecret())
                .add("refresh_token", acc.refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            val req = Request.Builder()
                .url(TOKEN_URL)
                .addHeader("User-Agent", USER_AGENT)
                .post(form)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("OAuth token refresh failed HTTP ${resp.code}: $body")
                }
                val obj = json.parseToJsonElement(body).jsonObject
                val token = obj["access_token"]?.jsonPrimitive?.content.orEmpty()
                val expiresIn = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
                if (token.isBlank()) throw IllegalStateException("Empty access_token in OAuth response")

                acc.accessToken = token
                acc.tokenExpiryMs = System.currentTimeMillis() + expiresIn * 1000L
                token
            }
        }
    }

    suspend fun getProjectId(
        acc: AccountState,
        token: String,
        forceRefresh: Boolean = false
    ): String {
        val cached = acc.mutex.withLock {
            if (!forceRefresh && acc.projectId.isNotBlank()) acc.projectId else ""
        }
        if (cached.isNotBlank()) return cached

        return acc.mutex.withLock {
            if (!forceRefresh && acc.projectId.isNotBlank()) {
                return@withLock acc.projectId
            }
            withContext(Dispatchers.IO) {
                val bodyJson = """{"metadata":{"ideType":"ANTIGRAVITY"}}"""
                for (ep in listOf(BASE_URL_DAILY, BASE_URL_SANDBOX, BASE_URL_PROD)) {
                    val req = Request.Builder()
                        .url("$ep:loadCodeAssist")
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Content-Type", "application/json")
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()

                    val outcome = runCatching {
                        httpClient.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            val obj = json.parseToJsonElement(resp.body.string()).jsonObject
                            val pid = obj["cloudaicompanionProject"]?.asStrOrNull().orEmpty()
                            val paidId = obj["paidTier"]?.let { runCatching { it.jsonObject }.getOrNull() }
                                ?.let { it["id"].asStrOrNull() ?: it["name"].asStrOrNull() }
                            val curId = obj["currentTier"]?.let { runCatching { it.jsonObject }.getOrNull() }
                                ?.let { it["id"].asStrOrNull() ?: it["name"].asStrOrNull() }
                            val resolvedTier = normalizeTierLabel(paidId ?: curId ?: "free-tier")
                            pid to resolvedTier
                        }
                    }.getOrNull()

                    if (outcome != null) {
                        var (pid, tier) = outcome
                        if (pid.isBlank()) {
                            pid = tryOnboardFreeTier(token)
                        }
                        acc.tierLabel = tier
                        if (pid.isNotBlank()) acc.projectId = pid
                        return@withContext acc.projectId.ifBlank { "aicode-consumers" }
                    }
                }
                acc.projectId.ifBlank { "aicode-consumers" }
            }
        }
    }

    private fun tryOnboardFreeTier(token: String): String {
        val onboardBody = """{"tierId":"free-tier","metadata":{"ideType":"ANTIGRAVITY"}}"""
        val req = Request.Builder()
            .url("$BASE_URL_DAILY:onboardUser")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Content-Type", "application/json")
            .post(onboardBody.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val obj = json.parseToJsonElement(resp.body.string()).jsonObject
                obj["response"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    ?.get("cloudaicompanionProject")?.asStrOrNull().orEmpty()
            }
        }.getOrDefault("")
    }

    suspend fun syncAllQuotas() = withContext(Dispatchers.IO) {
        val snapshot = poolMutex.withLock { accounts.toList() }
        for ((idx, acc) in snapshot.withIndex()) {
            runCatching {
                val token = getAccessToken(acc)
                val pid = getProjectId(acc, token, forceRefresh = true)
                val bodyJson = if (pid.isNotBlank()) """{"project":"$pid"}""" else "{}"
                val req = Request.Builder()
                    .url("$BASE_URL_DAILY:fetchAvailableModels")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("User-Agent", USER_AGENT)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val obj = json.parseToJsonElement(resp.body.string()).jsonObject
                    val models = obj["models"]?.jsonObject ?: return@use
                    val qHydrogen = models[MODEL_GEMINI_4_HYDROGEN]?.jsonObject
                        ?.get("quotaInfo")?.jsonObject
                        ?.get("remainingFraction")?.jsonPrimitive?.doubleOrNull
                    val q38High = models[MODEL_GEMINI_38_FLASH_HIGH]?.jsonObject
                        ?.get("quotaInfo")?.jsonObject
                        ?.get("remainingFraction")?.jsonPrimitive?.doubleOrNull
                    val q38 = models[MODEL_GEMINI_38_FLASH]?.jsonObject
                        ?.get("quotaInfo")?.jsonObject
                        ?.get("remainingFraction")?.jsonPrimitive?.doubleOrNull
                    val q3 = models[MODEL_GEMINI_3_FLASH]?.jsonObject
                        ?.get("quotaInfo")?.jsonObject
                        ?.get("remainingFraction")?.jsonPrimitive?.doubleOrNull
                    val qClaude = models[MODEL_CLAUDE_SONNET]?.jsonObject
                        ?.get("quotaInfo")?.jsonObject
                        ?.get("remainingFraction")?.jsonPrimitive?.doubleOrNull

                    if (qHydrogen != null || q38High != null || q38 != null || q3 != null) {
                        acc.geminiQuotaFraction = qHydrogen ?: q38High ?: q38 ?: q3 ?: acc.geminiQuotaFraction
                    }
                    if (qClaude != null) {
                        acc.claudeQuotaFraction = qClaude
                    }
                    Log.i(
                        PipelineConfig.LOG_TAG,
                        "[OAuthPool] [${idx + 1}] ${acc.email}: Gemini=${"%.1f".format(acc.geminiQuotaFraction * 100)}% | Claude=${"%.1f".format(acc.claudeQuotaFraction * 100)}%"
                    )
                }
            }.onFailure {
                Log.w(PipelineConfig.LOG_TAG, "[OAuthPool] Quota sync failed for ${acc.email}: ${it.message}")
            }
        }
    }
}
