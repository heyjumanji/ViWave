package app.vitune.android.ui.screens.builtinplaylist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Song
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.InHistoryMediaItemMenu
import app.vitune.android.ui.components.themed.NonQueuedMediaItemMenu
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.ui.components.themed.ValueSelectorDialog
import app.vitune.android.ui.items.SongItem
import app.vitune.android.ui.screens.home.HeaderSongSortBy
import app.vitune.android.utils.PlaylistDownloadIcon
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.enqueue
import app.vitune.android.utils.forcePlayAtIndex
import app.vitune.android.utils.forcePlayFromBeginning
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.compose.persist.persistList
import app.vitune.core.data.enums.BuiltInPlaylist
import app.vitune.core.data.enums.SongSortBy
import app.vitune.core.data.enums.SortOrder
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.enumSaver
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.vitune.android.ui.components.themed.Menu
import app.vitune.android.ui.components.themed.MenuEntry
import app.vitune.android.utils.PlaylistExportUtils
import app.vitune.android.utils.toast
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalCoroutinesApi::class)
@Composable
fun BuiltInPlaylistSongs(
    builtInPlaylist: BuiltInPlaylist,
    modifier: Modifier = Modifier
) = with(DataPreferences) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var songs by persistList<Song>("${builtInPlaylist.name}/songs")
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    var sortBy by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SongSortBy.DateAdded) }
    var sortOrder by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SortOrder.Descending) }

    val playlistDisplayName = when (builtInPlaylist) {
        BuiltInPlaylist.Favorites -> stringResource(R.string.favorites)
        BuiltInPlaylist.Offline -> stringResource(R.string.offline)
        BuiltInPlaylist.Top -> stringResource(R.string.format_my_top_playlist, topListLength)
        BuiltInPlaylist.History -> stringResource(R.string.history)
    }

    val filteredSongs by remember {
        derivedStateOf {
            val baseSongs = when (builtInPlaylist) {
                BuiltInPlaylist.Favorites, BuiltInPlaylist.Offline -> songs
                BuiltInPlaylist.Top, BuiltInPlaylist.History -> {
                    when (sortBy) {
                        SongSortBy.Title -> if (sortOrder == SortOrder.Ascending) {
                            songs.sortedBy { it.title.lowercase() }
                        } else {
                            songs.sortedByDescending { it.title.lowercase() }
                        }
                        SongSortBy.PlayTime -> if (sortOrder == SortOrder.Ascending) {
                            songs.sortedBy { it.totalPlayTimeMs }
                        } else {
                            songs.sortedByDescending { it.totalPlayTimeMs }
                        }
                        SongSortBy.DateAdded -> if (sortOrder == SortOrder.Ascending) {
                            songs.reversed()
                        } else {
                            songs
                        }
                    }.toImmutableList()
                }
            }
            val trimmed = searchQuery.trim()
            if (trimmed.isEmpty()) {
                baseSongs
            } else {
                val tokens = trimmed.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                baseSongs.filter { song ->
                    val titleLower = song.title.lowercase()
                    val artistLower = song.artistsText?.lowercase().orEmpty()
                    tokens.all { token ->
                        titleLower.contains(token) || artistLower.contains(token)
                    }
                }.toImmutableList()
            }
        }
    }

    LaunchedEffect(binder, sortBy, sortOrder) {
        when (builtInPlaylist) {
            BuiltInPlaylist.Favorites -> Database.favorites(
                sortBy = SongSortBy.DateAdded,
                sortOrder = sortOrder
            )

            BuiltInPlaylist.Offline ->
                Database
                    .songsWithContentLength(
                        sortBy = sortBy,
                        sortOrder = sortOrder
                    )
                    .map { songs ->
                        songs.filter { binder?.isCached(it) ?: false }.map { it.song }
                    }

            BuiltInPlaylist.Top -> combine(
                flow = topListPeriodProperty.stateFlow,
                flow2 = topListLengthProperty.stateFlow
            ) { period, length -> period to length }.flatMapLatest { (period, length) ->
                if (period.duration == null) Database
                    .songsByPlayTimeDesc(limit = length)
                    .distinctUntilChanged()
                    .cancellable()
                else Database
                    .trending(
                        limit = length,
                        period = period.duration.inWholeMilliseconds
                    )
                    .distinctUntilChanged()
                    .cancellable()
            }

            BuiltInPlaylist.History -> Database.history()
        }.collect { songs = it.toImmutableList() }
    }

    val lazyListState = rememberLazyListState()

    val (currentMediaId, playing) = playingSong(binder)

    var isShowingSpotifyDialog by rememberSaveable { mutableStateOf(false) }
    if (isShowingSpotifyDialog) {
        app.vitune.android.ui.screens.spotify.SpotifyImportDialog(
            onDismiss = { isShowingSpotifyDialog = false }
        )
    }

    var isAddingSongDialog by rememberSaveable { mutableStateOf(false) }
    if (isAddingSongDialog) {
        app.vitune.android.ui.components.themed.TextFieldDialog(
            hintText = "Enter song & artist name (or Spotify/YouTube link)",
            onDismiss = { isAddingSongDialog = false },
            onAccept = { queryText ->
                val cleanQuery = queryText.trim()
                if (cleanQuery.isNotBlank()) {
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val foundSong = app.vitune.providers.innertube.Innertube.searchPage(
                            body = app.vitune.providers.innertube.models.bodies.SearchBody(
                                query = cleanQuery,
                                params = app.vitune.providers.innertube.Innertube.SearchFilter.Song.value
                            ),
                            fromMusicShelfRendererContent = app.vitune.providers.innertube.Innertube.SongItem.Companion::from
                        )?.getOrNull()?.items?.firstOrNull()

                        if (foundSong != null) {
                            val mediaItem = foundSong.asMediaItem
                            app.vitune.android.transaction {
                                Database.insert(mediaItem)
                                Database.like(mediaItem.mediaId, System.currentTimeMillis())
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                context.toast("Added '${foundSong.info?.name ?: cleanQuery}' to Favorites")
                            }
                        } else {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                context.toast("Could not find song '$cleanQuery'")
                            }
                        }
                    }
                }
            }
        )
    }

    val sortOrderIconRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (sortOrder == SortOrder.Ascending) 0f else 180f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 400,
            easing = androidx.compose.animation.core.LinearEasing
        ),
        label = ""
    )

    Box(modifier = modifier) {
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
                Column(modifier = Modifier.fillMaxWidth()) {
                    Header(
                        title = playlistDisplayName,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        if (builtInPlaylist == BuiltInPlaylist.Favorites) {
                            HeaderIconButton(
                                icon = R.drawable.add,
                                color = colorPalette.accent,
                                onClick = {
                                    menuState.display {
                                        Menu {
                                            MenuEntry(
                                                icon = R.drawable.add,
                                                text = "Add song to Favorites",
                                                onClick = {
                                                    menuState.hide()
                                                    isAddingSongDialog = true
                                                }
                                            )
                                            if (app.vitune.android.utils.SpotifySyncManager.isLoggedIn()) {
                                                MenuEntry(
                                                    icon = R.drawable.sync,
                                                    text = "Sync Spotify Liked Songs Now (A–Z)",
                                                    onClick = {
                                                        menuState.hide()
                                                        app.vitune.android.utils.SpotifySyncManager.startImportLikedSongsToFavoritesInBackground()
                                                    }
                                                )
                                            }
                                            MenuEntry(
                                                icon = R.drawable.sync,
                                                text = "Import / Sync from Spotify",
                                                onClick = {
                                                    menuState.hide()
                                                    isShowingSpotifyDialog = true
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("builtin_playlist_add_button")
                            )

                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        if (builtInPlaylist != BuiltInPlaylist.Favorites) {
                            HeaderIconButton(
                                icon = R.drawable.text,
                                enabled = sortBy == SongSortBy.Title,
                                onClick = { sortBy = SongSortBy.Title },
                                modifier = Modifier.testTag("builtin_playlist_sort_title")
                            )

                            HeaderIconButton(
                                icon = R.drawable.time,
                                enabled = sortBy == SongSortBy.DateAdded,
                                onClick = { sortBy = SongSortBy.DateAdded },
                                modifier = Modifier.testTag("builtin_playlist_sort_date")
                            )

                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        HeaderIconButton(
                            icon = R.drawable.arrow_up,
                            color = colorPalette.text,
                            onClick = { sortOrder = !sortOrder },
                            modifier = Modifier
                                .graphicsLayer { rotationZ = sortOrderIconRotation }
                                .testTag("builtin_playlist_sort_order")
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        HeaderIconButton(
                            icon = R.drawable.search,
                            color = if (isSearchExpanded || searchQuery.isNotBlank()) colorPalette.accent else colorPalette.text,
                            onClick = {
                                isSearchExpanded = !isSearchExpanded
                                if (!isSearchExpanded) {
                                    searchQuery = ""
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            },
                            modifier = Modifier.testTag("builtin_playlist_search_toggle")
                        )

                        SecondaryTextButton(
                            text = stringResource(R.string.enqueue),
                            enabled = filteredSongs.isNotEmpty(),
                            onClick = {
                                binder?.player?.enqueue(filteredSongs.map(Song::asMediaItem))
                            }
                        )

                        if (builtInPlaylist == BuiltInPlaylist.Favorites) {
                            val bgSync by app.vitune.android.utils.SpotifySyncManager.backgroundSyncState.collectAsState()
                            val favSyncing = bgSync.isActive && bgSync.taskTitle.contains("Favorites", ignoreCase = true)
                            SecondaryTextButton(
                                text = when {
                                    favSyncing && bgSync.total > 0 -> "Syncing (${bgSync.current}/${bgSync.total})"
                                    favSyncing -> "Syncing..."
                                    else -> "Spotify Sync"
                                },
                                onClick = { isShowingSpotifyDialog = true }
                            )
                        }

                        if (builtInPlaylist == BuiltInPlaylist.Top) {
                            var dialogShowing by rememberSaveable { mutableStateOf(false) }

                            SecondaryTextButton(
                                text = topListPeriod.displayName(),
                                onClick = { dialogShowing = true }
                            )

                            if (dialogShowing) ValueSelectorDialog(
                                onDismiss = { dialogShowing = false },
                                title = stringResource(
                                    R.string.format_view_top_of_header,
                                    topListLength
                                ),
                                selectedValue = topListPeriod,
                                values = DataPreferences.TopListPeriod.entries.toImmutableList(),
                                onValueSelect = { topListPeriod = it },
                                valueText = { it.displayName() }
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (builtInPlaylist != BuiltInPlaylist.Favorites && builtInPlaylist.sortable) {
                            HeaderIconButton(
                                icon = R.drawable.trending,
                                enabled = sortBy == SongSortBy.PlayTime,
                                onClick = { sortBy = SongSortBy.PlayTime }
                            )
                        }

                        if (builtInPlaylist != BuiltInPlaylist.Offline) PlaylistDownloadIcon(
                            songs = filteredSongs.map(Song::asMediaItem).toImmutableList()
                        )
                    }

                    AnimatedVisibility(visible = isSearchExpanded || searchQuery.isNotBlank()) {
                        val focusRequester = remember { FocusRequester() }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .background(
                                    color = colorPalette.background1,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            HeaderIconButton(
                                icon = R.drawable.search,
                                color = colorPalette.textSecondary,
                                onClick = { focusRequester.requestFocus() }
                            )

                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                hintText = "Search song or artist...",
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester)
                                    .testTag("builtin_playlist_search_input")
                            )

                            if (songs.isNotEmpty()) {
                                BasicText(
                                    text = if (searchQuery.isBlank()) "${songs.size}" else "${filteredSongs.size}/${songs.size}",
                                    style = typography.xxs.secondary.semiBold
                                )
                            }

                            if (searchQuery.isNotBlank()) {
                                BasicText(
                                    text = "Clear",
                                    style = typography.xxs.semiBold,
                                    modifier = Modifier
                                        .clickable {
                                            searchQuery = ""
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                        }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                        .testTag("builtin_playlist_search_clear")
                                )
                            }
                        }
                    }
                }
            }

            itemsIndexed(
                items = filteredSongs,
                key = { _, song -> song.id },
                contentType = { _, song -> song }
            ) { index, song ->
                SongItem(
                    modifier = Modifier
                        .combinedClickable(
                            onLongClick = {
                                keyboardController?.hide()
                                menuState.display {
                                    when (builtInPlaylist) {
                                        BuiltInPlaylist.Offline -> InHistoryMediaItemMenu(
                                            song = song,
                                            onDismiss = menuState::hide
                                        )

                                        BuiltInPlaylist.Favorites,
                                        BuiltInPlaylist.Top,
                                        BuiltInPlaylist.History -> NonQueuedMediaItemMenu(
                                            mediaItem = song.asMediaItem,
                                            onDismiss = menuState::hide
                                        )
                                    }
                                }
                            },
                            onClick = {
                                keyboardController?.hide()
                                binder?.stopRadio()
                                binder?.player?.forcePlayAtIndex(
                                    items = filteredSongs.map(Song::asMediaItem),
                                    index = index,
                                    source = playlistDisplayName
                                )
                            }
                        )
                        .animateItem(),
                    song = song,
                    index = if (builtInPlaylist == BuiltInPlaylist.Top) index else null,
                    thumbnailSize = Dimensions.thumbnails.song,
                    isPlaying = playing && currentMediaId == song.id
                )
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyListState = lazyListState,
            icon = R.drawable.shuffle,
            onClick = {
                if (filteredSongs.isEmpty()) return@FloatingActionsContainerWithScrollToTop
                binder?.stopRadio()
                binder?.player?.forcePlayFromBeginning(
                    items = filteredSongs.shuffled().map(Song::asMediaItem),
                    source = playlistDisplayName
                )
            }
        )
    }
}
