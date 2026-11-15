package app.vitune.android.ui.screens.album

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.vitune.android.Database
import app.vitune.android.R
import app.vitune.android.models.Album
import app.vitune.android.models.Song
import app.vitune.android.models.SongAlbumMap
import app.vitune.android.query
import app.vitune.android.transaction
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.HeaderPlaceholder
import app.vitune.android.ui.components.themed.PlaylistInfo
import app.vitune.android.ui.components.themed.Scaffold
import app.vitune.android.ui.components.themed.adaptiveThumbnailContent
import app.vitune.android.ui.items.AlbumItem
import app.vitune.android.ui.items.AlbumItemPlaceholder
import app.vitune.android.ui.screens.GlobalRoutes
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.albumRoute
import app.vitune.android.ui.screens.searchresult.ItemsPage
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.completed
import app.vitune.compose.persist.PersistMapCleanup
import app.vitune.compose.persist.persist
import app.vitune.compose.persist.persistList
import app.vitune.compose.routing.RouteHandler
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.stateFlowSaver
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.BrowseBody
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.albumPage
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import app.vitune.providers.spotify.requests.SpotifyApi
import com.valentinilk.shimmer.shimmer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

@Route
@Composable
fun AlbumScreen(browseId: String) {
    val saveableStateHolder = rememberSaveableStateHolder()

    val tabIndexState = rememberSaveable(saver = stateFlowSaver()) { MutableStateFlow(0) }
    val tabIndex by tabIndexState.collectAsState()

    var album by persist<Album?>("album/$browseId/album")
    var albumPage by persist<Innertube.PlaylistOrAlbumPage?>("album/$browseId/albumPage")
    var songs by persistList<Song>("album/$browseId/songs")

    PersistMapCleanup(prefix = "album/$browseId/")

    LaunchedEffect(Unit) {
        Database
            .albumSongs(browseId)
            .distinctUntilChanged()
            .combine(
                Database
                    .album(browseId)
                    .distinctUntilChanged()
                    .cancellable()
            ) { currentSongs, currentAlbum ->
                album = currentAlbum
                songs = currentSongs.toImmutableList()

                if (currentAlbum?.timestamp != null && currentSongs.isNotEmpty()) return@combine

                withContext(Dispatchers.IO) {
                    val isSpotifyAlbum = browseId.startsWith("spotify:album:") ||
                        (!browseId.startsWith("MPREb_") && !browseId.startsWith("OLAK5uy_") && Regex("""^[A-Za-z0-9]{22}$""").matches(browseId))

                    if (isSpotifyAlbum) {
                        val token = runCatching { SpotifySyncManager.getValidAccessToken() }.getOrNull()
                        val spData = runCatching { SpotifyApi.getAlbumTracks(browseId, token) }.getOrNull()
                        if (spData != null) {
                            val (spRelease, spTracks) = spData
                            val primaryArtist = spRelease.artists.firstOrNull().orEmpty()

                            // First try matching YouTube Music album if it has all tracks
                            val ytAlbumMatch = runCatching {
                                Innertube.searchPage(
                                    body = SearchBody(
                                        query = "${spRelease.name} $primaryArtist".trim(),
                                        params = Innertube.SearchFilter.Album.value
                                    ),
                                    fromMusicShelfRendererContent = Innertube.AlbumItem.Companion::from
                                )?.getOrNull()?.items?.firstOrNull { item ->
                                    item.info?.name?.equals(spRelease.name, ignoreCase = true) == true ||
                                        item.info?.name?.contains(spRelease.name, ignoreCase = true) == true
                                }
                            }.getOrNull()

                            val ytAlbumPage = ytAlbumMatch?.key?.let { ytKey ->
                                runCatching {
                                    Innertube.albumPage(BrowseBody(browseId = ytKey))?.completed()?.getOrNull()
                                }.getOrNull()
                            }

                            val ytSongs = ytAlbumPage?.songsPage?.items.orEmpty()
                            if (ytAlbumPage != null && ytSongs.size >= spTracks.size && ytSongs.isNotEmpty()) {
                                albumPage = ytAlbumPage
                                transaction {
                                    Database.clearAlbum(browseId)
                                    Database.upsert(
                                        album = Album(
                                            id = browseId,
                                            title = spRelease.name.ifBlank { ytAlbumPage.title },
                                            description = ytAlbumPage.description,
                                            thumbnailUrl = spRelease.coverUrl ?: ytAlbumPage.thumbnail?.url,
                                            year = spRelease.releaseDate.take(4).ifBlank { ytAlbumPage.year },
                                            authorsText = spRelease.artists.joinToString(", ").ifBlank {
                                                ytAlbumPage.authors?.joinToString("") { it.name.orEmpty() }
                                            },
                                            shareUrl = "https://open.spotify.com/album/${spRelease.id}",
                                            timestamp = System.currentTimeMillis(),
                                            bookmarkedAt = album?.bookmarkedAt,
                                            otherInfo = ytAlbumPage.otherInfo
                                        ),
                                        songAlbumMaps = ytSongs
                                            .map { it.asMediaItem }
                                            .onEach { Database.insert(it) }
                                            .mapIndexed { position, mediaItem ->
                                                SongAlbumMap(
                                                    songId = mediaItem.mediaId,
                                                    albumId = browseId,
                                                    position = position
                                                )
                                            }
                                    )
                                }
                                return@withContext
                            }

                            // Otherwise resolve each Spotify track on YouTube Music in parallel so no track is missing
                            val sem = Semaphore(6)
                            val resolvedSongs = coroutineScope {
                                spTracks.mapIndexed { idx, spTrack ->
                                    async {
                                        sem.withPermit {
                                            val artistQuery = spTrack.artists.joinToString(" ") { it.name }.ifBlank { primaryArtist }
                                            val ytSong = runCatching {
                                                Innertube.searchPage(
                                                    body = SearchBody(
                                                        query = "${spTrack.name} $artistQuery".trim(),
                                                        params = Innertube.SearchFilter.Song.value
                                                    ),
                                                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                                                )?.getOrNull()?.items?.firstOrNull()
                                            }.getOrNull() ?: return@withPermit null

                                            val totalSec = (spTrack.durationMs / 1000L).coerceAtLeast(0L)
                                            val durText = if (totalSec > 0L) {
                                                String.format(Locale.US, "%d:%02d", totalSec / 60L, totalSec % 60L)
                                            } else ytSong.durationText

                                            idx to Song(
                                                id = ytSong.key,
                                                title = spTrack.name,
                                                artistsText = spTrack.artists.joinToString(", ") { it.name }.ifBlank {
                                                    ytSong.authors?.joinToString(", ") { it.name.orEmpty() }.orEmpty()
                                                },
                                                durationText = durText,
                                                thumbnailUrl = spRelease.coverUrl ?: ytSong.thumbnail?.url,
                                                explicit = spTrack.explicit || ytSong.explicit
                                            )
                                        }
                                    }
                                }.awaitAll().filterNotNull()
                            }

                            if (resolvedSongs.isNotEmpty()) {
                                transaction {
                                    Database.clearAlbum(browseId)
                                    Database.upsert(
                                        album = Album(
                                            id = browseId,
                                            title = spRelease.name,
                                            description = null,
                                            thumbnailUrl = spRelease.coverUrl,
                                            year = spRelease.releaseDate.take(4),
                                            authorsText = spRelease.artists.joinToString(", "),
                                            shareUrl = "https://open.spotify.com/album/${spRelease.id}",
                                            timestamp = System.currentTimeMillis(),
                                            bookmarkedAt = album?.bookmarkedAt,
                                            otherInfo = "${resolvedSongs.size} songs"
                                        ),
                                        songAlbumMaps = resolvedSongs.mapIndexed { pos, (_, songEntity) ->
                                            Database.insert(songEntity)
                                            SongAlbumMap(
                                                songId = songEntity.id,
                                                albumId = browseId,
                                                position = pos
                                            )
                                        }
                                    )
                                }
                                return@withContext
                            }
                        }
                    }

                    Innertube.albumPage(BrowseBody(browseId = browseId))
                        ?.completed()
                        ?.onSuccess { newAlbumPage ->
                            albumPage = newAlbumPage

                            transaction {
                                Database.clearAlbum(browseId)

                                Database.upsert(
                                    album = Album(
                                        id = browseId,
                                        title = newAlbumPage.title,
                                        description = newAlbumPage.description,
                                        thumbnailUrl = newAlbumPage.thumbnail?.url,
                                        year = newAlbumPage.year,
                                        authorsText = newAlbumPage.authors
                                            ?.joinToString("") { it.name.orEmpty() },
                                        shareUrl = newAlbumPage.url,
                                        timestamp = System.currentTimeMillis(),
                                        bookmarkedAt = album?.bookmarkedAt,
                                        otherInfo = newAlbumPage.otherInfo
                                    ),
                                    songAlbumMaps = newAlbumPage
                                        .songsPage
                                        ?.items
                                        ?.map { it.asMediaItem }
                                        ?.onEach { Database.insert(it) }
                                        ?.mapIndexed { position, mediaItem ->
                                            SongAlbumMap(
                                                songId = mediaItem.mediaId,
                                                albumId = browseId,
                                                position = position
                                            )
                                        } ?: emptyList()
                                )
                            }
                        }?.exceptionOrNull()?.printStackTrace()
                }
            }.collect()
    }

    RouteHandler {
        GlobalRoutes()

        Content {
            val headerContent: @Composable (
                beforeContent: (@Composable () -> Unit)?,
                afterContent: (@Composable () -> Unit)?
            ) -> Unit = { beforeContent, afterContent ->
                if (album?.timestamp == null) HeaderPlaceholder(modifier = Modifier.shimmer())
                else {
                    val (colorPalette) = LocalAppearance.current
                    val context = LocalContext.current

                    Header(title = album?.title ?: stringResource(R.string.unknown)) {
                        beforeContent?.invoke()

                        Spacer(modifier = Modifier.weight(1f))

                        afterContent?.invoke()

                        HeaderIconButton(
                            icon = if (album?.bookmarkedAt == null) R.drawable.bookmark_outline
                            else R.drawable.bookmark,
                            color = colorPalette.accent,
                            onClick = {
                                val bookmarkedAt =
                                    if (album?.bookmarkedAt == null) System.currentTimeMillis() else null

                                query {
                                    album
                                        ?.copy(bookmarkedAt = bookmarkedAt)
                                        ?.let(Database::update)
                                }
                            }
                        )

                        HeaderIconButton(
                            icon = R.drawable.share_social,
                            color = colorPalette.text,
                            onClick = {
                                album?.shareUrl?.let { url ->
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, url)
                                    }

                                    context.startActivity(
                                        Intent.createChooser(sendIntent, null)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            val thumbnailContent = adaptiveThumbnailContent(
                isLoading = album?.timestamp == null,
                url = album?.thumbnailUrl
            )

            Scaffold(
                key = "album",
                topIconButtonId = R.drawable.chevron_back,
                onTopIconButtonClick = pop,
                tabIndex = tabIndex,
                onTabChange = { newTab -> tabIndexState.update { newTab } },
                tabColumnContent = {
                    tab(0, R.string.songs, R.drawable.musical_notes, canHide = false)
                    tab(1, R.string.other_versions, R.drawable.disc)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                    when (currentTabIndex) {
                        0 -> AlbumSongs(
                            songs = songs,
                            headerContent = headerContent,
                            thumbnailContent = thumbnailContent,
                            albumTitle = album?.title ?: albumPage?.title,
                            afterHeaderContent = {
                                if (album == null) PlaylistInfo(playlist = albumPage)
                                else PlaylistInfo(playlist = album)
                            }
                        )

                        1 -> {
                            ItemsPage(
                                tag = "album/$browseId/alternatives",
                                header = headerContent,
                                initialPlaceholderCount = 1,
                                continuationPlaceholderCount = 1,
                                emptyItemsText = stringResource(R.string.no_alternative_version),
                                provider = albumPage?.let {
                                    {
                                        Result.success(
                                            Innertube.ItemsPage(
                                                items = albumPage?.otherVersions,
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
                        }
                    }
                }
            }
        }
    }
}
