package app.vitune.android.ui.screens.artist

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Artist
import app.vitune.android.preferences.UIStatePreferences
import app.vitune.android.preferences.UIStatePreferences.artistScreenTabIndexProperty
import app.vitune.android.query
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.HeaderPlaceholder
import app.vitune.android.ui.components.themed.NonQueuedMediaItemMenu
import app.vitune.android.ui.components.themed.Scaffold
import app.vitune.android.ui.components.themed.adaptiveThumbnailContent
import app.vitune.android.ui.items.AlbumItem
import app.vitune.android.ui.items.AlbumItemPlaceholder
import app.vitune.android.ui.items.SongItem
import app.vitune.android.ui.items.SongItemPlaceholder
import app.vitune.android.ui.screens.GlobalRoutes
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.albumRoute
import app.vitune.android.ui.screens.artistRoute
import app.vitune.android.ui.screens.searchresult.ItemsPage
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.forcePlay
import app.vitune.android.utils.playingSong
import app.vitune.compose.persist.PersistMapCleanup
import app.vitune.compose.persist.persist
import app.vitune.compose.routing.RouteHandler
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.NavigationEndpoint
import app.vitune.providers.innertube.models.Thumbnail
import app.vitune.providers.innertube.models.bodies.BrowseBody
import app.vitune.providers.innertube.models.bodies.ContinuationBody
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.artistPage
import app.vitune.providers.innertube.requests.itemsPage
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import app.vitune.providers.spotify.models.SpotifyArtistDiscography
import app.vitune.providers.spotify.models.SpotifyReleaseItem
import app.vitune.providers.spotify.requests.SpotifyApi
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private fun SpotifyReleaseItem.toInnertubeAlbumItem(): Innertube.AlbumItem = Innertube.AlbumItem(
    info = Innertube.Info(
        name = name,
        endpoint = NavigationEndpoint.Endpoint.Browse(
            browseId = if (id.startsWith("MPREb_") || id.startsWith("spotify:album:")) id else "spotify:album:$id"
        )
    ),
    authors = artists.map { artistName ->
        Innertube.Info(name = artistName, endpoint = null)
    },
    year = releaseDate.take(4).ifBlank { null },
    thumbnail = coverUrl?.let { Thumbnail(url = it, height = null, width = null) }
)

private fun mergeAlbumItems(
    youtubeItems: List<Innertube.AlbumItem>,
    spotifyItems: List<Innertube.AlbumItem>
): List<Innertube.AlbumItem> {
    if (spotifyItems.isEmpty()) return youtubeItems.filter { it.key.isNotBlank() }.distinctBy { it.key }
    if (youtubeItems.isEmpty()) return spotifyItems.filter { it.key.isNotBlank() }.distinctBy { it.key }
    val seenTitles = youtubeItems.mapNotNull {
        it.info?.name?.lowercase()?.trim()?.takeIf { n -> n.isNotBlank() }
    }.toMutableSet()
    val combined = youtubeItems.toMutableList()
    for (spItem in spotifyItems) {
        val normTitle = spItem.info?.name?.lowercase()?.trim().orEmpty()
        if (normTitle.isNotBlank() && seenTitles.add(normTitle)) {
            combined.add(spItem)
        }
    }
    return combined
        .filter { it.key.isNotBlank() }
        .distinctBy { it.key }
        .sortedByDescending { it.year?.toIntOrNull() ?: 0 }
}

@OptIn(ExperimentalFoundationApi::class)
@Route
@Composable
fun ArtistScreen(browseId: String) {
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current

    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup(prefix = "artist/$browseId/")

    var artist by persist<Artist?>("artist/$browseId/artist")

    var artistPage by persist<Innertube.ArtistPage?>("artist/$browseId/artistPage")
    var spotifyDiscog by persist<SpotifyArtistDiscography?>("artist/$browseId/spotifyDiscog")

    LaunchedEffect(browseId) {
        Database
            .artist(browseId)
            .combine(
                flow = artistScreenTabIndexProperty.stateFlow.map { it != 4 },
                transform = ::Pair
            )
            .distinctUntilChanged()
            .collect { (currentArtist, _) ->
                val nameFromBrowseId = browseId.removePrefix("name:").trim()
                    .takeIf { browseId.startsWith("name:") && it.isNotBlank() }

                val unifiedLocalArtist = withContext(Dispatchers.IO) {
                    val candidateName = currentArtist?.name?.takeIf { it.isNotBlank() }
                        ?: artistPage?.name?.takeIf { it.isNotBlank() }
                        ?: nameFromBrowseId
                    if (candidateName != null) {
                        val sameNameList = runCatching {
                            Database.artistsByNameExact(candidateName)
                        }.getOrDefault(emptyList())
                        val bookmarkedMatch = sameNameList.firstOrNull { it.bookmarkedAt != null }
                        val bestThumb = bookmarkedMatch?.thumbnailUrl?.takeIf { it.isNotBlank() }
                            ?: currentArtist?.thumbnailUrl?.takeIf { it.isNotBlank() }
                            ?: sameNameList.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                        val mergedBookmark = currentArtist?.bookmarkedAt ?: bookmarkedMatch?.bookmarkedAt

                        if (currentArtist != null) {
                            if (currentArtist.bookmarkedAt != mergedBookmark || (bestThumb != null && currentArtist.thumbnailUrl != bestThumb)) {
                                val updated = currentArtist.copy(
                                    thumbnailUrl = bestThumb ?: currentArtist.thumbnailUrl,
                                    bookmarkedAt = mergedBookmark
                                )
                                runCatching { Database.upsert(updated) }
                                updated
                            } else {
                                currentArtist
                            }
                        } else if (bookmarkedMatch != null) {
                            Artist(
                                id = browseId,
                                name = bookmarkedMatch.name ?: candidateName,
                                thumbnailUrl = bestThumb,
                                timestamp = bookmarkedMatch.timestamp,
                                bookmarkedAt = mergedBookmark
                            )
                        } else if (nameFromBrowseId != null) {
                            Artist(
                                id = browseId,
                                name = nameFromBrowseId,
                                thumbnailUrl = bestThumb,
                                timestamp = null,
                                bookmarkedAt = null
                            )
                        } else null
                    } else currentArtist
                }

                if (unifiedLocalArtist != null) {
                    artist = unifiedLocalArtist
                }

                if (artistPage == null) {
                    withContext(Dispatchers.IO) {
                        val artistName = unifiedLocalArtist?.name?.takeIf { it.isNotBlank() }
                            ?: nameFromBrowseId
                        val sameNamePreCheck = if (artistName != null) {
                            runCatching { Database.artistsByNameExact(artistName) }.getOrDefault(emptyList())
                        } else emptyList()

                        val spotifyIdCandidate = when {
                            browseId.startsWith("spotify:artist:") -> browseId
                            sameNamePreCheck.any { it.id.startsWith("spotify:artist:") } ->
                                sameNamePreCheck.first { it.id.startsWith("spotify:artist:") }.id
                            else -> browseId
                        }

                        val spJob = coroutineScope {
                            async {
                                val token = runCatching { SpotifySyncManager.getValidAccessToken() }.getOrNull()
                                runCatching {
                                    SpotifyApi.getArtistDiscography(
                                        artistIdOrName = spotifyIdCandidate,
                                        artistNameHint = artistName,
                                        accessToken = token
                                    )
                                }.getOrNull()
                            }
                        }

                        val resolvedArtistName = artistName
                            ?: spJob.await()?.artistName?.takeIf { it.isNotBlank() }

                        val effectiveBrowseId = when {
                            browseId.startsWith("UC") -> browseId
                            sameNamePreCheck.any { it.id.startsWith("UC") } ->
                                sameNamePreCheck.first { it.id.startsWith("UC") }.id
                            resolvedArtistName != null -> {
                                val searchedArtist = Innertube.searchPage(
                                    body = SearchBody(
                                        query = resolvedArtistName,
                                        params = Innertube.SearchFilter.Artist.value
                                    ),
                                    fromMusicShelfRendererContent = Innertube.ArtistItem::from
                                )?.getOrNull()?.items?.firstOrNull()?.key?.takeIf { it.startsWith("UC") }

                                searchedArtist ?: run {
                                    val searchedSongs = Innertube.searchPage(
                                        body = SearchBody(
                                            query = resolvedArtistName,
                                            params = Innertube.SearchFilter.Song.value
                                        ),
                                        fromMusicShelfRendererContent = Innertube.SongItem::from
                                    )?.getOrNull()?.items.orEmpty()
                                    searchedSongs
                                        .flatMap { it.authors.orEmpty() }
                                        .firstOrNull { author ->
                                            author.endpoint?.browseId?.startsWith("UC") == true &&
                                                author.name?.contains(resolvedArtistName, ignoreCase = true) == true
                                        }
                                        ?.endpoint
                                        ?.browseId
                                        ?: searchedSongs
                                            .flatMap { it.authors.orEmpty() }
                                            .firstOrNull { it.endpoint?.browseId?.startsWith("UC") == true }
                                            ?.endpoint
                                            ?.browseId
                                        ?: browseId
                                }
                            }
                            else -> browseId
                        }

                        val fetchedSpDiscog = spJob.await()
                        if (fetchedSpDiscog != null) {
                            spotifyDiscog = fetchedSpDiscog
                        }

                        val spAlbums = fetchedSpDiscog?.albums.orEmpty().map { it.toInnertubeAlbumItem() }
                        val spSingles = fetchedSpDiscog?.singles.orEmpty().map { it.toInnertubeAlbumItem() }

                        val ytPageResult = if (effectiveBrowseId.startsWith("UC")) {
                            Innertube.artistPage(BrowseBody(browseId = effectiveBrowseId))
                        } else null

                        val currentArtistPage = ytPageResult?.getOrNull()

                        if (currentArtistPage != null || fetchedSpDiscog != null) {
                            val resolvedName = unifiedLocalArtist?.name?.takeIf { it.isNotBlank() }
                                ?: currentArtistPage?.name?.takeIf { it.isNotBlank() }
                                ?: fetchedSpDiscog?.artistName.orEmpty()
                            val sameNameList = if (resolvedName.isNotBlank()) {
                                runCatching { Database.artistsByNameExact(resolvedName) }.getOrDefault(emptyList())
                            } else emptyList()

                            val bookmarkedMatch = sameNameList.firstOrNull { it.bookmarkedAt != null }
                            val mergedBookmarkedAt = unifiedLocalArtist?.bookmarkedAt ?: bookmarkedMatch?.bookmarkedAt
                            val mergedThumbnail = bookmarkedMatch?.thumbnailUrl?.takeIf { it.isNotBlank() }
                                ?: unifiedLocalArtist?.thumbnailUrl?.takeIf { it.isNotBlank() }
                                ?: fetchedSpDiscog?.avatarUrl?.takeIf { it.isNotBlank() }
                                ?: sameNameList.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                                ?: currentArtistPage?.thumbnail?.url

                            val now = System.currentTimeMillis()
                            val canonicalId = if (effectiveBrowseId.startsWith("UC")) effectiveBrowseId else browseId
                            val mergedArtist = Artist(
                                id = browseId,
                                name = resolvedName,
                                thumbnailUrl = mergedThumbnail,
                                timestamp = now,
                                bookmarkedAt = mergedBookmarkedAt
                            )

                            val fallbackSongs = if (currentArtistPage?.songs.isNullOrEmpty() && resolvedName.isNotBlank()) {
                                runCatching {
                                    Innertube.searchPage(
                                        body = SearchBody(
                                            query = resolvedName,
                                            params = Innertube.SearchFilter.Song.value
                                        ),
                                        fromMusicShelfRendererContent = Innertube.SongItem::from
                                    )?.getOrNull()?.items
                                }.getOrNull()
                            } else null

                            val mergedAlbums = mergeAlbumItems(currentArtistPage?.albums.orEmpty(), spAlbums)
                            val mergedSingles = mergeAlbumItems(currentArtistPage?.singles.orEmpty(), spSingles)

                            artistPage = if (currentArtistPage != null) {
                                currentArtistPage.copy(
                                    name = currentArtistPage.name ?: resolvedName,
                                    description = currentArtistPage.description ?: fetchedSpDiscog?.biography,
                                    thumbnail = currentArtistPage.thumbnail
                                        ?: mergedThumbnail?.let { Thumbnail(url = it, height = null, width = null) },
                                    songs = currentArtistPage.songs?.takeIf { it.isNotEmpty() } ?: fallbackSongs,
                                    albums = mergedAlbums,
                                    singles = mergedSingles
                                )
                            } else {
                                Innertube.ArtistPage(
                                    name = resolvedName,
                                    description = fetchedSpDiscog?.biography,
                                    thumbnail = mergedThumbnail?.let { Thumbnail(url = it, height = null, width = null) },
                                    shuffleEndpoint = null,
                                    radioEndpoint = fallbackSongs?.firstOrNull()?.info?.endpoint,
                                    songs = fallbackSongs,
                                    songsEndpoint = null,
                                    albums = mergedAlbums,
                                    albumsEndpoint = null,
                                    singles = mergedSingles,
                                    singlesEndpoint = null,
                                    subscribersCountText = fetchedSpDiscog?.followers?.let { "%,d".format(it) },
                                    similarArtists = null,
                                    similarArtistsEndpoint = null
                                )
                            }
                            artist = mergedArtist

                            runCatching {
                                if (canonicalId.startsWith("UC")) {
                                    Database.upsert(mergedArtist.copy(id = canonicalId))
                                }
                                if (!browseId.startsWith("name:")) {
                                    Database.upsert(mergedArtist)
                                }
                                sameNameList.forEach { dup ->
                                    if (dup.id != canonicalId) {
                                        Database.migrateSongArtistMap(dup.id, canonicalId)
                                        if (canonicalId.startsWith("UC") && (dup.id.startsWith("spotify:artist:") || dup.id.startsWith("name:"))) {
                                            Database.deleteArtistById(dup.id)
                                        } else if (dup.bookmarkedAt != mergedBookmarkedAt || dup.thumbnailUrl != mergedThumbnail) {
                                            Database.upsert(
                                                dup.copy(
                                                    thumbnailUrl = mergedThumbnail ?: dup.thumbnailUrl,
                                                    bookmarkedAt = mergedBookmarkedAt
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

    RouteHandler {
        GlobalRoutes()

        Content {
            val (currentMediaId, playing) = playingSong(binder)
            val context = LocalContext.current

            val thumbnailContent = adaptiveThumbnailContent(
                isLoading = artist?.timestamp == null && artistPage == null,
                url = artist?.thumbnailUrl ?: artistPage?.thumbnail?.url,
                shape = CircleShape
            )

            val onToggleBookmark: () -> Unit = {
                val bookmarkedAt = if (artist?.bookmarkedAt == null)
                    System.currentTimeMillis() else null

                val targetName = artist?.name ?: artistPage?.name
                val targetThumb = artist?.thumbnailUrl ?: artistPage?.thumbnail?.url
                val target = (artist ?: Artist(
                    id = browseId,
                    name = targetName,
                    thumbnailUrl = targetThumb,
                    timestamp = System.currentTimeMillis(),
                    bookmarkedAt = bookmarkedAt
                )).copy(bookmarkedAt = bookmarkedAt)

                artist = target
                query {
                    Database.upsert(target)
                    if (!targetName.isNullOrBlank()) {
                        val sameNameList = kotlinx.coroutines.runBlocking {
                            runCatching { Database.artistsByNameExact(targetName) }.getOrDefault(emptyList())
                        }
                        sameNameList.forEach { dup ->
                            if (dup.id != target.id) {
                                Database.upsert(dup.copy(bookmarkedAt = bookmarkedAt))
                            }
                        }
                    }
                }
            }

            val onShareArtist: () -> Unit = {
                val shareUrl = if (browseId.startsWith("spotify:artist:")) {
                    "https://open.spotify.com/artist/${browseId.removePrefix("spotify:artist:")}"
                } else {
                    "https://music.youtube.com/channel/$browseId"
                }
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareUrl)
                }
                context.startActivity(Intent.createChooser(sendIntent, null))
            }

            val headerContent: @Composable (textButton: (@Composable () -> Unit)?) -> Unit =
                { textButton ->
                    if (artist?.timestamp == null && artistPage == null) HeaderPlaceholder(
                        modifier = Modifier.shimmer()
                    ) else {
                        val (colorPalette) = LocalAppearance.current

                        Header(title = artist?.name ?: artistPage?.name ?: stringResource(R.string.unknown)) {
                            textButton?.invoke()

                            Spacer(modifier = Modifier.weight(1f))

                            HeaderIconButton(
                                icon = if (artist?.bookmarkedAt == null) R.drawable.bookmark_outline
                                else R.drawable.bookmark,
                                color = colorPalette.accent,
                                onClick = onToggleBookmark
                            )

                            HeaderIconButton(
                                icon = R.drawable.share_social,
                                color = colorPalette.text,
                                onClick = onShareArtist
                            )
                        }
                    }
                }

            Scaffold(
                key = "artist",
                topIconButtonId = R.drawable.chevron_back,
                onTopIconButtonClick = pop,
                tabIndex = UIStatePreferences.artistScreenTabIndex,
                onTabChange = { UIStatePreferences.artistScreenTabIndex = it },
                tabColumnContent = {
                    tab(0, R.string.overview, R.drawable.sparkles)
                    tab(1, R.string.songs, R.drawable.musical_notes)
                    tab(2, R.string.albums, R.drawable.disc)
                    tab(3, R.string.singles, R.drawable.disc)
                    tab(4, R.string.library, R.drawable.library)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                    @Suppress("Wrapping")
                    when (currentTabIndex) {
                        0 -> ArtistOverview(
                            youtubeArtistPage = artistPage,
                            artist = artist,
                            latestRelease = spotifyDiscog?.latestRelease,
                            onToggleBookmark = onToggleBookmark,
                            onShare = onShareArtist,
                            thumbnailContent = thumbnailContent,
                            headerContent = headerContent,
                            onAlbumClick = { albumRoute(it) },
                            onViewAllSongsClick = { UIStatePreferences.artistScreenTabIndex = 1 },
                            onViewAllAlbumsClick = { UIStatePreferences.artistScreenTabIndex = 2 },
                            onViewAllSinglesClick = { UIStatePreferences.artistScreenTabIndex = 3 },
                            onArtistClick = { artistRoute(it) }
                        )

                        1 -> ItemsPage(
                            tag = "artist/$browseId/songs",
                            header = headerContent,
                            provider = artistPage?.let {
                                { continuation ->
                                    continuation?.let {
                                        Innertube.itemsPage(
                                            body = ContinuationBody(continuation = continuation),
                                            fromListRenderer = Innertube.SongItem::from
                                        )
                                    } ?: artistPage
                                        ?.songsEndpoint
                                        ?.takeIf { it.browseId != null }
                                        ?.let { endpoint ->
                                            Innertube.itemsPage(
                                                body = BrowseBody(
                                                    browseId = endpoint.browseId!!,
                                                    params = endpoint.params
                                                ),
                                                fromListRenderer = Innertube.SongItem::from
                                            )
                                        }
                                        ?: Result.success(
                                            Innertube.ItemsPage(
                                                items = artistPage?.songs,
                                                continuation = null
                                            )
                                        )
                                }
                            },
                            itemContent = { song ->
                                SongItem(
                                    song = song,
                                    thumbnailSize = Dimensions.thumbnails.song,
                                    modifier = Modifier.combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    onDismiss = menuState::hide,
                                                    mediaItem = song.asMediaItem
                                                )
                                            }
                                        },
                                        onClick = {
                                            binder?.stopRadio()
                                            binder?.player?.forcePlay(
                                                mediaItem = song.asMediaItem,
                                                source = artist?.name ?: artistPage?.name
                                            )
                                            binder?.setupRadio(song.info?.endpoint)
                                        }
                                    ),
                                    isPlaying = playing && currentMediaId == song.key
                                )
                            },
                            itemPlaceholderContent = {
                                SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                            }
                        )

                        2 -> ItemsPage(
                            tag = "artist/$browseId/albums",
                            header = headerContent,
                            emptyItemsText = stringResource(R.string.artist_has_no_albums),
                            provider = artistPage?.let {
                                { continuation ->
                                    val spAlbums = spotifyDiscog?.albums.orEmpty().map { it.toInnertubeAlbumItem() }
                                    continuation?.let {
                                        Innertube.itemsPage(
                                            body = ContinuationBody(continuation = continuation),
                                            fromTwoRowRenderer = Innertube.AlbumItem::from
                                        )
                                    } ?: artistPage
                                        ?.albumsEndpoint
                                        ?.takeIf { it.browseId != null }
                                        ?.let { endpoint ->
                                            val ytRes = Innertube.itemsPage(
                                                body = BrowseBody(
                                                    browseId = endpoint.browseId!!,
                                                    params = endpoint.params
                                                ),
                                                fromTwoRowRenderer = Innertube.AlbumItem::from
                                            )
                                            val ytPage = ytRes?.getOrNull()
                                            if (ytPage != null) {
                                                Result.success(
                                                    ytPage.copy(
                                                        items = mergeAlbumItems(
                                                            ytPage.items ?: artistPage?.albums.orEmpty(),
                                                            spAlbums
                                                        )
                                                    )
                                                )
                                            } else {
                                                Result.success(
                                                    Innertube.ItemsPage(
                                                        items = mergeAlbumItems(artistPage?.albums.orEmpty(), spAlbums),
                                                        continuation = null
                                                    )
                                                )
                                            }
                                        }
                                        ?: Result.success(
                                            Innertube.ItemsPage(
                                                items = mergeAlbumItems(artistPage?.albums.orEmpty(), spAlbums),
                                                continuation = null
                                            )
                                        )
                                }
                            },
                            itemContent = { album ->
                                AlbumItem(
                                    album = album,
                                    thumbnailSize = Dimensions.thumbnails.album,
                                    modifier = Modifier.clickable { albumRoute(album.key) }
                                )
                            },
                            itemPlaceholderContent = {
                                AlbumItemPlaceholder(thumbnailSize = Dimensions.thumbnails.album)
                            }
                        )

                        3 -> ItemsPage(
                            tag = "artist/$browseId/singles",
                            header = headerContent,
                            emptyItemsText = stringResource(R.string.artist_has_no_singles),
                            provider = artistPage?.let {
                                { continuation ->
                                    val spSingles = spotifyDiscog?.singles.orEmpty().map { it.toInnertubeAlbumItem() }
                                    continuation?.let {
                                        Innertube.itemsPage(
                                            body = ContinuationBody(continuation = continuation),
                                            fromTwoRowRenderer = Innertube.AlbumItem::from
                                        )
                                    } ?: artistPage
                                        ?.singlesEndpoint
                                        ?.takeIf { it.browseId != null }
                                        ?.let { endpoint ->
                                            val ytRes = Innertube.itemsPage(
                                                body = BrowseBody(
                                                    browseId = endpoint.browseId!!,
                                                    params = endpoint.params
                                                ),
                                                fromTwoRowRenderer = Innertube.AlbumItem::from
                                            )
                                            val ytPage = ytRes?.getOrNull()
                                            if (ytPage != null) {
                                                Result.success(
                                                    ytPage.copy(
                                                        items = mergeAlbumItems(
                                                            ytPage.items ?: artistPage?.singles.orEmpty(),
                                                            spSingles
                                                        )
                                                    )
                                                )
                                            } else {
                                                Result.success(
                                                    Innertube.ItemsPage(
                                                        items = mergeAlbumItems(artistPage?.singles.orEmpty(), spSingles),
                                                        continuation = null
                                                    )
                                                )
                                            }
                                        }
                                        ?: Result.success(
                                            Innertube.ItemsPage(
                                                items = mergeAlbumItems(artistPage?.singles.orEmpty(), spSingles),
                                                continuation = null
                                            )
                                        )
                                }
                            },
                            itemContent = { album ->
                                AlbumItem(
                                    album = album,
                                    thumbnailSize = Dimensions.thumbnails.album,
                                    modifier = Modifier.clickable { albumRoute(album.key) }
                                )
                            },
                            itemPlaceholderContent = {
                                AlbumItemPlaceholder(thumbnailSize = Dimensions.thumbnails.album)
                            }
                        )

                        4 -> ArtistLocalSongs(
                            browseId = browseId,
                            headerContent = headerContent,
                            thumbnailContent = thumbnailContent,
                            artistName = artist?.name ?: artistPage?.name
                        )
                    }
                }
            }
        }
    }
}

