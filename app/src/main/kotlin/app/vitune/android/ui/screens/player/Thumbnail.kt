package app.vitune.android.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Left
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Companion.Right
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.LoginRequiredException
import app.vitune.android.service.PlayableFormatNotFoundException
import app.vitune.android.service.RestrictedVideoException
import app.vitune.android.service.UnplayableException
import app.vitune.android.service.VideoIdMismatchException
import app.vitune.android.service.isLocal
import app.vitune.android.ui.modifiers.onSwipe
import android.graphics.Bitmap
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.vitune.android.utils.collectProvidedBitmapAsState
import app.vitune.android.utils.findCause
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.highQualityThumbnail
import app.vitune.android.utils.isInPip
import app.vitune.android.utils.windowState
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.px
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.math.roundToInt

import android.graphics.Matrix
import android.os.Build
import android.view.TextureView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.vitune.android.preferences.DataPreferences
import app.vitune.providers.spotify.requests.SpotifyApi
import android.graphics.SurfaceTexture
import androidx.compose.runtime.collectAsState
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

internal fun Bitmap.ensureSoftwareBitmap(): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
        runCatching { copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: this
    } else {
        this
    }
}

private val blurredBitmapCache = android.util.LruCache<Int, ImageBitmap>(12)

internal fun createSoftBlurredImageBitmap(source: Bitmap, targetSize: Int = 48, radius: Int = 5): ImageBitmap? =
    runCatching {
        val cacheKey = System.identityHashCode(source) * 31 + source.generationId
        blurredBitmapCache.get(cacheKey)?.let { return it }

        val softSource = source.ensureSoftwareBitmap()
        val sw = softSource.width.coerceAtLeast(1)
        val sh = softSource.height.coerceAtLeast(1)
        val w = targetSize
        val h = ((sh.toFloat() / sw.toFloat()) * targetSize).roundToInt().coerceIn(16, targetSize * 2)
        val scaled = Bitmap.createScaledBitmap(softSource, w, h, true).ensureSoftwareBitmap()
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        repeat(3) {
            // Horizontal pass
            for (y in 0 until h) {
                val rowOffset = y * w
                for (x in 0 until w) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dx in -radius..radius) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val c = pixels[rowOffset + nx]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                    temp[rowOffset + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                }
            }
            // Vertical pass
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dy in -radius..radius) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        val c = temp[ny * w + x]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        count++
                    }
                    pixels[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        out.asImageBitmap().also { img ->
            blurredBitmapCache.put(cacheKey, img)
        }
    }.getOrNull()

@Composable
fun Thumbnail(
    isShowingLyrics: Boolean,
    onShowLyrics: (Boolean) -> Unit,
    isShowingStatsForNerds: Boolean,
    onShowStatsForNerds: (Boolean) -> Unit,
    onOpenDialog: () -> Unit,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    isFullScreen: Boolean = PlayerPreferences.fullScreenPlayer,
    animatedGradientEnabled: Boolean = PlayerPreferences.playerAnimatedGradient,
    animatedArtworkEnabled: Boolean = PlayerPreferences.animatedArtworkEnabled,
    spotifyCanvasEnabled: Boolean = PlayerPreferences.spotifyCanvasEnabled,
    shouldBePlaying: Boolean = true,
    onBitmapLoaded: ((Bitmap) -> Unit)? = null,
    shouldShowSynchronizedLyrics: Boolean = PlayerPreferences.isShowingSynchronizedLyrics,
    setShouldShowSynchronizedLyrics: (Boolean) -> Unit = {
        PlayerPreferences.isShowingSynchronizedLyrics = it
    },
    showLyricsControls: Boolean = true
) {
    val binder = LocalPlayerServiceBinder.current
    val (colorPalette, _, thumbnailCornerSize) = LocalAppearance.current

    val (window, error) = windowState()
    val sampleBitmap = binder.collectProvidedBitmapAsState()
    var loadedImageBitmap by remember(window?.mediaItem?.mediaId) { mutableStateOf<Bitmap?>(null) }
    val activeBitmap = loadedImageBitmap ?: sampleBitmap
    var blurredBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(activeBitmap) {
        if (activeBitmap != null) {
            onBitmapLoaded?.invoke(activeBitmap)
            blurredBitmap = withContext(Dispatchers.Default) {
                createSoftBlurredImageBitmap(activeBitmap)
            }
        } else {
            blurredBitmap = null
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val transitionState = remember { SeekableTransitionState(false) }
    val transition = rememberTransition(transitionState)
    val opacity by transition.animateFloat(label = "") { if (it) 1f else 0f }
    val scale by transition.animateFloat(
        label = "",
        transitionSpec = {
            spring(dampingRatio = Spring.DampingRatioLowBouncy)
        }
    ) { if (it) 1f else 0f }
    val isInPip = isInPip()
    val isAppleLayout = PlayerPreferences.playerLayout == PlayerPreferences.PlayerLayout.Apple && !isFullScreen && !isInPip

    val animatedCornerRadius by animateDpAsState(
        targetValue = when {
            isInPip || isFullScreen -> 0.dp
            isAppleLayout -> maxOf(thumbnailCornerSize, 18.dp)
            else -> thumbnailCornerSize
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "thumbnailCornerRadius"
    )

    val fullScreenFadeAlpha by animateFloatAsState(
        targetValue = if (isFullScreen) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "fullScreenFadeAlpha"
    )

    AnimatedContent(
        targetState = window,
        transitionSpec = {
            val duration = 500
            val initial = initialState
            val target = targetState

            if (initial == null || target == null) return@AnimatedContent ContentTransform(
                targetContentEnter = fadeIn(tween(duration)),
                initialContentExit = fadeOut(tween(duration)),
                sizeTransform = null
            )

            val sizeTransform = SizeTransform(clip = false) { _, _ ->
                tween(durationMillis = duration, delayMillis = duration)
            }

            val direction = if (target.firstPeriodIndex < initial.firstPeriodIndex) Right else Left

            ContentTransform(
                targetContentEnter = slideIntoContainer(direction, tween(duration)) +
                    fadeIn(tween(duration)) +
                    scaleIn(tween(duration), 0.85f),
                initialContentExit = slideOutOfContainer(direction, tween(duration)) +
                    fadeOut(tween(duration)) +
                    scaleOut(tween(duration), 0.85f),
                sizeTransform = sizeTransform
            )
        },
        modifier = modifier.onSwipe(
            onSwipeLeft = {
                binder?.player?.forceSeekToNext()
            },
            onSwipeRight = {
                binder?.player?.forceSeekToPrevious(seekToStart = false)
            }
        ),
        contentAlignment = Alignment.Center,
        label = ""
    ) { currentWindow ->
        val isOverlayActive = isShowingLyrics || error != null || isShowingStatsForNerds
        val shadowElevation by animateDpAsState(
            targetValue = when {
                window != currentWindow || isFullScreen -> 0.dp
                isAppleLayout -> if (shouldBePlaying || isOverlayActive) 22.dp else 8.dp
                else -> 8.dp
            },
            animationSpec = tween(
                durationMillis = 420,
                easing = LinearEasing
            ),
            label = ""
        )
        val appleArtworkScale by animateFloatAsState(
            targetValue = if (isAppleLayout && !isOverlayActive && !shouldBePlaying) 0.85f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "appleArtworkScale"
        )
        val blurOverlayAlpha by animateFloatAsState(
            targetValue = if (isOverlayActive) 1f else 0f,
            animationSpec = tween(380),
            label = "blurOverlayAlpha"
        )

        val currentShape = if (isInPip || isFullScreen) {
            RectangleShape
        } else {
            RoundedCornerShape(animatedCornerRadius)
        }

        val anyVideoArtEnabled = animatedArtworkEnabled || spotifyCanvasEnabled
        val currentMediaId = currentWindow?.mediaItem?.mediaId
        val currentTitle = currentWindow?.mediaItem?.mediaMetadata?.title?.toString().orEmpty()
        val currentArtist = currentWindow?.mediaItem?.mediaMetadata?.artist?.toString().orEmpty()
        val dbAlbumTitle by remember(currentMediaId) {
            if (currentMediaId != null) {
                Database.songAlbumTitle(currentMediaId).distinctUntilChanged()
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }.collectAsState(initial = null)
        val currentAlbum = currentWindow?.mediaItem?.mediaMetadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: dbAlbumTitle
        var canvasVideoUrl by remember(
            currentMediaId,
            animatedArtworkEnabled,
            spotifyCanvasEnabled
        ) {
            mutableStateOf<String?>(null)
        }
        LaunchedEffect(
            currentMediaId,
            currentTitle,
            currentArtist,
            currentAlbum,
            animatedArtworkEnabled,
            spotifyCanvasEnabled
        ) {
            if (!anyVideoArtEnabled || currentWindow == null) {
                canvasVideoUrl = null
                return@LaunchedEffect
            }
            if (currentTitle.isNotBlank() && currentArtist.isNotBlank()) {
                canvasVideoUrl = withContext(Dispatchers.IO) {
                    runCatching {
                        val validSpotifyToken = if (spotifyCanvasEnabled && app.vitune.android.utils.SpotifySyncManager.isLoggedIn()) {
                            runCatching { app.vitune.android.utils.SpotifySyncManager.getValidAccessToken() }.getOrNull()
                        } else {
                            DataPreferences.spotifyAccessToken.takeIf { it.isNotBlank() }
                        }
                        SpotifyApi.getTrackCanvasUrl(
                            title = currentTitle,
                            artist = currentArtist,
                            spDcCookie = DataPreferences.spotifySpDcCookie.takeIf { it.isNotBlank() },
                            cachedAccessToken = validSpotifyToken,
                            album = currentAlbum,
                            preferTall = false,
                            enableAnimatedArtwork = animatedArtworkEnabled,
                            enableSpotifyCanvas = spotifyCanvasEnabled
                        )
                    }.getOrNull()
                }
            } else {
                canvasVideoUrl = null
            }
        }

        if (currentWindow != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .let {
                    if (isFullScreen) it.fillMaxSize() else it.fillMaxWidth()
                }
                .graphicsLayer {
                    scaleX = appleArtworkScale
                    scaleY = appleArtworkScale
                }
                .shadow(
                    elevation = shadowElevation,
                    shape = currentShape,
                    clip = false
                )
                .clip(currentShape)
        ) {
            var height by remember { mutableIntStateOf(0) }

            val animatedFadeBg by androidx.compose.animation.animateColorAsState(
                targetValue = colorPalette.background1,
                animationSpec = tween(durationMillis = 600),
                label = "thumbnailFadeBg"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .let {
                        if (isFullScreen) {
                            it.fillMaxSize()
                        } else {
                            it
                                .fillMaxWidth()
                                .background(colorPalette.background0)
                        }
                    }
                    .clip(currentShape)
                    .graphicsLayer {
                        compositingStrategy = if (!anyVideoArtEnabled && (fullScreenFadeAlpha > 0.01f || blurOverlayAlpha > 0.01f) && canvasVideoUrl.isNullOrBlank()) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        if (blurOverlayAlpha > 0.01f) {
                            drawRect(
                                color = colorPalette.background0.copy(alpha = 0.85f * blurOverlayAlpha)
                            )
                            val currentBlurred = blurredBitmap
                            if (currentBlurred != null) {
                                // Center-crop the soft-blurred bitmap so it matches ContentScale.Crop / FillWidth
                                val bw = currentBlurred.width.toFloat().coerceAtLeast(1f)
                                val bh = currentBlurred.height.toFloat().coerceAtLeast(1f)
                                val scaleFactor = maxOf(size.width / bw, size.height / bh)
                                val dstW = (bw * scaleFactor).roundToInt().coerceAtLeast(1)
                                val dstH = (bh * scaleFactor).roundToInt().coerceAtLeast(1)
                                val offsetX = ((size.width - dstW) / 2f).roundToInt()
                                val offsetY = ((size.height - dstH) / 2f).roundToInt()
                                drawImage(
                                    image = currentBlurred,
                                    dstOffset = IntOffset(offsetX, offsetY),
                                    dstSize = IntSize(dstW, dstH),
                                    alpha = blurOverlayAlpha
                                )
                            }
                            // Darken the blurred artwork so white lyrics remain crisp on bright album covers
                            drawRect(
                                color = Color.Black.copy(alpha = 0.54f * blurOverlayAlpha)
                            )
                        }
                        if (fullScreenFadeAlpha > 0.01f && size.height > 1f) {
                            if (!anyVideoArtEnabled && canvasVideoUrl.isNullOrBlank()) {
                                // Smooth multi-stop alpha mask so the bottom of the artwork (and its blur/scrim)
                                // dissolves seamlessly into the player background without any hard horizontal seam
                                val endAlpha = 1f - fullScreenFadeAlpha
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to Color.Black,
                                        0.36f to Color.Black,
                                        0.54f to Color.Black.copy(alpha = 0.86f + 0.14f * endAlpha),
                                        0.70f to Color.Black.copy(alpha = 0.54f + 0.46f * endAlpha),
                                        0.84f to Color.Black.copy(alpha = 0.22f + 0.78f * endAlpha),
                                        0.94f to Color.Black.copy(alpha = 0.05f + 0.95f * endAlpha),
                                        1.0f to Color.Black.copy(alpha = endAlpha)
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            } else {
                                // 24-stop smootherstep dissolve into animatedFadeBg (colorPalette.background1)
                                // matching Player.kt's exact background color at the Thumbnail/Controls seam
                                val stops = 24
                                val colorStops = Array(stops + 1) { i ->
                                    val t = i / stops.toFloat()
                                    val u = t.coerceIn(0f, 1f)
                                    val smooth = u * u * u * (u * (u * 6f - 15f) + 10f)
                                    t to animatedFadeBg.copy(alpha = (fullScreenFadeAlpha * smooth).coerceIn(0f, 1f))
                                }
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = colorStops,
                                        startY = size.height * 0.48f,
                                        endY = size.height
                                    )
                                )
                            }
                        }
                    }
                    .animateContentSize()
                    .onGloballyPositioned { coords ->
                        coords.size.height.let { if (it > 0) height = it }
                    }
            ) {
                AsyncImage(
                    model = currentWindow.mediaItem.mediaMetadata.artworkUri
                        ?.highQualityThumbnail(),
                    placeholder = painterResource(id = R.drawable.ic_launcher_foreground),
                    error = painterResource(id = R.drawable.ic_launcher_foreground),
                    onSuccess = { state ->
                        val bmp = runCatching {
                            state.result.image.run { toBitmap(width, height) }.ensureSoftwareBitmap()
                        }.getOrNull()
                        if (bmp != null) {
                            if (currentWindow == window) {
                                loadedImageBitmap = bmp
                                onBitmapLoaded?.invoke(bmp)
                            } else if (loadedImageBitmap == null) {
                                loadedImageBitmap = bmp
                            }
                        }
                    },
                    contentDescription = null,
                    contentScale = if (isFullScreen || anyVideoArtEnabled) ContentScale.Crop else contentScale,
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onShowLyrics(true) },
                                onLongPress = { onShowStatsForNerds(true) },
                                onDoubleTap = {
                                    if (likedAt == null) setLikedAt(System.currentTimeMillis())

                                    coroutineScope.launch {
                                        val spec = tween<Float>(durationMillis = 500)
                                        transitionState.animateTo(true, spec)
                                        transitionState.animateTo(false, spec)
                                    }
                                }
                            )
                        }
                        .let {
                            if (isFullScreen) it.fillMaxSize() else it.fillMaxWidth().aspectRatio(1f)
                        }
                )

                val activeCanvasUrl = canvasVideoUrl
                if (anyVideoArtEnabled && !activeCanvasUrl.isNullOrBlank()) {
                    SpotifyCanvasVideoOverlay(
                        videoUrl = activeCanvasUrl,
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onShowLyrics(true) },
                                    onLongPress = { onShowStatsForNerds(true) },
                                    onDoubleTap = {
                                        if (likedAt == null) setLikedAt(System.currentTimeMillis())
                                        coroutineScope.launch {
                                            val spec = tween<Float>(durationMillis = 500)
                                            transitionState.animateTo(true, spec)
                                            transitionState.animateTo(false, spec)
                                        }
                                    }
                                )
                            }
                    )
                }
            }

            Lyrics(
                mediaId = currentWindow.mediaItem.mediaId,
                isDisplayed = isShowingLyrics && error == null,
                onDismiss = { onShowLyrics(false) },
                ensureSongInserted = { Database.insert(currentWindow.mediaItem) },
                mediaMetadataProvider = currentWindow.mediaItem::mediaMetadata,
                durationProvider = { binder?.player?.duration ?: C.TIME_UNSET },
                onOpenDialog = onOpenDialog,
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(height.px.dp)
                    .clip(currentShape),
                shouldShowSynchronizedLyrics = shouldShowSynchronizedLyrics,
                setShouldShowSynchronizedLyrics = setShouldShowSynchronizedLyrics,
                showControls = showLyricsControls
            )

            StatsForNerds(
                mediaId = currentWindow.mediaItem.mediaId,
                isDisplayed = isShowingStatsForNerds && error == null,
                onDismiss = { onShowStatsForNerds(false) },
                modifier = Modifier.height(height.px.dp)
            )

            Image(
                painter = painterResource(R.drawable.heart),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.accent),
                modifier = Modifier
                    .fillMaxSize(0.5f)
                    .aspectRatio(1f)
                    .align(Alignment.Center)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        alpha = opacity,
                        shadowElevation = 8.dp.px.toFloat()
                    )
            )

            PlaybackError(
                isDisplayed = error != null,
                messageProvider = {
                    if (currentWindow.mediaItem.isLocal) stringResource(R.string.error_local_music_deleted)
                    else when {
                        error?.findCause<UnresolvedAddressException>() != null || error?.findCause<UnknownHostException>() != null ->
                            stringResource(R.string.error_network)

                        error?.findCause<PlayableFormatNotFoundException>() != null -> stringResource(R.string.error_unplayable)
                        error?.findCause<UnplayableException>() != null -> stringResource(R.string.error_source_deleted)
                        error?.findCause<LoginRequiredException>() != null || error?.findCause<RestrictedVideoException>() != null ->
                            stringResource(R.string.error_server_restrictions)

                        error?.findCause<VideoIdMismatchException>() != null -> stringResource(R.string.error_id_mismatch)
                        else -> stringResource(R.string.error_unknown_playback)
                    }
                },
                onDismiss = { binder?.player?.prepare() },
                modifier = Modifier.height(height.px.dp)
            )
        }
    }
}

@Composable
internal fun SpotifyCanvasVideoOverlay(
    videoUrl: String,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true
) {
    val context = LocalContext.current
    var isVideoReady by remember(videoUrl) { mutableStateOf(false) }
    var activeTextureView by remember(videoUrl) { mutableStateOf<TextureView?>(null) }
    var activePlayer by remember(videoUrl) { mutableStateOf<ExoPlayer?>(null) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "canvasVideoAlpha"
    )

    fun applyCenterCropTransform(textureView: TextureView, videoSize: VideoSize) {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        val vWidth = videoSize.width.takeIf { it > 0 }?.toFloat() ?: 768f
        val vHeight = videoSize.height.takeIf { it > 0 }?.toFloat() ?: 768f
        if (vw > 0f && vh > 0f) {
            val scaleX = vw / vWidth
            val scaleY = vh / vHeight
            val maxScale = maxOf(scaleX, scaleY)
            val matrix = Matrix()
            matrix.setScale(maxScale / scaleX, maxScale / scaleY, vw / 2f, vh / 2f)
            textureView.setTransform(matrix)
        }
    }

    DisposableEffect(videoUrl) {
        isVideoReady = false
        val isHls = videoUrl.contains(".m3u8", ignoreCase = true)
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(
                if (isHls) {
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
                }
            )
            .setDefaultRequestProperties(
                if (isHls) {
                    mapOf(
                        "Origin" to "https://music.apple.com",
                        "Referer" to "https://music.apple.com/"
                    )
                } else {
                    emptyMap()
                }
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val mediaItem = ExoMediaItem.Builder()
            .setUri(videoUrl)
            .apply {
                if (isHls) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setFragmentedMp4ExtractorFlags(
                androidx.media3.extractor.mp4.FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_TFDT_BOX
            )

        val mediaSource = if (isHls) {
            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        } else {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                httpDataSourceFactory,
                extractorsFactory
            ).createMediaSource(mediaItem)
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
            }

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isVideoReady = true
                activeTextureView?.let { applyCenterCropTransform(it, player.videoSize) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                activeTextureView?.let { applyCenterCropTransform(it, videoSize) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0L)
                    player.playWhenReady = true
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isVideoReady = false
                player.prepare()
                player.playWhenReady = true
            }
        }

        player.addListener(listener)
        activeTextureView?.let { tv ->
            tv.tag = player
            player.setVideoTextureView(tv)
        }
        player.setMediaSource(mediaSource)
        player.prepare()
        activePlayer = player

        onDispose {
            activePlayer = null
            player.removeListener(listener)
            player.clearVideoSurface()
            player.stop()
            player.release()
        }
    }

    LaunchedEffect(activePlayer, isPlaying) {
        val player = activePlayer ?: return@LaunchedEffect
        if (isPlaying && !player.isPlaying) {
            player.playWhenReady = true
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                activeTextureView = this
                activePlayer?.let { player ->
                    tag = player
                    player.setVideoTextureView(this)
                    applyCenterCropTransform(this, player.videoSize)
                }
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    (view as? TextureView)?.let { tv ->
                        activePlayer?.let { player ->
                            applyCenterCropTransform(tv, player.videoSize)
                        }
                    }
                }
            }
        },
        update = { textureView ->
            activeTextureView = textureView
            val player = activePlayer
            if (player != null) {
                if (textureView.tag !== player) {
                    textureView.tag = player
                    player.setVideoTextureView(textureView)
                }
                applyCenterCropTransform(textureView, player.videoSize)
            }
        },
        modifier = modifier.graphicsLayer {
            alpha = videoAlpha
        }
    )
}
