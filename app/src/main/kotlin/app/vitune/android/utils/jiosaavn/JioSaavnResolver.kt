package app.vitune.android.utils.jiosaavn

import android.util.Log

class JioSaavnResolver(
    private val client: JioSaavnClient = JioSaavnClient()
) {
    suspend fun resolve(query: TrackQuery, kbps: Int = 320): JioSaavnMatchResult? {
        val label = "${query.artist} - ${query.title}"

        val searchQueries = listOf(
            "${query.artist} ${query.title}".trim(),
            "${query.artist.split(Regex("\\s*(,|&| x )\\s*")).first().trim()} ${query.title}".trim(),
        ).filter { it.isNotBlank() }.distinct()

        var candidatesSeen = 0

        for (searchQuery in searchQueries) {
            when (val outcome = client.search(searchQuery, SEARCH_LIMIT)) {
                JioSaavnSearchOutcome.RateLimited -> {
                    Log.i(TAG, "skip '$label': search rate-limited")
                    return null
                }
                is JioSaavnSearchOutcome.Failure -> {
                    Log.d(TAG, "search failed: ${outcome.message}")
                    return null
                }
                is JioSaavnSearchOutcome.Success -> {
                    candidatesSeen += outcome.songs.size
                    val match = JioSaavnMatcher.best(query, outcome.songs) ?: continue
                    val url = jioSaavnVariantUrl(match.media.url, kbps)

                    when (val probe = client.isPlayable(url)) {
                        JioSaavnProbeOutcome.Playable -> Unit
                        JioSaavnProbeOutcome.Unavailable -> {
                            Log.d(TAG, "$kbps kbps candidate unavailable for ${match.song.id}")
                            continue
                        }
                        JioSaavnProbeOutcome.RateLimited -> {
                            Log.i(TAG, "probe rate-limited for ${match.song.id}")
                            return null
                        }
                        is JioSaavnProbeOutcome.Failure -> {
                            Log.d(TAG, "$kbps kbps probe transport failed: ${probe.message}")
                            return null
                        }
                    }

                    return JioSaavnMatchResult(
                        downloadUrl = url,
                        codec = "aac",
                        bitrateKbps = kbps,
                        sampleRateHz = 44_100,
                        fileExtension = "m4a",
                        confidence = match.confidence,
                        sourceTrackId = match.song.id,
                        coverArtUrl = match.song.image.firstOrNull {
                            it.quality == "500x500" && it.url.startsWith("https://")
                        }?.url
                    )
                }
            }
        }

        Log.i(TAG, "no match for '$label': $candidatesSeen candidate(s) checked")
        return null
    }

    companion object {
        const val SOURCE_ID = "jiosaavn"
        private const val TAG = "JioSaavnResolver"
        private const val SEARCH_LIMIT = 10
    }
}

data class JioSaavnMatchResult(
    val downloadUrl: String,
    val codec: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val fileExtension: String,
    val confidence: Float,
    val sourceTrackId: String,
    val coverArtUrl: String?
)
