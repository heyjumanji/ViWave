package app.vitune.providers.lastfm

import app.vitune.providers.lastfm.models.LastFmMobileSessionResponse
import app.vitune.providers.lastfm.models.LastFmScrobbleResponse
import app.vitune.providers.lastfm.models.LastFmSessionResponse
import app.vitune.providers.lastfm.models.LastFmStatusResponse
import app.vitune.providers.lastfm.models.LastFmTokenResponse
import app.vitune.providers.utils.runCatchingCancellable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object LastFmApi {
    const val DEFAULT_API_KEY = "a6615d99946d7633ee214d2b481d87b7"
    const val DEFAULT_API_SECRET = "a068820d33bad50d5c79528d0767d6a6"
    private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpRequestRetry) {
                exponentialDelay()
                maxRetries = 2
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 5000L
                requestTimeoutMillis = 10000L
            }
        }
    }

    fun generateSignature(params: Map<String, String>, secret: String): String {
        val sortedKeys = params.keys.filter { it != "format" && it != "callback" }.sorted()
        val sb = StringBuilder()
        for (key in sortedKeys) {
            sb.append(key).append(params[key])
        }
        sb.append(secret)
        val digest = MessageDigest.getInstance("MD5").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun getToken(
        apiKey: String = DEFAULT_API_KEY
    ) = runCatchingCancellable {
        val params = mapOf(
            "method" to "auth.getToken",
            "api_key" to apiKey,
            "format" to "json"
        )
        client.submitForm(
            url = BASE_URL,
            formParameters = Parameters.build {
                params.forEach { (k, v) -> append(k, v) }
            }
        ).body<LastFmTokenResponse>()
    }

    suspend fun getSession(
        token: String,
        apiKey: String = DEFAULT_API_KEY,
        apiSecret: String = DEFAULT_API_SECRET
    ) = runCatchingCancellable {
        val params = mutableMapOf(
            "method" to "auth.getSession",
            "token" to token,
            "api_key" to apiKey
        )
        val sig = generateSignature(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        client.submitForm(
            url = BASE_URL,
            formParameters = Parameters.build {
                params.forEach { (k, v) -> append(k, v) }
            }
        ).body<LastFmSessionResponse>()
    }

    suspend fun getMobileSession(
        username: String,
        password: String,
        apiKey: String = DEFAULT_API_KEY,
        apiSecret: String = DEFAULT_API_SECRET
    ) = runCatchingCancellable {
        val params = mutableMapOf(
            "method" to "auth.getMobileSession",
            "username" to username,
            "password" to password,
            "api_key" to apiKey
        )
        val sig = generateSignature(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        client.submitForm(
            url = BASE_URL,
            formParameters = Parameters.build {
                params.forEach { (k, v) -> append(k, v) }
            }
        ).body<LastFmMobileSessionResponse>()
    }

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        sessionKey: String,
        album: String? = null,
        duration: Int? = null,
        apiKey: String = DEFAULT_API_KEY,
        apiSecret: String = DEFAULT_API_SECRET
    ) = runCatchingCancellable {
        val params = mutableMapOf(
            "method" to "track.updateNowPlaying",
            "artist" to artist,
            "track" to track,
            "sk" to sessionKey,
            "api_key" to apiKey
        )
        if (!album.isNullOrBlank()) params["album"] = album
        if (duration != null && duration > 0) params["duration"] = duration.toString()

        val sig = generateSignature(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        client.submitForm(
            url = BASE_URL,
            formParameters = Parameters.build {
                params.forEach { (k, v) -> append(k, v) }
            }
        ).body<LastFmStatusResponse>()
    }

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        sessionKey: String,
        album: String? = null,
        duration: Int? = null,
        apiKey: String = DEFAULT_API_KEY,
        apiSecret: String = DEFAULT_API_SECRET
    ) = runCatchingCancellable {
        val params = mutableMapOf(
            "method" to "track.scrobble",
            "artist[0]" to artist,
            "track[0]" to track,
            "timestamp[0]" to timestamp.toString(),
            "sk" to sessionKey,
            "api_key" to apiKey
        )
        if (!album.isNullOrBlank()) params["album[0]"] = album
        if (duration != null && duration > 0) params["duration[0]"] = duration.toString()

        val sig = generateSignature(params, apiSecret)
        params["api_sig"] = sig
        params["format"] = "json"

        client.submitForm(
            url = BASE_URL,
            formParameters = Parameters.build {
                params.forEach { (k, v) -> append(k, v) }
            }
        ).body<LastFmScrobbleResponse>()
    }

    data class LastFmTrackCandidate(
        val title: String,
        val artist: String
    )

    private suspend fun fetchJsonObject(params: Map<String, String>): kotlinx.serialization.json.JsonObject? =
        runCatchingCancellable {
            client.submitForm(
                url = BASE_URL,
                formParameters = Parameters.build {
                    params.forEach { (k, v) -> append(k, v) }
                    append("format", "json")
                }
            ).body<kotlinx.serialization.json.JsonObject>()
        }?.getOrNull()

    private fun extractTracksFromArray(
        array: kotlinx.serialization.json.JsonArray?
    ): List<LastFmTrackCandidate> {
        if (array == null) return emptyList()
        val list = mutableListOf<LastFmTrackCandidate>()
        for (el in array) {
            val obj = (el as? kotlinx.serialization.json.JsonObject) ?: continue
            val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
            val artistEl = obj["artist"]
            val artistName = when (artistEl) {
                is kotlinx.serialization.json.JsonObject ->
                    ((artistEl["name"] ?: artistEl["#text"]) as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
                is kotlinx.serialization.json.JsonPrimitive -> artistEl.content.trim()
                else -> ""
            }
            if (name.isNotBlank() && artistName.isNotBlank()) {
                list.add(LastFmTrackCandidate(title = name, artist = artistName))
            }
        }
        return list
    }

    suspend fun getSimilarTracks(
        artist: String,
        track: String,
        limit: Int = 10,
        apiKey: String = DEFAULT_API_KEY
    ): List<LastFmTrackCandidate> {
        val root = fetchJsonObject(
            mapOf(
                "method" to "track.getSimilar",
                "artist" to artist,
                "track" to track,
                "autocorrect" to "1",
                "limit" to limit.toString(),
                "api_key" to apiKey
            )
        ) ?: return emptyList()
        val similarTracksObj = root["similartracks"] as? kotlinx.serialization.json.JsonObject
        val trackArr = similarTracksObj?.get("track") as? kotlinx.serialization.json.JsonArray
        return extractTracksFromArray(trackArr)
    }

    suspend fun getUserRecentTracks(
        username: String,
        limit: Int = 12,
        apiKey: String = DEFAULT_API_KEY
    ): List<LastFmTrackCandidate> {
        val root = fetchJsonObject(
            mapOf(
                "method" to "user.getRecentTracks",
                "user" to username,
                "limit" to limit.toString(),
                "api_key" to apiKey
            )
        ) ?: return emptyList()
        val recentObj = root["recenttracks"] as? kotlinx.serialization.json.JsonObject
        val trackArr = recentObj?.get("track") as? kotlinx.serialization.json.JsonArray
        return extractTracksFromArray(trackArr)
    }

    suspend fun getUserTopTracks(
        username: String,
        period: String = "7day",
        limit: Int = 15,
        apiKey: String = DEFAULT_API_KEY
    ): List<LastFmTrackCandidate> {
        val root = fetchJsonObject(
            mapOf(
                "method" to "user.getTopTracks",
                "user" to username,
                "period" to period,
                "limit" to limit.toString(),
                "api_key" to apiKey
            )
        ) ?: return emptyList()
        val topObj = root["toptracks"] as? kotlinx.serialization.json.JsonObject
        val trackArr = topObj?.get("track") as? kotlinx.serialization.json.JsonArray
        return extractTracksFromArray(trackArr)
    }

    suspend fun getChartTopTracks(
        limit: Int = 20,
        apiKey: String = DEFAULT_API_KEY
    ): List<LastFmTrackCandidate> {
        val root = fetchJsonObject(
            mapOf(
                "method" to "chart.getTopTracks",
                "limit" to limit.toString(),
                "api_key" to apiKey
            )
        ) ?: return emptyList()
        val tracksObj = root["tracks"] as? kotlinx.serialization.json.JsonObject
        val trackArr = tracksObj?.get("track") as? kotlinx.serialization.json.JsonArray
        return extractTracksFromArray(trackArr)
    }

    suspend fun fetchDynamicRecommendations(
        username: String,
        seedTitle: String? = null,
        seedArtist: String? = null,
        excludeKeys: Set<String> = emptySet(),
        apiKey: String = DEFAULT_API_KEY
    ): List<LastFmTrackCandidate> {
        val recent = getUserRecentTracks(username = username, limit = 25, apiKey = apiKey)
        val weeklyTop = getUserTopTracks(username = username, period = "7day", limit = 25, apiKey = apiKey)
        val monthlyTop = getUserTopTracks(username = username, period = "1month", limit = 25, apiKey = apiKey)
        val overallTop = if (weeklyTop.size + monthlyTop.size < 12) {
            getUserTopTracks(username = username, period = "overall", limit = 25, apiKey = apiKey)
        } else emptyList()

        val allUserTracks = (recent + weeklyTop + monthlyTop + overallTop)
            .distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }

        val seeds = buildList {
            if (!seedTitle.isNullOrBlank() && !seedArtist.isNullOrBlank()) {
                add(LastFmTrackCandidate(title = seedTitle, artist = seedArtist))
            }
            addAll(allUserTracks.shuffled().take(4))
        }
            .distinctBy { "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}" }
            .shuffled()
            .take(3)

        val similarPool = mutableListOf<LastFmTrackCandidate>()
        for (seed in seeds) {
            val sim = getSimilarTracks(
                artist = seed.artist,
                track = seed.title,
                limit = 12,
                apiKey = apiKey
            )
            similarPool.addAll(sim.shuffled().take(6))
        }

        val chartFallback = if (similarPool.size + allUserTracks.size < 12) {
            getChartTopTracks(limit = 20, apiKey = apiKey).shuffled().take(10)
        } else emptyList()

        val combined = buildList {
            addAll(similarPool.shuffled())
            addAll(weeklyTop.shuffled().take(8))
            addAll(monthlyTop.shuffled().take(8))
            addAll(recent.shuffled().take(8))
            addAll(overallTop.shuffled().take(6))
            addAll(chartFallback)
        }

        val seen = HashSet<String>()
        val deduplicated = combined.filter { candidate ->
            seen.add("${candidate.title.lowercase().trim()}|${candidate.artist.lowercase().trim()}")
        }

        if (excludeKeys.isEmpty()) {
            return deduplicated.take(16)
        }

        val freshOnly = deduplicated.filterNot { candidate ->
            val key = "${candidate.title.lowercase().trim()}|${candidate.artist.lowercase().trim()}"
            key in excludeKeys
        }

        return if (freshOnly.size >= 8) {
            freshOnly.take(16)
        } else {
            (freshOnly + deduplicated.shuffled()).distinctBy {
                "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}"
            }.take(16)
        }
    }
}

