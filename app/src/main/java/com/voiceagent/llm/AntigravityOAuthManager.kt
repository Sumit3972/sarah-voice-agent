package com.voiceagent.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.voiceagent.MainActivity
import com.voiceagent.config.GoogleAccountRecord
import com.voiceagent.config.PipelineConfig
import com.voiceagent.config.SecretsProvider
import com.voiceagent.config.asStrOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OAuthSignInOutcome(
    val ok: Boolean,
    val record: GoogleAccountRecord? = null,
    val message: String = ""
)

/**
 * Implements Google OAuth2 Desktop/Loopback sign-in modeled directly after lbjlaq/Antigravity-Manager:
 * Chrome Custom Tabs authorization + local 127.0.0.1 HTTP listener + token exchange +
 * userinfo email + loadCodeAssist/onboardUser tier & project discovery (FREE / PRO / ULTRA).
 */
object AntigravityOAuthManager {
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"

    private val SCOPES = listOf(
        "openid",
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
        "https://www.googleapis.com/auth/aicode"
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeSocket: ServerSocket? = null

    @Volatile
    private var activeState: String = ""

    @Volatile
    private var activeRedirectUri: String = ""

    fun buildAuthorizationUrl(redirectUri: String, state: String, clientId: String = SecretsProvider.antigravityClientId()): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to SCOPES.joinToString(" "),
            "access_type" to "offline",
            "prompt" to "consent",
            "include_granted_scopes" to "true",
            "state" to state
        )
        val qs = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTH_URL?$qs"
    }

    /**
     * Parses either a full callback URL (http://127.0.0.1:PORT/oauth-callback?code=...&state=...),
     * a schemeless localhost URL, or a raw URL-encoded/plain authorization code.
     */
    internal fun parseManualCallbackInput(rawInput: String): Result<Pair<String, String?>> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Authorization input cannot be empty"))
        }

        val normalizedUrl = when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("localhost:", ignoreCase = true) || trimmed.startsWith("127.0.0.1:", ignoreCase = true) -> "http://$trimmed"
            else -> null
        }

        if (normalizedUrl != null) {
            val uri = runCatching { Uri.parse(normalizedUrl) }.getOrNull()
            // Fallback pure-JVM parsing when running unit tests without full Uri mock:
            val query = normalizedUrl.substringAfter('?', "")
            val params = parseQueryString(query)
            val err = params["error"] ?: runCatching { uri?.getQueryParameter("error") }.getOrNull()
            if (!err.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Google authorization returned error: $err"))
            }
            val code = params["code"] ?: runCatching { uri?.getQueryParameter("code") }.getOrNull()
            val state = params["state"] ?: runCatching { uri?.getQueryParameter("state") }.getOrNull()
            if (code.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("No authorization code found in callback URL"))
            }
            return Result.success(code to state)
        }

        val decoded = runCatching { URLDecoder.decode(trimmed, StandardCharsets.UTF_8.name()) }.getOrDefault(trimmed)
        return Result.success(decoded to null)
    }

    private fun parseQueryString(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
            val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
            out[k] = v
        }
        return out
    }

    fun cancelActiveFlow() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        activeState = ""
        activeRedirectUri = ""
    }

    suspend fun launchAndAwaitGoogleSignIn(
        context: Context,
        oauthPool: OAuthAccountPool
    ): OAuthSignInOutcome = withContext(Dispatchers.IO) {
        cancelActiveFlow()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 180_000
        activeSocket = server
        val port = server.localPort
        val redirectUri = "http://127.0.0.1:$port/oauth-callback"
        val expectedState = UUID.randomUUID().toString()
        activeRedirectUri = redirectUri
        activeState = expectedState

        val authUrl = buildAuthorizationUrl(redirectUri, expectedState)
        withContext(Dispatchers.Main) {
            runCatching {
                val customTabs = CustomTabsIntent.Builder().setShowTitle(true).build()
                customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabs.launchUrl(context, Uri.parse(authUrl))
            }.onFailure {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
            }
        }

        try {
            val socket = server.accept()
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine().orEmpty()
            val pathPart = requestLine.split(' ').getOrNull(1).orEmpty()
            val query = pathPart.substringAfter('?', "")
            val parsed = parseQueryString(query)
            val error = parsed["error"]
            val code = parsed["code"]
            val receivedState = parsed["state"]

            val valid = error.isNullOrBlank() && !code.isNullOrBlank() && receivedState == expectedState
            val htmlBody = if (valid) {
                """<html><body style="font-family:sans-serif;text-align:center;padding:48px;background:#FAF9F6;color:#1A1918;"><h2 style="color:#2E7D5B;">Sarah Connected ✓</h2><p>Google account linked! Returning to Sarah...</p><script>setTimeout(function(){window.close();},1200);</script></body></html>"""
            } else {
                """<html><body style="font-family:sans-serif;text-align:center;padding:48px;background:#FAF9F6;color:#1A1918;"><h2 style="color:#B3261E;">Sign-In Not Completed</h2><p>${error ?: "State mismatch or missing code"}. Return to Sarah and try again.</p></body></html>"""
            }
            val resp = "HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Connection: close\r\n\r\n$htmlBody"
            runCatching {
                socket.getOutputStream().write(resp.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                socket.close()
            }

            if (!valid) {
                return@withContext OAuthSignInOutcome(
                    ok = false,
                    message = if (!error.isNullOrBlank()) "Google denied access: $error" else "Invalid OAuth callback state"
                )
            }

            // Bring MainActivity back to the front cleanly after Custom Tabs redirect
            withContext(Dispatchers.Main) {
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                }
            }

            return@withContext completeCodeExchange(context, code!!, redirectUri, oauthPool)
        } catch (e: Exception) {
            return@withContext OAuthSignInOutcome(
                ok = false,
                message = "Sign-in cancelled or timed out: ${e.message}"
            )
        } finally {
            cancelActiveFlow()
        }
    }

    suspend fun completeManualFallback(
        context: Context,
        rawInput: String,
        oauthPool: OAuthAccountPool
    ): OAuthSignInOutcome = withContext(Dispatchers.IO) {
        val parsed = parseManualCallbackInput(rawInput).getOrElse {
            return@withContext OAuthSignInOutcome(false, message = it.message ?: "Invalid callback input")
        }
        val (code, state) = parsed
        if (state != null && activeState.isNotBlank() && state != activeState) {
            return@withContext OAuthSignInOutcome(false, message = "OAuth state mismatch (CSRF check failed)")
        }
        val redirectUri = activeRedirectUri.ifBlank { "http://127.0.0.1/oauth-callback" }
        completeCodeExchange(context, code, redirectUri, oauthPool)
    }

    private suspend fun completeCodeExchange(
        context: Context,
        code: String,
        redirectUri: String,
        oauthPool: OAuthAccountPool
    ): OAuthSignInOutcome {
        val clientId = SecretsProvider.antigravityClientId()
        val clientSecret = SecretsProvider.antigravityClientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return OAuthSignInOutcome(false, message = "OAuth client ID/secret not configured in build")
        }

        var lastError = ""
        for (attempt in 1..3) {
            if (attempt > 1) delay((attempt - 1) * 500L)
            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
                .build()
            val req = Request.Builder()
                .url(TOKEN_URL)
                .addHeader("User-Agent", OAuthAccountPool.USER_AGENT)
                .post(form)
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) {
                        lastError = "Token exchange HTTP ${resp.code}: ${body.take(180)}"
                        if (resp.code in 400..428 || resp.code in 430..499) {
                            return OAuthSignInOutcome(false, message = lastError)
                        }
                    } else {
                        val obj = json.parseToJsonElement(body).jsonObject
                        val accessToken = obj["access_token"].asStrOrNull().orEmpty()
                        val refreshToken = obj["refresh_token"].asStrOrNull().orEmpty()
                        if (refreshToken.isBlank()) {
                            return OAuthSignInOutcome(
                                false,
                                message = "Google did not return a refresh_token. Revoke previous Antigravity permission in myaccount.google.com/permissions and retry."
                            )
                        }
                        val email = fetchUserEmail(accessToken).ifBlank { "google-oauth-user" }
                        val tempAcc = AccountState(email = email, refreshToken = refreshToken, accessToken = accessToken, tokenExpiryMs = System.currentTimeMillis() + 3300_000L)
                        val pid = oauthPool.getProjectId(tempAcc, accessToken)
                        val record = GoogleAccountRecord(
                            email = email,
                            refresh_token = refreshToken,
                            tier = tempAcc.tierLabel,
                            project_id = pid
                        )
                        SecretsProvider.addOrUpdateAntigravityAccount(context, record)
                        oauthPool.reloadAccounts()
                        oauthPool.syncAllQuotas()
                        Log.i(PipelineConfig.LOG_TAG, "[OAuthSignIn] Added Google account $email (tier=${tempAcc.tierLabel}, project=${pid.ifBlank { "<default>" }})")
                        return OAuthSignInOutcome(
                            ok = true,
                            record = record,
                            message = "Signed in as $email (${tempAcc.tierLabel})"
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Network error"
            }
        }
        return OAuthSignInOutcome(false, message = lastError.ifBlank { "Token exchange failed" })
    }

    private fun fetchUserEmail(accessToken: String): String {
        if (accessToken.isBlank()) return ""
        val req = Request.Builder()
            .url(USERINFO_URL)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                json.parseToJsonElement(resp.body.string()).jsonObject["email"].asStrOrNull().orEmpty()
            }
        }.getOrDefault("")
    }
}
