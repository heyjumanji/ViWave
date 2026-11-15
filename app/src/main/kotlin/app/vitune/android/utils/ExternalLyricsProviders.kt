package app.vitune.android.utils

import app.vitune.android.preferences.PlayerPreferences.LyricsProvider
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.NextBody
import app.vitune.providers.innertube.requests.lyrics
import app.vitune.providers.kugou.KuGou
import app.vitune.providers.lrclib.LrcLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ResolvedLyrics(
    val fixed: String?,
    val synced: String?,
    val providerName: String? = null
)

object ExternalLyricsProviders {

    private val memoryCache = ConcurrentHashMap<String, ResolvedLyrics>()
    private val resolvedProvidersByVideoId = ConcurrentHashMap<String, String>()
    private val translationCache = ConcurrentHashMap<String, Map<Int, String>>()
    private val romanizationCache = ConcurrentHashMap<String, Map<Int, String>>()

    fun clearCacheFor(videoId: String) {
        val cleanId = videoId.trim()
        val prefix = "$cleanId|"
        memoryCache.keys.removeIf { it.startsWith(prefix) }
        translationCache.keys.removeIf { it.startsWith(prefix) }
        romanizationCache.keys.removeIf { it.startsWith(prefix) }
        resolvedProvidersByVideoId.remove(cleanId)
    }

    fun getResolvedProviderName(
        videoId: String,
        synced: String?,
        fixed: String?,
        preferredProvider: LyricsProvider
    ): String {
        val cleanId = videoId.trim()
        resolvedProvidersByVideoId[cleanId]?.takeIf { it.isNotBlank() }?.let { return it }
        if (preferredProvider != LyricsProvider.Auto) {
            return preferredProvider.shortName
        }
        return when {
            hasWordLevelTimestamps(synced) -> "Unison"
            !synced.isNullOrBlank() -> "LrcLib"
            !fixed.isNullOrBlank() -> "YouTube Music"
            else -> "LrcLib"
        }
    }

    private val YOULYPLUS_SERVERS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev"
    )

    private fun encodeParam(value: String): String =
        runCatching { URLEncoder.encode(value.trim(), "UTF-8") }.getOrDefault("")

    private fun httpGet(
        urlString: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 2500,
        readTimeoutMs: Int = 3000
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "ViTune/1.0 (Android; https://unison.boidu.dev; https://lyrics.paxsenix.org)"
                )
                setRequestProperty("Accept", "application/json, text/plain, */*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = connection.responseCode
            if (code !in 200..299) return null
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private val WORD_TIMESTAMP_DETECTION_REGEX =
        Regex("(?:<|\\()\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?(?:>|\\))")

    fun hasWordLevelTimestamps(lrc: String?): Boolean {
        if (lrc.isNullOrBlank()) return false
        return WORD_TIMESTAMP_DETECTION_REGEX.containsMatchIn(lrc)
    }

    private suspend fun raceCandidateUrls(
        urls: List<String>,
        timeoutMs: Long = 3400L,
        parser: (String) -> ResolvedLyrics?
    ): ResolvedLyrics? = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val deferreds = urls.map { url ->
                async(Dispatchers.IO) {
                    val raw = httpGet(url, connectTimeoutMs = 2400, readTimeoutMs = 3000) ?: return@async null
                    parser(raw)?.takeIf { !it.synced.isNullOrBlank() || !it.fixed.isNullOrBlank() }
                }
            }
            val results = deferreds.awaitAll().filterNotNull()
            results.firstOrNull { hasWordLevelTimestamps(it.synced) }
                ?: results.firstOrNull { !it.synced.isNullOrBlank() }
                ?: results.firstOrNull()
        }
    }

    private fun formatLrcTimestamp(timeMs: Long, bracketStart: Char = '[', bracketEnd: Char = ']'): String {
        val safeMs = timeMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centis = (safeMs % 1000L) / 10L
        return String.format(
            java.util.Locale.US,
            "%c%02d:%02d.%02d%c",
            bracketStart,
            minutes,
            seconds,
            centis,
            bracketEnd
        )
    }

    private fun parseTimeAttrToMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
        if (cleaned.endsWith("ms", ignoreCase = true)) {
            return cleaned.removeSuffix("ms").removeSuffix("MS").trim().toDoubleOrNull()?.toLong()
        }
        if (cleaned.endsWith("s", ignoreCase = true)) {
            return cleaned.removeSuffix("s").removeSuffix("S").trim().toDoubleOrNull()?.let { (it * 1000.0).toLong() }
        }
        val parts = cleaned.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val s = parts[2].toDoubleOrNull() ?: return null
                (h * 3600_000L) + (m * 60_000L) + (s * 1000.0).toLong()
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val s = parts[1].toDoubleOrNull() ?: return null
                (m * 60_000L) + (s * 1000.0).toLong()
            }
            1 -> cleaned.toDoubleOrNull()?.let {
                // If large integer, treat as ms; if decimal or small, inspect context
                if (cleaned.contains(".")) (it * 1000.0).toLong() else it.toLong()
            }
            else -> null
        }
    }

    private fun extractPlainFromLrc(lrc: String): String {
        val lineTimestampRegex = Regex("\\[\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?]")
        val wordTimestampRegex = Regex("(?:<|\\()\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?(?:>|\\))")
        val metaTagRegex = Regex("^\\[[a-zA-Z]+:.*]$")
        return lrc.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !metaTagRegex.matches(it) }
            .map { line ->
                line.replace(lineTimestampRegex, "")
                    .replace(wordTimestampRegex, "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun parseTtmlToLyrics(ttml: String): ResolvedLyrics? {
        val pRegex = Regex("<p\\b([^>]*)>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val spanRegex = Regex("<span\\b([^>]*)>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val beginAttrRegex = Regex("\\bbegin\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val endAttrRegex = Regex("\\bend\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val durAttrRegex = Regex("\\bdur\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        val tagStripRegex = Regex("<[^>]+>")

        val lrcLines = mutableListOf<String>()
        val plainLines = mutableListOf<String>()

        for (pMatch in pRegex.findAll(ttml)) {
            val attrs = pMatch.groupValues[1]
            val body = pMatch.groupValues[2]
            val beginMs = beginAttrRegex.find(attrs)?.groupValues?.getOrNull(1)?.let(::parseTimeAttrToMs)
            val lineEndMs = endAttrRegex.find(attrs)?.groupValues?.getOrNull(1)?.let(::parseTimeAttrToMs)

            val spans = spanRegex.findAll(body).toList()
            if (spans.isNotEmpty() && beginMs != null) {
                val wordBuilder = StringBuilder()
                val plainWordBuilder = StringBuilder()
                var lastWordEndMs: Long? = null
                for (span in spans) {
                    val sAttrs = span.groupValues[1]
                    val rawSpanText = span.groupValues[2].replace(tagStripRegex, "")
                    val sText = rawSpanText.trim()
                    if (sText.isEmpty()) continue
                    val wBeginMs = beginAttrRegex.find(sAttrs)?.groupValues?.getOrNull(1)?.let(::parseTimeAttrToMs)
                        ?: lastWordEndMs
                        ?: beginMs
                    val wEndMs = endAttrRegex.find(sAttrs)?.groupValues?.getOrNull(1)?.let(::parseTimeAttrToMs)
                        ?: durAttrRegex.find(sAttrs)?.groupValues?.getOrNull(1)?.let(::parseTimeAttrToMs)?.let { wBeginMs + it }

                    if (lastWordEndMs != null && wBeginMs - lastWordEndMs > 60L) {
                        wordBuilder.append(formatLrcTimestamp(lastWordEndMs, '<', '>'))
                    }
                    if (wordBuilder.isNotEmpty()) wordBuilder.append(' ')
                    wordBuilder.append(formatLrcTimestamp(wBeginMs, '<', '>')).append(sText)
                    if (wEndMs != null && wEndMs > wBeginMs) {
                        lastWordEndMs = wEndMs
                    } else {
                        lastWordEndMs = null
                    }
                    if (plainWordBuilder.isNotEmpty()) plainWordBuilder.append(' ')
                    plainWordBuilder.append(sText)
                }
                val finalEndMarker = lastWordEndMs ?: lineEndMs
                if (finalEndMarker != null && finalEndMarker > beginMs) {
                    wordBuilder.append(formatLrcTimestamp(finalEndMarker, '<', '>'))
                }
                val plainText = plainWordBuilder.toString().trim()
                if (plainText.isNotEmpty()) {
                    lrcLines.add("${formatLrcTimestamp(beginMs)}$wordBuilder")
                    plainLines.add(plainText)
                }
            } else {
                val cleanLine = body.replace(tagStripRegex, "").trim()
                if (cleanLine.isNotEmpty()) {
                    plainLines.add(cleanLine)
                    if (beginMs != null) {
                        lrcLines.add("${formatLrcTimestamp(beginMs)}$cleanLine")
                    }
                }
            }
        }

        if (lrcLines.isEmpty() && plainLines.isEmpty()) return null
        return ResolvedLyrics(
            fixed = plainLines.joinToString("\n").takeIf { it.isNotBlank() },
            synced = lrcLines.joinToString("\n").takeIf { it.isNotBlank() },
            providerName = "Unison"
        )
    }

    /**
     * 1. Unison & BetterLyrics (https://unison.boidu.dev/docs & https://lyrics-api.boidu.dev)
     */
    suspend fun fetchUnisonLyrics(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int
    ): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val cleanVideoId = videoId.removePrefix("https://youtube.com/watch?v=").trim()
        val encodedTitle = encodeParam(title)
        val encodedArtist = encodeParam(artist)
        val encodedAlbum = album?.takeIf { it.isNotBlank() }?.let(::encodeParam) ?: ""
        val durParam = if (durationSec > 0) durationSec.toString() else ""
        val durMsParam = if (durationSec > 0) (durationSec * 1000).toString() else ""

        val candidateUrls = buildList {
            add(
                "https://lyrics-api.boidu.dev/getLyrics?s=$encodedTitle&a=$encodedArtist" +
                    (if (encodedAlbum.isNotEmpty()) "&al=$encodedAlbum" else "") +
                    (if (durParam.isNotEmpty()) "&d=$durParam" else "")
            )
            if (durMsParam.isNotEmpty()) {
                add(
                    "https://lyrics-api.boidu.dev/getLyrics?s=$encodedTitle&a=$encodedArtist" +
                        (if (encodedAlbum.isNotEmpty()) "&al=$encodedAlbum" else "") +
                        "&d=$durMsParam"
                )
            }
            if (cleanVideoId.isNotEmpty() && !cleanVideoId.startsWith("local:")) {
                add(
                    "https://unison.boidu.dev/lyrics?v=${encodeParam(cleanVideoId)}" +
                        "&song=$encodedTitle&artist=$encodedArtist" +
                        (if (encodedAlbum.isNotEmpty()) "&album=$encodedAlbum" else "") +
                        (if (durParam.isNotEmpty()) "&duration=$durParam" else "")
                )
            }
            add(
                "https://unison.boidu.dev/lyrics?song=$encodedTitle&artist=$encodedArtist" +
                    (if (encodedAlbum.isNotEmpty()) "&album=$encodedAlbum" else "") +
                    (if (durParam.isNotEmpty()) "&duration=$durParam" else "")
            )
        }

        raceCandidateUrls(candidateUrls, timeoutMs = 3400L, parser = ::parseUnisonResponse)
    }

    private fun parseUnisonResponse(raw: String): ResolvedLyrics? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Raw LRC
        if (trimmed.startsWith("[0") || Regex("^\\[\\d{2}:\\d{2}").containsMatchIn(trimmed)) {
            val plain = extractPlainFromLrc(trimmed)
            return ResolvedLyrics(
                fixed = plain.ifBlank { trimmed },
                synced = trimmed,
                providerName = "Unison"
            )
        }

        // Raw TTML
        if (trimmed.startsWith("<tt") || trimmed.contains("<tt ") || trimmed.contains("<body")) {
            return parseTtmlToLyrics(trimmed)
        }

        return runCatching {
            val root = JSONObject(trimmed)
            val dataObj = root.optJSONObject("data") ?: root

            // Case 1: String field "lyrics" or "lrc" or "ttml"
            val ttmlStr = dataObj.optString("ttml").takeIf { it.isNotBlank() }
                ?: root.optString("ttml").takeIf { it.isNotBlank() }
            if (ttmlStr != null) {
                parseTtmlToLyrics(ttmlStr)?.let { return@runCatching it }
            }

            val lrcStr = dataObj.optString("lrc").takeIf { it.isNotBlank() }
                ?: dataObj.optString("syncedLyrics").takeIf { it.isNotBlank() }
            val plainStr = dataObj.optString("plain").takeIf { it.isNotBlank() }
                ?: dataObj.optString("plainLyrics").takeIf { it.isNotBlank() }

            if (lrcStr != null || plainStr != null) {
                return@runCatching ResolvedLyrics(
                    fixed = plainStr ?: lrcStr?.let(::extractPlainFromLrc),
                    synced = lrcStr,
                    providerName = "Unison"
                )
            }

            val lyricsString = dataObj.opt("lyrics")
            if (lyricsString is String && lyricsString.isNotBlank()) {
                if (lyricsString.contains("<tt") || lyricsString.contains("<p ")) {
                    parseTtmlToLyrics(lyricsString)?.let { return@runCatching it }
                }
                val hasLrcTimestamps = Regex("\\[\\d{1,3}:\\d{2}").containsMatchIn(lyricsString)
                return@runCatching if (hasLrcTimestamps) {
                    ResolvedLyrics(
                        fixed = extractPlainFromLrc(lyricsString).ifBlank { lyricsString },
                        synced = lyricsString,
                        providerName = "Unison"
                    )
                } else {
                    ResolvedLyrics(
                        fixed = lyricsString,
                        synced = null,
                        providerName = "Unison"
                    )
                }
            }

            // Case 2: Array of line objects (BetterLyrics / Unison JSON array)
            val lyricsArray = (lyricsString as? JSONArray)
                ?: dataObj.optJSONArray("lines")
                ?: root.optJSONArray("lyrics")

            if (lyricsArray != null && lyricsArray.length() > 0) {
                val lrcLines = mutableListOf<String>()
                val plainLines = mutableListOf<String>()

                for (i in 0 until lyricsArray.length()) {
                    val item = lyricsArray.optJSONObject(i) ?: continue
                    val startMs = when {
                        item.has("startTimeMs") -> item.optLong("startTimeMs", -1L)
                        item.has("time") -> item.optLong("time", -1L)
                        item.has("timestamp") -> item.optLong("timestamp", -1L)
                        else -> -1L
                    }
                    val lineDurMs = when {
                        item.has("durationMs") -> item.optLong("durationMs", 0L)
                        item.has("duration") -> item.optLong("duration", 0L)
                        else -> 0L
                    }
                    val lineEndMs = when {
                        item.has("endTimeMs") -> item.optLong("endTimeMs", -1L)
                        lineDurMs > 0L && startMs >= 0L -> startMs + lineDurMs
                        else -> -1L
                    }
                    val lineWords = item.optString("words")
                        .ifBlank { item.optString("text") }
                        .trim()

                    val partsArray = item.optJSONArray("parts") ?: item.optJSONArray("syllabus")
                    if (partsArray != null && partsArray.length() > 0 && startMs >= 0L) {
                        val wordLine = StringBuilder()
                        val plainLineBuilder = StringBuilder()
                        var lastPartEndMs: Long? = null

                        // Merge sub-word syllables into complete words or preserve individual syllable tags
                        for (p in 0 until partsArray.length()) {
                            val part = partsArray.optJSONObject(p) ?: continue
                            val rawPartText = part.optString("words")
                                .ifBlank { part.optString("text") }
                            val pText = rawPartText.trim()
                            if (pText.isEmpty()) continue
                            val pStart = when {
                                part.has("startTimeMs") -> part.optLong("startTimeMs", startMs)
                                part.has("time") -> part.optLong("time", startMs)
                                part.has("timestamp") -> part.optLong("timestamp", startMs)
                                else -> lastPartEndMs ?: startMs
                            }
                            val pDur = when {
                                part.has("durationMs") -> part.optLong("durationMs", 0L)
                                part.has("duration") -> part.optLong("duration", 0L)
                                else -> 0L
                            }
                            val pEnd = when {
                                part.has("endTimeMs") -> part.optLong("endTimeMs", -1L)
                                pDur > 0L -> pStart + pDur
                                else -> -1L
                            }

                            if (lastPartEndMs != null && pStart - lastPartEndMs > 60L) {
                                wordLine.append(formatLrcTimestamp(lastPartEndMs, '<', '>'))
                            }
                            if (wordLine.isNotEmpty()) wordLine.append(' ')
                            wordLine.append(formatLrcTimestamp(pStart, '<', '>')).append(pText)
                            lastPartEndMs = if (pEnd > pStart) pEnd else null

                            if (plainLineBuilder.isNotEmpty()) plainLineBuilder.append(' ')
                            plainLineBuilder.append(pText)
                        }
                        val finalEndMs = lastPartEndMs ?: lineEndMs.takeIf { it > startMs }
                        if (finalEndMs != null && finalEndMs > startMs) {
                            wordLine.append(formatLrcTimestamp(finalEndMs, '<', '>'))
                        }
                        val finalPlain = plainLineBuilder.toString().ifBlank { lineWords }
                        if (finalPlain.isNotBlank()) {
                            plainLines.add(finalPlain)
                            lrcLines.add("${formatLrcTimestamp(startMs)}${wordLine.ifEmpty { finalPlain }}")
                        }
                    } else if (lineWords.isNotEmpty()) {
                        plainLines.add(lineWords)
                        if (startMs >= 0L) {
                            lrcLines.add("${formatLrcTimestamp(startMs)}$lineWords")
                        }
                    }
                }

                if (lrcLines.isNotEmpty() || plainLines.isNotEmpty()) {
                    return@runCatching ResolvedLyrics(
                        fixed = plainLines.joinToString("\n").takeIf { it.isNotBlank() },
                        synced = lrcLines.joinToString("\n").takeIf { it.isNotBlank() },
                        providerName = "Unison"
                    )
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 2. YouLyPlus / LyricsPlus (https://github.com/ibratabian17/YouLyPlus)
     */
    suspend fun fetchYouLyPlusLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int
    ): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val encodedTitle = encodeParam(title)
        val encodedArtist = encodeParam(artist)
        val encodedAlbum = album?.takeIf { it.isNotBlank() }?.let(::encodeParam) ?: ""
        val durParam = if (durationSec > 0) durationSec.toString() else ""

        val candidateUrls = YOULYPLUS_SERVERS.map { baseUrl ->
            buildString {
                append("$baseUrl/v2/lyrics/get?title=$encodedTitle&artist=$encodedArtist")
                if (encodedAlbum.isNotEmpty()) append("&album=$encodedAlbum")
                if (durParam.isNotEmpty()) append("&duration=$durParam")
            }
        }
        raceCandidateUrls(candidateUrls, timeoutMs = 3400L, parser = ::parseYouLyPlusResponse)
    }

    private fun parseYouLyPlusResponse(raw: String): ResolvedLyrics? = runCatching {
        val root = JSONObject(raw)
        val type = root.optString("type", "Line")
        val lyricsArray = root.optJSONArray("lyrics") ?: return@runCatching null
        if (lyricsArray.length() == 0) return@runCatching null

        val lrcLines = mutableListOf<String>()
        val plainLines = mutableListOf<String>()
        val isPlainOnly = type.equals("Plain", ignoreCase = true)

        for (i in 0 until lyricsArray.length()) {
            val item = lyricsArray.optJSONObject(i) ?: continue
            val timeMs = item.optLong("time", -1L)
            val lineDurationMs = item.optLong("duration", 0L)
            val text = item.optString("text").trim()
            val syllabus = item.optJSONArray("syllabus")

            if (!isPlainOnly && syllabus != null && syllabus.length() > 0 && timeMs >= 0L) {
                val wordLine = StringBuilder()
                val plainBuilder = StringBuilder()
                var lastSylEndMs: Long? = null

                // Group sub-word syllables that don't end with whitespace if needed, or emit precise timestamps
                var pendingWordText = StringBuilder()
                var pendingWordStartMs = -1L
                var pendingWordEndMs = -1L

                fun flushPendingWord() {
                    val wordStr = pendingWordText.toString().trim()
                    if (wordStr.isNotEmpty() && pendingWordStartMs >= 0L) {
                        val prevEndMs = lastSylEndMs
                        if (prevEndMs != null && pendingWordStartMs - prevEndMs > 60L) {
                            wordLine.append(formatLrcTimestamp(prevEndMs, '<', '>'))
                        }
                        if (wordLine.isNotEmpty()) wordLine.append(' ')
                        wordLine.append(formatLrcTimestamp(pendingWordStartMs, '<', '>')).append(wordStr)
                        lastSylEndMs = if (pendingWordEndMs > pendingWordStartMs) pendingWordEndMs else null
                        if (plainBuilder.isNotEmpty()) plainBuilder.append(' ')
                        plainBuilder.append(wordStr)
                    }
                    pendingWordText.clear()
                    pendingWordStartMs = -1L
                    pendingWordEndMs = -1L
                }

                for (s in 0 until syllabus.length()) {
                    val syl = syllabus.optJSONObject(s) ?: continue
                    val rawSylText = syl.optString("text")
                    if (rawSylText.isEmpty()) continue
                    val sylTime = syl.optLong("time", timeMs)
                    val sylDur = syl.optLong("duration", 0L)
                    val sylEnd = if (sylDur > 0L) sylTime + sylDur else -1L

                    val startsWithSpace = rawSylText.first().isWhitespace()
                    val endsWithSpace = rawSylText.last().isWhitespace()

                    if (startsWithSpace && pendingWordText.isNotEmpty()) {
                        flushPendingWord()
                    }
                    if (pendingWordStartMs < 0L) {
                        pendingWordStartMs = sylTime
                    }
                    pendingWordText.append(rawSylText.trim())
                    if (sylEnd > 0L) {
                        pendingWordEndMs = sylEnd
                    }
                    if (endsWithSpace || s == syllabus.length() - 1) {
                        flushPendingWord()
                    }
                }

                val finalEndMs = lastSylEndMs ?: if (lineDurationMs > 0L) timeMs + lineDurationMs else null
                if (finalEndMs != null && finalEndMs > timeMs) {
                    wordLine.append(formatLrcTimestamp(finalEndMs, '<', '>'))
                }

                val finalPlain = text.ifBlank { plainBuilder.toString().trim() }
                if (finalPlain.isNotBlank()) {
                    plainLines.add(finalPlain)
                    lrcLines.add("${formatLrcTimestamp(timeMs)}${wordLine.ifEmpty { finalPlain }}")
                }
            } else if (text.isNotEmpty()) {
                plainLines.add(text)
                if (!isPlainOnly && timeMs >= 0L) {
                    lrcLines.add("${formatLrcTimestamp(timeMs)}$text")
                }
            }
        }

        if (lrcLines.isEmpty() && plainLines.isEmpty()) return@runCatching null
        ResolvedLyrics(
            fixed = plainLines.joinToString("\n").takeIf { it.isNotBlank() },
            synced = lrcLines.joinToString("\n").takeIf { it.isNotBlank() },
            providerName = "YouLyPlus"
        )
    }.getOrNull()

    /**
     * 3. Paxsenix Lyrics API (https://lyrics.paxsenix.org/)
     */
    suspend fun fetchPaxsenixLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int
    ): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val encodedTitle = encodeParam(title)
        val encodedArtist = encodeParam(artist)
        val encodedAlbum = album?.takeIf { it.isNotBlank() }?.let(::encodeParam) ?: ""
        val encodedQuery = encodeParam("$title $artist".trim())
        val durParam = if (durationSec > 0) durationSec.toString() else ""

        // 1. Direct query endpoints on lyrics.paxsenix.org
        val directUrls = buildList {
            add(
                "https://lyrics.paxsenix.org/apple-music/lyrics?title=$encodedTitle&artist=$encodedArtist" +
                    (if (encodedAlbum.isNotEmpty()) "&album=$encodedAlbum" else "") +
                    (if (durParam.isNotEmpty()) "&duration=$durParam" else "")
            )
            add(
                "https://lyrics.paxsenix.org/lyrics?title=$encodedTitle&artist=$encodedArtist" +
                    (if (encodedAlbum.isNotEmpty()) "&album=$encodedAlbum" else "") +
                    (if (durParam.isNotEmpty()) "&duration=$durParam" else "")
            )
            add("https://lyrics.paxsenix.org/musixmatch/lyrics?title=$encodedTitle&artist=$encodedArtist")
        }

        raceCandidateUrls(directUrls, timeoutMs = 3000L, parser = ::parsePaxsenixResponse)?.let {
            return@withContext it
        }

        // 2. Two-step Search -> Fetch by Track ID on lyrics.paxsenix.org
        val searchUrls = listOf(
            "https://lyrics.paxsenix.org/apple-music/search?q=$encodedQuery",
            "https://lyrics.paxsenix.org/search?q=$encodedQuery"
        )

        for (searchUrl in searchUrls) {
            val searchRaw = httpGet(searchUrl, connectTimeoutMs = 2200, readTimeoutMs = 2600) ?: continue
            val trackId = extractPaxsenixTrackId(searchRaw, durationSec) ?: continue
            val trackLyricsUrls = listOf(
                "https://lyrics.paxsenix.org/apple-music/lyrics?id=${encodeParam(trackId)}",
                "https://lyrics.paxsenix.org/lyrics?id=${encodeParam(trackId)}"
            )
            raceCandidateUrls(trackLyricsUrls, timeoutMs = 2800L, parser = ::parsePaxsenixResponse)?.let {
                return@withContext it
            }
        }

        null
    }

    private fun extractPaxsenixTrackId(raw: String, targetDurationSec: Int): String? = runCatching {
        val trimmed = raw.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("results")
                    ?: obj.optJSONArray("data")
                    ?: obj.optJSONArray("tracks")
                    ?: obj.optJSONArray("songs")
            }
            else -> null
        } ?: return@runCatching null

        if (array.length() == 0) return@runCatching null
        var bestId: String? = null
        var bestDiff = Int.MAX_VALUE

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() }
                ?: item.optString("trackId").takeIf { it.isNotBlank() }
                ?: continue
            if (bestId == null) bestId = id

            val durMsOrSec = item.optLong("durationInMillis", -1L).let {
                if (it > 0) (it / 1000L).toInt() else item.optInt("duration", -1)
            }
            if (targetDurationSec > 0 && durMsOrSec > 0) {
                val diff = kotlin.math.abs(durMsOrSec - targetDurationSec)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestId = id
                }
            }
        }
        bestId
    }.getOrNull()

    private fun parsePaxsenixResponse(raw: String): ResolvedLyrics? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Raw LRC response
        if (trimmed.startsWith("[0") || Regex("^\\[\\d{2}:\\d{2}").containsMatchIn(trimmed)) {
            return ResolvedLyrics(
                fixed = extractPlainFromLrc(trimmed).ifBlank { trimmed },
                synced = trimmed,
                providerName = "Paxsenix"
            )
        }

        return runCatching {
            val root = if (trimmed.startsWith("{")) JSONObject(trimmed) else null
            val dataObj = root?.optJSONObject("data") ?: root

            if (dataObj != null) {
                val ttmlField = dataObj.optString("ttml").takeIf { it.isNotBlank() }
                if (ttmlField != null) {
                    parseTtmlToLyrics(ttmlField)?.copy(providerName = "Paxsenix")?.let { return@runCatching it }
                }

                val lrcField = dataObj.optString("elrc").takeIf { it.isNotBlank() }
                    ?: dataObj.optString("richsync").takeIf { it.isNotBlank() }
                    ?: dataObj.optString("lrc").takeIf { it.isNotBlank() }
                    ?: dataObj.optString("syncedLyrics").takeIf { it.isNotBlank() }
                    ?: dataObj.optString("lyrics").takeIf {
                        it.isNotBlank() && Regex("\\[\\d{1,3}:\\d{2}").containsMatchIn(it)
                    }
                val plainField = dataObj.optString("plain").takeIf { it.isNotBlank() }
                    ?: dataObj.optString("plainLyrics").takeIf { it.isNotBlank() }
                    ?: dataObj.optString("unsyncedLyrics").takeIf { it.isNotBlank() }

                if (lrcField != null || plainField != null) {
                    return@runCatching ResolvedLyrics(
                        fixed = plainField ?: lrcField?.let(::extractPlainFromLrc),
                        synced = lrcField,
                        providerName = "Paxsenix"
                    )
                }
            }

            val contentArray = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                dataObj != null -> dataObj.optJSONArray("content")
                    ?: dataObj.optJSONArray("lyrics")
                    ?: dataObj.optJSONArray("lines")
                else -> null
            } ?: return@runCatching null

            if (contentArray.length() == 0) return@runCatching null

            val lrcLines = mutableListOf<String>()
            val plainLines = mutableListOf<String>()

            for (i in 0 until contentArray.length()) {
                val lineObj = contentArray.optJSONObject(i) ?: continue
                val lineTimeMs = when {
                    lineObj.has("timestamp") -> lineObj.optLong("timestamp", -1L)
                    lineObj.has("time") -> lineObj.optLong("time", -1L)
                    lineObj.has("startTimeMs") -> lineObj.optLong("startTimeMs", -1L)
                    else -> -1L
                }
                val lineEndTimeMs = when {
                    lineObj.has("endtime") -> lineObj.optLong("endtime", -1L)
                    lineObj.has("endTimeMs") -> lineObj.optLong("endTimeMs", -1L)
                    lineObj.has("duration") && lineTimeMs >= 0L -> lineTimeMs + lineObj.optLong("duration", 0L)
                    else -> -1L
                }

                val textProp = lineObj.opt("text") ?: lineObj.opt("words") ?: lineObj.opt("syllabus")
                when (textProp) {
                    is JSONArray -> {
                        val wordLine = StringBuilder()
                        val plainBuilder = StringBuilder()
                        var lastWordEndMs: Long? = null

                        for (w in 0 until textProp.length()) {
                            val wordObj = textProp.optJSONObject(w) ?: continue
                            val wText = wordObj.optString("text")
                                .ifBlank { wordObj.optString("word") }
                                .trim()
                            if (wText.isEmpty()) continue
                            val wTime = when {
                                wordObj.has("timestamp") -> wordObj.optLong("timestamp", lineTimeMs.coerceAtLeast(0L))
                                wordObj.has("time") -> wordObj.optLong("time", lineTimeMs.coerceAtLeast(0L))
                                wordObj.has("startTimeMs") -> wordObj.optLong("startTimeMs", lineTimeMs.coerceAtLeast(0L))
                                else -> lastWordEndMs ?: lineTimeMs.coerceAtLeast(0L)
                            }
                            val wEnd = when {
                                wordObj.has("endtime") -> wordObj.optLong("endtime", -1L)
                                wordObj.has("endTimeMs") -> wordObj.optLong("endTimeMs", -1L)
                                wordObj.has("duration") -> wTime + wordObj.optLong("duration", 0L)
                                else -> -1L
                            }
                            if (lastWordEndMs != null && wTime - lastWordEndMs > 60L) {
                                wordLine.append(formatLrcTimestamp(lastWordEndMs, '<', '>'))
                            }
                            if (wordLine.isNotEmpty()) wordLine.append(' ')
                            wordLine.append(formatLrcTimestamp(wTime, '<', '>')).append(wText)
                            lastWordEndMs = if (wEnd > wTime) wEnd else null

                            if (plainBuilder.isNotEmpty()) plainBuilder.append(' ')
                            plainBuilder.append(wText)
                        }
                        val finalEndMs = lastWordEndMs ?: lineEndTimeMs.takeIf { it > lineTimeMs }
                        if (finalEndMs != null && finalEndMs > lineTimeMs.coerceAtLeast(0L)) {
                            wordLine.append(formatLrcTimestamp(finalEndMs, '<', '>'))
                        }
                        val finalPlain = plainBuilder.toString().trim()
                        if (finalPlain.isNotEmpty()) {
                            plainLines.add(finalPlain)
                            val effectiveLineMs = if (lineTimeMs >= 0L) lineTimeMs else 0L
                            lrcLines.add("${formatLrcTimestamp(effectiveLineMs)}$wordLine")
                        }
                    }
                    is String -> {
                        val cleanText = textProp.trim()
                        if (cleanText.isNotEmpty()) {
                            plainLines.add(cleanText)
                            if (lineTimeMs >= 0L) {
                                lrcLines.add("${formatLrcTimestamp(lineTimeMs)}$cleanText")
                            }
                        }
                    }
                }
            }

            if (lrcLines.isEmpty() && plainLines.isEmpty()) return@runCatching null
            ResolvedLyrics(
                fixed = plainLines.joinToString("\n").takeIf { it.isNotBlank() },
                synced = lrcLines.joinToString("\n").takeIf { it.isNotBlank() },
                providerName = "Paxsenix"
            )
        }.getOrNull()
    }

    /**
     * 4. LrcLib (single-pass query returning both synced & plain lyrics simultaneously)
     */
    suspend fun fetchLrcLibLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Int
    ): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val tracks = coroutineScope {
            val primaryDeferred = async {
                withTimeoutOrNull(2800L) {
                    LrcLib.lyrics(
                        artist = artist,
                        title = title,
                        album = album?.takeIf { it.isNotBlank() },
                        synced = false
                    )?.getOrNull()
                }
            }
            val fallbackDeferred = if (!album.isNullOrBlank()) {
                async {
                    withTimeoutOrNull(2800L) {
                        LrcLib.lyrics(
                            artist = artist,
                            title = title,
                            album = null,
                            synced = false
                        )?.getOrNull()
                    }
                }
            } else null

            val primary = primaryDeferred.await().orEmpty()
            if (primary.any { !it.syncedLyrics.isNullOrBlank() }) {
                primary
            } else {
                val secondary = fallbackDeferred?.await().orEmpty()
                (primary + secondary).distinctBy { it.id }
            }
        }.ifEmpty {
            withTimeoutOrNull(2500L) {
                LrcLib.lyrics(query = "$artist $title".trim(), synced = false)?.getOrNull()
            }.orEmpty()
        }

        if (tracks.isEmpty()) return@withContext null

        val durationMatchingTracks = if (durationSec > 0) {
            tracks.filter { abs(it.duration.toInt() - durationSec) <= 12 }
                .ifEmpty { tracks }
        } else {
            tracks
        }

        // Prefer any LrcLib track that has enhanced word-level timestamps (<mm:ss.xx>) if present
        val bestSyncedTrack = durationMatchingTracks.firstOrNull { hasWordLevelTimestamps(it.syncedLyrics) }
            ?: durationMatchingTracks.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
            ?: tracks.firstOrNull { hasWordLevelTimestamps(it.syncedLyrics) }
            ?: tracks.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
        val bestPlainTrack = bestSyncedTrack?.takeIf { !it.plainLyrics.isNullOrBlank() }
            ?: durationMatchingTracks.firstOrNull { !it.plainLyrics.isNullOrBlank() }
            ?: tracks.firstOrNull { !it.plainLyrics.isNullOrBlank() }

        val synced = bestSyncedTrack?.syncedLyrics?.trim()?.takeIf { it.isNotBlank() }
        val fixed = bestPlainTrack?.plainLyrics?.trim()?.takeIf { it.isNotBlank() }
            ?: synced?.let(::extractPlainFromLrc)?.takeIf { it.isNotBlank() }

        if (fixed.isNullOrBlank() && synced.isNullOrBlank()) null
        else ResolvedLyrics(
            fixed = fixed,
            synced = synced,
            providerName = "LrcLib"
        )
    }

    /**
     * 5. KuGou
     */
    suspend fun fetchKuGouLyrics(
        title: String,
        artist: String,
        durationSec: Int
    ): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val synced = withTimeoutOrNull(3000L) {
            KuGou.lyrics(
                artist = artist,
                title = title,
                duration = durationSec.coerceAtLeast(1).toLong()
            )?.getOrNull()?.value?.trim()?.takeIf { it.isNotBlank() }
        } ?: return@withContext null

        ResolvedLyrics(
            fixed = extractPlainFromLrc(synced).ifBlank { null },
            synced = synced,
            providerName = "KuGou"
        )
    }

    /**
     * 6. Innertube (YouTube Music)
     */
    suspend fun fetchInnertubeLyrics(videoId: String): ResolvedLyrics? = withContext(Dispatchers.IO) {
        val cleanVideoId = videoId.removePrefix("https://youtube.com/watch?v=").trim()
        if (cleanVideoId.isEmpty() || cleanVideoId.startsWith("local:")) return@withContext null
        val fixed = withTimeoutOrNull(3000L) {
            Innertube.lyrics(NextBody(videoId = cleanVideoId))
                ?.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } ?: return@withContext null

        ResolvedLyrics(
            fixed = fixed,
            synced = null,
            providerName = "YouTube Music"
        )
    }

    suspend fun fetchFromSingleProvider(
        provider: LyricsProvider,
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        durationSec: Int
    ): ResolvedLyrics? = when (provider) {
        LyricsProvider.Unison -> fetchUnisonLyrics(videoId, title, artist, album, durationSec)
        LyricsProvider.YouLyPlus -> fetchYouLyPlusLyrics(title, artist, album, durationSec)
        LyricsProvider.Paxsenix -> fetchPaxsenixLyrics(title, artist, album, durationSec)
        LyricsProvider.LrcLib -> fetchLrcLibLyrics(title, artist, album, durationSec)
        LyricsProvider.KuGou -> fetchKuGouLyrics(title, artist, durationSec)
        LyricsProvider.Innertube -> fetchInnertubeLyrics(videoId)
        LyricsProvider.Auto -> null
    }

    /**
     * Resolves lyrics starting with the user's selected default provider, and if fallback is enabled
     * (or Auto is selected), races providers concurrently and prioritizes syllable/word-synced lyrics
     * (BetterLyrics/Unison, YouLyPlus/LyricsPlus, Paxsenix) when preferWordSync is enabled.
     */
    suspend fun resolveLyricsWithFallback(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        preferredProvider: LyricsProvider,
        fallbackEnabled: Boolean,
        existingFixed: String? = null,
        existingSynced: String? = null,
        preferWordSync: Boolean = true
    ): ResolvedLyrics = withContext(Dispatchers.IO) {
        val cacheKey = "${videoId.trim()}|${title.trim().lowercase()}|${artist.trim().lowercase()}|${preferredProvider.name}|$fallbackEnabled|$preferWordSync"
        memoryCache[cacheKey]?.let { cached ->
            val cachedHasWordSync = hasWordLevelTimestamps(cached.synced)
            if ((!preferWordSync || cachedHasWordSync) && (!cached.synced.isNullOrBlank() || !cached.fixed.isNullOrBlank())) {
                return@withContext cached
            }
        }

        val durationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else 0

        // BitChord priority order: Word-sync rich providers first (Unison/BetterLyrics, YouLyPlus, Paxsenix),
        // followed by LrcLib, KuGou, and Innertube.
        val defaultOrder = if (preferWordSync) {
            listOf(
                LyricsProvider.Unison,
                LyricsProvider.YouLyPlus,
                LyricsProvider.Paxsenix,
                LyricsProvider.LrcLib,
                LyricsProvider.KuGou,
                LyricsProvider.Innertube
            )
        } else {
            listOf(
                LyricsProvider.LrcLib,
                LyricsProvider.Unison,
                LyricsProvider.YouLyPlus,
                LyricsProvider.Paxsenix,
                LyricsProvider.KuGou,
                LyricsProvider.Innertube
            )
        }

        var resolvedFixed = existingFixed?.takeIf { it.isNotBlank() }
        var resolvedSynced = existingSynced?.takeIf { it.isNotBlank() }
        var resolvedProviderName: String? = null

        // 1. If a specific provider is selected, try it first quickly
        if (preferredProvider != LyricsProvider.Auto) {
            val primaryResult = runCatching {
                fetchFromSingleProvider(
                    provider = preferredProvider,
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec
                )
            }.getOrNull()

            if (primaryResult != null) {
                if (!primaryResult.synced.isNullOrBlank() &&
                    (resolvedSynced.isNullOrBlank() || (preferWordSync && !hasWordLevelTimestamps(resolvedSynced) && hasWordLevelTimestamps(primaryResult.synced)))
                ) {
                    resolvedSynced = primaryResult.synced
                    resolvedProviderName = primaryResult.providerName ?: preferredProvider.title
                }
                if (resolvedFixed.isNullOrBlank() && !primaryResult.fixed.isNullOrBlank()) {
                    resolvedFixed = primaryResult.fixed
                    if (resolvedProviderName == null) {
                        resolvedProviderName = primaryResult.providerName ?: preferredProvider.title
                    }
                }
            }
        }

        // 2. If Auto or fallback is enabled and we still need synced lyrics (or word-level synced lyrics), race providers concurrently
        val needsWordSyncUpgrade = preferWordSync && !hasWordLevelTimestamps(resolvedSynced)
        val shouldRunFallback = preferredProvider == LyricsProvider.Auto || fallbackEnabled
        if (shouldRunFallback && (resolvedSynced.isNullOrBlank() || resolvedFixed.isNullOrBlank() || needsWordSyncUpgrade)) {
            val remainingProviders = if (preferredProvider == LyricsProvider.Auto) {
                defaultOrder
            } else {
                defaultOrder.filter { it != preferredProvider }
            }

            val parallelResults = coroutineScope {
                remainingProviders.map { provider ->
                    async(Dispatchers.IO) {
                        provider to runCatching {
                            fetchFromSingleProvider(
                                provider = provider,
                                videoId = videoId,
                                title = title,
                                artist = artist,
                                album = album,
                                durationSec = durationSec
                            )
                        }.getOrNull()
                    }
                }.awaitAll()
            }

            // First pass: look for true word-by-word / syllable-synced lyrics if preferWordSync is true
            if (preferWordSync && !hasWordLevelTimestamps(resolvedSynced)) {
                for ((provider, result) in parallelResults) {
                    if (result == null) continue
                    if (hasWordLevelTimestamps(result.synced)) {
                        resolvedSynced = result.synced
                        resolvedProviderName = result.providerName ?: provider.title
                        if (resolvedFixed.isNullOrBlank() && !result.fixed.isNullOrBlank()) {
                            resolvedFixed = result.fixed
                        }
                        break
                    }
                }
            }

            // Second pass: fill any remaining missing synced or fixed lyrics
            for ((provider, result) in parallelResults) {
                if (result == null) continue
                if (resolvedSynced.isNullOrBlank() && !result.synced.isNullOrBlank()) {
                    resolvedSynced = result.synced
                    resolvedProviderName = result.providerName ?: provider.title
                }
                if (resolvedFixed.isNullOrBlank() && !result.fixed.isNullOrBlank()) {
                    resolvedFixed = result.fixed
                    if (resolvedProviderName == null) {
                        resolvedProviderName = result.providerName ?: provider.title
                    }
                }
                if (!resolvedSynced.isNullOrBlank() && !resolvedFixed.isNullOrBlank()) break
            }
        }

        if (resolvedFixed.isNullOrBlank() && !resolvedSynced.isNullOrBlank()) {
            resolvedFixed = extractPlainFromLrc(resolvedSynced).takeIf { it.isNotBlank() }
        }

        ResolvedLyrics(
            fixed = resolvedFixed.orEmpty(),
            synced = resolvedSynced.orEmpty(),
            providerName = resolvedProviderName
        ).also { finalResult ->
            if (!finalResult.synced.isNullOrBlank() || !finalResult.fixed.isNullOrBlank()) {
                memoryCache[cacheKey] = finalResult
                val cleanProvider = finalResult.providerName
                    ?: if (preferredProvider != LyricsProvider.Auto) preferredProvider.shortName
                    else if (hasWordLevelTimestamps(finalResult.synced)) "Unison"
                    else if (!finalResult.synced.isNullOrBlank()) "LrcLib"
                    else "YouTube Music"
                resolvedProvidersByVideoId[videoId.trim()] = cleanProvider
            }
        }
    }

    /**
     * Translates a list of lyric lines into [targetLangCode] (e.g. "en", "es", "ja", "ko", "hi")
     * using Google Translate's GTX endpoint and returns a map of [lineIndex -> translatedLine].
     */
    suspend fun translateLyricsLines(
        videoId: String,
        lines: List<String>,
        targetLangCode: String
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty() || targetLangCode.isBlank()) return@withContext emptyMap()
        val cacheKey = "${videoId.trim()}|${targetLangCode.trim().lowercase()}|${lines.size}|${lines.hashCode()}"
        translationCache[cacheKey]?.let { return@withContext it }

        val indexedNonBlank = lines.mapIndexedNotNull { idx, text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == "♪" || trimmed == "..." || trimmed.equals("Instrumental", ignoreCase = true)) {
                null
            } else {
                idx to trimmed
            }
        }
        if (indexedNonBlank.isEmpty()) return@withContext emptyMap()

        val result = mutableMapOf<Int, String>()
        val chunkSize = 28
        for (chunk in indexedNonBlank.chunked(chunkSize)) {
            val joinedSource = chunk.joinToString("\n") { it.second }
            val encodedQ = encodeParam(joinedSource)
            val encodedTl = encodeParam(targetLangCode.trim())
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$encodedTl&dt=t&q=$encodedQ"
            val raw = httpGet(url, connectTimeoutMs = 3500, readTimeoutMs = 4500) ?: continue
            val translatedChunk = runCatching {
                val rootArray = JSONArray(raw)
                val sentencesArray = rootArray.optJSONArray(0) ?: return@runCatching null
                val sb = StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val seg = sentencesArray.optJSONArray(i) ?: continue
                    val part = seg.optString(0)
                    if (!part.isNullOrEmpty() && part != "null") {
                        sb.append(part)
                    }
                }
                sb.toString()
            }.getOrNull() ?: continue

            val splitLines = translatedChunk.split("\n")
            for (i in chunk.indices) {
                val (originalIdx, originalText) = chunk[i]
                val translatedLine = splitLines.getOrNull(i)?.trim().orEmpty()
                if (translatedLine.isNotBlank() && !translatedLine.equals(originalText, ignoreCase = true)) {
                    result[originalIdx] = translatedLine
                }
            }
        }

        if (result.isNotEmpty()) {
            translationCache[cacheKey] = result
        }
        result
    }

    private fun containsNonLatinLetters(text: String): Boolean {
        for (ch in text) {
            if (ch.isLetter() && ch.code > 0x024F) return true
        }
        return false
    }

    private fun transliterateToLatinOnDevice(text: String): String? {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val transliterated = runCatching {
                android.icu.text.Transliterator.getInstance("Any-Latin").transliterate(text)?.trim()
            }.getOrNull()
            if (!transliterated.isNullOrBlank() && !transliterated.equals(text, ignoreCase = true)) {
                return transliterated
            }
        }
        return null
    }

    /**
     * Romanizes / transliterates non-Latin lyric lines (e.g., Japanese Romaji, Korean Romanization,
     * Chinese Pinyin, Hindi/Devanagari, Cyrillic, Arabic, Thai, etc.) into phonetic Latin script
     * for BitChord's Earth/Globe (🌐) Romanize Lyrics toggle.
     * Combines Google Translate's `dt=rm` romanization endpoint with Android ICU `Any-Latin` fallback.
     */
    suspend fun romanizeLyricsLines(
        videoId: String,
        lines: List<String>
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyMap()
        val cacheKey = "${videoId.trim()}|rm|${lines.size}|${lines.hashCode()}"
        romanizationCache[cacheKey]?.let { return@withContext it }

        val indexedNonLatin = lines.mapIndexedNotNull { idx, text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() ||
                trimmed == "♪" ||
                trimmed == "..." ||
                trimmed.equals("Instrumental", ignoreCase = true) ||
                !containsNonLatinLetters(trimmed)
            ) {
                null
            } else {
                idx to trimmed
            }
        }
        if (indexedNonLatin.isEmpty()) return@withContext emptyMap()

        val result = mutableMapOf<Int, String>()
        val chunkSize = 20
        for (chunk in indexedNonLatin.chunked(chunkSize)) {
            val joinedSource = chunk.joinToString("\n") { it.second }
            val encodedQ = encodeParam(joinedSource)
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&dt=rm&q=$encodedQ"
            val raw = httpGet(url, connectTimeoutMs = 3000, readTimeoutMs = 4000)
            val romanizedLinesFromApi: List<String>? = if (raw != null) {
                runCatching {
                    val rootArray = JSONArray(raw)
                    val sentencesArray = rootArray.optJSONArray(0) ?: return@runCatching null
                    val sb = StringBuilder()
                    for (i in 0 until sentencesArray.length()) {
                        val seg = sentencesArray.optJSONArray(i) ?: continue
                        val srcRom = seg.optString(3)
                        if (!srcRom.isNullOrEmpty() && srcRom != "null") {
                            sb.append(srcRom)
                        }
                    }
                    sb.toString().takeIf { it.isNotBlank() }?.split("\n")
                }.getOrNull()
            } else null

            if (romanizedLinesFromApi != null && romanizedLinesFromApi.size == chunk.size) {
                for (i in chunk.indices) {
                    val (originalIdx, originalText) = chunk[i]
                    val romLine = romanizedLinesFromApi[i].trim()
                    val resolved = romLine.takeIf { it.isNotBlank() && !it.equals(originalText, ignoreCase = true) }
                        ?: transliterateToLatinOnDevice(originalText)
                    if (!resolved.isNullOrBlank() && !resolved.equals(originalText, ignoreCase = true)) {
                        result[originalIdx] = resolved
                    }
                }
            } else {
                for ((originalIdx, originalText) in chunk) {
                    val singleEncoded = encodeParam(originalText)
                    val singleUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&dt=rm&q=$singleEncoded"
                    val singleRaw = httpGet(singleUrl, connectTimeoutMs = 2000, readTimeoutMs = 2500)
                    val apiSingle = if (singleRaw != null) {
                        runCatching {
                            val rootArray = JSONArray(singleRaw)
                            val sentencesArray = rootArray.optJSONArray(0) ?: return@runCatching null
                            val sb = StringBuilder()
                            for (i in 0 until sentencesArray.length()) {
                                val seg = sentencesArray.optJSONArray(i) ?: continue
                                val srcRom = seg.optString(3)
                                if (!srcRom.isNullOrEmpty() && srcRom != "null") {
                                    sb.append(srcRom)
                                }
                            }
                            sb.toString().trim().takeIf { it.isNotBlank() }
                        }.getOrNull()
                    } else null

                    val resolved = apiSingle?.takeIf { !it.equals(originalText, ignoreCase = true) }
                        ?: transliterateToLatinOnDevice(originalText)
                    if (!resolved.isNullOrBlank() && !resolved.equals(originalText, ignoreCase = true)) {
                        result[originalIdx] = resolved
                    }
                }
            }
        }

        if (result.isNotEmpty()) {
            romanizationCache[cacheKey] = result
        }
        result
    }
}
