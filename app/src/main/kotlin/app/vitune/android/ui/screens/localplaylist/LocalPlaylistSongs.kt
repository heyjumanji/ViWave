package app.vitune.android.ui.screens.localplaylist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Playlist
import app.vitune.android.models.Song
import app.vitune.android.models.SongPlaylistMap
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.query
import app.vitune.android.transaction
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.ConfirmationDialog
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.InPlaylistMediaItemMenu
import app.vitune.android.ui.components.themed.LayoutWithAdaptiveThumbnail
import app.vitune.android.ui.components.themed.Menu
import app.vitune.android.ui.components.themed.MenuEntry
import app.vitune.android.ui.components.themed.ReorderHandle
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.TextFieldDialog
import app.vitune.android.ui.items.SongItem
import app.vitune.android.utils.PlaylistDownloadIcon
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.completed
import app.vitune.android.utils.enqueue
import app.vitune.android.utils.forcePlayAtIndex
import app.vitune.android.utils.forcePlayFromBeginning
import app.vitune.android.utils.launchYouTubeMusic
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.toast
import app.vitune.compose.reordering.animateItemPlacement
import app.vitune.compose.reordering.draggedItem
import app.vitune.compose.reordering.rememberReorderingState
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isLandscape
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.BrowseBody
import app.vitune.providers.innertube.requests.playlistPage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.utils.PlaylistExportUtils
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.data.enums.SongSortBy
import app.vitune.core.data.enums.SortOrder
import app.vitune.core.ui.utils.enumSaver
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalPlaylistSongs(
    playlist: Playlist,
    songs: ImmutableList<Song>,
    onDelete: () -> Unit,
    thumbnailContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) = LayoutWithAdaptiveThumbnail(
    thumbnailContent = thumbnailContent,
    modifier = modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    var loading by remember { mutableStateOf(false) }
    var syncCurrent by remember { mutableIntStateOf(0) }
    var syncTotal by remember { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var sortBy by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SongSortBy.DateAdded) }
    var sortOrder by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(SortOrder.Ascending) }

    val sortOrderIconRotation by animateFloatAsState(
        targetValue = if (sortOrder == SortOrder.Ascending) 0f else 180f,
        animationSpec = tween(durationMillis = 400, easing = LinearEasing),
        label = ""
    )

    val filteredSongs by remember(songs, sortBy, sortOrder) {
        derivedStateOf {
            val sortedBase = when (sortBy) {
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
                    songs
                } else {
                    songs.reversed()
                }
            }.toImmutableList()

            val trimmed = searchQuery.trim()
            if (trimmed.isEmpty()) {
                sortedBase
            } else {
                val tokens = trimmed.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                sortedBase.filter { song ->
                    val titleLower = song.title.lowercase()
                    val artistLower = song.artistsText?.lowercase().orEmpty()
                    tokens.all { token ->
                        titleLower.contains(token) || artistLower.contains(token)
                    }
                }.toImmutableList()
            }
        }
    }

    val isSpotifyPlaylist = remember(playlist.browseId, playlist.name) {
        playlist.browseId?.startsWith("spotify:", ignoreCase = true) == true ||
            playlist.name.startsWith("Spotify:", ignoreCase = true)
    }

    val bgSyncState by SpotifySyncManager.backgroundSyncState.collectAsState()
    val isThisPlaylistSyncingInBg = isSpotifyPlaylist && bgSyncState.isActive &&
        (bgSyncState.activePlaylistId == playlist.id || bgSyncState.taskTitle.equals(playlist.name, ignoreCase = true) || bgSyncState.taskTitle.equals(playlist.name.removePrefix("Spotify:").trim(), ignoreCase = true))

    val isSyncingNow = loading || isThisPlaylistSyncingInBg
    val displaySyncCurrent = if (isThisPlaylistSyncingInBg) bgSyncState.current else syncCurrent
    val displaySyncTotal = if (isThisPlaylistSyncingInBg) bgSyncState.total else syncTotal

    fun triggerSpotifyPlaylistSync(showToastFeedback: Boolean = true) {
        if (isSyncingNow) return
        SpotifySyncManager.startSyncSpotifyPlaylistInBackground(
            playlist = playlist,
            existingSongs = songs,
            showToastFeedback = showToastFeedback
        )
    }

    LaunchedEffect(Unit) {
        if (DataPreferences.autoSyncPlaylists) {
            if (isSpotifyPlaylist) {
                triggerSpotifyPlaylistSync(showToastFeedback = false)
            } else {
                playlist.browseId?.let { browseId ->
                    loading = true
                    sync(playlist, browseId)
                    loading = false
                }
            }
        }
    }

    val reorderingState = rememberReorderingState(
        lazyListState = lazyListState,
        key = songs,
        onDragEnd = { fromIndex, toIndex ->
            transaction {
                Database.move(playlist.id, fromIndex, toIndex)
            }
        },
        extraItemCount = 1
    )

    var isRenaming by rememberSaveable { mutableStateOf(false) }

    if (isRenaming) TextFieldDialog(
        hintText = stringResource(R.string.enter_playlist_name_prompt),
        initialTextInput = playlist.name,
        onDismiss = { isRenaming = false },
        onAccept = { text ->
            query {
                Database.update(playlist.copy(name = text))
            }
        }
    )

    var isDeleting by rememberSaveable { mutableStateOf(false) }

    if (isDeleting) ConfirmationDialog(
        text = stringResource(R.string.confirm_delete_playlist),
        onDismiss = { isDeleting = false },
        onConfirm = {
            query {
                Database.delete(playlist)
            }
            onDelete()
        }
    )

    var isAddingSongDialog by rememberSaveable { mutableStateOf(false) }
    var isShowingSpotifyDialog by rememberSaveable { mutableStateOf(false) }

    if (isShowingSpotifyDialog) {
        app.vitune.android.ui.screens.spotify.SpotifyImportDialog(
            onDismiss = { isShowingSpotifyDialog = false }
        )
    }

    if (isAddingSongDialog) {
        TextFieldDialog(
            hintText = "Enter song & artist name (or Spotify/YouTube link)",
            onDismiss = { isAddingSongDialog = false },
            onAccept = { queryText ->
                val cleanQuery = queryText.trim()
                if (cleanQuery.isNotBlank()) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val foundSong = Innertube.searchPage(
                            body = SearchBody(
                                query = cleanQuery,
                                params = Innertube.SearchFilter.Song.value
                            ),
                            fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                        )?.getOrNull()?.items?.firstOrNull()

                        if (foundSong != null) {
                            val mediaItem = foundSong.asMediaItem
                            transaction {
                                Database.insert(mediaItem)
                                Database.insert(
                                    SongPlaylistMap(
                                        songId = mediaItem.mediaId,
                                        playlistId = playlist.id,
                                        position = songs.size
                                    )
                                )
                            }
                            withContext(Dispatchers.Main) {
                                context.toast("Added '${foundSong.info?.name ?: cleanQuery}' to '${playlist.name}'")
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                context.toast("Could not find song '$cleanQuery'")
                            }
                        }
                    }
                }
            }
        )
    }

    val (currentMediaId, playing) = playingSong(binder)

    Box {
        LookaheadScope {
            LazyColumn(
                state = reorderingState.lazyListState,
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Header(
                            title = playlist.name,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            HeaderIconButton(
                                icon = R.drawable.add,
                                color = colorPalette.accent,
                                onClick = {
                                    menuState.display {
                                        Menu {
                                            MenuEntry(
                                                icon = R.drawable.add,
                                                text = "Add song to '${playlist.name}'",
                                                onClick = {
                                                    menuState.hide()
                                                    isAddingSongDialog = true
                                                }
                                            )
                                            if (isSpotifyPlaylist) {
                                                MenuEntry(
                                                    icon = R.drawable.sync,
                                                    text = "Sync playlist with Spotify",
                                                    enabled = !loading,
                                                    onClick = {
                                                        menuState.hide()
                                                        triggerSpotifyPlaylistSync(showToastFeedback = true)
                                                    }
                                                )
                                            }
                                            MenuEntry(
                                                icon = R.drawable.sync,
                                                text = "Import from Spotify",
                                                onClick = {
                                                    menuState.hide()
                                                    isShowingSpotifyDialog = true
                                                }
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("local_playlist_add_button")
                            )

                            Spacer(modifier = Modifier.width(4.dp))

                            HeaderIconButton(
                                icon = R.drawable.text,
                                enabled = sortBy == SongSortBy.Title,
                                onClick = { sortBy = SongSortBy.Title },
                                modifier = Modifier.testTag("local_playlist_sort_title")
                            )

                            HeaderIconButton(
                                icon = R.drawable.time,
                                enabled = sortBy == SongSortBy.DateAdded,
                                onClick = { sortBy = SongSortBy.DateAdded },
                                modifier = Modifier.testTag("local_playlist_sort_date")
                            )

                            Spacer(modifier = Modifier.width(2.dp))

                            HeaderIconButton(
                                icon = R.drawable.arrow_up,
                                color = colorPalette.text,
                                onClick = { sortOrder = !sortOrder },
                                modifier = Modifier
                                    .graphicsLayer { rotationZ = sortOrderIconRotation }
                                    .testTag("local_playlist_sort_order")
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
                                modifier = Modifier.testTag("local_playlist_search_toggle")
                            )

                            SecondaryTextButton(
                                text = stringResource(R.string.enqueue),
                                enabled = filteredSongs.isNotEmpty(),
                                onClick = {
                                    binder?.player?.enqueue(filteredSongs.map { it.asMediaItem })
                                }
                            )

                            if (isSpotifyPlaylist) {
                                SecondaryTextButton(
                                    text = when {
                                        isSyncingNow && displaySyncTotal > 0 -> "Syncing ($displaySyncCurrent/$displaySyncTotal)"
                                        isSyncingNow -> "Syncing..."
                                        else -> "Spotify Sync"
                                    },
                                    enabled = !isSyncingNow,
                                    onClick = { triggerSpotifyPlaylistSync(showToastFeedback = true) },
                                    modifier = Modifier.testTag("spotify_playlist_sync_button")
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            AnimatedVisibility(isSyncingNow) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            }

                            PlaylistDownloadIcon(
                                songs = filteredSongs.map { it.asMediaItem }.toImmutableList()
                            )

                            HeaderIconButton(
                                icon = R.drawable.ellipsis_horizontal,
                                color = colorPalette.text,
                                onClick = {
                                    menuState.display {
                                        Menu {
                                            if (isSpotifyPlaylist) {
                                                MenuEntry(
                                                    icon = R.drawable.sync,
                                                    text = "Sync with Spotify",
                                                    enabled = !isSyncingNow,
                                                    onClick = {
                                                        menuState.hide()
                                                        triggerSpotifyPlaylistSync(showToastFeedback = true)
                                                    }
                                                )
                                            } else {
                                                playlist.browseId?.let { browseId ->
                                                    MenuEntry(
                                                        icon = R.drawable.sync,
                                                        text = stringResource(R.string.sync),
                                                        enabled = !loading,
                                                        onClick = {
                                                            menuState.hide()
                                                            coroutineScope.launch {
                                                                loading = true
                                                                sync(playlist, browseId)
                                                                loading = false
                                                            }
                                                        }
                                                    )

                                                    songs.firstOrNull()?.id?.let { firstSongId ->
                                                        MenuEntry(
                                                            icon = R.drawable.play,
                                                            text = stringResource(R.string.watch_playlist_on_youtube),
                                                            onClick = {
                                                                menuState.hide()
                                                                binder?.player?.pause()
                                                                uriHandler.openUri(
                                                                    "https://youtube.com/watch?v=$firstSongId&list=${
                                                                        playlist.browseId.drop(2)
                                                                    }"
                                                                )
                                                            }
                                                        )

                                                        MenuEntry(
                                                            icon = R.drawable.musical_notes,
                                                            text = stringResource(R.string.open_in_youtube_music),
                                                            onClick = {
                                                                menuState.hide()
                                                                binder?.player?.pause()
                                                                if (
                                                                    !launchYouTubeMusic(
                                                                        context = context,
                                                                        endpoint = "watch?v=$firstSongId&list=${
                                                                            playlist.browseId.drop(2)
                                                                        }"
                                                                    )
                                                                ) context.toast(
                                                                    context.getString(R.string.youtube_music_not_installed)
                                                                )
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            MenuEntry(
                                                icon = R.drawable.pencil,
                                                text = stringResource(R.string.rename),
                                                onClick = {
                                                    menuState.hide()
                                                    isRenaming = true
                                                }
                                            )

                                            MenuEntry(
                                                icon = R.drawable.trash,
                                                text = stringResource(R.string.delete),
                                                onClick = {
                                                    menuState.hide()
                                                    isDeleting = true
                                                }
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        AnimatedVisibility(visible = isSearchExpanded || searchQuery.isNotBlank()) {
                            val focusRequester = remember { FocusRequester() }
                            LaunchedEffect(isSearchExpanded) {
                                if (isSearchExpanded) {
                                    runCatching { focusRequester.requestFocus() }
                                }
                            }
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
                                    hintText = "Search song or artist in playlist...",
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
                                        .testTag("local_playlist_search_input")
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
                                            .testTag("local_playlist_search_clear")
                                    )
                                }
                            }
                        }

                        if (!isLandscape) thumbnailContent()
                    }
                }

                itemsIndexed(
                    items = filteredSongs,
                    key = { _, song -> song.id },
                    contentType = { _, song -> song }
                ) { index, song ->
                    val originalPosition = remember(songs, song.id) {
                        songs.indexOfFirst { it.id == song.id }.coerceAtLeast(index)
                    }
                    SongItem(
                        modifier = Modifier
                            .combinedClickable(
                                onLongClick = {
                                    keyboardController?.hide()
                                    menuState.display {
                                        InPlaylistMediaItemMenu(
                                            playlistId = playlist.id,
                                            positionInPlaylist = originalPosition,
                                            song = song,
                                            onDismiss = menuState::hide
                                        )
                                    }
                                },
                                onClick = {
                                    keyboardController?.hide()
                                    binder?.stopRadio()
                                    binder?.player?.forcePlayAtIndex(
                                        items = filteredSongs.map { it.asMediaItem },
                                        index = index,
                                        source = playlist.name
                                    )
                                }
                            )
                            .animateItemPlacement(reorderingState)
                            .draggedItem(
                                reorderingState = reorderingState,
                                index = originalPosition
                            )
                            .background(colorPalette.background0),
                        song = song,
                        thumbnailSize = Dimensions.thumbnails.song,
                        trailingContent = if (
                            searchQuery.isBlank() &&
                            sortBy == SongSortBy.DateAdded &&
                            sortOrder == SortOrder.Ascending
                        ) {
                            {
                                ReorderHandle(
                                    reorderingState = reorderingState,
                                    index = index
                                )
                            }
                        } else null,
                        clip = !reorderingState.isDragging,
                        isPlaying = playing && currentMediaId == song.id
                    )
                }
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyListState = lazyListState,
            icon = R.drawable.shuffle,
            visible = !reorderingState.isDragging,
            onClick = {
                if (filteredSongs.isEmpty()) return@FloatingActionsContainerWithScrollToTop

                binder?.stopRadio()
                binder?.player?.forcePlayFromBeginning(
                    items = filteredSongs.shuffled().map { it.asMediaItem },
                    source = playlist.name
                )
            }
        )
    }
}

private suspend fun sync(
    playlist: Playlist,
    browseId: String
) = runCatching {
    Innertube.playlistPage(
        BrowseBody(browseId = browseId)
    )?.completed()?.getOrNull()?.let { remotePlaylist ->
        transaction {
            Database.clearPlaylist(playlist.id)

            remotePlaylist.songsPage
                ?.items
                ?.map { it.asMediaItem }
                ?.onEach { Database.insert(it) }
                ?.mapIndexed { position, mediaItem ->
                    SongPlaylistMap(
                        songId = mediaItem.mediaId,
                        playlistId = playlist.id,
                        position = position
                    )
                }
                ?.let(Database::insertSongPlaylistMaps)
        }
    }
}.onFailure {
    if (it is CancellationException) throw it
    it.printStackTrace()
}
