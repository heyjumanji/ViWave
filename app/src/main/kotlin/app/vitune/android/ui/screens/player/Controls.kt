package app.vitune.android.ui.screens.player

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Format
import app.vitune.android.models.Info
import app.vitune.android.models.ui.UiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.PlayerService
import app.vitune.android.ui.components.FadingRow
import app.vitune.android.ui.components.SeekBar
import app.vitune.android.ui.components.themed.BigIconButton
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.ui.screens.artistRoute
import app.vitune.android.utils.bold
import app.vitune.android.utils.color
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.medium
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.favoritesIcon
import app.vitune.core.ui.utils.px
import app.vitune.core.ui.utils.roundedShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import app.vitune.android.utils.formatAsDuration
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val DefaultOffset = 24.dp

@Composable
fun Controls(
    media: UiMedia?,
    binder: PlayerService.Binder?,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    shouldBePlaying: Boolean,
    position: Long,
    modifier: Modifier = Modifier,
    isShowingLyrics: Boolean = false,
    onToggleLyrics: () -> Unit = {},
    onOpenQueue: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    onOpenSpeedDialog: () -> Unit = {},
    layout: PlayerPreferences.PlayerLayout = PlayerPreferences.playerLayout
) {
    val shouldBePlayingTransition = updateTransition(
        targetState = shouldBePlaying,
        label = "shouldBePlaying"
    )

    val playButtonRadius by shouldBePlayingTransition.animateDp(
        transitionSpec = { tween(durationMillis = 100, easing = LinearEasing) },
        label = "playPauseRoundness",
        targetValueByState = { if (it) 16.dp else 32.dp }
    )

    if (media != null && binder != null) when (layout) {
        PlayerPreferences.PlayerLayout.Classic -> ClassicControls(
            media = media,
            binder = binder,
            shouldBePlaying = shouldBePlaying,
            position = position,
            likedAt = likedAt,
            setLikedAt = setLikedAt,
            playButtonRadius = playButtonRadius,
            modifier = modifier
        )

        PlayerPreferences.PlayerLayout.New -> ModernControls(
            media = media,
            binder = binder,
            shouldBePlaying = shouldBePlaying,
            position = position,
            likedAt = likedAt,
            setLikedAt = setLikedAt,
            playButtonRadius = playButtonRadius,
            modifier = modifier
        )

        PlayerPreferences.PlayerLayout.Material -> MaterialControls(
            media = media,
            binder = binder,
            shouldBePlaying = shouldBePlaying,
            position = position,
            likedAt = likedAt,
            setLikedAt = setLikedAt,
            playButtonRadius = playButtonRadius,
            modifier = modifier
        )

        PlayerPreferences.PlayerLayout.Apple -> AppleControls(
            media = media,
            binder = binder,
            shouldBePlaying = shouldBePlaying,
            position = position,
            likedAt = likedAt,
            setLikedAt = setLikedAt,
            isShowingLyrics = isShowingLyrics,
            onToggleLyrics = onToggleLyrics,
            onOpenQueue = onOpenQueue,
            onOpenMenu = onOpenMenu,
            onOpenSpeedDialog = onOpenSpeedDialog,
            modifier = modifier
        )
    }
}

@Composable
private fun ClassicControls(
    media: UiMedia,
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    position: Long,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    playButtonRadius: Dp,
    modifier: Modifier = Modifier
) = with(PlayerPreferences) {
    val (colorPalette) = LocalAppearance.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        MediaInfo(media)
        Spacer(modifier = Modifier.weight(1f))
        SeekBar(
            binder = binder,
            position = position,
            media = media,
            alwaysShowDuration = true
        )
        Spacer(modifier = Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                icon = if (likedAt == null) R.drawable.heart_outline else R.drawable.heart,
                color = colorPalette.favoritesIcon,
                onClick = {
                    setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                },
                modifier = Modifier
                    .weight(1f)
                    .size(24.dp)
            )

            IconButton(
                icon = R.drawable.play_skip_back,
                color = colorPalette.text,
                onClick = binder.player::forceSeekToPrevious,
                modifier = Modifier
                    .weight(1f)
                    .size(24.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .clip(playButtonRadius.roundedShape)
                    .clickable {
                        if (shouldBePlaying) binder.player.pause()
                        else {
                            if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                            binder.player.play()
                        }
                    }
                    .background(colorPalette.background2)
                    .size(64.dp)
            ) {
                AnimatedPlayPauseButton(
                    playing = shouldBePlaying,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                icon = R.drawable.play_skip_forward,
                color = colorPalette.text,
                onClick = binder.player::forceSeekToNext,
                modifier = Modifier
                    .weight(1f)
                    .size(24.dp)
            )

            IconButton(
                icon = R.drawable.infinite,
                enabled = trackLoopEnabled,
                onClick = { trackLoopEnabled = !trackLoopEnabled },
                modifier = Modifier
                    .weight(1f)
                    .size(24.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ModernControls(
    media: UiMedia,
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    position: Long,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    playButtonRadius: Dp,
    modifier: Modifier = Modifier,
    controlHeight: Dp = 64.dp
) {
    val previousButtonContent: @Composable RowScope.() -> Unit = {
        SkipButton(
            iconId = R.drawable.play_skip_back,
            onClick = binder.player::forceSeekToPrevious,
            modifier = Modifier.weight(1f),
            offsetOnPress = -DefaultOffset
        )
    }

    val likeButtonContent: @Composable RowScope.() -> Unit = {
        BigIconButton(
            iconId = if (likedAt == null) R.drawable.heart_outline else R.drawable.heart,
            onClick = {
                setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
            },
            modifier = Modifier.weight(1f)
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        MediaInfo(media)
        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (PlayerPreferences.showLike) 4.dp else 8.dp)
        ) {
            if (PlayerPreferences.showLike) previousButtonContent()
            PlayButton(
                radius = playButtonRadius,
                shouldBePlaying = shouldBePlaying,
                modifier = Modifier
                    .height(controlHeight)
                    .weight(if (PlayerPreferences.showLike) 3f else 4f)
            )
            SkipButton(
                iconId = R.drawable.play_skip_forward,
                onClick = binder.player::forceSeekToNext,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (PlayerPreferences.showLike) likeButtonContent() else previousButtonContent()

            Column(modifier = Modifier.weight(4f)) {
                SeekBar(
                    binder = binder,
                    position = position,
                    media = media
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MaterialControls(
    media: UiMedia,
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    position: Long,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    playButtonRadius: Dp,
    modifier: Modifier = Modifier
) {
    val (colorPalette) = LocalAppearance.current
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaInfo(media = media, modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start)
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(
                icon = R.drawable.share_social,
                color = colorPalette.textSecondary,
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${media.id}")
                    }
                    context.startActivity(Intent.createChooser(shareIntent, media.title))
                },
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(
                icon = if (likedAt == null) R.drawable.heart_outline else R.drawable.heart,
                color = if (likedAt == null) colorPalette.textSecondary else colorPalette.favoritesIcon,
                onClick = {
                    setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                },
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        SeekBar(
            binder = binder,
            position = position,
            media = media,
            alwaysShowDuration = true
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                icon = R.drawable.shuffle,
                color = colorPalette.text,
                onClick = {
                    binder.player.shuffleModeEnabled = !binder.player.shuffleModeEnabled
                },
                modifier = Modifier.size(24.dp)
            )
            SkipButton(
                iconId = R.drawable.play_skip_back,
                onClick = binder.player::forceSeekToPrevious,
                modifier = Modifier.size(32.dp),
                offsetOnPress = -DefaultOffset,
                backgroundColor = androidx.compose.ui.graphics.Color.Transparent
            )
            PlayButton(radius = 16.dp, shouldBePlaying = shouldBePlaying, modifier = Modifier.size(80.dp, 64.dp))
            SkipButton(
                iconId = R.drawable.play_skip_forward,
                onClick = binder.player::forceSeekToNext,
                modifier = Modifier.size(32.dp),
                backgroundColor = androidx.compose.ui.graphics.Color.Transparent
            )
            IconButton(
                icon = R.drawable.infinite,
                color = if (PlayerPreferences.trackLoopEnabled) colorPalette.text else colorPalette.textSecondary,
                onClick = { PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled },
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AppleControls(
    media: UiMedia,
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    position: Long,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    isShowingLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenSpeedDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    var shuffleEnabled by remember(binder.player) { mutableStateOf(binder.player.shuffleModeEnabled) }
    var format by remember(media.id) { mutableStateOf<Format?>(null) }

    DisposableEffect(binder.player) {
        val listener = object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleEnabled = shuffleModeEnabled
            }
        }
        binder.player.addListener(listener)
        onDispose {
            binder.player.removeListener(listener)
        }
    }

    LaunchedEffect(media.id) {
        Database
            .format(media.id)
            .distinctUntilChanged()
            .collectLatest { currentFormat ->
                if (currentFormat != null) format = currentFormat
            }
    }

    val audioQualityLabel = remember(format, media.duration) {
        formatAudioBadgeText(format, media.duration) ?: "LOSSLESS"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 26.dp)
    ) {
        Spacer(modifier = Modifier.weight(0.55f))

        // BitChord / Apple Music Header Row: Left-aligned Title & Artist + Right Circular Frosted Action Pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaInfo(
                media = media,
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            )
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppleCircularActionButton(
                    iconId = if (likedAt == null) R.drawable.heart_outline else R.drawable.heart,
                    tint = if (likedAt == null) colorPalette.text.copy(alpha = 0.88f) else colorPalette.favoritesIcon,
                    isActive = likedAt != null,
                    onClick = {
                        setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                    }
                )
                AppleCircularActionButton(
                    iconId = R.drawable.ellipsis_horizontal,
                    tint = colorPalette.text.copy(alpha = 0.88f),
                    isActive = false,
                    onClick = onOpenMenu
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.8f))

        // BitChord / Apple Music Expanding Pill Scrubber + Timestamps + Audio Quality Pill
        AppleSeekBar(
            binder = binder,
            position = position,
            media = media,
            audioBadgeText = audioQualityLabel
        )

        Spacer(modifier = Modifier.weight(0.95f))

        // BitChord / Apple Music Frameless Transport Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            AppleModeToggleButton(
                iconId = R.drawable.shuffle,
                isActive = shuffleEnabled,
                onClick = {
                    val nextState = !binder.player.shuffleModeEnabled
                    binder.player.shuffleModeEnabled = nextState
                    shuffleEnabled = nextState
                }
            )

            AppleTransportButton(
                iconId = R.drawable.play_skip_back,
                iconSize = 34.dp,
                touchSize = 52.dp,
                nudgeOffset = (-6).dp,
                onClick = binder.player::forceSeekToPrevious
            )

            ApplePlayPauseButton(
                shouldBePlaying = shouldBePlaying,
                onClick = {
                    if (shouldBePlaying) {
                        binder.player.pause()
                    } else {
                        if (binder.player.playbackState == Player.STATE_IDLE) binder.player.prepare()
                        binder.player.play()
                    }
                }
            )

            AppleTransportButton(
                iconId = R.drawable.play_skip_forward,
                iconSize = 34.dp,
                touchSize = 52.dp,
                nudgeOffset = 6.dp,
                onClick = binder.player::forceSeekToNext
            )

            AppleModeToggleButton(
                iconId = R.drawable.repeat,
                isActive = PlayerPreferences.trackLoopEnabled,
                onClick = {
                    PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
                }
            )
        }

        Spacer(modifier = Modifier.weight(0.95f))

        // BitChord / Apple Music Bottom Utility Bar (Lyrics, Audio/Speed, Up Next Queue)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            AppleBottomUtilityPill(
                iconId = R.drawable.text,
                label = "Lyrics",
                isActive = isShowingLyrics,
                onClick = onToggleLyrics
            )
            AppleBottomUtilityPill(
                iconId = R.drawable.speed,
                label = if (PlayerPreferences.speed != 1f) {
                    "${"%.2f".format(PlayerPreferences.speed).trimEnd('0').trimEnd('.')}x"
                } else {
                    "Audio"
                },
                isActive = PlayerPreferences.speed != 1f || PlayerPreferences.pitch != 1f,
                onClick = onOpenSpeedDialog
            )
            AppleBottomUtilityPill(
                iconId = R.drawable.playlist,
                label = "Up Next",
                isActive = false,
                onClick = onOpenQueue
            )
        }

        Spacer(modifier = Modifier.weight(0.35f))
    }
}

@Composable
private fun AppleCircularActionButton(
    @DrawableRes iconId: Int,
    tint: Color,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "appleActionScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                colorPalette.text.copy(
                    alpha = when {
                        pressed -> 0.24f
                        isActive -> 0.16f
                        else -> 0.10f
                    }
                )
            )
            .border(
                width = 0.8.dp,
                color = colorPalette.text.copy(alpha = if (isActive) 0.18f else 0.08f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Image(
            painter = painterResource(iconId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun AppleSeekBar(
    binder: PlayerService.Binder,
    position: Long,
    media: UiMedia,
    audioBadgeText: String
) {
    val (colorPalette, typography) = LocalAppearance.current
    val duration = media.duration.coerceAtLeast(1L)
    val range = 0L..duration

    var scrubbingPosition by remember(media.id) { mutableStateOf<Long?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    val effectivePos = (scrubbingPosition ?: position).coerceIn(range)
    val animatedFraction by animateFloatAsState(
        targetValue = if (media.duration <= 0L || media.duration == C.TIME_UNSET) 0f
        else (effectivePos.toFloat() / duration.toFloat()).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = if (isDragging) 20 else 90, easing = LinearEasing),
        label = "appleSeekFraction"
    )

    val trackHeight by animateDpAsState(
        targetValue = if (isDragging) 12.dp else 7.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "appleTrackHeight"
    )

    val timeScale by animateFloatAsState(
        targetValue = if (isDragging) 1.04f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "appleTimeScale"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .pointerInput(range) {
                    var acc = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val startPos = ((offset.x / size.width.coerceAtLeast(1)) * duration).toLong().coerceIn(range)
                            scrubbingPosition = startPos
                        },
                        onHorizontalDrag = { _, delta ->
                            acc += (delta / size.width.coerceAtLeast(1)) * duration.toFloat()
                            if (acc !in -1f..1f) {
                                val step = acc.toLong()
                                scrubbingPosition = ((scrubbingPosition ?: position) + step).coerceIn(range)
                                acc -= step
                            }
                        },
                        onDragEnd = {
                            isDragging = false
                            acc = 0f
                            scrubbingPosition?.let(binder.player::seekTo)
                            scrubbingPosition = null
                        },
                        onDragCancel = {
                            isDragging = false
                            acc = 0f
                            scrubbingPosition?.let(binder.player::seekTo)
                            scrubbingPosition = null
                        }
                    )
                }
                .pointerInput(range) {
                    detectTapGestures(
                        onTap = { offset ->
                            val tappedPos = ((offset.x / size.width.coerceAtLeast(1)) * duration).toLong().coerceIn(range)
                            binder.player.seekTo(tappedPos)
                        }
                    )
                }
        ) {
            // Track background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(50))
                    .background(colorPalette.text.copy(alpha = 0.18f))
            ) {
                // Active filled pill
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction)
                        .height(trackHeight)
                        .clip(RoundedCornerShape(50))
                        .background(
                            colorPalette.text.copy(alpha = if (isDragging) 0.98f else 0.82f)
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = timeScale
                    scaleY = timeScale
                }
        ) {
            BasicText(
                text = formatAsDuration(effectivePos),
                style = typography.xxs.semiBold.color(
                    colorPalette.text.copy(alpha = if (isDragging) 0.90f else 0.54f)
                ),
                maxLines = 1
            )

            // Center Apple Music Lossless / Codec Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colorPalette.text.copy(alpha = 0.10f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.musical_notes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.text.copy(alpha = 0.72f)),
                    modifier = Modifier.size(9.dp)
                )
                BasicText(
                    text = audioBadgeText,
                    style = typography.xxs.bold.color(colorPalette.text.copy(alpha = 0.72f)).copy(
                        fontSize = 9.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val remainingMs = (duration - effectivePos).coerceAtLeast(0L)
            BasicText(
                text = if (PlayerPreferences.showRemaining) {
                    "-${formatAsDuration(remainingMs)}"
                } else {
                    formatAsDuration(duration)
                },
                style = typography.xxs.semiBold.color(
                    colorPalette.text.copy(alpha = if (isDragging) 0.90f else 0.54f)
                ),
                maxLines = 1,
                modifier = Modifier.clickable {
                    PlayerPreferences.showRemaining = !PlayerPreferences.showRemaining
                }
            )
        }
    }
}

@Composable
private fun AppleTransportButton(
    @DrawableRes iconId: Int,
    iconSize: Dp,
    touchSize: Dp,
    nudgeOffset: Dp,
    onClick: () -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "appleTransportScale"
    )
    val offsetX by animateDpAsState(
        targetValue = if (pressed) nudgeOffset else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "appleTransportNudge"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(touchSize)
            .offset { IntOffset(x = offsetX.roundToPx(), y = 0) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(colorPalette.text.copy(alpha = if (pressed) 0.12f else 0f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Image(
            painter = painterResource(iconId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.text),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
private fun ApplePlayPauseButton(
    shouldBePlaying: Boolean,
    onClick: () -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "applePlayScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(68.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(colorPalette.text.copy(alpha = if (pressed) 0.14f else 0f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        AnimatedPlayPauseButton(
            playing = shouldBePlaying,
            modifier = Modifier.size(44.dp)
        )
    }
}

@Composable
private fun AppleModeToggleButton(
    @DrawableRes iconId: Int,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val (colorPalette) = LocalAppearance.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "appleModeToggleScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(
                colorPalette.text.copy(
                    alpha = when {
                        pressed -> 0.22f
                        isActive -> 0.14f
                        else -> 0f
                    }
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Image(
            painter = painterResource(iconId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                if (isActive) colorPalette.text else colorPalette.text.copy(alpha = 0.52f)
            ),
            modifier = Modifier.size(20.dp)
        )
        if (isActive) {
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(colorPalette.accent)
            )
        }
    }
}

@Composable
private fun AppleBottomUtilityPill(
    @DrawableRes iconId: Int,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "appleUtilityScale"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(50))
            .background(
                colorPalette.text.copy(
                    alpha = when {
                        pressed -> 0.24f
                        isActive -> 0.18f
                        else -> 0.08f
                    }
                )
            )
            .border(
                width = 0.8.dp,
                color = colorPalette.text.copy(alpha = if (isActive) 0.22f else 0.06f),
                shape = RoundedCornerShape(50)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Image(
            painter = painterResource(iconId),
            contentDescription = label,
            colorFilter = ColorFilter.tint(
                if (isActive) colorPalette.text else colorPalette.text.copy(alpha = 0.72f)
            ),
            modifier = Modifier.size(15.dp)
        )
        BasicText(
            text = label,
            style = typography.xxs.semiBold.color(
                if (isActive) colorPalette.text else colorPalette.text.copy(alpha = 0.72f)
            )
        )
    }
}

@Composable
private fun SkipButton(
    @DrawableRes iconId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    offsetOnPress: Dp = DefaultOffset,
    backgroundColor: androidx.compose.ui.graphics.Color = LocalAppearance.current.colorPalette.background2
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val offset by animateDpAsState(
        targetValue = if (pressed) offsetOnPress else 0.dp,
        label = ""
    )

    BigIconButton(
        iconId = iconId,
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .offset {
                IntOffset(x = offset.roundToPx(), y = 0)
            },
        backgroundColor = backgroundColor
    )
}

@Composable
private fun PlayButton(
    radius: Dp,
    shouldBePlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val (colorPalette) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current

    Box(
        modifier = modifier
            .clip(radius.roundedShape)
            .clickable {
                if (shouldBePlaying) binder?.player?.pause() else {
                    if (binder?.player?.playbackState == Player.STATE_IDLE) binder.player.prepare()
                    binder?.player?.play()
                }
            }
            .background(colorPalette.accent)
    ) {
        AnimatedPlayPauseButton(
            playing = shouldBePlaying,
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp)
        )
    }
}

private fun formatAudioBadgeText(format: Format?, durationMs: Long): String? {
    if (format == null) return null
    val codec = format.mimeType?.let { mime ->
        when {
            mime.contains("opus", ignoreCase = true) -> "OPUS"
            mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true) -> "AAC"
            mime.contains("flac", ignoreCase = true) -> "FLAC"
            mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true) -> "MP3"
            mime.contains("vorbis", ignoreCase = true) -> "VORBIS"
            mime.contains("webm", ignoreCase = true) -> "WEBM"
            mime.contains("mp4", ignoreCase = true) -> "M4A"
            else -> mime.substringAfter("/").substringBefore(";").uppercase()
        }
    }

    val kbps = when {
        format.contentLength != null && format.contentLength > 0 && durationMs > 0 -> {
            val seconds = durationMs / 1000.0
            val kilobits = (format.contentLength * 8) / 1000.0
            (kilobits / seconds).toLong().takeIf { it > 0 }
        }
        format.bitrate != null && format.bitrate > 0 -> (format.bitrate / 1000L).takeIf { it > 0 }
        else -> null
    }

    val parts = buildList {
        if (!codec.isNullOrBlank()) add(codec)
        if (kbps != null) add("$kbps kbps")
        if (format.itag != null) add("itag ${format.itag}")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private val artistSplitRegex = Regex("\\s*(?:,|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)

private fun parseInitialArtistInfos(rawArtistText: String?): List<Info> {
    val clean = rawArtistText?.trim().orEmpty()
    if (clean.isBlank()) return emptyList()
    val parts = clean.split(artistSplitRegex).map { it.trim() }.filter { it.isNotBlank() }
    if (parts.isEmpty()) return listOf(Info(id = "name:$clean", name = clean))
    val seen = HashSet<String>()
    return parts.mapNotNull { part ->
        if (seen.add(part.lowercase())) Info(id = "name:$part", name = part) else null
    }
}

@Composable
private fun MediaInfo(
    media: UiMedia,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally
) {
    val (colorPalette, typography) = LocalAppearance.current

    var artistInfo: List<Info>? by remember(media.id, media.artist) {
        mutableStateOf(parseInitialArtistInfos(media.artist).takeIf { it.isNotEmpty() })
    }
    var format by remember(media.id) { mutableStateOf<Format?>(null) }
    var maxHeight by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(media.id, media.artist) {
        withContext(Dispatchers.IO) {
            val dbArtists = Database
                .songArtistInfo(media.id)
                .filter { info ->
                    val n = info.name?.trim().orEmpty()
                    n.isNotBlank() && !(':' in n && n.all { it.isDigit() || it == ':' }) && !info.id.startsWith("MPREb_")
                }

            val rawCandidates = dbArtists.takeIf { it.isNotEmpty() }
                ?: parseInitialArtistInfos(media.artist)

            if (rawCandidates.isNotEmpty()) {
                val expanded = mutableListOf<Info>()
                val seenNames = HashSet<String>()

                for (info in rawCandidates) {
                    val rawName = info.name?.trim().orEmpty()
                    if (rawName.isBlank()) continue
                    val parts = rawName.split(artistSplitRegex).map { it.trim() }.filter { it.isNotBlank() }
                    if (parts.size <= 1) {
                        if (seenNames.add(rawName.lowercase())) {
                            val sameName = runCatching { Database.artistsByNameExact(rawName) }.getOrDefault(emptyList())
                            val existingMatch = sameName.firstOrNull { it.id.startsWith("UC") }
                                ?: sameName.firstOrNull { it.bookmarkedAt != null }
                                ?: sameName.firstOrNull()
                            val resolvedId = when {
                                info.id.startsWith("UC") -> info.id
                                existingMatch != null -> existingMatch.id
                                info.id.isNotBlank() -> info.id
                                else -> "name:$rawName"
                            }
                            expanded.add(
                                Info(
                                    id = resolvedId,
                                    name = existingMatch?.name?.takeIf { it.isNotBlank() } ?: rawName
                                )
                            )
                        }
                    } else {
                        for ((idx, partName) in parts.withIndex()) {
                            if (!seenNames.add(partName.lowercase())) continue
                            val sameName = runCatching { Database.artistsByNameExact(partName) }.getOrDefault(emptyList())
                            val existingMatch = sameName.firstOrNull { it.id.startsWith("UC") }
                                ?: sameName.firstOrNull { it.bookmarkedAt != null }
                                ?: sameName.firstOrNull()
                            val resolvedId = existingMatch?.id
                                ?: if (idx == 0 && info.id.startsWith("UC")) info.id else "name:$partName"
                            expanded.add(
                                Info(
                                    id = resolvedId,
                                    name = existingMatch?.name?.takeIf { it.isNotBlank() } ?: partName
                                )
                            )
                        }
                    }
                }
                if (expanded.isNotEmpty()) {
                    artistInfo = expanded
                }
            }
        }
    }

    LaunchedEffect(media.id, PlayerPreferences.showItag) {
        if (!PlayerPreferences.showItag) return@LaunchedEffect
        Database
            .format(media.id)
            .distinctUntilChanged()
            .collectLatest { currentFormat ->
                if (currentFormat != null) format = currentFormat
            }
    }

    Column(horizontalAlignment = horizontalAlignment, modifier = modifier) {
        AnimatedContent(
            targetState = media.title,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = ""
        ) { title ->
            FadingRow(modifier = Modifier.fillMaxWidth(if (horizontalAlignment == Alignment.CenterHorizontally) 0.85f else 1f)) {
                BasicText(
                    text = title,
                    style = typography.l.bold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        AnimatedContent(
            targetState = media to artistInfo,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = ""
        ) { (media, state) ->
            val widthFraction = if (horizontalAlignment == Alignment.CenterHorizontally) 0.85f else 1f
            state?.takeIf { it.isNotEmpty() }?.let { artists ->
                FadingRow(
                    modifier = Modifier
                        .fillMaxWidth(widthFraction)
                        .let { mod -> if (maxHeight > 0) mod.heightIn(maxHeight.px.dp) else mod },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    artists.fastForEachIndexed { i, artist ->
                        if (i == artists.lastIndex && artists.size > 1) BasicText(
                            text = " & ",
                            style = typography.s.semiBold.secondary
                        )
                        BasicText(
                            text = artist.name.orEmpty(),
                            style = typography.s.bold.secondary,
                            modifier = Modifier
                                .clickable {
                                    val targetId = artist.id.ifBlank { "name:${artist.name.orEmpty()}" }
                                    if (targetId.isNotBlank()) artistRoute.global(targetId)
                                }
                                .padding(vertical = 2.dp)
                        )
                        if (i != artists.lastIndex && i + 1 != artists.lastIndex) BasicText(
                            text = ", ",
                            style = typography.s.semiBold.secondary
                        )
                    }
                    if (media.explicit) {
                        Spacer(Modifier.width(4.dp))

                        Image(
                            painter = painterResource(R.drawable.explicit),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.text),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            } ?: FadingRow(
                modifier = Modifier.fillMaxWidth(widthFraction),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = media.artist,
                    style = typography.s.semiBold.secondary,
                    maxLines = 1,
                    modifier = Modifier
                        .onGloballyPositioned { maxHeight = it.size.height }
                        .clickable(enabled = media.artist.isNotBlank()) {
                            artistRoute.global("name:${media.artist.trim()}")
                        }
                        .padding(vertical = 2.dp)
                )
                if (media.explicit) {
                    Spacer(Modifier.width(4.dp))

                    Image(
                        painter = painterResource(R.drawable.explicit),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.text),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        val badgeText = remember(format, media.duration) {
            formatAudioBadgeText(format, media.duration)
        }

        AnimatedVisibility(
            visible = PlayerPreferences.showItag && !badgeText.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colorPalette.background2.copy(alpha = 0.72f))
                    .border(
                        width = 1.dp,
                        color = colorPalette.accent.copy(alpha = 0.32f),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                BasicText(
                    text = badgeText.orEmpty(),
                    style = typography.xxs.medium.color(colorPalette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
