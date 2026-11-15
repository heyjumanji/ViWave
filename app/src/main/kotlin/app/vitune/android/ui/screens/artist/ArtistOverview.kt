package app.vitune.android.ui.screens.artist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.ShimmerHost
import app.vitune.android.ui.components.themed.Attribution
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.LayoutWithAdaptiveThumbnail
import app.vitune.android.ui.components.themed.NonQueuedMediaItemMenu
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.TextPlaceholder
import app.vitune.android.ui.items.AlbumItem
import app.vitune.android.ui.items.AlbumItemPlaceholder
import app.vitune.android.ui.items.ArtistItem
import app.vitune.android.ui.items.SongItem
import app.vitune.android.ui.items.SongItemPlaceholder
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.forcePlay
import app.vitune.android.utils.medium
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isLandscape
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.NavigationEndpoint

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.vitune.android.models.Artist
import app.vitune.android.utils.color
import app.vitune.android.utils.forcePlayAtIndex
import app.vitune.android.utils.highQualityThumbnail
import app.vitune.android.utils.thumbnail
import app.vitune.providers.spotify.models.SpotifyReleaseItem
import coil3.compose.AsyncImage

private val sectionTextModifier = Modifier
    .padding(horizontal = 16.dp)
    .padding(top = 24.dp, bottom = 8.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistOverview(
    youtubeArtistPage: Innertube.ArtistPage?,
    onViewAllSongsClick: () -> Unit,
    onViewAllAlbumsClick: () -> Unit,
    onViewAllSinglesClick: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    thumbnailContent: @Composable () -> Unit,
    headerContent: @Composable (textButton: (@Composable () -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
    artist: Artist? = null,
    latestRelease: SpotifyReleaseItem? = null,
    onToggleBookmark: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null
) = LayoutWithAdaptiveThumbnail(
    thumbnailContent = thumbnailContent,
    modifier = modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val windowInsets = LocalPlayerAwareWindowInsets.current

    val endPaddingValues = windowInsets.only(WindowInsetsSides.End).asPaddingValues()

    val scrollState = rememberScrollState()
    val artistName = artist?.name ?: youtubeArtistPage?.name ?: stringResource(R.string.unknown)
    val heroImageUrl = (artist?.thumbnailUrl ?: youtubeArtistPage?.thumbnail?.url)?.thumbnail(1000)
    val isFollowed = artist?.bookmarkedAt != null

    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
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
            if (!isLandscape) {
                // Modern Hero Artist Header Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(310.dp)
                        .padding(endPaddingValues)
                ) {
                    if (heroImageUrl != null) {
                        AsyncImage(
                            model = heroImageUrl,
                            contentDescription = artistName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colorPalette.background2)
                        )
                    }

                    // Deep vertical gradient scrim into background0
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Black.copy(alpha = 0.25f),
                                    0.45f to Color.Black.copy(alpha = 0.45f),
                                    0.82f to colorPalette.background0.copy(alpha = 0.92f),
                                    1.0f to colorPalette.background0
                                )
                            )
                    )

                    // Top floating action buttons (Follow + Share)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 14.dp, end = 16.dp)
                    ) {
                        if (onToggleBookmark != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (isFollowed) colorPalette.accent
                                        else Color.Black.copy(alpha = 0.48f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isFollowed) colorPalette.accent else Color.White.copy(alpha = 0.32f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable(onClick = onToggleBookmark)
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Image(
                                    painter = painterResource(
                                        if (isFollowed) R.drawable.bookmark else R.drawable.bookmark_outline
                                    ),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier.size(15.dp)
                                )
                                BasicText(
                                    text = if (isFollowed) "Following" else "Follow",
                                    style = typography.xxs.semiBold.color(Color.White)
                                )
                            }
                        }

                        if (onShare != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.48f))
                                    .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                                    .clickable(onClick = onShare)
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.share_social),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(Color.White),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Artist Title, Subscriber Badge, and Play / Shuffle Action Bar at bottom of Hero
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        BasicText(
                            text = artistName,
                            style = typography.l.semiBold.color(Color.White),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        youtubeArtistPage?.subscribersCountText?.let { subscribers ->
                            BasicText(
                                text = stringResource(R.string.format_subscribers, subscribers),
                                style = typography.xs.medium.color(Color.White.copy(alpha = 0.78f))
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val topSongs = youtubeArtistPage?.songs.orEmpty()
                            if (topSongs.isNotEmpty() || youtubeArtistPage?.radioEndpoint != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(colorPalette.accent)
                                        .clickable {
                                            if (topSongs.isNotEmpty()) {
                                                val mediaItems = topSongs.map { it.asMediaItem }
                                                binder?.stopRadio()
                                                binder?.player?.forcePlayAtIndex(
                                                    items = mediaItems,
                                                    index = 0,
                                                    source = artist?.name ?: youtubeArtistPage?.name
                                                )
                                                youtubeArtistPage?.radioEndpoint?.let { binder?.setupRadio(it) }
                                            } else {
                                                youtubeArtistPage?.radioEndpoint?.let {
                                                    binder?.stopRadio()
                                                    binder?.playRadio(it)
                                                }
                                            }
                                        }
                                        .padding(horizontal = 18.dp, vertical = 10.dp)
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.play),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colorPalette.onAccent),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    BasicText(
                                        text = "Play",
                                        style = typography.xs.semiBold.color(colorPalette.onAccent)
                                    )
                                }
                            }

                            youtubeArtistPage?.shuffleEndpoint?.let { endpoint ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(colorPalette.background2)
                                        .clickable {
                                            binder?.stopRadio()
                                            binder?.playRadio(endpoint)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Image(
                                        painter = painterResource(R.drawable.shuffle),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colorPalette.text),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    BasicText(
                                        text = stringResource(R.string.shuffle),
                                        style = typography.xs.semiBold
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.padding(endPaddingValues)) {
                    headerContent {
                        youtubeArtistPage?.shuffleEndpoint?.let { endpoint ->
                            SecondaryTextButton(
                                text = stringResource(R.string.shuffle),
                                onClick = {
                                    binder?.stopRadio()
                                    binder?.playRadio(endpoint)
                                }
                            )
                        }
                        youtubeArtistPage?.subscribersCountText?.let { subscribers ->
                            BasicText(
                                text = stringResource(R.string.format_subscribers, subscribers),
                                style = typography.xxs.medium
                            )
                        }
                    }
                }
            }

            youtubeArtistPage?.let { artistPageData ->
                latestRelease?.let { latest ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(endPaddingValues)
                    ) {
                        BasicText(
                            text = "Latest Release",
                            style = typography.m.semiBold,
                            modifier = sectionTextModifier
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colorPalette.background1)
                                .clickable {
                                    val targetId = if (latest.id.startsWith("MPREb_") || latest.id.startsWith("spotify:album:")) {
                                        latest.id
                                    } else {
                                        "spotify:album:${latest.id}"
                                    }
                                    onAlbumClick(targetId)
                                }
                                .padding(12.dp)
                        ) {
                            AsyncImage(
                                model = latest.coverUrl,
                                contentDescription = latest.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colorPalette.background2)
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                val typeLabel = if (latest.albumType.equals("album", ignoreCase = true) || latest.totalTracks > 3) {
                                    "ALBUM"
                                } else {
                                    "SINGLE"
                                }
                                val datePart = latest.releaseDate.ifBlank { "" }
                                BasicText(
                                    text = if (datePart.isNotBlank()) "$typeLabel • $datePart" else typeLabel,
                                    style = typography.xxs.semiBold.color(colorPalette.accent)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                BasicText(
                                    text = latest.name,
                                    style = typography.s.semiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (latest.totalTracks > 0) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    BasicText(
                                        text = if (latest.totalTracks == 1) "1 song" else "${latest.totalTracks} songs",
                                        style = typography.xs.secondary
                                    )
                                }
                            }
                        }
                    }
                }

                artistPageData.songs?.takeIf { it.isNotEmpty() }?.let { songs ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(endPaddingValues)
                    ) {
                        BasicText(
                            text = "Popular Songs",
                            style = typography.m.semiBold,
                            modifier = sectionTextModifier
                        )

                        artistPageData.songsEndpoint?.let {
                            BasicText(
                                text = stringResource(R.string.view_all),
                                style = typography.xs.semiBold.color(colorPalette.accent),
                                modifier = sectionTextModifier.clickable(onClick = onViewAllSongsClick)
                            )
                        }
                    }

                    val (currentMediaId, playing) = playingSong(binder)

                    songs.forEachIndexed { index, song ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(endPaddingValues)
                        ) {
                            BasicText(
                                text = "${index + 1}",
                                style = if (playing && currentMediaId == song.key) {
                                    typography.xs.semiBold.color(colorPalette.accent)
                                } else {
                                    typography.xs.semiBold.secondary
                                },
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .width(22.dp)
                            )
                            SongItem(
                                song = song,
                                thumbnailSize = Dimensions.thumbnails.song,
                                modifier = Modifier
                                    .weight(1f)
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
                                            binder?.player?.forcePlay(
                                                mediaItem = mediaItem,
                                                source = artist?.name ?: youtubeArtistPage?.name
                                            )
                                            binder?.setupRadio(
                                                NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId)
                                            )
                                        }
                                    ),
                                isPlaying = playing && currentMediaId == song.key
                            )
                        }
                    }
                }

                artistPageData.albums?.filter { it.key.isNotBlank() }?.distinctBy(Innertube.AlbumItem::key)?.takeIf { it.isNotEmpty() }?.let { albums ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(endPaddingValues)
                    ) {
                        BasicText(
                            text = stringResource(R.string.albums),
                            style = typography.m.semiBold,
                            modifier = sectionTextModifier
                        )

                        BasicText(
                            text = stringResource(R.string.view_all),
                            style = typography.xs.semiBold.color(colorPalette.accent),
                            modifier = sectionTextModifier.clickable(onClick = onViewAllAlbumsClick)
                        )
                    }

                    LazyRow(
                        contentPadding = endPaddingValues,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(
                            items = albums,
                            key = Innertube.AlbumItem::key
                        ) { album ->
                            AlbumItem(
                                album = album,
                                thumbnailSize = Dimensions.thumbnails.album,
                                alternative = true,
                                modifier = Modifier.clickable {
                                    onAlbumClick(album.key)
                                }
                            )
                        }
                    }
                }

                artistPageData.singles?.filter { it.key.isNotBlank() }?.distinctBy(Innertube.AlbumItem::key)?.takeIf { it.isNotEmpty() }?.let { singles ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(endPaddingValues)
                    ) {
                        BasicText(
                            text = stringResource(R.string.singles),
                            style = typography.m.semiBold,
                            modifier = sectionTextModifier
                        )

                        BasicText(
                            text = stringResource(R.string.view_all),
                            style = typography.xs.semiBold.color(colorPalette.accent),
                            modifier = sectionTextModifier.clickable(onClick = onViewAllSinglesClick)
                        )
                    }

                    LazyRow(
                        contentPadding = endPaddingValues,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(
                            items = singles,
                            key = Innertube.AlbumItem::key
                        ) { album ->
                            AlbumItem(
                                album = album,
                                thumbnailSize = Dimensions.thumbnails.album,
                                alternative = true,
                                modifier = Modifier.clickable(onClick = { onAlbumClick(album.key) })
                            )
                        }
                    }
                }

                artistPageData.similarArtists?.filter { it.key.isNotBlank() }?.distinctBy(Innertube.ArtistItem::key)?.takeIf { it.isNotEmpty() }?.let { similarArtists ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(endPaddingValues)
                    ) {
                        BasicText(
                            text = stringResource(R.string.similar_artists),
                            style = typography.m.semiBold,
                            modifier = sectionTextModifier
                        )
                    }

                    LazyRow(
                        contentPadding = endPaddingValues,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(
                            items = similarArtists,
                            key = Innertube.ArtistItem::key
                        ) { artistItem ->
                            ArtistItem(
                                artist = artistItem,
                                thumbnailSize = Dimensions.thumbnails.album,
                                alternative = true,
                                modifier = Modifier.clickable(onClick = { onArtistClick(artistItem.key) })
                            )
                        }
                    }
                }

                artistPageData.description?.takeIf { it.isNotBlank() }?.let { description ->
                    var isExpanded by remember { mutableStateOf(false) }
                    var hasOverflow by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(endPaddingValues)
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colorPalette.background1)
                            .padding(16.dp)
                    ) {
                        BasicText(
                            text = "About $artistName",
                            style = typography.s.semiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        BasicText(
                            text = description,
                            style = typography.xs.secondary,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { textLayoutResult ->
                                if (textLayoutResult.hasVisualOverflow) {
                                    hasOverflow = true
                                }
                            }
                        )
                        if (hasOverflow || isExpanded) {
                            BasicText(
                                text = stringResource(if (isExpanded) R.string.view_less else R.string.view_more),
                                style = typography.xs.semiBold.color(colorPalette.accent),
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .clickable { isExpanded = !isExpanded }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            } ?: ArtistOverviewBodyPlaceholder()
        }

        youtubeArtistPage?.radioEndpoint?.let { endpoint ->
            FloatingActionsContainerWithScrollToTop(
                scrollState = scrollState,
                icon = R.drawable.radio,
                onClick = {
                    binder?.stopRadio()
                    binder?.playRadio(endpoint)
                }
            )
        }
    }
}

@Composable
fun ArtistOverviewBodyPlaceholder(modifier: Modifier = Modifier) = ShimmerHost(
    modifier = modifier
) {
    TextPlaceholder(modifier = sectionTextModifier)

    repeat(5) {
        SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
    }

    repeat(2) {
        TextPlaceholder(modifier = sectionTextModifier)

        Row {
            repeat(2) {
                AlbumItemPlaceholder(
                    thumbnailSize = Dimensions.thumbnails.album,
                    alternative = true
                )
            }
        }
    }
}
