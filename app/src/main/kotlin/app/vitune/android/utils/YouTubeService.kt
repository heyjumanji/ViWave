package app.vitune.android.utils

import androidx.media3.common.MediaItem
import app.vitune.android.Database
import app.vitune.android.models.Song
import app.vitune.android.models.SongPlaylistMap
import app.vitune.android.transaction
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

object YouTubeService {
    private val positionCounter = AtomicInteger((System.currentTimeMillis() / 1000).toInt())
    private val unwantedKeywords = listOf("remix", "live", "cover", "slowed", "sped up", "karaoke", "instrumental")
    private val nonAlnumRegex = Regex("[^a-z0-9\\p{L}\\p{N}]+")
    private val parensDecorationsRegex = Regex(
        "\\((?:feat\\.?|ft\\.?|with|prod\\.?|from|official|audio|video|lyric|lyrics|visualizer|remaster(?:ed)?|live|explicit|clean)[^)]*\\)",
        RegexOption.IGNORE_CASE
    )
    private val bracketsDecorationsRegex = Regex(
        "\\[(?:feat\\.?|ft\\.?|with|prod\\.?|from|official|audio|video|lyric|lyrics|visualizer|remaster(?:ed)?|live|explicit|clean)[^\\]]*\\]",
        RegexOption.IGNORE_CASE
    )
    private val dashDecorationsRegex = Regex(
        "\\s+-\\s+(?:feat\\.?|ft\\.?|with|remaster(?:ed)?|from\\s+|single|version|radio\\s+edit|official).*$",
        RegexOption.IGNORE_CASE
    )

    internal fun normalizeKey(text: String?): String =
        text.orEmpty()
            .lowercase()
            .replace(nonAlnumRegex, " ")
            .trim()

    internal fun stripTitleDecorations(title: String?): String {
        if (title.isNullOrBlank()) return ""
        return title
            .replace(parensDecorationsRegex, " ")
            .replace(bracketsDecorationsRegex, " ")
            .replace(dashDecorationsRegex, " ")
            .trim()
    }

    private fun scoreCandidate(
        candidate: Innertube.SongItem,
        index: Int,
        targetFullTitle: String,
        targetCoreTitle: String,
        targetTitleWords: Set<String>,
        targetArtistWords: Set<String>,
        rawCleanTitle: String
    ): Int {
        val rawCandTitle = candidate.info?.name.orEmpty()
        val candFullTitle = normalizeKey(rawCandTitle)
        if (candFullTitle.isBlank()) return Int.MIN_VALUE / 2
        val candCoreTitle = normalizeKey(stripTitleDecorations(rawCandTitle)).ifBlank { candFullTitle }
        val candTitleWords = candCoreTitle.split(' ').filter { it.isNotBlank() }.toSet()

        var score = 0

        // Title matching
        when {
            candFullTitle == targetFullTitle || candCoreTitle == targetCoreTitle -> score += 120
            candCoreTitle.contains(targetCoreTitle) || targetCoreTitle.contains(candCoreTitle) -> {
                val overlap = targetTitleWords.intersect(candTitleWords).size
                if (overlap > 0) score += 75 else score -= 60
            }
            else -> {
                val overlap = targetTitleWords.intersect(candTitleWords).size
                if (overlap > 0 && targetTitleWords.isNotEmpty()) {
                    score += (overlap * 60) / targetTitleWords.size
                } else {
                    // Completely different song title (e.g., another track from the same album)
                    score -= 150
                }
            }
        }

        // Artist matching
        if (targetArtistWords.isNotEmpty()) {
            val candArtistsText = candidate.authors?.joinToString(" ") { it.name.orEmpty() }.orEmpty()
            val candArtistWords = normalizeKey(candArtistsText).split(' ').filter { it.isNotBlank() }.toSet()
            val matchedArtistWords = targetArtistWords.count { it in candArtistWords }
            if (matchedArtistWords == targetArtistWords.size) {
                score += 60
            } else if (matchedArtistWords > 0) {
                score += 30
            } else {
                score -= 40
            }
        }

        // Penalize remixes/live/covers if not requested in the original title
        val hasUnwanted = unwantedKeywords.any { kw ->
            rawCandTitle.contains(kw, ignoreCase = true) && !rawCleanTitle.contains(kw, ignoreCase = true)
        }
        if (hasUnwanted) {
            score -= 80
        }

        // Slight preference for higher search rank when scores are equal
        score -= index * 2
        return score
    }

    private suspend fun findBestMatchMediaItem(track: CsvParser.Track): MediaItem? {
        val cleanTitle = track.title.replace('\u00A0', ' ').trim()
        val cleanArtist = track.artist.replace('\u00A0', ' ').trim()
        if (cleanTitle.isBlank()) return null

        val primaryQuery = if (cleanArtist.isNotBlank()) {
            "$cleanTitle $cleanArtist"
        } else {
            cleanTitle
        }

        val response = Innertube.searchPage(
            body = SearchBody(query = primaryQuery, params = Innertube.SearchFilter.Song.value),
            fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
        )?.getOrNull()

        var items = response?.items.orEmpty()
        if (items.isEmpty() && cleanArtist.isNotBlank()) {
            val fallbackResponse = Innertube.searchPage(
                body = SearchBody(query = cleanTitle, params = Innertube.SearchFilter.Song.value),
                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
            )?.getOrNull()
            items = fallbackResponse?.items.orEmpty()
        }

        if (items.isEmpty()) return null

        val targetFullTitle = normalizeKey(cleanTitle)
        val targetCoreTitle = normalizeKey(stripTitleDecorations(cleanTitle)).ifBlank { targetFullTitle }
        val targetTitleWords = targetCoreTitle.split(' ').filter { it.isNotBlank() }.toSet()
        val targetArtistWords = normalizeKey(cleanArtist).split(' ').filter { it.isNotBlank() }.toSet()

        val candidates = items.take(10)
        val bestCandidate = candidates
            .mapIndexed { index, item ->
                item to scoreCandidate(
                    candidate = item,
                    index = index,
                    targetFullTitle = targetFullTitle,
                    targetCoreTitle = targetCoreTitle,
                    targetTitleWords = targetTitleWords,
                    targetArtistWords = targetArtistWords,
                    rawCleanTitle = cleanTitle
                )
            }
            .maxByOrNull { it.second }
            ?.first
            ?: items.first()

        return bestCandidate.asMediaItem
    }

    suspend fun resolveAndInsertSong(
        track: CsvParser.Track
    ): String? = withContext(Dispatchers.IO) {
        val item = findBestMatchMediaItem(track) ?: return@withContext null
        val songId = item.mediaId
        transaction {
            Database.insert(item)

            val songObj = Song(
                id = songId,
                title = item.mediaMetadata.title.toString(),
                artistsText = item.mediaMetadata.artist.toString(),
                durationText = item.mediaMetadata.extras?.getString("durationText") ?: "0:00",
                explicit = item.mediaMetadata.extras?.getBoolean("explicit") ?: false,
                thumbnailUrl = item.mediaMetadata.artworkUri?.toString()
            )
            Database.insert(songObj)
        }
        songId
    }

    suspend fun fetchBestMatchAndSave(
        track: CsvParser.Track,
        playlistId: Long,
        position: Int? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val songId = resolveAndInsertSong(track) ?: return@withContext false
        val resolvedPosition = position ?: positionCounter.incrementAndGet()
        transaction {
            Database.insert(
                SongPlaylistMap(
                    songId = songId,
                    playlistId = playlistId,
                    position = resolvedPosition
                )
            )
        }
        true
    }

    suspend fun resolveAndLikeSong(
        track: CsvParser.Track,
        likedAtTimestamp: Long = System.currentTimeMillis()
    ): String? = withContext(Dispatchers.IO) {
        val item = findBestMatchMediaItem(track) ?: return@withContext null
        val songId = item.mediaId
        transaction {
            Database.insert(item)

            val songObj = Song(
                id = songId,
                title = item.mediaMetadata.title.toString(),
                artistsText = item.mediaMetadata.artist.toString(),
                durationText = item.mediaMetadata.extras?.getString("durationText") ?: "0:00",
                explicit = item.mediaMetadata.extras?.getBoolean("explicit") ?: false,
                thumbnailUrl = item.mediaMetadata.artworkUri?.toString(),
                likedAt = likedAtTimestamp
            )
            Database.insert(songObj)
            Database.like(songId = songId, likedAt = likedAtTimestamp)
        }
        songId
    }

    suspend fun fetchBestMatchAndLike(
        track: CsvParser.Track,
        likedAtTimestamp: Long = System.currentTimeMillis()
    ): Boolean = resolveAndLikeSong(track, likedAtTimestamp) != null
}
