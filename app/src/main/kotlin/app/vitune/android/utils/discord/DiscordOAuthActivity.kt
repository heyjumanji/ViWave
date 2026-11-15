package app.vitune.android.utils.discord

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

data class AuthCodeResult(val code: String, val state: String)

class DiscordOAuthActivity : Activity() {

    companion object {
        private const val TAG = "DiscordSvc"

        @Volatile
        private var deferred: CompletableDeferred<AuthCodeResult>? = null

        fun newDeferred(): CompletableDeferred<AuthCodeResult> {
            val d = CompletableDeferred<AuthCodeResult>()
            deferred = d
            return d
        }

        suspend fun awaitCode(timeoutMs: Long = 180_000L): AuthCodeResult {
            val d = deferred ?: throw CancellationException("No pending authorization")
            return withTimeout(timeoutMs) { d.await() }
        }

        fun cancelPending() {
            deferred?.let { d ->
                if (!d.isCompleted) {
                    d.completeExceptionally(CancellationException("Authorization cancelled by user"))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "OAuthActivity: onCreate with intent=${intent?.action}")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "OAuthActivity: onNewIntent with intent=${intent?.action}")
        handleIntent(intent)
    }

    private fun handleIntent(currentIntent: Intent?) {
        val uri = currentIntent?.data ?: run {
            Log.w(TAG, "OAuthActivity: no URI in intent")
            finish()
            return
        }

        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val errorDescription = uri.getQueryParameter("error_description")

        if (error != null) {
            Log.w(TAG, "OAuthActivity: error=$error, description=$errorDescription")
            val exception = when (error) {
                "invalid_scope" -> DiscordAuthException.InvalidScope(
                    errorDescription ?: "Scope sdk.social_layer_presence is not enabled on this Discord app"
                )
                "access_denied" -> DiscordAuthException.UserCancelled(
                    errorDescription ?: "Authorization cancelled on Discord"
                )
                else -> DiscordAuthException.OAuthError(error, errorDescription)
            }
            deferred?.completeExceptionally(exception)
            finish()
            return
        }

        if (code == null) {
            Log.w(TAG, "OAuthActivity: missing code")
            deferred?.completeExceptionally(
                DiscordAuthException.InvalidGrant("Missing authorization code")
            )
            finish()
            return
        }

        Log.i(TAG, "OAuthActivity: received code (length=${code.length})")
        deferred?.complete(AuthCodeResult(code = code, state = state ?: ""))
        finish()
    }
}
