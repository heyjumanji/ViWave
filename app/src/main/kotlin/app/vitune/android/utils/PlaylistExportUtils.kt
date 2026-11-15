package app.vitune.android.utils

import android.content.Context
import android.net.Uri
import app.vitune.android.Database
import app.vitune.android.models.Playlist
import app.vitune.android.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PlaylistExportUtils {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class ExportedTrackMetadata(
        val position: Int,
        val id: String,
        val title: String,
        val artists: String,
        val duration: String,
        val explicit: Boolean,
        val likedAt: Long? = null,
        val totalPlayTimeMs: Long = 0L,
        val thumbnailUrl: String? = null,
        val youtubeUrl: String
    )

    @Serializable
    data class ExportedPlaylistDocument(
        val playlistName: String,
        val browseId: String? = null,
        val exportedAtIso: String,
        val exportedAtTimestampMs: Long,
        val totalTracks: Int,
        val tracks: List<ExportedTrackMetadata>
    )

    @Serializable
    data class ExportedLibraryBundle(
        val exportedAtIso: String,
        val exportedAtTimestampMs: Long,
        val likedSongs: ExportedPlaylistDocument,
        val playlists: List<ExportedPlaylistDocument>
    )

    fun suggestedFileName(name: String, extension: String): String {
        val sanitized = name
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(40)
            .ifBlank { "Playlist" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val cleanExt = extension.removePrefix(".")
        return "ViWaves_${sanitized}_$timestamp.$cleanExt"
    }

    private fun escapeCsvField(value: String?): String {
        val raw = value.orEmpty()
        val needsQuotes = raw.contains(',') || raw.contains('"') || raw.contains('\n') || raw.contains('\r')
        return if (needsQuotes) {
            "\"${raw.replace("\"", "\"\"")}\""
        } else {
            raw
        }
    }

    private fun buildYoutubeUrl(songId: String): String =
        if (songId.startsWith("local:")) "" else "https://music.youtube.com/watch?v=$songId"

    fun buildPlaylistJson(
        playlistName: String,
        songs: List<Song>,
        browseId: String? = null
    ): String {
        val now = System.currentTimeMillis()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val doc = ExportedPlaylistDocument(
            playlistName = playlistName,
            browseId = browseId,
            exportedAtIso = isoFormat.format(Date(now)),
            exportedAtTimestampMs = now,
            totalTracks = songs.size,
            tracks = songs.mapIndexed { index, song ->
                ExportedTrackMetadata(
                    position = index + 1,
                    id = song.id,
                    title = song.title,
                    artists = song.artistsText.orEmpty(),
                    duration = song.durationText.orEmpty(),
                    explicit = song.explicit,
                    likedAt = song.likedAt,
                    totalPlayTimeMs = song.totalPlayTimeMs,
                    thumbnailUrl = song.thumbnailUrl,
                    youtubeUrl = buildYoutubeUrl(song.id)
                )
            }
        )
        return prettyJson.encodeToString(doc)
    }

    fun buildPlaylistCsv(
        playlistName: String,
        songs: List<Song>
    ): String {
        val sb = StringBuilder(songs.size * 96 + 128)
        // First 4 columns (Title, Artist, Album/Playlist, Duration) also match CsvParser.parse() for easy re-import
        sb.append("Title,Artist,Playlist,Duration,Position,Song ID,Explicit,Liked At,Thumbnail URL,YouTube URL\n")
        songs.forEachIndexed { index, song ->
            sb.append(escapeCsvField(song.title)).append(',')
            sb.append(escapeCsvField(song.artistsText)).append(',')
            sb.append(escapeCsvField(playlistName)).append(',')
            sb.append(escapeCsvField(song.durationText)).append(',')
            sb.append(index + 1).append(',')
            sb.append(escapeCsvField(song.id)).append(',')
            sb.append(song.explicit).append(',')
            sb.append(song.likedAt ?: "").append(',')
            sb.append(escapeCsvField(song.thumbnailUrl)).append(',')
            sb.append(escapeCsvField(buildYoutubeUrl(song.id))).append('\n')
        }
        return sb.toString()
    }

    suspend fun writePlaylistExportToUri(
        context: Context,
        uri: Uri,
        playlistName: String,
        songs: List<Song>,
        asJson: Boolean,
        browseId: String? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val content = if (asJson) {
                buildPlaylistJson(playlistName = playlistName, songs = songs, browseId = browseId)
            } else {
                buildPlaylistCsv(playlistName = playlistName, songs = songs)
            }
            context.applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: error("Could not open destination file for writing")
            songs.size
        }
    }

    suspend fun exportFullLibraryMetadataToUri(
        context: Context,
        uri: Uri,
        asJson: Boolean
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val favorites = Database.favorites().firstOrNull().orEmpty()
            val playlists: List<Playlist> = Database.playlists().firstOrNull().orEmpty()

            if (asJson) {
                val now = System.currentTimeMillis()
                val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(now))
                val playlistDocs = playlists.map { playlist ->
                    val pSongs = Database.playlistSongs(playlist.id).firstOrNull().orEmpty()
                    ExportedPlaylistDocument(
                        playlistName = playlist.name,
                        browseId = playlist.browseId,
                        exportedAtIso = iso,
                        exportedAtTimestampMs = now,
                        totalTracks = pSongs.size,
                        tracks = pSongs.mapIndexed { idx, song ->
                            ExportedTrackMetadata(
                                position = idx + 1,
                                id = song.id,
                                title = song.title,
                                artists = song.artistsText.orEmpty(),
                                duration = song.durationText.orEmpty(),
                                explicit = song.explicit,
                                likedAt = song.likedAt,
                                totalPlayTimeMs = song.totalPlayTimeMs,
                                thumbnailUrl = song.thumbnailUrl,
                                youtubeUrl = buildYoutubeUrl(song.id)
                            )
                        }
                    )
                }
                val likedDoc = ExportedPlaylistDocument(
                    playlistName = "Favorites (Liked Songs)",
                    browseId = "spotify:liked",
                    exportedAtIso = iso,
                    exportedAtTimestampMs = now,
                    totalTracks = favorites.size,
                    tracks = favorites.mapIndexed { idx, song ->
                        ExportedTrackMetadata(
                            position = idx + 1,
                            id = song.id,
                            title = song.title,
                            artists = song.artistsText.orEmpty(),
                            duration = song.durationText.orEmpty(),
                            explicit = song.explicit,
                            likedAt = song.likedAt,
                            totalPlayTimeMs = song.totalPlayTimeMs,
                            thumbnailUrl = song.thumbnailUrl,
                            youtubeUrl = buildYoutubeUrl(song.id)
                        )
                    }
                )
                val bundle = ExportedLibraryBundle(
                    exportedAtIso = iso,
                    exportedAtTimestampMs = now,
                    likedSongs = likedDoc,
                    playlists = playlistDocs
                )
                val payload = prettyJson.encodeToString(bundle)
                context.applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("Could not open destination file for writing")
                favorites.size + playlistDocs.sumOf { it.totalTracks }
            } else {
                val sb = StringBuilder()
                sb.append("Title,Artist,Playlist,Duration,Position,Song ID,Explicit,Liked At,Thumbnail URL,YouTube URL\n")
                var totalExported = 0
                favorites.forEachIndexed { idx, song ->
                    sb.append(escapeCsvField(song.title)).append(',')
                    sb.append(escapeCsvField(song.artistsText)).append(',')
                    sb.append(escapeCsvField("Favorites")).append(',')
                    sb.append(escapeCsvField(song.durationText)).append(',')
                    sb.append(idx + 1).append(',')
                    sb.append(escapeCsvField(song.id)).append(',')
                    sb.append(song.explicit).append(',')
                    sb.append(song.likedAt ?: "").append(',')
                    sb.append(escapeCsvField(song.thumbnailUrl)).append(',')
                    sb.append(escapeCsvField(buildYoutubeUrl(song.id))).append('\n')
                    totalExported++
                }
                for (playlist in playlists) {
                    val pSongs = Database.playlistSongs(playlist.id).firstOrNull().orEmpty()
                    pSongs.forEachIndexed { idx, song ->
                        sb.append(escapeCsvField(song.title)).append(',')
                        sb.append(escapeCsvField(song.artistsText)).append(',')
                        sb.append(escapeCsvField(playlist.name)).append(',')
                        sb.append(escapeCsvField(song.durationText)).append(',')
                        sb.append(idx + 1).append(',')
                        sb.append(escapeCsvField(song.id)).append(',')
                        sb.append(song.explicit).append(',')
                        sb.append(song.likedAt ?: "").append(',')
                        sb.append(escapeCsvField(song.thumbnailUrl)).append(',')
                        sb.append(escapeCsvField(buildYoutubeUrl(song.id))).append('\n')
                        totalExported++
                    }
                }
                context.applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("Could not open destination file for writing")
                totalExported
            }
        }
    }
}
