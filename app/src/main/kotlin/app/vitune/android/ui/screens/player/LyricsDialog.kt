package app.vitune.android.ui.screens.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.ui.modifiers.PinchDirection
import app.vitune.android.ui.modifiers.onSwipe
import app.vitune.android.ui.modifiers.pinchToToggle
import app.vitune.android.utils.FullScreenState
import app.vitune.android.utils.collectProvidedBitmapAsState
import app.vitune.android.utils.color
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.highQualityThumbnail
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.windowState
import app.vitune.core.ui.ColorSource
import app.vitune.core.ui.Darkness
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.colorPaletteOf
import coil3.compose.AsyncImage
import coil3.toBitmap
import kotlin.math.roundToInt

@Composable
fun LyricsDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) = Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    )
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    FullScreenState(shown = PlayerPreferences.lyricsShowSystemBars)

    val appearance = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val player = binder?.player ?: return@Dialog
    val (window, error) = windowState()

    LaunchedEffect(window, error) {
        if (window == null || error != null) currentOnDismiss()
    }

    window ?: return@Dialog

    val sampleBitmap = binder.collectProvidedBitmapAsState()
    var dialogLoadedBitmap by remember(window.mediaItem.mediaId) { mutableStateOf<Bitmap?>(null) }
    val activeBitmap = sampleBitmap ?: dialogLoadedBitmap

    val dynamicPalette = remember(activeBitmap, appearance.colorPalette) {
        if (activeBitmap != null) {
            colorPaletteOf(
                source = ColorSource.Dynamic,
                darkness = Darkness.Normal,
                isDark = true,
                materialAccentColor = null,
                sampleBitmap = activeBitmap
            )
        } else {
            appearance.colorPalette
        }
    }

    val animatedBg0 by animateColorAsState(
        targetValue = dynamicPalette.background0,
        animationSpec = tween(600),
        label = "lyricsDialogBg0"
    )
    val animatedBg1 by animateColorAsState(
        targetValue = dynamicPalette.background1,
        animationSpec = tween(600),
        label = "lyricsDialogBg1"
    )
    val animatedBg2 by animateColorAsState(
        targetValue = dynamicPalette.background2,
        animationSpec = tween(600),
        label = "lyricsDialogBg2"
    )

    CompositionLocalProvider(
        LocalAppearance provides appearance.copy(colorPalette = dynamicPalette)
    ) {
        val (colorPalette, typography) = LocalAppearance.current

        AnimatedContent(
            targetState = window,
            transitionSpec = {
                if (initialState.mediaItem.mediaId == targetState.mediaItem.mediaId)
                    return@AnimatedContent ContentTransform(
                        targetContentEnter = EnterTransition.None,
                        initialContentExit = ExitTransition.None
                    )

                val direction = if (targetState.firstPeriodIndex > initialState.firstPeriodIndex)
                    AnimatedContentTransitionScope.SlideDirection.Left
                else AnimatedContentTransitionScope.SlideDirection.Right

                ContentTransform(
                    targetContentEnter = slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(500)
                    ),
                    initialContentExit = slideOutOfContainer(
                        towards = direction,
                        animationSpec = tween(500)
                    ),
                    sizeTransform = null
                )
            },
            label = "FullScreenLyricsTransition"
        ) { currentWindow ->
            val blurredBitmap = remember(activeBitmap) {
                activeBitmap?.let { createSoftBlurredImageBitmap(it) }
            }

            BoxWithConstraints(
                modifier = modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to animatedBg1,
                            0.45f to animatedBg2,
                            1.0f to animatedBg0
                        )
                    )
                    .pinchToToggle(
                        direction = PinchDirection.In,
                        threshold = 0.9f,
                        onPinch = { onDismiss() }
                    )
                    .onSwipe(
                        onSwipeLeft = {
                            player.forceSeekToNext()
                        },
                        onSwipeRight = {
                            player.seekToDefaultPosition()
                            player.forceSeekToPrevious()
                        }
                    )
            ) {
                if (currentWindow.mediaItem.mediaMetadata.artworkUri != null) {
                    AsyncImage(
                        model = currentWindow.mediaItem.mediaMetadata.artworkUri.highQualityThumbnail(),
                        onSuccess = { state ->
                            if (dialogLoadedBitmap == null) {
                                dialogLoadedBitmap = runCatching {
                                    state.result.image.run { toBitmap(width, height) }.ensureSoftwareBitmap()
                                }.getOrNull()
                            }
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    color = animatedBg0.copy(alpha = 0.86f)
                                )
                                if (blurredBitmap != null) {
                                    val bw = blurredBitmap.width.toFloat().coerceAtLeast(1f)
                                    val bh = blurredBitmap.height.toFloat().coerceAtLeast(1f)
                                    val scaleFactor = maxOf(size.width / bw, size.height / bh)
                                    val dstW = (bw * scaleFactor).roundToInt().coerceAtLeast(1)
                                    val dstH = (bh * scaleFactor).roundToInt().coerceAtLeast(1)
                                    val offsetX = ((size.width - dstW) / 2f).roundToInt()
                                    val offsetY = ((size.height - dstH) / 2f).roundToInt()
                                    drawImage(
                                        image = blurredBitmap,
                                        dstOffset = IntOffset(offsetX, offsetY),
                                        dstSize = IntSize(dstW, dstH),
                                        alpha = 0.96f
                                    )
                                }
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to animatedBg0.copy(alpha = 0.68f),
                                        0.5f to Color.Black.copy(alpha = 0.54f),
                                        1.0f to animatedBg0.copy(alpha = 0.78f)
                                    )
                                )
                            }
                            .blur(
                                radius = 38.dp,
                                edgeTreatment = BlurredEdgeTreatment.Rectangle
                            )
                    )
                }

                val topPadding = WindowInsets.systemBars
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    .asPaddingValues()
                val bottomPadding = WindowInsets.systemBars
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    .asPaddingValues()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottomPadding)
                ) {
                    // Full-screen lyrics header with song artwork, title/artist, and collapse button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(topPadding)
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        AsyncImage(
                            model = currentWindow.mediaItem.mediaMetadata.artworkUri.highQualityThumbnail(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorPalette.background2)
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            BasicText(
                                text = currentWindow.mediaItem.mediaMetadata.title?.toString().orEmpty(),
                                style = typography.s.semiBold.color(Color.White),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val artistName = currentWindow.mediaItem.mediaMetadata.artist?.toString().orEmpty()
                            if (artistName.isNotBlank()) {
                                BasicText(
                                    text = artistName,
                                    style = typography.xs.semiBold.color(Color.White.copy(alpha = 0.72f)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            icon = R.drawable.chevron_down,
                            color = Color.White,
                            onClick = onDismiss,
                            modifier = Modifier
                                .background(
                                    color = Color.White.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(50)
                                )
                                .padding(8.dp)
                                .size(20.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Lyrics(
                            mediaId = currentWindow.mediaItem.mediaId,
                            isDisplayed = true,
                            onDismiss = { },
                            mediaMetadataProvider = currentWindow.mediaItem::mediaMetadata,
                            durationProvider = player::getDuration,
                            ensureSongInserted = { Database.insert(currentWindow.mediaItem) },
                            onMenuLaunch = onDismiss,
                            modifier = Modifier.fillMaxSize(),
                            shouldKeepScreenAwake = false, // otherwise the keepScreenOn flag resets after dialog closes
                            shouldUpdateLyrics = false,
                            isFullScreenLyricsDialog = true
                        )
                    }
                }
            }
        }
    }
}

