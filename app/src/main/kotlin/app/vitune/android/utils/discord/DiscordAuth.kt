package app.vitune.android.utils.discord

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import app.vitune.android.utils.discord.DiscordDefaults.DISCORD_OAUTH_AUTHORIZE
import app.vitune.android.utils.discord.DiscordDefaults.DISCORD_OAUTH_TOKEN
import app.vitune.android.utils.discord.DiscordDefaults.DISCORD_SCOPES
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class DiscordAuthResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
    val scope: String,
)

data class PkcePair(val verifier: String, val challenge: String)

sealed class DiscordAuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UserCancelled(message: String = "User cancelled authorization") : DiscordAuthException(message)
    class Timeout(message: String = "Authorization timed out") : DiscordAuthException(message)
    class NetworkFailure(cause: Throwable) : DiscordAuthException("Network failure: ${cause.message}", cause)
    class InvalidGrant(message: String = "Invalid or expired grant") : DiscordAuthException(message)
    class InvalidClient(message: String = "Public Client is disabled in Discord Developer Portal") : DiscordAuthException(message)
    class InvalidScope(message: String = "Discord Social SDK scope is not enabled in Discord Developer Portal") : DiscordAuthException(message)
    class RedirectUriMismatch(message: String = "Redirect URI mismatch in Discord Developer Portal") : DiscordAuthException(message)
    class OAuthError(val errorCode: String, val errorDescription: String?) :
        DiscordAuthException(errorDescription ?: "Discord OAuth error: $errorCode")
    class StateMismatch : DiscordAuthException("OAuth state mismatch")
    class NoBrowser(message: String = "No browser available") : DiscordAuthException(message)
}

class DiscordAuth(
    private val httpClient: HttpClient = defaultClient(),
) {

    suspend fun authorize(activity: Activity): DiscordAuthResult {
        val pkce = generatePkcePair()
        val state = generateState()
        val clientId = DiscordDefaults.activeAppId()
        val redirectUri = DiscordDefaults.activeRedirectUri()
        DiscordOAuthActivity.newDeferred()

        try {
            val authUrl = buildAuthorizeUrl(
                clientId = clientId,
                redirectUri = redirectUri,
                state = state,
                challenge = pkce.challenge,
            )

            Log.i(TAG, "authorize: launching URL (clientId prefix=${clientId.take(8)}, redirectUri=$redirectUri)")

            val intent = Intent(Intent.ACTION_VIEW, authUrl.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Avoid opening in the native com.discord Android app which breaks custom scheme redirects
            runCatching {
                val pm = activity.packageManager
                val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri())
                val browsers = pm.queryIntentActivities(probe, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .filter { it.isNotBlank() && it != "com.discord" && !it.startsWith("com.discord.") }
                val preferred = browsers.firstOrNull { it == "com.android.chrome" }
                    ?: browsers.firstOrNull()
                if (preferred != null) {
                    intent.setPackage(preferred)
                }
            }

            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                intent.setPackage(null)
                try {
                    activity.startActivity(intent)
                } catch (e2: ActivityNotFoundException) {
                    Log.w(TAG, "authorize: no browser available to launch URL", e2)
                    throw DiscordAuthException.NoBrowser()
                }
            }

            val callback = try {
                DiscordOAuthActivity.awaitCode(timeoutMs = 180_000L)
            } catch (e: TimeoutCancellationException) {
                throw DiscordAuthException.Timeout()
            } catch (e: DiscordAuthException) {
                throw e
            } catch (e: CancellationException) {
                Log.i(TAG, "authorize: cancelled")
                throw DiscordAuthException.UserCancelled()
            }

            if (callback.state != state) {
                Log.w(
                    TAG,
                    "authorize: state mismatch (expected=${state.take(8)}, got=${callback.state.take(8)})"
                )
                throw DiscordAuthException.StateMismatch()
            }

            return exchangeAuthorizationCode(
                clientId = clientId,
                code = callback.code,
                verifier = pkce.verifier,
                redirectUri = redirectUri,
            )
        } finally {
            // no cleanup needed — no local server
        }
    }

    fun cancel() {
        DiscordOAuthActivity.cancelPending()
    }

    fun close() {
        runCatching { httpClient.close() }
    }

    suspend fun refresh(refreshToken: String): DiscordAuthResult =
        refreshAccessToken(DiscordDefaults.activeAppId(), refreshToken)

    private suspend fun exchangeAuthorizationCode(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
    ): DiscordAuthResult = performTokenExchange(
        clientId = clientId,
        grantType = "authorization_code",
        extraParams = parameters {
            append("code", code)
            append("redirect_uri", redirectUri)
            append("code_verifier", verifier)
        },
    )

    private suspend fun refreshAccessToken(clientId: String, refreshToken: String): DiscordAuthResult =
        performTokenExchange(
            clientId = clientId,
            grantType = "refresh_token",
            extraParams = parameters {
                append("refresh_token", refreshToken)
            },
        )

    private suspend fun performTokenExchange(
        clientId: String,
        grantType: String,
        extraParams: io.ktor.http.Parameters,
    ): DiscordAuthResult {
        val response: HttpResponse = httpClient.submitForm(
            url = DISCORD_OAUTH_TOKEN,
            formParameters = parameters {
                append("client_id", clientId)
                append("grant_type", grantType)
                extraParams.forEach { name, values ->
                    values.forEach { value -> append(name, value) }
                }
            },
        )

        val status = response.status
        val body = response.bodyAsText()

        if (status.value in 200..299) {
            val json = JSONObject(body)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", "")
            val expiresIn = json.optLong("expires_in", 0L)
            val scope = json.optString("scope", DISCORD_SCOPES)
            Log.i(
                TAG,
                "token exchange: success (accessToken length=${accessToken.length}, refreshToken present=${refreshToken.isNotEmpty()}, expiresIn=$expiresIn)"
            )
            return DiscordAuthResult(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSec = expiresIn,
                scope = scope,
            )
        }

        val errorJson = runCatching { JSONObject(body) }.getOrNull()
        val errorCode = errorJson?.optString("error", "").orEmpty()
        val errorDesc = errorJson?.optString("error_description", "").orEmpty()

        Log.w(
            TAG,
            "token exchange: HTTP ${status.value} (grantType=$grantType, error=$errorCode, desc=$errorDesc, body=${body.take(200)})"
        )

        when (errorCode) {
            "invalid_client" -> throw DiscordAuthException.InvalidClient(
                errorDesc.ifBlank { "Public Client is turned OFF in Discord Developer Portal" }
            )
            "invalid_scope" -> throw DiscordAuthException.InvalidScope(
                errorDesc.ifBlank { "Discord Social SDK is not enabled in Discord Developer Portal" }
            )
            "invalid_request" -> {
                if (errorDesc.contains("redirect_uri", ignoreCase = true)) {
                    throw DiscordAuthException.RedirectUriMismatch(errorDesc)
                }
                throw DiscordAuthException.OAuthError(errorCode, errorDesc.ifBlank { body.take(160) })
            }
            "invalid_grant" -> {
                if (grantType == "refresh_token") {
                    throw DiscordAuthException.InvalidGrant()
                }
                throw DiscordAuthException.OAuthError(
                    errorCode,
                    errorDesc.ifBlank { "Authorization code expired or already used. Please tap Login again." }
                )
            }
        }

        if (status == HttpStatusCode.Unauthorized) {
            throw DiscordAuthException.InvalidClient()
        }

        throw DiscordAuthException.NetworkFailure(IOException("HTTP ${status.value}: $body"))
    }

    private fun buildAuthorizeUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        challenge: String,
    ): String {
        val encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8.name())
        val encodedScope = URLEncoder.encode(DISCORD_SCOPES, StandardCharsets.UTF_8.name())
        return buildString {
            append(DISCORD_OAUTH_AUTHORIZE)
            append("?client_id=").append(clientId)
            append("&response_type=code")
            append("&redirect_uri=").append(encodedRedirect)
            append("&scope=").append(encodedScope)
            append("&state=").append(state)
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(challenge)
        }
    }

    companion object {
        private const val TAG = "DiscordSvc"
        const val REDIRECT_URI = DiscordDefaults.VIWAVES_REDIRECT_URI

        private fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000L
                connectTimeoutMillis = 10_000L
                socketTimeoutMillis = 15_000L
            }
            expectSuccess = false
        }

        fun generatePkcePair(): PkcePair {
            val bytes = ByteArray(64)
            SecureRandom().nextBytes(bytes)
            val verifier = Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val challenge = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8)),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            return PkcePair(verifier = verifier, challenge = challenge)
        }

        private fun generateState(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(
                bytes,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
        }
    }
}
