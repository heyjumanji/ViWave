package app.vitune.android.utils.jiosaavn

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class JioSaavnClient(
    private val client: HttpClient = defaultClient
) {
    var baseUrl = "https://www.jiosaavn.com"

    suspend fun search(query: String, limit: Int = 10): JioSaavnSearchOutcome {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api.php") {
                parameter("__call", "search.getResults")
                parameter("_format", "json")
                parameter("_marker", "0")
                parameter("ctx", "web6dot0")
                parameter("n", limit.coerceIn(1, 20))
                parameter("p", "1")
                parameter("q", query)
                header("User-Agent", USER_AGENT)
                header("Referer", "https://www.jiosaavn.com/")
            }

            when (response.status) {
                HttpStatusCode.TooManyRequests -> JioSaavnSearchOutcome.RateLimited
                HttpStatusCode.OK -> {
                    val text: String = response.body()
                    val parsed = json.decodeFromString<NativeSearchResponse>(text)
                    JioSaavnSearchOutcome.Success(parsed.results.mapNotNull(::normalize))
                }
                else -> JioSaavnSearchOutcome.Failure("HTTP ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "search failed: ${e.javaClass.simpleName}: ${e.message}")
            JioSaavnSearchOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun isPlayable(url: String): JioSaavnProbeOutcome {
        if (!isTrustedMediaUrl(url)) return JioSaavnProbeOutcome.Unavailable
        return try {
            val response: HttpResponse = client.get(url) {
                header("Range", "bytes=0-4095")
                header("User-Agent", USER_AGENT)
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                return JioSaavnProbeOutcome.RateLimited
            }
            if (response.status.value >= 500) {
                return JioSaavnProbeOutcome.Failure("HTTP ${response.status.value}")
            }
            if (response.status != HttpStatusCode.PartialContent && response.status != HttpStatusCode.OK) {
                return JioSaavnProbeOutcome.Unavailable
            }

            val range = response.headers["Content-Range"].orEmpty()
            if (response.status == HttpStatusCode.PartialContent && !range.startsWith("bytes 0-", ignoreCase = true)) {
                return JioSaavnProbeOutcome.Unavailable
            }

            val type = response.headers["Content-Type"].orEmpty().lowercase()
            if (!type.startsWith("audio/mp4") && !type.startsWith("application/octet-stream") && !type.startsWith("audio/aac")) {
                return JioSaavnProbeOutcome.Unavailable
            }

            JioSaavnProbeOutcome.Playable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JioSaavnProbeOutcome.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    internal fun isTrustedMediaUrl(value: String): Boolean {
        return try {
            val url = Url(value)
            url.protocol.name == "https" && url.host == AAC_CDN_HOST &&
                VARIANTS_KBPS.any { url.encodedPath.contains("_$it") }
        } catch (e: Exception) {
            false
        }
    }

    internal fun decrypt320Url(encryptedMediaUrl: String): String? = runCatching {
        if (encryptedMediaUrl.isBlank()) return null
        val payload = Base64.getDecoder().decode(encryptedMediaUrl)

        val template = decryptTemplate(payload, DES_KEY.toByteArray(Charsets.UTF_8))
            ?: return null

        if (!template.contains("_96")) return null
        template.replace("_96", "_320")
    }.getOrNull()

    private fun decryptTemplate(payload: ByteArray, key: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES"))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrNull()

    private fun normalize(raw: NativeSong): JioSaavnSong? {
        val id = raw.id?.takeIf { it.isNotBlank() } ?: return null
        val name = decodeEntities(raw.song.orEmpty()).takeIf { it.isNotBlank() } ?: return null
        val primary = raw.primaryArtists.orEmpty().split(',')
            .map { decodeEntities(it).trim() }
            .filter { it.isNotBlank() }
            .map(::JioSaavnArtist)
        if (primary.isEmpty()) return null

        val media = if (raw.has320.equals("true", ignoreCase = true) || !raw.encryptedMediaUrl.isNullOrBlank()) {
            decrypt320Url(raw.encryptedMediaUrl.orEmpty())
                ?.let { listOf(JioSaavnMediaLink("320kbps", it)) }
                .orEmpty()
        } else emptyList()

        val images = raw.image?.takeIf { it.isNotBlank() }?.let { value ->
            val high = value.replace(Regex("(?:50|150)x(?:50|150)"), "500x500")
                .replace(Regex("^http://"), "https://")
            listOf(JioSaavnImage("500x500", high))
        }.orEmpty()

        return JioSaavnSong(
            id = id,
            name = name,
            duration = raw.duration?.toIntOrNull(),
            explicitContent = raw.explicitContent == 1,
            album = raw.album?.let { JioSaavnAlbum(decodeEntities(it)) },
            artists = JioSaavnArtists(primary),
            image = images,
            downloadUrl = media,
            language = raw.language?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        )
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#039;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)

    @Serializable
    private data class NativeSearchResponse(val results: List<NativeSong> = emptyList())

    @Serializable
    private data class NativeSong(
        val id: String? = null,
        val song: String? = null,
        val album: String? = null,
        val duration: String? = null,
        val image: String? = null,
        @SerialName("primary_artists") val primaryArtists: String? = null,
        @SerialName("explicit_content") val explicitContent: Int? = null,
        @SerialName("320kbps") val has320: String? = null,
        @SerialName("encrypted_media_url") val encryptedMediaUrl: String? = null,
        val language: String? = null
    )

    companion object {
        private const val TAG = "JioSaavnClient"
        private const val DES_KEY = "38346591"
        const val AAC_CDN_HOST = "aac.saavncdn.com"
        val VARIANTS_KBPS = listOf(96, 160, 320)
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val defaultClient by lazy {
            HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 6_000
                    connectTimeoutMillis = 5_000
                }
            }
        }
    }
}

internal fun jioSaavnVariantUrl(url320: String, kbps: Int): String = url320.replace("_320", "_$kbps")
