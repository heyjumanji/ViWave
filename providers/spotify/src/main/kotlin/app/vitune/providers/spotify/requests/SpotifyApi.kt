package app.vitune.providers.spotify.requests

import android.util.Base64
import app.vitune.providers.spotify.auth.SpotifyTotp
import app.vitune.providers.spotify.models.SpotifyAlbumSimple
import app.vitune.providers.spotify.models.SpotifyArtist
import app.vitune.providers.spotify.models.SpotifyImage
import app.vitune.providers.spotify.models.SpotifyOwner
import app.vitune.providers.spotify.models.SpotifyPlaylist
import app.vitune.providers.spotify.models.SpotifyPlaylistItem
import app.vitune.providers.spotify.models.SpotifyPlaylistTracksRef
import app.vitune.providers.spotify.models.SpotifyPlaylistTracksResponse
import app.vitune.providers.spotify.models.SpotifyPlaylistsResponse
import app.vitune.providers.spotify.models.SpotifySavedTrackItem
import app.vitune.providers.spotify.models.SpotifySavedTracksResponse
import app.vitune.providers.spotify.models.SpotifySearchResponse
import app.vitune.providers.spotify.models.SpotifyTokenResponse
import app.vitune.providers.spotify.models.SpotifyTrack
import app.vitune.providers.spotify.models.SpotifyUserProfile
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Rebuilt Spotify integration modeled after rawnaldclark/Stash:
 * 1. Client Credentials OAuth2 flow (SpotDL public client) for public playlist tracks via /v1/playlists/{id}/tracks
 * 2. sp_dc WebPlayer token + Client-Token (via HttpURLConnection to clienttoken.spotify.com) +
 *    GraphQL Partner API (https://api-partner.spotify.com/pathfinder/v1/query) with live bundle hash re-scraping
 * 3. Spotify Embed __NEXT_DATA__ scraper fallback for zero-auth public playlist extraction
 */
object SpotifyApi {
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private const val SPOTIFY_TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val SPOTIFY_API_BASE_URL = "https://api.spotify.com/v1"
    private const val SPOTIFY_PARTNER_API_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val SPOTIFY_CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val SPOTIFY_OPEN_URL = "https://open.spotify.com"

    // SpotDL public OAuth2 credentials used by rawnaldclark/Stash for client_credentials flow
    private const val SPOTDL_CLIENT_ID = "5f573c9620494bae87890c0f08a60293"
    private const val SPOTDL_CLIENT_SECRET = "212476d9b0f3472eaa762d90b19b0ba8"

    // Verified Seed Hashes from open.spotify.com web-player bundle
    private const val HASH_LIBRARY_V3 = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
    private const val HASH_FETCH_PLAYLIST = "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"
    private const val HASH_FETCH_LIBRARY_TRACKS = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    private const val HASH_SEARCH_DESKTOP = "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49"
    private const val HASH_HOME = "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1"
    private const val HASH_LIBRARY_MUTATION = "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148"
    private const val HASH_PROFILE_ATTRIBUTES = "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0"
    private const val HASH_QUERY_WHATS_NEW_FEED = "d889c8c936ab192af8ced595427f5ba2acdf63478fdc0a181c8d477f8322630e"
    private const val HASH_QUERY_ARTIST_OVERVIEW = "eafb1620d783ece118e72fbfede2b0064483e029d3c1aafafa1c2de0e25a89e7"
    private const val HASH_GET_ALBUM = "0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f"
    private const val HASH_GET_TRACK = "e7b1bf4e95cb3d733d48b442f93006e9712fd504e8b88d7a82e6e3bac9306a6b"
    private const val HASH_CANVAS = "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f"
    private const val HASH_ADD_TO_PLAYLIST = "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a"
    private const val HASH_SAVE_RECENT_SEARCHES = "ece96d7b82c820fb9938dd1df50dee3e53737d808c3883e3d01c467c423601b0"

    private const val FALLBACK_CLIENT_VERSION = "1.2.87.311.g2db0c2c4"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonParser)
        }
    }

    private val authMutex = Mutex()
    private val hashMap = mutableMapOf(
        "libraryV3" to HASH_LIBRARY_V3,
        "fetchPlaylist" to HASH_FETCH_PLAYLIST,
        "fetchLibraryTracks" to HASH_FETCH_LIBRARY_TRACKS,
        "searchDesktop" to HASH_SEARCH_DESKTOP,
        "home" to HASH_HOME,
        "addToLibrary" to HASH_LIBRARY_MUTATION,
        "removeFromLibrary" to HASH_LIBRARY_MUTATION,
        "profileAttributes" to HASH_PROFILE_ATTRIBUTES,
        "queryWhatsNewFeed" to HASH_QUERY_WHATS_NEW_FEED,
        "queryArtistOverview" to HASH_QUERY_ARTIST_OVERVIEW,
        "getAlbum" to HASH_GET_ALBUM,
        "getTrack" to HASH_GET_TRACK,
        "canvas" to HASH_CANVAS,
        "addToPlaylist" to HASH_ADD_TO_PLAYLIST,
        "saveRecentSearches" to HASH_SAVE_RECENT_SEARCHES
    )

    @Volatile
    private var cachedClientId: String = ""

    @Volatile
    private var spTDeviceId: String? = null

    @Volatile
    private var cachedClientToken: String? = null

    @Volatile
    private var cachedClientVersion: String? = null

    @Volatile
    private var cachedClientCredentialsToken: String? = null

    @Volatile
    private var cachedClientCredentialsExpiryMs: Long = 0L

    @Volatile
    private var cachedSpDcAccessToken: String? = null

    @Volatile
    private var cachedSpDcTokenExpiryMs: Long = 0L

    @Volatile
    private var cachedAnonymousWebToken: String? = null

    @Volatile
    private var cachedAnonymousWebTokenExpiryMs: Long = 0L

    fun updateSessionMetadata(clientId: String, spT: String? = null) {
        if (clientId.isNotBlank()) cachedClientId = clientId
        if (!spT.isNullOrBlank()) spTDeviceId = spT
    }

    fun clearSessionCache() {
        cachedClientToken = null
        spTDeviceId = null
        cachedClientId = ""
        cachedSpDcAccessToken = null
        cachedSpDcTokenExpiryMs = 0L
    }

    private suspend fun getServerTime(): Long {
        return try {
            val response = client.head("https://open.spotify.com/api/token") {
                header("User-Agent", WEB_USER_AGENT)
            }
            val dateHeader = response.headers["Date"] ?: return System.currentTimeMillis() / 1000
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            val date = sdf.parse(dateHeader) ?: return System.currentTimeMillis() / 1000
            date.time / 1000
        } catch (_: Exception) {
            System.currentTimeMillis() / 1000
        }
    }

    fun normalizeSpDcCookie(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.contains("sp_dc=")) {
            return trimmed
                .substringAfter("sp_dc=")
                .substringBefore(";")
                .trim()
        }
        return trimmed.trim('"', '\'', ';', ' ')
    }

    /**
     * Extracts a Spotify playlist ID from a full URL, URI, embed URL, or raw 22-char ID.
     */
    fun extractPlaylistId(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null

        val uriMatch = Regex("spotify:(?:user:[^:]+:)?playlist:([A-Za-z0-9]{22})").find(trimmed)
        if (uriMatch != null) return uriMatch.groupValues[1]

        val urlMatch = Regex("spotify\\.com/(?:intl-[a-zA-Z-]+/)?(?:embed/)?playlist/([A-Za-z0-9]{22})").find(trimmed)
        if (urlMatch != null) return urlMatch.groupValues[1]

        val rawMatch = Regex("^([A-Za-z0-9]{22})$").find(trimmed)
        if (rawMatch != null) return rawMatch.groupValues[1]

        return null
    }

    suspend fun resolvePlaylistId(rawInput: String): String? {
        extractPlaylistId(rawInput)?.let { return it }
        val trimmed = rawInput.trim()
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            return try {
                val parsedHost = URL(trimmed).host.lowercase(Locale.US)
                val isAllowedSpotifyHost = parsedHost == "spotify.link" ||
                    parsedHost == "spoti.fi" ||
                    parsedHost == "spotify.com" ||
                    parsedHost.endsWith(".spotify.com") ||
                    parsedHost.endsWith(".spotify.link")
                if (!isAllowedSpotifyHost) return null

                val response = client.get(trimmed) {
                    header("User-Agent", WEB_USER_AGENT)
                }
                val finalUrl = response.call.request.url.toString()
                extractPlaylistId(finalUrl) ?: extractPlaylistId(response.bodyAsText())
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Extracts the Spotify username from a JWT access token's 'sub' or 'username' claim,
     * avoiding /v1/me which is rate-limited (429) for sp_dc WebPlayer tokens.
     */
    fun extractUsernameFromJwt(jwt: String): String? {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payloadBytes = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val payload = jsonParser.parseToJsonElement(String(payloadBytes, Charsets.UTF_8)).jsonObject
            payload["sub"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: payload["username"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Prong 1: Fetches a Client Credentials OAuth2 token using SpotDL's client ID/secret.
     * Used for fetching public playlist tracks via https://api.spotify.com/v1/playlists/{id}/tracks.
     */
    suspend fun getClientCredentialsToken(): String? = authMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = cachedClientCredentialsToken
        if (!existing.isNullOrBlank() && now + 60_000L < cachedClientCredentialsExpiryMs) {
            return@withLock existing
        }

        return@withLock try {
            val credentials = "$SPOTDL_CLIENT_ID:$SPOTDL_CLIENT_SECRET"
            val basicAuth = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val response = client.post(SPOTIFY_TOKEN_URL) {
                header("Authorization", "Basic $basicAuth")
                header("Content-Type", "application/x-www-form-urlencoded")
                setBody("grant_type=client_credentials")
            }

            if (!response.status.isSuccess()) return@withLock null
            val json = jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
            val token = json["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@withLock null
            val expiresInSec = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L

            cachedClientCredentialsToken = token
            cachedClientCredentialsExpiryMs = now + expiresInSec * 1000L
            token
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Scrapes the current Spotify WebPlayer clientVersion from https://open.spotify.com HTML
     * (checking direct JSON or Base64-encoded <script id="appServerConfig">).
     */
    private suspend fun getClientVersion(): String {
        cachedClientVersion?.let { return it }
        return try {
            val html = client.get(SPOTIFY_OPEN_URL) {
                header("User-Agent", WEB_USER_AGENT)
            }.bodyAsText()

            val directMatch = Regex(""""clientVersion"\s*:\s*"([^"]+)"""").find(html)
            if (directMatch != null) {
                return directMatch.groupValues[1].also { cachedClientVersion = it }
            }

            val b64Match = Regex(
                """<script id="appServerConfig"[^>]*>([A-Za-z0-9+/=\s]+)</script>"""
            ).find(html)
            if (b64Match != null) {
                val decoded = String(Base64.decode(b64Match.groupValues[1].trim(), Base64.DEFAULT), Charsets.UTF_8)
                val verMatch = Regex(""""clientVersion"\s*:\s*"([^"]+)"""").find(decoded)
                if (verMatch != null) {
                    return verMatch.groupValues[1].also { cachedClientVersion = it }
                }
            }

            FALLBACK_CLIENT_VERSION.also { cachedClientVersion = it }
        } catch (_: Exception) {
            FALLBACK_CLIENT_VERSION.also { cachedClientVersion = it }
        }
    }

    /**
     * Acquires Spotify's mandatory `Client-Token` header for `api-partner.spotify.com/pathfinder/v1/query`.
     * Uses Android's native `HttpURLConnection` (BoringSSL TLS stack) just like rawnaldclark/Stash
     * so `clienttoken.spotify.com` does not reject the TLS fingerprint.
     */
    private suspend fun getClientToken(clientId: String): String? {
        cachedClientToken?.let { return it }
        val resolvedClientId = clientId
            .ifBlank { cachedClientId }
            .ifBlank { "d8a5ed958d274c2e8ee717e6a4b0971d" }

        val clientVersion = getClientVersion()
        val deviceId = spTDeviceId ?: UUID.randomUUID().toString().also { spTDeviceId = it }

        val payload = buildJsonObject {
            put(
                "client_data",
                buildJsonObject {
                    put("client_version", clientVersion)
                    put("client_id", resolvedClientId)
                    put(
                        "js_sdk_data",
                        buildJsonObject {
                            put("device_brand", "unknown")
                            put("device_model", "unknown")
                            put("os", "windows")
                            put("os_version", "NT 10.0")
                            put("device_id", deviceId)
                            put("device_type", "computer")
                        }
                    )
                }
            )
        }.toString()

        return try {
            val conn = (URL(SPOTIFY_CLIENT_TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", WEB_USER_AGENT)
                setRequestProperty("Origin", SPOTIFY_OPEN_URL)
                setRequestProperty("Referer", "$SPOTIFY_OPEN_URL/")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.readText()
                .orEmpty()
            conn.disconnect()

            if (code !in 200..299 || body.isBlank()) return null

            val root = jsonParser.parseToJsonElement(body).jsonObject
            val token = (root["granted_token"] as? JsonObject)
                ?.get("token")?.let { it as? JsonPrimitive }?.takeIf { it !is JsonNull }?.content
                ?.takeIf { it.isNotBlank() }

            if (token != null) {
                cachedClientToken = token
            }
            token
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Live-scrapes the current persistedQuery SHA-256 hashes from open.spotify.com's web-player JS bundle
     * if a seed hash ever becomes stale (PersistedQueryNotFound).
     */
    private suspend fun scrapePersistedQueryHash(operationName: String): String? {
        val previousHash = hashMap[operationName]
        return try {
            val html = client.get(SPOTIFY_OPEN_URL) {
                header("User-Agent", WEB_USER_AGENT)
            }.bodyAsText()

            val bundleUrls = Regex("""src="(https://[^"]*/(?:web-player|xpui)[^"]*\.js)"""")
                .findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .distinct()
                .toList()

            for (bundleUrl in bundleUrls) {
                runCatching {
                    val jsContent = client.get(bundleUrl) {
                        header("User-Agent", WEB_USER_AGENT)
                    }.bodyAsText()

                    val patternA = Regex(""""([A-Za-z0-9_]+)"\s*,\s*"(?:query|mutation|subscription)"\s*,\s*"([0-9a-f]{64})"""")
                    for (match in patternA.findAll(jsContent)) {
                        hashMap[match.groupValues[1]] = match.groupValues[2]
                    }

                    val patternB = Regex(""""name"\s*:\s*"([A-Za-z0-9_]+)"[^}]{0,120}"id"\s*:\s*"([0-9a-f]{64})"""")
                    for (match in patternB.findAll(jsContent)) {
                        hashMap[match.groupValues[1]] = match.groupValues[2]
                    }
                }
                val current = hashMap[operationName]
                if (!current.isNullOrBlank() && current != previousHash) {
                    return current
                }
            }
            hashMap[operationName]
        } catch (_: Exception) {
            hashMap[operationName]
        }
    }

    /**
     * Executes a GraphQL query against `https://api-partner.spotify.com/pathfinder/v1/query`
     * using Stash's exact GET request + URL-encoded `variables` and `extensions` + `Client-Token` header,
     * with automatic POST fallback and live hash re-scraping.
     */
    private suspend fun executeGraphQL(
        accessToken: String,
        operationName: String,
        variables: JsonObject,
        seedHash: String? = null,
        isMutation: Boolean = false
    ): JsonObject? {
        var currentHash = hashMap[operationName]
            ?: seedHash
            ?: scrapePersistedQueryHash(operationName)
            ?: return null

        val clientVersion = getClientVersion()
        var clientToken = getClientToken(cachedClientId)

        suspend fun performCall(hashToUse: String, forcePost: Boolean = isMutation): Pair<Int, String> {
            val extensions = buildJsonObject {
                put(
                    "persistedQuery",
                    buildJsonObject {
                        put("version", 1)
                        put("sha256Hash", hashToUse)
                    }
                )
            }

            val response = if (forcePost) {
                val postBody = buildJsonObject {
                    put("variables", variables)
                    put("operationName", operationName)
                    put("extensions", extensions)
                }
                client.post(SPOTIFY_PARTNER_API_URL) {
                    header("Authorization", "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("Client-Token", clientToken)
                    header("Accept", "application/json")
                    header("Cache-Control", "no-cache, no-store, must-revalidate")
                    header("Pragma", "no-cache")
                    header("App-Platform", "WebPlayer")
                    header("Spotify-App-Version", clientVersion)
                    header("Origin", SPOTIFY_OPEN_URL)
                    header("Referer", "$SPOTIFY_OPEN_URL/")
                    header("User-Agent", WEB_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(postBody.toString())
                }
            } else {
                val encodedVars = URLEncoder.encode(variables.toString(), "UTF-8")
                val encodedExt = URLEncoder.encode(extensions.toString(), "UTF-8")
                val url = "$SPOTIFY_PARTNER_API_URL?operationName=$operationName&variables=$encodedVars&extensions=$encodedExt"

                client.get(url) {
                    header("Authorization", "Bearer $accessToken")
                    if (!clientToken.isNullOrBlank()) header("Client-Token", clientToken)
                    header("Accept", "application/json")
                    header("Cache-Control", "no-cache, no-store, must-revalidate")
                    header("Pragma", "no-cache")
                    header("App-Platform", "WebPlayer")
                    header("Spotify-App-Version", clientVersion)
                    header("Origin", SPOTIFY_OPEN_URL)
                    header("Referer", "$SPOTIFY_OPEN_URL/")
                    header("User-Agent", WEB_USER_AGENT)
                }
            }
            return response.status.value to response.bodyAsText()
        }

        return try {
            var (status, body) = performCall(currentHash)

            if (body.contains("PersistedQueryNotFound")) {
                val newHash = scrapePersistedQueryHash(operationName)
                if (!newHash.isNullOrBlank() && newHash != currentHash) {
                    currentHash = newHash
                    val retry = performCall(currentHash)
                    status = retry.first
                    body = retry.second
                }
            }

            // If Client-Token expired, refresh once and update local clientToken var
            if (status == 401 || status == 403 || body.contains("CLIENT_TOKEN", ignoreCase = true)) {
                cachedClientToken = null
                val refreshedClientToken = getClientToken(cachedClientId)
                if (!refreshedClientToken.isNullOrBlank()) {
                    clientToken = refreshedClientToken
                    val retry = performCall(currentHash)
                    status = retry.first
                    body = retry.second
                }
            }

            // If GET returned non-2xx or errors without data, try POST
            if (!isMutation && (status !in 200..299 || !body.contains("\"data\""))) {
                val postRetry = performCall(currentHash, forcePost = true)
                if (postRetry.first in 200..299 && postRetry.second.contains("\"data\"")) {
                    status = postRetry.first
                    body = postRetry.second
                }
            }

            if (status in 200..299 && body.isNotBlank()) {
                jsonParser.parseToJsonElement(body) as? JsonObject
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAnonymousAccessToken(): String? {
        return try {
            val serverTime = getServerTime()
            val totp = SpotifyTotp.generate(serverTime)
            val url =
                "https://open.spotify.com/api/token?reason=transport&productType=web-player&totp=$totp&totpServer=$totp&totpVer=61"

            val response = client.get(url) {
                header("User-Agent", WEB_USER_AGENT)
                header("Accept", "application/json")
                header("App-Platform", "WebPlayer")
                header("Origin", SPOTIFY_OPEN_URL)
                header("Referer", "$SPOTIFY_OPEN_URL/")
            }

            response.headers.getAll("Set-Cookie")?.forEach { cookie ->
                if (cookie.startsWith("sp_t=")) {
                    spTDeviceId = cookie.substringAfter("sp_t=").substringBefore(";").trim()
                }
            }

            val parsed = jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
            val accessToken = parsed["accessToken"]?.jsonPrimitive?.content.orEmpty()
            val clientId = parsed["clientId"]?.jsonPrimitive?.content.orEmpty()
            if (clientId.isNotBlank()) cachedClientId = clientId

            accessToken.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getTokenDetails(spDcCookie: String): SpotifyTokenResponse? {
        val cleanCookie = normalizeSpDcCookie(spDcCookie)
        if (cleanCookie.isBlank()) return null

        return try {
            val serverTime = getServerTime()
            val totp = SpotifyTotp.generate(serverTime)
            val url =
                "https://open.spotify.com/api/token?reason=transport&productType=web-player&totp=$totp&totpServer=$totp&totpVer=61"

            val response = client.get(url) {
                header("Cookie", "sp_dc=$cleanCookie")
                header("User-Agent", WEB_USER_AGENT)
                header("Accept", "application/json")
                header("App-Platform", "WebPlayer")
                header("Origin", SPOTIFY_OPEN_URL)
                header("Referer", "$SPOTIFY_OPEN_URL/")
            }

            var extractedSpT = ""
            response.headers.getAll("Set-Cookie")?.forEach { cookie ->
                if (cookie.startsWith("sp_t=")) {
                    extractedSpT = cookie.substringAfter("sp_t=").substringBefore(";").trim()
                    if (extractedSpT.isNotBlank()) {
                        spTDeviceId = extractedSpT
                    }
                }
            }

            val rawText = response.bodyAsText()
            val json = jsonParser.parseToJsonElement(rawText).jsonObject
            val accessToken = json["accessToken"]?.jsonPrimitive?.content.orEmpty()
            val clientId = json["clientId"]?.jsonPrimitive?.content.orEmpty()
            val expirationMs = json["accessTokenExpirationTimestampMs"]?.jsonPrimitive?.longOrNull ?: 0L
            val isAnonymous = json["isAnonymous"]?.jsonPrimitive?.booleanOrNull ?: false
            val username = json["username"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: extractUsernameFromJwt(accessToken).orEmpty()

            if (accessToken.isBlank() || isAnonymous) {
                null
            } else {
                if (clientId.isNotBlank()) {
                    cachedClientId = clientId
                    // Pre-warm Client-Token so GraphQL queries work immediately
                    getClientToken(clientId)
                }
                SpotifyTokenResponse(
                    clientId = clientId,
                    accessToken = accessToken,
                    accessTokenExpirationTimestampMs = expirationMs,
                    isAnonymous = isAnonymous,
                    username = username,
                    spT = extractedSpT
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAccessToken(spDcCookie: String): String? {
        return getTokenDetails(spDcCookie)?.accessToken
    }

    suspend fun getCurrentUserProfile(accessToken: String): SpotifyUserProfile? {
        // 1. Try GraphQL profileAttributes
        try {
            val pf = executeGraphQL(
                accessToken = accessToken,
                operationName = "profileAttributes",
                variables = buildJsonObject {},
                seedHash = HASH_PROFILE_ATTRIBUTES
            )
            val profile = pf?.get("data")?.jsonObject
                ?.get("me")?.jsonObject
                ?.get("profile")?.jsonObject
            if (profile != null) {
                val uri = profile["uri"]?.jsonPrimitive?.content.orEmpty()
                val username = profile["username"]?.jsonPrimitive?.content
                    ?: uri.substringAfterLast(":")
                val name = profile["name"]?.jsonPrimitive?.content ?: username
                if (username.isNotBlank() || name.isNotBlank()) {
                    return SpotifyUserProfile(
                        id = username.ifBlank { name },
                        displayName = name.ifBlank { username }
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Extract username directly from the JWT token (zero network calls, never 429s)
        val jwtUser = extractUsernameFromJwt(accessToken)
        if (!jwtUser.isNullOrBlank()) {
            return SpotifyUserProfile(
                id = jwtUser,
                displayName = jwtUser
            )
        }

        // 3. Last resort REST v1/me
        return try {
            val resp = client.get("$SPOTIFY_API_BASE_URL/me") {
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", WEB_USER_AGENT)
            }
            if (resp.status.isSuccess()) resp.body<SpotifyUserProfile>() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses playlists and folder URIs from a GraphQL `libraryV3` response (matching Stash's SpotifyLibraryParser).
     */
    private fun parseLibraryV3Page(
        root: JsonObject?,
        playlistsOut: MutableList<SpotifyPlaylist>,
        foldersOut: MutableList<String>? = null
    ) {
        val items = root?.get("data")?.jsonObject
            ?.get("me")?.jsonObject
            ?.get("libraryV3")?.jsonObject
            ?.get("items")?.jsonArray ?: return

        for (entry in items) {
            val itemObj = entry.jsonObject
            val itemType = itemObj["__typename"]?.jsonPrimitive?.content.orEmpty()
            val innerItem = itemObj["item"]?.jsonObject ?: itemObj
            val data = innerItem["data"]?.jsonObject ?: innerItem
            val dataTypeName = data["__typename"]?.jsonPrimitive?.content.orEmpty()
            val uri = data["uri"]?.jsonPrimitive?.content
                ?: innerItem["uri"]?.jsonPrimitive?.content
                ?: innerItem["_uri"]?.jsonPrimitive?.content.orEmpty()

            if (dataTypeName.equals("Folder", ignoreCase = true) ||
                itemType.contains("Folder", ignoreCase = true) ||
                uri.contains(":folder:")
            ) {
                if (uri.isNotBlank()) foldersOut?.add(uri)
                val nestedItems = data["items"]?.jsonArray ?: innerItem["items"]?.jsonArray
                if (nestedItems != null) {
                    val synthetic = buildJsonObject {
                        put(
                            "data",
                            buildJsonObject {
                                put(
                                    "me",
                                    buildJsonObject {
                                        put(
                                            "libraryV3",
                                            buildJsonObject {
                                                put("items", nestedItems)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                    parseLibraryV3Page(synthetic, playlistsOut, foldersOut)
                }
                continue
            }

            val isPlaylist = dataTypeName.equals("Playlist", ignoreCase = true) ||
                itemType.contains("Playlist", ignoreCase = true) ||
                uri.contains(":playlist:")
            if (!isPlaylist) continue

            val id = extractPlaylistId(uri) ?: uri.substringAfterLast(":").takeIf { it.isNotBlank() } ?: continue
            if (playlistsOut.any { it.id == id }) continue

            val name = (data["name"]?.jsonPrimitive?.content ?: innerItem["name"]?.jsonPrimitive?.content)
                ?.replace('\u00A0', ' ')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val imagesList = mutableListOf<SpotifyImage>()
            val imagesItems = data["images"]?.jsonObject?.get("items")?.jsonArray
            val firstSource = imagesItems?.firstOrNull()?.jsonObject
                ?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject
            val imageUrl = firstSource?.get("url")?.jsonPrimitive?.content
                ?: data["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            if (!imageUrl.isNullOrBlank()) {
                imagesList.add(SpotifyImage(url = imageUrl))
            }

            val ownerV2 = data["ownerV2"]?.jsonObject?.get("data")?.jsonObject
            val ownerName = ownerV2?.get("name")?.jsonPrimitive?.content
                ?: ownerV2?.get("username")?.jsonPrimitive?.content
                ?: "Spotify"

            val count = itemObj["count"]?.jsonPrimitive?.intOrNull
                ?: innerItem["count"]?.jsonPrimitive?.intOrNull
                ?: data["totalCount"]?.jsonPrimitive?.intOrNull
                ?: data["tracks"]?.jsonObject?.get("totalCount")?.jsonPrimitive?.intOrNull
                ?: 0

            playlistsOut.add(
                SpotifyPlaylist(
                    id = id,
                    name = name,
                    images = imagesList.takeIf { it.isNotEmpty() },
                    owner = SpotifyOwner(displayName = ownerName),
                    tracks = SpotifyPlaylistTracksRef(total = count)
                )
            )
        }
    }

    /**
     * Fetches the user's playlists via Stash's `libraryV3` GraphQL query (with folder recursion + Daily/Yearly mixes).
     */
    suspend fun getUserPlaylists(
        accessToken: String,
        limit: Int = 50,
        offset: Int = 0
    ): SpotifyPlaylistsResponse? {
        val playlists = mutableListOf<SpotifyPlaylist>()
        val folderUris = mutableListOf<String>()

        try {
            val variables = buildJsonObject {
                put("filters", buildJsonArray { add(JsonPrimitive("Playlists")) })
                put("order", JsonNull)
                put("textFilter", "")
                put(
                    "features",
                    buildJsonArray {
                        add(JsonPrimitive("LIKED_SONGS"))
                        add(JsonPrimitive("YOUR_EPISODES"))
                    }
                )
                put("limit", limit)
                put("offset", offset)
                put("flatten", false)
                put("expandedFolders", buildJsonArray {})
                put("folderUri", JsonNull)
                put("includeFoldersWhenFlattening", true)
            }

            val pf = executeGraphQL(
                accessToken = accessToken,
                operationName = "libraryV3",
                variables = variables,
                seedHash = HASH_LIBRARY_V3
            )
            if (pf != null) {
                parseLibraryV3Page(pf, playlists, folderUris)

                for (folderUri in folderUris.distinct()) {
                    val folderVars = buildJsonObject {
                        put("filters", buildJsonArray {})
                        put("order", JsonNull)
                        put("textFilter", "")
                        put(
                            "features",
                            buildJsonArray {
                                add(JsonPrimitive("LIKED_SONGS"))
                                add(JsonPrimitive("YOUR_EPISODES"))
                            }
                        )
                        put("limit", 50)
                        put("offset", 0)
                        put("flatten", false)
                        put("expandedFolders", buildJsonArray {})
                        put("folderUri", JsonPrimitive(folderUri))
                        put("includeFoldersWhenFlattening", true)
                    }
                    val folderResp = executeGraphQL(
                        accessToken = accessToken,
                        operationName = "libraryV3",
                        variables = folderVars,
                        seedHash = HASH_LIBRARY_V3
                    )
                    if (folderResp != null) {
                        parseLibraryV3Page(folderResp, playlists, null)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Also include Personalized / Daily Mixes from Home + Search like Stash
        if (offset == 0) {
            runCatching {
                val mixes = getDailyAndPersonalizedMixes(accessToken)
                for (mix in mixes) {
                    if (playlists.none { it.id == mix.id }) {
                        playlists.add(mix)
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            return SpotifyPlaylistsResponse(
                items = playlists,
                next = null,
                total = playlists.size
            )
        }

        // Fallback to REST v1/me/playlists
        return try {
            val resp = client.get("$SPOTIFY_API_BASE_URL/me/playlists") {
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", WEB_USER_AGENT)
                parameter("limit", limit)
                parameter("offset", offset)
            }
            if (resp.status.isSuccess()) resp.body<SpotifyPlaylistsResponse>() else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getDailyAndPersonalizedMixes(accessToken: String): List<SpotifyPlaylist> {
        val mixes = mutableListOf<SpotifyPlaylist>()
        // 1. Try Home feed
        try {
            val timeZone = TimeZone.getDefault().id
            val homeVars = buildJsonObject {
                put("timeZone", timeZone)
                put("sp_t", spTDeviceId ?: "")
                put("country", Locale.getDefault().country.ifBlank { "US" })
                put("facet", JsonNull)
                put("sectionItemsLimit", 20)
            }
            val homeRoot = executeGraphQL(
                accessToken = accessToken,
                operationName = "home",
                variables = homeVars,
                seedHash = HASH_HOME
            )
            val sections = homeRoot?.get("data")?.jsonObject
                ?.get("home")?.jsonObject
                ?.get("sectionContainer")?.jsonObject
                ?.get("sections")?.jsonObject
                ?.get("items")?.jsonArray

            sections?.forEach { secEl ->
                val secItems = secEl.jsonObject["sectionItems"]?.jsonObject?.get("items")?.jsonArray ?: return@forEach
                secItems.forEach { entryEl ->
                    val contentWrapper = entryEl.jsonObject["content"]?.jsonObject ?: return@forEach
                    val wrapperType = contentWrapper["__typename"]?.jsonPrimitive?.content.orEmpty()
                    val data = contentWrapper["data"]?.jsonObject ?: return@forEach
                    val dataType = data["__typename"]?.jsonPrimitive?.content.orEmpty()
                    val uri = entryEl.jsonObject["uri"]?.jsonPrimitive?.content
                        ?: data["uri"]?.jsonPrimitive?.content.orEmpty()

                    if (wrapperType.contains("Playlist", ignoreCase = true) ||
                        dataType.equals("Playlist", ignoreCase = true) ||
                        uri.contains(":playlist:")
                    ) {
                        val id = extractPlaylistId(uri) ?: uri.substringAfterLast(":").takeIf { it.isNotBlank() }
                        val name = data["name"]?.jsonPrimitive?.content?.replace('\u00A0', ' ')?.trim()
                        if (!id.isNullOrBlank() && !name.isNullOrBlank() && mixes.none { it.id == id }) {
                            val imgUrl = data["images"]?.jsonObject
                                ?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
                                ?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject
                                ?.get("url")?.jsonPrimitive?.content
                            val ownerName = data["ownerV2"]?.jsonObject
                                ?.get("data")?.jsonObject
                                ?.get("name")?.jsonPrimitive?.content ?: "Spotify"
                            mixes.add(
                                SpotifyPlaylist(
                                    id = id,
                                    name = name,
                                    images = imgUrl?.let { listOf(SpotifyImage(url = it)) },
                                    owner = SpotifyOwner(displayName = ownerName)
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return mixes
    }

    /**
     * Parses a Spotify track from any GraphQL track node (matching Stash's `SpotifyTrackParser.parseGraphQLTrackObject`).
     */
    private fun parseGraphQLTrackObject(
        trackData: JsonObject,
        fallbackUri: String? = null
    ): SpotifyTrack? {
        val typeName = trackData["__typename"]?.jsonPrimitive?.content.orEmpty()
        if (typeName == "RestrictedContent" ||
            typeName == "NotFound" ||
            typeName == "Unavailable" ||
            typeName == "LocalTrack"
        ) {
            return null
        }

        // Check if this is an Entity (itemV3) format
        val identityTrait = trackData["identityTrait"]?.jsonObject
        if (identityTrait != null) {
            val uri = trackData["uri"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: trackData["_uri"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: fallbackUri.orEmpty()
            val name = identityTrait["name"]?.jsonPrimitive?.content
                ?.replace('\u00A0', ' ')
                ?.trim()
                .orEmpty()
            if (name.isBlank()) return null

            val durationSec = trackData["consumptionExperienceTrait"]?.jsonObject
                ?.get("duration")?.jsonObject
                ?.get("seconds")?.jsonPrimitive?.longOrNull ?: 0L
            val contributors = identityTrait["contributors"]?.jsonObject
                ?.get("items")?.jsonArray ?: JsonArray(emptyList())
            val artists = contributors.mapNotNull { c ->
                val cName = c.jsonObject["name"]?.jsonPrimitive?.content
                    ?.replace('\u00A0', ' ')
                    ?.trim()
                    ?: return@mapNotNull null
                val cUri = c.jsonObject["uri"]?.jsonPrimitive?.content.orEmpty()
                SpotifyArtist(id = cUri.substringAfterLast(":"), name = cName)
            }
            val albumName = identityTrait["contentHierarchyParent"]?.jsonObject
                ?.get("identityTrait")?.jsonObject
                ?.get("name")?.jsonPrimitive?.content
                ?.replace('\u00A0', ' ')
                ?.trim()
            val id = uri.substringAfterLast(":").takeIf { it.isNotBlank() }
                ?: "${name}_${artists.firstOrNull()?.name.orEmpty()}".hashCode().toUInt().toString(16)

            return SpotifyTrack(
                id = id,
                name = name,
                uri = uri.ifBlank { "spotify:track:$id" },
                artists = artists,
                album = albumName?.let { SpotifyAlbumSimple(name = it) },
                durationMs = durationSec * 1000L
            )
        }

        val name = trackData["name"]?.jsonPrimitive?.content
            ?.replace('\u00A0', ' ')
            ?.trim()
            .orEmpty()
        if (name.isBlank()) return null

        val uri = trackData["uri"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: trackData["_uri"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: fallbackUri?.takeIf { it.isNotBlank() }
            .orEmpty()

        val durationMs = trackData["duration"]?.jsonObject
            ?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull
            ?: trackData["trackDuration"]?.jsonObject
                ?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull
            ?: trackData["duration_ms"]?.jsonPrimitive?.longOrNull
            ?: 0L

        val explicit = trackData["contentRating"]?.jsonObject
            ?.get("label")?.jsonPrimitive?.content == "EXPLICIT" ||
            (trackData["explicit"]?.jsonPrimitive?.booleanOrNull == true)

        val albumData = trackData["albumOfTrack"]?.jsonObject ?: trackData["album"]?.jsonObject
        val albumId = albumData?.get("uri")?.jsonPrimitive?.content?.substringAfterLast(":")
            ?: albumData?.get("id")?.jsonPrimitive?.content
        val albumName = albumData?.get("name")?.jsonPrimitive?.content
            ?.replace('\u00A0', ' ')
            ?.trim()
        val coverSources = albumData?.get("coverArt")?.jsonObject?.get("sources")?.jsonArray
            ?: albumData?.get("images")?.jsonArray
        val coverUrl = coverSources?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content

        val artistsList = mutableListOf<SpotifyArtist>()
        val artistsItems = trackData["artists"]?.jsonObject?.get("items")?.jsonArray
            ?: (trackData["artists"] as? JsonArray)
            ?: trackData["firstArtist"]?.jsonObject?.get("items")?.jsonArray
            ?: albumData?.get("artists")?.jsonObject?.get("items")?.jsonArray

        artistsItems?.forEach { artistEl ->
            val aObj = artistEl.jsonObject
            val aName = (aObj["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?: aObj["name"]?.jsonPrimitive?.content)
                ?.replace('\u00A0', ' ')
                ?.trim()
            val aUri = aObj["uri"]?.jsonPrimitive?.content ?: aObj["id"]?.jsonPrimitive?.content.orEmpty()
            if (!aName.isNullOrBlank()) {
                artistsList.add(
                    SpotifyArtist(
                        id = aUri.substringAfterLast(":"),
                        name = aName
                    )
                )
            }
        }

        val id = trackData["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: uri.substringAfterLast(":").takeIf { it.isNotBlank() }
            ?: "${name}_${artistsList.firstOrNull()?.name.orEmpty()}".hashCode().toUInt().toString(16)

        return SpotifyTrack(
            id = id,
            name = name,
            uri = uri.ifBlank { "spotify:track:$id" },
            artists = artistsList,
            album = if (albumName != null || coverUrl != null) {
                SpotifyAlbumSimple(
                    id = albumId,
                    name = albumName,
                    images = coverUrl?.let { listOf(SpotifyImage(url = it)) }
                )
            } else null,
            durationMs = durationMs,
            explicit = explicit
        )
    }

    /**
     * Parses tracks from a GraphQL `fetchPlaylist` response (matching Stash's `parseGraphQLPlaylistTracks`).
     */
    private fun parseGraphQLPlaylistTracks(root: JsonObject): Pair<Int, List<SpotifyTrack>> {
        val content = root["data"]?.jsonObject
            ?.get("playlistV2")?.jsonObject
            ?.get("content")?.jsonObject
            ?: return 0 to emptyList()

        val totalCount = content["totalCount"]?.jsonPrimitive?.intOrNull ?: 0
        val items = content["items"]?.jsonArray ?: return totalCount to emptyList()

        val tracks = items.mapNotNull { el ->
            val wrapper = el.jsonObject
            val itemV2 = wrapper["itemV2"]?.jsonObject ?: wrapper["item"]?.jsonObject
            val itemV3 = wrapper["itemV3"]?.jsonObject
            val trackObj = wrapper["track"]?.jsonObject
            val fallbackUri = itemV2?.get("_uri")?.jsonPrimitive?.content
                ?: itemV2?.get("uri")?.jsonPrimitive?.content
                ?: itemV3?.get("_uri")?.jsonPrimitive?.content
                ?: itemV3?.get("uri")?.jsonPrimitive?.content
                ?: trackObj?.get("_uri")?.jsonPrimitive?.content
                ?: trackObj?.get("uri")?.jsonPrimitive?.content
                ?: wrapper["_uri"]?.jsonPrimitive?.content
                ?: wrapper["uri"]?.jsonPrimitive?.content

            val trackData = itemV2?.get("data")?.jsonObject
                ?: itemV2
                ?: itemV3?.get("data")?.jsonObject
                ?: trackObj?.get("data")?.jsonObject
                ?: trackObj
                ?: return@mapNotNull null

            parseGraphQLTrackObject(trackData, fallbackUri)
        }
        return totalCount to tracks
    }

    /**
     * Prong 1 implementation: Uses SpotDL Client Credentials token to fetch playlist metadata and tracks
     * from `https://api.spotify.com/v1/playlists/{id}`.
     */
    private suspend fun tryGetPlaylistViaClientCredentials(
        playlistId: String,
        maxTracks: Int = 1000
    ): Pair<SpotifyPlaylist, List<SpotifyTrack>>? {
        val ccToken = getClientCredentialsToken() ?: return null

        var playlistName = "Spotify Playlist"
        var ownerName: String? = null
        var coverUrl: String? = null

        try {
            val metaResp = client.get("$SPOTIFY_API_BASE_URL/playlists/$playlistId") {
                header("Authorization", "Bearer $ccToken")
                header("User-Agent", WEB_USER_AGENT)
                header("Cache-Control", "no-cache, no-store, must-revalidate")
                header("Pragma", "no-cache")
                parameter("fields", "id,name,images,owner(display_name),tracks(total)")
            }
            if (metaResp.status.isSuccess()) {
                val metaObj = jsonParser.parseToJsonElement(metaResp.bodyAsText()).jsonObject
                playlistName = metaObj["name"]?.jsonPrimitive?.content
                    ?.replace('\u00A0', ' ')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: playlistName
                ownerName = metaObj["owner"]?.jsonObject
                    ?.get("display_name")?.jsonPrimitive?.content
                coverUrl = metaObj["images"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("url")?.jsonPrimitive?.content
            }
        } catch (_: Exception) {}

        val allTracks = mutableListOf<SpotifyTrack>()
        var nextUrl: String? =
            "$SPOTIFY_API_BASE_URL/playlists/$playlistId/tracks?limit=50&offset=0&fields=items(track(id,name,uri,duration_ms,explicit,external_ids,artists(id,name),album(id,name,images))),next"

        try {
            while (!nextUrl.isNullOrBlank() && allTracks.size < maxTracks) {
                val resp = client.get(nextUrl) {
                    header("Authorization", "Bearer $ccToken")
                    header("User-Agent", WEB_USER_AGENT)
                    header("Cache-Control", "no-cache, no-store, must-revalidate")
                    header("Pragma", "no-cache")
                }
                if (!resp.status.isSuccess()) break

                val pageJson = jsonParser.parseToJsonElement(resp.bodyAsText()).jsonObject
                val items = pageJson["items"]?.jsonArray ?: break
                for (item in items) {
                    val trackObj = item.jsonObject["track"]?.takeIf { it !is JsonNull }?.jsonObject
                        ?: continue
                    parseGraphQLTrackObject(trackObj)?.let { allTracks.add(it) }
                }

                val nextEl: JsonElement? = pageJson["next"]
                nextUrl = if (nextEl != null && nextEl !is JsonNull) {
                    nextEl.jsonPrimitive.content.takeIf { it.isNotBlank() && it != "null" }
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (allTracks.isEmpty()) return null

        val playlist = SpotifyPlaylist(
            id = playlistId,
            name = playlistName,
            images = coverUrl?.let { listOf(SpotifyImage(url = it)) },
            owner = ownerName?.let { SpotifyOwner(displayName = it) },
            tracks = SpotifyPlaylistTracksRef(total = allTracks.size)
        )
        return playlist to allTracks
    }

    /**
     * Prong 3 implementation: Scrapes `https://open.spotify.com/embed/playlist/{id}` `__NEXT_DATA__` script
     * (matching Stash's `SpotifyEmbedClient.kt`).
     */
    private suspend fun fetchPlaylistFromEmbed(playlistId: String): Pair<SpotifyPlaylist, List<SpotifyTrack>>? {
        return try {
            val html = client.get("https://open.spotify.com/embed/playlist/$playlistId") {
                header("User-Agent", WEB_USER_AGENT)
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                header("Accept-Language", "en-US,en;q=0.5")
            }.bodyAsText()

            val nextDataJson = Regex(
                """<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(html)?.groupValues?.getOrNull(1) ?: return null

            val root = jsonParser.parseToJsonElement(nextDataJson).jsonObject
            val entity = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject ?: return null

            val name = (entity["name"]?.jsonPrimitive?.content ?: entity["title"]?.jsonPrimitive?.content)
                ?.replace('\u00A0', ' ')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Spotify Playlist"
            val subtitle = entity["subtitle"]?.jsonPrimitive?.content?.replace('\u00A0', ' ')?.trim()
            val coverUrl = entity["coverArt"]?.jsonObject
                ?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("url")?.jsonPrimitive?.content

            val trackList = entity["trackList"]?.jsonArray ?: JsonArray(emptyList())
            val tracks = trackList.mapNotNull { el ->
                val obj = el.jsonObject
                val entityType = obj["entityType"]?.jsonPrimitive?.content ?: "track"
                if (entityType != "track") return@mapNotNull null

                val uri = obj["uri"]?.jsonPrimitive?.content.orEmpty()
                val trackId = uri.substringAfterLast(":").ifBlank { obj["uid"]?.jsonPrimitive?.content.orEmpty() }
                val title = obj["title"]?.jsonPrimitive?.content
                    ?.replace('\u00A0', ' ')
                    ?.trim()
                    .orEmpty()
                val artistStr = obj["subtitle"]?.jsonPrimitive?.content
                    ?.replace('\u00A0', ' ')
                    ?.trim()
                    .orEmpty()
                val duration = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                val isExplicit = obj["isExplicit"]?.jsonPrimitive?.booleanOrNull ?: false
                if (title.isBlank()) return@mapNotNull null

                val artists = artistStr
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { SpotifyArtist(name = it) }

                SpotifyTrack(
                    id = trackId.ifBlank { title.hashCode().toString() },
                    name = title,
                    uri = uri.ifBlank { "spotify:track:$trackId" },
                    artists = artists,
                    album = coverUrl?.let { SpotifyAlbumSimple(images = listOf(SpotifyImage(url = it))) },
                    durationMs = duration,
                    explicit = isExplicit
                )
            }

            if (tracks.isEmpty()) return null

            val playlist = SpotifyPlaylist(
                id = playlistId,
                name = name,
                images = coverUrl?.let { listOf(SpotifyImage(url = it)) },
                owner = subtitle?.let { SpotifyOwner(displayName = it) },
                tracks = SpotifyPlaylistTracksRef(total = tracks.size)
            )
            playlist to tracks
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Fetches both playlist metadata (name, owner, cover) and all tracks for any Spotify playlist URL, URI, or ID.
     * Cascades through Stash's 3 strategies:
     * 1. SpotDL Client Credentials Web API (/v1/playlists/{id}/tracks)
     * 2. Pathfinder GraphQL `fetchPlaylist` (with `Client-Token` + `sp_dc` or anonymous WebPlayer token)
     * 3. Spotify Embed `__NEXT_DATA__` scraper (`open.spotify.com/embed/playlist/{id}`)
     */
    suspend fun getPlaylistInfoAndTracks(
        rawPlaylistInput: String,
        accessToken: String? = null,
        maxTracks: Int = 10000
    ): Result<Pair<SpotifyPlaylist, List<SpotifyTrack>>> {
        val playlistId = resolvePlaylistId(rawPlaylistInput)
            ?: return Result.failure(
                IllegalArgumentException("Invalid Spotify playlist link or ID. Please paste a valid open.spotify.com/playlist/... link.")
            )

        // Strategy 1: Try Pathfinder GraphQL `fetchPlaylist` first for instant, uncached live playlist state
        val token = accessToken?.takeIf { it.isNotBlank() } ?: getAnonymousAccessToken()
        if (!token.isNullOrBlank()) {
            try {
                val tracks = mutableListOf<SpotifyTrack>()
                var playlistName = "Spotify Playlist"
                var ownerName: String? = null
                var coverUrl: String? = null
                var totalCount = 0
                var offset = 0
                val pageSize = 100

                while (tracks.size < maxTracks) {
                    val variables = buildJsonObject {
                        put("uri", "spotify:playlist:$playlistId")
                        put("offset", offset)
                        put("limit", pageSize)
                        put("enableWatchFeedEntrypoint", false)
                    }
                    val pf = executeGraphQL(
                        accessToken = token,
                        operationName = "fetchPlaylist",
                        variables = variables,
                        seedHash = HASH_FETCH_PLAYLIST
                    ) ?: break

                    val playlistV2 = pf["data"]?.jsonObject?.get("playlistV2")?.jsonObject ?: break
                    val typename = playlistV2["__typename"]?.jsonPrimitive?.content.orEmpty()
                    if (typename == "NotFound" || typename == "GenericError") break

                    if (offset == 0) {
                        playlistName = playlistV2["name"]?.jsonPrimitive?.content
                            ?.replace('\u00A0', ' ')
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: playlistName
                        ownerName = playlistV2["ownerV2"]?.jsonObject
                            ?.get("data")?.jsonObject
                            ?.get("name")?.jsonPrimitive?.content
                        coverUrl = playlistV2["images"]?.jsonObject
                            ?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("url")?.jsonPrimitive?.content
                    }

                    val rawItemsCount = playlistV2["content"]?.jsonObject
                        ?.get("items")?.jsonArray?.size ?: 0
                    val (pageTotal, pageTracks) = parseGraphQLPlaylistTracks(pf)
                    if (pageTotal > 0) totalCount = pageTotal
                    if (pageTracks.isEmpty() && rawItemsCount == 0) break

                    tracks.addAll(pageTracks)
                    offset += pageSize
                    if (rawItemsCount < pageSize || (totalCount > 0 && offset >= totalCount)) break
                }

                if (tracks.isNotEmpty()) {
                    val playlist = SpotifyPlaylist(
                        id = playlistId,
                        name = playlistName,
                        images = coverUrl?.let { listOf(SpotifyImage(url = it)) },
                        owner = ownerName?.let { SpotifyOwner(displayName = it) },
                        tracks = SpotifyPlaylistTracksRef(total = totalCount.coerceAtLeast(tracks.size))
                    )
                    return Result.success(playlist to tracks)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Strategy 2: Try Client Credentials Web API (works for public playlists without user login)
        val ccResult = tryGetPlaylistViaClientCredentials(playlistId, maxTracks)
        if (ccResult != null && ccResult.second.isNotEmpty()) {
            return Result.success(ccResult)
        }

        // Strategy 3: Fallback to Embed endpoint (zero rate limits, works without token, up to 100 tracks)
        val embedResult = fetchPlaylistFromEmbed(playlistId)
        if (embedResult != null && embedResult.second.isNotEmpty()) {
            return Result.success(embedResult)
        }

        return Result.failure(
            IllegalStateException("Could not load tracks from this Spotify playlist. Make sure the playlist is public or your Spotify account is connected.")
        )
    }

    suspend fun getPlaylistTracks(
        accessToken: String,
        playlistId: String,
        limit: Int = 100,
        offset: Int = 0
    ): SpotifyPlaylistTracksResponse? {
        val cleanId = extractPlaylistId(playlistId) ?: playlistId

        // 1. Try Pathfinder fetchPlaylist with Client-Token
        try {
            val variables = buildJsonObject {
                put("uri", "spotify:playlist:$cleanId")
                put("offset", offset)
                put("limit", limit)
                put("enableWatchFeedEntrypoint", false)
            }
            val pf = executeGraphQL(
                accessToken = accessToken,
                operationName = "fetchPlaylist",
                variables = variables,
                seedHash = HASH_FETCH_PLAYLIST
            )
            if (pf != null) {
                val (totalCount, pageTracks) = parseGraphQLPlaylistTracks(pf)
                if (pageTracks.isNotEmpty()) {
                    return SpotifyPlaylistTracksResponse(
                        items = pageTracks.map { SpotifyPlaylistItem(track = it) },
                        next = if (offset + pageTracks.size < totalCount) "next" else null,
                        total = totalCount
                    )
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback to Client Credentials or provided token on /v1/playlists/{id}/tracks
        val bearer = getClientCredentialsToken() ?: accessToken
        return try {
            val resp = client.get("$SPOTIFY_API_BASE_URL/playlists/$cleanId/tracks") {
                header("Authorization", "Bearer $bearer")
                header("User-Agent", WEB_USER_AGENT)
                parameter("limit", limit)
                parameter("offset", offset)
            }
            if (resp.status.isSuccess()) resp.body<SpotifyPlaylistTracksResponse>() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAllPlaylistTracks(
        accessToken: String,
        playlistId: String,
        maxTracks: Int = 10000
    ): List<SpotifyTrack> {
        return getPlaylistInfoAndTracks(
            rawPlaylistInput = playlistId,
            accessToken = accessToken,
            maxTracks = maxTracks
        ).getOrNull()?.second.orEmpty()
    }

    /**
     * Searches Spotify for a playlist matching [playlistName] (used when syncing a legacy local
     * `"Spotify: <name>"` playlist that was imported before `browseId` tracking was added).
     */
    suspend fun findPlaylistByName(
        accessToken: String?,
        playlistName: String
    ): SpotifyPlaylist? {
        val cleanTarget = playlistName.removePrefix("Spotify:").trim()
        if (cleanTarget.isBlank()) return null

        // 1. Check user's own playlists first if logged in
        if (!accessToken.isNullOrBlank()) {
            val userPlaylists = getUserPlaylists(accessToken)?.items.orEmpty()
            userPlaylists.firstOrNull { it.name.equals(cleanTarget, ignoreCase = true) }?.let { return it }
            userPlaylists.firstOrNull {
                it.name.contains(cleanTarget, ignoreCase = true) ||
                    cleanTarget.contains(it.name, ignoreCase = true)
            }?.let { return it }
        }

        // 2. Fallback to Client Credentials /v1/search?type=playlist
        val bearer = getClientCredentialsToken() ?: accessToken ?: return null
        return try {
            val resp = client.get("$SPOTIFY_API_BASE_URL/search") {
                header("Authorization", "Bearer $bearer")
                header("User-Agent", WEB_USER_AGENT)
                parameter("q", cleanTarget)
                parameter("type", "playlist")
                parameter("limit", 10)
            }
            if (!resp.status.isSuccess()) return null
            val root = jsonParser.parseToJsonElement(resp.bodyAsText()).jsonObject
            val items = root["playlists"]?.jsonObject?.get("items")?.jsonArray ?: return null
            val parsed = items.mapNotNull { el ->
                if (el is JsonNull) return@mapNotNull null
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                val imgUrl = obj["images"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                SpotifyPlaylist(
                    id = id,
                    name = name,
                    images = imgUrl?.let { listOf(SpotifyImage(url = it)) }
                )
            }
            parsed.firstOrNull { it.name.equals(cleanTarget, ignoreCase = true) } ?: parsed.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches a page of the user's Spotify Liked Songs via GraphQL `fetchLibraryTracks`
     * (matching Stash's `SpotifyApiClient.getLikedSongs` + `SpotifyTrackParser.parseGraphQLLibraryTracks`).
     */
    suspend fun getLikedTracksPage(
        accessToken: String,
        limit: Int = 50,
        offset: Int = 0
    ): SpotifySavedTracksResponse? {
        try {
            val variables = buildJsonObject {
                put("offset", offset)
                put("limit", limit)
            }
            val pf = executeGraphQL(
                accessToken = accessToken,
                operationName = "fetchLibraryTracks",
                variables = variables,
                seedHash = HASH_FETCH_LIBRARY_TRACKS
            )
            val me = pf?.get("data")?.jsonObject?.get("me")?.jsonObject
            val tracksContainer = me?.get("library")?.jsonObject?.get("tracks")?.jsonObject
                ?: me?.get("libraryTracks")?.jsonObject
                ?: me?.get("collection")?.jsonObject?.get("tracks")?.jsonObject

            if (tracksContainer != null) {
                val totalCount = tracksContainer["totalCount"]?.jsonPrimitive?.intOrNull ?: 0
                val rawItems = tracksContainer["items"]?.jsonArray ?: JsonArray(emptyList())
                val items = rawItems.mapNotNull { el ->
                    val wrapper = el.jsonObject
                    val addedAt = wrapper["addedAt"]?.jsonObject?.get("isoString")?.jsonPrimitive?.content
                    val trackObj = wrapper["track"]?.jsonObject
                    val itemObj = wrapper["item"]?.jsonObject
                    val itemV2Obj = wrapper["itemV2"]?.jsonObject
                    val itemV3Obj = wrapper["itemV3"]?.jsonObject

                    // In fetchLibraryTracks, `_uri` ("spotify:track:...") is placed on `wrapper["track"]`
                    // rather than inside `wrapper["track"]["data"]`.
                    val fallbackUri = trackObj?.get("_uri")?.jsonPrimitive?.content
                        ?: trackObj?.get("uri")?.jsonPrimitive?.content
                        ?: itemObj?.get("_uri")?.jsonPrimitive?.content
                        ?: itemObj?.get("uri")?.jsonPrimitive?.content
                        ?: itemV2Obj?.get("_uri")?.jsonPrimitive?.content
                        ?: itemV2Obj?.get("uri")?.jsonPrimitive?.content
                        ?: itemV3Obj?.get("_uri")?.jsonPrimitive?.content
                        ?: itemV3Obj?.get("uri")?.jsonPrimitive?.content
                        ?: wrapper["_uri"]?.jsonPrimitive?.content
                        ?: wrapper["uri"]?.jsonPrimitive?.content

                    val trackData = trackObj?.get("data")?.jsonObject
                        ?: trackObj
                        ?: itemObj?.get("data")?.jsonObject
                        ?: itemObj
                        ?: itemV2Obj?.get("data")?.jsonObject
                        ?: itemV2Obj
                        ?: itemV3Obj?.get("data")?.jsonObject
                        ?: itemV3Obj
                        ?: return@mapNotNull null

                    val parsedTrack = parseGraphQLTrackObject(trackData, fallbackUri) ?: return@mapNotNull null
                    SpotifySavedTrackItem(
                        addedAt = addedAt,
                        track = parsedTrack
                    )
                }
                return SpotifySavedTracksResponse(
                    items = items,
                    next = if (rawItems.isNotEmpty() && offset + rawItems.size < totalCount) {
                        (offset + rawItems.size).toString()
                    } else {
                        null
                    },
                    total = totalCount.coerceAtLeast(offset + items.size)
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to REST v1/me/tracks
        return try {
            val resp = client.get("$SPOTIFY_API_BASE_URL/me/tracks") {
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", WEB_USER_AGENT)
                header("Cache-Control", "no-cache, no-store, must-revalidate")
                header("Pragma", "no-cache")
                parameter("limit", limit)
                parameter("offset", offset)
            }
            if (resp.status.isSuccess()) resp.body<SpotifySavedTracksResponse>() else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAllLikedTracks(
        accessToken: String,
        maxTracks: Int = Int.MAX_VALUE,
        onProgress: ((fetched: Int, total: Int) -> Unit)? = null
    ): List<SpotifyTrack> {
        val tracks = mutableListOf<SpotifyTrack>()
        var offset = 0
        val pageSize = 50
        var totalExpected = 0

        while (tracks.size < maxTracks) {
            var page: SpotifySavedTracksResponse? = null
            for (attempt in 0..2) {
                page = getLikedTracksPage(
                    accessToken = accessToken,
                    limit = pageSize,
                    offset = offset
                )
                if (page != null) break
                kotlinx.coroutines.delay(350L * (attempt + 1))
            }
            if (page == null) break

            if (page.total > totalExpected) {
                totalExpected = page.total
            }

            val pageTracks = page.items
                .mapNotNull { it.track }
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }

            if (pageTracks.isEmpty() && page.next == null) break

            val remaining = (maxTracks - tracks.size).coerceAtLeast(0)
            tracks.addAll(pageTracks.take(remaining))
            onProgress?.invoke(tracks.size, totalExpected.coerceAtLeast(tracks.size))

            val nextOffset = page.next?.toIntOrNull()
            if (page.next == null) break
            offset = if (nextOffset != null && nextOffset > offset) {
                nextOffset
            } else {
                offset + pageSize
            }
            if (totalExpected > 0 && offset >= totalExpected) break
        }
        return tracks
    }

    /**
     * Searches Spotify for a matching track using Stash's `searchDesktop` GraphQL query
     * (`HASH_SEARCH_DESKTOP`), with Client Credentials `/v1/search` as fallback.
     */
    suspend fun searchTrack(
        accessToken: String,
        title: String,
        artist: String?
    ): SpotifyTrack? {
        val cleanTitle = title
            .replace(Regex("\\((official|lyric|audio|video|visualizer|hd|hq|4k).*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[(official|lyric|audio|video|visualizer|hd|hq|4k).*?]", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { title.trim() }

        val primaryArtist = artist
            ?.split(",", "&", "feat.", "ft.", "•", "/")
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        val searchQuery = if (primaryArtist.isNotBlank()) {
            "$cleanTitle $primaryArtist"
        } else {
            cleanTitle
        }

        if (searchQuery.isBlank()) return null

        // 1. Try GraphQL `searchDesktop` (Stash's primary search operation)
        try {
            val variables = buildJsonObject {
                put("searchTerm", searchQuery)
                put("offset", 0)
                put("limit", 10)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includePreReleases", false)
            }
            val pf = executeGraphQL(
                accessToken = accessToken,
                operationName = "searchDesktop",
                variables = variables,
                seedHash = HASH_SEARCH_DESKTOP
            )
            val data = pf?.get("data")?.jsonObject
            val tracksItems = data?.get("searchV2")?.jsonObject
                ?.get("tracksV2")?.jsonObject
                ?.get("items")?.jsonArray
                ?: data?.get("search")?.jsonObject
                    ?.get("tracks")?.jsonObject
                    ?.get("items")?.jsonArray

            val candidates = tracksItems?.mapNotNull { el ->
                val wrapper = el.jsonObject
                val trackData = wrapper["item"]?.jsonObject?.get("data")?.jsonObject
                    ?: wrapper["track"]?.jsonObject
                    ?: wrapper["itemV2"]?.jsonObject?.get("data")?.jsonObject
                    ?: wrapper
                parseGraphQLTrackObject(trackData)
            }.orEmpty()

            if (candidates.isNotEmpty()) {
                return pickBestCandidate(candidates, cleanTitle, primaryArtist)
            }
        } catch (_: Exception) {}

        // 2. Fallback to REST /v1/search using Client Credentials token (never rate-limited like sp_dc)
        val searchBearer = getClientCredentialsToken() ?: accessToken
        return try {
            val resp = client.get("$SPOTIFY_API_BASE_URL/search") {
                header("Authorization", "Bearer $searchBearer")
                header("User-Agent", WEB_USER_AGENT)
                parameter("q", searchQuery)
                parameter("type", "track")
                parameter("limit", 5)
            }
            if (!resp.status.isSuccess()) return null
            val response: SpotifySearchResponse = resp.body()
            val candidates = response.tracks?.items.orEmpty().filter { it.id.isNotBlank() }
            if (candidates.isEmpty()) return null
            pickBestCandidate(candidates, cleanTitle, primaryArtist)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun pickBestCandidate(
        candidates: List<SpotifyTrack>,
        cleanTitle: String,
        primaryArtist: String
    ): SpotifyTrack? {
        val unwantedKeywords = listOf("karaoke", "tribute", "8-bit", "instrumental", "cover")
        val titleHasUnwanted = unwantedKeywords.any { cleanTitle.contains(it, ignoreCase = true) }

        val scored = candidates.map { candidate ->
            var score = 0
            if (candidate.name.equals(cleanTitle, ignoreCase = true)) {
                score += 60
            } else if (isStrictTitleMatch(candidate.name, cleanTitle)) {
                score += 45
            } else if (candidate.name.contains(cleanTitle, ignoreCase = true) ||
                cleanTitle.contains(candidate.name, ignoreCase = true)
            ) {
                score += 20
            }

            if (primaryArtist.isNotBlank()) {
                val artistMatched = candidate.artists.any { artistObj ->
                    isStrictArtistMatch(artistObj.name, primaryArtist)
                }
                if (artistMatched) {
                    score += 50
                } else {
                    score -= 40
                }
            }

            if (!titleHasUnwanted &&
                unwantedKeywords.any { candidate.name.contains(it, ignoreCase = true) }
            ) {
                score -= 35
            }
            candidate to score
        }

        val best = scored.maxByOrNull { it.second } ?: return null
        return if (best.second >= 45) best.first else null
    }

    /**
     * Adds a track to the user's Spotify Liked Songs library using Stash's `addToLibrary` GraphQL mutation
     * (`HASH_LIBRARY_MUTATION`), with `/v1/me/tracks` as fallback.
     */
    suspend fun saveLikedTrack(accessToken: String, trackId: String): Boolean {
        val cleanId = trackId.substringAfterLast(":").trim()
        if (cleanId.isBlank()) return false

        // 1. Try Pathfinder addToLibrary mutation (Stash's exact mutation & hash)
        try {
            val variables = buildJsonObject {
                put(
                    "libraryItemUris",
                    buildJsonArray {
                        add(JsonPrimitive("spotify:track:$cleanId"))
                    }
                )
            }
            val pf = executeGraphQL(
                accessToken = accessToken,
                operationName = "addToLibrary",
                variables = variables,
                seedHash = HASH_LIBRARY_MUTATION,
                isMutation = true
            )
            if (pf != null && pf["errors"] == null) {
                return true
            }
        } catch (_: Exception) {}

        // 2. Fallback to REST v1/me/tracks
        return try {
            val response = client.put("$SPOTIFY_API_BASE_URL/me/tracks") {
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", WEB_USER_AGENT)
                parameter("ids", cleanId)
                contentType(ContentType.Application.Json)
                setBody("""{"ids":["$cleanId"]}""")
            }
            response.status.value in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Volatile
    private var cleanedUpLegacyPlaylist = false

    private suspend fun cleanupLegacyRecentlyPlayedPlaylistIfPresent(accessToken: String) {
        if (cleanedUpLegacyPlaylist) return
        cleanedUpLegacyPlaylist = true
        runCatching {
            val playlistsResp = getUserPlaylists(accessToken = accessToken, limit = 50, offset = 0)
            val legacy = playlistsResp?.items?.firstOrNull {
                it.name.equals("ViWaves Recently Played", ignoreCase = true)
            }
            if (legacy != null && legacy.id.isNotBlank()) {
                client.delete("$SPOTIFY_API_BASE_URL/playlists/${legacy.id}/followers") {
                    header("Authorization", "Bearer $accessToken")
                    header("User-Agent", WEB_USER_AGENT)
                }
            }
        }
    }

    /**
     * Syncs the currently playing track to the user's Spotify account so Spotify updates
     * real Listening Stats (Minutes listened, Top song, Top artist) and Recently Played:
     * 1. Queries available/active Spotify Connect devices (`GET /v1/me/player/devices`).
     * 2. Mutes the background Spotify Connect device (`PUT /v1/me/player/volume?volume_percent=0`)
     *    so it does not double-play audio over the user's headphones.
     * 3. Transfers/activates playback (`PUT /v1/me/player` with `play: true`) and starts track
     *    playback (`PUT /v1/me/player/play`) so Spotify counts real stream duration & minutes listened.
     */
    suspend fun syncPlaybackToSpotify(
        accessToken: String,
        trackId: String,
        positionMs: Long = 0L
    ): Pair<Boolean, String> {
        val cleanId = trackId.substringAfterLast(":").trim()
        if (cleanId.isBlank()) return false to "Invalid Spotify track ID"

        val trackUri = "spotify:track:$cleanId"
        val playBody = buildJsonObject {
            put(
                "uris",
                buildJsonArray {
                    add(JsonPrimitive(trackUri))
                }
            )
            put("position_ms", positionMs.coerceAtLeast(0L))
        }.toString()

        return try {
            cleanupLegacyRecentlyPlayedPlaylistIfPresent(accessToken)

            // 1. Discover available Spotify Connect devices (prefer currently active device, else smartphone/computer)
            val devicesResp = runCatching {
                client.get("$SPOTIFY_API_BASE_URL/me/player/devices") {
                    header("Authorization", "Bearer $accessToken")
                    header("User-Agent", WEB_USER_AGENT)
                    header("Cache-Control", "no-cache")
                }
            }.getOrNull()

            var targetDeviceId: String? = null
            var targetDeviceName = "Spotify Connect"
            var isTargetActive = false

            if (devicesResp != null && devicesResp.status.isSuccess()) {
                val root = jsonParser.parseToJsonElement(devicesResp.bodyAsText()).obj
                val devices = root?.get("devices")?.arr.orEmpty().mapNotNull { it.obj }.filter { dev ->
                    dev["is_restricted"]?.jsonPrimitive?.booleanOrNull != true &&
                        !dev["id"]?.str.isNullOrBlank()
                }
                val chosen = devices.firstOrNull { it["is_active"]?.jsonPrimitive?.booleanOrNull == true }
                    ?: devices.firstOrNull { it["type"]?.str?.equals("Smartphone", ignoreCase = true) == true }
                    ?: devices.firstOrNull()
                if (chosen != null) {
                    targetDeviceId = chosen["id"]?.str
                    targetDeviceName = chosen["name"]?.str ?: "Spotify Device"
                    isTargetActive = chosen["is_active"]?.jsonPrimitive?.booleanOrNull == true
                }
            }

            if (!targetDeviceId.isNullOrBlank()) {
                // Mute Spotify Connect device volume so background playback counts minutes listened without duplicate audio
                runCatching {
                    client.put("$SPOTIFY_API_BASE_URL/me/player/volume") {
                        header("Authorization", "Bearer $accessToken")
                        header("User-Agent", WEB_USER_AGENT)
                        parameter("volume_percent", 0)
                        parameter("device_id", targetDeviceId)
                    }
                }

                // Activate the device via Transfer Playback if it isn't currently active
                if (!isTargetActive) {
                    runCatching {
                        client.put("$SPOTIFY_API_BASE_URL/me/player") {
                            header("Authorization", "Bearer $accessToken")
                            header("User-Agent", WEB_USER_AGENT)
                            contentType(ContentType.Application.Json)
                            setBody(
                                buildJsonObject {
                                    put("device_ids", buildJsonArray { add(JsonPrimitive(targetDeviceId)) })
                                    put("play", true)
                                }.toString()
                            )
                        }
                    }
                    kotlinx.coroutines.delay(250L)
                }

                val targetedResp = runCatching {
                    client.put("$SPOTIFY_API_BASE_URL/me/player/play") {
                        header("Authorization", "Bearer $accessToken")
                        header("User-Agent", WEB_USER_AGENT)
                        parameter("device_id", targetDeviceId)
                        contentType(ContentType.Application.Json)
                        setBody(playBody)
                    }
                }.getOrNull()

                if (targetedResp != null && targetedResp.status.value in 200..299) {
                    // Ensure device stays unpaused so Listening Stats (Minutes listened) accumulate
                    kotlinx.coroutines.delay(400L)
                    runCatching {
                        client.put("$SPOTIFY_API_BASE_URL/me/player/play") {
                            header("Authorization", "Bearer $accessToken")
                            header("User-Agent", WEB_USER_AGENT)
                            parameter("device_id", targetDeviceId)
                        }
                    }
                    return true to "Synced to Spotify on $targetDeviceName"
                }
            }

            // 2. Fallback: Try playing directly on any default active Spotify session
            val directResp = runCatching {
                client.put("$SPOTIFY_API_BASE_URL/me/player/play") {
                    header("Authorization", "Bearer $accessToken")
                    header("User-Agent", WEB_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    setBody(playBody)
                }
            }.getOrNull()
            if (directResp != null && directResp.status.value in 200..299) {
                return true to "Synced to active Spotify player"
            }

            // 3. Record in Spotify Recents via Pathfinder `saveRecentSearches`
            var syncedToRecents = false
            runCatching {
                val recentVars = buildJsonObject {
                    put(
                        "uris",
                        buildJsonArray {
                            add(JsonPrimitive(trackUri))
                        }
                    )
                }
                val pfRecent = executeGraphQL(
                    accessToken = accessToken,
                    operationName = "saveRecentSearches",
                    variables = recentVars,
                    seedHash = HASH_SAVE_RECENT_SEARCHES,
                    isMutation = true
                )
                val typename = pfRecent?.get("data")?.obj
                    ?.get("saveToRecentSearches")?.obj
                    ?.get("__typename")?.str
                if (pfRecent != null && pfRecent["errors"] == null && typename != "GenericError") {
                    syncedToRecents = true
                }
            }

            if (syncedToRecents) {
                true to "Recorded in Spotify Recents (keep Spotify open in background for Minutes Listened)"
            } else {
                false to "No active Spotify device found; open Spotify in the background to sync Listening Stats"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false to (e.message ?: "Spotify playback sync failed")
        }
    }

    /**
     * Periodically verifies that the synced track is still actively playing (unpaused) on Spotify Connect
     * while the user listens in ViWaves so that Spotify records the full stream duration (>30s / Minutes listened).
     */
    suspend fun keepPlaybackAliveOnSpotify(
        accessToken: String,
        trackId: String,
        expectedPositionMs: Long
    ) {
        val cleanId = trackId.substringAfterLast(":").trim()
        if (cleanId.isBlank()) return
        val trackUri = "spotify:track:$cleanId"

        runCatching {
            val stateResp = client.get("$SPOTIFY_API_BASE_URL/me/player") {
                header("Authorization", "Bearer $accessToken")
                header("User-Agent", WEB_USER_AGENT)
                header("Cache-Control", "no-cache")
            }
            if (stateResp.status.value in 200..299) {
                val stateObj = jsonParser.parseToJsonElement(stateResp.bodyAsText()).obj
                val isPlaying = stateObj?.get("is_playing")?.jsonPrimitive?.booleanOrNull == true
                val currentItemId = stateObj?.get("item")?.obj?.get("id")?.str
                val deviceId = stateObj?.get("device")?.obj?.get("id")?.str

                if (!isPlaying || currentItemId != cleanId) {
                    if (!deviceId.isNullOrBlank()) {
                        runCatching {
                            client.put("$SPOTIFY_API_BASE_URL/me/player/volume") {
                                header("Authorization", "Bearer $accessToken")
                                header("User-Agent", WEB_USER_AGENT)
                                parameter("volume_percent", 0)
                                parameter("device_id", deviceId)
                            }
                        }
                    }
                    client.put("$SPOTIFY_API_BASE_URL/me/player/play") {
                        header("Authorization", "Bearer $accessToken")
                        header("User-Agent", WEB_USER_AGENT)
                        if (!deviceId.isNullOrBlank()) parameter("device_id", deviceId)
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("uris", buildJsonArray { add(JsonPrimitive(trackUri)) })
                                put("position_ms", expectedPositionMs.coerceAtLeast(0L))
                            }.toString()
                        )
                    }
                }
            } else {
                // Player went dormant; re-trigger syncPlaybackToSpotify at current position
                syncPlaybackToSpotify(
                    accessToken = accessToken,
                    trackId = cleanId,
                    positionMs = expectedPositionMs
                )
            }
        }
    }

    private val appleArtworkCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val spotifyCanvasCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun normalizeTitleForMatch(raw: String): String {
        val withoutBrackets = raw
            .replace(
                Regex(
                    """\s*[\(\[](?:official|lyric|audio|music|visualizer|video|hd|hq|4k|feat\.?|ft\.?|with|prod\.?|bonus\s*track|deluxe|remaster(?:ed)?|explicit|clean|single|ep|version|radio\s*edit)[^\)\]]*[\)\]]""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(
                Regex(
                    """\s*-\s*(?:single|ep|deluxe.*|remaster.*|bonus\s*track.*|explicit.*|official.*)$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
        return withoutBrackets
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    private fun extractArtistTokens(rawArtist: String): List<String> {
        return rawArtist
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[\(\[](?:feat\.?|ft\.?|with)[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), " ")
            .split(Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bwith\b|×|;|/|•|\bx\b)\s*""", RegexOption.IGNORE_CASE))
            .map { token ->
                token
                    .lowercase(Locale.ROOT)
                    .replace(Regex("""[^a-z0-9\p{L}\p{N}]+"""), " ")
                    .trim()
                    .replace(Regex("""\s+"""), " ")
            }
            .filter { it.length >= 2 }
    }

    private fun isStrictArtistMatch(candidateArtist: String, targetArtist: String): Boolean {
        val targetTokens = extractArtistTokens(targetArtist)
        val candidateTokens = extractArtistTokens(candidateArtist)
        if (targetTokens.isEmpty() || candidateTokens.isEmpty()) return false

        val primaryTarget = targetTokens.first()
        for (cand in candidateTokens) {
            if (cand == primaryTarget) return true
            val candWords = cand.split(" ").filter { it.isNotBlank() }
            val targetWords = primaryTarget.split(" ").filter { it.isNotBlank() }
            if (targetWords.size >= 2 && candWords.containsAll(targetWords)) return true
            if (candWords.size >= 2 && targetWords.containsAll(candWords)) return true
        }
        for (t in targetTokens.drop(1)) {
            if (candidateTokens.any { it == t }) return true
        }
        return false
    }

    private fun isStrictTitleMatch(candidateTitle: String, targetTitle: String): Boolean {
        val candNorm = normalizeTitleForMatch(candidateTitle)
        val targetNorm = normalizeTitleForMatch(targetTitle)
        if (candNorm.isBlank() || targetNorm.isBlank()) return false
        if (candNorm == targetNorm) return true

        val candWords = candNorm.split(" ").filter { it.isNotBlank() }
        val targetWords = targetNorm.split(" ").filter { it.isNotBlank() }
        if (candWords.isEmpty() || targetWords.isEmpty()) return false

        // Never allow a 1-word candidate (e.g. "Something") to match a multi-word title (e.g. "Mr. Do Something")
        if (candWords.size != targetWords.size) {
            val minWords = minOf(candWords.size, targetWords.size)
            val maxWords = maxOf(candWords.size, targetWords.size)
            if (minWords < 2) return false
            if (minWords.toFloat() / maxWords.toFloat() < 0.75f) return false
            val shorter = if (candWords.size <= targetWords.size) candNorm else targetNorm
            val longer = if (candWords.size <= targetWords.size) targetNorm else candNorm
            return longer.startsWith("$shorter ") || longer.endsWith(" $shorter")
        }
        return false
    }

    @Volatile
    private var cachedAppleMusicBearerToken: String? = null

    @Volatile
    private var cachedAppleMusicBearerExpiryMs: Long = 0L

    private val appleBurstJsRegex = Regex("""(/assets/[^"'\s<>]+\.js)""", RegexOption.IGNORE_CASE)
    private val appleJwtRegex = Regex("""["'](eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+)["']""")
    private val appleStorefrontAlbumRegex = Regex("""music\.apple\.com/(?:([a-z]{2})/)?album/(?:[^/?#]+/)?(\d+)""", RegexOption.IGNORE_CASE)
    private val appleWebSearchLinkRegex = Regex("""href="(https://music\.apple\.com/([a-z]{2})/album/([^/"?]+)/(\d+))"""", RegexOption.IGNORE_CASE)

    /**
     * Ported from `m8tec/apple-music-animated-artworks` (`CacheKeyNormalizer.StripEditionSuffixes`):
     * Strips edition notes like `(Deluxe Version)`, `[Remastered]`, `- Single`, `- EP` while keeping live albums distinct.
     */
    private fun stripAppleEditionSuffixes(rawAlbum: String): String {
        if (rawAlbum.isBlank()) return ""
        val editionRegex = Regex("""\b(?:deluxe|edition|remaster|remastered|expanded|anniversary|bonus|reissue|ep|single|explicit|clean|version)\b""", RegexOption.IGNORE_CASE)
        val liveRegex = Regex("""\blive\b""", RegexOption.IGNORE_CASE)
        val suffixRegex = Regex("""\s*\([^()]*\)|\s*\[[^\[\]]*]|\s+[-–—]\s+[^()\[\]]+$""")

        var current = rawAlbum.trim()
        while (true) {
            val stripped = suffixRegex.replace(current) { matchResult ->
                val text = matchResult.value
                if (editionRegex.containsMatchIn(text) && !liveRegex.containsMatchIn(text)) "" else text
            }.trim()
            if (stripped == current) {
                return stripped.ifBlank { rawAlbum.trim() }
            }
            current = stripped
        }
    }

    /**
     * Ported from `m8tec/apple-music-animated-artworks` (`AppleMusicClient.GetBearerTokenAsync`):
     * Extracts and caches Apple Music's anonymous web-client JWT token (`eyJ...`) for `amp-api.music.apple.com`.
     */
    private suspend fun getAppleMusicBearerToken(referenceUrl: String = "https://music.apple.com/us/browse"): String? {
        val now = System.currentTimeMillis()
        val cached = cachedAppleMusicBearerToken
        if (!cached.isNullOrBlank() && now < cachedAppleMusicBearerExpiryMs) {
            return cached
        }
        return runCatching {
            val pageHtml = client.get(referenceUrl) {
                header("User-Agent", WEB_USER_AGENT)
                header("Accept-Language", "en-US,en;q=0.9")
            }.bodyAsText()

            val jsPaths = appleBurstJsRegex.findAll(pageHtml)
                .map { it.groupValues[1] }
                .filter { it.contains("index") || it.contains("web-client") || it.contains("apple-music") }
                .distinct()
                .toList()

            for (jsPath in jsPaths) {
                val jsUrl = if (jsPath.startsWith("http")) jsPath else "https://music.apple.com$jsPath"
                val jsText = runCatching {
                    client.get(jsUrl) {
                        header("User-Agent", WEB_USER_AGENT)
                    }.bodyAsText()
                }.getOrNull() ?: continue

                val token = appleJwtRegex.find(jsText)?.groupValues?.getOrNull(1)
                if (!token.isNullOrBlank()) {
                    cachedAppleMusicBearerToken = token
                    cachedAppleMusicBearerExpiryMs = now + 6 * 3600_000L
                    return token
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Resolves an Apple Music animated album cover (`editorialVideo`) stream URL for a track/album using
     * the architecture from `m8tec/apple-music-animated-artworks`:
     * 1. Resolves candidate albums & collection IDs via iTunes Search (`explicit=Yes`) and Apple Music Web Search (`music.apple.com/us/search`).
     * 2. Queries `https://artwork.m8tec.top/api/v1/artwork/search` & `/api/v1/artwork/url` for instant cached `.m3u8` lookup.
     * 3. Queries Apple Music's official `amp-api.music.apple.com/v1/catalog/{storefront}/albums?ids=...&extend=editorialVideo&platform=web`
     *    using the extracted Apple Music web-client Bearer token.
     * 4. Falls back to `serialized-server-data` HTML parsing if needed.
     * 5. Resolves the master `.m3u8` -> best SDR H.264 (`avc1`) variant `.m3u8` -> direct self-contained `.mp4` stream (`...-.mp4`)
     *    so ExoPlayer plays it natively as an `.mp4` video on all Android devices.
     */
    suspend fun getAppleAnimatedArtworkUrl(
        title: String,
        artist: String,
        album: String? = null,
        preferTall: Boolean = false
    ): String? {
        val rawPrimaryArtist = artist
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .split(Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|×|;|/|•)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull()
            ?.trim()
            .orEmpty()

        val cleanTitle = title
            .replace(Regex("""\s*\((?:Official|Lyric|Audio|Music|Visualizer|Video|HD|HQ|4K)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[(?:Official|Lyric|Audio|Music|Visualizer|Video|HD|HQ|4K)[^\]]*]""", RegexOption.IGNORE_CASE), "")
            .let { t ->
                if (rawPrimaryArtist.isNotBlank() && t.startsWith("$rawPrimaryArtist - ", ignoreCase = true)) {
                    t.substring(rawPrimaryArtist.length + 3)
                } else {
                    t
                }
            }
            .trim()
        val cleanArtist = extractArtistTokens(artist).firstOrNull().orEmpty()

        if (cleanTitle.isBlank() || cleanArtist.isBlank() || rawPrimaryArtist.isBlank()) {
            return null
        }

        val cleanAlbum = album
            ?.trim()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.equals("Search", ignoreCase = true) &&
                    !it.equals("Favorites", ignoreCase = true) &&
                    !it.equals("Downloads", ignoreCase = true) &&
                    !it.equals("Library", ignoreCase = true) &&
                    !it.equals("History", ignoreCase = true) &&
                    !it.equals("Quick Picks", ignoreCase = true) &&
                    !it.startsWith("Playing from ", ignoreCase = true)
            }
            .orEmpty()

        val cacheKey = "${normalizeTitleForMatch(cleanTitle)}|$cleanArtist|${normalizeTitleForMatch(cleanAlbum)}"
        val fallbackTrackCacheKey = "${normalizeTitleForMatch(cleanTitle)}|$cleanArtist|"
        appleArtworkCache[cacheKey]?.let { cached ->
            return cached.takeIf { it.isNotBlank() }
        }
        if (cleanAlbum.isBlank()) {
            appleArtworkCache[fallbackTrackCacheKey]?.let { cached ->
                return cached.takeIf { it.isNotBlank() }
            }
        }

        data class VerifiedAlbumCandidate(
            val url: String,
            val storefront: String,
            val collectionId: String,
            val collectionName: String,
            val albumMatches: Boolean,
            val isFullAlbum: Boolean
        )

        val candidates = mutableListOf<VerifiedAlbumCandidate>()
        val discoveredAlbumNames = LinkedHashSet<String>()
        if (cleanAlbum.isNotBlank()) {
            discoveredAlbumNames.add(stripAppleEditionSuffixes(cleanAlbum))
            discoveredAlbumNames.add(cleanAlbum)
        }

        val searchQueries = buildList {
            add("$rawPrimaryArtist $cleanTitle" to "song")
            if (cleanAlbum.isNotBlank() && !cleanAlbum.equals(cleanTitle, ignoreCase = true)) {
                add("$rawPrimaryArtist $cleanAlbum" to "album")
            }
            add("$rawPrimaryArtist $cleanTitle" to "album")
        }

        // Step 1A: iTunes Search API with explicit=Yes (matching m8tec's AppleMusicClient)
        for ((term, entity) in searchQueries) {
            runCatching {
                val encoded = URLEncoder.encode(term, "UTF-8")
                val searchUrl = "https://itunes.apple.com/search?term=$encoded&media=music&entity=$entity&limit=25&explicit=Yes"
                val body = client.get(searchUrl) {
                    header("User-Agent", WEB_USER_AGENT)
                }.bodyAsText()
                val root = jsonParser.parseToJsonElement(body).obj ?: return@runCatching
                val results = root["results"].arr ?: return@runCatching
                for (item in results) {
                    val obj = item.obj ?: continue
                    val itemArtist = obj["artistName"].str.orEmpty()
                    val itemTrack = obj["trackName"].str.orEmpty()
                    val itemCollection = obj["collectionName"].str.orEmpty()
                    val trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 1
                    val collectionId = obj["collectionId"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val rawUrl = obj["collectionViewUrl"].str ?: continue

                    if (!isStrictArtistMatch(itemArtist, artist)) continue

                    if (entity == "song") {
                        if (!isStrictTitleMatch(itemTrack, cleanTitle)) continue
                    } else {
                        val matchesAlbum = cleanAlbum.isNotBlank() &&
                            (isStrictTitleMatch(itemCollection, cleanAlbum) ||
                                isStrictTitleMatch(stripAppleEditionSuffixes(itemCollection), stripAppleEditionSuffixes(cleanAlbum)))
                        val matchesTitleAsAlbum = isStrictTitleMatch(itemCollection, cleanTitle)
                        if (!matchesAlbum && !matchesTitleAsAlbum) continue
                    }

                    val isLive = itemCollection.contains("Live", ignoreCase = true) &&
                        !cleanTitle.contains("Live", ignoreCase = true) &&
                        !cleanAlbum.contains("Live", ignoreCase = true)
                    if (isLive) continue

                    val isFullAlbum = !itemCollection.contains("- Single", ignoreCase = true) && trackCount > 1
                    val albumMatches = cleanAlbum.isNotBlank() &&
                        (isStrictTitleMatch(itemCollection, cleanAlbum) ||
                            isStrictTitleMatch(stripAppleEditionSuffixes(itemCollection), stripAppleEditionSuffixes(cleanAlbum)))

                    if (isFullAlbum && itemCollection.isNotBlank()) {
                        discoveredAlbumNames.add(stripAppleEditionSuffixes(itemCollection))
                        discoveredAlbumNames.add(itemCollection)
                    }

                    val normalizedUrl = rawUrl
                        .substringBefore("?")
                        .replace("https://itunes.apple.com/", "https://music.apple.com/")
                    val sfMatch = appleStorefrontAlbumRegex.find(normalizedUrl)
                    val storefront = sfMatch?.groupValues?.getOrNull(1)?.ifBlank { "us" } ?: "us"
                    val parsedId = sfMatch?.groupValues?.getOrNull(2)?.ifBlank { collectionId } ?: collectionId

                    val alreadyAdded = candidates.any {
                        it.url == normalizedUrl || (parsedId.isNotBlank() && it.collectionId == parsedId)
                    }
                    if (normalizedUrl.contains("music.apple.com/") && !alreadyAdded) {
                        candidates.add(
                            VerifiedAlbumCandidate(
                                url = normalizedUrl,
                                storefront = storefront,
                                collectionId = parsedId,
                                collectionName = itemCollection,
                                albumMatches = albumMatches,
                                isFullAlbum = isFullAlbum
                            )
                        )
                    }
                }
            }
        }

        // Fallback: if only Singles were found, also record their collection names for m8tec search
        if (discoveredAlbumNames.isEmpty()) {
            candidates.firstOrNull()?.collectionName?.takeIf { it.isNotBlank() }?.let {
                discoveredAlbumNames.add(stripAppleEditionSuffixes(it))
            }
        }

        val sortedCandidates = candidates.sortedWith(
            compareByDescending<VerifiedAlbumCandidate> { it.albumMatches }
                .thenByDescending { it.isFullAlbum }
        ).toMutableList()

        // Step 1B: Ported from m8tec's `GetAppleMusicUrlViaWebSearchAsync` (`https://music.apple.com/us/search?term=...`)
        if (sortedCandidates.isEmpty() || sortedCandidates.none { it.isFullAlbum }) {
            runCatching {
                val targetAlbumForSearch = discoveredAlbumNames.firstOrNull().orEmpty()
                val webQuery = listOf(rawPrimaryArtist, targetAlbumForSearch, cleanTitle)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" ")
                val encoded = URLEncoder.encode(webQuery, "UTF-8")
                val searchHtml = client.get("https://music.apple.com/us/search?term=$encoded") {
                    header("User-Agent", WEB_USER_AGENT)
                    header("Accept-Language", "en-US,en;q=0.9")
                }.bodyAsText()
                for (match in appleWebSearchLinkRegex.findAll(searchHtml)) {
                    val foundUrl = match.groupValues[1]
                    val sf = match.groupValues[2].ifBlank { "us" }
                    val slug = java.net.URLDecoder.decode(match.groupValues[3], "UTF-8").replace('-', ' ')
                    val id = match.groupValues[4]
                    val matchesKnownAlbum = discoveredAlbumNames.any {
                        isStrictTitleMatch(slug, it) || isStrictTitleMatch(stripAppleEditionSuffixes(slug), stripAppleEditionSuffixes(it))
                    }
                    val matchesTrackTitle = isStrictTitleMatch(slug, cleanTitle)
                    if ((matchesKnownAlbum || matchesTrackTitle) && sortedCandidates.none { it.collectionId == id }) {
                        sortedCandidates.add(
                            VerifiedAlbumCandidate(
                                url = foundUrl,
                                storefront = sf,
                                collectionId = id,
                                collectionName = slug,
                                albumMatches = matchesKnownAlbum,
                                isFullAlbum = !slug.contains("single", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        }

        fun selectPreferredM3u8(squareUrl: String?, square1x1Url: String?, tallUrl: String?): String? {
            val sq = squareUrl?.takeIf { it.isNotBlank() && it != "NONE" }
                ?: square1x1Url?.takeIf { it.isNotBlank() && it != "NONE" }
            val tl = tallUrl?.takeIf { it.isNotBlank() && it != "NONE" }
            // Always prefer square (768x768 / 456x456 16/8-aligned H.264 macroblocks) over tall (664x886 odd-multiple-of-2 height)
            // because Android hardware/emulator MediaCodec AVC decoders reject non-aligned tall heights (e.g. 886 = 2*443)
            val chosen = sq ?: tl
            return chosen?.replace("\\/", "/")?.replace("\\u0026", "&")
        }

        // Step 2: Query m8tec's public Apple Music Animated Artworks API (`https://artwork.m8tec.top/api/v1/artwork/search`)
        for (albumCandidateName in discoveredAlbumNames.take(2)) {
            val m8tecM3u8 = runCatching {
                val resp = client.get("https://artwork.m8tec.top/api/v1/artwork/search") {
                    header("User-Agent", WEB_USER_AGENT)
                    parameter("artist", rawPrimaryArtist)
                    parameter("album", albumCandidateName)
                    parameter("title", cleanTitle)
                }
                if (resp.status.isSuccess()) {
                    val obj = jsonParser.parseToJsonElement(resp.bodyAsText()).obj
                    val resArtist = obj?.get("artist")?.str.orEmpty()
                    if (resArtist.isBlank() || isStrictArtistMatch(resArtist, artist)) {
                        selectPreferredM3u8(
                            squareUrl = obj?.get("url")?.str,
                            square1x1Url = null,
                            tallUrl = obj?.get("url_tall")?.str
                        )
                    } else null
                } else null
            }.getOrNull()

            if (!m8tecM3u8.isNullOrBlank()) {
                val playableUrl = resolveBestAppleHlsVariant(m8tecM3u8)
                appleArtworkCache[cacheKey] = playableUrl
                appleArtworkCache[fallbackTrackCacheKey] = playableUrl
                return playableUrl
            }
        }

        // Step 3: Official Apple Music AMP Catalog API (`amp-api.music.apple.com/v1/catalog/{storefront}/albums?ids=...&extend=editorialVideo&platform=web`)
        // Ported directly from `m8tec/apple-music-animated-artworks` (`AppleMusicClient.ParseAppleMusicPageAsync`)
        val candidateIds = sortedCandidates.map { it.collectionId }.filter { it.isNotBlank() }.distinct().take(8)
        if (candidateIds.isNotEmpty()) {
            val firstUrl = sortedCandidates.firstOrNull()?.url ?: "https://music.apple.com/us/browse"
            val storefront = sortedCandidates.firstOrNull()?.storefront?.ifBlank { "us" } ?: "us"
            val bearer = getAppleMusicBearerToken(firstUrl)
            if (!bearer.isNullOrBlank()) {
                val ampM3u8 = runCatching {
                    val idsParam = candidateIds.joinToString(",")
                    val ampUrl = "https://amp-api.music.apple.com/v1/catalog/$storefront/albums?ids=$idsParam&extend=editorialVideo&platform=web"
                    val resp = client.get(ampUrl) {
                        header("Authorization", "Bearer $bearer")
                        header("Origin", "https://music.apple.com")
                        header("Referer", "https://music.apple.com/")
                        header("User-Agent", WEB_USER_AGENT)
                    }
                    if (resp.status.isSuccess()) {
                        val dataArr = jsonParser.parseToJsonElement(resp.bodyAsText()).obj?.get("data")?.arr
                        var foundUrl: String? = null
                        if (dataArr != null) {
                            for (el in dataArr) {
                                val attrs = el.obj?.get("attributes")?.obj ?: continue
                                val attrArtist = attrs["artistName"]?.str ?: attrs["curatorName"]?.str.orEmpty()
                                if (attrArtist.isNotBlank() && !isStrictArtistMatch(attrArtist, artist)) continue
                                val editorialVideo = attrs["editorialVideo"]?.obj ?: continue
                                val sq = editorialVideo["motionDetailSquare"]?.obj?.get("video")?.str
                                val sq1x1 = editorialVideo["motionSquareVideo1x1"]?.obj?.get("video")?.str
                                val tl = editorialVideo["motionDetailTall"]?.obj?.get("video")?.str
                                val picked = selectPreferredM3u8(sq, sq1x1, tl)
                                if (!picked.isNullOrBlank()) {
                                    foundUrl = picked
                                    break
                                }
                            }
                        }
                        foundUrl
                    } else null
                }.getOrNull()

                if (!ampM3u8.isNullOrBlank()) {
                    val playableUrl = resolveBestAppleHlsVariant(ampM3u8)
                    appleArtworkCache[cacheKey] = playableUrl
                    appleArtworkCache[fallbackTrackCacheKey] = playableUrl
                    return playableUrl
                }
            }
        }

        // Step 4: Fallback to `serialized-server-data` HTML extraction on top candidates
        fun findMotionVideoInJson(element: JsonElement?, keyName: String): String? {
            if (element == null) return null
            when (element) {
                is JsonObject -> {
                    element[keyName]?.obj?.get("video")?.str?.takeIf { it.isNotBlank() }?.let { return it }
                    for ((_, child) in element) {
                        findMotionVideoInJson(child, keyName)?.let { return it }
                    }
                }
                is JsonArray -> {
                    for (child in element) {
                        findMotionVideoInJson(child, keyName)?.let { return it }
                    }
                }
                else -> {}
            }
            return null
        }

        for (candidate in sortedCandidates.take(4)) {
            val masterM3u8 = runCatching {
                val html = client.get(candidate.url) {
                    header("User-Agent", WEB_USER_AGENT)
                    header("Accept-Language", "en-US,en;q=0.9")
                }.bodyAsText()

                val idMarkerIdx = html.indexOf("id=\"serialized-server-data\"", ignoreCase = true)
                    .takeIf { it >= 0 }
                    ?: html.indexOf("id='serialized-server-data'", ignoreCase = true)
                val scriptOpenEnd = if (idMarkerIdx >= 0) html.indexOf('>', idMarkerIdx) else -1
                val scriptCloseStart = if (scriptOpenEnd >= 0) html.indexOf("</script>", scriptOpenEnd + 1, ignoreCase = true) else -1
                val scriptJson = if (scriptOpenEnd >= 0 && scriptCloseStart > scriptOpenEnd) {
                    html.substring(scriptOpenEnd + 1, scriptCloseStart).trim()
                } else {
                    null
                }

                var tallUrl: String? = null
                var squareUrl: String? = null
                var square1x1Url: String? = null

                if (!scriptJson.isNullOrBlank()) {
                    runCatching {
                        val parsedRoot = jsonParser.parseToJsonElement(scriptJson)
                        val dataArr = if (parsedRoot is JsonArray) {
                            parsedRoot
                        } else {
                            parsedRoot.obj?.get("data")?.arr
                        }
                        val sections = dataArr?.firstOrNull()?.obj
                            ?.get("data")?.obj
                            ?.get("sections")?.arr

                        val headerSection = sections?.firstOrNull { secEl ->
                            val secObj = secEl.obj
                            val itemKind = secObj?.get("itemKind")?.str.orEmpty()
                            val secId = secObj?.get("id")?.str.orEmpty()
                            itemKind == "containerDetailHeaderLockup" || secId.startsWith("album-detail-header")
                        } ?: sections?.firstOrNull()

                        val headerItem = headerSection?.obj
                            ?.get("items")?.arr
                            ?.firstOrNull()?.obj

                        val videoDict = headerItem?.get("videoArtwork")?.obj?.get("dictionary")?.obj
                        val tallVideoDict = headerItem?.get("tallVideoArtwork")?.obj?.get("dictionary")?.obj

                        squareUrl = videoDict?.get("motionDetailSquare")?.obj?.get("video")?.str
                            ?: findMotionVideoInJson(headerSection, "motionDetailSquare")
                        square1x1Url = videoDict?.get("motionSquareVideo1x1")?.obj?.get("video")?.str
                            ?: findMotionVideoInJson(headerSection, "motionSquareVideo1x1")
                        tallUrl = tallVideoDict?.get("motionDetailTall")?.obj?.get("video")?.str
                            ?: videoDict?.get("motionDetailTall")?.obj?.get("video")?.str
                            ?: findMotionVideoInJson(headerSection, "motionDetailTall")
                    }
                }

                selectPreferredM3u8(squareUrl, square1x1Url, tallUrl)
            }.getOrNull()

            if (!masterM3u8.isNullOrBlank()) {
                val playableUrl = resolveBestAppleHlsVariant(masterM3u8)
                appleArtworkCache[cacheKey] = playableUrl
                appleArtworkCache[fallbackTrackCacheKey] = playableUrl
                return playableUrl
            }
        }

        appleArtworkCache[cacheKey] = ""
        return null
    }

    /**
     * Parses an Apple Music master `.m3u8` manifest, selects the best SDR `avc1` (H.264) variant playlist,
     * and extracts the direct self-contained `.mp4` stream URL (`...-.mp4`) from the variant playlist's
     * `#EXT-X-MAP:URI="..."` / segment list so ExoPlayer plays it directly via `ProgressiveMediaSource` (`video/mp4`).
     */
    private suspend fun resolveBestAppleHlsVariant(masterUrl: String): String {
        return runCatching {
            val manifest = client.get(masterUrl) {
                header("User-Agent", WEB_USER_AGENT)
            }.bodyAsText()

            val lines = manifest.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val baseUrl = masterUrl.substringBeforeLast("/") + "/"

            data class Variant(
                val url: String,
                val bandwidth: Int,
                val width: Int,
                val height: Int,
                val isAvc: Boolean,
                val isHevc: Boolean,
                val isDolbyVision: Boolean
            )

            val variants = mutableListOf<Variant>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val info = line.removePrefix("#EXT-X-STREAM-INF:")
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(info)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""").find(info)
                    val width = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val height = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                    val codecs = Regex("""CODECS="([^"]+)"""").find(info)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
                    val nextLine = lines.getOrNull(i + 1)
                    if (nextLine != null && !nextLine.startsWith("#") && !nextLine.contains("trickPlay", ignoreCase = true)) {
                        val resolvedUrl = if (nextLine.startsWith("http://") || nextLine.startsWith("https://")) {
                            nextLine
                        } else {
                            baseUrl + nextLine
                        }
                        variants.add(
                            Variant(
                                url = resolvedUrl,
                                bandwidth = bw,
                                width = width,
                                height = height,
                                isAvc = codecs.contains("avc1"),
                                isHevc = codecs.contains("hvc1") || codecs.contains("hev1"),
                                isDolbyVision = codecs.contains("dvh1") || codecs.contains("dvhe")
                            )
                        )
                        i += 2
                        continue
                    }
                }
                i++
            }

            // Prefer Full HD / High-Definition SDR H.264 (avc1) variants (1080x1080, 960x960, 1080x1440, 768x768)
            val sdrVariants = variants.filter { !it.isDolbyVision && it.width >= 360 && it.height >= 360 }
            val preferred = sdrVariants
                .filter { it.isAvc && it.width in 720..1080 && it.height in 720..1440 && it.width % 8 == 0 && it.height % 8 == 0 }
                .ifEmpty { sdrVariants.filter { it.isAvc && it.width in 720..1080 && it.height in 720..1440 } }
                .ifEmpty { sdrVariants.filter { it.isAvc && it.width <= 1080 && it.height <= 1440 } }
                .ifEmpty { sdrVariants.filter { it.width in 720..1080 && it.height in 720..1440 } }
                .ifEmpty { sdrVariants }
                .sortedWith(
                    compareByDescending<Variant> { it.isAvc }
                        .thenByDescending { it.width }
                        .thenByDescending { it.bandwidth }
                )
                .firstOrNull()

            val variantM3u8Url = preferred?.url ?: return@runCatching masterUrl

            // Fetch the variant .m3u8 media playlist and extract its underlying self-contained .mp4 file URL
            val mediaPlaylistText = runCatching {
                client.get(variantM3u8Url) {
                    header("User-Agent", WEB_USER_AGENT)
                }.bodyAsText()
            }.getOrNull()

            if (!mediaPlaylistText.isNullOrBlank()) {
                val variantBaseUrl = variantM3u8Url.substringBeforeLast("/") + "/"
                var relativeMp4: String? = null
                for (mLine in mediaPlaylistText.lines().map { it.trim() }) {
                    if (mLine.startsWith("#EXT-X-MAP:")) {
                        val uriMatch = Regex("""URI="([^"]+\.mp4[^"]*)"""", RegexOption.IGNORE_CASE).find(mLine)
                        if (uriMatch != null) {
                            relativeMp4 = uriMatch.groupValues[1]
                            break
                        }
                    } else if (mLine.isNotEmpty() && !mLine.startsWith("#") && mLine.contains(".mp4", ignoreCase = true)) {
                        relativeMp4 = mLine
                        break
                    }
                }
                if (!relativeMp4.isNullOrBlank()) {
                    val directMp4Url = if (relativeMp4.startsWith("http://") || relativeMp4.startsWith("https://")) {
                        relativeMp4
                    } else {
                        variantBaseUrl + relativeMp4
                    }
                    return@runCatching directMp4Url
                }
            }

            variantM3u8Url
        }.getOrDefault(masterUrl)
    }

    private suspend fun getValidWebPlayerTokenForCanvas(
        spDcCookie: String?,
        cachedAccessToken: String?,
        forceRefresh: Boolean = false
    ): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            if (!cachedAccessToken.isNullOrBlank()) {
                return cachedAccessToken
            }
            val localSpDcToken = cachedSpDcAccessToken
            if (!localSpDcToken.isNullOrBlank() && now + 60_000L < cachedSpDcTokenExpiryMs) {
                return localSpDcToken
            }
        }

        if (!spDcCookie.isNullOrBlank()) {
            val details = runCatching { getTokenDetails(spDcCookie) }.getOrNull()
            if (details != null && details.accessToken.isNotBlank()) {
                cachedSpDcAccessToken = details.accessToken
                cachedSpDcTokenExpiryMs = if (details.accessTokenExpirationTimestampMs > now) {
                    details.accessTokenExpirationTimestampMs
                } else {
                    now + 45 * 60_000L
                }
                return details.accessToken
            }
        }

        // Fallback to cached anonymous WebPlayer token (NOT client_credentials, which Pathfinder rejects for Canvas)
        val anonCached = cachedAnonymousWebToken
        if (!forceRefresh && !anonCached.isNullOrBlank() && now + 60_000L < cachedAnonymousWebTokenExpiryMs) {
            return anonCached
        }
        val anon = runCatching { getAnonymousAccessToken() }.getOrNull()
        if (!anon.isNullOrBlank()) {
            cachedAnonymousWebToken = anon
            cachedAnonymousWebTokenExpiryMs = now + 45 * 60_000L
            return anon
        }
        return null
    }

    /**
     * Resolves a Spotify Canvas (`.mp4` looping vertical video) URL for a track using Spotify Pathfinder GraphQL
     * and the user's logged-in Spotify session (`cachedAccessToken` / `spDcCookie`), with strict artist + title verification.
     */
    suspend fun getSpotifyCanvasUrl(
        title: String,
        artist: String,
        spDcCookie: String? = null,
        cachedAccessToken: String? = null
    ): String? {
        val cleanTitle = title
            .replace(Regex("""\s*\((?:Official|Lyric|Audio|Music|Visualizer|Video|HD|HQ|4K)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\[(?:Official|Lyric|Audio|Music|Visualizer|Video|HD|HQ|4K)[^\]]*]""", RegexOption.IGNORE_CASE), "")
            .trim()
        val cleanArtist = extractArtistTokens(artist).firstOrNull().orEmpty()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return null

        val cacheKey = "${normalizeTitleForMatch(cleanTitle)}|$cleanArtist"
        spotifyCanvasCache[cacheKey]?.let { cached ->
            return cached.takeIf { it.isNotBlank() }
        }

        var token = getValidWebPlayerTokenForCanvas(
            spDcCookie = spDcCookie,
            cachedAccessToken = cachedAccessToken,
            forceRefresh = false
        ) ?: return null

        var matchedTrack = runCatching { searchTrack(token, cleanTitle, artist) }.getOrNull()
        if (matchedTrack == null && !spDcCookie.isNullOrBlank()) {
            val refreshed = getValidWebPlayerTokenForCanvas(
                spDcCookie = spDcCookie,
                cachedAccessToken = null,
                forceRefresh = true
            )
            if (!refreshed.isNullOrBlank() && refreshed != token) {
                token = refreshed
                matchedTrack = runCatching { searchTrack(token, cleanTitle, artist) }.getOrNull()
            }
        }

        if (matchedTrack == null) {
            spotifyCanvasCache[cacheKey] = ""
            return null
        }

        // Strictly verify that the matched Spotify track matches both the playing song's artist and title
        val artistOk = matchedTrack.artists.any { isStrictArtistMatch(it.name, artist) }
        val titleOk = isStrictTitleMatch(matchedTrack.name, cleanTitle)
        if (!artistOk || !titleOk) {
            spotifyCanvasCache[cacheKey] = ""
            return null
        }

        val trackId = matchedTrack.id.substringAfterLast(":").trim()
        if (trackId.isBlank()) {
            spotifyCanvasCache[cacheKey] = ""
            return null
        }

        val trackUri = "spotify:track:$trackId"

        suspend fun fetchCanvasWithToken(activeToken: String): String? {
            val variables = buildJsonObject {
                put("trackUri", trackUri)
            }
            val canvasRoot = executeGraphQL(
                accessToken = activeToken,
                operationName = "canvas",
                variables = variables,
                seedHash = HASH_CANVAS
            )
            val directCanvasUrl = canvasRoot
                ?.get("data")?.obj
                ?.get("trackUnion")?.obj
                ?.get("canvas")?.obj
                ?.get("url")?.str
                ?.replace("\\/", "/")
                ?.takeIf { it.isNotBlank() && it.endsWith(".mp4", ignoreCase = true) }
            if (!directCanvasUrl.isNullOrBlank()) return directCanvasUrl

            val fromCanvasOp = canvasRoot?.toString()?.let { raw ->
                Regex("""https://canvaz\.scdn\.co/upload/[^\s"\\]+\.mp4""")
                    .find(raw)
                    ?.value
                    ?.replace("\\/", "/")
            }
            if (!fromCanvasOp.isNullOrBlank()) return fromCanvasOp

            val trackRoot = executeGraphQL(
                accessToken = activeToken,
                operationName = "getTrack",
                variables = buildJsonObject { put("uri", trackUri) },
                seedHash = HASH_GET_TRACK
            )
            return trackRoot?.toString()?.let { raw ->
                Regex("""https://canvaz\.scdn\.co/upload/[^\s"\\]+\.mp4""")
                    .find(raw)
                    ?.value
                    ?.replace("\\/", "/")
            }
        }

        try {
            var mp4Url = fetchCanvasWithToken(token)
            if (mp4Url.isNullOrBlank()) {
                val refreshed = getValidWebPlayerTokenForCanvas(
                    spDcCookie = spDcCookie,
                    cachedAccessToken = null,
                    forceRefresh = true
                )
                if (!refreshed.isNullOrBlank() && refreshed != token) {
                    mp4Url = fetchCanvasWithToken(refreshed)
                }
            }
            if (!mp4Url.isNullOrBlank()) {
                spotifyCanvasCache[cacheKey] = mp4Url
                return mp4Url
            }
        } catch (_: Exception) {}

        spotifyCanvasCache[cacheKey] = ""
        return null
    }

    /**
     * Resolves an animated music artwork video URL according to which options are enabled:
     * - `enableAnimatedArtwork`: Apple Music animated album cover (`editorialVideo` HLS `.m3u8`)
     * - `enableSpotifyCanvas`: Spotify Canvas looping video (`.mp4`) using Spotify login
     */
    suspend fun getTrackCanvasUrl(
        title: String,
        artist: String,
        spDcCookie: String? = null,
        cachedAccessToken: String? = null,
        album: String? = null,
        preferTall: Boolean = false,
        enableAnimatedArtwork: Boolean = true,
        enableSpotifyCanvas: Boolean = true
    ): String? {
        if (enableAnimatedArtwork) {
            val appleAnimatedUrl = runCatching {
                getAppleAnimatedArtworkUrl(
                    title = title,
                    artist = artist,
                    album = album,
                    preferTall = preferTall
                )
            }.getOrNull()
            if (!appleAnimatedUrl.isNullOrBlank()) {
                return appleAnimatedUrl
            }
        }

        if (enableSpotifyCanvas) {
            val spotifyCanvasUrl = runCatching {
                getSpotifyCanvasUrl(
                    title = title,
                    artist = artist,
                    spDcCookie = spDcCookie,
                    cachedAccessToken = cachedAccessToken
                )
            }.getOrNull()
            if (!spotifyCanvasUrl.isNullOrBlank()) {
                return spotifyCanvasUrl
            }
        }

        return null
    }

    /**
     * Extracts a 22-character Spotify artist ID from a URL, URI (`spotify:artist:...`), or raw ID.
     */
    fun extractArtistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null
        Regex("""spotify:artist:([A-Za-z0-9]{22})""").find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?artist/([A-Za-z0-9]{22})""").find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        if (Regex("""^[A-Za-z0-9]{22}$""").matches(trimmed)) return trimmed
        return null
    }

    private val JsonElement?.obj: JsonObject?
        get() = this as? JsonObject

    private val JsonElement?.arr: JsonArray?
        get() = this as? JsonArray

    private val JsonElement?.str: String?
        get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it != "null" }

    private val JsonElement?.int: Int?
        get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private val JsonElement?.long: Long?
        get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private val JsonElement?.bool: Boolean?
        get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    /**
     * Parses artist nodes (and recurses into Folder nodes) from a `libraryV3` GraphQL items array.
     * Uses null-safe JSON casting so `JsonNull` fields (such as `"avatarImage": null`) never throw
     * or abort pagination.
     */
    private fun parseLibraryV3Artists(
        items: JsonArray,
        results: MutableList<app.vitune.providers.spotify.models.SpotifyArtistFull>,
        foldersOut: MutableList<String>? = null,
        assumeAllNonFoldersAreArtists: Boolean = false
    ) {
        for (entryEl in items) {
            runCatching {
                val entryObj = entryEl.obj ?: return@runCatching
                val entryType = entryObj["__typename"].str.orEmpty()
                val itemObj = entryObj["item"].obj
                    ?: entryObj["itemV2"].obj
                    ?: entryObj["itemV3"].obj
                    ?: entryObj["content"].obj
                    ?: entryObj
                val itemType = itemObj["__typename"].str.orEmpty()
                val data = itemObj["data"].obj
                    ?: itemObj["content"].obj
                    ?: itemObj["artist"].obj
                    ?: itemObj
                val typeName = data["__typename"].str.orEmpty()
                val uri = data["uri"].str
                    ?: data["_uri"].str
                    ?: itemObj["_uri"].str
                    ?: itemObj["uri"].str
                    ?: entryObj["uri"].str
                    ?: entryObj["_uri"].str
                    ?: data["identityTrait"].obj?.get("uri").str
                    .orEmpty()

                if (typeName.equals("Folder", ignoreCase = true) ||
                    itemType.contains("Folder", ignoreCase = true) ||
                    entryType.contains("Folder", ignoreCase = true) ||
                    uri.contains(":folder:")
                ) {
                    val folderUri = uri.takeIf { it.isNotBlank() }
                        ?: Regex("""spotify:(?:user:[^":]+:)?folder:[A-Za-z0-9]+""").find(entryObj.toString())?.value
                    if (!folderUri.isNullOrBlank()) foldersOut?.add(folderUri)
                    val nested = data["items"].arr ?: itemObj["items"].arr ?: entryObj["items"].arr
                    if (nested != null) {
                        parseLibraryV3Artists(nested, results, foldersOut, assumeAllNonFoldersAreArtists)
                    }
                    return@runCatching
                }

                val rawJson by lazy(LazyThreadSafetyMode.NONE) { entryObj.toString() }
                val regexArtistUriMatch by lazy(LazyThreadSafetyMode.NONE) {
                    Regex("""spotify:artist:([A-Za-z0-9]{22})""").find(rawJson)
                }

                val isNonArtistUri = uri.contains(":playlist:") ||
                    uri.contains(":album:") ||
                    uri.contains(":track:") ||
                    uri.contains(":episode:") ||
                    uri.contains(":show:") ||
                    uri.contains(":collection:") ||
                    typeName.equals("Playlist", ignoreCase = true) ||
                    typeName.equals("Album", ignoreCase = true) ||
                    typeName.equals("PseudoPlaylist", ignoreCase = true)

                val isArtist = !isNonArtistUri && (
                    typeName.contains("Artist", ignoreCase = true) ||
                        itemType.contains("Artist", ignoreCase = true) ||
                        entryType.contains("Artist", ignoreCase = true) ||
                        uri.contains(":artist:") ||
                        regexArtistUriMatch != null ||
                        assumeAllNonFoldersAreArtists
                    )
                if (!isArtist) return@runCatching

                val id = extractArtistId(uri)
                    ?: regexArtistUriMatch?.groupValues?.getOrNull(1)
                    ?: data["id"].str?.let(::extractArtistId)
                    ?: itemObj["id"].str?.let(::extractArtistId)
                    ?: uri.substringAfterLast(":").takeIf { Regex("""^[A-Za-z0-9]{22}$""").matches(it) }
                    ?: return@runCatching

                val identityTrait = data["identityTrait"].obj ?: itemObj["identityTrait"].obj
                val rawName = data["profile"].obj?.get("name").str
                    ?: identityTrait?.get("name").str
                    ?: data["name"].str
                    ?: itemObj["name"].str
                    ?: itemObj["profile"].obj?.get("name").str
                    ?: entryObj["name"].str
                    ?: data["header"].obj?.get("title").str
                    ?: Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)+)"""").find(rawJson)?.groupValues?.getOrNull(1)
                        ?.replace("\\\"", "\"")
                        ?.replace("\\\\", "\\")
                val name = rawName?.replace('\u00A0', ' ')?.trim().orEmpty()

                val imageUrl = data["visuals"].obj
                    ?.get("avatarImage").obj
                    ?.get("sources").arr?.firstOrNull().obj
                    ?.get("url").str
                    ?: data["visuals"].obj
                        ?.get("headerImage").obj
                        ?.get("sources").arr?.firstOrNull().obj
                        ?.get("url").str
                    ?: data["images"].obj
                        ?.get("items").arr?.firstOrNull().obj
                        ?.get("sources").arr?.firstOrNull().obj
                        ?.get("url").str
                    ?: data["images"].arr?.firstOrNull().obj
                        ?.get("url").str
                    ?: data["visualIdentityTrait"].obj
                        ?.get("squareCoverImage").obj
                        ?.get("image").obj
                        ?.get("data").obj
                        ?.get("sources").arr?.firstOrNull().obj
                        ?.get("url").str
                    ?: identityTrait
                        ?.get("squareCoverImage").obj
                        ?.get("image").obj
                        ?.get("data").obj
                        ?.get("sources").arr?.firstOrNull().obj
                        ?.get("url").str
                    ?: Regex("""https://(?:i\.scdn\.co/image|image-cdn-[a-z0-9-]+\.spotifycdn\.com/image|mosaic\.scdn\.co)/[^"\\\s]+""")
                        .find(rawJson)?.value

                val existingIdx = results.indexOfFirst { it.id == id }
                if (existingIdx == -1) {
                    results.add(
                        app.vitune.providers.spotify.models.SpotifyArtistFull(
                            id = id,
                            name = name,
                            imageUrl = imageUrl
                        )
                    )
                } else {
                    val existing = results[existingIdx]
                    if ((existing.name.isBlank() && name.isNotBlank()) ||
                        (existing.imageUrl.isNullOrBlank() && !imageUrl.isNullOrBlank())
                    ) {
                        results[existingIdx] = existing.copy(
                            name = existing.name.ifBlank { name },
                            imageUrl = existing.imageUrl ?: imageUrl
                        )
                    }
                }
            }
        }
    }

    /**
     * Fetches ALL of the user's followed artists on Spotify (including 500+ followed artists) using:
     * 1. Pathfinder `libraryV3` (both `flatten = false` and `flatten = true` with `Artists` filter, unfiltered fallback, and folder recursion)
     * 2. Web API `/v1/me/following?type=artist` (merged so no artist is ever missed)
     * 3. Batch metadata enrichment via `/v1/artists?ids=...` and Pathfinder `queryArtistOverview` for any artist missing a name or avatar.
     */
    suspend fun getFollowedArtists(
        accessToken: String,
        maxArtists: Int = 5000,
        onProgress: ((fetched: Int) -> Unit)? = null
    ): List<app.vitune.providers.spotify.models.SpotifyArtistFull> = kotlinx.coroutines.coroutineScope {
        val results = mutableListOf<app.vitune.providers.spotify.models.SpotifyArtistFull>()
        val folderUris = mutableListOf<String>()

        var reportedTotalArtists = -1

        suspend fun runLibraryV3Pass(
            filtersList: List<String>,
            flatten: Boolean
        ) {
            var offset = 0
            val limit = 50
            var consecutiveStalePages = 0
            var safetyPages = 0

            while (offset < maxArtists && results.size < maxArtists && safetyPages < 120) {
                safetyPages++
                val variables = buildJsonObject {
                    put(
                        "filters",
                        buildJsonArray {
                            filtersList.forEach { add(JsonPrimitive(it)) }
                        }
                    )
                    put("order", JsonNull)
                    put("textFilter", "")
                    put(
                        "features",
                        buildJsonArray {
                            add(JsonPrimitive("LIKED_SONGS"))
                            add(JsonPrimitive("YOUR_EPISODES"))
                        }
                    )
                    put("limit", limit)
                    put("offset", offset)
                    put("flatten", flatten)
                    put("expandedFolders", buildJsonArray {})
                    put("folderUri", JsonNull)
                    put("includeFoldersWhenFlattening", true)
                }

                var root: JsonObject? = null
                for (attempt in 0..2) {
                    root = executeGraphQL(
                        accessToken = accessToken,
                        operationName = "libraryV3",
                        variables = variables,
                        seedHash = HASH_LIBRARY_V3
                    )
                    if (root != null) break
                    kotlinx.coroutines.delay(250L * (attempt + 1))
                }

                val libraryData = root?.get("data").obj
                    ?.get("me").obj
                    ?.get("libraryV3").obj
                    ?: break
                val items = libraryData["items"].arr ?: break
                val rawItemsCount = items.size
                if (rawItemsCount == 0) break

                val sizeBefore = results.size
                parseLibraryV3Artists(
                    items = items,
                    results = results,
                    foldersOut = folderUris,
                    assumeAllNonFoldersAreArtists = filtersList.any { it.equals("Artists", ignoreCase = true) }
                )
                if (results.size > sizeBefore) {
                    onProgress?.invoke(results.size)
                }

                val pagingInfo = libraryData["pagingInfo"].obj ?: libraryData["PagingInfo"].obj
                val totalCount = pagingInfo?.get("totalCount").int
                    ?: libraryData["totalCount"].int
                    ?: -1
                if (filtersList.isNotEmpty() && totalCount > reportedTotalArtists) {
                    reportedTotalArtists = totalCount
                }

                offset += rawItemsCount
                if (totalCount > 0 && offset >= totalCount) break
                if (results.size == sizeBefore && filtersList.isNotEmpty()) {
                    consecutiveStalePages++
                    if (totalCount <= 0 && consecutiveStalePages >= 2) break
                } else {
                    consecutiveStalePages = 0
                }
            }
        }

        // 1A. Primary Web Player query: filters = ["Artists"], flatten = false
        runCatching { runLibraryV3Pass(filtersList = listOf("Artists"), flatten = false) }

        // 1B. Flattened pass: filters = ["Artists"], flatten = true (catches any artists flattened from folders)
        if (results.isEmpty() || (reportedTotalArtists > 0 && results.size < reportedTotalArtists)) {
            runCatching { runLibraryV3Pass(filtersList = listOf("Artists"), flatten = true) }
        }

        // 1C. Traverse any discovered folders with full pagination for artists
        for (folderUri in folderUris.distinct()) {
            runCatching {
                var folderOffset = 0
                val folderLimit = 100
                var safetyFolderPages = 0
                while (folderOffset < 2000 && results.size < maxArtists && safetyFolderPages < 30) {
                    safetyFolderPages++
                    val folderVars = buildJsonObject {
                        put("filters", buildJsonArray {})
                        put("order", JsonNull)
                        put("textFilter", "")
                        put(
                            "features",
                            buildJsonArray {
                                add(JsonPrimitive("LIKED_SONGS"))
                                add(JsonPrimitive("YOUR_EPISODES"))
                            }
                        )
                        put("limit", folderLimit)
                        put("offset", folderOffset)
                        put("flatten", false)
                        put("expandedFolders", buildJsonArray {})
                        put("folderUri", JsonPrimitive(folderUri))
                        put("includeFoldersWhenFlattening", true)
                    }
                    val folderRoot = executeGraphQL(
                        accessToken = accessToken,
                        operationName = "libraryV3",
                        variables = folderVars,
                        seedHash = HASH_LIBRARY_V3
                    )
                    val folderLib = folderRoot?.get("data").obj
                        ?.get("me").obj
                        ?.get("libraryV3").obj
                        ?: break
                    val folderItems = folderLib["items"].arr ?: break
                    if (folderItems.isEmpty()) break

                    val sizeBefore = results.size
                    parseLibraryV3Artists(
                        items = folderItems,
                        results = results,
                        foldersOut = null,
                        assumeAllNonFoldersAreArtists = false
                    )
                    if (results.size > sizeBefore) {
                        onProgress?.invoke(results.size)
                    }

                    val folderTotal = folderLib["pagingInfo"].obj?.get("totalCount").int
                        ?: folderLib["PagingInfo"].obj?.get("totalCount").int
                        ?: folderLib["totalCount"].int
                        ?: -1
                    folderOffset += folderItems.size
                    if (folderTotal > 0 && folderOffset >= folderTotal) break
                    if (folderTotal <= 0 && folderItems.size < folderLimit) break
                }
            }
        }

        // 1D. Unfiltered libraryV3 fallback if "Artists" filter returned nothing or missed items
        if (results.isEmpty() || (reportedTotalArtists > 0 && results.size < reportedTotalArtists)) {
            runCatching { runLibraryV3Pass(filtersList = emptyList(), flatten = true) }
        }

        // 2. Also query Web API /v1/me/following?type=artist and merge so no followed artist is missed
        if (results.size < maxArtists) {
            runCatching {
                var after: String? = null
                var safetyPages = 0
                while (safetyPages < 100 && results.size < maxArtists) {
                    safetyPages++
                    var response = client.get("$SPOTIFY_API_BASE_URL/me/following") {
                        header("Authorization", "Bearer $accessToken")
                        header("User-Agent", WEB_USER_AGENT)
                        parameter("type", "artist")
                        parameter("limit", 50)
                        if (after != null) parameter("after", after)
                    }
                    if (response.status.value == 429) {
                        val retrySec = response.headers["Retry-After"]?.toLongOrNull() ?: 1L
                        if (retrySec <= 3L) {
                            kotlinx.coroutines.delay(retrySec * 1000L)
                            response = client.get("$SPOTIFY_API_BASE_URL/me/following") {
                                header("Authorization", "Bearer $accessToken")
                                header("User-Agent", WEB_USER_AGENT)
                                parameter("type", "artist")
                                parameter("limit", 50)
                                if (after != null) parameter("after", after)
                            }
                        }
                    }
                    if (response.status.value !in 200..299) break
                    val root = jsonParser.parseToJsonElement(response.bodyAsText()).obj ?: break
                    val artistsObj = root["artists"].obj ?: break
                    val items = artistsObj["items"].arr ?: break
                    if (items.isEmpty()) break

                    var lastId: String? = null
                    val sizeBefore = results.size
                    for (el in items) {
                        runCatching {
                            val obj = el.obj ?: return@runCatching
                            val id = obj["id"].str?.takeIf { it.isNotBlank() } ?: return@runCatching
                            lastId = id
                            val name = obj["name"].str?.replace('\u00A0', ' ')?.trim().orEmpty()
                            val img = obj["images"].arr?.firstOrNull().obj?.get("url").str
                            val followers = obj["followers"].obj?.get("total").int
                            val existingIdx = results.indexOfFirst { it.id == id }
                            if (existingIdx == -1) {
                                results.add(
                                    app.vitune.providers.spotify.models.SpotifyArtistFull(
                                        id = id,
                                        name = name,
                                        imageUrl = img,
                                        followers = followers
                                    )
                                )
                            } else {
                                val existing = results[existingIdx]
                                if (existing.name.isBlank() || existing.imageUrl.isNullOrBlank()) {
                                    results[existingIdx] = existing.copy(
                                        name = existing.name.ifBlank { name },
                                        imageUrl = existing.imageUrl ?: img,
                                        followers = existing.followers ?: followers
                                    )
                                }
                            }
                        }
                    }
                    if (results.size > sizeBefore) {
                        onProgress?.invoke(results.size)
                    }

                    val cursorsAfter = artistsObj["cursors"].obj?.get("after").str
                    val nextUrl = artistsObj["next"].str
                    val nextUrlAfter = nextUrl?.let {
                        Regex("""[?&]after=([^&]+)""").find(it)?.groupValues?.getOrNull(1)
                    }
                    val nextAfter = cursorsAfter?.takeIf { it.isNotBlank() }
                        ?: nextUrlAfter?.takeIf { it.isNotBlank() }
                        ?: lastId
                    if (nextAfter.isNullOrBlank() || nextAfter == after) break
                    if (nextUrl.isNullOrBlank() && cursorsAfter.isNullOrBlank() && items.size < 50) break
                    after = nextAfter
                }
            }
        }

        // 3. Batch-enrich any artist missing name or imageUrl via /v1/artists?ids=...
        // Prefer Client Credentials token first so sp_dc never hits /v1/ rate limits
        val incompleteIds = results
            .filter { it.name.isBlank() || it.imageUrl.isNullOrBlank() }
            .map { it.id }
            .filter { Regex("""^[A-Za-z0-9]{22}$""").matches(it) }
            .distinct()

        if (incompleteIds.isNotEmpty()) {
            val tokensToTry = listOfNotNull(
                getClientCredentialsToken()?.takeIf { it.isNotBlank() },
                accessToken.takeIf { it.isNotBlank() }
            ).distinct()

            for (chunk in incompleteIds.chunked(50)) {
                for (bearer in tokensToTry) {
                    var chunkSuccess = false
                    runCatching {
                        val resp = client.get("$SPOTIFY_API_BASE_URL/artists") {
                            header("Authorization", "Bearer $bearer")
                            header("User-Agent", WEB_USER_AGENT)
                            parameter("ids", chunk.joinToString(","))
                        }
                        if (resp.status.isSuccess()) {
                            val root = jsonParser.parseToJsonElement(resp.bodyAsText()).obj
                            val artistsArr = root?.get("artists").arr ?: JsonArray(emptyList())
                            for (el in artistsArr) {
                                val obj = el.obj ?: continue
                                val id = obj["id"].str ?: continue
                                val name = obj["name"].str?.replace('\u00A0', ' ')?.trim().orEmpty()
                                val img = obj["images"].arr?.firstOrNull().obj?.get("url").str
                                val idx = results.indexOfFirst { it.id == id }
                                if (idx != -1) {
                                    val current = results[idx]
                                    results[idx] = current.copy(
                                        name = current.name.ifBlank { name },
                                        imageUrl = current.imageUrl ?: img
                                    )
                                }
                            }
                            chunkSuccess = true
                        }
                    }
                    if (chunkSuccess) break
                }
            }
        }

        // 4. Parallel fallback via Pathfinder `queryArtistOverview` / `getArtistById` for any still-unnamed artist
        val unnamedIndices = results.indices.filter { results[it].name.isBlank() }
        if (unnamedIndices.isNotEmpty()) {
            val enrichSemaphore = Semaphore(8)
            val resolvedPairs = unnamedIndices.map { index ->
                async {
                    enrichSemaphore.withPermit {
                        val resolved = runCatching { getArtistById(results[index].id, accessToken) }.getOrNull()
                        if (resolved != null && resolved.name.isNotBlank()) index to resolved else null
                    }
                }
            }.awaitAll().filterNotNull()
            for ((idx, artist) in resolvedPairs) {
                results[idx] = artist
            }
        }

        results.filter { it.id.isNotBlank() && it.name.isNotBlank() }.take(maxArtists)
    }

    /**
     * Fetches a single Spotify artist's metadata (name & avatar) by Spotify artist ID or link,
     * using Pathfinder `queryArtistOverview` first (never 429s), then `/v1/artists/{id}`, then web scrape.
     */
    suspend fun getArtistById(
        artistIdOrUrl: String,
        accessToken: String? = null
    ): app.vitune.providers.spotify.models.SpotifyArtistFull? {
        val cleanId = extractArtistId(artistIdOrUrl) ?: return null
        val gqlToken = accessToken?.takeIf { it.isNotBlank() } ?: getAnonymousAccessToken()

        // 1. Pathfinder GraphQL `queryArtistOverview`
        if (!gqlToken.isNullOrBlank()) {
            try {
                val vars = buildJsonObject {
                    put("uri", "spotify:artist:$cleanId")
                    put("locale", "")
                }
                val pf = executeGraphQL(
                    accessToken = gqlToken,
                    operationName = "queryArtistOverview",
                    variables = vars,
                    seedHash = HASH_QUERY_ARTIST_OVERVIEW
                )
                val artistUnion = pf?.get("data").obj?.get("artistUnion").obj
                if (artistUnion != null) {
                    val name = (artistUnion["profile"].obj?.get("name").str
                        ?: artistUnion["name"].str)
                        ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
                    val img = artistUnion["visuals"].obj
                        ?.get("avatarImage").obj
                        ?.get("sources").arr?.firstOrNull().obj
                        ?.get("url").str
                    val followers = artistUnion["stats"].obj
                        ?.get("followers").int
                    if (!name.isNullOrBlank()) {
                        return app.vitune.providers.spotify.models.SpotifyArtistFull(
                            id = cleanId,
                            name = name,
                            imageUrl = img,
                            followers = followers
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Web API `/v1/artists/{id}`
        val token = getClientCredentialsToken() ?: accessToken?.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank()) {
            try {
                val response = client.get("$SPOTIFY_API_BASE_URL/artists/$cleanId") {
                    header("Authorization", "Bearer $token")
                    header("User-Agent", WEB_USER_AGENT)
                }
                if (response.status.value in 200..299) {
                    val obj = jsonParser.parseToJsonElement(response.bodyAsText()).obj
                    val name = obj?.get("name").str?.takeIf { it.isNotBlank() }
                    val img = obj?.get("images").arr?.firstOrNull().obj?.get("url").str
                    val followers = obj?.get("followers").obj?.get("total").int
                    if (name != null) {
                        return app.vitune.providers.spotify.models.SpotifyArtistFull(
                            id = cleanId,
                            name = name,
                            imageUrl = img,
                            followers = followers
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Scrape open.spotify.com/embed/artist/<id> or open.spotify.com/artist/<id> meta tags as universal fallback
        try {
            val html = client.get("https://open.spotify.com/artist/$cleanId") {
                header("User-Agent", WEB_USER_AGENT)
            }.bodyAsText()
            val titleMatch = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)?.substringBefore("|")?.substringBefore("- Spotify")?.trim()
            val imageMatch = Regex("""<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            if (!titleMatch.isNullOrBlank()) {
                val unescapedTitle = titleMatch
                    .replace("&amp;", "&")
                    .replace("&#x27;", "'")
                    .replace("&#39;", "'")
                    .replace("&quot;", "\"")
                    .trim()
                return app.vitune.providers.spotify.models.SpotifyArtistFull(
                    id = cleanId,
                    name = unescapedTitle,
                    imageUrl = imageMatch
                )
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Resolves a Spotify artist by name using Pathfinder GraphQL `searchDesktop` first,
     * with `/v1/search?type=artist` as fallback.
     */
    suspend fun searchArtistByName(
        artistName: String,
        accessToken: String? = null
    ): app.vitune.providers.spotify.models.SpotifyArtistFull? {
        val cleanName = artistName.trim()
        if (cleanName.isBlank()) return null

        val gqlToken = accessToken?.takeIf { it.isNotBlank() } ?: getAnonymousAccessToken()
        if (!gqlToken.isNullOrBlank()) {
            try {
                val variables = buildJsonObject {
                    put("searchTerm", cleanName)
                    put("offset", 0)
                    put("limit", 5)
                    put("numberOfTopResults", 3)
                    put("includeAudiobooks", false)
                    put("includePreReleases", false)
                }
                val pf = executeGraphQL(
                    accessToken = gqlToken,
                    operationName = "searchDesktop",
                    variables = variables,
                    seedHash = HASH_SEARCH_DESKTOP
                )
                val artistsItems = pf?.get("data").obj
                    ?.get("searchV2").obj
                    ?.get("artists").obj
                    ?.get("items").arr
                val parsed = artistsItems?.mapNotNull { el ->
                    runCatching {
                        val elObj = el.obj ?: return@mapNotNull null
                        val data = elObj["data"].obj ?: elObj
                        val uri = data["uri"].str.orEmpty()
                        val id = extractArtistId(uri)
                            ?: uri.substringAfterLast(":").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val name = (data["profile"].obj?.get("name").str
                            ?: data["name"].str)
                            ?.replace('\u00A0', ' ')
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val img = data["visuals"].obj
                            ?.get("avatarImage").obj
                            ?.get("sources").arr?.firstOrNull().obj
                            ?.get("url").str
                        app.vitune.providers.spotify.models.SpotifyArtistFull(
                            id = id,
                            name = name,
                            imageUrl = img
                        )
                    }.getOrNull()
                }.orEmpty()
                if (parsed.isNotEmpty()) {
                    return parsed.firstOrNull { it.name.equals(cleanName, ignoreCase = true) } ?: parsed.first()
                }
            } catch (_: Exception) {}
        }

        val bearer = getClientCredentialsToken() ?: accessToken ?: return null
        return try {
            val resp = client.get("$SPOTIFY_API_BASE_URL/search") {
                header("Authorization", "Bearer $bearer")
                header("User-Agent", WEB_USER_AGENT)
                parameter("q", cleanName)
                parameter("type", "artist")
                parameter("limit", 3)
            }
            if (!resp.status.isSuccess()) return null
            val root = jsonParser.parseToJsonElement(resp.bodyAsText()).obj ?: return null
            val items = root["artists"].obj?.get("items").arr ?: return null
            val parsed = items.mapNotNull { el ->
                val obj = el.obj ?: return@mapNotNull null
                val id = obj["id"].str ?: return@mapNotNull null
                val name = obj["name"].str ?: return@mapNotNull null
                val img = obj["images"].arr?.firstOrNull().obj?.get("url").str
                app.vitune.providers.spotify.models.SpotifyArtistFull(
                    id = id,
                    name = name,
                    imageUrl = img
                )
            }
            parsed.firstOrNull { it.name.equals(cleanName, ignoreCase = true) } ?: parsed.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Searches tracks via Pathfinder GraphQL `searchDesktop` (`HASH_SEARCH_DESKTOP`).
     */
    private suspend fun searchTracksViaGraphQL(
        gqlToken: String,
        query: String,
        limit: Int = 10
    ): List<SpotifyTrack> {
        if (query.isBlank()) return emptyList()
        return try {
            val variables = buildJsonObject {
                put("searchTerm", query)
                put("offset", 0)
                put("limit", limit)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includePreReleases", false)
            }
            val pf = executeGraphQL(
                accessToken = gqlToken,
                operationName = "searchDesktop",
                variables = variables,
                seedHash = HASH_SEARCH_DESKTOP
            )
            val data = pf?.get("data").obj
            val tracksItems = data?.get("searchV2").obj
                ?.get("tracksV2").obj
                ?.get("items").arr
                ?: data?.get("search").obj
                    ?.get("tracks").obj
                    ?.get("items").arr

            tracksItems?.mapNotNull { el ->
                runCatching {
                    val wrapper = el.obj ?: return@mapNotNull null
                    val trackData = wrapper["item"].obj?.get("data").obj
                        ?: wrapper["track"].obj
                        ?: wrapper["itemV2"].obj?.get("data").obj
                        ?: wrapper
                    parseGraphQLTrackObject(trackData)
                }.getOrNull()
            }.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseDateElement(dateEl: JsonElement?, fallbackStr: String? = null): String {
        val dateObj = dateEl.obj
        if (dateObj != null) {
            val iso = dateObj["isoString"].str?.take(10)
            if (!iso.isNullOrBlank()) return iso
            val year = dateObj["year"].int
            val month = dateObj["month"].int
            val day = dateObj["day"].int
            if (year != null && month != null && day != null) {
                return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
            }
            if (year != null && month != null) {
                return String.format(Locale.US, "%04d-%02d", year, month)
            }
            if (year != null) {
                return year.toString()
            }
        }
        val primStr = dateEl.str?.take(10)
        if (!primStr.isNullOrBlank() && primStr.first().isDigit()) return primStr
        val fb = fallbackStr?.trim()?.take(10)
        if (!fb.isNullOrBlank() && fb.first().isDigit()) return fb
        return ""
    }

    private fun parseOverviewReleaseNode(
        relNode: JsonObject,
        defaultArtistName: String,
        defaultArtistId: String,
        fallbackGroupType: String = "single"
    ): app.vitune.providers.spotify.models.SpotifyReleaseItem? = runCatching {
        val rel = relNode["data"].obj ?: relNode
        val rawUri = rel["uri"].str ?: relNode["uri"].str.orEmpty()
        val relId = (rel["id"].str ?: rawUri.substringAfterLast(":"))
            ?.takeIf { it.isNotBlank() && !it.startsWith("spotify") }
            ?: return@runCatching null
        val relName = (rel["name"].str ?: relNode["name"].str)
            ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val relType = (rel["type"].str
            ?: rel["albumType"].str
            ?: rel["album_type"].str
            ?: fallbackGroupType).lowercase()
        val releaseDate = parseDateElement(
            dateEl = rel["date"] ?: relNode["date"] ?: rel["releaseDate"],
            fallbackStr = rel["release_date"].str
        )
        val coverUrl = rel["coverArt"].obj
            ?.get("sources").arr
            ?.maxByOrNull { (it.obj?.get("width").int ?: 0) }
            .obj?.get("url").str
            ?: rel["coverArt"].obj?.get("sources").arr?.firstOrNull().obj?.get("url").str
            ?: rel["images"].arr?.firstOrNull().obj?.get("url").str
        val totalTracks = rel["tracks"].obj?.get("totalCount").int
            ?: rel["tracksV2"].obj?.get("totalCount").int
            ?: rel["total_tracks"].int
            ?: if (relType == "album" || relType == "compilation") 8 else 1

        val artistsItems = rel["artists"].obj?.get("items").arr ?: rel["artists"].arr
        val artistNames = artistsItems?.mapNotNull { a ->
            val aObj = a.obj ?: return@mapNotNull null
            (aObj["profile"].obj?.get("name").str ?: aObj["name"].str)
                ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
        }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(defaultArtistName.takeIf { it.isNotBlank() })

        val artistIds = artistsItems?.mapNotNull { a ->
            val aObj = a.obj ?: return@mapNotNull null
            (aObj["id"].str ?: aObj["uri"].str?.substringAfterLast(":"))?.takeIf { it.isNotBlank() }
        }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(defaultArtistId.takeIf { it.isNotBlank() })

        val explicit = rel["contentRating"].obj?.get("label").str?.equals("EXPLICIT", ignoreCase = true) == true

        app.vitune.providers.spotify.models.SpotifyReleaseItem(
            id = relId,
            name = relName,
            albumType = relType,
            releaseDate = releaseDate,
            totalTracks = totalTracks,
            coverUrl = coverUrl,
            artists = artistNames,
            artistIds = artistIds,
            explicit = explicit,
            trackPreviewNames = listOf(relName),
            firstTrackName = relName
        )
    }.getOrNull()

    /**
     * Searches albums & new release tracks via Pathfinder GraphQL `searchDesktop` (`HASH_SEARCH_DESKTOP`).
     */
    private suspend fun searchReleasesViaGraphQL(
        gqlToken: String,
        query: String,
        filterArtistName: String? = null,
        limit: Int = 12
    ): List<app.vitune.providers.spotify.models.SpotifyReleaseItem> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<app.vitune.providers.spotify.models.SpotifyReleaseItem>()
        try {
            val variables = buildJsonObject {
                put("searchTerm", query)
                put("offset", 0)
                put("limit", limit)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includePreReleases", true)
            }
            val pf = executeGraphQL(
                accessToken = gqlToken,
                operationName = "searchDesktop",
                variables = variables,
                seedHash = HASH_SEARCH_DESKTOP
            )
            val searchV2 = pf?.get("data").obj?.get("searchV2").obj ?: return emptyList()

            // 1. Parse albumsV2 / albums
            val albumItems = searchV2["albumsV2"].obj?.get("items").arr
                ?: searchV2["albums"].obj?.get("items").arr
            albumItems?.forEach { el ->
                runCatching {
                    val wrapper = el.obj ?: return@forEach
                    val albumData = wrapper["data"].obj
                        ?: wrapper["item"].obj?.get("data").obj
                        ?: wrapper
                    val uri = albumData["uri"].str.orEmpty()
                    val albumId = uri.substringAfterLast(":").takeIf { it.isNotBlank() }
                        ?: albumData["id"].str?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val name = albumData["name"].str
                        ?.replace('\u00A0', ' ')
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val type = (albumData["type"].str
                        ?: albumData["album_type"].str
                        ?: "single").lowercase()
                    val releaseDate = parseDateElement(
                        dateEl = albumData["date"],
                        fallbackStr = albumData["release_date"].str
                    )
                    val coverUrl = albumData["coverArt"].obj
                        ?.get("sources").arr?.firstOrNull().obj
                        ?.get("url").str
                    val artistsItems = albumData["artists"].obj?.get("items").arr
                    val artistNames = artistsItems?.mapNotNull { a ->
                        val aObj = a.obj ?: return@mapNotNull null
                        (aObj["profile"].obj?.get("name").str
                            ?: aObj["name"].str)
                            ?.replace('\u00A0', ' ')
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    }.orEmpty()
                    val artistIds = artistsItems?.mapNotNull { a ->
                        a.obj?.get("uri").str?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                    }.orEmpty()

                    if (filterArtistName != null && artistNames.isNotEmpty() &&
                        artistNames.none {
                            it.equals(filterArtistName, ignoreCase = true) ||
                                it.contains(filterArtistName, ignoreCase = true) ||
                                filterArtistName.contains(it, ignoreCase = true)
                        }
                    ) {
                        return@forEach
                    }

                    out.add(
                        app.vitune.providers.spotify.models.SpotifyReleaseItem(
                            id = albumId,
                            name = name,
                            albumType = type,
                            releaseDate = releaseDate,
                            totalTracks = if (type == "album") 10 else 1,
                            coverUrl = coverUrl,
                            artists = artistNames.ifEmpty { listOfNotNull(filterArtistName) },
                            artistIds = artistIds,
                            trackPreviewNames = listOf(name),
                            firstTrackName = name
                        )
                    )
                }
            }

            // 2. Also parse tracksV2 so single / track releases with exact track metadata are captured
            val tracksItems = searchV2["tracksV2"].obj?.get("items").arr
            tracksItems?.forEach { el ->
                runCatching {
                    val wrapper = el.obj ?: return@forEach
                    val trackData = wrapper["item"].obj?.get("data").obj
                        ?: wrapper["track"].obj
                        ?: wrapper["itemV2"].obj?.get("data").obj
                        ?: wrapper
                    val parsedTrack = parseGraphQLTrackObject(trackData) ?: return@forEach
                    val artistNames = parsedTrack.artists.map { it.name }.filter { it.isNotBlank() }
                    if (filterArtistName != null && artistNames.isNotEmpty() &&
                        artistNames.none {
                            it.equals(filterArtistName, ignoreCase = true) ||
                                it.contains(filterArtistName, ignoreCase = true) ||
                                filterArtistName.contains(it, ignoreCase = true)
                        }
                    ) {
                        return@forEach
                    }

                    val albumObj = trackData["albumOfTrack"].obj ?: trackData["album"].obj
                    val releaseDate = parseDateElement(albumObj?.get("date"))
                    val albumId = parsedTrack.album?.id?.takeIf { it.isNotBlank() } ?: parsedTrack.id
                    val existingIdx = out.indexOfFirst { it.id == albumId || it.name.equals(parsedTrack.album?.name ?: parsedTrack.name, ignoreCase = true) }
                    if (existingIdx != -1) {
                        val existing = out[existingIdx]
                        val mergedTracks = (existing.trackPreviewNames + parsedTrack.name).distinct().take(6)
                        out[existingIdx] = existing.copy(
                            explicit = existing.explicit || parsedTrack.explicit,
                            trackPreviewNames = mergedTracks,
                            firstTrackName = existing.firstTrackName ?: parsedTrack.name,
                            releaseDate = existing.releaseDate.ifBlank { releaseDate }
                        )
                    } else {
                        out.add(
                            app.vitune.providers.spotify.models.SpotifyReleaseItem(
                                id = albumId,
                                name = parsedTrack.album?.name?.takeIf { it.isNotBlank() } ?: parsedTrack.name,
                                albumType = "single",
                                releaseDate = releaseDate,
                                totalTracks = 1,
                                coverUrl = parsedTrack.album?.images?.firstOrNull()?.url,
                                artists = artistNames.ifEmpty { listOfNotNull(filterArtistName) },
                                artistIds = parsedTrack.artists.mapNotNull { it.id?.takeIf { id -> id.isNotBlank() } },
                                explicit = parsedTrack.explicit,
                                trackPreviewNames = listOf(parsedTrack.name),
                                firstTrackName = parsedTrack.name
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * Fetches a Spotify album's metadata and full tracklist via Pathfinder `getAlbum` (`HASH_GET_ALBUM`)
     * with Web API `/v1/albums/{id}` as fallback.
     */
    suspend fun getAlbumTracks(
        albumIdOrUri: String,
        accessToken: String? = null
    ): Pair<app.vitune.providers.spotify.models.SpotifyReleaseItem, List<SpotifyTrack>>? {
        val cleanId = albumIdOrUri
            .removePrefix("spotify:album:")
            .substringAfterLast("/")
            .substringBefore("?")
            .trim()
        if (!Regex("""^[A-Za-z0-9]{22}$""").matches(cleanId)) return null

        val gqlToken = accessToken?.takeIf { it.isNotBlank() } ?: getAnonymousAccessToken()
        if (!gqlToken.isNullOrBlank()) {
            runCatching {
                val vars = buildJsonObject {
                    put("uri", "spotify:album:$cleanId")
                    put("locale", "")
                    put("offset", 0)
                    put("limit", 100)
                }
                val pf = executeGraphQL(
                    accessToken = gqlToken,
                    operationName = "getAlbum",
                    variables = vars,
                    seedHash = HASH_GET_ALBUM
                )
                val albumUnion = pf?.get("data").obj?.get("albumUnion").obj
                if (albumUnion != null) {
                    val albumName = albumUnion["name"].str?.replace('\u00A0', ' ')?.trim().orEmpty()
                    val albumType = (albumUnion["type"].str ?: "album").lowercase()
                    val releaseDate = parseDateElement(albumUnion["date"])
                    val coverUrl = albumUnion["coverArt"].obj
                        ?.get("sources").arr
                        ?.maxByOrNull { it.obj?.get("width").int ?: 0 }
                        .obj?.get("url").str
                        ?: albumUnion["coverArt"].obj?.get("sources").arr?.firstOrNull().obj?.get("url").str
                    val artistsItems = albumUnion["artists"].obj?.get("items").arr
                    val artistNames = artistsItems?.mapNotNull { a ->
                        val aObj = a.obj ?: return@mapNotNull null
                        (aObj["profile"].obj?.get("name").str ?: aObj["name"].str)
                            ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
                    }.orEmpty()
                    val artistIds = artistsItems?.mapNotNull { a ->
                        val aObj = a.obj ?: return@mapNotNull null
                        (aObj["id"].str ?: aObj["uri"].str?.substringAfterLast(":"))?.takeIf { it.isNotBlank() }
                    }.orEmpty()

                    val tracksV2 = albumUnion["tracksV2"].obj ?: albumUnion["tracks"].obj
                    val trackItems = tracksV2?.get("items").arr
                    val tracks = trackItems?.mapNotNull { tEl ->
                        val tObj = tEl.obj?.get("track").obj ?: tEl.obj ?: return@mapNotNull null
                        val trackUri = tObj["uri"].str.orEmpty()
                        val trackId = trackUri.substringAfterLast(":").takeIf { it.isNotBlank() } ?: tObj["id"].str.orEmpty()
                        val trackName = tObj["name"].str?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val durationMs = tObj["duration"].obj?.get("totalMilliseconds").long
                            ?: tObj["duration_ms"].long ?: 0L
                        val explicit = tObj["contentRating"].obj?.get("label").str?.equals("EXPLICIT", ignoreCase = true) == true
                        val trackArtists = (tObj["artists"].obj?.get("items").arr ?: tObj["artists"].arr)?.mapNotNull { a ->
                            val aObj = a.obj ?: return@mapNotNull null
                            val aName = (aObj["profile"].obj?.get("name").str ?: aObj["name"].str)
                                ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val aId = (aObj["id"].str ?: aObj["uri"].str?.substringAfterLast(":")).orEmpty()
                            SpotifyArtist(id = aId, name = aName)
                        }?.takeIf { it.isNotEmpty() } ?: artistNames.mapIndexed { idx, n ->
                            SpotifyArtist(id = artistIds.getOrNull(idx).orEmpty(), name = n)
                        }

                        SpotifyTrack(
                            id = trackId,
                            name = trackName,
                            uri = trackUri.ifBlank { "spotify:track:$trackId" },
                            artists = trackArtists,
                            album = SpotifyAlbumSimple(
                                id = cleanId,
                                name = albumName,
                                images = listOfNotNull(coverUrl?.let { SpotifyImage(url = it) })
                            ),
                            durationMs = durationMs,
                            explicit = explicit
                        )
                    }.orEmpty()

                    if (albumName.isNotBlank() && tracks.isNotEmpty()) {
                        val releaseItem = app.vitune.providers.spotify.models.SpotifyReleaseItem(
                            id = cleanId,
                            name = albumName,
                            albumType = albumType,
                            releaseDate = releaseDate,
                            totalTracks = tracks.size,
                            coverUrl = coverUrl,
                            artists = artistNames,
                            artistIds = artistIds,
                            explicit = tracks.any { it.explicit },
                            trackPreviewNames = tracks.take(5).map { it.name },
                            firstTrackName = tracks.firstOrNull()?.name
                        )
                        return releaseItem to tracks
                    }
                }
            }
        }

        // Fallback: Web API /v1/albums/{id}
        val bearer = getClientCredentialsToken() ?: accessToken?.takeIf { it.isNotBlank() } ?: gqlToken
        if (!bearer.isNullOrBlank()) {
            runCatching {
                val resp = client.get("$SPOTIFY_API_BASE_URL/albums/$cleanId") {
                    header("Authorization", "Bearer $bearer")
                    header("User-Agent", WEB_USER_AGENT)
                }
                if (resp.status.isSuccess()) {
                    val obj = jsonParser.parseToJsonElement(resp.bodyAsText()).obj ?: return@runCatching
                    val albumName = obj["name"].str?.trim().orEmpty()
                    val albumType = (obj["album_type"].str ?: "album").lowercase()
                    val releaseDate = obj["release_date"].str.orEmpty()
                    val coverUrl = obj["images"].arr?.firstOrNull().obj?.get("url").str
                    val albumArtists = obj["artists"].arr?.mapNotNull { a ->
                        val aObj = a.obj ?: return@mapNotNull null
                        val aName = aObj["name"].str?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        SpotifyArtist(id = aObj["id"].str.orEmpty(), name = aName)
                    }.orEmpty()
                    val tracksArr = obj["tracks"].obj?.get("items").arr
                    val tracks = tracksArr?.mapNotNull { tEl ->
                        val tObj = tEl.obj ?: return@mapNotNull null
                        val tId = tObj["id"].str.orEmpty()
                        val tName = tObj["name"].str?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val tArtists = tObj["artists"].arr?.mapNotNull { a ->
                            val aObj = a.obj ?: return@mapNotNull null
                            val aName = aObj["name"].str?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            SpotifyArtist(id = aObj["id"].str.orEmpty(), name = aName)
                        }?.takeIf { it.isNotEmpty() } ?: albumArtists
                        SpotifyTrack(
                            id = tId,
                            name = tName,
                            uri = tObj["uri"].str ?: "spotify:track:$tId",
                            artists = tArtists,
                            album = SpotifyAlbumSimple(
                                id = cleanId,
                                name = albumName,
                                images = listOfNotNull(coverUrl?.let { SpotifyImage(url = it) })
                            ),
                            durationMs = tObj["duration_ms"].long ?: 0L,
                            explicit = tObj["explicit"].bool ?: false
                        )
                    }.orEmpty()
                    if (albumName.isNotBlank() && tracks.isNotEmpty()) {
                        val releaseItem = app.vitune.providers.spotify.models.SpotifyReleaseItem(
                            id = cleanId,
                            name = albumName,
                            albumType = albumType,
                            releaseDate = releaseDate,
                            totalTracks = tracks.size,
                            coverUrl = coverUrl,
                            artists = albumArtists.map { it.name },
                            artistIds = albumArtists.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } },
                            explicit = tracks.any { it.explicit },
                            trackPreviewNames = tracks.take(5).map { it.name },
                            firstTrackName = tracks.firstOrNull()?.name
                        )
                        return releaseItem to tracks
                    }
                }
            }
        }
        return null
    }

    /**
     * Fetches the complete Spotify discography (latest release, all albums, all singles/EPs, and top tracks)
     * for a given Spotify artist ID (`spotify:artist:<id>` or 22-char ID) or artist name.
     */
    suspend fun getArtistDiscography(
        artistIdOrName: String,
        artistNameHint: String? = null,
        accessToken: String? = null
    ): app.vitune.providers.spotify.models.SpotifyArtistDiscography? = coroutineScope {
        val gqlToken = accessToken?.takeIf { it.isNotBlank() } ?: getAnonymousAccessToken()
        val ccToken = getClientCredentialsToken()
        val bearer = ccToken ?: accessToken?.takeIf { it.isNotBlank() } ?: gqlToken
        val market = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

        var cleanId = extractArtistId(artistIdOrName)
        var resolvedName = artistNameHint?.trim().orEmpty()
        var resolvedAvatar: String? = null

        if (cleanId.isNullOrBlank()) {
            val queryName = resolvedName.ifBlank {
                artistIdOrName.removePrefix("name:").trim()
            }
            if (queryName.isNotBlank() && !queryName.startsWith("UC")) {
                val searched = searchArtistByName(queryName, accessToken ?: gqlToken)
                if (searched != null) {
                    cleanId = searched.id
                    if (resolvedName.isBlank()) resolvedName = searched.name
                    resolvedAvatar = searched.imageUrl
                }
            }
        }

        if (cleanId.isNullOrBlank()) return@coroutineScope null

        val allAlbums = java.util.Collections.synchronizedList(
            mutableListOf<app.vitune.providers.spotify.models.SpotifyReleaseItem>()
        )
        val allSingles = java.util.Collections.synchronizedList(
            mutableListOf<app.vitune.providers.spotify.models.SpotifyReleaseItem>()
        )
        val allTopTracks = java.util.Collections.synchronizedList(mutableListOf<SpotifyTrack>())
        var latestRelease: app.vitune.providers.spotify.models.SpotifyReleaseItem? = null
        var headerImageUrl: String? = null
        var followersCount: Int? = null
        var biographyText: String? = null

        // 1. Pathfinder `queryArtistOverview`
        val overviewJob = async {
            if (gqlToken.isNullOrBlank()) return@async
            runCatching {
                val vars = buildJsonObject {
                    put("uri", "spotify:artist:$cleanId")
                    put("locale", "")
                }
                val pf = executeGraphQL(
                    accessToken = gqlToken,
                    operationName = "queryArtistOverview",
                    variables = vars,
                    seedHash = HASH_QUERY_ARTIST_OVERVIEW
                )
                val artistUnion = pf?.get("data").obj?.get("artistUnion").obj ?: return@runCatching
                val profileName = (artistUnion["profile"].obj?.get("name").str ?: artistUnion["name"].str)
                    ?.replace('\u00A0', ' ')?.trim()
                if (!profileName.isNullOrBlank()) {
                    resolvedName = profileName
                }
                biographyText = artistUnion["profile"].obj?.get("biography").obj?.get("text").str
                    ?.replace(Regex("<[^>]*>"), "")?.trim()?.takeIf { it.isNotBlank() }
                followersCount = artistUnion["stats"].obj?.get("followers").int

                val avatarSources = artistUnion["visuals"].obj?.get("avatarImage").obj?.get("sources").arr
                val bestAvatar = avatarSources?.maxByOrNull { it.obj?.get("width").int ?: 0 }.obj?.get("url").str
                    ?: avatarSources?.firstOrNull().obj?.get("url").str
                if (!bestAvatar.isNullOrBlank()) resolvedAvatar = bestAvatar

                val headerSources = artistUnion["visuals"].obj?.get("headerImage").obj?.get("sources").arr
                headerImageUrl = headerSources?.maxByOrNull { it.obj?.get("width").int ?: 0 }.obj?.get("url").str
                    ?: headerSources?.firstOrNull().obj?.get("url").str
                    ?: resolvedAvatar

                val discog = artistUnion["discography"].obj ?: return@runCatching

                // Parse latest
                discog["latest"].obj?.let { latestObj ->
                    latestRelease = parseOverviewReleaseNode(
                        relNode = latestObj,
                        defaultArtistName = resolvedName,
                        defaultArtistId = cleanId,
                        fallbackGroupType = "single"
                    )
                }

                // Parse albums & compilations
                listOf("albums" to "album", "popularReleasesAlbums" to "album", "compilations" to "compilation").forEach { (groupKey, defaultType) ->
                    val groupItems = discog[groupKey].obj?.get("items").arr
                    groupItems?.forEach { groupEl ->
                        val groupObj = groupEl.obj ?: return@forEach
                        val nestedReleases = groupObj["releases"].obj?.get("items").arr
                        if (nestedReleases != null) {
                            nestedReleases.forEach { relEl ->
                                relEl.obj?.let { relObj ->
                                    parseOverviewReleaseNode(relObj, resolvedName, cleanId, defaultType)?.let { item ->
                                        if (item.albumType == "single" || item.albumType == "ep") {
                                            allSingles.add(item)
                                        } else {
                                            allAlbums.add(item)
                                        }
                                    }
                                }
                            }
                        } else {
                            parseOverviewReleaseNode(groupObj, resolvedName, cleanId, defaultType)?.let { item ->
                                if (item.albumType == "single" || item.albumType == "ep") {
                                    allSingles.add(item)
                                } else {
                                    allAlbums.add(item)
                                }
                            }
                        }
                    }
                }

                // Parse singles & EPs
                val singleItems = discog["singles"].obj?.get("items").arr
                singleItems?.forEach { groupEl ->
                    val groupObj = groupEl.obj ?: return@forEach
                    val nestedReleases = groupObj["releases"].obj?.get("items").arr
                    if (nestedReleases != null) {
                        nestedReleases.forEach { relEl ->
                            relEl.obj?.let { relObj ->
                                parseOverviewReleaseNode(relObj, resolvedName, cleanId, "single")?.let(allSingles::add)
                            }
                        }
                    } else {
                        parseOverviewReleaseNode(groupObj, resolvedName, cleanId, "single")?.let(allSingles::add)
                    }
                }

                // Parse topTracks
                val topTrackItems = discog["topTracks"].obj?.get("items").arr
                topTrackItems?.forEach { itemEl ->
                    val trackObj = itemEl.obj?.get("track").obj ?: itemEl.obj ?: return@forEach
                    parseGraphQLTrackObject(trackObj)?.let { track ->
                        val enrichedTrack = if (track.artists.isEmpty() && resolvedName.isNotBlank()) {
                            track.copy(artists = listOf(SpotifyArtist(id = cleanId, name = resolvedName)))
                        } else track
                        allTopTracks.add(enrichedTrack)
                    }
                }
            }
        }

        // 2. Web API `/v1/artists/{id}/albums?include_groups=album,single,compilation&limit=50` (fetches complete catalog!)
        val webAlbumsJob = async {
            if (bearer.isNullOrBlank()) return@async
            for (pageOffset in listOf(0, 50)) {
                val pageSucceeded = runCatching {
                    val resp = client.get("$SPOTIFY_API_BASE_URL/artists/$cleanId/albums") {
                        header("Authorization", "Bearer $bearer")
                        header("User-Agent", WEB_USER_AGENT)
                        parameter("include_groups", "album,single,compilation")
                        parameter("market", market)
                        parameter("limit", 50)
                        parameter("offset", pageOffset)
                    }
                    if (!resp.status.isSuccess()) return@runCatching false
                    val root = jsonParser.parseToJsonElement(resp.bodyAsText()).obj ?: return@runCatching false
                    val items = root["items"].arr ?: return@runCatching false
                    items.forEach { el ->
                        val obj = el.obj ?: return@forEach
                        val albumId = obj["id"].str?.takeIf { it.isNotBlank() } ?: return@forEach
                        val name = obj["name"].str?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
                            ?: return@forEach
                        val albumType = (obj["album_type"].str ?: obj["album_group"].str ?: "single").lowercase()
                        val releaseDate = obj["release_date"].str.orEmpty()
                        val totalTracks = obj["total_tracks"].int ?: 1
                        val coverUrl = obj["images"].arr?.firstOrNull().obj?.get("url").str
                        val artistsArr = obj["artists"].arr
                        val artistNames = artistsArr?.mapNotNull { a ->
                            a.obj?.get("name").str?.trim()?.takeIf { it.isNotBlank() }
                        }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(resolvedName.takeIf { it.isNotBlank() })
                        val artistIdsList = artistsArr?.mapNotNull { a ->
                            a.obj?.get("id").str?.trim()?.takeIf { it.isNotBlank() }
                        }?.takeIf { it.isNotEmpty() } ?: listOf(cleanId)

                        val item = app.vitune.providers.spotify.models.SpotifyReleaseItem(
                            id = albumId,
                            name = name,
                            albumType = albumType,
                            releaseDate = releaseDate,
                            totalTracks = totalTracks,
                            coverUrl = coverUrl,
                            artists = artistNames,
                            artistIds = artistIdsList,
                            trackPreviewNames = listOf(name),
                            firstTrackName = name
                        )
                        if (albumType == "album" || albumType == "compilation") {
                            allAlbums.add(item)
                        } else {
                            allSingles.add(item)
                        }
                    }
                    items.size >= 50
                }.getOrDefault(false)
                if (!pageSucceeded) break
            }
        }

        // 3. Web API `/v1/artists/{id}/top-tracks` supplement
        val topTracksJob = async {
            if (bearer.isNullOrBlank()) return@async
            runCatching {
                val resp = client.get("$SPOTIFY_API_BASE_URL/artists/$cleanId/top-tracks") {
                    header("Authorization", "Bearer $bearer")
                    header("User-Agent", WEB_USER_AGENT)
                    parameter("market", market)
                }
                if (resp.status.isSuccess()) {
                    val root = jsonParser.parseToJsonElement(resp.bodyAsText()).obj ?: return@runCatching
                    val tracksArr = root["tracks"].arr ?: return@runCatching
                    tracksArr.forEach { el ->
                        el.obj?.let(::parseGraphQLTrackObject)?.let(allTopTracks::add)
                    }
                }
            }
        }

        overviewJob.await()
        webAlbumsJob.await()
        topTracksJob.await()

        latestRelease?.let { latest ->
            if (latest.albumType == "album" || latest.albumType == "compilation" || latest.totalTracks > 3) {
                allAlbums.add(0, latest)
            } else {
                allSingles.add(0, latest)
            }
        }

        val dedupedAlbums = allAlbums
            .filter { it.name.isNotBlank() }
            .distinctBy { it.id.ifBlank { it.name.lowercase().trim() } }
            .distinctBy { it.name.lowercase().trim() }
            .sortedByDescending { normalizeReleaseDateForSort(it.releaseDate) }

        val dedupedSingles = allSingles
            .filter { it.name.isNotBlank() }
            .distinctBy { it.id.ifBlank { it.name.lowercase().trim() } }
            .distinctBy { it.name.lowercase().trim() }
            .sortedByDescending { normalizeReleaseDateForSort(it.releaseDate) }

        val dedupedTopTracks = allTopTracks
            .filter { it.name.isNotBlank() }
            .distinctBy { it.id.ifBlank { it.name.lowercase().trim() } }
            .distinctBy { it.name.lowercase().trim() }

        val newestOverall = (dedupedAlbums + dedupedSingles)
            .maxByOrNull { normalizeReleaseDateForSort(it.releaseDate) }
            ?: latestRelease

        app.vitune.providers.spotify.models.SpotifyArtistDiscography(
            artistId = cleanId,
            artistName = resolvedName,
            avatarUrl = resolvedAvatar,
            headerImageUrl = headerImageUrl ?: resolvedAvatar,
            followers = followersCount,
            biography = biographyText,
            latestRelease = newestOverall,
            albums = dedupedAlbums,
            singles = dedupedSingles,
            topTracks = dedupedTopTracks
        )
    }

    /**
     * Fetches smart, personalized track recommendations from Spotify:
     * 1. Personalized mixes from Spotify Home (`getDailyAndPersonalizedMixes` - e.g. Discover Weekly, Release Radar, Daily Mixes)
     * 2. Top tracks & related tracks from the user's followed Spotify artists and recent Liked Songs via Pathfinder GraphQL `searchDesktop`
     * 3. Seed artist/track search fallback via GraphQL & Client Credentials so recommendations always work.
     */
    suspend fun getSmartRecommendations(
        accessToken: String?,
        seedArtists: List<String> = emptyList(),
        seedTracks: List<String> = emptyList(),
        limit: Int = 24
    ): List<SpotifyTrack> {
        val results = mutableListOf<SpotifyTrack>()
        val seenKeys = HashSet<String>()

        fun addUnique(track: SpotifyTrack) {
            if (track.name.isBlank()) return
            val key = "${track.name.lowercase().trim()}|${track.artists.firstOrNull()?.name?.lowercase()?.trim().orEmpty()}"
            if (seenKeys.add(key)) {
                results.add(track)
            }
        }

        val gqlToken = accessToken?.takeIf { it.isNotBlank() } ?: getAnonymousAccessToken()
        val ccToken = getClientCredentialsToken()

        // 1. Pull tracks from user's Spotify Home personalized mixes (Discover Weekly, Daily Mix, On Repeat)
        if (!gqlToken.isNullOrBlank()) {
            runCatching {
                val mixes = getDailyAndPersonalizedMixes(gqlToken)
                    .sortedByDescending { mix ->
                        val n = mix.name.lowercase()
                        when {
                            n.contains("discover") || n.contains("daily mix") || n.contains("repeat") -> 3
                            n.contains("mix") || n.contains("daylist") || n.contains("radar") -> 2
                            else -> 1
                        }
                    }
                    .take(2)

                for (mix in mixes) {
                    val tracks = getPlaylistInfoAndTracks(
                        rawPlaylistInput = mix.id,
                        accessToken = gqlToken,
                        maxTracks = 20
                    ).getOrNull()?.second.orEmpty()
                    tracks.shuffled().take(8).forEach(::addUnique)
                }
            }
        }

        // 2. Gather artist names & IDs from followed artists + recent liked songs + local seed artists
        val artistNamesToQuery = mutableListOf<String>()
        val artistIds = mutableListOf<String>()
        if (!accessToken.isNullOrBlank()) {
            runCatching {
                val followed = getFollowedArtists(accessToken, maxArtists = 60)
                followed.shuffled().take(6).forEach { a ->
                    if (a.id.isNotBlank()) artistIds.add(a.id)
                    if (a.name.isNotBlank()) artistNamesToQuery.add(a.name)
                }
            }
            if (artistNamesToQuery.size < 4) {
                runCatching {
                    val liked = getLikedTracksPage(accessToken, limit = 15, offset = 0)?.items.orEmpty()
                    liked.mapNotNull { it.track }.shuffled().take(6).forEach { t ->
                        t.artists.firstOrNull()?.let { a ->
                            if (!a.id.isNullOrBlank()) artistIds.add(a.id)
                            if (a.name.isNotBlank()) artistNamesToQuery.add(a.name)
                        }
                    }
                }
            }
        }
        seedArtists.filter { it.isNotBlank() }.forEach { name ->
            if (artistNamesToQuery.none { it.equals(name, ignoreCase = true) }) {
                artistNamesToQuery.add(name)
            }
        }

        // 3. Use Pathfinder GraphQL `searchDesktop` for artist & seed track recommendations (never 429s)
        if (!gqlToken.isNullOrBlank()) {
            val queries = (artistNamesToQuery.take(6) + seedTracks.take(4)).distinct()
            for (q in queries) {
                if (results.size >= limit) break
                val found = searchTracksViaGraphQL(gqlToken, q, limit = 8)
                found.shuffled().take(4).forEach(::addUnique)
            }
        }

        // 4. Supplement with Web API /v1/artists/{id}/top-tracks if Client Credentials token is available
        val bearerForWebApi = ccToken ?: accessToken
        if (results.size < limit && !bearerForWebApi.isNullOrBlank()) {
            val market = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
            for (artistId in artistIds.distinct().take(4)) {
                if (results.size >= limit) break
                runCatching {
                    val resp = client.get("$SPOTIFY_API_BASE_URL/artists/$artistId/top-tracks") {
                        header("Authorization", "Bearer $bearerForWebApi")
                        header("User-Agent", WEB_USER_AGENT)
                        parameter("market", market)
                    }
                    if (resp.status.isSuccess()) {
                        val root = jsonParser.parseToJsonElement(resp.bodyAsText()).obj
                        val tracksArr = root?.get("tracks").arr ?: JsonArray(emptyList())
                        tracksArr.shuffled().take(4).forEach { el ->
                            el.obj?.let(::parseGraphQLTrackObject)?.let(::addUnique)
                        }
                    }
                }
            }
        }

        return results.take(limit)
    }

    /**
     * Fetches all newly released songs, singles, EPs, and albums from Spotify:
     * 1. Paginates Spotify's official What's New feed (`queryWhatsNewFeed` - `HASH_QUERY_WHATS_NEW_FEED`)
     * 2. Queries Pathfinder `queryArtistOverview` (`HASH_QUERY_ARTIST_OVERVIEW`) across ALL of the user's
     *    followed Spotify artists and local bookmarked artists (`discography.latest`, `singles`, `albums`, `compilations`)
     * 3. Extracts new releases & Release Radar / New Music Friday tracks from Spotify's `home` GraphQL feed (`HASH_HOME`)
     * 4. Enriches top release items with their actual track names via Pathfinder `getAlbum` (`HASH_GET_ALBUM`).
     */
    suspend fun getFollowedArtistsLatestReleases(
        accessToken: String?,
        extraArtists: List<Pair<String?, String>> = emptyList(),
        maxReleases: Int = 200,
        onPartialResults: ((List<app.vitune.providers.spotify.models.SpotifyReleaseItem>) -> Unit)? = null
    ): List<app.vitune.providers.spotify.models.SpotifyReleaseItem> = kotlinx.coroutines.coroutineScope {
        val gqlToken = accessToken?.takeIf { it.isNotBlank() } ?: getAnonymousAccessToken()
        val ccToken = getClientCredentialsToken()
        val bearer = ccToken ?: accessToken?.takeIf { it.isNotBlank() } ?: gqlToken
        val market = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"

        val collectedReleases = java.util.Collections.synchronizedList(
            mutableListOf<app.vitune.providers.spotify.models.SpotifyReleaseItem>()
        )

        fun buildCurrentSortedSnapshot(followedNamesSet: Set<String> = emptySet()): List<app.vitune.providers.spotify.models.SpotifyReleaseItem> {
            val snapshot = synchronized(collectedReleases) { collectedReleases.toList() }
            val byIdOrKey = LinkedHashMap<String, app.vitune.providers.spotify.models.SpotifyReleaseItem>()
            for (item in snapshot) {
                if (item.name.isBlank()) continue
                val cleanId = item.id.trim()
                val primaryKey = if (cleanId.isNotBlank()) {
                    "id:${cleanId.lowercase()}"
                } else {
                    val normType = if (item.albumType == "album" || item.totalTracks > 3) "album" else "single"
                    "name:${item.name.lowercase().trim()}|${item.artists.firstOrNull()?.lowercase()?.trim().orEmpty()}|$normType"
                }
                val existing = byIdOrKey[primaryKey]
                if (existing == null) {
                    byIdOrKey[primaryKey] = item
                } else {
                    val mergedArtists = (existing.artists + item.artists)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }
                    val mergedArtistIds = (existing.artistIds + item.artistIds)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val mergedPreviews = (existing.trackPreviewNames + item.trackPreviewNames)
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }
                        .take(5)
                    byIdOrKey[primaryKey] = existing.copy(
                        releaseDate = if (normalizeReleaseDateForSort(item.releaseDate) > normalizeReleaseDateForSort(existing.releaseDate)) {
                            item.releaseDate
                        } else {
                            existing.releaseDate.ifBlank { item.releaseDate }
                        },
                        totalTracks = maxOf(existing.totalTracks, item.totalTracks),
                        coverUrl = existing.coverUrl ?: item.coverUrl,
                        artists = mergedArtists,
                        artistIds = mergedArtistIds,
                        explicit = existing.explicit || item.explicit,
                        trackPreviewNames = mergedPreviews.ifEmpty { existing.trackPreviewNames },
                        firstTrackName = existing.firstTrackName ?: item.firstTrackName
                    )
                }
            }
            return byIdOrKey.values
                .distinctBy {
                    val normType = if (it.albumType == "album" || it.totalTracks > 3) "album" else "single"
                    "${it.name.lowercase().trim()}|${it.artists.firstOrNull()?.lowercase()?.trim().orEmpty()}|$normType"
                }
                .distinctBy { "release_${it.id}_${it.name}_${it.albumType}" }
                .sortedWith(
                    compareByDescending<app.vitune.providers.spotify.models.SpotifyReleaseItem> { item ->
                        normalizeReleaseDateForSort(item.releaseDate)
                    }.thenByDescending { item ->
                        if (followedNamesSet.isEmpty()) true
                        else item.artists.any { a -> followedNamesSet.contains(a.lowercase().trim()) }
                    }
                )
                .take(maxReleases)
        }

        // 1. Paginate Spotify's official What's New feed (`queryWhatsNewFeed`) — dedicated to new releases from followed artists!
        val whatsNewJob = async {
            if (gqlToken.isNullOrBlank()) return@async
            val contentFilterPasses = listOf(
                buildJsonArray { add(JsonPrimitive("ALBUM")) },
                buildJsonArray { }
            )
            for (contentTypesFilter in contentFilterPasses) {
                var offset = 0
                val pageLimit = 50
                var addedInPass = 0
                while (offset < 200) {
                    val feedVars = buildJsonObject {
                        put("offset", offset)
                        put("limit", pageLimit)
                        put("onlyUnPlayedItems", false)
                        put("includedContentTypes", contentTypesFilter)
                    }
                    val feedRoot = executeGraphQL(
                        accessToken = gqlToken,
                        operationName = "queryWhatsNewFeed",
                        variables = feedVars,
                        seedHash = HASH_QUERY_WHATS_NEW_FEED
                    ) ?: break

                    val feedContainer = feedRoot["data"].obj?.get("whatsNewFeedItems").obj
                        ?: feedRoot["data"].obj?.get("whatsNewFeed").obj
                        ?: feedRoot["data"].obj?.get("whatsNewFeedItems")?.obj?.get("content").obj
                        ?: break
                    val items = feedContainer["items"].arr ?: break
                    if (items.isEmpty()) break

                    for (itemEl in items) {
                        runCatching {
                            val itemObj = itemEl.obj ?: return@runCatching
                            val contentObj = itemObj["content"].obj ?: itemObj
                            val data = contentObj["data"].obj ?: contentObj
                            val typeName = (data["__typename"].str
                                ?: contentObj["__typename"].str
                                ?: itemObj["__typename"].str).orEmpty()
                            val uri = data["uri"].str
                                ?: contentObj["uri"].str
                                ?: itemObj["uri"].str
                                ?: data["albumOfTrack"].obj?.get("uri").str.orEmpty()

                            // Skip podcast episodes / shows
                            if (typeName.contains("Episode", ignoreCase = true) ||
                                typeName.contains("Show", ignoreCase = true) ||
                                uri.contains(":episode:") ||
                                uri.contains(":show:")
                            ) {
                                return@runCatching
                            }

                            val albumId = uri.substringAfterLast(":").takeIf { it.isNotBlank() && !it.startsWith("spotify") }
                                ?: data["id"].str?.takeIf { it.isNotBlank() }
                                ?: return@runCatching
                            val name = (data["name"].str ?: contentObj["name"].str ?: itemObj["title"].str)
                                ?.replace('\u00A0', ' ')?.trim()
                                ?.takeIf { it.isNotBlank() } ?: return@runCatching
                            val albumType = (data["type"].str
                                ?: data["albumType"].str
                                ?: data["album_type"].str
                                ?: contentObj["type"].str
                                ?: "single").lowercase()
                            val coverUrl = (data["coverArt"].obj ?: contentObj["coverArt"].obj ?: itemObj["image"].obj)
                                ?.get("sources").arr
                                ?.maxByOrNull { it.obj?.get("width").int ?: 0 }
                                .obj?.get("url").str
                                ?: (data["coverArt"].obj ?: contentObj["coverArt"].obj)
                                    ?.get("sources").arr?.firstOrNull().obj?.get("url").str

                            val artistsItems = data["artists"].obj?.get("items").arr
                                ?: contentObj["artists"].obj?.get("items").arr
                                ?: data["artists"].arr
                            val artistNames = artistsItems?.mapNotNull { a ->
                                val aObj = a.obj ?: return@mapNotNull null
                                (aObj["profile"].obj?.get("name").str ?: aObj["name"].str)
                                    ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
                            }.orEmpty()
                            val artistIds = artistsItems?.mapNotNull { a ->
                                val aObj = a.obj ?: return@mapNotNull null
                                (aObj["id"].str ?: aObj["uri"].str?.substringAfterLast(":"))?.takeIf { it.isNotBlank() }
                            }.orEmpty()

                            val releaseDate = parseDateElement(
                                dateEl = data["date"] ?: contentObj["date"] ?: itemObj["releaseDate"] ?: contentObj["releaseDate"] ?: itemObj["addedAt"],
                                fallbackStr = data["release_date"].str ?: itemObj["releaseDate"].str
                            )
                            val totalTracks = data["tracks"].obj?.get("totalCount").int
                                ?: data["tracksV2"].obj?.get("totalCount").int
                                ?: data["totalTracks"].int
                                ?: if (albumType == "album") 8 else 1
                            val explicit = data["contentRating"].obj?.get("label").str?.equals("EXPLICIT", ignoreCase = true) == true

                            collectedReleases.add(
                                app.vitune.providers.spotify.models.SpotifyReleaseItem(
                                    id = albumId,
                                    name = name,
                                    albumType = albumType,
                                    releaseDate = releaseDate,
                                    totalTracks = totalTracks,
                                    coverUrl = coverUrl,
                                    artists = artistNames,
                                    artistIds = artistIds,
                                    explicit = explicit,
                                    trackPreviewNames = listOf(name),
                                    firstTrackName = name
                                )
                            )
                            addedInPass++
                        }
                    }

                    val pagingInfo = feedContainer["pagingInfo"].obj
                    val nextOffset = pagingInfo?.get("nextOffset").int
                    val hasNextPage = pagingInfo?.get("hasNextPage").bool
                    if (nextOffset != null && nextOffset > offset) {
                        offset = nextOffset
                    } else if (hasNextPage == true && items.isNotEmpty()) {
                        offset += items.size
                    } else {
                        break
                    }
                }
                if (addedInPass > 0) break
            }
            if (collectedReleases.isNotEmpty()) {
                onPartialResults?.invoke(buildCurrentSortedSnapshot())
            }
        }

        // 2. Gather ALL followed artists from Spotify + local bookmarked artists
        val followedArtists = mutableListOf<app.vitune.providers.spotify.models.SpotifyArtistFull>()
        if (!accessToken.isNullOrBlank()) {
            runCatching {
                val allSpotifyFollowed = getFollowedArtists(accessToken, maxArtists = 500)
                for (a in allSpotifyFollowed) {
                    if (followedArtists.none { (it.id.isNotBlank() && it.id == a.id) || it.name.equals(a.name, ignoreCase = true) }) {
                        followedArtists.add(a)
                    }
                }
            }
        }

        for ((idHint, name) in extraArtists) {
            val cleanName = name.trim()
            if (cleanName.isBlank()) continue
            val cleanSpotifyId = idHint?.let(::extractArtistId).orEmpty()
            val existingIdx = followedArtists.indexOfFirst {
                it.name.equals(cleanName, ignoreCase = true) ||
                    (cleanSpotifyId.isNotBlank() && it.id == cleanSpotifyId)
            }
            if (existingIdx == -1) {
                followedArtists.add(
                    app.vitune.providers.spotify.models.SpotifyArtistFull(
                        id = cleanSpotifyId,
                        name = cleanName
                    )
                )
            } else if (followedArtists[existingIdx].id.isBlank() && cleanSpotifyId.isNotBlank()) {
                followedArtists[existingIdx] = followedArtists[existingIdx].copy(id = cleanSpotifyId)
            }
        }

        if (followedArtists.size < 6 && !accessToken.isNullOrBlank()) {
            runCatching {
                val liked = getLikedTracksPage(accessToken, limit = 25, offset = 0)?.items.orEmpty()
                liked.mapNotNull { it.track }.flatMap { it.artists }.forEach { a ->
                    val name = a.name.trim()
                    if (name.isNotBlank() && followedArtists.none { it.name.equals(name, ignoreCase = true) }) {
                        followedArtists.add(
                            app.vitune.providers.spotify.models.SpotifyArtistFull(
                                id = a.id.orEmpty(),
                                name = name
                            )
                        )
                    }
                }
            }
        }

        val followedNamesSet = followedArtists.map { it.name.lowercase().trim() }.toSet()

        // 3. Query Pathfinder `queryArtistOverview` in parallel batches across ALL followed artists with Spotify IDs
        val artistOverviewJob = async {
            if (gqlToken.isNullOrBlank()) return@async
            val artistsWithId = followedArtists
                .filter { Regex("""^[A-Za-z0-9]{22}$""").matches(it.id) }
                .take(160)
            if (artistsWithId.isEmpty()) return@async

            val overviewSem = Semaphore(10)
            for (batch in artistsWithId.chunked(24)) {
                batch.map { artist ->
                    async {
                        overviewSem.withPermit {
                            val artistReleases = mutableListOf<app.vitune.providers.spotify.models.SpotifyReleaseItem>()
                            runCatching {
                                val vars = buildJsonObject {
                                    put("uri", "spotify:artist:${artist.id}")
                                    put("locale", "")
                                }
                                val pf = executeGraphQL(
                                    accessToken = gqlToken,
                                    operationName = "queryArtistOverview",
                                    variables = vars,
                                    seedHash = HASH_QUERY_ARTIST_OVERVIEW
                                )
                                val artistUnion = pf?.get("data").obj?.get("artistUnion").obj
                                    ?: return@runCatching
                                val resolvedArtistName = (artistUnion["profile"].obj?.get("name").str
                                    ?: artistUnion["name"].str
                                    ?: artist.name).replace('\u00A0', ' ').trim()
                                val discog = artistUnion["discography"].obj ?: return@runCatching

                                discog["latest"].obj?.let { latestObj ->
                                    parseOverviewReleaseNode(latestObj, resolvedArtistName, artist.id, "single")
                                        ?.let(artistReleases::add)
                                }

                                listOf(
                                    "singles" to "single",
                                    "albums" to "album",
                                    "popularReleasesAlbums" to "album",
                                    "compilations" to "compilation"
                                ).forEach { (groupKey, defaultType) ->
                                    val groupItems = discog[groupKey].obj?.get("items").arr
                                    groupItems?.take(6)?.forEach { groupEl ->
                                        val groupObj = groupEl.obj ?: return@forEach
                                        val nestedReleases = groupObj["releases"].obj?.get("items").arr
                                        if (nestedReleases != null) {
                                            nestedReleases.take(3).forEach { relEl ->
                                                relEl.obj?.let { relObj ->
                                                    parseOverviewReleaseNode(relObj, resolvedArtistName, artist.id, defaultType)
                                                        ?.let(artistReleases::add)
                                                }
                                            }
                                        } else {
                                            parseOverviewReleaseNode(groupObj, resolvedArtistName, artist.id, defaultType)
                                                ?.let(artistReleases::add)
                                        }
                                    }
                                }
                            }

                            // Fallback to Web API /v1/artists/{id}/albums if Pathfinder returned nothing for this artist
                            if (artistReleases.isEmpty() && !bearer.isNullOrBlank()) {
                                runCatching {
                                    val resp = client.get("$SPOTIFY_API_BASE_URL/artists/${artist.id}/albums") {
                                        header("Authorization", "Bearer $bearer")
                                        header("User-Agent", WEB_USER_AGENT)
                                        parameter("include_groups", "album,single")
                                        parameter("market", market)
                                        parameter("limit", 8)
                                    }
                                    if (resp.status.isSuccess()) {
                                        val root = jsonParser.parseToJsonElement(resp.bodyAsText()).obj
                                        val items = root?.get("items").arr
                                        items?.forEach { el ->
                                            val obj = el.obj ?: return@forEach
                                            val albumId = obj["id"].str?.takeIf { it.isNotBlank() } ?: return@forEach
                                            val name = obj["name"].str?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                                            val albumType = (obj["album_type"].str ?: obj["album_group"].str ?: "single").lowercase()
                                            val releaseDate = obj["release_date"].str.orEmpty()
                                            val totalTracks = obj["total_tracks"].int ?: 1
                                            val coverUrl = obj["images"].arr?.firstOrNull().obj?.get("url").str
                                            val artistsArr = obj["artists"].arr
                                            val artistNames = artistsArr?.mapNotNull { a ->
                                                a.obj?.get("name").str?.trim()?.takeIf { it.isNotBlank() }
                                            }?.takeIf { it.isNotEmpty() } ?: listOf(artist.name)
                                            val artistIdsList = artistsArr?.mapNotNull { a ->
                                                a.obj?.get("id").str?.trim()?.takeIf { it.isNotBlank() }
                                            }?.takeIf { it.isNotEmpty() } ?: listOf(artist.id)

                                            artistReleases.add(
                                                app.vitune.providers.spotify.models.SpotifyReleaseItem(
                                                    id = albumId,
                                                    name = name,
                                                    albumType = albumType,
                                                    releaseDate = releaseDate,
                                                    totalTracks = totalTracks,
                                                    coverUrl = coverUrl,
                                                    artists = artistNames,
                                                    artistIds = artistIdsList,
                                                    trackPreviewNames = listOf(name),
                                                    firstTrackName = name
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            if (artistReleases.isNotEmpty()) {
                                val topForArtist = artistReleases
                                    .distinctBy { it.id.ifBlank { it.name.lowercase().trim() } }
                                    .sortedByDescending { normalizeReleaseDateForSort(it.releaseDate) }
                                    .take(8)
                                collectedReleases.addAll(topForArtist)
                            }
                        }
                    }
                }.awaitAll()

                if (collectedReleases.isNotEmpty()) {
                    onPartialResults?.invoke(buildCurrentSortedSnapshot(followedNamesSet))
                }
            }
        }

        // 4. Check Spotify `home` GraphQL feed for new release Album sections
        val homeJob = async {
            if (gqlToken.isNullOrBlank()) return@async
            runCatching {
                val timeZone = TimeZone.getDefault().id
                val homeVars = buildJsonObject {
                    put("timeZone", timeZone)
                    put("sp_t", spTDeviceId ?: "")
                    put("country", market)
                    put("facet", JsonNull)
                    put("sectionItemsLimit", 25)
                }
                val homeRoot = executeGraphQL(
                    accessToken = gqlToken,
                    operationName = "home",
                    variables = homeVars,
                    seedHash = HASH_HOME
                )
                val sections = homeRoot?.get("data").obj
                    ?.get("home").obj
                    ?.get("sectionContainer").obj
                    ?.get("sections").obj
                    ?.get("items").arr

                sections?.forEach { secEl ->
                    runCatching {
                        val secObj = secEl.obj ?: return@forEach
                        val secTitle = (
                            secObj["data"].obj?.get("title").obj?.get("transformedLabel").str
                                ?: secObj["data"].obj?.get("title").obj?.get("text").str
                            ).orEmpty()
                        val secItems = secObj["sectionItems"].obj?.get("items").arr ?: return@forEach

                        secItems.forEach { entryEl ->
                            runCatching {
                                val entryObj = entryEl.obj ?: return@forEach
                                val contentWrapper = entryObj["content"].obj ?: return@forEach
                                val wrapperType = contentWrapper["__typename"].str.orEmpty()
                                val data = contentWrapper["data"].obj ?: return@forEach
                                val dataType = data["__typename"].str.orEmpty()
                                val uri = entryObj["uri"].str ?: data["uri"].str.orEmpty()

                                if (wrapperType.contains("Album", ignoreCase = true) ||
                                    dataType.equals("Album", ignoreCase = true) ||
                                    uri.contains(":album:")
                                ) {
                                    val albumId = uri.substringAfterLast(":").takeIf { it.isNotBlank() } ?: return@forEach
                                    val name = data["name"].str?.replace('\u00A0', ' ')?.trim()
                                        ?.takeIf { it.isNotBlank() } ?: return@forEach
                                    val coverUrl = data["coverArt"].obj
                                        ?.get("sources").arr?.firstOrNull().obj
                                        ?.get("url").str
                                    val artistsItems = data["artists"].obj?.get("items").arr
                                    val artistNames = artistsItems?.mapNotNull { a ->
                                        val aObj = a.obj ?: return@mapNotNull null
                                        (aObj["profile"].obj?.get("name").str ?: aObj["name"].str)
                                            ?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
                                    }.orEmpty()
                                    val artistIds = artistsItems?.mapNotNull { a ->
                                        a.obj?.get("uri").str?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                                    }.orEmpty()
                                    val releaseDate = parseDateElement(data["date"])

                                    if (secTitle.contains("release", ignoreCase = true) ||
                                        secTitle.contains("new", ignoreCase = true) ||
                                        releaseDate.startsWith("2025") ||
                                        releaseDate.startsWith("2026")
                                    ) {
                                        collectedReleases.add(
                                            app.vitune.providers.spotify.models.SpotifyReleaseItem(
                                                id = albumId,
                                                name = name,
                                                albumType = (data["type"].str ?: "single").lowercase(),
                                                releaseDate = releaseDate,
                                                totalTracks = 1,
                                                coverUrl = coverUrl,
                                                artists = artistNames,
                                                artistIds = artistIds,
                                                trackPreviewNames = listOf(name),
                                                firstTrackName = name
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        whatsNewJob.await()
        artistOverviewJob.await()
        homeJob.await()

        // 5. Query followed artists that lacked a 22-char Spotify ID via Pathfinder `searchDesktop`
        val artistsWithoutId = followedArtists.filter { !Regex("""^[A-Za-z0-9]{22}$""").matches(it.id) && it.name.isNotBlank() }
        if (!gqlToken.isNullOrBlank() && artistsWithoutId.isNotEmpty()) {
            val gqlSemaphore = Semaphore(6)
            artistsWithoutId.take(25).map { artist ->
                async {
                    gqlSemaphore.withPermit {
                        val found = searchReleasesViaGraphQL(
                            gqlToken = gqlToken,
                            query = artist.name,
                            filterArtistName = artist.name,
                            limit = 8
                        )
                        val newestForArtist = found
                            .sortedByDescending { normalizeReleaseDateForSort(it.releaseDate) }
                            .take(4)
                        collectedReleases.addAll(newestForArtist)
                    }
                }
            }.awaitAll()
        }

        // 6. Only if no releases were found at all, fallback to `tag:new` in `searchDesktop`
        if (!gqlToken.isNullOrBlank() && collectedReleases.isEmpty()) {
            val newReleaseQueries = listOf("tag:new", "new releases")
            for (q in newReleaseQueries) {
                val tagNewReleases = searchReleasesViaGraphQL(
                    gqlToken = gqlToken,
                    query = q,
                    filterArtistName = null,
                    limit = 20
                )
                collectedReleases.addAll(tagNewReleases)
            }
        }

        val sortedReleases = buildCurrentSortedSnapshot(followedNamesSet).toMutableList()

        // 7. Enrich top releases with their actual track names via Pathfinder `getAlbum` (`HASH_GET_ALBUM`)
        if (!gqlToken.isNullOrBlank() && sortedReleases.isNotEmpty()) {
            val albumSem = Semaphore(8)
            val enrichedPairs = sortedReleases.indices.take(24).map { idx ->
                async {
                    albumSem.withPermit {
                        val rel = sortedReleases[idx]
                        if (!Regex("""^[A-Za-z0-9]{22}$""").matches(rel.id)) return@withPermit null
                        runCatching {
                            val vars = buildJsonObject {
                                put("uri", "spotify:album:${rel.id}")
                                put("locale", "")
                                put("offset", 0)
                                put("limit", 10)
                            }
                            val pf = executeGraphQL(
                                accessToken = gqlToken,
                                operationName = "getAlbum",
                                variables = vars,
                                seedHash = HASH_GET_ALBUM
                            )
                            val albumUnion = pf?.get("data").obj?.get("albumUnion").obj
                                ?: return@runCatching null
                            val tracksV2 = albumUnion["tracksV2"].obj ?: albumUnion["tracks"].obj
                            val totalCount = tracksV2?.get("totalCount").int ?: rel.totalTracks
                            val trackItems = tracksV2?.get("items").arr
                            val trackNames = trackItems?.mapNotNull { tEl ->
                                val tObj = tEl.obj?.get("track").obj ?: tEl.obj ?: return@mapNotNull null
                                tObj["name"].str?.replace('\u00A0', ' ')?.trim()?.takeIf { it.isNotBlank() }
                            }.orEmpty()
                            val anyExplicit = trackItems?.any { tEl ->
                                val tObj = tEl.obj?.get("track").obj ?: tEl.obj
                                tObj?.get("contentRating").obj?.get("label").str
                                    ?.equals("EXPLICIT", ignoreCase = true) == true
                            } ?: rel.explicit
                            val parsedDate = parseDateElement(albumUnion["date"])
                            if (trackNames.isNotEmpty() || parsedDate.isNotBlank()) {
                                idx to rel.copy(
                                    releaseDate = rel.releaseDate.ifBlank { parsedDate },
                                    totalTracks = maxOf(totalCount, trackNames.size),
                                    trackPreviewNames = if (trackNames.isNotEmpty()) trackNames.take(5) else rel.trackPreviewNames,
                                    firstTrackName = trackNames.firstOrNull() ?: rel.firstTrackName,
                                    explicit = rel.explicit || anyExplicit
                                )
                            } else null
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
            for ((idx, updated) in enrichedPairs) {
                sortedReleases[idx] = updated
            }
        }

        sortedReleases
    }

    private fun normalizeReleaseDateForSort(dateStr: String): String {
        val trimmed = dateStr.trim()
        return when {
            Regex("""^\d{4}-\d{2}-\d{2}$""").matches(trimmed) -> trimmed
            Regex("""^\d{4}-\d{2}$""").matches(trimmed) -> "$trimmed-01"
            Regex("""^\d{4}$""").matches(trimmed) -> "$trimmed-01-01"
            else -> "0000-00-00"
        }
    }
}

