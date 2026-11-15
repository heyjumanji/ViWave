package app.vitune.android.ui.screens.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Song
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.query
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.ShimmerHost
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.NonQueuedMediaItemMenu
import app.vitune.android.ui.components.themed.TextPlaceholder
import app.vitune.android.ui.items.AlbumItem
import app.vitune.android.ui.items.AlbumItemPlaceholder
import app.vitune.android.ui.items.ArtistItem
import app.vitune.android.ui.items.ArtistItemPlaceholder
import app.vitune.android.ui.items.PlaylistItem
import app.vitune.android.ui.items.PlaylistItemPlaceholder
import app.vitune.android.ui.items.SongItem
import app.vitune.android.ui.items.SongItemPlaceholder
import app.vitune.android.ui.screens.Route
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.center
import app.vitune.android.utils.forcePlay
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.rememberSnapLayoutInfo
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.compose.persist.persist
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isLandscape
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.NavigationEndpoint
import app.vitune.providers.innertube.models.bodies.NextBody
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.nextPage
import app.vitune.providers.innertube.requests.relatedPage
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import app.vitune.providers.lastfm.LastFmApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private suspend fun fetchResilientRelatedPage(primarySong: Song?): Result<Innertube.RelatedPage?> =
    withContext(Dispatchers.IO) {
        val localTrending = runCatching { Database.trending(limit = 5).firstOrNull().orEmpty() }.getOrDefault(emptyList())
        val localFavorites = runCatching { Database.favorites().firstOrNull().orEmpty() }.getOrDefault(emptyList())
        val localHistory = runCatching { Database.history(size = 20).firstOrNull().orEmpty() }.getOrDefault(emptyList())

        val candidateVideoIds = buildList {
            primarySong?.id?.takeIf { it.isNotBlank() && !it.startsWith("local:") }?.let(::add)
            localTrending.forEach { s ->
                if (s.id.isNotBlank() && !s.id.startsWith("local:")) add(s.id)
            }
            localFavorites.take(4).forEach { s ->
                if (s.id.isNotBlank() && !s.id.startsWith("local:")) add(s.id)
            }
            localHistory.take(4).forEach { s ->
                if (s.id.isNotBlank() && !s.id.startsWith("local:")) add(s.id)
            }
            add("J7p4bzqLvCw")
        }.distinct()

        for (videoId in candidateVideoIds.take(4)) {
            val result = Innertube.relatedPage(body = NextBody(videoId = videoId))
            val page = result?.getOrNull()
            if (page != null && !page.songs.isNullOrEmpty()) {
                return@withContext Result.success(page)
            }
        }

        // Fallback 1: Build Quick Picks from Innertube.nextPage (Watch Next radio) + Search
        val seedVideoId = candidateVideoIds.firstOrNull() ?: "J7p4bzqLvCw"
        val nextPageItems = Innertube.nextPage(NextBody(videoId = seedVideoId))
            ?.getOrNull()
            ?.itemsPage
            ?.items
            ?.takeIf { it.isNotEmpty() }

        val seedQuery = listOfNotNull(
            primarySong?.artistsText?.takeIf { it.isNotBlank() },
            primarySong?.title?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { "popular hits" }

        val searchedSongs = if (nextPageItems.isNullOrEmpty()) {
            Innertube.searchPage(
                body = SearchBody(query = seedQuery, params = Innertube.SearchFilter.Song.value),
                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
            )?.getOrNull()?.items
        } else null

        val fallbackSongs = (nextPageItems ?: searchedSongs).orEmpty()
        if (fallbackSongs.isNotEmpty()) {
            return@withContext Result.success(
                Innertube.RelatedPage(
                    songs = fallbackSongs,
                    playlists = emptyList(),
                    albums = emptyList(),
                    artists = emptyList()
                )
            )
        }

        // Fallback 2: Use cachedQuickPicks if non-empty
        val cached = DataPreferences.cachedQuickPicks
        if (!cached.songs.isNullOrEmpty() || !cached.albums.isNullOrEmpty() || !cached.artists.isNullOrEmpty()) {
            return@withContext Result.success(cached)
        }

        // Fallback 3: Never show "An error has occurred" if we can show an empty/local RelatedPage
        Result.success(
            Innertube.RelatedPage(
                songs = emptyList(),
                playlists = emptyList(),
                albums = emptyList(),
                artists = emptyList()
            )
        )
    }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Route
@Composable
fun QuickPicks(
    onAlbumClick: (Innertube.AlbumItem) -> Unit,
    onArtistClick: (Innertube.ArtistItem) -> Unit,
    onPlaylistClick: (Innertube.PlaylistItem) -> Unit,
    onSearchClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val windowInsets = LocalPlayerAwareWindowInsets.current

    var trending by persist<Song?>("home/trending")
    var relatedPageResult by persist<Result<Innertube.RelatedPage?>?>(tag = "home/relatedPageResult")
    var lastFmRecommendedSongs by persist<List<Song>?>(tag = "home/lastFmRecommendedSongs")
    var isLastFmLoading by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var isRefreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var lastFmRefreshTrigger by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }

    val isLastFmLoggedIn = DataPreferences.lastFmUsername.isNotBlank() &&
        DataPreferences.lastFmSessionKey.isNotBlank()

    // Immediately restore cached Last.fm picks when reopening the app
    if (isLastFmLoggedIn && lastFmRecommendedSongs.isNullOrEmpty() && DataPreferences.cachedLastFmPicks.isNotEmpty()) {
        lastFmRecommendedSongs = DataPreferences.cachedLastFmPicks.map { cached ->
            Song(
                id = cached.id,
                title = cached.title,
                artistsText = cached.artistsText,
                durationText = cached.durationText,
                thumbnailUrl = cached.thumbnailUrl,
                explicit = cached.explicit
            )
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            relatedPageResult = fetchResilientRelatedPage(trending)
            if (isLastFmLoggedIn) {
                lastFmRefreshTrigger++
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(
        isLastFmLoggedIn,
        DataPreferences.lastFmUsername,
        lastFmRefreshTrigger
    ) {
        if (!isLastFmLoggedIn) {
            lastFmRecommendedSongs = null
            DataPreferences.cachedLastFmPicks = emptyList()
            isLastFmLoading = false
            return@LaunchedEffect
        }
        // If we already restored cached picks on initial launch (trigger == 0), don't block the button
        if (lastFmRefreshTrigger == 0 && !lastFmRecommendedSongs.isNullOrEmpty()) {
            isLastFmLoading = false
            return@LaunchedEffect
        }

        isLastFmLoading = true
        val previousKeys = if (lastFmRefreshTrigger > 0) {
            lastFmRecommendedSongs.orEmpty().mapTo(HashSet()) {
                "${it.title.lowercase().trim()}|${it.artistsText.orEmpty().lowercase().trim()}"
            }
        } else emptySet()
        val previousIds = if (lastFmRefreshTrigger > 0) {
            lastFmRecommendedSongs.orEmpty().mapTo(HashSet()) { it.id }
        } else emptySet()

        val freshResolved = withContext(Dispatchers.IO) {
            val seedPool = buildList {
                trending?.let { add(it) }
                runCatching { Database.trending(limit = 10).firstOrNull().orEmpty() }.getOrNull()?.let { addAll(it) }
                runCatching { Database.favorites().firstOrNull().orEmpty().shuffled().take(5) }.getOrNull()?.let { addAll(it) }
            }
            val seedSong = seedPool.shuffled().firstOrNull()

            val apiKey = DataPreferences.lastFmCustomApiKey.ifBlank { LastFmApi.DEFAULT_API_KEY }
            val candidates = runCatching {
                LastFmApi.fetchDynamicRecommendations(
                    username = DataPreferences.lastFmUsername,
                    seedTitle = seedSong?.title,
                    seedArtist = seedSong?.artistsText,
                    excludeKeys = previousKeys,
                    apiKey = apiKey
                )
            }.getOrDefault(emptyList())

            if (candidates.isEmpty()) {
                return@withContext emptyList<Song>()
            }

            val allLocalSongs = runCatching {
                Database.songsByRowIdAsc().firstOrNull().orEmpty()
            }.getOrDefault(emptyList())

            val semaphore = Semaphore(4)
            val resolved = coroutineScope {
                candidates.take(14).map { candidate ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val localMatch = allLocalSongs.firstOrNull { s ->
                                s.title.equals(candidate.title, ignoreCase = true) &&
                                    (s.artistsText?.contains(candidate.artist, ignoreCase = true) == true)
                            }
                            if (localMatch != null) return@withPermit localMatch

                            val ytItem = Innertube.searchPage(
                                body = SearchBody(
                                    query = "${candidate.title} ${candidate.artist}",
                                    params = Innertube.SearchFilter.Song.value
                                ),
                                fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                            )?.getOrNull()?.items?.firstOrNull() ?: return@withPermit null

                            Song(
                                id = ytItem.key,
                                title = ytItem.info?.name ?: candidate.title,
                                artistsText = ytItem.authors?.joinToString(", ") { it.name.orEmpty() }
                                    ?.takeIf { it.isNotBlank() } ?: candidate.artist,
                                durationText = ytItem.durationText,
                                thumbnailUrl = ytItem.thumbnail?.url,
                                explicit = ytItem.explicit
                            )
                        }
                    }
                }.awaitAll().filterNotNull().distinctBy { it.id }
            }

            val filtered = if (previousIds.isNotEmpty()) {
                val nonOverlapping = resolved.filterNot { it.id in previousIds }
                if (nonOverlapping.size >= 4) nonOverlapping.take(12)
                else (nonOverlapping + resolved.shuffled()).distinctBy { it.id }.take(12)
            } else {
                resolved.take(12)
            }

            if (filtered.isNotEmpty()) {
                DataPreferences.cachedLastFmPicks = filtered.map { song ->
                    app.vitune.android.preferences.CachedLastFmSong(
                        id = song.id,
                        title = song.title,
                        artistsText = song.artistsText,
                        durationText = song.durationText,
                        thumbnailUrl = song.thumbnailUrl,
                        explicit = song.explicit
                    )
                }
            }
            filtered
        }

        if (freshResolved.isNotEmpty()) {
            lastFmRecommendedSongs = freshResolved
        }
        isLastFmLoading = false
    }

    LaunchedEffect(relatedPageResult, DataPreferences.shouldCacheQuickPicks) {
        if (DataPreferences.shouldCacheQuickPicks)
            relatedPageResult?.getOrNull()?.let { DataPreferences.cachedQuickPicks = it }
        else DataPreferences.cachedQuickPicks = Innertube.RelatedPage()
    }

    LaunchedEffect(DataPreferences.quickPicksSource) {
        if (
            DataPreferences.shouldCacheQuickPicks && !DataPreferences.cachedQuickPicks.let {
                it.albums.isNullOrEmpty() &&
                    it.artists.isNullOrEmpty() &&
                    it.playlists.isNullOrEmpty() &&
                    it.songs.isNullOrEmpty()
            }
        ) relatedPageResult = Result.success(DataPreferences.cachedQuickPicks)

        suspend fun handleSong(song: Song?) {
            if (relatedPageResult == null || relatedPageResult?.getOrNull() == null || trending?.id != song?.id) {
                relatedPageResult = fetchResilientRelatedPage(song)
            }
            trending = song
        }

        when (DataPreferences.quickPicksSource) {
            DataPreferences.QuickPicksSource.Trending ->
                Database
                    .trending()
                    .distinctUntilChanged()
                    .collect { handleSong(it.firstOrNull()) }

            DataPreferences.QuickPicksSource.LastInteraction ->
                Database
                    .events()
                    .distinctUntilChanged()
                    .collect { handleSong(it.firstOrNull()?.song) }
        }
    }

    val scrollState = rememberScrollState()
    val quickPicksLazyGridState = rememberLazyGridState()
    val lastFmLazyGridState = rememberLazyGridState()

    val endPaddingValues = windowInsets.only(WindowInsetsSides.End).asPaddingValues()

    val sectionTextModifier = Modifier
        .padding(horizontal = 16.dp)
        .padding(top = 24.dp, bottom = 8.dp)
        .padding(endPaddingValues)

    val (currentMediaId, playing) = playingSong(binder)

    BoxWithConstraints {
        val quickPicksLazyGridItemWidthFactor =
            if (isLandscape && maxWidth * 0.475f >= 320.dp) 0.475f else 0.75f

        val snapLayoutInfoProvider = rememberSnapLayoutInfo(
            lazyGridState = quickPicksLazyGridState,
            positionInLayout = { layoutSize, itemSize ->
                (layoutSize * quickPicksLazyGridItemWidthFactor / 2f - itemSize / 2f)
            }
        )

        val itemInHorizontalGridWidth = maxWidth * quickPicksLazyGridItemWidthFactor

        PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { isRefreshing = true }) {
        Column(
            modifier = Modifier
                .background(colorPalette.background0)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    windowInsets
                        .only(WindowInsetsSides.Vertical)
                        .asPaddingValues()
                )
        ) {
            Header(
                title = stringResource(R.string.quick_picks),
                modifier = Modifier.padding(endPaddingValues)
            )

            relatedPageResult?.getOrNull()?.let { related ->
                LazyHorizontalGrid(
                    state = quickPicksLazyGridState,
                    rows = GridCells.Fixed(4),
                    flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                    contentPadding = endPaddingValues,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((Dimensions.thumbnails.song + Dimensions.items.verticalPadding * 2) * 4)
                ) {
                    trending?.let { song ->
                        item {
                            SongItem(
                                modifier = Modifier
                                    .combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    onDismiss = menuState::hide,
                                                    mediaItem = song.asMediaItem,
                                                    onRemoveFromQuickPicks = {
                                                        query {
                                                            Database.clearEventsFor(song.id)
                                                        }
                                                    }
                                                )
                                            }
                                        },
                                        onClick = {
                                            val mediaItem = song.asMediaItem
                                            binder?.stopRadio()
                                            binder?.player?.forcePlay(mediaItem, source = "Quick Picks")
                                            binder?.setupRadio(
                                                NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId)
                                            )
                                        }
                                    )
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    .width(itemInHorizontalGridWidth),
                                song = song,
                                thumbnailSize = Dimensions.thumbnails.song,
                                trailingContent = {
                                    Image(
                                        painter = painterResource(R.drawable.star),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colorPalette.accent),
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                showDuration = false,
                                isPlaying = playing && currentMediaId == song.id
                            )
                        }
                    }

                    items(
                        items = related.songs?.dropLast(if (trending == null) 0 else 1)
                            ?: emptyList(),
                        key = Innertube.SongItem::key
                    ) { song ->
                        SongItem(
                            song = song,
                            thumbnailSize = Dimensions.thumbnails.song,
                            modifier = Modifier
                                .combinedClickable(
                                    onLongClick = {
                                        menuState.display {
                                            NonQueuedMediaItemMenu(
                                                onDismiss = menuState::hide,
                                                mediaItem = song.asMediaItem
                                            )
                                        }
                                    },
                                    onClick = {
                                        val mediaItem = song.asMediaItem
                                        binder?.stopRadio()
                                        binder?.player?.forcePlay(mediaItem, source = "Quick Picks")
                                        binder?.setupRadio(
                                            NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId)
                                        )
                                    }
                                )
                                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                .width(itemInHorizontalGridWidth),
                            showDuration = false,
                            isPlaying = playing && currentMediaId == song.key
                        )
                    }
                }

                if (isLastFmLoggedIn && (!lastFmRecommendedSongs.isNullOrEmpty() || isLastFmLoading)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(top = 24.dp, bottom = 8.dp)
                            .padding(endPaddingValues)
                    ) {
                        BasicText(
                            text = if (isLastFmLoading) {
                                "Refreshing Last.fm Picks…"
                            } else {
                                "Last.fm Picks for @${DataPreferences.lastFmUsername}"
                            },
                            style = typography.m.semiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Image(
                            painter = painterResource(R.drawable.sync),
                            contentDescription = "Refresh Last.fm recommendations",
                            colorFilter = ColorFilter.tint(
                                if (isLastFmLoading) colorPalette.textDisabled else colorPalette.accent
                            ),
                            modifier = Modifier
                                .clickable(enabled = !isLastFmLoading) {
                                    lastFmRefreshTrigger++
                                }
                                .padding(8.dp)
                                .size(22.dp)
                        )
                    }

                    val lastFmSongs = lastFmRecommendedSongs.orEmpty()
                    if (isLastFmLoading) {
                        ShimmerHost {
                            repeat(4) {
                                SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                            }
                        }
                    } else if (lastFmSongs.isNotEmpty()) {
                        val rowsCount = minOf(4, lastFmSongs.size.coerceAtLeast(1))
                        LazyHorizontalGrid(
                            state = lastFmLazyGridState,
                            rows = GridCells.Fixed(rowsCount),
                            contentPadding = endPaddingValues,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((Dimensions.thumbnails.song + Dimensions.items.verticalPadding * 2) * rowsCount)
                        ) {
                            items(
                                items = lastFmSongs,
                                key = { "lastfm_${it.id}" }
                            ) { song ->
                            SongItem(
                                song = song,
                                thumbnailSize = Dimensions.thumbnails.song,
                                modifier = Modifier
                                    .combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    onDismiss = menuState::hide,
                                                    mediaItem = song.asMediaItem
                                                )
                                            }
                                        },
                                        onClick = {
                                            val mediaItem = song.asMediaItem
                                            binder?.stopRadio()
                                            binder?.player?.forcePlay(mediaItem, source = "Last.fm Picks")
                                            binder?.setupRadio(
                                                NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId)
                                            )
                                        }
                                    )
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    .width(itemInHorizontalGridWidth),
                                showDuration = false,
                                isPlaying = playing && currentMediaId == song.id
                            )
                        }
                        }
                    }
                }

                related.albums?.let { albums ->
                    BasicText(
                        text = stringResource(R.string.related_albums),
                        style = typography.m.semiBold,
                        modifier = sectionTextModifier
                    )

                    LazyRow(contentPadding = endPaddingValues) {
                        items(
                            items = albums,
                            key = Innertube.AlbumItem::key
                        ) { album ->
                            AlbumItem(
                                album = album,
                                thumbnailSize = Dimensions.thumbnails.album,
                                alternative = true,
                                modifier = Modifier.clickable { onAlbumClick(album) }
                            )
                        }
                    }
                }

                related.artists?.let { artists ->
                    BasicText(
                        text = stringResource(R.string.similar_artists),
                        style = typography.m.semiBold,
                        modifier = sectionTextModifier
                    )

                    LazyRow(contentPadding = endPaddingValues) {
                        items(
                            items = artists,
                            key = Innertube.ArtistItem::key
                        ) { artist ->
                            ArtistItem(
                                artist = artist,
                                thumbnailSize = Dimensions.thumbnails.artist,
                                alternative = true,
                                modifier = Modifier.clickable { onArtistClick(artist) }
                            )
                        }
                    }
                }

                related.playlists?.let { playlists ->
                    BasicText(
                        text = stringResource(R.string.recommended_playlists),
                        style = typography.m.semiBold,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(top = 24.dp, bottom = 8.dp)
                    )

                    LazyRow(contentPadding = endPaddingValues) {
                        items(
                            items = playlists,
                            key = Innertube.PlaylistItem::key
                        ) { playlist ->
                            PlaylistItem(
                                playlist = playlist,
                                thumbnailSize = Dimensions.thumbnails.playlist,
                                alternative = true,
                                modifier = Modifier.clickable { onPlaylistClick(playlist) }
                            )
                        }
                    }
                }

                Unit
            } ?: relatedPageResult?.exceptionOrNull()?.let {
                BasicText(
                    text = stringResource(R.string.error_message),
                    style = typography.s.secondary.center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(all = 16.dp)
                )
            } ?: ShimmerHost {
                repeat(4) {
                    SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                }

                TextPlaceholder(modifier = sectionTextModifier)

                Row {
                    repeat(2) {
                        AlbumItemPlaceholder(
                            thumbnailSize = Dimensions.thumbnails.album,
                            alternative = true
                        )
                    }
                }

                TextPlaceholder(modifier = sectionTextModifier)

                Row {
                    repeat(2) {
                        ArtistItemPlaceholder(
                            thumbnailSize = Dimensions.thumbnails.album,
                            alternative = true
                        )
                    }
                }

                TextPlaceholder(modifier = sectionTextModifier)

                Row {
                    repeat(2) {
                        PlaylistItemPlaceholder(
                            thumbnailSize = Dimensions.thumbnails.album,
                            alternative = true
                        )
                    }
                }
            }
        }
        }

        FloatingActionsContainerWithScrollToTop(
            scrollState = scrollState,
            icon = R.drawable.search,
            onClick = onSearchClick
        )
    }
}
