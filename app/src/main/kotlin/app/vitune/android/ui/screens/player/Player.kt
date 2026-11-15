package app.vitune.android.ui.screens.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.ui.toUiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.query
import app.vitune.android.service.PlayerService
import app.vitune.android.transaction
import app.vitune.android.ui.components.BottomSheet
import app.vitune.android.ui.components.BottomSheetState
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.rememberBottomSheetState
import app.vitune.android.ui.components.themed.BaseMediaItemMenu
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.components.themed.SliderDialog
import app.vitune.android.ui.components.themed.SliderDialogBody
import app.vitune.android.ui.modifiers.PinchDirection
import app.vitune.android.ui.modifiers.onSwipe
import app.vitune.android.ui.modifiers.pinchToToggle
import app.vitune.android.utils.DisposableListener
import app.vitune.android.utils.Pip
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.positionAndDurationState
import app.vitune.android.utils.rememberEqualizerLauncher
import app.vitune.android.utils.rememberPipHandler
import app.vitune.android.utils.seamlessPlay
import app.vitune.android.utils.secondary
import app.vitune.android.utils.highQualityThumbnail
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.shouldBePlaying
import app.vitune.android.utils.thumbnail
import app.vitune.compose.persist.PersistMapCleanup
import app.vitune.compose.routing.OnGlobalRoute
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.ThumbnailRoundness
import app.vitune.core.ui.collapsedPlayerProgressBar
import app.vitune.core.ui.utils.isLandscape
import app.vitune.core.ui.utils.px
import app.vitune.core.ui.utils.roundedShape
import app.vitune.core.ui.utils.songBundle
import app.vitune.providers.innertube.models.NavigationEndpoint
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import app.vitune.android.utils.collectProvidedBitmapAsState
import app.vitune.core.ui.ColorSource
import app.vitune.core.ui.Darkness
import app.vitune.core.ui.colorPaletteOf
import coil3.compose.AsyncImage
import coil3.toBitmap
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.absoluteValue

@Composable
fun Player(
    layoutState: BottomSheetState,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp
    ),
    windowInsets: WindowInsets = WindowInsets.systemBars
) = with(PlayerPreferences) {
    val menuState = LocalMenuState.current
    val baseAppearance = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val sampleBitmap = binder.collectProvidedBitmapAsState()

    var mediaItem by remember(binder) {
        mutableStateOf(
            value = binder?.player?.currentMediaItem,
            policy = neverEqualPolicy()
        )
    }
    var shouldBePlaying by remember(binder) { mutableStateOf(binder?.player?.shouldBePlaying == true) }

    var fallbackArtworkBitmap by remember(mediaItem?.mediaId) { mutableStateOf<Bitmap?>(null) }
    val activeArtworkBitmap = fallbackArtworkBitmap ?: sampleBitmap

    val dynamicPlayerPalette = remember(activeArtworkBitmap, baseAppearance.colorPalette) {
        if (activeArtworkBitmap != null) {
            colorPaletteOf(
                source = ColorSource.Dynamic,
                darkness = Darkness.Normal,
                isDark = baseAppearance.colorPalette.isDark,
                materialAccentColor = null,
                sampleBitmap = activeArtworkBitmap
            )
        } else {
            baseAppearance.colorPalette
        }
    }

    CompositionLocalProvider(
        LocalAppearance provides baseAppearance.copy(colorPalette = dynamicPlayerPalette)
    ) {
    val (colorPalette, typography, thumbnailCornerSize) = LocalAppearance.current

    val pipHandler = rememberPipHandler()

    PersistMapCleanup(prefix = "queue/suggestions")

    var likedAt by remember(mediaItem) {
        mutableStateOf(
            value = null,
            policy = object : SnapshotMutationPolicy<Long?> {
                override fun equivalent(a: Long?, b: Long?): Boolean {
                    mediaItem?.let { item ->
                        query {
                            if (Database.like(item.mediaId, b) == 0 && b != null) {
                                Database.insert(item) { song -> song.copy(likedAt = b) }
                            }
                        }
                    }
                    return a == b
                }
            }
        )
    }

    LaunchedEffect(mediaItem) {
        mediaItem?.mediaId?.let { mediaId ->
            Database
                .likedAt(mediaId)
                .distinctUntilChanged()
                .collect { likedAt = it }
        }
    }

    binder?.player.DisposableListener {
        object : Player.Listener {
            override fun onMediaItemTransition(newMediaItem: MediaItem?, reason: Int) {
                mediaItem = newMediaItem
                fallbackArtworkBitmap = null
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                shouldBePlaying = player.shouldBePlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                shouldBePlaying = player.shouldBePlaying
            }
        }
    }

    val (position, duration) = binder?.player.positionAndDurationState()
    val metadata = remember(mediaItem) { mediaItem?.mediaMetadata }
    val extras = remember(metadata) { metadata?.extras?.songBundle }

    val horizontalBottomPaddingValues = windowInsets
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        .asPaddingValues()

    OnGlobalRoute { if (layoutState.expanded) layoutState.collapseSoft() }

    if (mediaItem != null) BottomSheet(
        state = layoutState,
        modifier = modifier.fillMaxSize(),
        onDismiss = {
            binder?.let { onDismiss(it) }
            layoutState.dismissSoft()
        },
        backHandlerEnabled = !menuState.isDisplayed,
        collapsedContent = { innerModifier ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .let { modifier ->
                        if (horizontalSwipeToClose) modifier.onSwipe(
                            animateOffset = true,
                            onSwipeOut = { animationJob ->
                                binder?.let { onDismiss(it) }
                                animationJob.join()
                                layoutState.dismissSoft()
                            }
                        ) else modifier
                    }
                    .fillMaxSize()
                    .clip(shape)
                    .background(colorPalette.background1)
                    .drawBehind {
                        drawRect(
                            color = colorPalette.collapsedPlayerProgressBar,
                            topLeft = Offset.Zero,
                            size = Size(
                                width = runCatching {
                                    size.width * (position.toFloat() / duration.absoluteValue)
                                }.getOrElse { 0f },
                                height = size.height
                            )
                        )
                    }
                    .then(innerModifier)
                    .padding(horizontalBottomPaddingValues)
            ) {
                Spacer(modifier = Modifier.width(2.dp))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.height(Dimensions.items.collapsedPlayerHeight)
                ) {
                    AsyncImage(
                        model = metadata?.artworkUri?.highQualityThumbnail(),
                        onSuccess = { state ->
                            if (fallbackArtworkBitmap == null) {
                                fallbackArtworkBitmap = runCatching {
                                    state.result.image.run { toBitmap(width, height) }.ensureSoftwareBitmap()
                                }.getOrNull()
                            }
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .clip(thumbnailCornerSize.coerceAtMost(ThumbnailRoundness.Heavy.dp).roundedShape)
                            .background(colorPalette.background0)
                            .size(48.dp)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .height(Dimensions.items.collapsedPlayerHeight)
                        .weight(1f)
                ) {
                    AnimatedContent(
                        targetState = metadata?.title?.toString().orEmpty(),
                        label = "",
                        transitionSpec = { fadeIn() togetherWith fadeOut() }
                    ) { text ->
                        BasicText(
                            text = text,
                            style = typography.xs.semiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    AnimatedVisibility(visible = metadata?.artist != null) {
                        AnimatedContent(
                            targetState = metadata?.artist?.toString().orEmpty(),
                            label = "",
                            transitionSpec = { fadeIn() togetherWith fadeOut() }
                        ) { text ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                BasicText(
                                    text = text,
                                    style = typography.xs.semiBold.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                AnimatedVisibility(visible = extras?.explicit == true) {
                                    Image(
                                        painter = painterResource(R.drawable.explicit),
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colorPalette.text),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(Dimensions.items.collapsedPlayerHeight)
                ) {
                    AnimatedVisibility(visible = isShowingPrevButtonCollapsed) {
                        IconButton(
                            icon = R.drawable.play_skip_back,
                            color = colorPalette.text,
                            onClick = { binder?.player?.forceSeekToPrevious() },
                            modifier = Modifier
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .size(20.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clickable(
                                onClick = {
                                    if (shouldBePlaying) binder?.player?.pause()
                                    else {
                                        if (binder?.player?.playbackState == Player.STATE_IDLE) binder.player.prepare()
                                        binder?.player?.play()
                                    }
                                },
                                indication = ripple(bounded = false),
                                interactionSource = remember { MutableInteractionSource() }
                            )
                            .clip(CircleShape)
                    ) {
                        AnimatedPlayPauseButton(
                            playing = shouldBePlaying,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .size(23.dp)
                        )
                    }

                    IconButton(
                        icon = R.drawable.play_skip_forward,
                        color = colorPalette.text,
                        onClick = { binder?.player?.forceSeekToNext() },
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 8.dp)
                            .size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))
            }
        }
    ) {
        var isShowingStatsForNerds by rememberSaveable { mutableStateOf(false) }
        var isShowingLyricsDialog by rememberSaveable { mutableStateOf(false) }

        if (isShowingLyricsDialog) LyricsDialog(onDismiss = { isShowingLyricsDialog = false })

        val isAppleLayout = playerLayout == PlayerPreferences.PlayerLayout.Apple

        val playerBottomSheetState = rememberBottomSheetState(
            dismissedBound = if (isAppleLayout) {
                0.dp
            } else {
                64.dp + horizontalBottomPaddingValues.calculateBottomPadding()
            },
            expandedBound = layoutState.expandedBound
        )

        val infiniteGradTransition = if (playerAnimatedGradient) {
            rememberInfiniteTransition(label = "playerGraduation")
        } else null

        val gradShiftState = infiniteGradTransition?.animateFloat(
            initialValue = 0.30f,
            targetValue = 0.62f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "gradShift"
        )
        val glowDriftState = infiniteGradTransition?.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.78f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 8500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowDrift"
        )

        val animatedBg1 by animateColorAsState(
            targetValue = colorPalette.background1,
            animationSpec = tween(durationMillis = 600),
            label = "animatedBg1"
        )
        val animatedBg2 by animateColorAsState(
            targetValue = colorPalette.background2,
            animationSpec = tween(durationMillis = 600),
            label = "animatedBg2"
        )
        val animatedAccent by animateColorAsState(
            targetValue = colorPalette.accent,
            animationSpec = tween(durationMillis = 600),
            label = "animatedAccent"
        )
        val animatedBg0 by animateColorAsState(
            targetValue = colorPalette.background0,
            animationSpec = tween(durationMillis = 600),
            label = "animatedBg0"
        )

        val isFullScreenPortrait = fullScreenPlayer && !isLandscape

        val containerModifier = Modifier
            .clip(shape)
            .background(animatedBg0)
            .drawBehind {
                if (playerAnimatedGradient) {
                    val currentGradShift = gradShiftState?.value ?: 0.45f
                    val currentGlowDrift = glowDriftState?.value ?: 0.50f
                    val verticalBrush = if (isFullScreenPortrait) {
                        Brush.verticalGradient(
                            0.0f to animatedBg1,
                            0.50f to animatedBg1,
                            (0.55f + (currentGradShift - 0.30f) * 0.6f) to animatedBg2.copy(alpha = 0.85f),
                            0.92f to animatedBg0,
                            1.0f to animatedBg0
                        )
                    } else {
                        Brush.verticalGradient(
                            0.0f to animatedBg1,
                            currentGradShift to animatedBg2.copy(alpha = 0.78f),
                            0.90f to animatedBg0,
                            1.0f to animatedBg0
                        )
                    }
                    drawRect(brush = verticalBrush)

                    val maxDim = size.maxDimension.coerceAtLeast(1f)
                    val glowBrush = Brush.radialGradient(
                        colors = listOf(
                            animatedAccent.copy(alpha = if (isFullScreenPortrait) 0.16f else 0.22f),
                            animatedBg2.copy(alpha = if (isFullScreenPortrait) 0.12f else 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(
                            x = size.width * currentGlowDrift,
                            y = size.height * (if (isFullScreenPortrait) 0.80f else currentGradShift * 0.85f)
                        ),
                        radius = maxDim * (if (isFullScreenPortrait) 0.42f else 0.75f)
                    )
                    drawRect(brush = glowBrush)
                } else {
                    val staticBrush = if (isFullScreenPortrait) {
                        Brush.verticalGradient(
                            0.0f to animatedBg1,
                            0.52f to animatedBg1,
                            0.78f to animatedBg2.copy(alpha = 0.75f),
                            1.0f to animatedBg0
                        )
                    } else {
                        Brush.verticalGradient(
                            0.5f to animatedBg1,
                            1f to animatedBg0
                        )
                    }
                    drawRect(brush = staticBrush)
                }
            }
            .padding(
                windowInsets
                    .only(
                        if (isFullScreenPortrait) {
                            WindowInsetsSides.Horizontal
                        } else {
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        }
                    )
                    .asPaddingValues()
            )
            .padding(bottom = playerBottomSheetState.collapsedBound)

        val onUserSetLikedAt: (Long?) -> Unit = { newLikedAt ->
            likedAt = newLikedAt
            if (newLikedAt != null) {
                mediaItem?.let { currentItem ->
                    app.vitune.android.utils.SpotifySyncManager.onSongLiked(
                        songId = currentItem.mediaId,
                        fallbackTitle = currentItem.mediaMetadata.title?.toString(),
                        fallbackArtist = currentItem.mediaMetadata.artist?.toString()
                    )
                }
            }
        }

        var audioDialogOpen by rememberSaveable { mutableStateOf(false) }
        var boostDialogOpen by rememberSaveable { mutableStateOf(false) }

        val openPlayerMenu: () -> Unit = {
            mediaItem?.let { currentMediaItem ->
                menuState.display {
                    if (binder != null) {
                        PlayerMenu(
                            onDismiss = menuState::hide,
                            mediaItem = currentMediaItem,
                            binder = binder,
                            onShowSpeedDialog = { audioDialogOpen = true },
                            onShowNormalizationDialog = {
                                boostDialogOpen = true
                            }.takeIf { volumeNormalization }
                        )
                    }
                }
            }
        }

        val thumbnailContent: @Composable (modifier: Modifier) -> Unit = { innerModifier ->
            Pip(
                numerator = 1,
                denominator = 1,
                modifier = innerModifier
            ) {
                Thumbnail(
                    isShowingLyrics = isShowingLyrics && !isShowingLyricsDialog,
                    onShowLyrics = { isShowingLyrics = it },
                    isShowingStatsForNerds = isShowingStatsForNerds,
                    onShowStatsForNerds = { isShowingStatsForNerds = it },
                    onOpenDialog = { isShowingLyricsDialog = true },
                    likedAt = likedAt,
                    setLikedAt = onUserSetLikedAt,
                    isFullScreen = fullScreenPlayer && !isLandscape,
                    animatedGradientEnabled = playerAnimatedGradient,
                    shouldBePlaying = shouldBePlaying,
                    onBitmapLoaded = { loadedBmp ->
                        fallbackArtworkBitmap = loadedBmp.ensureSoftwareBitmap()
                    },
                    modifier = Modifier
                        .nestedScroll(layoutState.preUpPostDownNestedScrollConnection)
                        .pinchToToggle(
                            key = isShowingLyricsDialog,
                            direction = PinchDirection.Out,
                            threshold = 1.05f,
                            onPinch = {
                                if (isShowingLyrics) isShowingLyricsDialog = true
                            }
                        )
                        .pinchToToggle(
                            key = isShowingLyricsDialog,
                            direction = PinchDirection.In,
                            threshold = .95f,
                            onPinch = {
                                pipHandler.enterPictureInPictureMode()
                            }
                        )
                )
            }
        }

        val controlsContent: @Composable (modifier: Modifier) -> Unit = { innerModifier ->
            Controls(
                media = mediaItem?.toUiMedia(duration),
                binder = binder,
                likedAt = likedAt,
                setLikedAt = onUserSetLikedAt,
                shouldBePlaying = shouldBePlaying,
                position = position,
                isShowingLyrics = isShowingLyrics,
                onToggleLyrics = { isShowingLyrics = !isShowingLyrics },
                onOpenQueue = { playerBottomSheetState.expandSoft() },
                onOpenMenu = openPlayerMenu,
                onOpenSpeedDialog = { audioDialogOpen = true },
                modifier = innerModifier
            )
        }

        val currentMediaItem = mediaItem
        val currentUiMedia = remember(currentMediaItem, duration) { currentMediaItem?.toUiMedia(duration) }

        if (isAppleLayout && currentMediaItem != null && currentUiMedia != null && binder != null) {
            BitChordNowPlayingScreen(
                mediaItem = currentMediaItem,
                media = currentUiMedia,
                binder = binder,
                shouldBePlaying = shouldBePlaying,
                position = position,
                likedAt = likedAt,
                setLikedAt = onUserSetLikedAt,
                isShowingLyrics = isShowingLyrics && !isShowingLyricsDialog,
                setIsShowingLyrics = { isShowingLyrics = it },
                isShowingStatsForNerds = isShowingStatsForNerds,
                setIsShowingStatsForNerds = { isShowingStatsForNerds = it },
                onOpenLyricsDialog = { isShowingLyricsDialog = true },
                onOpenQueue = { playerBottomSheetState.expandSoft() },
                onOpenMenu = openPlayerMenu,
                onOpenSpeedDialog = { audioDialogOpen = true },
                activeArtworkBitmap = activeArtworkBitmap,
                onBitmapLoaded = { loadedBmp ->
                    fallbackArtworkBitmap = loadedBmp.ensureSoftwareBitmap()
                },
                layoutState = layoutState,
                windowInsets = windowInsets,
                shape = shape
            )
        } else if (isLandscape) Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = containerModifier
                .padding(top = 32.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(0.66f)
                    .padding(bottom = 16.dp)
            ) {
                thumbnailContent(Modifier.padding(horizontal = 16.dp))
            }

            controlsContent(
                Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxHeight()
                    .weight(1f)
            )
        } else Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = containerModifier
                .let {
                    if (isFullScreenPortrait) it else it.padding(top = 54.dp)
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1.25f)
            ) {
                thumbnailContent(
                    if (isFullScreenPortrait) Modifier.fillMaxSize()
                    else Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }

            controlsContent(
                Modifier
                    .padding(vertical = 8.dp)
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        if (audioDialogOpen) SliderDialog(
            onDismiss = { audioDialogOpen = false },
            title = stringResource(R.string.playback_settings)
        ) {
            SliderDialogBody(
                provideState = { remember(speed) { mutableFloatStateOf(speed) } },
                onSlideComplete = { speed = it },
                min = 0f,
                max = 2f,
                toDisplay = {
                    if (it <= 0.01f) stringResource(R.string.minimum_speed_value)
                    else stringResource(R.string.format_multiplier, "%.2f".format(it))
                },
                steps = 39,
                label = stringResource(R.string.playback_speed)
            )
            SliderDialogBody(
                provideState = { remember(pitch) { mutableFloatStateOf(pitch) } },
                onSlideComplete = { pitch = it },
                min = 0f,
                max = 2f,
                toDisplay = {
                    if (it <= 0.01f) stringResource(R.string.minimum_speed_value)
                    else stringResource(R.string.format_multiplier, "%.2f".format(it))
                },
                steps = 39,
                label = stringResource(R.string.playback_pitch)
            )
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                SecondaryTextButton(
                    text = stringResource(R.string.reset),
                    onClick = {
                        speed = 1f
                        pitch = 1f
                    }
                )
            }
        }

        if (boostDialogOpen) {
            fun submit(state: Float) = transaction {
                mediaItem?.mediaId?.let { mediaId ->
                    Database.setLoudnessBoost(
                        songId = mediaId,
                        loudnessBoost = state.takeUnless { it == 0f }
                    )
                }
            }

            SliderDialog(
                onDismiss = { boostDialogOpen = false },
                title = stringResource(R.string.volume_boost)
            ) {
                SliderDialogBody(
                    provideState = {
                        val state = remember { mutableFloatStateOf(0f) }

                        LaunchedEffect(mediaItem) {
                            mediaItem?.mediaId?.let { mediaId ->
                                Database
                                    .loudnessBoost(mediaId)
                                    .distinctUntilChanged()
                                    .collect { state.floatValue = it ?: 0f }
                            }
                        }

                        state
                    },
                    onSlideComplete = { submit(it) },
                    min = -20f,
                    max = 20f,
                    toDisplay = { stringResource(R.string.format_db, "%.2f".format(it)) }
                )
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    SecondaryTextButton(
                        text = stringResource(R.string.reset),
                        onClick = { submit(0f) }
                    )
                }
            }
        }

        if (binder != null && !isAppleLayout) Queue(
            layoutState = playerBottomSheetState,
            binder = binder,
            beforeContent = {
                if (playerLayout == PlayerPreferences.PlayerLayout.New || playerLayout == PlayerPreferences.PlayerLayout.Material) IconButton(
                    onClick = { trackLoopEnabled = !trackLoopEnabled },
                    icon = R.drawable.infinite,
                    enabled = trackLoopEnabled,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(20.dp)
                ) else Spacer(modifier = Modifier.width(20.dp))
            },
            afterContent = {
                IconButton(
                    icon = R.drawable.ellipsis_horizontal,
                    color = colorPalette.text,
                    onClick = {
                        mediaItem?.let {
                            menuState.display {
                                PlayerMenu(
                                    onDismiss = menuState::hide,
                                    mediaItem = it,
                                    binder = binder,
                                    onShowSpeedDialog = { audioDialogOpen = true },
                                    onShowNormalizationDialog = {
                                        boostDialogOpen = true
                                    }.takeIf { volumeNormalization }
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .size(20.dp)
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            shape = shape
        )
    }
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun PlayerMenu(
    binder: PlayerService.Binder,
    mediaItem: MediaItem,
    onDismiss: () -> Unit,
    onShowSpeedDialog: (() -> Unit)? = null,
    onShowNormalizationDialog: (() -> Unit)? = null
) {
    val launchEqualizer by rememberEqualizerLauncher(audioSessionId = { binder.player.audioSessionId })

    BaseMediaItemMenu(
        mediaItem = mediaItem,
        onStartRadio = {
            binder.stopRadio()
            binder.player.seamlessPlay(mediaItem)
            binder.setupRadio(NavigationEndpoint.Endpoint.Watch(videoId = mediaItem.mediaId))
        },
        onGoToEqualizer = launchEqualizer,
        onShowSleepTimer = {},
        onDismiss = onDismiss,
        onShowSpeedDialog = onShowSpeedDialog,
        onShowNormalizationDialog = onShowNormalizationDialog
    )
}

private fun onDismiss(binder: PlayerService.Binder) {
    binder.stopRadio()
    binder.player.clearMediaItems()
}


