package app.vitune.android.ui.screens.spotify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.vitune.android.Database
import app.vitune.android.models.Playlist
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.ui.components.themed.Switch
import app.vitune.android.utils.CsvParser
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.YouTubeService
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.LocalAppearance
import app.vitune.android.models.Artist
import app.vitune.android.models.SongPlaylistMap
import app.vitune.android.transaction
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import app.vitune.providers.spotify.models.SpotifyArtistFull
import app.vitune.providers.spotify.models.SpotifyPlaylist
import app.vitune.providers.spotify.requests.SpotifyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun SpotifyImportDialog(
    onDismiss: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val scope = rememberCoroutineScope()

    var isLoggedIn by remember { mutableStateOf(SpotifySyncManager.isLoggedIn()) }
    var showLoginDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    val syncHistory by SpotifySyncManager.syncHistory.collectAsState()
    val bgSyncState by SpotifySyncManager.backgroundSyncState.collectAsState()

    var playlistUrlInput by rememberSaveable { mutableStateOf("") }
    var artistUrlInput by rememberSaveable { mutableStateOf("") }
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var followedArtists by remember { mutableStateOf<List<SpotifyArtistFull>>(emptyList()) }
    var importedArtistIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showFollowedArtistsList by rememberSaveable { mutableStateOf(false) }
    var likedSongsTotal by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var completionMessage by remember { mutableStateOf<String?>(null) }

    suspend fun loadSpotifyLibrary() {
        isLoading = true
        errorMessage = null
        loadingMessage = "Loading your Spotify library..."
        var token = SpotifySyncManager.getValidAccessToken()
        if (token == null) {
            isLoading = false
            isLoggedIn = false
            return
        }
        val fetchedPlaylists = SpotifyApi.getUserPlaylists(token)?.items.orEmpty()
        val likedPage = SpotifyApi.getLikedTracksPage(token, limit = 1, offset = 0)
        var fetchedArtists = runCatching {
            SpotifyApi.getFollowedArtists(
                accessToken = token,
                onProgress = { count ->
                    scope.launch(Dispatchers.Main) {
                        loadingMessage = "Loading your Spotify library ($count followed artists)..."
                    }
                }
            )
        }.getOrDefault(emptyList())

        if (fetchedArtists.isEmpty()) {
            val refreshed = SpotifySyncManager.getValidAccessToken(forceRefresh = true)
            if (refreshed != null) {
                token = refreshed
                fetchedArtists = runCatching {
                    SpotifyApi.getFollowedArtists(
                        accessToken = token,
                        onProgress = { count ->
                            scope.launch(Dispatchers.Main) {
                                loadingMessage = "Loading your Spotify library ($count followed artists)..."
                            }
                        }
                    )
                }.getOrDefault(emptyList())
            }
        }

        playlists = fetchedPlaylists
        likedSongsTotal = likedPage?.total ?: 0
        followedArtists = fetchedArtists
        isLoading = false
    }

    fun importArtistByInput(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        isLoading = true
        errorMessage = null
        completionMessage = null
        progressCurrent = 0
        progressTotal = 0
        loadingMessage = "Resolving artist '$trimmed'..."
        scope.launch(Dispatchers.IO) {
            val token = if (isLoggedIn) SpotifySyncManager.getValidAccessToken() else null
            val spotifyArtist = if (SpotifyApi.extractArtistId(trimmed) != null) {
                SpotifyApi.getArtistById(trimmed, token)
            } else null

            val searchName = spotifyArtist?.name ?: trimmed
            val ytArtist = Innertube.searchPage(
                body = SearchBody(
                    query = searchName,
                    params = Innertube.SearchFilter.Artist.value
                ),
                fromMusicShelfRendererContent = Innertube.ArtistItem::from
            )?.getOrNull()?.items?.firstOrNull()

            val finalId = ytArtist?.key ?: spotifyArtist?.id?.let { "spotify:artist:$it" }
            val finalName = spotifyArtist?.name ?: ytArtist?.info?.name ?: searchName.takeIf { !it.startsWith("http") }

            withContext(Dispatchers.Main) {
                isLoading = false
                if (!finalId.isNullOrBlank() && !finalName.isNullOrBlank()) {
                    val now = System.currentTimeMillis()
                    transaction {
                        Database.upsert(
                            Artist(
                                id = finalId,
                                name = finalName,
                                thumbnailUrl = spotifyArtist?.imageUrl ?: ytArtist?.thumbnail?.url,
                                timestamp = now,
                                bookmarkedAt = now
                            )
                        )
                    }
                    artistUrlInput = ""
                    completionMessage = "Imported artist '$finalName' to your Artists library!"
                } else {
                    errorMessage = "Could not resolve artist from '$trimmed'. Paste a valid Spotify artist link or artist name."
                }
            }
        }
    }

    fun importSingleSpotifyArtist(spArtist: SpotifyArtistFull) {
        importedArtistIds = importedArtistIds + spArtist.id
        scope.launch {
            SpotifySyncManager.upsertSpotifyArtists(listOf(spArtist))
            SpotifySyncManager.recordSyncEvent(
                songTitle = spArtist.name,
                artistName = "Spotify Artist",
                action = "Imported Artist",
                success = true,
                details = "1-click imported to Artists library"
            )
        }
    }

    fun importAllFollowedArtists() {
        SpotifySyncManager.startImportAllFollowedArtistsInBackground(followedArtists)
        onDismiss()
    }

    LaunchedEffect(isLoggedIn) {
        SpotifySyncManager.refreshHistoryFromPreferences()
        if (isLoggedIn) {
            loadSpotifyLibrary()
        }
    }

    if (showHistoryDialog) {
        SpotifySyncHistoryDialog(
            onDismiss = { showHistoryDialog = false }
        )
        return
    }

    if (showLoginDialog) {
        SpotifyLoginDialog(
            onDismiss = {
                showLoginDialog = false
            },
            onLoginSuccess = {
                showLoginDialog = false
                isLoggedIn = true
            }
        )
        return
    }

    fun importLikedSongsAsPlaylist() {
        SpotifySyncManager.startImportLikedSongsAsPlaylistInBackground(likedSongsTotal)
        onDismiss()
    }

    fun importSpotifyPlaylistByLinkOrId(rawInput: String, fallbackName: String? = null) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            errorMessage = "Please enter a Spotify playlist link or ID first."
            return
        }
        playlistUrlInput = ""
        SpotifySyncManager.startImportPlaylistByLinkOrIdInBackground(
            rawInput = trimmed,
            fallbackName = fallbackName
        )
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorPalette.background1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = "Spotify Library & Sync",
                            style = typography.m.semiBold
                        )
                        BasicText(
                            text = if (isLoggedIn && DataPreferences.spotifyUsername.isNotBlank()) {
                                "Connected as ${DataPreferences.spotifyUsername}"
                            } else {
                                "Import playlists by link or connect your account"
                            },
                            style = typography.xxs.secondary
                        )
                    }

                    if (!isLoading) {
                        DialogTextButton(
                            text = if (isLoggedIn) "Switch Account" else "Connect Account",
                            primary = !isLoggedIn,
                            onClick = {
                                if (isLoggedIn) {
                                    SpotifySyncManager.logout()
                                    isLoggedIn = false
                                }
                                showLoginDialog = true
                            },
                            modifier = Modifier.testTag("spotify_switch_account_button")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live Sync Status Indicator + Quick tap to open Sync History Log
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpotifySyncStatusIndicator(
                        alwaysVisibleWhenConnected = true,
                        onClick = { showHistoryDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DialogTextButton(
                        text = "History (${syncHistory.size})",
                        onClick = { showHistoryDialog = true },
                        modifier = Modifier.testTag("spotify_open_history_button")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (bgSyncState.isActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.background0)
                            .padding(12.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            BasicText(
                                text = "Background Sync Active: ${bgSyncState.taskTitle}",
                                style = typography.xs.semiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            BasicText(
                                text = bgSyncState.statusMessage,
                                style = typography.xxs.secondary
                            )
                            if (bgSyncState.total > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        (bgSyncState.current.toFloat() / bgSyncState.total.toFloat()).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                BasicText(
                                    text = "${bgSyncState.current} / ${bgSyncState.total} tracks • Continues if you close this dialog",
                                    style = typography.xxs.secondary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                when {
                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        BasicText(
                            text = loadingMessage,
                            style = typography.xs.semiBold.center
                        )
                        if (progressTotal > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = {
                                    (progressCurrent.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            BasicText(
                                text = "$progressCurrent / $progressTotal tracks",
                                style = typography.xxs.secondary.center
                            )
                        }
                    }

                    completionMessage != null -> {
                        BasicText(
                            text = completionMessage.orEmpty(),
                            style = typography.xs.semiBold.center,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogTextButton(
                                text = "Import Another",
                                onClick = { completionMessage = null }
                            )
                            DialogTextButton(
                                text = "View Sync Log",
                                onClick = { showHistoryDialog = true }
                            )
                            DialogTextButton(
                                text = "Done",
                                primary = true,
                                onClick = onDismiss,
                                modifier = Modifier.testTag("spotify_import_done_button")
                            )
                        }
                    }

                    errorMessage != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorPalette.red.copy(alpha = 0.14f))
                                .padding(14.dp)
                        ) {
                            BasicText(
                                text = errorMessage.orEmpty(),
                                style = typography.xs.semiBold.center.color(colorPalette.text),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogTextButton(
                                text = "Try Again",
                                primary = true,
                                onClick = { errorMessage = null }
                            )
                            if (isLoggedIn) {
                                DialogTextButton(
                                    text = "Reconnect Account",
                                    onClick = {
                                        errorMessage = null
                                        SpotifySyncManager.logout()
                                        isLoggedIn = false
                                        showLoginDialog = true
                                    }
                                )
                            }
                            DialogTextButton(
                                text = "Cancel",
                                onClick = onDismiss
                            )
                        }
                    }

                    else -> {
                        // Direct Spotify Playlist URL / Link Import Card (works with or without account login)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorPalette.background0)
                                .padding(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                BasicText(
                                    text = "Import by Spotify Playlist Link",
                                    style = typography.xs.semiBold
                                )
                                BasicText(
                                    text = "Paste any Spotify playlist URL, URI, or ID",
                                    style = typography.xxs.secondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = playlistUrlInput,
                                    onValueChange = { playlistUrlInput = it },
                                    placeholder = {
                                        BasicText(
                                            text = "https://open.spotify.com/playlist/...",
                                            style = typography.xxs.secondary
                                        )
                                    },
                                    singleLine = true,
                                    textStyle = typography.xs.copy(color = colorPalette.text),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colorPalette.accent,
                                        unfocusedBorderColor = colorPalette.background2,
                                        cursorColor = colorPalette.accent
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_playlist_link_input")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    DialogTextButton(
                                        text = "Import Playlist Link",
                                        primary = true,
                                        enabled = playlistUrlInput.isNotBlank(),
                                        onClick = {
                                            importSpotifyPlaylistByLinkOrId(playlistUrlInput)
                                        },
                                        modifier = Modifier.testTag("spotify_import_link_button")
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isLoggedIn) {
                            // 1-Click Spotify Followed Artists Import Card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorPalette.background0)
                                    .padding(12.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    BasicText(
                                        text = if (followedArtists.isNotEmpty()) {
                                            "Spotify Followed Artists (${followedArtists.size})"
                                        } else {
                                            "Spotify Followed Artists"
                                        },
                                        style = typography.xs.semiBold
                                    )
                                    BasicText(
                                        text = "Import all your followed artists in 1 click or tap any artist below",
                                        style = typography.xxs.secondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        DialogTextButton(
                                            text = if (followedArtists.isNotEmpty()) {
                                                "1-Click Import All (${followedArtists.size})"
                                            } else {
                                                "1-Click Import All"
                                            },
                                            primary = true,
                                            onClick = { importAllFollowedArtists() },
                                            modifier = Modifier.testTag("spotify_import_all_artists_button")
                                        )
                                        if (followedArtists.isNotEmpty()) {
                                            DialogTextButton(
                                                text = if (showFollowedArtistsList) "Hide List" else "Pick Artists",
                                                onClick = { showFollowedArtistsList = !showFollowedArtistsList }
                                            )
                                        }
                                    }

                                    if (showFollowedArtistsList) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 180.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(colorPalette.background1)
                                        ) {
                                            items(
                                                items = followedArtists,
                                                key = { it.id }
                                            ) { spArtist ->
                                                val isAdded = spArtist.id in importedArtistIds
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            importSingleSpotifyArtist(spArtist)
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    BasicText(
                                                        text = spArtist.name,
                                                        style = typography.xs.semiBold,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    BasicText(
                                                        text = if (isAdded) "Imported ✓" else "+ Import",
                                                        style = typography.xxs.semiBold.color(
                                                            if (isAdded) colorPalette.textSecondary else colorPalette.accent
                                                        )
                                                    )
                                                }
                                                HorizontalDivider(
                                                    color = colorPalette.background0,
                                                    thickness = 1.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Import Artist by Spotify Artist Link / URI or Name
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorPalette.background0)
                                .padding(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                BasicText(
                                    text = "Import Artist by Link or Name",
                                    style = typography.xs.semiBold
                                )
                                BasicText(
                                    text = "Paste a Spotify artist URL/URI or type any artist name",
                                    style = typography.xxs.secondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = artistUrlInput,
                                    onValueChange = { artistUrlInput = it },
                                    placeholder = {
                                        BasicText(
                                            text = "https://open.spotify.com/artist/... or Artist name",
                                            style = typography.xxs.secondary
                                        )
                                    },
                                    singleLine = true,
                                    textStyle = typography.xs.copy(color = colorPalette.text),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colorPalette.accent,
                                        unfocusedBorderColor = colorPalette.background2,
                                        cursorColor = colorPalette.accent
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("spotify_artist_link_input")
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    DialogTextButton(
                                        text = "Import Artist",
                                        primary = true,
                                        enabled = artistUrlInput.isNotBlank(),
                                        onClick = {
                                            importArtistByInput(artistUrlInput)
                                        },
                                        modifier = Modifier.testTag("spotify_import_artist_button")
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (isLoggedIn) {
                            // Real-time auto-sync toggle right inside the Spotify dialog
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorPalette.background0)
                                    .clickable(enabled = !isLoading) {
                                        DataPreferences.spotifyAutoSyncLikedSongs =
                                            !DataPreferences.spotifyAutoSyncLikedSongs
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    BasicText(
                                        text = "Auto-add liked songs to Spotify",
                                        style = typography.xs.semiBold
                                    )
                                    BasicText(
                                        text = "Liking a song in the app adds it to Spotify Liked Songs",
                                        style = typography.xxs.secondary
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    isChecked = DataPreferences.spotifyAutoSyncLikedSongs
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Pinned Spotify Liked Songs Card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorPalette.background0)
                                    .padding(12.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    BasicText(
                                        text = "Spotify Liked Songs" +
                                            if (likedSongsTotal > 0) " ($likedSongsTotal)" else "",
                                        style = typography.xs.semiBold
                                    )
                                    BasicText(
                                        text = "Syncs fresh A–Z list from Spotify, adds new tracks & removes unliked songs",
                                        style = typography.xxs.secondary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        DialogTextButton(
                                            text = "Sync to Favorites",
                                            primary = true,
                                            onClick = {
                                                SpotifySyncManager.startImportLikedSongsToFavoritesInBackground()
                                                onDismiss()
                                            },
                                            modifier = Modifier.testTag("spotify_import_liked_to_favorites")
                                        )

                                        DialogTextButton(
                                            text = "As Playlist",
                                            onClick = {
                                                importLikedSongsAsPlaylist()
                                            },
                                            modifier = Modifier.testTag("spotify_import_liked_as_playlist")
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            BasicText(
                                text = "Your Spotify Playlists (${playlists.size})",
                                style = typography.xs.semiBold,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(bottom = 6.dp)
                            )

                            if (playlists.isEmpty()) {
                                BasicText(
                                    text = "No custom playlists found on this account. You can paste any playlist link above!",
                                    style = typography.xxs.secondary.center,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colorPalette.background0)
                                ) {
                                    items(
                                        items = playlists,
                                        key = { it.id }
                                    ) { playlist ->
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    importSpotifyPlaylistByLinkOrId(
                                                        rawInput = playlist.id,
                                                        fallbackName = playlist.name
                                                    )
                                                }
                                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                        ) {
                                            BasicText(
                                                text = playlist.name,
                                                style = typography.xs.semiBold
                                            )
                                            val trackCount = playlist.tracks?.total
                                            val ownerName = playlist.owner?.displayName
                                            val subtitle = buildList {
                                                if (trackCount != null && trackCount > 0) add("$trackCount tracks")
                                                if (!ownerName.isNullOrBlank()) add("by $ownerName")
                                            }.joinToString(" • ")

                                            if (subtitle.isNotBlank()) {
                                                BasicText(
                                                    text = subtitle,
                                                    style = typography.xxs.secondary
                                                )
                                            }
                                        }
                                        HorizontalDivider(
                                            color = colorPalette.background1,
                                            thickness = 1.dp
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colorPalette.background0)
                                    .clickable { showLoginDialog = true }
                                    .padding(14.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    BasicText(
                                        text = "Want to sync Liked Songs & Account Playlists?",
                                        style = typography.xs.semiBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    BasicText(
                                        text = "Tap Connect Account above to sign in with Spotify and enable real-time Liked Songs sync.",
                                        style = typography.xxs.secondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        DialogTextButton(
                            text = "Cancel",
                            onClick = onDismiss,
                            modifier = Modifier.testTag("spotify_import_cancel_button")
                        )
                    }
                }
            }
        }
    }
}
