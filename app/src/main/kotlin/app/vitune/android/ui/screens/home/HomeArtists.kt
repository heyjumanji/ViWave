package app.vitune.android.ui.screens.home

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.R
import app.vitune.android.models.Artist
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.OrderPreferences
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.ui.items.ArtistItem
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.spotify.SpotifyImportDialog
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.compose.persist.persistList
import app.vitune.core.data.enums.ArtistSortBy
import app.vitune.core.data.enums.SortOrder
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import app.vitune.providers.spotify.requests.SpotifyApi
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Route
@Composable
fun HomeArtistList(
    onArtistClick: (Artist) -> Unit,
    onSearchClick: () -> Unit
) = with(OrderPreferences) {
    val (colorPalette, typography) = LocalAppearance.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var items by persistList<Artist>("home/artists")
    var filter: String? by rememberSaveable { mutableStateOf(null) }
    val filteredItems by remember {
        derivedStateOf {
            filter?.trim()?.lowercase()?.ifBlank { null }?.let { f ->
                items.filter { artist ->
                    artist.name?.lowercase()?.contains(f) == true
                }
            } ?: items
        }
    }

    var showImportArtistDialog by rememberSaveable { mutableStateOf(false) }
    var showSpotifyDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(artistSortBy, artistSortOrder) {
        Database
            .artists(artistSortBy, artistSortOrder)
            .collect { items = it.toImmutableList() }
    }

    val sortOrderIconRotation by animateFloatAsState(
        targetValue = if (artistSortOrder == SortOrder.Ascending) 0f else 180f,
        animationSpec = tween(
            durationMillis = 400,
            easing = LinearEasing
        ),
        label = ""
    )

    val lazyGridState = rememberLazyGridState()

    if (showSpotifyDialog) {
        SpotifyImportDialog(
            onDismiss = { showSpotifyDialog = false }
        )
    }

    if (showImportArtistDialog) {
        var artistInput by rememberSaveable { mutableStateOf("") }
        var isImporting by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf<String?>(null) }

        Dialog(onDismissRequest = { if (!isImporting) showImportArtistDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colorPalette.background1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    BasicText(
                        text = stringResource(R.string.import_artist),
                        style = typography.m.semiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    BasicText(
                        text = stringResource(R.string.import_artist_description),
                        style = typography.xxs.secondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    if (DataPreferences.spotifySpDcCookie.isNotBlank()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = colorPalette.accent,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable(enabled = !isImporting) {
                                    isImporting = true
                                    statusText = "Fetching all followed artists from Spotify..."
                                    scope.launch {
                                        val count = SpotifySyncManager.importAllFollowedArtistsFromSpotify(
                                            onProgress = { progressMsg ->
                                                scope.launch(Dispatchers.Main) {
                                                    statusText = progressMsg
                                                }
                                            }
                                        )
                                        isImporting = false
                                        when {
                                            count > 0 -> {
                                                Toast.makeText(
                                                    context,
                                                    "Imported all $count followed Spotify artists!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                showImportArtistDialog = false
                                            }
                                            count == 0 -> statusText = "No followed artists found on your Spotify account."
                                            else -> statusText = "Spotify session expired. Reconnect via Spotify Library."
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 14.dp)
                        ) {
                            BasicText(
                                text = "1-Click Import All Spotify Followed Artists",
                                style = typography.xs.semiBold.color(colorPalette.background0)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    OutlinedTextField(
                        value = artistInput,
                        onValueChange = { artistInput = it },
                        placeholder = {
                            BasicText(
                                text = "https://open.spotify.com/artist/... or Artist name",
                                style = typography.xxs.secondary
                            )
                        },
                        singleLine = true,
                        enabled = !isImporting,
                        textStyle = typography.xs.copy(color = colorPalette.text),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorPalette.accent,
                            unfocusedBorderColor = colorPalette.background2,
                            cursorColor = colorPalette.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (statusText != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        BasicText(
                            text = statusText.orEmpty(),
                            style = typography.xxs.semiBold.center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isImporting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DialogTextButton(
                            text = "Pick from Spotify",
                            enabled = !isImporting,
                            onClick = {
                                showImportArtistDialog = false
                                showSpotifyDialog = true
                            }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        DialogTextButton(
                            text = "Cancel",
                            enabled = !isImporting,
                            onClick = { showImportArtistDialog = false }
                        )
                        DialogTextButton(
                            text = "Import",
                            primary = true,
                            enabled = !isImporting && artistInput.isNotBlank(),
                            onClick = {
                                val raw = artistInput.trim()
                                isImporting = true
                                statusText = "Resolving artist..."
                                scope.launch {
                                    val importedArtist = withContext(Dispatchers.IO) {
                                        val token = if (DataPreferences.spotifySpDcCookie.isNotBlank()) {
                                            SpotifySyncManager.getValidAccessToken()
                                        } else null
                                        val spotifyMeta = if (SpotifyApi.extractArtistId(raw) != null) {
                                            SpotifyApi.getArtistById(raw, token)
                                        } else null

                                        val queryName = spotifyMeta?.name ?: raw
                                        val ytMatch = Innertube.searchPage(
                                            body = SearchBody(
                                                query = queryName,
                                                params = Innertube.SearchFilter.Artist.value
                                            ),
                                            fromMusicShelfRendererContent = Innertube.ArtistItem::from
                                        )?.getOrNull()?.items?.firstOrNull()

                                        val finalId = ytMatch?.key
                                            ?: spotifyMeta?.id?.let { "spotify:artist:$it" }
                                        val finalName = spotifyMeta?.name
                                            ?: ytMatch?.info?.name
                                            ?: queryName.takeIf { !it.startsWith("http") }

                                        if (!finalId.isNullOrBlank() && !finalName.isNullOrBlank()) {
                                            val now = System.currentTimeMillis()
                                            val entity = Artist(
                                                id = finalId,
                                                name = finalName,
                                                thumbnailUrl = spotifyMeta?.imageUrl ?: ytMatch?.thumbnail?.url,
                                                timestamp = now,
                                                bookmarkedAt = now
                                            )
                                            Database.upsert(entity)
                                            entity
                                        } else null
                                    }
                                    isImporting = false
                                    if (importedArtist != null) {
                                        Toast.makeText(
                                            context,
                                            "Imported ${importedArtist.name} to your Artists!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        showImportArtistDialog = false
                                        onArtistClick(importedArtist)
                                    } else {
                                        statusText = "Could not find artist. Try entering the artist name or a valid Spotify artist link."
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    Box {
        LazyVerticalGrid(
            state = lazyGridState,
            columns = GridCells.Adaptive(Dimensions.thumbnails.song * 2 + Dimensions.items.verticalPadding * 2),
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                .asPaddingValues(),
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(colorPalette.background0)
                .fillMaxSize()
        ) {
            item(
                key = "header",
                contentType = 0,
                span = { GridItemSpan(maxLineSpan) }
            ) {
                Header(title = stringResource(R.string.artists)) {
                    var searching by rememberSaveable { mutableStateOf(false) }

                    AnimatedContent(
                        targetState = searching,
                        label = "artist_header_search"
                    ) { state ->
                        if (state) {
                            val focusRequester = remember { FocusRequester() }

                            LaunchedEffect(Unit) {
                                focusRequester.requestFocus()
                            }

                            TextField(
                                value = filter.orEmpty(),
                                onValueChange = { filter = it },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (filter.isNullOrBlank()) filter = ""
                                    focusManager.clearFocus()
                                }),
                                hintText = stringResource(R.string.filter_placeholder),
                                modifier = Modifier
                                    .focusRequester(focusRequester)
                                    .onFocusChanged {
                                        if (!it.hasFocus) {
                                            keyboardController?.hide()
                                            if (filter?.isBlank() == true) {
                                                filter = null
                                                searching = false
                                            }
                                        }
                                    }
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HeaderIconButton(
                                    onClick = { searching = true },
                                    icon = R.drawable.search,
                                    color = colorPalette.text
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    HeaderIconButton(
                        icon = R.drawable.add,
                        color = colorPalette.accent,
                        onClick = { showImportArtistDialog = true }
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    HeaderIconButton(
                        icon = R.drawable.text,
                        enabled = artistSortBy == ArtistSortBy.Name,
                        onClick = { artistSortBy = ArtistSortBy.Name }
                    )

                    HeaderIconButton(
                        icon = R.drawable.time,
                        enabled = artistSortBy == ArtistSortBy.DateAdded,
                        onClick = { artistSortBy = ArtistSortBy.DateAdded }
                    )

                    Spacer(modifier = Modifier.width(2.dp))

                    HeaderIconButton(
                        icon = R.drawable.arrow_up,
                        color = colorPalette.text,
                        onClick = { artistSortOrder = !artistSortOrder },
                        modifier = Modifier.graphicsLayer { rotationZ = sortOrderIconRotation }
                    )
                }
            }

            items(items = filteredItems, key = Artist::id) { artist ->
                ArtistItem(
                    artist = artist,
                    thumbnailSize = Dimensions.thumbnails.song * 2,
                    alternative = true,
                    modifier = Modifier
                        .clickable(onClick = {
                            keyboardController?.hide()
                            onArtistClick(artist)
                        })
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                )
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyGridState = lazyGridState,
            icon = R.drawable.search,
            onClick = onSearchClick
        )
    }
}

