package app.vitune.providers.spotify.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyTokenResponse(
    val clientId: String = "",
    val accessToken: String = "",
    val accessTokenExpirationTimestampMs: Long = 0L,
    val isAnonymous: Boolean = false,
    val username: String = "",
    val spT: String = ""
)

@Serializable
data class SpotifyUserProfile(
    val id: String = "",
    @SerialName("display_name")
    val displayName: String? = null
)

@Serializable
data class SpotifyPlaylistsResponse(
    val items: List<SpotifyPlaylist> = emptyList(),
    val next: String? = null,
    val total: Int = 0
)

@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val images: List<SpotifyImage>? = null,
    val owner: SpotifyOwner? = null,
    val tracks: SpotifyPlaylistTracksRef? = null
)

@Serializable
data class SpotifyPlaylistTracksRef(
    val total: Int = 0
)

@Serializable
data class SpotifyImage(
    val url: String,
    val height: Int? = null,
    val width: Int? = null
)

@Serializable
data class SpotifyOwner(
    @SerialName("display_name")
    val displayName: String? = null
)

@Serializable
data class SpotifyPlaylistTracksResponse(
    val items: List<SpotifyPlaylistItem> = emptyList(),
    val next: String? = null,
    val total: Int = 0
)

@Serializable
data class SpotifyPlaylistItem(
    val track: SpotifyTrack? = null
)

@Serializable
data class SpotifySavedTracksResponse(
    val items: List<SpotifySavedTrackItem> = emptyList(),
    val next: String? = null,
    val total: Int = 0
)

@Serializable
data class SpotifySavedTrackItem(
    @SerialName("added_at")
    val addedAt: String? = null,
    val track: SpotifyTrack? = null
)

@Serializable
data class SpotifySearchResponse(
    val tracks: SpotifySearchTracksPage? = null
)

@Serializable
data class SpotifySearchTracksPage(
    val items: List<SpotifyTrack> = emptyList()
)

@Serializable
data class SpotifyTrack(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbumSimple? = null,
    @SerialName("duration_ms")
    val durationMs: Long = 0L,
    val explicit: Boolean = false
)

@Serializable
data class SpotifyAlbumSimple(
    val id: String? = null,
    val name: String? = null,
    val images: List<SpotifyImage>? = null
)

@Serializable
data class SpotifyArtist(
    val id: String = "",
    val name: String = ""
)

@Serializable
data class SpotifyArtistFull(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val followers: Int? = null,
    val genres: List<String> = emptyList()
)

@Serializable
data class SpotifyReleaseItem(
    val id: String = "",
    val name: String = "",
    val albumType: String = "single",
    val releaseDate: String = "",
    val totalTracks: Int = 1,
    val coverUrl: String? = null,
    val artists: List<String> = emptyList(),
    val artistIds: List<String> = emptyList(),
    val explicit: Boolean = false,
    val trackPreviewNames: List<String> = emptyList(),
    val firstTrackName: String? = null
)

@Serializable
data class SpotifyArtistDiscography(
    val artistId: String = "",
    val artistName: String = "",
    val avatarUrl: String? = null,
    val headerImageUrl: String? = null,
    val followers: Int? = null,
    val biography: String? = null,
    val latestRelease: SpotifyReleaseItem? = null,
    val albums: List<SpotifyReleaseItem> = emptyList(),
    val singles: List<SpotifyReleaseItem> = emptyList(),
    val topTracks: List<SpotifyTrack> = emptyList()
)



