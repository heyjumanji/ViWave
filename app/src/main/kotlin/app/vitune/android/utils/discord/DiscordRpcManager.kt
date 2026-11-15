package app.vitune.android.utils.discord

import android.app.Activity
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

data class DiscordUser(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String?,
)

object DiscordRpcManager {
    private const val TAG = "DiscordSvc"

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var _ready: Boolean = false

    @Volatile
    private var _authorized: Boolean = false

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var authorizeInProgress: Boolean = false

    @Volatile
    private var lastActivitySentAtMs: Long = 0L

    @Volatile
    private var lastActivity: ActivityPayload? = null

    @Volatile private var currentSongId: String? = null
    @Volatile private var currentIsPlaying: Boolean = false
    @Volatile private var currentRawLargeImage: String? = null
    @Volatile private var currentHasEndTimestamp: Boolean = false
    private val currentActivityId = AtomicLong(0L)
    @Volatile private var imageResolutionJob: Job? = null
    @Volatile private var currentActivityHadImages: Boolean = false
    private val reconnectMutex = Mutex()

    private val _accessTokenFlow = MutableStateFlow<String?>(null)
    val accessTokenFlow: StateFlow<String?> = _accessTokenFlow

    private val _connectionStatus = MutableStateFlow(Status.Disconnected)
    val connectionStatus: StateFlow<Status> = _connectionStatus

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser

    private val _settingsChanged = MutableStateFlow(0)
    val settingsChanged: StateFlow<Int> = _settingsChanged

    fun notifySettingsChanged() {
        Log.d(TAG, "notifySettingsChanged: incrementing (count=${_settingsChanged.value + 1}), invalidating dedup")
        _settingsChanged.value++
        currentSongId = null
        currentIsPlaying = false
        currentRawLargeImage = null
        currentHasEndTimestamp = false
    }

    enum class Status { Disconnected, Authorizing, Connected }

    fun getAccessToken(): String? = accessToken

    fun isInitialized(): Boolean = initialized

    fun isAuthorized(): Boolean = _authorized

    fun isReady(): Boolean = _ready

    fun isShowingSong(songId: String, isPlaying: Boolean): Boolean {
        if (currentSongId != songId || currentIsPlaying != isPlaying) {
            return false
        }
        if (lastActivity == null) {
            return false
        }
        if (currentActivityHadImages &&
            lastActivity?.largeImage == null && lastActivity?.smallImage == null &&
            (imageResolutionJob == null || imageResolutionJob?.isCompleted == true)
        ) {
            return false
        }
        return true
    }

    fun clearLastError() {
        _lastError.value = null
    }

    private var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appId: String get() = DiscordDefaults.activeAppId()

    private val auth: DiscordAuth = DiscordAuth()

    private var gateway: DiscordGateway = createGateway(scope)

    private fun createGateway(scope: CoroutineScope): DiscordGateway =
        DiscordGateway(
            appId = DiscordDefaults.activeAppId(),
            tokenProvider = { formatTokenForGateway(accessToken ?: "") },
            externalScope = scope,
        )

    private fun formatTokenForGateway(raw: String): String {
        val trimmed = raw.trim().trim('"').trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            return "Bearer " + trimmed.substring(7).trim()
        }
        val hasRefreshToken = !DiscordTokenStore.getRefreshToken().isNullOrEmpty()
        return if (!hasRefreshToken || trimmed.count { it == '.' } >= 2 || trimmed.startsWith("mfa.")) {
            trimmed
        } else {
            "Bearer $trimmed"
        }
    }

    private fun startEventCollection() {
        scope.launch {
            gateway.events.collect { event -> handleGatewayEvent(event) }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        DiscordTokenStore.init(context.applicationContext)
        if (initialized && scope.isActive) {
            Log.i(TAG, "init: already initialized and active, skipping")
            return
        }
        if (!scope.isActive) {
            Log.i(TAG, "init: recreating scope after previous destroy")
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            gateway = createGateway(scope)
        }
        initialized = true
        _connectionStatus.value = Status.Disconnected
        startEventCollection()
        Log.i(TAG, "init: token store initialized, scheduling auto-rehydrate")

        scope.launch {
            val saved = DiscordTokenStore.retrieveSuspend()
            if (!saved.isNullOrEmpty()) {
                Log.i(TAG, "init: found persisted token, reconnecting")
                reconnectWithToken(saved)
            } else {
                Log.i(TAG, "init: no persisted token, waiting for explicit authorize")
            }
        }
    }

    fun authorize(activity: Activity, onComplete: (Boolean) -> Unit) {
        if (authorizeInProgress) {
            Log.w(TAG, "authorize: already in progress, ignoring double-tap")
            scope.launch(Dispatchers.Main) { onComplete(false) }
            return
        }
        authorizeInProgress = true

        fun completeWith(success: Boolean) {
            scope.launch(Dispatchers.Main) { onComplete(success) }
        }

        if (_ready && _authorized) {
            Log.d(TAG, "authorize: short-circuit — already ready and authorized")
            authorizeInProgress = false
            scope.launch(Dispatchers.Main) { onComplete(true) }
            return
        }
        if (_authorized) {
            Log.d(TAG, "authorize: short-circuit — authorized but not ready, reconnecting")
            authorizeInProgress = false
            reconnectWithToken(accessToken ?: "")
            scope.launch(Dispatchers.Main) { onComplete(true) }
            return
        }

        _connectionStatus.value = Status.Authorizing
        _lastError.value = null

        scope.launch {
            try {
                val result = auth.authorize(activity)
                DiscordTokenStore.storeFull(
                    result.accessToken,
                    result.refreshToken,
                    result.expiresInSec,
                )
                accessToken = result.accessToken
                _accessTokenFlow.value = result.accessToken
                _authorized = true

                try {
                    reconnectMutex.withLock {
                        runCatching { gateway.close(4000, "re-authorizing") }
                        gateway.connect()
                        gateway.identify(formatTokenForGateway(result.accessToken))
                    }
                    completeWith(true)
                } catch (e: Throwable) {
                    Log.e(TAG, "authorize: gateway connect/identify failed", e)
                    _lastError.value = "discord_error_loopback_timeout"
                    _connectionStatus.value = Status.Disconnected
                    _ready = false
                    _authorized = false
                    completeWith(false)
                }
            } catch (e: DiscordAuthException.InvalidClient) {
                Log.w(TAG, "authorize: invalid_client (Public Client disabled)", e)
                _lastError.value = "discord_error_public_client_off"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.InvalidScope) {
                Log.w(TAG, "authorize: invalid_scope", e)
                _lastError.value = "discord_error_invalid_scope"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.RedirectUriMismatch) {
                Log.w(TAG, "authorize: redirect_uri mismatch", e)
                _lastError.value = "discord_error_redirect_uri_mismatch"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.OAuthError) {
                Log.w(TAG, "authorize: oauth error (${e.errorCode})", e)
                _lastError.value = "Discord OAuth error (${e.errorCode}): ${e.errorDescription ?: "Please verify your Discord Developer Portal settings."}"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.Timeout) {
                Log.i(TAG, "authorize: timed out waiting for redirect")
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.UserCancelled) {
                Log.i(TAG, "authorize: user cancelled")
                _lastError.value = "discord_error_user_cancelled"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.StateMismatch) {
                Log.w(TAG, "authorize: state mismatch", e)
                _lastError.value = "discord_error_state_mismatch"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.NetworkFailure) {
                Log.e(TAG, "authorize: network failure", e)
                _lastError.value = "discord_error_network"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.NoBrowser) {
                Log.w(TAG, "authorize: no browser available", e)
                _lastError.value = "discord_error_no_browser"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: DiscordAuthException.InvalidGrant) {
                Log.w(TAG, "authorize: invalid grant", e)
                _lastError.value = "discord_error_token_refresh_failed"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } catch (e: Throwable) {
                Log.e(TAG, "authorize: unexpected failure", e)
                _lastError.value = "discord_error_loopback_timeout"
                _connectionStatus.value = Status.Disconnected
                completeWith(false)
            } finally {
                authorizeInProgress = false
            }
        }
    }

    fun cancelAuthorize() {
        Log.i(TAG, "cancelAuthorize: cancelling active authorization")
        auth.cancel()
    }

    fun fetchCurrentUser(token: String): DiscordUser? {
        return try {
            val url = URL("https://discord.com/api/v10/users/@me")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", formatTokenForGateway(token))
            conn.setRequestProperty("User-Agent", DiscordSuperProperties.USER_AGENT)
            conn.setRequestProperty("X-Super-Properties", DiscordSuperProperties.base64)
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()

            if (responseCode !in 200..299) {
                Log.w(TAG, "fetchCurrentUser: HTTP $responseCode body=$responseBody")
                return null
            }

            val json = JSONObject(responseBody)
            val id = json.getString("id")
            val username = json.getString("username")
            val name = json.optString("global_name", username).takeIf { it.isNotBlank() && it != "null" } ?: username
            val avatarHash = json.optString("avatar")
            val avatar = if (avatarHash.isNotEmpty() && avatarHash != "null") {
                "https://cdn.discordapp.com/avatars/$id/$avatarHash.png"
            } else null

            DiscordUser(id, username, name, avatar)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCurrentUser: exception", e)
            null
        }
    }

    fun setActivity(
        activity: DiscordActivity,
        songId: String? = null,
        isPlaying: Boolean = true,
        status: PresenceStatus = PresenceStatus.Online,
    ) {
        if (!_ready) {
            Log.w(TAG, "setActivity: skipping — not ready (name=${activity.name})")
            return
        }

        val hasEnd = (activity.endTimestamp ?: 0L) > 0L
        val stateChanged = songId != currentSongId ||
            isPlaying != currentIsPlaying ||
            (hasEnd && !currentHasEndTimestamp) ||
            (!activity.largeImage.isNullOrEmpty() && activity.largeImage != currentRawLargeImage)

        val now = System.currentTimeMillis()
        if (!stateChanged &&
            lastActivitySentAtMs > 0L && (now - lastActivitySentAtMs) < 1_500L
        ) {
            Log.v(TAG, "setActivity: debounced (<1.5s since last, stateChanged=false)")
            return
        }
        lastActivitySentAtMs = now

        currentSongId = songId
        currentIsPlaying = isPlaying
        currentRawLargeImage = activity.largeImage
        currentHasEndTimestamp = hasEnd
        val activityIdAtLaunch = currentActivityId.incrementAndGet()
        currentActivityHadImages = !activity.largeImage.isNullOrEmpty() || !activity.smallImage.isNullOrEmpty()

        val buttons = buildList {
            if (!activity.button1Label.isNullOrEmpty() && !activity.button1Url.isNullOrEmpty()) {
                add(activity.button1Label to activity.button1Url)
            }
            if (!activity.button2Label.isNullOrEmpty() && !activity.button2Url.isNullOrEmpty()) {
                add(activity.button2Label to activity.button2Url)
            }
        }

        imageResolutionJob?.cancel()

        val currentToken = accessToken
        val largeImageUrl = activity.largeImage
        val smallImageUrl = activity.smallImage

        imageResolutionJob = scope.launch {
            var largeResolved: String? = null
            var smallResolved: String? = null

            if (!currentToken.isNullOrEmpty() && (!largeImageUrl.isNullOrEmpty() || !smallImageUrl.isNullOrEmpty())) {
                val tokenHeader = formatTokenForGateway(currentToken)
                kotlinx.coroutines.withTimeoutOrNull(3_500L) {
                    if (!largeImageUrl.isNullOrEmpty()) {
                        largeResolved = DiscordExternalAssets.resolve(largeImageUrl, appId, tokenHeader)
                        if (largeResolved == null && !songId.isNullOrBlank()) {
                            val ytFallback = "https://i.ytimg.com/vi/$songId/hqdefault.jpg"
                            if (ytFallback != largeImageUrl) {
                                largeResolved = DiscordExternalAssets.resolve(ytFallback, appId, tokenHeader)
                            }
                        }
                    }
                    if (!smallImageUrl.isNullOrEmpty()) {
                        smallResolved = DiscordExternalAssets.resolveSmallImage(
                            smallImageKeyOrUrl = smallImageUrl,
                            appId = appId,
                            token = tokenHeader,
                            context = appContext,
                        )
                    }
                }
            }

            if (activityIdAtLaunch != currentActivityId.get()) {
                Log.d(TAG, "setActivity: superseded before send (id=$activityIdAtLaunch vs ${currentActivityId.get()})")
                return@launch
            }

            val payload = DiscordPresence.buildActivity(
                applicationId = appId,
                name = activity.name.orEmpty(),
                type = activityTypeToEnum(activity.activityType),
                details = activity.details,
                state = activity.state,
                largeImage = largeResolved,
                largeText = activity.largeText,
                smallImage = smallResolved,
                smallText = activity.smallText,
                startMs = activity.startTimestamp.takeIf { it > 0L },
                endMs = activity.endTimestamp?.takeIf { it > 0L },
                buttons = buttons,
            )

            lastActivity = payload

            try {
                val presenceJson = DiscordPresence.buildPresenceUpdate(
                    status = status,
                    activities = listOf(payload),
                )
                Log.i(
                    TAG,
                    "setActivity: sending (type=${activity.activityType}, name=${activity.name}, details=${activity.details}, state=${activity.state}, songId=$songId, hasImage=${largeResolved != null}, hasEnd=$hasEnd)"
                )
                gateway.presenceUpdate(presenceJson)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "setActivity: gateway not open", e)
            } catch (e: Throwable) {
                Log.e(TAG, "setActivity: send failed", e)
            }
        }
    }

    fun clear() {
        if (!_ready) {
            Log.w(TAG, "clear: skipping — not ready")
            return
        }
        if (lastActivity == null && currentSongId == null) {
            Log.d(TAG, "clear: already cleared, skipping")
            return
        }
        lastActivity = null
        currentSongId = null
        currentIsPlaying = false
        currentRawLargeImage = null
        currentHasEndTimestamp = false
        currentActivityHadImages = false
        currentActivityId.incrementAndGet()
        imageResolutionJob?.cancel()
        try {
            gateway.presenceUpdate(
                DiscordPresence.buildPresenceUpdate(
                    status = PresenceStatus.Online,
                    activities = emptyList(),
                ),
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "clear: gateway not open", e)
        } catch (e: Throwable) {
            Log.e(TAG, "clear: send failed", e)
        }
    }

    fun reconnectWithToken(token: String) {
        if (!initialized) {
            Log.w(TAG, "reconnectWithToken: not initialized, ignoring")
            return
        }

        scope.launch {
            reconnectMutex.withLock {
                accessToken = token
                _accessTokenFlow.value = token
                DiscordTokenStore.storeAccessToken(token)
                _connectionStatus.value = Status.Authorizing
                try {
                    val refreshToken = DiscordTokenStore.getRefreshToken()
                    val expiresAt = DiscordTokenStore.getExpiresAt()
                    val nowSec = System.currentTimeMillis() / 1000L
                    val needsRefresh = !refreshToken.isNullOrEmpty() &&
                        expiresAt > 0L &&
                        (expiresAt - nowSec) < 3600L

                    Log.i(
                        TAG,
                        "reconnectWithToken: hasRefreshToken=${!refreshToken.isNullOrEmpty()}, expiresAt=$expiresAt, now=$nowSec, needsRefresh=$needsRefresh"
                    )

                    if (needsRefresh) {
                        Log.i(TAG, "reconnectWithToken: proactive token refresh")
                        val refreshed = try {
                            auth.refresh(refreshToken)
                        } catch (e: DiscordAuthException.InvalidGrant) {
                            Log.w(TAG, "reconnectWithToken: refresh invalid_grant, logging out", e)
                            _lastError.value = "discord_error_token_refresh_failed"
                            logout()
                            return@withLock
                        } catch (e: Throwable) {
                            Log.w(TAG, "reconnectWithToken: refresh failed, continuing with old token", e)
                            null
                        }
                        if (refreshed != null) {
                            Log.i(
                                TAG,
                                "reconnectWithToken: refresh succeeded (new token length=${refreshed.accessToken.length}, expiresIn=${refreshed.expiresInSec})"
                            )
                            accessToken = refreshed.accessToken
                            _accessTokenFlow.value = refreshed.accessToken
                            DiscordTokenStore.storeFull(
                                refreshed.accessToken,
                                refreshed.refreshToken,
                                refreshed.expiresInSec,
                            )
                        }
                    }

                    runCatching { gateway.close(4000, "reconnecting") }
                    gateway.connect()
                    gateway.identify(formatTokenForGateway(accessToken ?: token))
                } catch (e: Throwable) {
                    Log.e(TAG, "reconnectWithToken: connect/identify failed", e)
                    _lastError.value = "discord_error_loopback_timeout"
                    _connectionStatus.value = Status.Disconnected
                }
            }
        }
    }

    private suspend fun refreshAndReconnect() {
        val refreshToken = DiscordTokenStore.getRefreshToken()
        if (refreshToken.isNullOrEmpty()) {
            Log.w(TAG, "refreshAndReconnect: no refresh token available, logging out")
            _lastError.value = "discord_error_token_refresh_failed"
            logout()
            return
        }

        val refreshed = try {
            auth.refresh(refreshToken)
        } catch (e: DiscordAuthException.InvalidGrant) {
            Log.w(TAG, "refreshAndReconnect: refresh token rejected, logging out", e)
            _lastError.value = "discord_error_token_refresh_failed"
            logout()
            return
        } catch (e: Throwable) {
            Log.e(TAG, "refreshAndReconnect: token refresh failed", e)
            _lastError.value = "discord_error_token_refresh_failed"
            return
        }

        Log.i(
            TAG,
            "refreshAndReconnect: refresh succeeded (token length=${refreshed.accessToken.length}, expiresIn=${refreshed.expiresInSec}), reconnecting"
        )
        DiscordTokenStore.storeFull(
            refreshed.accessToken,
            refreshed.refreshToken,
            refreshed.expiresInSec,
        )
        reconnectWithToken(refreshed.accessToken)
    }

    fun disconnect() {
        Log.i(TAG, "disconnect: closing gateway, clearing ready/authorized")
        currentActivityId.incrementAndGet()
        imageResolutionJob?.cancel()
        runCatching { gateway.close(1000, "user disconnect") }
        _connectionStatus.value = Status.Disconnected
        _ready = false
        _authorized = false
        currentSongId = null
        currentIsPlaying = false
        currentRawLargeImage = null
        currentHasEndTimestamp = false
        currentActivityHadImages = false
    }

    fun destroy() {
        Log.i(TAG, "destroy: cancelling scope and tearing down (initialized=$initialized)")
        currentActivityId.incrementAndGet()
        imageResolutionJob?.cancel()
        runCatching { gateway.close(1000, "destroy") }
        runCatching { gateway.closeHttp() }
        scope.cancel()
        _ready = false
        _authorized = false
        initialized = false
        _connectionStatus.value = Status.Disconnected
        lastActivity = null
        currentSongId = null
        currentIsPlaying = false
        currentRawLargeImage = null
        currentHasEndTimestamp = false
        currentActivityHadImages = false
    }

    fun logout() {
        Log.i(TAG, "logout: clearing tokens and disconnecting")
        disconnect()
        accessToken = null
        _accessTokenFlow.value = null
        _currentUser.value = null
        DiscordTokenStore.clear()
        DiscordSuperProperties.reset()
        _lastError.value = null
        lastActivity = null
        currentActivityHadImages = false
    }

    private suspend fun handleGatewayEvent(event: GatewayEvent) {
        when (event) {
            is GatewayEvent.Ready -> {
                Log.i(TAG, "gateway: READY (sessionId prefix=${event.sessionId.take(8)})")
                _ready = true
                _authorized = true
                _connectionStatus.value = Status.Connected
                _lastError.value = null
                val token = accessToken ?: return
                scope.launch {
                    val user = fetchCurrentUser(token)
                    _currentUser.value = user
                    if (user != null) {
                        Log.i(TAG, "gateway READY: fetched user ${user.username}")
                    }
                }
            }
            is GatewayEvent.Resumed -> {
                Log.i(TAG, "gateway: RESUMED")
                _ready = true
                _authorized = true
                _connectionStatus.value = Status.Connected
                _lastError.value = null
            }
            is GatewayEvent.Disconnected -> {
                Log.i(
                    TAG,
                    "gateway: Disconnected (code=${event.code}, remote=${event.remote}, reason=${event.reason})"
                )
                _ready = false
                _authorized = false
                _connectionStatus.value = Status.Disconnected
                currentSongId = null
                currentIsPlaying = false
                currentRawLargeImage = null
                currentHasEndTimestamp = false
                imageResolutionJob?.cancel()
                imageResolutionJob = null
                if (event.code in setOf(4001, 4004) && event.reason.contains("max reconnect", ignoreCase = true)) {
                    _lastError.value = when (event.code) {
                        4004 -> "discord_error_token_refresh_failed"
                        4001 -> "discord_error_invalid_scope"
                        else -> _lastError.value
                    }
                }
            }
            is GatewayEvent.InvalidSession -> {
                Log.w(TAG, "gateway: InvalidSession (resumable=${event.resumable}), closing WS to trigger reconnect")
                imageResolutionJob?.cancel()
                imageResolutionJob = null
            }
            is GatewayEvent.RefreshToken -> {
                Log.w(TAG, "gateway: RefreshToken requested, refreshing and reconnecting")
                scope.launch { refreshAndReconnect() }
            }
            is GatewayEvent.Hello -> Unit
            is GatewayEvent.HeartbeatAck -> Unit
            is GatewayEvent.TextDispatch -> {
                Log.v(TAG, "gateway: TextDispatch op=${event.op} t=${event.t}")
            }
        }
    }

    private fun activityTypeToEnum(code: Int): ActivityType = when (code) {
        0 -> ActivityType.Playing
        1 -> ActivityType.Streaming
        2 -> ActivityType.Listening
        3 -> ActivityType.Watching
        4 -> ActivityType.Custom
        5 -> ActivityType.Competing
        else -> ActivityType.Listening
    }
}
