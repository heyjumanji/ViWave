package app.vitune.android.utils.discord

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

sealed interface GatewayEvent {
    data class Hello(val heartbeatIntervalMs: Long) : GatewayEvent
    data class Ready(val sessionId: String, val resumeGatewayUrl: String?) : GatewayEvent
    data class Resumed(val sessionId: String) : GatewayEvent
    data class HeartbeatAck(val lastSeq: Int?) : GatewayEvent
    data class InvalidSession(val resumable: Boolean) : GatewayEvent
    data class Disconnected(val code: Int, val reason: String, val remote: Boolean) : GatewayEvent
    data object RefreshToken : GatewayEvent
    data class TextDispatch(val op: Int, val t: String?, val d: JSONObject) : GatewayEvent
}

class DiscordGateway(
    private val appId: String,
    private val tokenProvider: suspend () -> String,
    private val externalScope: CoroutineScope,
) {

    private val _events = MutableSharedFlow<GatewayEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    @Volatile
    private var _currentSeq: Int = 0
    val currentSeq: Int get() = _currentSeq

    @Volatile
    private var _sessionId: String? = null
    val sessionId: String? get() = _sessionId

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var isOpen: Boolean = false

    private val webSocketIdCounter = AtomicLong(0L)

    @Volatile
    private var activeWebSocketId: Long = 0L

    @Volatile
    private var gatewayUrl: String = DEFAULT_GATEWAY_URL

    @Volatile
    private var reconnectAttempts: Int = 0

    @Volatile
    private var heartbeatJob: Job? = null

    private val lastAckAtMs = AtomicLong(0L)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun connect() {
        val myId = webSocketIdCounter.incrementAndGet()
        activeWebSocketId = myId
        val openDeferred = CompletableDeferred<Unit>()
        val request = Request.Builder().url(gatewayUrl).build()
        val listener = createListener(openDeferred, myId)
        val ws = httpClient.newWebSocket(request, listener)
        webSocket = ws
        try {
            openDeferred.await()
            Log.i(TAG, "connect: WS opened (id=$myId), gatewayUrl=$gatewayUrl")
        } catch (e: Throwable) {
            Log.e(TAG, "connect: failed to open WS (id=$myId)", e)
            runCatching { ws.cancel() }
            webSocket = null
            throw e
        }
    }

    fun close(code: Int = 1000, reason: String? = null) {
        Log.i(TAG, "close: code=$code, reason=${reason ?: ""}")
        heartbeatJob?.cancel()
        heartbeatJob = null
        val ws = webSocket
        webSocket = null
        isOpen = false
        activeWebSocketId = webSocketIdCounter.incrementAndGet()
        if (ws != null) {
            runCatching {
                if (reason != null) ws.close(code, reason) else ws.close(code, null)
            }
        }
    }

    fun send(frameJson: String) {
        val ws = webSocket
        if (ws == null || !isOpen) {
            Log.w(TAG, "send: WebSocket not open (ws=$ws, isOpen=$isOpen)")
            throw IllegalStateException("DiscordGateway: WebSocket is not open")
        }
        Log.v(TAG, "send: frame (length=${frameJson.length})")
        val ok = ws.send(frameJson)
        if (!ok) {
            Log.w(TAG, "send: WebSocket send returned false (queue full or closing)")
            throw IllegalStateException("DiscordGateway: WebSocket send returned false (queue full or closing)")
        }
    }

    suspend fun identify(token: String) {
        val frame = buildIdentifyFrame(token)
        send(frame)
        Log.i(TAG, "identify: IDENTIFY sent (token length=${token.length})")
    }

    fun presenceUpdate(presenceJson: String) {
        send(presenceJson)
    }

    suspend fun resume(sessionId: String, seq: Int, token: String) {
        val frame = buildResumeFrame(sessionId, seq, token)
        send(frame)
        Log.i(TAG, "resume: RESUME sent (sessionId prefix=${sessionId.take(8)}, seq=$seq)")
    }

    fun heartbeat(seq: Int) {
        Log.d(TAG, "heartbeat: sending seq=$seq")
        val frame = buildHeartbeatFrame(seq)
        send(frame)
    }

    fun setGatewayUrl(url: String) {
        gatewayUrl = url
        Log.i(TAG, "setGatewayUrl: $url")
    }

    fun closeHttp() {
        runCatching {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        }
    }

    private fun createListener(openDeferred: CompletableDeferred<Unit>, wsId: Long): WebSocketListener =
        object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (wsId != activeWebSocketId) return
                Log.i(TAG, "onOpen: response.code=${response.code}, wsId=$wsId")
                this@DiscordGateway.webSocket = webSocket
                isOpen = true
                lastAckAtMs.set(System.currentTimeMillis())
                openDeferred.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (wsId != activeWebSocketId) return
                externalScope.launch {
                    try {
                        handleFrame(text)
                    } catch (e: Throwable) {
                        Log.e(TAG, "onMessage: failed to handle text frame", e)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.w(TAG, "onMessage: binary frame received (${bytes.size} bytes), ignoring")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "onClosing: code=$code, reason=$reason, wsId=$wsId")
                runCatching { webSocket.close(1000, null) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "onClosed: code=$code, reason=$reason, wsId=$wsId")
                externalScope.launch {
                    handleClose(code, reason, remote = true, closedWebSocketId = wsId)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (wsId != activeWebSocketId) return
                Log.e(TAG, "onFailure: response=${response?.code}, wsId=$wsId", t)
                if (!openDeferred.isCompleted) {
                    openDeferred.completeExceptionally(t)
                }
                externalScope.launch {
                    val code = response?.code ?: 4000
                    val retryAfter = response?.header("Retry-After")
                    val reason = if (retryAfter != null) {
                        "${t.message ?: "failure"};retry_after=$retryAfter"
                    } else {
                        t.message ?: "failure"
                    }
                    handleClose(code, reason, remote = false, closedWebSocketId = wsId)
                }
            }
        }

    private suspend fun handleFrame(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Throwable) {
            Log.e(TAG, "handleFrame: invalid JSON", e)
            return
        }

        val op = json.optInt("op", -1)
        val d: JSONObject? = json.optJSONObject("d")
        val t: String? = if (json.has("t")) json.optString("t") else null

        Log.v(TAG, "handleFrame: op=$op t=${t ?: ""} seq=${json.optInt("s", 0)}")

        if (json.has("s") && !json.isNull("s")) {
            val seq = json.optInt("s", 0)
            if (seq > 0) _currentSeq = seq
        }

        when (op) {
            HELLO -> {
                val interval = d?.optLong("heartbeat_interval", DEFAULT_HEARTBEAT_MS)
                    ?: DEFAULT_HEARTBEAT_MS
                Log.d(TAG, "handleFrame: HELLO received, heartbeatInterval=${interval}ms")
                startHeartbeat(interval)
                _events.emit(GatewayEvent.Hello(interval))
            }
            HEARTBEAT_ACK -> {
                lastAckAtMs.set(System.currentTimeMillis())
                _events.emit(GatewayEvent.HeartbeatAck(_currentSeq))
            }
            DISPATCH -> {
                when (t) {
                    "READY" -> {
                        val data = d ?: JSONObject()
                        val sessionId = data.optString("session_id", "")
                        val resumeUrl: String? = data.optString("resume_gateway_url", "")
                            .takeIf { it.isNotEmpty() }
                        _sessionId = sessionId
                        Log.d(
                            TAG,
                            "handleFrame: READY parsed (sessionId prefix=${sessionId.take(8)}, resumeUrl=${resumeUrl?.take(60)})"
                        )
                        if (resumeUrl != null) setGatewayUrl(resumeUrl)
                        reconnectAttempts = 0
                        _events.emit(GatewayEvent.Ready(sessionId, resumeUrl))
                    }
                    "RESUMED" -> {
                        reconnectAttempts = 0
                        Log.d(TAG, "handleFrame: RESUMED parsed (sessionId prefix=${_sessionId?.take(8)})")
                        _events.emit(GatewayEvent.Resumed(_sessionId.orEmpty()))
                    }
                    else -> {
                        _events.emit(GatewayEvent.TextDispatch(op, t, d ?: JSONObject()))
                    }
                }
            }
            INVALID_SESSION -> {
                val resumable = (json.opt("d") as? Boolean) ?: false
                Log.w(TAG, "INVALID_SESSION: resumable=$resumable")
                if (!resumable) {
                    _sessionId = null
                }
                _events.emit(GatewayEvent.InvalidSession(resumable))
                webSocket?.close(4000, "invalid session")
            }
            HEARTBEAT -> {
                heartbeat(_currentSeq)
            }
            RECONNECT -> {
                Log.w(TAG, "RECONNECT requested by server, closing 4000")
                webSocket?.close(4000, "reconnect requested")
            }
            else -> {
                _events.emit(GatewayEvent.TextDispatch(op, t, d ?: JSONObject()))
            }
        }
    }

    private suspend fun handleClose(code: Int, reason: String, remote: Boolean, closedWebSocketId: Long) {
        if (closedWebSocketId != activeWebSocketId) {
            Log.i(
                TAG,
                "handleClose: ignoring stale WS close (closedId=$closedWebSocketId, activeId=$activeWebSocketId, code=$code)"
            )
            return
        }
        if (!isOpen && webSocket == null) {
            return
        }
        isOpen = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket = null

        _events.emit(GatewayEvent.Disconnected(code, reason, remote))

        if (code == 1000 && remote) {
            Log.d(TAG, "handleClose: clean remote close (code=1000), resetting session")
            _sessionId = null
            _currentSeq = 0
            return
        }

        val action = DiscordReconnectStrategy.decide(
            closeCode = code,
            hadSession = _sessionId != null,
            seq = _currentSeq,
            sessionId = _sessionId,
        )
        Log.i(TAG, "handleClose: strategy=${action::class.simpleName} for closeCode=$code")

        when (action) {
            is ReconnectAction.SurfaceFatal -> {
                Log.w(TAG, "SurfaceFatal for closeCode=$code, giving up")
                _sessionId = null
                _currentSeq = 0
            }
            is ReconnectAction.RefreshAndReIdentify -> {
                Log.w(TAG, "RefreshAndReIdentify for closeCode=$code, delegating to manager")
                _events.emit(GatewayEvent.RefreshToken)
            }
            is ReconnectAction.Resume,
            is ReconnectAction.ReIdentify -> {
                if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    Log.w(TAG, "max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS), giving up")
                    _events.emit(
                        GatewayEvent.Disconnected(4000, "max reconnect attempts", remote = false),
                    )
                    return
                }
                reconnectAttempts++
                val delay = if (code == 429) {
                    val retryAfter = parseRetryAfter(reason)
                    retryAfter.coerceAtLeast(60_000L)
                } else {
                    reconnectDelayMs(reconnectAttempts)
                }
                Log.i(TAG, "handleClose: reconnecting in ${delay}ms (attempt $reconnectAttempts, code=$code)")
                delay(delay)
                performReconnect(action)
            }
        }
    }

    private suspend fun performReconnect(action: ReconnectAction) {
        try {
            connect()
        } catch (e: Throwable) {
            Log.e(TAG, "performReconnect: connect failed", e)
            return
        }

        when (action) {
            is ReconnectAction.Resume -> {
                try {
                    val token = tokenProvider()
                    resume(action.sessionId, action.seq, token)
                } catch (e: Throwable) {
                    Log.e(TAG, "performReconnect: resume failed", e)
                    webSocket?.close(1011, "resume failed")
                }
            }
            is ReconnectAction.ReIdentify -> {
                try {
                    val token = tokenProvider()
                    identify(token)
                } catch (e: Throwable) {
                    Log.e(TAG, "performReconnect: identify failed", e)
                    webSocket?.close(1011, "identify failed")
                }
            }
            is ReconnectAction.SurfaceFatal -> {}
            is ReconnectAction.RefreshAndReIdentify -> {}
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        val jittered = applyJitter(intervalMs, JITTER_RATIO)
        Log.i(TAG, "startHeartbeat: interval=${intervalMs}ms, jittered=${jittered}ms")
        lastAckAtMs.set(System.currentTimeMillis())
        heartbeatJob = externalScope.launch {
            var lastSentAt = System.currentTimeMillis()
            while (isActive && isOpen) {
                delay(jittered)
                if (!isActive || !isOpen) break
                val lastAck = lastAckAtMs.get()
                if (lastAck < lastSentAt) {
                    Log.w(TAG, "heartbeat: no ACK in $jittered ms, closing with 4000")
                    webSocket?.close(4000, "heartbeat timeout")
                    break
                }
                lastSentAt = System.currentTimeMillis()
                runCatching { heartbeat(_currentSeq) }
                    .onFailure { Log.w(TAG, "heartbeat: send failed", it) }
            }
        }
    }

    private fun buildIdentifyFrame(token: String): String {
        val d = JSONObject()
        d.put("token", token)
        d.put("intents", 0)
        val props = JSONObject()
        props.put("os", "android")
        props.put("browser", "Discord Android")
        props.put("device", appId)
        d.put("properties", props)
        d.put("compress", false)
        return wrapOp(IDENTIFY, d)
    }

    private fun buildResumeFrame(sessionId: String, seq: Int, token: String): String {
        val d = JSONObject()
        d.put("token", token)
        d.put("session_id", sessionId)
        d.put("seq", seq)
        return wrapOp(RESUME, d)
    }

    private fun buildHeartbeatFrame(seq: Int): String {
        val root = JSONObject()
        root.put("op", HEARTBEAT)
        if (seq > 0) {
            root.put("d", seq)
        } else {
            root.put("d", JSONObject.NULL)
        }
        return root.toString()
    }

    private fun wrapOp(op: Int, d: JSONObject): String {
        val root = JSONObject()
        root.put("op", op)
        root.put("d", d)
        return root.toString()
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val base = (RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1)))
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        return applyJitter(base, 0.25)
    }

    private fun parseRetryAfter(reason: String): Long {
        val prefix = ";retry_after="
        val idx = reason.indexOf(prefix)
        if (idx < 0) return 60_000L
        val value = reason.substring(idx + prefix.length).trim()
        val seconds = value.substringBefore(';').substringBefore(',').toDoubleOrNull()
        return if (seconds != null) (seconds * 1000.0).toLong().coerceAtLeast(60_000L) else 60_000L
    }

    private fun applyJitter(intervalMs: Long, ratio: Double): Long {
        if (intervalMs <= 0L) return intervalMs
        val delta = (intervalMs * ratio).toLong()
        if (delta <= 0L) return intervalMs
        val offset = abs(Random.nextLong(delta + 1))
        val sign = if (Random.nextBoolean()) -1L else 1L
        return intervalMs + sign * offset
    }

    companion object {
        private const val TAG = "DiscordSvc"

        private const val DEFAULT_GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val DEFAULT_HEARTBEAT_MS = 41250L
        private const val JITTER_RATIO = 0.05
        private const val MAX_RECONNECT_ATTEMPTS = 7
        private const val RECONNECT_BASE_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 64_000L

        private const val DISPATCH = 0
        private const val HEARTBEAT = 1
        private const val IDENTIFY = 2
        private const val PRESENCE_UPDATE = 3
        private const val VOICE_STATE = 4
        private const val RESUME = 6
        private const val RECONNECT = 7
        private const val INVALID_SESSION = 9
        private const val HELLO = 10
        private const val HEARTBEAT_ACK = 11
    }
}
