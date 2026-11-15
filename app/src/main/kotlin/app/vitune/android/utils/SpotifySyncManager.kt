package app.vitune.android.utils

import android.webkit.CookieManager
import app.vitune.android.Database
import app.vitune.android.internal
import app.vitune.android.models.Playlist
import app.vitune.android.models.Song
import app.vitune.android.models.SongPlaylistMap
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.SpotifySyncLogEntry
import app.vitune.android.transaction
import app.vitune.providers.spotify.models.SpotifyTrack
import app.vitune.providers.spotify.requests.SpotifyApi
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

sealed interface SpotifySyncIndicatorState {
    data object Idle : SpotifySyncIndicatorState
    data class Syncing(val message: String) : SpotifySyncIndicatorState
    data class Success(
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SpotifySyncIndicatorState
    data class Error(
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SpotifySyncIndicatorState
}

data class SpotifyBackgroundSyncState(
    val isActive: Boolean = false,
    val taskTitle: String = "",
    val statusMessage: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val activePlaylistId: Long? = null,
    val completionMessage: String? = null,
    val errorMessage: String? = null
)

object SpotifySyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("SpotifySyncManager"))
    private val tokenMutex = Mutex()
    private val historyMutex = Mutex()

    private val _syncIndicatorState = MutableStateFlow<SpotifySyncIndicatorState>(SpotifySyncIndicatorState.Idle)
    val syncIndicatorState: StateFlow<SpotifySyncIndicatorState> = _syncIndicatorState.asStateFlow()

    private val _backgroundSyncState = MutableStateFlow(SpotifyBackgroundSyncState())
    val backgroundSyncState: StateFlow<SpotifyBackgroundSyncState> = _backgroundSyncState.asStateFlow()

    private val _syncHistory = MutableStateFlow<List<SpotifySyncLogEntry>>(emptyList())
    val syncHistory: StateFlow<List<SpotifySyncLogEntry>> = _syncHistory.asStateFlow()

    init {
        scope.launch {
            runCatching {
                _syncHistory.value = DataPreferences.spotifySyncHistory
            }
        }
    }

    fun dismissBackgroundSyncResult() {
        val current = _backgroundSyncState.value
        if (!current.isActive) {
            _backgroundSyncState.value = SpotifyBackgroundSyncState()
        }
    }

    private fun showAppToast(message: String) {
        scope.launch(Dispatchers.Main) {
            runCatching {
                app.vitune.android.Dependencies.application.applicationContext.toast(message)
            }
        }
    }

    fun refreshHistoryFromPreferences() {
        runCatching {
            val stored = DataPreferences.spotifySyncHistory
            if (stored.isNotEmpty() || _syncHistory.value.isEmpty()) {
                _syncHistory.value = stored
            }
        }
    }

    suspend fun recordSyncEvent(
        songTitle: String,
        artistName: String,
        action: String,
        success: Boolean,
        details: String
    ) {
        historyMutex.withLock {
            val now = System.currentTimeMillis()
            val newEntry = SpotifySyncLogEntry(
                id = now,
                timestamp = now,
                songTitle = songTitle,
                artistName = artistName,
                action = action,
                success = success,
                details = details
            )
            val currentList = runCatching { DataPreferences.spotifySyncHistory }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: _syncHistory.value

            val updated = (listOf(newEntry) + currentList).take(100)
            _syncHistory.value = updated
            runCatching {
                DataPreferences.spotifySyncHistory = updated
            }
        }
    }

    fun clearSyncHistory() {
        scope.launch {
            historyMutex.withLock {
                _syncHistory.value = emptyList()
                runCatching {
                    DataPreferences.spotifySyncHistory = emptyList()
                }
            }
        }
    }

    fun getStoredCookie(): String {
        return DataPreferences.spotifySpDcCookie.trim()
    }

    fun isLoggedIn(): Boolean = getStoredCookie().isNotBlank()

    fun isAutoSyncLikedSongsEnabled(): Boolean {
        return DataPreferences.getBoolean("spotifyAutoSyncLikedSongs", DataPreferences.spotifyAutoSyncLikedSongs)
    }

    fun isSyncPlaybackEnabled(): Boolean {
        return DataPreferences.getBoolean("spotifySyncPlayback", DataPreferences.spotifySyncPlayback)
    }

    @Volatile
    private var lastSyncedPlaySongId: String = ""
    @Volatile
    private var lastSyncedPlayTimestampMs: Long = 0L
    private var playSyncJob: kotlinx.coroutines.Job? = null

    suspend fun loginWithCookie(rawCookie: String): Boolean = withContext(Dispatchers.IO) {
        val cleanCookie = SpotifyApi.normalizeSpDcCookie(rawCookie)
        if (cleanCookie.isBlank()) return@withContext false

        _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing("Verifying Spotify account...")
        val tokenDetails = SpotifyApi.getTokenDetails(cleanCookie)
        if (tokenDetails == null) {
            _syncIndicatorState.value = SpotifySyncIndicatorState.Error("Spotify login verification failed")
            return@withContext false
        }

        val profile = SpotifyApi.getCurrentUserProfile(tokenDetails.accessToken)
        val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
            ?: profile?.id?.takeIf { it.isNotBlank() }
            ?: tokenDetails.username.takeIf { it.isNotBlank() }
            ?: "Spotify User"

        DataPreferences.spotifySpDcCookie = cleanCookie
        DataPreferences.spotifyAccessToken = tokenDetails.accessToken
        DataPreferences.spotifyTokenExpirationMs = tokenDetails.accessTokenExpirationTimestampMs
        DataPreferences.spotifyUsername = displayName
        if (tokenDetails.clientId.isNotBlank()) {
            DataPreferences.spotifyClientId = tokenDetails.clientId
        }
        if (tokenDetails.spT.isNotBlank()) {
            DataPreferences.spotifySpTCookie = tokenDetails.spT
        }
        SpotifyApi.updateSessionMetadata(
            clientId = tokenDetails.clientId,
            spT = tokenDetails.spT
        )

        recordSyncEvent(
            songTitle = displayName,
            artistName = "Spotify Account",
            action = "Connected Account",
            success = true,
            details = "Logged in and enabled Liked Songs sync"
        )
        _syncIndicatorState.value = SpotifySyncIndicatorState.Success("Connected as $displayName")
        true
    }

    fun logout() {
        DataPreferences.clearSpotifySession()
        SpotifyApi.clearSessionCache()
        _syncIndicatorState.value = SpotifySyncIndicatorState.Idle
        runCatching {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        }
    }

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val cookie = getStoredCookie()
            if (cookie.isBlank()) return@withLock null

            val now = System.currentTimeMillis()
            val cachedToken = DataPreferences.spotifyAccessToken
            val cachedExpiry = DataPreferences.getLong("spotifyTokenExpirationMs", DataPreferences.spotifyTokenExpirationMs)
            val cachedClientId = DataPreferences.getString("spotifyClientId", DataPreferences.spotifyClientId).orEmpty()
            val cachedSpT = DataPreferences.spotifySpTCookie

            if (!forceRefresh && cachedToken.isNotBlank() && cachedClientId.isNotBlank() && now + 60_000L < cachedExpiry) {
                SpotifyApi.updateSessionMetadata(clientId = cachedClientId, spT = cachedSpT)
                return@withLock cachedToken
            }

            val tokenDetails = SpotifyApi.getTokenDetails(cookie) ?: return@withLock null
            DataPreferences.spotifyAccessToken = tokenDetails.accessToken
            DataPreferences.spotifyTokenExpirationMs = tokenDetails.accessTokenExpirationTimestampMs
            if (tokenDetails.clientId.isNotBlank()) {
                DataPreferences.spotifyClientId = tokenDetails.clientId
            }
            if (tokenDetails.spT.isNotBlank()) {
                DataPreferences.spotifySpTCookie = tokenDetails.spT
            }

            if (DataPreferences.getString("spotifyUsername", DataPreferences.spotifyUsername).isNullOrBlank()) {
                val profile = SpotifyApi.getCurrentUserProfile(tokenDetails.accessToken)
                val displayName = profile?.displayName?.takeIf { it.isNotBlank() }
                    ?: profile?.id?.takeIf { it.isNotBlank() }
                    ?: tokenDetails.username.takeIf { it.isNotBlank() }
                if (displayName != null) {
                    DataPreferences.spotifyUsername = displayName
                }
            }

            tokenDetails.accessToken
        }
    }

    /**
     * Automatically resolves a locally liked song on Spotify and adds it to the user's
     * official Spotify Liked Songs library (PUT /v1/me/tracks).
     * Never removes songs from Spotify when unliking locally.
     */
    fun onSongLiked(
        songId: String,
        fallbackTitle: String? = null,
        fallbackArtist: String? = null
    ) {
        if (!isLoggedIn() || !isAutoSyncLikedSongsEnabled()) return

        scope.launch {
            runCatching {
                delay(250L)
                val dbSong = Database.song(songId).firstOrNull()
                val title = dbSong?.title?.takeIf { it.isNotBlank() }
                    ?: fallbackTitle?.takeIf { it.isNotBlank() }
                    ?: return@runCatching
                val artist = dbSong?.artistsText?.takeIf { it.isNotBlank() }
                    ?: fallbackArtist.orEmpty()

                _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing("Syncing '$title' to Spotify...")

                var accessToken = getValidAccessToken()
                if (accessToken == null) {
                    recordSyncEvent(
                        songTitle = title,
                        artistName = artist,
                        action = "Auto-Like Sync",
                        success = false,
                        details = "Spotify session expired; please re-login"
                    )
                    _syncIndicatorState.value = SpotifySyncIndicatorState.Error("Spotify session expired")
                    return@runCatching
                }

                var matchedTrack = SpotifyApi.searchTrack(
                    accessToken = accessToken,
                    title = title,
                    artist = artist
                )

                if (matchedTrack == null) {
                    accessToken = getValidAccessToken(forceRefresh = true) ?: return@runCatching
                    matchedTrack = SpotifyApi.searchTrack(
                        accessToken = accessToken,
                        title = title,
                        artist = artist
                    )
                }

                if (matchedTrack != null) {
                    var saved = SpotifyApi.saveLikedTrack(
                        accessToken = accessToken,
                        trackId = matchedTrack.id
                    )
                    if (!saved) {
                        val refreshed = getValidAccessToken(forceRefresh = true)
                        if (refreshed != null) {
                            saved = SpotifyApi.saveLikedTrack(
                                accessToken = refreshed,
                                trackId = matchedTrack.id
                            )
                        }
                    }

                    val matchedArtist = matchedTrack.artists.firstOrNull()?.name.orEmpty()
                    if (saved) {
                        recordSyncEvent(
                            songTitle = title,
                            artistName = artist,
                            action = "Added to Liked Songs",
                            success = true,
                            details = "Spotify match: ${matchedTrack.name}" +
                                if (matchedArtist.isNotBlank()) " • $matchedArtist" else ""
                        )
                        _syncIndicatorState.value =
                            SpotifySyncIndicatorState.Success("Saved '${matchedTrack.name}' to Spotify ✓")
                    } else {
                        recordSyncEvent(
                            songTitle = title,
                            artistName = artist,
                            action = "Auto-Like Sync",
                            success = false,
                            details = "Found '${matchedTrack.name}' but Spotify rejected save request"
                        )
                        _syncIndicatorState.value =
                            SpotifySyncIndicatorState.Error("Could not save '$title' to Spotify")
                    }
                } else {
                    recordSyncEvent(
                        songTitle = title,
                        artistName = artist,
                        action = "Auto-Like Sync",
                        success = false,
                        details = "No matching track found on Spotify"
                    )
                    _syncIndicatorState.value =
                        SpotifySyncIndicatorState.Error("No Spotify match for '$title'")
                }

                delay(3800L)
                val current = _syncIndicatorState.value
                if (current !is SpotifySyncIndicatorState.Syncing) {
                    _syncIndicatorState.value = SpotifySyncIndicatorState.Idle
                }
            }
        }
    }

    /**
     * Automatically resolves a song being played in the app and syncs it to the user's
     * active/available Spotify Connect session so it appears in Spotify Recently Played / Currently Playing.
     */
    fun onSongPlayed(
        songId: String,
        fallbackTitle: String? = null,
        fallbackArtist: String? = null,
        positionMs: Long = 0L
    ) {
        if (!isLoggedIn() || !isSyncPlaybackEnabled() || songId.isBlank()) return
        val now = System.currentTimeMillis()
        // Avoid re-syncing the exact same song repeatedly when looping or seeking within 3 minutes
        if (songId == lastSyncedPlaySongId && now - lastSyncedPlayTimestampMs < 180_000L) return

        playSyncJob?.cancel()
        playSyncJob = scope.launch {
            runCatching {
                // Wait 5 seconds so rapid track skipping does not spam Spotify
                delay(5000L)
                if (!isLoggedIn() || !isSyncPlaybackEnabled()) return@runCatching
                val afterDelayNow = System.currentTimeMillis()
                if (songId == lastSyncedPlaySongId && afterDelayNow - lastSyncedPlayTimestampMs < 180_000L) return@runCatching

                val dbSong = Database.song(songId).firstOrNull()
                val title = dbSong?.title?.takeIf { it.isNotBlank() }
                    ?: fallbackTitle?.takeIf { it.isNotBlank() }
                    ?: return@runCatching
                val artist = dbSong?.artistsText?.takeIf { it.isNotBlank() }
                    ?: fallbackArtist.orEmpty()

                var accessToken = getValidAccessToken() ?: return@runCatching
                var matchedTrack = SpotifyApi.searchTrack(
                    accessToken = accessToken,
                    title = title,
                    artist = artist
                )
                if (matchedTrack == null) {
                    accessToken = getValidAccessToken(forceRefresh = true) ?: return@runCatching
                    matchedTrack = SpotifyApi.searchTrack(
                        accessToken = accessToken,
                        title = title,
                        artist = artist
                    )
                }

                if (matchedTrack != null) {
                    var (synced, statusDetail) = SpotifyApi.syncPlaybackToSpotify(
                        accessToken = accessToken,
                        trackId = matchedTrack.id,
                        positionMs = positionMs
                    )
                    if (!synced) {
                        val refreshed = getValidAccessToken(forceRefresh = true)
                        if (!refreshed.isNullOrBlank() && refreshed != accessToken) {
                            val retry = SpotifyApi.syncPlaybackToSpotify(
                                accessToken = refreshed,
                                trackId = matchedTrack.id,
                                positionMs = positionMs
                            )
                            synced = retry.first
                            statusDetail = retry.second
                        }
                    }

                    lastSyncedPlaySongId = songId
                    lastSyncedPlayTimestampMs = System.currentTimeMillis()

                    val matchedArtist = matchedTrack.artists.firstOrNull()?.name.orEmpty()
                    recordSyncEvent(
                        songTitle = title,
                        artistName = artist,
                        action = "Synced Play",
                        success = synced,
                        details = "${matchedTrack.name}" +
                            (if (matchedArtist.isNotBlank()) " • $matchedArtist" else "") +
                            " — $statusDetail"
                    )

                    if (synced) {
                        val currentIndicator = _syncIndicatorState.value
                        if (currentIndicator !is SpotifySyncIndicatorState.Syncing) {
                            _syncIndicatorState.value =
                                SpotifySyncIndicatorState.Success("Synced '${matchedTrack.name}' to Spotify ✓")
                            delay(3500L)
                            if (_syncIndicatorState.value !is SpotifySyncIndicatorState.Syncing) {
                                _syncIndicatorState.value = SpotifySyncIndicatorState.Idle
                            }
                        }

                        // Keep Spotify Connect session unpaused & progressing while the user listens to this track
                        // so Spotify increments "Minutes listened", "Top song", "Top artist", and "Recently played"
                        val syncStartWallMs = System.currentTimeMillis()
                        val trackDurationMs = matchedTrack.durationMs.takeIf { it > 0L } ?: 210_000L
                        var elapsedMs = 8_500L
                        while (elapsedMs + 15_000L < trackDurationMs && isLoggedIn() && isSyncPlaybackEnabled()) {
                            delay(20_000L)
                            elapsedMs = System.currentTimeMillis() - syncStartWallMs + 5_000L
                            if (elapsedMs >= trackDurationMs - 5_000L) break
                            val token = getValidAccessToken() ?: break
                            SpotifyApi.keepPlaybackAliveOnSpotify(
                                accessToken = token,
                                trackId = matchedTrack.id,
                                expectedPositionMs = positionMs + elapsedMs
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Pushes all local Favorites in the app to the user's Spotify Liked Songs library.
     */
    suspend fun syncAllLocalFavoritesToSpotify(
        onProgress: (current: Int, total: Int, title: String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val favorites = Database.favorites().firstOrNull().orEmpty()
        if (favorites.isEmpty()) return@withContext 0

        val accessToken = getValidAccessToken() ?: return@withContext 0
        var syncedCount = 0

        favorites.forEachIndexed { index, song ->
            val progressMsg = "Syncing (${index + 1}/${favorites.size}): ${song.title}"
            _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing(progressMsg)
            onProgress(index + 1, favorites.size, song.title)

            val matchedTrack = SpotifyApi.searchTrack(
                accessToken = accessToken,
                title = song.title,
                artist = song.artistsText
            )
            if (matchedTrack != null && SpotifyApi.saveLikedTrack(accessToken, matchedTrack.id)) {
                syncedCount++
            }
        }

        recordSyncEvent(
            songTitle = "Favorites Library (${favorites.size} tracks)",
            artistName = "Batch Push to Spotify",
            action = "Batch Sync",
            success = syncedCount > 0,
            details = "Added $syncedCount of ${favorites.size} local Favorites to Spotify Liked Songs"
        )
        _syncIndicatorState.value =
            SpotifySyncIndicatorState.Success("Synced $syncedCount Favorites to Spotify ✓")
        syncedCount
    }

    data class PlaylistSyncResult(
        val playlistId: Long,
        val totalTracks: Int,
        val addedCount: Int,
        val alreadyPresentCount: Int,
        val removedCount: Int,
        val playlistName: String,
        val wasExistingPlaylist: Boolean = true
    )

    data class LikedSongsImportResult(
        val totalSpotifyTracks: Int,
        val totalMatchedCount: Int,
        val newlyAddedCount: Int,
        val alreadyInFavoritesCount: Int,
        val removedFromFavoritesCount: Int = 0
    )

    private val trackCacheMutex = Mutex()
    private val resolvedTrackCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile
    private var isTrackCacheLoaded = false

    private fun getTrackCacheFile(): java.io.File =
        java.io.File(app.vitune.android.Dependencies.application.applicationContext.filesDir, "spotify_track_map.tsv")

    private suspend fun ensureTrackCacheLoaded() {
        if (isTrackCacheLoaded) return
        trackCacheMutex.withLock {
            if (isTrackCacheLoaded) return@withLock
            runCatching {
                val file = getTrackCacheFile()
                if (file.exists()) {
                    file.forEachLine { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0 && tab + 1 < line.length) {
                            val spId = line.substring(0, tab).trim()
                            val ytId = line.substring(tab + 1).trim()
                            if (spId.isNotEmpty() && ytId.isNotEmpty()) {
                                resolvedTrackCache[spId] = ytId
                            }
                        }
                    }
                }
            }
            isTrackCacheLoaded = true
        }
    }

    private suspend fun persistTrackCacheIfNeeded(newMappingsAllocated: Boolean) {
        if (!newMappingsAllocated) return
        trackCacheMutex.withLock {
            runCatching {
                val file = getTrackCacheFile()
                val snapshot = resolvedTrackCache.entries.map { "${it.key}\t${it.value}" }
                file.writeText(snapshot.joinToString("\n"))
            }
        }
    }

    internal fun normalizeMatchKey(text: String?): String =
        YouTubeService.normalizeKey(text)

    internal fun stripTitleDecorations(text: String?): String =
        YouTubeService.stripTitleDecorations(text)

    internal class LocalSongLookupIndex(
        songs: List<Song>,
        preferredSongIds: Set<String> = emptySet()
    ) {
        private data class IndexedEntry(
            val song: Song,
            val fullTitle: String,
            val coreTitle: String,
            val artistWords: Set<String>,
            val isPreferred: Boolean
        )

        private val byId = HashMap<String, Song>(songs.size.coerceAtLeast(16))
        private val byTitle = HashMap<String, MutableList<IndexedEntry>>((songs.size * 2).coerceAtLeast(32))
        private val preferredEntries = ArrayList<IndexedEntry>(preferredSongIds.size.coerceAtLeast(16))

        init {
            val sortedSongs = if (preferredSongIds.isEmpty()) {
                songs
            } else {
                songs.sortedByDescending { it.id in preferredSongIds }
            }
            for (song in sortedSongs) {
                byId[song.id] = song
                val fullTitle = normalizeMatchKey(song.title)
                if (fullTitle.isBlank()) continue
                val coreTitle = normalizeMatchKey(stripTitleDecorations(song.title)).ifBlank { fullTitle }
                val artistWords = normalizeMatchKey(song.artistsText)
                    .split(' ')
                    .filter { it.isNotBlank() }
                    .toSet()
                val isPref = song.id in preferredSongIds
                val entry = IndexedEntry(
                    song = song,
                    fullTitle = fullTitle,
                    coreTitle = coreTitle,
                    artistWords = artistWords,
                    isPreferred = isPref
                )
                if (isPref) {
                    preferredEntries.add(entry)
                }
                byTitle.getOrPut(fullTitle) { ArrayList(2) }.add(entry)
                if (coreTitle != fullTitle) {
                    byTitle.getOrPut(coreTitle) { ArrayList(2) }.add(entry)
                }
            }
        }

        fun findMatch(track: SpotifyTrack): Song? {
            if (track.id.isNotBlank()) {
                val cachedSongId = resolvedTrackCache[track.id]
                if (!cachedSongId.isNullOrBlank()) {
                    byId[cachedSongId]?.let { return it }
                }
            }

            val fullTrackTitle = normalizeMatchKey(track.name)
            if (fullTrackTitle.isBlank()) return null
            val coreTrackTitle = normalizeMatchKey(stripTitleDecorations(track.name)).ifBlank { fullTrackTitle }

            val candidates = ArrayList<IndexedEntry>(4)
            byTitle[fullTrackTitle]?.let { candidates.addAll(it) }
            if (coreTrackTitle != fullTrackTitle) {
                byTitle[coreTrackTitle]?.forEach { entry ->
                    if (candidates.none { it.song.id == entry.song.id }) {
                        candidates.add(entry)
                    }
                }
            }

            val trackArtistWordLists = track.artists
                .map { artist ->
                    normalizeMatchKey(artist.name).split(' ').filter { it.isNotBlank() }
                }
                .filter { it.isNotEmpty() }

            var matched: Song? = candidates.firstOrNull { candidate ->
                if (trackArtistWordLists.isEmpty()) {
                    true
                } else if (candidate.artistWords.isEmpty()) {
                    false
                } else {
                    trackArtistWordLists.any { artistWords ->
                        artistWords.all { word -> word in candidate.artistWords }
                    }
                }
            }?.song

            if (matched == null && candidates.isNotEmpty() && trackArtistWordLists.isNotEmpty()) {
                matched = candidates.firstOrNull { candidate ->
                    candidate.artistWords.isNotEmpty() && trackArtistWordLists.any { artistWords ->
                        artistWords.any { word -> word.length >= 2 && word in candidate.artistWords }
                    }
                }?.song
            }

            // Fallback: check preferred entries (songs already in Favorites/Playlist) whose YouTube title
            // includes artist/movie prefix or suffix around coreTrackTitle
            if (matched == null && coreTrackTitle.length >= 3 && preferredEntries.isNotEmpty()) {
                matched = preferredEntries.firstOrNull { entry ->
                    entry.coreTitle.length >= 3 &&
                        (entry.coreTitle.contains(coreTrackTitle) || coreTrackTitle.contains(entry.coreTitle)) &&
                        (
                            trackArtistWordLists.isEmpty() ||
                                (
                                    entry.artistWords.isNotEmpty() &&
                                        trackArtistWordLists.any { artistWords ->
                                            artistWords.all { word -> word in entry.artistWords } ||
                                                artistWords.any { word -> word.length >= 3 && word in entry.artistWords }
                                        }
                                    )
                            )
                }?.song
            }

            if (matched != null && track.id.isNotBlank()) {
                resolvedTrackCache[track.id] = matched.id
            }
            return matched
        }
    }

    /**
     * Imports/syncs the user's Spotify Liked Songs (including 8,000+ song libraries) into Favorites.
     * - Checks if each song is already available in Favorites or the local database and skips
     *   network resolution for all already-available songs (only resolves newly added songs).
     * - Assigns deterministic `likedAt` timestamps in a single final transaction so Favorites
     *   (`ORDER BY likedAt DESC`) matches Spotify's Liked Songs order 100%.
     */
    suspend fun importSpotifyLikedSongsToFavoritesDetailed(
        onProgress: (current: Int, total: Int, title: String) -> Unit
    ): LikedSongsImportResult = withContext(Dispatchers.IO) {
        _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs...")
        ensureTrackCacheLoaded()

        var accessToken = getValidAccessToken()
            ?: return@withContext LikedSongsImportResult(0, 0, 0, 0)
        var likedTracks = SpotifyApi.getAllLikedTracks(
            accessToken = accessToken,
            onProgress = { fetched, total ->
                _syncIndicatorState.value =
                    SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs ($fetched / $total)...")
                onProgress(fetched, total, "Fetching Spotify library ($fetched / $total)...")
            }
        )
        if (likedTracks.isEmpty()) {
            val refreshed = getValidAccessToken(forceRefresh = true)
            if (refreshed != null) {
                accessToken = refreshed
                likedTracks = SpotifyApi.getAllLikedTracks(
                    accessToken = accessToken,
                    onProgress = { fetched, total ->
                        _syncIndicatorState.value =
                            SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs ($fetched / $total)...")
                        onProgress(fetched, total, "Fetching Spotify library ($fetched / $total)...")
                    }
                )
            }
        }
        if (likedTracks.isEmpty()) {
            _syncIndicatorState.value = SpotifySyncIndicatorState.Idle
            return@withContext LikedSongsImportResult(0, 0, 0, 0)
        }

        val existingFavorites = runCatching {
            Database.favorites().firstOrNull().orEmpty()
        }.getOrDefault(emptyList())
        val existingFavoriteIds = existingFavorites.mapTo(HashSet(existingFavorites.size)) { it.id }

        val allLocalSongs = runCatching {
            (existingFavorites + Database.songsByRowIdAsc().firstOrNull().orEmpty()).distinctBy { it.id }
        }.getOrDefault(existingFavorites)

        val lookupIndex = LocalSongLookupIndex(
            songs = allLocalSongs,
            preferredSongIds = existingFavoriteIds
        )
        val totalCount = likedTracks.size
        val orderedSongIds = arrayOfNulls<String>(totalCount)
        val missingIndices = ArrayList<Int>()
        var alreadyInFavoritesCount = 0
        val newlyAddedCounter = AtomicInteger(0)

        // Fast Pass 1: Match all already-available local songs in memory from A to Z without network calls
        for (index in likedTracks.indices) {
            val track = likedTracks[index]
            val localMatch = lookupIndex.findMatch(track)
            if (localMatch != null) {
                orderedSongIds[index] = localMatch.id
                if (localMatch.id in existingFavoriteIds) {
                    alreadyInFavoritesCount++
                } else {
                    newlyAddedCounter.incrementAndGet()
                }
            } else {
                missingIndices.add(index)
            }
        }

        val alreadyResolvedCount = totalCount - missingIndices.size
        val completedCounter = AtomicInteger(alreadyResolvedCount)
        var newCacheMappings = false

        if (missingIndices.isEmpty()) {
            onProgress(totalCount, totalCount, "Checking A–Z & matching $totalCount Spotify Liked Songs...")
        } else {
            onProgress(
                alreadyResolvedCount,
                totalCount,
                "Matched $alreadyResolvedCount existing songs, resolving ${missingIndices.size} new song(s)..."
            )
            val semaphore = Semaphore(6)
            val livePreviewBaseTs = System.currentTimeMillis()

            coroutineScope {
                missingIndices.map { trackIndex ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val track = likedTracks[trackIndex]
                            val csvTrack = CsvParser.Track(
                                title = track.name,
                                artist = track.artists.firstOrNull()?.name.orEmpty(),
                                album = track.album?.name.orEmpty()
                            )
                            val resolvedSongId = runCatching {
                                YouTubeService.resolveAndLikeSong(
                                    track = csvTrack,
                                    likedAtTimestamp = livePreviewBaseTs - trackIndex.toLong()
                                )
                            }.getOrNull()

                            if (resolvedSongId != null) {
                                orderedSongIds[trackIndex] = resolvedSongId
                                if (track.id.isNotBlank()) {
                                    resolvedTrackCache[track.id] = resolvedSongId
                                    newCacheMappings = true
                                }
                                if (resolvedSongId !in existingFavoriteIds) {
                                    newlyAddedCounter.incrementAndGet()
                                }
                            }

                            val done = completedCounter.incrementAndGet()
                            _syncIndicatorState.value =
                                SpotifySyncIndicatorState.Syncing("Syncing Liked Songs ($done/$totalCount): ${track.name}")
                            onProgress(done, totalCount, track.name)
                        }
                    }
                }.awaitAll()
            }
        }

        persistTrackCacheIfNeeded(newCacheMappings)

        // Pass 2: Enforce 100% exact Spotify Liked Songs list from A to Z:
        // 1) Remove any songs from Favorites that were removed from Liked Songs on Spotify
        // 2) Assign descending likedAt timestamps so Favorites matches Spotify order 100%
        // 3) If a local "Spotify: Liked Songs" playlist exists, keep it in sync as well
        val finalOrderBaseTimestamp = System.currentTimeMillis()
        val finalOrderedIds = ArrayList<String>(totalCount)
        val seenSongIds = HashSet<String>(totalCount)
        for (songId in orderedSongIds) {
            if (songId != null && seenSongIds.add(songId)) {
                finalOrderedIds.add(songId)
            }
        }

        val removedFavoriteIds = if (finalOrderedIds.isNotEmpty()) {
            existingFavoriteIds.filter { it !in seenSongIds }
        } else {
            emptyList()
        }
        val removedFromFavoritesCount = removedFavoriteIds.size
        val totalMatched = finalOrderedIds.size

        val existingLikedPlaylists = runCatching {
            Database.playlists().firstOrNull().orEmpty().filter { p ->
                p.browseId.equals("spotify:liked", ignoreCase = true) ||
                    p.name.equals("Spotify: Liked Songs", ignoreCase = true)
            }
        }.getOrDefault(emptyList())

        if (finalOrderedIds.isNotEmpty()) {
            transaction {
                for (removedSongId in removedFavoriteIds) {
                    Database.like(songId = removedSongId, likedAt = null)
                }
                for (index in finalOrderedIds.indices) {
                    val songId = finalOrderedIds[index]
                    Database.like(
                        songId = songId,
                        likedAt = finalOrderBaseTimestamp - (index.toLong() * 10L)
                    )
                }
                for (likedPlaylist in existingLikedPlaylists) {
                    Database.clearPlaylist(likedPlaylist.id)
                    Database.insertSongPlaylistMaps(
                        finalOrderedIds.mapIndexed { position, songId ->
                            SongPlaylistMap(
                                songId = songId,
                                playlistId = likedPlaylist.id,
                                position = position
                            )
                        }
                    )
                }
            }
        }

        val newlyAdded = newlyAddedCounter.get()
        val summaryMsg = when {
            newlyAdded > 0 && removedFromFavoritesCount > 0 ->
                "Synced Favorites: +$newlyAdded new, -$removedFromFavoritesCount removed ($totalMatched total) ✓"
            removedFromFavoritesCount > 0 ->
                "Removed $removedFromFavoritesCount unliked song(s) • $totalMatched total in Favorites ✓"
            newlyAdded > 0 ->
                "Added $newlyAdded new song(s) • $totalMatched total in Spotify order ✓"
            else ->
                "All $totalMatched Liked Songs matched & ordered ✓"
        }

        recordSyncEvent(
            songTitle = "Spotify Liked Songs",
            artistName = "${likedTracks.size} tracks on Spotify",
            action = "Synced to Favorites",
            success = totalMatched > 0,
            details = "Total: $totalMatched tracks (+$newlyAdded new, $alreadyInFavoritesCount kept, -$removedFromFavoritesCount removed)"
        )
        _syncIndicatorState.value = SpotifySyncIndicatorState.Success(summaryMsg)

        LikedSongsImportResult(
            totalSpotifyTracks = likedTracks.size,
            totalMatchedCount = totalMatched,
            newlyAddedCount = newlyAdded,
            alreadyInFavoritesCount = alreadyInFavoritesCount,
            removedFromFavoritesCount = removedFromFavoritesCount
        )
    }

    suspend fun importSpotifyLikedSongsToFavorites(
        onProgress: (current: Int, total: Int, title: String) -> Unit
    ): Int = importSpotifyLikedSongsToFavoritesDetailed(onProgress).totalMatchedCount

    /**
     * Incrementally imports or updates a Spotify playlist (or Spotify Liked Songs as a playlist).
     * - If a local playlist with matching `browseId` or name already exists, reuses it instead of
     *   creating a duplicate playlist.
     * - Checks which songs are already in the playlist or local DB, skips network calls for
     *   already-available songs, and only resolves new songs on YouTube.
     */
    suspend fun importOrSyncSpotifyTracksToPlaylist(
        targetName: String,
        targetBrowseId: String,
        thumbnailUrl: String?,
        remoteTracks: List<SpotifyTrack>,
        onProgress: (current: Int, total: Int, title: String) -> Unit = { _, _, _ -> }
    ): PlaylistSyncResult = withContext(Dispatchers.IO) {
        ensureTrackCacheLoaded()

        val allPlaylists = runCatching {
            Database.playlists().firstOrNull().orEmpty()
        }.getOrDefault(emptyList())

        val existingPlaylist = allPlaylists.firstOrNull { p ->
            (!p.browseId.isNullOrBlank() && p.browseId.equals(targetBrowseId, ignoreCase = true)) ||
                p.name.equals(targetName, ignoreCase = true) ||
                p.name.equals(targetName.removePrefix("Spotify:").trim(), ignoreCase = true)
        }

        val wasExistingPlaylist = existingPlaylist != null
        val playlistId: Long = if (existingPlaylist != null) {
            if (existingPlaylist.browseId != targetBrowseId || (thumbnailUrl != null && existingPlaylist.thumbnail != thumbnailUrl)) {
                transaction {
                    Database.update(
                        existingPlaylist.copy(
                            browseId = targetBrowseId,
                            thumbnail = thumbnailUrl ?: existingPlaylist.thumbnail
                        )
                    )
                }
            }
            existingPlaylist.id
        } else {
            Database.insert(
                Playlist(
                    name = targetName,
                    browseId = targetBrowseId,
                    thumbnail = thumbnailUrl
                )
            )
        }

        val existingPlaylistSongs = runCatching {
            Database.playlistSongs(playlistId).firstOrNull().orEmpty()
        }.getOrDefault(emptyList())
        val existingIdsSet = existingPlaylistSongs.mapTo(HashSet(existingPlaylistSongs.size)) { it.id }

        val isLikedSongsTarget = targetBrowseId.equals("spotify:liked", ignoreCase = true) ||
            targetName.equals("Spotify: Liked Songs", ignoreCase = true)

        val existingFavorites = if (isLikedSongsTarget) {
            runCatching { Database.favorites().firstOrNull().orEmpty() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val existingFavoriteIds = existingFavorites.mapTo(HashSet(existingFavorites.size)) { it.id }

        val allKnownSongs = runCatching {
            (existingPlaylistSongs + existingFavorites + Database.songsByRowIdAsc().firstOrNull().orEmpty()).distinctBy { it.id }
        }.getOrDefault(existingPlaylistSongs)

        val lookupIndex = LocalSongLookupIndex(
            songs = allKnownSongs,
            preferredSongIds = existingIdsSet + existingFavoriteIds
        )
        val totalTracksCount = remoteTracks.size
        val orderedSongIds = arrayOfNulls<String>(totalTracksCount)
        val missingIndices = ArrayList<Int>()
        val newlyAddedCounter = AtomicInteger(0)
        var alreadyInPlaylistCount = 0

        // Fast Pass 1: Match existing songs in memory from A to Z without re-importing
        for (index in remoteTracks.indices) {
            val track = remoteTracks[index]
            val existingMatch = lookupIndex.findMatch(track)
            if (existingMatch != null) {
                orderedSongIds[index] = existingMatch.id
                if (existingMatch.id in existingIdsSet) {
                    alreadyInPlaylistCount++
                } else {
                    newlyAddedCounter.incrementAndGet()
                }
            } else {
                missingIndices.add(index)
            }
        }

        val alreadyResolvedCount = totalTracksCount - missingIndices.size
        val completedCounter = AtomicInteger(alreadyResolvedCount)
        var newCacheMappings = false

        // Immediately replace the playlist with already-known songs so removed songs disappear right away
        // and the playlist is playable while any remaining new songs resolve in the background
        if (alreadyResolvedCount > 0 && missingIndices.isNotEmpty()) {
            val initialMaps = ArrayList<SongPlaylistMap>(alreadyResolvedCount)
            val initialSeen = HashSet<String>(alreadyResolvedCount)
            for (index in orderedSongIds.indices) {
                val sId = orderedSongIds[index] ?: continue
                if (initialSeen.add(sId)) {
                    initialMaps.add(
                        SongPlaylistMap(
                            songId = sId,
                            playlistId = playlistId,
                            position = index
                        )
                    )
                }
            }
            if (initialMaps.isNotEmpty()) {
                transaction {
                    runCatching {
                        Database.clearPlaylist(playlistId)
                        Database.insertSongPlaylistMaps(initialMaps)
                    }
                }
            }
        }

        if (missingIndices.isEmpty()) {
            onProgress(totalTracksCount, totalTracksCount, "Checking A–Z & matching $totalTracksCount songs...")
        } else {
            onProgress(
                alreadyResolvedCount,
                totalTracksCount,
                "Matched $alreadyResolvedCount existing songs, resolving ${missingIndices.size} new song(s)..."
            )
            val semaphore = Semaphore(6)
            coroutineScope {
                missingIndices.map { trackIndex ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val track = remoteTracks[trackIndex]
                            val csvTrack = CsvParser.Track(
                                title = track.name,
                                artist = track.artists.firstOrNull()?.name.orEmpty(),
                                album = track.album?.name.orEmpty()
                            )
                            val resolved = runCatching {
                                YouTubeService.resolveAndInsertSong(csvTrack)
                            }.getOrNull()

                            if (resolved != null) {
                                orderedSongIds[trackIndex] = resolved
                                if (track.id.isNotBlank()) {
                                    resolvedTrackCache[track.id] = resolved
                                    newCacheMappings = true
                                }
                                if (resolved !in existingIdsSet) {
                                    newlyAddedCounter.incrementAndGet()
                                }
                                // Stream song into the playlist immediately so user can see and play it while sync continues
                                transaction {
                                    runCatching {
                                        Database.insert(
                                            SongPlaylistMap(
                                                songId = resolved,
                                                playlistId = playlistId,
                                                position = trackIndex
                                            )
                                        )
                                    }
                                }
                            }

                            val done = completedCounter.incrementAndGet()
                            onProgress(done, totalTracksCount, track.name)
                            _syncIndicatorState.value =
                                SpotifySyncIndicatorState.Syncing("Syncing '$targetName' ($done/$totalTracksCount): ${track.name}")
                        }
                    }
                }.awaitAll()
            }
        }

        persistTrackCacheIfNeeded(newCacheMappings)

        // Preserve exact Spotify playlist order from A to Z while avoiding duplicate songId entries
        val finalOrderedIds = ArrayList<String>(totalTracksCount)
        val seenInPlaylist = HashSet<String>(totalTracksCount)
        for (songId in orderedSongIds) {
            if (songId != null && seenInPlaylist.add(songId)) {
                finalOrderedIds.add(songId)
            }
        }

        val newlyAddedCount = newlyAddedCounter.get()
        val removedFromPlaylistCount = (existingIdsSet - seenInPlaylist).size.coerceAtLeast(0)
        val removedFromFavoritesIds = if (isLikedSongsTarget && finalOrderedIds.isNotEmpty()) {
            existingFavoriteIds.filter { it !in seenInPlaylist }
        } else {
            emptyList()
        }
        val removedCount = maxOf(removedFromPlaylistCount, removedFromFavoritesIds.size)

        if (finalOrderedIds.isNotEmpty()) {
            val finalOrderBaseTimestamp = System.currentTimeMillis()
            transaction {
                Database.clearPlaylist(playlistId)
                Database.insertSongPlaylistMaps(
                    finalOrderedIds.mapIndexed { position, songId ->
                        SongPlaylistMap(
                            songId = songId,
                            playlistId = playlistId,
                            position = position
                        )
                    }
                )
                if (isLikedSongsTarget) {
                    for (removedFavId in removedFromFavoritesIds) {
                        Database.like(songId = removedFavId, likedAt = null)
                    }
                    for (index in finalOrderedIds.indices) {
                        Database.like(
                            songId = finalOrderedIds[index],
                            likedAt = finalOrderBaseTimestamp - (index.toLong() * 10L)
                        )
                    }
                }
            }
        }

        val displayPlaylistName = existingPlaylist?.name ?: targetName
        PlaylistSyncResult(
            playlistId = playlistId,
            totalTracks = finalOrderedIds.size,
            addedCount = newlyAddedCount,
            alreadyPresentCount = alreadyInPlaylistCount,
            removedCount = removedCount,
            playlistName = displayPlaylistName,
            wasExistingPlaylist = wasExistingPlaylist
        )
    }

    /**
     * Incrementally syncs a local playlist that was imported from Spotify with its remote Spotify state.
     * Reuses already-matched local songs for fast syncing and only searches YouTube Music for newly added tracks.
     */
    suspend fun syncSpotifyPlaylist(
        playlist: Playlist,
        existingSongs: List<Song>,
        onProgress: (current: Int, total: Int, title: String) -> Unit = { _, _, _ -> }
    ): Result<PlaylistSyncResult> = withContext(Dispatchers.IO) {
        _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing("Syncing '${playlist.name}' with Spotify...")
        val rawBrowseId = playlist.browseId?.trim().orEmpty()
        val isLikedSongsPlaylist = rawBrowseId.equals("spotify:liked", ignoreCase = true) ||
            playlist.name.equals("Spotify: Liked Songs", ignoreCase = true)

        var accessToken = if (isLoggedIn()) getValidAccessToken() else null
        var resolvedBrowseId = rawBrowseId
        var resolvedThumbnail = playlist.thumbnail

        val remoteTracks: List<SpotifyTrack> = if (isLikedSongsPlaylist) {
            if (accessToken == null) {
                _syncIndicatorState.value = SpotifySyncIndicatorState.Error("Connect Spotify to sync Liked Songs")
                return@withContext Result.failure(
                    IllegalStateException("Please connect your Spotify account first to sync Liked Songs.")
                )
            }
            var liked = SpotifyApi.getAllLikedTracks(
                accessToken = accessToken,
                onProgress = { fetched, total ->
                    _syncIndicatorState.value =
                        SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs ($fetched / $total)...")
                    onProgress(fetched, total, "Fetching Liked Songs ($fetched / $total)...")
                }
            )
            if (liked.isEmpty()) {
                val refreshed = getValidAccessToken(forceRefresh = true)
                if (refreshed != null) {
                    accessToken = refreshed
                    liked = SpotifyApi.getAllLikedTracks(
                        accessToken = accessToken,
                        onProgress = { fetched, total ->
                            _syncIndicatorState.value =
                                SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs ($fetched / $total)...")
                            onProgress(fetched, total, "Fetching Liked Songs ($fetched / $total)...")
                        }
                    )
                }
            }
            if (liked.isEmpty()) {
                _syncIndicatorState.value = SpotifySyncIndicatorState.Error("No Liked Songs found on Spotify")
                return@withContext Result.failure(
                    IllegalStateException("Could not fetch Liked Songs from Spotify. Check your Spotify login.")
                )
            }
            resolvedBrowseId = "spotify:liked"
            liked
        } else {
            var spotifyPlaylistId = when {
                rawBrowseId.startsWith("spotify:playlist:", ignoreCase = true) ->
                    rawBrowseId.substringAfterLast(':').trim()
                rawBrowseId.startsWith("spotify:", ignoreCase = true) ->
                    rawBrowseId.substringAfterLast(':').trim()
                else -> ""
            }

            if (spotifyPlaylistId.isBlank() && accessToken != null) {
                val matchedPlaylist = SpotifyApi.findPlaylistByName(accessToken, playlist.name)
                if (matchedPlaylist != null) {
                    spotifyPlaylistId = matchedPlaylist.id
                    resolvedBrowseId = "spotify:playlist:${matchedPlaylist.id}"
                    resolvedThumbnail = matchedPlaylist.images?.firstOrNull()?.url ?: playlist.thumbnail
                }
            }

            if (spotifyPlaylistId.isBlank()) {
                _syncIndicatorState.value = SpotifySyncIndicatorState.Error("Spotify playlist ID not linked")
                return@withContext Result.failure(
                    IllegalStateException(
                        if (!isLoggedIn()) {
                            "Connect your Spotify account or re-import this playlist once by link to enable 1-tap sync."
                        } else {
                            "Could not find '${playlist.name.removePrefix("Spotify:").trim()}' in your Spotify account playlists. Re-import it by link once to link its Spotify ID."
                        }
                    )
                )
            }

            val fetchedResult = SpotifyApi.getPlaylistInfoAndTracks(
                rawPlaylistInput = spotifyPlaylistId,
                accessToken = accessToken
            )
            val pair = fetchedResult.getOrElse { err ->
                _syncIndicatorState.value = SpotifySyncIndicatorState.Error("Failed to sync '${playlist.name}'")
                return@withContext Result.failure(err)
            }
            val (meta, tracks) = pair
            resolvedBrowseId = "spotify:playlist:${meta.id}"
            resolvedThumbnail = meta.images?.firstOrNull()?.url ?: playlist.thumbnail
            tracks
        }

        if (remoteTracks.isEmpty()) {
            _syncIndicatorState.value = SpotifySyncIndicatorState.Error("No playable tracks in Spotify playlist")
            return@withContext Result.failure(
                IllegalStateException("No playable tracks were returned from Spotify for '${playlist.name}'.")
            )
        }

        val syncResult = importOrSyncSpotifyTracksToPlaylist(
            targetName = playlist.name,
            targetBrowseId = resolvedBrowseId,
            thumbnailUrl = resolvedThumbnail,
            remoteTracks = remoteTracks,
            onProgress = onProgress
        )

        val summaryDetails =
            "Total: ${syncResult.totalTracks} tracks (+${syncResult.addedCount} new, ${syncResult.alreadyPresentCount} kept, -${syncResult.removedCount} removed)"
        recordSyncEvent(
            songTitle = playlist.name,
            artistName = "Spotify Playlist Sync",
            action = "Synced Playlist",
            success = syncResult.totalTracks > 0,
            details = summaryDetails
        )
        _syncIndicatorState.value = SpotifySyncIndicatorState.Success(
            if (syncResult.addedCount > 0) {
                "Synced '${playlist.name}' (+${syncResult.addedCount} new) ✓"
            } else {
                "'${playlist.name}' up to date (${syncResult.totalTracks} tracks) ✓"
            }
        )

        Result.success(syncResult)
    }

    fun syncLikedSongIfEnabled(song: Song) {
        onSongLiked(
            songId = song.id,
            fallbackTitle = song.title,
            fallbackArtist = song.artistsText
        )
    }

    /**
     * Upserts a list of Spotify artists into the local `Artist` table while merging with any
     * existing YouTube (`UC...`) artist rows of the same name so no duplicate clone pages are created
     * and every single artist (including 500+ followed artists) is bookmarked and visible in the Artists tab.
     */
    suspend fun upsertSpotifyArtists(
        artists: List<app.vitune.providers.spotify.models.SpotifyArtistFull>
    ): Int = withContext(Dispatchers.IO) {
        if (artists.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        val existingAll = runCatching {
            Database.allArtists()
        }.getOrDefault(emptyList())

        val existingByName = existingAll.groupBy {
            it.name?.replace('\u00A0', ' ')?.trim()?.lowercase().orEmpty()
        }
        val existingById = existingAll.associateBy { it.id }

        val resolvedToUpsert = mutableListOf<app.vitune.android.models.Artist>()
        val duplicateSpotifyToYtMigrations = mutableListOf<Pair<String, String>>()
        val seenKeys = HashSet<String>(artists.size * 2)

        for ((idx, spArtist) in artists.withIndex()) {
            val cleanName = spArtist.name.replace('\u00A0', ' ').trim()
            val cleanSpId = spArtist.id.trim()
            if (cleanName.isBlank() || cleanSpId.isBlank()) continue

            val spotifyKey = "spotify:artist:$cleanSpId"
            val nameKey = cleanName.lowercase()
            if (!seenKeys.add(spotifyKey)) continue

            val existingMatches = existingByName[nameKey].orEmpty()
            val existingYt = existingMatches
                .filter { it.id.startsWith("UC") }
                .sortedByDescending { it.bookmarkedAt ?: 0L }
                .firstOrNull { it.id !in seenKeys }

            if (existingYt != null) {
                seenKeys.add(existingYt.id)
                resolvedToUpsert.add(
                    existingYt.copy(
                        name = cleanName,
                        thumbnailUrl = spArtist.imageUrl?.takeIf { it.isNotBlank() } ?: existingYt.thumbnailUrl,
                        bookmarkedAt = existingYt.bookmarkedAt ?: (now - idx)
                    )
                )
                if (existingById.containsKey(spotifyKey) || existingMatches.any { it.id == spotifyKey }) {
                    duplicateSpotifyToYtMigrations.add(spotifyKey to existingYt.id)
                }
            } else {
                val existingSp = existingById[spotifyKey] ?: existingMatches.firstOrNull { it.id == spotifyKey }
                resolvedToUpsert.add(
                    app.vitune.android.models.Artist(
                        id = spotifyKey,
                        name = cleanName,
                        thumbnailUrl = spArtist.imageUrl?.takeIf { it.isNotBlank() } ?: existingSp?.thumbnailUrl,
                        timestamp = existingSp?.timestamp,
                        bookmarkedAt = existingSp?.bookmarkedAt ?: (now - idx)
                    )
                )
            }
        }

        if (resolvedToUpsert.isNotEmpty()) {
            Database.internal.runInTransaction {
                duplicateSpotifyToYtMigrations.forEach { (spId, ytId) ->
                    runCatching {
                        Database.migrateSongArtistMap(spId, ytId)
                        Database.deleteArtistById(spId)
                    }
                }
                resolvedToUpsert.forEach { artistEntity ->
                    runCatching {
                        Database.upsert(artistEntity)
                    }
                }
            }
        }
        resolvedToUpsert.size
    }

    /**
     * Fetches all followed artists from the user's Spotify account (with token refresh retry)
     * and imports all of them into the local Artists database.
     */
    suspend fun importAllFollowedArtistsFromSpotify(
        onProgress: ((String) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing("Fetching followed artists from Spotify...")
        onProgress?.invoke("Fetching followed artists from Spotify...")

        var token = getValidAccessToken() ?: run {
            _syncIndicatorState.value = SpotifySyncIndicatorState.Error("Spotify session expired")
            return@withContext -1
        }

        var followed = SpotifyApi.getFollowedArtists(
            accessToken = token,
            onProgress = { fetched ->
                val msg = "Fetching Spotify followed artists ($fetched found)..."
                _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing(msg)
                onProgress?.invoke(msg)
            }
        )
        if (followed.isEmpty()) {
            val refreshed = getValidAccessToken(forceRefresh = true)
            if (refreshed != null) {
                token = refreshed
                followed = SpotifyApi.getFollowedArtists(
                    accessToken = token,
                    onProgress = { fetched ->
                        val msg = "Fetching Spotify followed artists ($fetched found)..."
                        _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing(msg)
                        onProgress?.invoke(msg)
                    }
                )
            }
        }
        if (followed.isEmpty()) {
            _syncIndicatorState.value = SpotifySyncIndicatorState.Idle
            return@withContext 0
        }

        onProgress?.invoke("Saving ${followed.size} artists to your library...")
        val count = upsertSpotifyArtists(followed)
        recordSyncEvent(
            songTitle = "Spotify Followed Artists",
            artistName = "$count artists",
            action = "Imported Artists",
            success = count > 0,
            details = "Imported all $count followed Spotify artists to Artists library"
        )
        _syncIndicatorState.value = SpotifySyncIndicatorState.Success("Imported $count followed artists ✓")
        count
    }

    /**
     * Launches a Spotify playlist import or update in the background so the user can close dialogs,
     * browse the app, and play any song while the playlist syncs.
     */
    fun startImportPlaylistByLinkOrIdInBackground(
        rawInput: String,
        fallbackName: String? = null
    ) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        if (
            trimmed.contains("collection/tracks", ignoreCase = true) ||
            trimmed.equals("spotify:collection:tracks", ignoreCase = true) ||
            trimmed.equals("liked", ignoreCase = true) ||
            trimmed.equals("liked songs", ignoreCase = true)
        ) {
            startImportLikedSongsAsPlaylistInBackground()
            return
        }

        val label = fallbackName?.takeIf { it.isNotBlank() } ?: "Spotify Playlist"
        showAppToast("Syncing '$label' in the background...")

        scope.launch {
            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                isActive = true,
                taskTitle = label,
                statusMessage = "Fetching '$label' from Spotify...",
                current = 0,
                total = 0
            )
            _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing("Fetching '$label' from Spotify...")

            val token = if (isLoggedIn()) getValidAccessToken() else null
            val result = SpotifyApi.getPlaylistInfoAndTracks(
                rawPlaylistInput = trimmed,
                accessToken = token
            )

            result.fold(
                onSuccess = { (playlistMeta, tracks) ->
                    val resolvedName = fallbackName?.takeIf { it.isNotBlank() }
                        ?: playlistMeta.name.ifBlank { "Spotify Playlist" }
                    if (tracks.isEmpty()) {
                        val errMsg = "No playable tracks found in '$resolvedName'."
                        recordSyncEvent(
                            songTitle = resolvedName,
                            artistName = "0 tracks",
                            action = "Playlist Import Failed",
                            success = false,
                            details = "No playable tracks found in playlist"
                        )
                        _syncIndicatorState.value = SpotifySyncIndicatorState.Error(errMsg)
                        _backgroundSyncState.value = SpotifyBackgroundSyncState(
                            isActive = false,
                            taskTitle = resolvedName,
                            errorMessage = errMsg
                        )
                        showAppToast(errMsg)
                        return@fold
                    }

                    _backgroundSyncState.value = _backgroundSyncState.value.copy(
                        taskTitle = resolvedName,
                        statusMessage = "Syncing '$resolvedName' (0/${tracks.size})...",
                        current = 0,
                        total = tracks.size
                    )

                    val syncResult = importOrSyncSpotifyTracksToPlaylist(
                        targetName = "Spotify: $resolvedName",
                        targetBrowseId = "spotify:playlist:${playlistMeta.id}",
                        thumbnailUrl = playlistMeta.images?.firstOrNull()?.url,
                        remoteTracks = tracks,
                        onProgress = { done, total, title ->
                            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                                isActive = true,
                                taskTitle = resolvedName,
                                statusMessage = "Syncing ($done/$total): $title",
                                current = done,
                                total = total
                            )
                        }
                    )

                    val importedCount = syncResult.totalTracks
                    val newlyAddedCount = syncResult.addedCount
                    val alreadyPresentCount = syncResult.alreadyPresentCount
                    val removedCount = syncResult.removedCount
                    val unavailableCount = (tracks.size - importedCount).coerceAtLeast(0)

                    recordSyncEvent(
                        songTitle = resolvedName,
                        artistName = playlistMeta.owner?.displayName ?: "Spotify Playlist",
                        action = if (syncResult.wasExistingPlaylist) "Updated Playlist" else "Imported Playlist",
                        success = importedCount > 0,
                        details = "Total $importedCount of ${tracks.size} tracks (+$newlyAddedCount new, $alreadyPresentCount kept, -$removedCount removed)" +
                            if (unavailableCount > 0) " ($unavailableCount unavailable)" else ""
                    )

                    if (importedCount > 0) {
                        val doneMsg = when {
                            syncResult.wasExistingPlaylist && (newlyAddedCount > 0 || removedCount > 0) ->
                                "Synced '${syncResult.playlistName}': +$newlyAddedCount new, -$removedCount removed ($importedCount total)"
                            syncResult.wasExistingPlaylist ->
                                "'${syncResult.playlistName}' matched with Spotify ($importedCount songs)"
                            else ->
                                "Imported $importedCount songs into '${syncResult.playlistName}'"
                        }
                        _syncIndicatorState.value = SpotifySyncIndicatorState.Success("$doneMsg ✓")
                        _backgroundSyncState.value = SpotifyBackgroundSyncState(
                            isActive = false,
                            taskTitle = syncResult.playlistName,
                            current = importedCount,
                            total = tracks.size,
                            completionMessage = doneMsg
                        )
                        showAppToast(doneMsg)
                    } else {
                        val errMsg = "Could not match tracks in '$resolvedName'."
                        _syncIndicatorState.value = SpotifySyncIndicatorState.Error(errMsg)
                        _backgroundSyncState.value = SpotifyBackgroundSyncState(
                            isActive = false,
                            taskTitle = resolvedName,
                            errorMessage = errMsg
                        )
                        showAppToast(errMsg)
                    }
                },
                onFailure = { err ->
                    val errMsg = err.message ?: "Could not import Spotify playlist."
                    recordSyncEvent(
                        songTitle = fallbackName ?: trimmed.take(32),
                        artistName = "Spotify Playlist",
                        action = "Playlist Import Failed",
                        success = false,
                        details = errMsg
                    )
                    _syncIndicatorState.value = SpotifySyncIndicatorState.Error(errMsg)
                    _backgroundSyncState.value = SpotifyBackgroundSyncState(
                        isActive = false,
                        taskTitle = label,
                        errorMessage = errMsg
                    )
                    showAppToast(errMsg)
                }
            )
        }
    }

    /**
     * Launches importing Spotify Liked Songs as a local playlist ("Spotify: Liked Songs") in the background.
     */
    fun startImportLikedSongsAsPlaylistInBackground(expectedTotal: Int = 0) {
        showAppToast("Syncing 'Spotify: Liked Songs' & Favorites in the background...")
        scope.launch {
            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                isActive = true,
                taskTitle = "Spotify: Liked Songs",
                statusMessage = "Fetching Spotify Liked Songs...",
                current = 0,
                total = expectedTotal.coerceAtLeast(0)
            )
            _syncIndicatorState.value = SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs...")

            var token = getValidAccessToken()
            var tracks = if (token != null) {
                SpotifyApi.getAllLikedTracks(
                    accessToken = token,
                    onProgress = { fetched, total ->
                        _backgroundSyncState.value = SpotifyBackgroundSyncState(
                            isActive = true,
                            taskTitle = "Spotify: Liked Songs",
                            statusMessage = "Fetching Spotify Liked Songs ($fetched / $total)...",
                            current = fetched,
                            total = total
                        )
                        _syncIndicatorState.value =
                            SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs ($fetched / $total)...")
                    }
                )
            } else emptyList()

            if (tracks.isEmpty() && isLoggedIn()) {
                token = getValidAccessToken(forceRefresh = true)
                if (token != null) {
                    tracks = SpotifyApi.getAllLikedTracks(
                        accessToken = token,
                        onProgress = { fetched, total ->
                            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                                isActive = true,
                                taskTitle = "Spotify: Liked Songs",
                                statusMessage = "Fetching Spotify Liked Songs ($fetched / $total)...",
                                current = fetched,
                                total = total
                            )
                            _syncIndicatorState.value =
                                SpotifySyncIndicatorState.Syncing("Fetching Spotify Liked Songs ($fetched / $total)...")
                        }
                    )
                }
            }

            if (tracks.isEmpty()) {
                val errMsg = if (!isLoggedIn()) {
                    "Connect your Spotify account first to import Liked Songs."
                } else {
                    "No Liked Songs found on your Spotify account."
                }
                _syncIndicatorState.value = SpotifySyncIndicatorState.Error(errMsg)
                _backgroundSyncState.value = SpotifyBackgroundSyncState(
                    isActive = false,
                    taskTitle = "Spotify: Liked Songs",
                    errorMessage = errMsg
                )
                showAppToast(errMsg)
                return@launch
            }

            val syncResult = importOrSyncSpotifyTracksToPlaylist(
                targetName = "Spotify: Liked Songs",
                targetBrowseId = "spotify:liked",
                thumbnailUrl = tracks.firstOrNull()?.album?.images?.firstOrNull()?.url,
                remoteTracks = tracks,
                onProgress = { done, total, title ->
                    _backgroundSyncState.value = SpotifyBackgroundSyncState(
                        isActive = true,
                        taskTitle = "Spotify: Liked Songs",
                        statusMessage = "Syncing ($done/$total): $title",
                        current = done,
                        total = total
                    )
                }
            )

            val importedCount = syncResult.totalTracks
            val newlyAddedCount = syncResult.addedCount
            val alreadyPresentCount = syncResult.alreadyPresentCount
            val removedCount = syncResult.removedCount

            recordSyncEvent(
                songTitle = "Spotify: Liked Songs",
                artistName = "${tracks.size} tracks",
                action = if (syncResult.wasExistingPlaylist) "Updated Playlist" else "Imported Playlist",
                success = importedCount > 0,
                details = "Total $importedCount tracks (+$newlyAddedCount new, $alreadyPresentCount kept, -$removedCount removed)"
            )

            val doneMsg = when {
                syncResult.wasExistingPlaylist && (newlyAddedCount > 0 || removedCount > 0) ->
                    "Synced 'Spotify: Liked Songs': +$newlyAddedCount new, -$removedCount removed ($importedCount total)"
                syncResult.wasExistingPlaylist ->
                    "'Spotify: Liked Songs' matched with Spotify ($importedCount songs)"
                else ->
                    "Imported $importedCount songs to 'Spotify: Liked Songs'"
            }
            _syncIndicatorState.value = SpotifySyncIndicatorState.Success("$doneMsg ✓")
            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                isActive = false,
                taskTitle = "Spotify: Liked Songs",
                current = importedCount,
                total = tracks.size,
                completionMessage = doneMsg
            )
            showAppToast(doneMsg)
        }
    }

    /**
     * Launches importing Spotify Liked Songs into local Favorites in the background.
     */
    fun startImportLikedSongsToFavoritesInBackground() {
        showAppToast("Syncing Spotify Liked Songs to Favorites in the background...")
        scope.launch {
            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                isActive = true,
                taskTitle = "Spotify Liked Songs → Favorites",
                statusMessage = "Fetching Spotify Liked Songs...",
                current = 0,
                total = 0
            )
            val res = importSpotifyLikedSongsToFavoritesDetailed { current, total, title ->
                _backgroundSyncState.value = SpotifyBackgroundSyncState(
                    isActive = true,
                    taskTitle = "Spotify Liked Songs → Favorites",
                    statusMessage = title,
                    current = current,
                    total = total
                )
            }
            if (res.totalMatchedCount > 0) {
                val doneMsg = when {
                    res.newlyAddedCount > 0 && res.removedFromFavoritesCount > 0 ->
                        "Synced Favorites: +${res.newlyAddedCount} new, -${res.removedFromFavoritesCount} removed (${res.totalMatchedCount} total)"
                    res.removedFromFavoritesCount > 0 ->
                        "Removed ${res.removedFromFavoritesCount} unliked song(s) from Favorites (${res.totalMatchedCount} total)"
                    res.newlyAddedCount > 0 && res.alreadyInFavoritesCount > 0 ->
                        "Added ${res.newlyAddedCount} new song(s) to Favorites (${res.totalMatchedCount} total)"
                    res.newlyAddedCount == 0 && res.alreadyInFavoritesCount > 0 ->
                        "Favorites matched with Spotify (${res.totalMatchedCount} songs)"
                    else ->
                        "Imported ${res.totalMatchedCount} liked songs into Favorites"
                }
                _backgroundSyncState.value = SpotifyBackgroundSyncState(
                    isActive = false,
                    taskTitle = "Spotify Liked Songs → Favorites",
                    current = res.totalMatchedCount,
                    total = res.totalSpotifyTracks,
                    completionMessage = doneMsg
                )
                showAppToast(doneMsg)
            } else {
                val errMsg = "Could not import Liked Songs. Check your Spotify session."
                _backgroundSyncState.value = SpotifyBackgroundSyncState(
                    isActive = false,
                    taskTitle = "Spotify Liked Songs → Favorites",
                    errorMessage = errMsg
                )
                showAppToast(errMsg)
            }
        }
    }

    /**
     * Launches syncing an existing local Spotify playlist in the background so the user can leave
     * the playlist screen and play any song without interrupting the sync.
     */
    fun startSyncSpotifyPlaylistInBackground(
        playlist: Playlist,
        existingSongs: List<Song>,
        showToastFeedback: Boolean = true
    ) {
        val currentBg = _backgroundSyncState.value
        if (currentBg.isActive && currentBg.activePlaylistId == playlist.id) return

        if (showToastFeedback) {
            showAppToast("Syncing '${playlist.name}' in the background...")
        }

        scope.launch {
            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                isActive = true,
                taskTitle = playlist.name,
                statusMessage = "Syncing '${playlist.name}' with Spotify...",
                current = 0,
                total = 0,
                activePlaylistId = playlist.id
            )

            val result = syncSpotifyPlaylist(
                playlist = playlist,
                existingSongs = existingSongs
            ) { current, total, title ->
                _backgroundSyncState.value = SpotifyBackgroundSyncState(
                    isActive = true,
                    taskTitle = playlist.name,
                    statusMessage = "Syncing ($current/$total): $title",
                    current = current,
                    total = total,
                    activePlaylistId = playlist.id
                )
            }

            result.fold(
                onSuccess = { summary ->
                    val msg = when {
                        summary.addedCount > 0 || summary.removedCount > 0 ->
                            "Synced '${playlist.name}': +${summary.addedCount} new, -${summary.removedCount} removed (${summary.totalTracks} tracks)"
                        else ->
                            "'${playlist.name}' is up to date with Spotify (${summary.totalTracks} tracks)"
                    }
                    _backgroundSyncState.value = SpotifyBackgroundSyncState(
                        isActive = false,
                        taskTitle = playlist.name,
                        current = summary.totalTracks,
                        total = summary.totalTracks,
                        completionMessage = msg
                    )
                    if (showToastFeedback) {
                        showAppToast(msg)
                    }
                },
                onFailure = { err ->
                    val errMsg = err.message ?: "Failed to sync playlist with Spotify"
                    _backgroundSyncState.value = SpotifyBackgroundSyncState(
                        isActive = false,
                        taskTitle = playlist.name,
                        errorMessage = errMsg
                    )
                    if (showToastFeedback) {
                        showAppToast(errMsg)
                    }
                }
            )
        }
    }

    /**
     * Launches importing all followed Spotify artists in the background.
     */
    fun startImportAllFollowedArtistsInBackground(
        prefetchedArtists: List<app.vitune.providers.spotify.models.SpotifyArtistFull> = emptyList()
    ) {
        showAppToast("Importing followed Spotify artists in the background...")
        scope.launch {
            _backgroundSyncState.value = SpotifyBackgroundSyncState(
                isActive = true,
                taskTitle = "Spotify Followed Artists",
                statusMessage = if (prefetchedArtists.isNotEmpty()) {
                    "Importing ${prefetchedArtists.size} followed artists..."
                } else {
                    "Fetching followed artists from Spotify..."
                }
            )
            val count = if (prefetchedArtists.isNotEmpty()) {
                val c = upsertSpotifyArtists(prefetchedArtists)
                recordSyncEvent(
                    songTitle = "Spotify Followed Artists",
                    artistName = "$c artists",
                    action = "Imported Artists",
                    success = c > 0,
                    details = "1-click imported $c followed artists"
                )
                _syncIndicatorState.value = SpotifySyncIndicatorState.Success("Imported $c followed artists ✓")
                c
            } else {
                importAllFollowedArtistsFromSpotify { msg ->
                    _backgroundSyncState.value = _backgroundSyncState.value.copy(
                        isActive = true,
                        statusMessage = msg
                    )
                }
            }
            if (count > 0) {
                val doneMsg = "Imported all $count followed Spotify artists!"
                _backgroundSyncState.value = SpotifyBackgroundSyncState(
                    isActive = false,
                    taskTitle = "Spotify Followed Artists",
                    completionMessage = doneMsg
                )
                showAppToast(doneMsg)
            } else {
                val errMsg = "Could not find followed artists on your Spotify account."
                _backgroundSyncState.value = SpotifyBackgroundSyncState(
                    isActive = false,
                    taskTitle = "Spotify Followed Artists",
                    errorMessage = errMsg
                )
                showAppToast(errMsg)
            }
        }
    }
}
