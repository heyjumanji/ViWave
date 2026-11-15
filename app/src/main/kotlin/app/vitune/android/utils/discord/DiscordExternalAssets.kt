package app.vitune.android.utils.discord

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.Log
import app.vitune.android.R
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object DiscordExternalAssets {

    private const val TAG = "DiscordSvc"
    private const val EXTERNAL_ASSETS_API =
        "https://discord.com/api/v9/applications/%s/external-assets"
    private const val PREFS_NAME = "discord_external_assets_prefs"
    private const val KEY_UPLOADED_LOGO_URL = "uploaded_viwaves_logo_url_v1"

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()
    private val smallLogoMutex = Mutex()
    private const val CACHE_MAX_SIZE = 128

    private val FALLBACK_LOGO_URLS = listOf(
        "https://raw.githubusercontent.com/heyjumanji/Vitune-Repatched/main/app/src/main/res/drawable/app_icon.png",
        "https://raw.githubusercontent.com/heyjumanji/Vitune-Repatched/main/app/src/main/res/drawable/ic_notification.png",
        "https://raw.githubusercontent.com/heyjumanji/Vitune-Repatched/master/app/src/main/res/drawable/app_icon.png",
        "https://raw.githubusercontent.com/heyjumanji/Vitune-Repatched/master/app/src/main/res/drawable/ic_notification.png",
    )

    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 10_000L
                connectTimeoutMillis = 5_000L
                socketTimeoutMillis = 10_000L
            }
            expectSuccess = false
        }
    }

    private fun normalizeImageUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.contains("googleusercontent.com") && trimmed.contains("=")) {
            val base = trimmed.substringBeforeLast("=")
            return "$base=w544-h544-l90-rj"
        }
        return trimmed
    }

    suspend fun resolve(
        imageUrl: String,
        appId: String,
        token: String,
    ): String? {
        if (imageUrl.isBlank()) return null
        if (imageUrl.startsWith("mp:")) return imageUrl

        val normalizedUrl = normalizeImageUrl(imageUrl)

        cache[normalizedUrl]?.let {
            Log.d(TAG, "resolve: cache hit for ${normalizedUrl.take(60)} -> $it")
            return it
        }
        cache[imageUrl]?.let {
            return it
        }
        Log.d(TAG, "resolve: cache miss for ${normalizedUrl.take(60)}, calling API")

        return try {
            val response = client.post(EXTERNAL_ASSETS_API.format(appId)) {
                header("Authorization", token)
                header("User-Agent", DiscordSuperProperties.USER_AGENT)
                header("X-Super-Properties", DiscordSuperProperties.base64)
                header("Content-Type", "application/json")
                setBody(json.encodeToString(ExternalAssetRequest(listOf(normalizedUrl))))
            }

            val body = response.bodyAsText()
            val statusCode = response.status.value

            if (statusCode in 200..299 && body.isNotBlank()) {
                val parsed = json.decodeFromString<List<ExternalAssetResponse>>(body)
                val assetPath = parsed.firstOrNull()?.externalAssetPath
                if (assetPath != null) {
                    val result = "mp:$assetPath"
                    cache[normalizedUrl] = result
                    cache[imageUrl] = result
                    trimCache()
                    Log.i(TAG, "external-assets: resolved ${normalizedUrl.take(60)} -> $result")
                    return result
                } else {
                    Log.w(TAG, "external-assets: no path in response for ${imageUrl.take(60)}: ${body.take(200)}")
                }
            } else {
                Log.w(TAG, "external-assets: HTTP $statusCode for ${imageUrl.take(60)}: ${body.take(200)}")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "external-assets: failed for ${imageUrl.take(60)}", e)
            null
        }
    }

    suspend fun resolveSmallImage(
        smallImageKeyOrUrl: String,
        appId: String,
        token: String,
        context: Context? = null,
    ): String? {
        if (smallImageKeyOrUrl.isBlank()) return null
        if (smallImageKeyOrUrl != DiscordDefaults.VIWAVES_SMALL_IMAGE_KEY) {
            return resolve(smallImageKeyOrUrl, appId, token)
        }

        val cacheKey = "${DiscordDefaults.VIWAVES_SMALL_IMAGE_KEY}:$appId"
        cache[cacheKey]?.let { return it }

        return smallLogoMutex.withLock {
            cache[cacheKey]?.let { return@withLock it }

            // 1. Check persisted uploaded logo URL first if available
            val prefs = context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedLogoUrl = prefs?.getString(KEY_UPLOADED_LOGO_URL, null)
            if (!savedLogoUrl.isNullOrBlank()) {
                val resolvedSaved = resolve(savedLogoUrl, appId, token)
                if (resolvedSaved != null) {
                    cache[cacheKey] = resolvedSaved
                    return@withLock resolvedSaved
                }
            }

            // 2. Check Discord application's own icon hash via /applications/{appId}/rpc
            fetchDiscordAppIconUrl(appId)?.let { appIconUrl ->
                val resolvedIcon = resolve(appIconUrl, appId, token)
                if (resolvedIcon != null) {
                    cache[cacheKey] = resolvedIcon
                    return@withLock resolvedIcon
                }
            }

            // 3. Check Discord application's Rich Presence uploaded assets via /oauth2/applications/{appId}/assets
            fetchDiscordAppAssetUrl(appId)?.let { (assetId, assetUrl) ->
                val resolvedAsset = resolve(assetUrl, appId, token) ?: assetId
                if (resolvedAsset.isNotBlank()) {
                    cache[cacheKey] = resolvedAsset
                    return@withLock resolvedAsset
                }
            }

            // 4. Render local ViWaves circular wave badge and upload once to obtain a public HTTPS URL
            if (context != null) {
                val uploadedUrl = renderAndUploadViWavesBadge(context.applicationContext)
                if (!uploadedUrl.isNullOrBlank()) {
                    prefs?.edit()?.putString(KEY_UPLOADED_LOGO_URL, uploadedUrl)?.apply()
                    val resolvedUploaded = resolve(uploadedUrl, appId, token)
                    if (resolvedUploaded != null) {
                        cache[cacheKey] = resolvedUploaded
                        return@withLock resolvedUploaded
                    }
                }
            }

            // 5. Try fallback GitHub raw URLs
            for (url in FALLBACK_LOGO_URLS) {
                val resolvedFallback = resolve(url, appId, token)
                if (resolvedFallback != null) {
                    cache[cacheKey] = resolvedFallback
                    return@withLock resolvedFallback
                }
            }

            null
        }
    }

    private suspend fun fetchDiscordAppIconUrl(appId: String): String? = try {
        val response = client.get("https://discord.com/api/v10/applications/$appId/rpc")
        if (response.status.value in 200..299) {
            val obj = JSONObject(response.bodyAsText())
            val iconHash = obj.optString("icon", "")
            if (iconHash.isNotBlank() && iconHash != "null") {
                "https://cdn.discordapp.com/app-icons/$appId/$iconHash.png?size=256"
            } else null
        } else null
    } catch (e: Exception) {
        Log.d(TAG, "fetchDiscordAppIconUrl: failed", e)
        null
    }

    private suspend fun fetchDiscordAppAssetUrl(appId: String): Pair<String, String>? = try {
        val response = client.get("https://discord.com/api/v10/oauth2/applications/$appId/assets")
        if (response.status.value in 200..299) {
            val arr = JSONArray(response.bodyAsText())
            if (arr.length() > 0) {
                var chosenId: String? = null
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val name = item.optString("name", "").lowercase()
                    val id = item.optString("id", "")
                    if (id.isNotBlank()) {
                        if (chosenId == null || name.contains("viwaves") || name.contains("logo") || name.contains("icon")) {
                            chosenId = id
                        }
                    }
                }
                chosenId?.let { id ->
                    id to "https://cdn.discordapp.com/app-assets/$appId/$id.png?size=256"
                }
            } else null
        } else null
    } catch (e: Exception) {
        Log.d(TAG, "fetchDiscordAppAssetUrl: failed", e)
        null
    }

    private fun renderAndUploadViWavesBadge(context: Context): String? {
        return try {
            val size = 256
            val badge = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(badge)

            // Draw dark circular badge background matching the DiscordScreen preview
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E1F22")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)

            val rawWave = BitmapFactory.decodeResource(context.resources, R.drawable.ic_notification)
                ?: BitmapFactory.decodeResource(context.resources, R.drawable.app_icon)

            if (rawWave != null) {
                val iconSize = (size * 0.68f).toInt()
                val scaled = Bitmap.createScaledBitmap(rawWave, iconSize, iconSize, true)
                val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                }
                val offset = (size - iconSize) / 2f
                canvas.drawBitmap(scaled, offset, offset, iconPaint)
            }

            val pngBytes = ByteArrayOutputStream().use { out ->
                badge.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }

            uploadToCatbox(pngBytes) ?: uploadToLitterbox(pngBytes)
        } catch (e: Exception) {
            Log.w(TAG, "renderAndUploadViWavesBadge: failed", e)
            null
        }
    }

    private fun uploadToCatbox(pngBytes: ByteArray): String? = try {
        val boundary = "----ViWavesDiscordBoundary${System.currentTimeMillis()}"
        val conn = (URL("https://catbox.moe/user/api.php").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "ViWaves/1.0")
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"viwaves_logo.png\"\r\n")
            out.writeBytes("Content-Type: image/png\r\n\r\n")
            out.write(pngBytes)
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText().trim() else ""
        conn.disconnect()
        resp.takeIf { it.startsWith("https://") }
    } catch (e: Exception) {
        null
    }

    private fun uploadToLitterbox(pngBytes: ByteArray): String? = try {
        val boundary = "----ViWavesLitterboxBoundary${System.currentTimeMillis()}"
        val conn = (URL("https://litterbox.catbox.moe/resources/internals/api.php").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "ViWaves/1.0")
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"time\"\r\n\r\n72h\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"viwaves_logo.png\"\r\n")
            out.writeBytes("Content-Type: image/png\r\n\r\n")
            out.write(pngBytes)
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }
        val code = conn.responseCode
        val resp = if (code in 200..299) conn.inputStream.bufferedReader().readText().trim() else ""
        conn.disconnect()
        resp.takeIf { it.startsWith("https://") }
    } catch (e: Exception) {
        null
    }

    private fun trimCache() {
        if (cache.size > CACHE_MAX_SIZE) {
            val toRemove = cache.size - CACHE_MAX_SIZE
            cache.keys.take(toRemove).forEach { cache.remove(it) }
        }
    }

    fun clearCache() {
        Log.d(TAG, "clearCache: clearing ${cache.size} entries")
        cache.clear()
    }

    fun close() {
        runCatching { client.close() }
    }

    @Serializable
    private data class ExternalAssetRequest(
        val urls: List<String>,
    )

    @Serializable
    private data class ExternalAssetResponse(
        val url: String? = null,
        @SerialName("external_asset_path")
        val externalAssetPath: String? = null,
    )
}
