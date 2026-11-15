package app.vitune.android.utils.jiosaavn

/** Normalized subset of a JioSaavn search result used by matcher. */
data class JioSaavnSong(
    val id: String,
    val name: String,
    val duration: Int?,
    val explicitContent: Boolean,
    val album: JioSaavnAlbum?,
    val artists: JioSaavnArtists,
    val image: List<JioSaavnImage>,
    val downloadUrl: List<JioSaavnMediaLink>,
    val language: String? = null,
)

data class JioSaavnAlbum(val name: String?)

data class JioSaavnArtists(val primary: List<JioSaavnArtist> = emptyList())

data class JioSaavnArtist(val name: String)

data class JioSaavnImage(val quality: String, val url: String)

data class JioSaavnMediaLink(val quality: String, val url: String)

sealed interface JioSaavnSearchOutcome {
    data class Success(val songs: List<JioSaavnSong>) : JioSaavnSearchOutcome
    data object RateLimited : JioSaavnSearchOutcome
    data class Failure(val message: String) : JioSaavnSearchOutcome
}

sealed interface JioSaavnProbeOutcome {
    data object Playable : JioSaavnProbeOutcome
    data object Unavailable : JioSaavnProbeOutcome
    data object RateLimited : JioSaavnProbeOutcome
    data class Failure(val message: String) : JioSaavnProbeOutcome
}

data class JioSaavnMatch(
    val song: JioSaavnSong,
    val media: JioSaavnMediaLink,
    val confidence: Float
)

data class TrackQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean? = null
)
