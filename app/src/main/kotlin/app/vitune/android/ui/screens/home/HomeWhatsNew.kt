package app.vitune.android.ui.screens.home

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Artist
import app.vitune.android.models.Song
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.query
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.ShimmerHost
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.NonQueuedMediaItemMenu
import app.vitune.android.ui.items.SongItemPlaceholder
import app.vitune.android.ui.screens.Route
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.bold
import app.vitune.android.utils.color
import app.vitune.android.utils.forcePlay
import app.vitune.android.utils.medium
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.compose.persist.persist
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.NavigationEndpoint
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.discoverPage
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import app.vitune.providers.spotify.models.SpotifyReleaseItem
import app.vitune.providers.spotify.requests.SpotifyApi
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun formatRelativeReleaseDate(releaseDate: String): String {
    if (releaseDate.isBlank()) return "New release"
    return try {
        val pattern = when {
            Regex("""^\d{4}-\d{2}-\d{2}$""").matches(releaseDate.trim()) -> "yyyy-MM-dd"
            Regex("""^\d{4}-\d{2}$""").matches(releaseDate.trim()) -> "yyyy-MM"
            Regex("""^\d{4}$""").matches(releaseDate.trim()) -> "yyyy"
            else -> return releaseDate
        }
        val sdf = SimpleDateFormat(pattern, Locale.US)
        val parsed = sdf.parse(releaseDate.trim()) ?: return releaseDate
        val diffMs = (System.currentTimeMillis() - parsed.time).coerceAtLeast(0L)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        when {
            hours < 1L -> "Just now"
            hours < 24L -> "$hours hours ago"
            days == 1L -> "1 day ago"
            days < 7L -> "$days days ago"
            days < 30L -> "${(days / 7).coerceAtLeast(1)} weeks ago"
            days < 365L -> "${(days / 30).coerceAtLeast(1)} months ago"
            else -> releaseDate
        }
    } catch (_: Exception) {
        releaseDate
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Route
@Composable
fun HomeWhatsNew(
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onSearchClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val windowInsets = LocalPlayerAwareWindowInsets.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var releases by persist<List<SpotifyReleaseItem>?>(tag = "home/whatsNew/releases")
    var isLoadingReleases by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var hasAutoRefreshedSession by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Albums", "Singles"
    var playingReleaseId by remember { mutableStateOf<String?>(null) }
    val savedReleaseIds = remember { mutableStateMapOf<String, Boolean>() }

    val isSpotifyConnected = DataPreferences.spotifySpDcCookie.isNotBlank() ||
        DataPreferences.spotifyAccessToken.isNotBlank()

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            refreshTrigger++
            isRefreshing = false
        }
    }

    LaunchedEffect(
        isSpotifyConnected,
        DataPreferences.spotifyUsername,
        DataPreferences.spotifySpDcCookie,
        refreshTrigger
    ) {
        if (!isSpotifyConnected) {
            releases = null
            DataPreferences.cachedSpotifyReleases = emptyList()
            isLoadingReleases = false
            return@LaunchedEffect
        }
        if (releases.isNullOrEmpty() && DataPreferences.cachedSpotifyReleases.isNotEmpty()) {
            val dedupedCache = DataPreferences.cachedSpotifyReleases
                .filter { it.name.isNotBlank() }
                .distinctBy { it.id.trim().lowercase().ifBlank { "${it.name.lowercase().trim()}_${it.albumType}" } }
                .distinctBy { "release_${it.id}_${it.name}_${it.albumType}" }
            releases = dedupedCache
            if (dedupedCache.size != DataPreferences.cachedSpotifyReleases.size) {
                DataPreferences.cachedSpotifyReleases = dedupedCache
            }
        }
        if (refreshTrigger == 0 && hasAutoRefreshedSession && !releases.isNullOrEmpty()) {
            return@LaunchedEffect
        }
        hasAutoRefreshedSession = true
        isLoadingReleases = true

        val fetchedReleases = withContext(Dispatchers.IO) {
            val accessToken = runCatching {
                SpotifySyncManager.getValidAccessToken()
            }.getOrNull()
            if (accessToken.isNullOrBlank()) {
                return@withContext emptyList<SpotifyReleaseItem>()
            }

            val localArtists: List<Artist> = runCatching {
                Database.allArtists().filter { it.bookmarkedAt != null }
            }.getOrDefault(emptyList())

            val localSongs: List<Song> = runCatching {
                (Database.favorites().firstOrNull().orEmpty() +
                    Database.trending(limit = 15).firstOrNull().orEmpty())
            }.getOrDefault(emptyList())

            val extraArtists = buildList<Pair<String?, String>> {
                localArtists.forEach { artist ->
                    val name = artist.name?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    add(artist.id to name)
                }
                localSongs.forEach { song ->
                    val primaryArtist = song.artistsText
                        ?.split(",", "&", "feat.")
                        ?.firstOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    if (none { it.second.equals(primaryArtist, ignoreCase = true) }) {
                        add(null to primaryArtist)
                    }
                }
            }

            var result = runCatching {
                SpotifyApi.getFollowedArtistsLatestReleases(
                    accessToken = accessToken,
                    extraArtists = extraArtists,
                    maxReleases = 200,
                    onPartialResults = { partial ->
                        if (partial.isNotEmpty()) {
                            coroutineScope.launch(Dispatchers.Main) {
                                releases = partial
                            }
                        }
                    }
                )
            }.getOrDefault(emptyList())

            if (result.isEmpty()) {
                val refreshedToken = runCatching {
                    SpotifySyncManager.getValidAccessToken(forceRefresh = true)
                }.getOrNull()
                if (!refreshedToken.isNullOrBlank()) {
                    result = runCatching {
                        SpotifyApi.getFollowedArtistsLatestReleases(
                            accessToken = refreshedToken,
                            extraArtists = extraArtists,
                            maxReleases = 200,
                            onPartialResults = { partial ->
                                if (partial.isNotEmpty()) {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        releases = partial
                                    }
                                }
                            }
                        )
                    }.getOrDefault(emptyList())
                }
            }
            result
        }

        if (fetchedReleases.isNotEmpty()) {
            releases = fetchedReleases
            DataPreferences.cachedSpotifyReleases = fetchedReleases
        }
        isLoadingReleases = false
    }

    suspend fun resolveReleaseLeadSong(release: SpotifyReleaseItem): Song? = withContext(Dispatchers.IO) {
        val primaryArtist = release.artists.firstOrNull().orEmpty()
        val trackTitle = release.firstTrackName ?: release.name
        val queryStr = "$trackTitle $primaryArtist".trim()
        val ytItem = Innertube.searchPage(
            body = SearchBody(
                query = queryStr,
                params = Innertube.SearchFilter.Song.value
            ),
            fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
        )?.getOrNull()?.items?.firstOrNull() ?: return@withContext null

        Song(
            id = ytItem.key,
            title = trackTitle,
            artistsText = release.artists.joinToString(", ").ifBlank {
                ytItem.authors?.joinToString(", ") { it.name.orEmpty() }.orEmpty()
            },
            durationText = ytItem.durationText,
            thumbnailUrl = release.coverUrl ?: ytItem.thumbnail?.url,
            explicit = release.explicit || ytItem.explicit
        )
    }

    fun playRelease(release: SpotifyReleaseItem) {
        if (playingReleaseId == release.id) return
        playingReleaseId = release.id
        coroutineScope.launch {
            val song = resolveReleaseLeadSong(release)
            playingReleaseId = null
            if (song != null) {
                query {
                    Database.insert(song)
                }
                val mediaItem = song.asMediaItem
                binder?.stopRadio()
                binder?.player?.forcePlay(mediaItem, source = release.name)
                binder?.setupRadio(
                    NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId)
                )
            } else {
                Toast.makeText(context, "Couldn't find stream for ${release.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun previewRelease(release: SpotifyReleaseItem) {
        coroutineScope.launch {
            if (release.id.startsWith("MPREb_") || release.id.startsWith("spotify:album:")) {
                onAlbumClick(release.id)
                return@launch
            }
            if (Regex("""^[A-Za-z0-9]{22}$""").matches(release.id) &&
                (release.totalTracks > 1 || release.albumType.equals("album", ignoreCase = true) || release.albumType.equals("compilation", ignoreCase = true))
            ) {
                onAlbumClick("spotify:album:${release.id}")
                return@launch
            }
            val primaryArtist = release.artists.firstOrNull().orEmpty()
            if (release.totalTracks > 1 || release.albumType.equals("album", ignoreCase = true)) {
                val ytAlbum = withContext(Dispatchers.IO) {
                    Innertube.searchPage(
                        body = SearchBody(
                            query = "${release.name} $primaryArtist".trim(),
                            params = Innertube.SearchFilter.Album.value
                        ),
                        fromMusicShelfRendererContent = Innertube.AlbumItem.Companion::from
                    )?.getOrNull()?.items?.firstOrNull()
                }
                if (ytAlbum != null) {
                    onAlbumClick(ytAlbum.key)
                    return@launch
                }
            }
            playRelease(release)
        }
    }

    fun openReleaseArtist(release: SpotifyReleaseItem) {
        val artistName = release.artists.firstOrNull()?.trim().orEmpty()
        val spotifyArtistId = release.artistIds.firstOrNull()?.trim().orEmpty()
        if (artistName.isBlank() && spotifyArtistId.isBlank()) return
        coroutineScope.launch {
            val localMatch = withContext(Dispatchers.IO) {
                Database.allArtists().firstOrNull { a ->
                    (artistName.isNotBlank() && a.name?.trim()?.equals(artistName, ignoreCase = true) == true) ||
                        (spotifyArtistId.isNotBlank() && a.id == "spotify:artist:$spotifyArtistId")
                }
            }
            if (localMatch != null) {
                onArtistClick(localMatch.id)
                return@launch
            }
            if (spotifyArtistId.isNotBlank()) {
                onArtistClick("spotify:artist:$spotifyArtistId")
                return@launch
            }
            val ytArtist = withContext(Dispatchers.IO) {
                Innertube.searchPage(
                    body = SearchBody(
                        query = artistName,
                        params = Innertube.SearchFilter.Artist.value
                    ),
                    fromMusicShelfRendererContent = Innertube.ArtistItem.Companion::from
                )?.getOrNull()?.items?.firstOrNull()
            }
            if (ytArtist != null) {
                onArtistClick(ytArtist.key)
            }
        }
    }

    fun saveReleaseToFavorites(release: SpotifyReleaseItem) {
        coroutineScope.launch {
            val song = resolveReleaseLeadSong(release)
            if (song != null) {
                val likedSong = song.copy(likedAt = System.currentTimeMillis())
                query {
                    Database.insert(likedSong)
                    Database.like(likedSong.id, System.currentTimeMillis())
                }
                SpotifySyncManager.syncLikedSongIfEnabled(likedSong)
                savedReleaseIds[release.id] = true
                Toast.makeText(context, "Added \"${release.name}\" to Favorites", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val (currentMediaId, playing) = playingSong(binder)
    val rawReleases = releases.orEmpty()
    val allReleases = remember(rawReleases, selectedFilter) {
        val filtered = when (selectedFilter) {
            "Albums" -> rawReleases.filter {
                it.albumType.equals("album", ignoreCase = true) ||
                    it.albumType.equals("compilation", ignoreCase = true) ||
                    it.totalTracks > 3
            }
            "Singles" -> rawReleases.filter {
                !it.albumType.equals("album", ignoreCase = true) &&
                    !it.albumType.equals("compilation", ignoreCase = true) &&
                    it.totalTracks <= 3
            }
            else -> rawReleases
        }
        filtered
            .filter { it.name.isNotBlank() }
            .distinctBy { it.id.trim().lowercase().ifBlank { "${it.name.lowercase().trim()}_${it.albumType}" } }
            .distinctBy { "release_${it.id}_${it.name}_${it.albumType}" }
    }

    Box(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { isRefreshing = true },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = lazyListState,
                contentPadding = LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues(),
                modifier = Modifier
                    .background(colorPalette.background0)
                    .fillMaxSize()
            ) {
                item(
                    key = "header",
                    contentType = 0
                ) {
                    Header(title = stringResource(R.string.whats_new)) {
                        if (isLoadingReleases) {
                            CircularProgressIndicator(
                                color = colorPalette.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(18.dp)
                            )
                        }
                        HeaderIconButton(
                            icon = R.drawable.sync,
                            color = colorPalette.text,
                            onClick = { refreshTrigger++ }
                        )
                    }
                }

                if (rawReleases.isNotEmpty()) {
                    item(key = "filter_chips") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            listOf("All", "Albums", "Singles").forEach { filterOption ->
                                val isSelected = selectedFilter == filterOption
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (isSelected) colorPalette.accent else colorPalette.background1
                                        )
                                        .clickable { selectedFilter = filterOption }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    BasicText(
                                        text = if (filterOption == "Singles") "Singles & EPs" else filterOption,
                                        style = typography.xs.semiBold.color(
                                            if (isSelected) colorPalette.onAccent else colorPalette.textSecondary
                                        )
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            BasicText(
                                text = "${allReleases.size} releases",
                                style = typography.xxs.medium.color(colorPalette.textSecondary)
                            )
                        }
                    }
                }

                if (allReleases.isEmpty() && isLoadingReleases) {
                    item(key = "releases_loading") {
                        ShimmerHost {
                            repeat(5) {
                                SongItemPlaceholder(thumbnailSize = 72.dp)
                            }
                        }
                    }
                } else if (allReleases.isEmpty()) {
                    item(key = "releases_empty") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 48.dp)
                        ) {
                            BasicText(
                                text = "No new releases loaded",
                                style = typography.m.semiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            BasicText(
                                text = "Pull down or tap Sync in the header to fetch the latest Spotify releases.",
                                style = typography.xs.secondary
                            )
                        }
                    }
                } else {
                    items(
                        items = allReleases,
                        key = { "release_${it.id}_${it.name}_${it.albumType}" }
                    ) { release ->
                        val isSaved = savedReleaseIds[release.id] == true
                        val isResolvingPlay = playingReleaseId == release.id

                        ThemedReleaseCard(
                            release = release,
                            isSaved = isSaved,
                            isResolvingPlay = isResolvingPlay,
                            onPlayClick = { playRelease(release) },
                            onPreviewClick = { previewRelease(release) },
                            onArtistClick = { openReleaseArtist(release) },
                            onSaveClick = { saveReleaseToFavorites(release) },
                            onMenuClick = {
                                coroutineScope.launch {
                                    val song = resolveReleaseLeadSong(release)
                                    if (song != null) {
                                        query { Database.insert(song) }
                                        menuState.display {
                                            NonQueuedMediaItemMenu(
                                                onDismiss = menuState::hide,
                                                mediaItem = song.asMediaItem
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyListState = lazyListState,
            icon = R.drawable.search,
            onClick = onSearchClick
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemedReleaseCard(
    release: SpotifyReleaseItem,
    isSaved: Boolean,
    isResolvingPlay: Boolean,
    onPlayClick: () -> Unit,
    onPreviewClick: () -> Unit,
    onArtistClick: () -> Unit,
    onSaveClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography, _, thumbnailShape) = LocalAppearance.current
    val artistsText = remember(release.artists) {
        release.artists.joinToString(", ").ifBlank { "Spotify Artist" }
    }
    val relativeDate = remember(release.releaseDate) {
        formatRelativeReleaseDate(release.releaseDate)
    }
    val isAlbum = release.totalTracks > 1 || release.albumType.equals("album", ignoreCase = true)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(thumbnailShape)
            .background(colorPalette.background1)
            .combinedClickable(
                onClick = onPlayClick,
                onLongClick = onMenuClick
            )
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            AsyncImage(
                model = release.coverUrl,
                contentDescription = release.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(76.dp)
                    .clip(thumbnailShape)
                    .background(colorPalette.background2)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onArtistClick)
                ) {
                    if (release.explicit) {
                        Image(
                            painter = painterResource(R.drawable.explicit),
                            contentDescription = "Explicit",
                            colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(14.dp)
                        )
                    }

                    BasicText(
                        text = artistsText,
                        style = typography.s.bold.color(colorPalette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                BasicText(
                    text = release.name,
                    style = typography.xs.semiBold.color(colorPalette.textSecondary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colorPalette.background2)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        BasicText(
                            text = if (isAlbum) "Album" else "Single",
                            style = typography.xxs.semiBold.color(colorPalette.accent)
                        )
                    }

                    BasicText(
                        text = "• $relativeDate",
                        style = typography.xxs.medium.color(colorPalette.textSecondary),
                        maxLines = 1
                    )
                }
            }

            Image(
                painter = painterResource(R.drawable.ellipsis_horizontal),
                contentDescription = "Release options",
                colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                modifier = Modifier
                    .size(28.dp)
                    .padding(4.dp)
                    .clickable(onClick = onMenuClick)
            )
        }

        val previewTracks = remember(release.trackPreviewNames, release.name) {
            release.trackPreviewNames.ifEmpty { listOf(release.name) }.joinToString(" • ")
        }
        if (previewTracks.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))

            val songCountLabel = if (release.totalTracks <= 1) "1 song • " else "${release.totalTracks} songs • "
            BasicText(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = colorPalette.text,
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(songCountLabel)
                    }
                    withStyle(
                        SpanStyle(
                            color = colorPalette.textSecondary,
                            fontWeight = FontWeight.Normal
                        )
                    ) {
                        append(previewTracks)
                    }
                },
                style = typography.xs,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colorPalette.background2)
                    .clickable(onClick = onPreviewClick)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.disc),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                BasicText(
                    text = if (isAlbum) "Open album" else "Play single",
                    style = typography.xxs.semiBold.color(colorPalette.text)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(
                        width = 1.5.dp,
                        color = if (isSaved) colorPalette.accent else colorPalette.textSecondary.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
                    .clickable(onClick = onSaveClick)
            ) {
                Image(
                    painter = painterResource(if (isSaved) R.drawable.heart else R.drawable.add),
                    contentDescription = "Save release to Favorites",
                    colorFilter = ColorFilter.tint(
                        if (isSaved) colorPalette.accent else colorPalette.text
                    ),
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colorPalette.accent)
                    .clickable(onClick = onPlayClick)
            ) {
                if (isResolvingPlay) {
                    CircularProgressIndicator(
                        color = colorPalette.onAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.play),
                        contentDescription = "Play release",
                        colorFilter = ColorFilter.tint(colorPalette.onAccent),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
