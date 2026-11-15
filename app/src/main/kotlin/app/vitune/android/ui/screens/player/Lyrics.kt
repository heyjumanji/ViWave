package app.vitune.android.ui.screens.player

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Lyrics
import app.vitune.android.preferences.PlayerPreferences

import app.vitune.android.preferences.PlayerPreferences.LyricsTextPosition
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em

import app.vitune.android.query
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.transaction
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.Menu
import app.vitune.android.ui.components.themed.MenuEntry
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.ui.components.themed.TextFieldDialog
import app.vitune.android.ui.components.themed.TextPlaceholder
import app.vitune.android.ui.components.themed.ValueSelectorDialogBody
import app.vitune.android.ui.modifiers.verticalFadingEdge
import app.vitune.android.utils.SynchronizedLyrics
import app.vitune.android.utils.SynchronizedLyricsState
import app.vitune.android.utils.WordTiming
import app.vitune.android.utils.buildWordTimingsForSentences
import app.vitune.android.utils.cleanLrcText
import app.vitune.android.utils.ExternalLyricsProviders
import app.vitune.android.ui.components.themed.ValueSelectorDialog
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.isInPip
import app.vitune.android.utils.medium
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.toast
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.onOverlay
import app.vitune.core.ui.onOverlayShimmer
import app.vitune.core.ui.overlay
import app.vitune.core.ui.utils.dp
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.NextBody
import app.vitune.providers.innertube.requests.lyrics
import app.vitune.providers.kugou.KuGou
import app.vitune.providers.lrclib.LrcLib
import app.vitune.providers.lrclib.LrcParser
import app.vitune.providers.lrclib.models.Track
import app.vitune.providers.lrclib.toLrcFile
import com.valentinilk.shimmer.shimmer
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val UPDATE_DELAY = 50L

@Composable
fun Lyrics(
    mediaId: String,
    isDisplayed: Boolean,
    onDismiss: () -> Unit,
    mediaMetadataProvider: () -> MediaMetadata,
    durationProvider: () -> Long,
    ensureSongInserted: () -> Unit,
    modifier: Modifier = Modifier,
    onMenuLaunch: () -> Unit = { },
    onOpenDialog: (() -> Unit)? = null,
    shouldShowSynchronizedLyrics: Boolean = PlayerPreferences.isShowingSynchronizedLyrics,
    setShouldShowSynchronizedLyrics: (Boolean) -> Unit = {
        PlayerPreferences.isShowingSynchronizedLyrics = it
    },
    shouldKeepScreenAwake: Boolean = PlayerPreferences.lyricsKeepScreenAwake,
    shouldUpdateLyrics: Boolean = true,
    showControls: Boolean = true,
    isFullScreenLyricsDialog: Boolean = false
) = AnimatedVisibility(
    visible = isDisplayed,
    enter = fadeIn(),
    exit = fadeOut()
) {
    val currentEnsureSongInserted by rememberUpdatedState(ensureSongInserted)
    val currentMediaMetadataProvider by rememberUpdatedState(mediaMetadataProvider)
    val currentDurationProvider by rememberUpdatedState(durationProvider)

    val (colorPalette, typography) = LocalAppearance.current
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val binder = LocalPlayerServiceBinder.current
    val density = LocalDensity.current
    val view = LocalView.current

    val pip = isInPip()

    var lyrics by remember { mutableStateOf<Lyrics?>(null) }

    val showSynchronizedLyrics = remember(shouldShowSynchronizedLyrics, lyrics) {
        shouldShowSynchronizedLyrics && lyrics?.synced?.isBlank() != true
    }

    var editing by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }
    var picking by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }
    var selectingProvider by remember { mutableStateOf(false) }
    var error by remember(mediaId, shouldShowSynchronizedLyrics) { mutableStateOf(false) }

    val text = remember(lyrics, showSynchronizedLyrics) {
        if (showSynchronizedLyrics) lyrics?.synced else lyrics?.fixed
    }
    var invalidLrc by remember(text) { mutableStateOf(false) }

    DisposableEffect(shouldKeepScreenAwake) {
        view.keepScreenOn = shouldKeepScreenAwake

        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(
        mediaId,
        shouldShowSynchronizedLyrics,
        PlayerPreferences.defaultLyricsProvider,
        PlayerPreferences.lyricsFallbackEnabled,
        PlayerPreferences.lyricsV2Enabled
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                var attemptedFallbackOnBlank = false
                Database
                    .lyrics(mediaId)
                    .distinctUntilChanged()
                    .cancellable()
                    .collect { currentLyrics ->
                        val needsSynced = shouldShowSynchronizedLyrics && currentLyrics?.synced.isNullOrBlank()
                        val needsFixed = !shouldShowSynchronizedLyrics && currentLyrics?.fixed.isNullOrBlank()
                        val needsWordSyncUpgrade = shouldShowSynchronizedLyrics &&
                            PlayerPreferences.lyricsV2Enabled &&
                            !currentLyrics?.synced.isNullOrBlank() &&
                            !ExternalLyricsProviders.hasWordLevelTimestamps(currentLyrics?.synced)
                        val hasCompleteRecord = currentLyrics?.fixed != null && currentLyrics.synced != null
                        val shouldRetryWithFallback = !attemptedFallbackOnBlank && (needsSynced || needsFixed || needsWordSyncUpgrade)

                        if (!shouldUpdateLyrics || (hasCompleteRecord && !shouldRetryWithFallback)) {
                            lyrics = currentLyrics
                        } else {
                            attemptedFallbackOnBlank = true
                            // If we already have line-synced lyrics and are upgrading to word-sync in the background,
                            // show the existing lyrics immediately so there is zero blank flash
                            if (needsWordSyncUpgrade && currentLyrics != null) {
                                lyrics = currentLyrics
                                error = false
                            } else {
                                lyrics = null
                                error = false
                            }

                            val mediaMetadata = currentMediaMetadataProvider()
                            var duration =
                                withContext(Dispatchers.Main) { currentDurationProvider() }

                            var waitSteps = 0
                            while (duration == C.TIME_UNSET && waitSteps < 30) {
                                delay(100)
                                waitSteps++
                                duration =
                                    withContext(Dispatchers.Main) { currentDurationProvider() }
                            }
                            val resolvedDurationMs = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration

                            val album = mediaMetadata.albumTitle?.toString()
                            val artist = mediaMetadata.artist?.toString().orEmpty()
                            val title = mediaMetadata.title?.toString().orEmpty().let {
                                if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
                                    .substringBeforeLast('.')
                                    .trim()
                                else it
                            }

                            var resolved = ExternalLyricsProviders.resolveLyricsWithFallback(
                                videoId = mediaId,
                                title = title,
                                artist = artist,
                                album = album,
                                durationMs = resolvedDurationMs,
                                preferredProvider = PlayerPreferences.defaultLyricsProvider,
                                fallbackEnabled = PlayerPreferences.lyricsFallbackEnabled,
                                existingFixed = currentLyrics?.fixed,
                                existingSynced = currentLyrics?.synced,
                                preferWordSync = PlayerPreferences.lyricsV2Enabled
                            )

                            val strippedTitle = title.substringBefore('(').substringBefore('[').trim()
                            val stillNeedsUpgrade = PlayerPreferences.lyricsV2Enabled &&
                                !ExternalLyricsProviders.hasWordLevelTimestamps(resolved.synced)
                            if (
                                (resolved.synced.isNullOrBlank() || resolved.fixed.isNullOrBlank() || stillNeedsUpgrade) &&
                                strippedTitle.isNotBlank() &&
                                strippedTitle != title
                            ) {
                                resolved = ExternalLyricsProviders.resolveLyricsWithFallback(
                                    videoId = mediaId,
                                    title = strippedTitle,
                                    artist = artist,
                                    album = album,
                                    durationMs = resolvedDurationMs,
                                    preferredProvider = PlayerPreferences.defaultLyricsProvider,
                                    fallbackEnabled = PlayerPreferences.lyricsFallbackEnabled,
                                    existingFixed = resolved.fixed,
                                    existingSynced = resolved.synced,
                                    preferWordSync = PlayerPreferences.lyricsV2Enabled
                                )
                            }

                            val newLyrics = Lyrics(
                                songId = mediaId,
                                fixed = resolved.fixed.orEmpty(),
                                synced = resolved.synced.orEmpty(),
                                startTime = currentLyrics?.startTime
                            )
                            lyrics = newLyrics

                            ensureActive()
                            if (newLyrics.fixed != currentLyrics?.fixed || newLyrics.synced != currentLyrics?.synced) {
                                transaction {
                                    runCatching {
                                        currentEnsureSongInserted()
                                        Database.upsert(newLyrics)
                                    }
                                }
                            }
                        }

                        error =
                            (shouldShowSynchronizedLyrics && lyrics?.synced?.isBlank() == true) ||
                            (!shouldShowSynchronizedLyrics && lyrics?.fixed?.isBlank() == true)
                    }
            }
        }.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            else it.printStackTrace()
        }
    }

    if (editing) TextFieldDialog(
        hintText = stringResource(R.string.enter_lyrics),
        initialTextInput = (if (shouldShowSynchronizedLyrics) lyrics?.synced else lyrics?.fixed)
            .orEmpty(),
        singleLine = false,
        maxLines = 10,
        isTextInputValid = { true },
        onDismiss = { editing = false },
        onAccept = {
            transaction {
                runCatching {
                    currentEnsureSongInserted()

                    Database.upsert(
                        if (shouldShowSynchronizedLyrics) Lyrics(
                            songId = mediaId,
                            fixed = lyrics?.fixed,
                            synced = it
                        ) else Lyrics(
                            songId = mediaId,
                            fixed = it,
                            synced = lyrics?.synced
                        )
                    )
                }
            }
        }
    )

    if (picking && shouldShowSynchronizedLyrics) {
        var query by rememberSaveable {
            mutableStateOf(
                currentMediaMetadataProvider().title?.toString().orEmpty().let {
                    if (mediaId.startsWith(LOCAL_KEY_PREFIX)) it
                        .substringBeforeLast('.')
                        .trim()
                    else it
                }
            )
        }

        LrcLibSearchDialog(
            query = query,
            setQuery = { query = it },
            onDismiss = { picking = false },
            onPick = {
                runCatching {
                    transaction {
                        Database.upsert(
                            Lyrics(
                                songId = mediaId,
                                fixed = lyrics?.fixed,
                                synced = it.syncedLyrics
                            )
                        )
                    }
                }
            }
        )
    }

    if (selectingProvider) {
        ValueSelectorDialog(
            onDismiss = { selectingProvider = false },
            title = "Default Lyrics Provider",
            selectedValue = PlayerPreferences.defaultLyricsProvider,
            values = PlayerPreferences.LyricsProvider.entries.toImmutableList(),
            onValueSelect = { provider ->
                PlayerPreferences.defaultLyricsProvider = provider
                selectingProvider = false
                transaction {
                    runCatching {
                        currentEnsureSongInserted()
                        Database.upsert(
                            Lyrics(
                                songId = mediaId,
                                fixed = null,
                                synced = null
                            )
                        )
                    }
                }
            },
            valueText = { it.title }
        )
    }

    val topInsetPadding = if (PlayerPreferences.fullScreenPlayer && !isFullScreenLyricsDialog && !pip) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 14.dp
    } else {
        0.dp
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .fillMaxSize()
            .background(
                if (PlayerPreferences.fullScreenPlayer || isFullScreenLyricsDialog) {
                    Color.Transparent
                } else {
                    colorPalette.overlay.copy(alpha = 0.45f)
                }
            )
            .padding(
                top = topInsetPadding,
                bottom = if (PlayerPreferences.fullScreenPlayer && !isFullScreenLyricsDialog && !pip) 24.dp else 0.dp
            )
    ) {
        val animatedHeight by animateDpAsState(
            targetValue = this.maxHeight, // `this` needed, thanks Android Lint!
            label = ""
        )

        AnimatedVisibility(
            visible = !text.isNullOrBlank() && !error && invalidLrc && shouldShowSynchronizedLyrics,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
        ) {
            BasicText(
                text = stringResource(R.string.invalid_synchronized_lyrics),
                style = typography.xs.center.medium.color(colorPalette.onOverlay),
                modifier = Modifier
                    .background(
                        color = colorPalette.background2.copy(alpha = 0.85f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                maxLines = if (pip) 1 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val lyricsState = rememberSaveable(text, PlayerPreferences.lyricsV2Enabled) {
            val file = lyrics?.synced?.takeIf { it.isNotBlank() }?.let {
                LrcParser.parse(it)?.toLrcFile()
            }

            val sentences = file?.lines
            val wordTimings = if (PlayerPreferences.lyricsV2Enabled) {
                sentences?.let { buildWordTimingsForSentences(it) }
            } else {
                null
            }

            SynchronizedLyricsState(
                sentences = sentences,
                wordTimings = wordTimings,
                offset = file?.offset?.inWholeMilliseconds ?: 0L
            )
        }

        val synchronizedLyrics = remember(lyricsState) {
            invalidLrc = lyricsState.sentences == null
            lyricsState.sentences?.let { sentences ->
                val wordTimingsMap = lyricsState.wordTimings
                    ?.mapValues { entry -> entry.value.toImmutableList() }
                    ?.toImmutableMap()
                    ?: persistentMapOf()

                SynchronizedLyrics(
                    sentences = sentences.toImmutableMap(),
                    wordTimings = wordTimingsMap
                ) {
                    binder?.player?.let { player ->
                        player.currentPosition + UPDATE_DELAY + lyricsState.offset
                    } ?: 0L
                }.apply {
                    update()
                }
            }
        }

        AnimatedContent(
            targetState = showSynchronizedLyrics,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = ""
        ) { synchronized ->
            val lazyListState = rememberLazyListState()
            if (synchronized) {
                LaunchedEffect(synchronizedLyrics, density, animatedHeight) {
                    val currentSynchronizedLyrics = synchronizedLyrics ?: return@LaunchedEffect
                    val centerOffset = with(density) { (-animatedHeight / 3.1f).roundToPx() }

                    currentSynchronizedLyrics.update()
                    var lastIndex = currentSynchronizedLyrics.index
                    lazyListState.animateScrollToItem(
                        index = (lastIndex + 1).coerceAtLeast(0),
                        scrollOffset = centerOffset
                    )

                    while (true) {
                        val isPlayingNow = binder?.player?.isPlaying == true
                        if (isPlayingNow && PlayerPreferences.lyricsV2Enabled) {
                            // 60fps frame-accurate synchronization for BitChord / Apple Music word-by-word sweep
                            withFrameNanos { }
                        } else {
                            delay(if (isPlayingNow) 60L else 250L)
                        }
                        currentSynchronizedLyrics.update()
                        if (currentSynchronizedLyrics.index != lastIndex) {
                            lastIndex = currentSynchronizedLyrics.index
                            lazyListState.animateScrollToItem(
                                index = (lastIndex + 1).coerceAtLeast(0),
                                scrollOffset = centerOffset
                            )
                        }
                    }
                }

                if (synchronizedLyrics != null && !error) LazyColumn(
                    state = lazyListState,
                    userScrollEnabled = PlayerPreferences.lyricsAutoScroll,
                    horizontalAlignment = when (PlayerPreferences.lyricsTextPosition) {
                        LyricsTextPosition.Left -> Alignment.Start
                        LyricsTextPosition.Center -> Alignment.CenterHorizontally
                        LyricsTextPosition.Right -> Alignment.End
                    },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .verticalFadingEdge()
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                ) {
                    item(key = "header", contentType = 0) {
                        Spacer(modifier = Modifier.height(maxHeight / 2.4f))
                    }
                    itemsIndexed(
                        items = synchronizedLyrics.sentences.values.toImmutableList()
                    ) { index, sentence ->
                        val isCurrent = index == synchronizedLyrics.index
                        val isPast = index < synchronizedLyrics.index

                        val targetAlpha = when {
                            isCurrent -> 1f
                            isPast -> 0.28f
                            else -> 0.44f
                        }

                        val alpha by animateFloatAsState(
                            targetValue = targetAlpha,
                            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                            label = "alpha"
                        )
                        val scale by animateFloatAsState(
                            targetValue = if (isCurrent) 1.035f else 0.975f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "scale"
                        )

                        val translationX = 0f

                        val blurRadius by animateFloatAsState(
                            targetValue = if (isCurrent) 8f else 0f,
                            animationSpec = tween(durationMillis = 260, easing = LinearEasing),
                            label = "blur"
                        )

                        val textSize = PlayerPreferences.lyricsTextSize.sp
                        val textAlign = when (PlayerPreferences.lyricsTextPosition) {
                            LyricsTextPosition.Left -> TextAlign.Left
                            LyricsTextPosition.Center -> TextAlign.Center
                            LyricsTextPosition.Right -> TextAlign.Right
                        }

                        val finalAlpha = alpha
                        val cleanedText = remember(sentence) { cleanLrcText(sentence) }
                        val lineWords = synchronizedLyrics.wordTimings[index]
                        val isWordSync = PlayerPreferences.lyricsV2Enabled && !lineWords.isNullOrEmpty()

                        if (cleanedText.isBlank() && sentence.isBlank()) {
                            // BitChord / Apple Music animated instrumental interlude dots
                            val currentPos = if (isCurrent) synchronizedLyrics.currentPositionMs else 0L
                            val lineStart = synchronizedLyrics.sentences.keys.toList().getOrNull(index) ?: 0L
                            val lineEnd = synchronizedLyrics.sentences.keys.toList().getOrNull(index + 1) ?: (lineStart + 4000L)
                            val interludeProgress = if (isCurrent && lineEnd > lineStart) {
                                ((currentPos - lineStart).toFloat() / (lineEnd - lineStart).toFloat()).coerceIn(0f, 1f)
                            } else 0f

                            androidx.compose.foundation.layout.Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(vertical = 12.dp, horizontal = 20.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        this.translationX = translationX
                                    }
                            ) {
                                repeat(3) { dotIdx ->
                                    val dotStart = dotIdx / 3f
                                    val dotFill = ((interludeProgress - dotStart) * 3f).coerceIn(0f, 1f)
                                    val dotScale = if (isCurrent) 0.85f + 0.35f * kotlin.math.sin(dotFill * Math.PI).toFloat() else 0.8f
                                    val dotAlpha = when {
                                        isCurrent -> 0.35f + 0.65f * dotFill
                                        else -> finalAlpha * 0.65f
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .graphicsLayer {
                                                scaleX = dotScale
                                                scaleY = dotScale
                                            }
                                            .background(
                                                color = (if (isCurrent && PlayerPreferences.lyricsWordSyncDynamicColors) colorPalette.accent else Color.White)
                                                    .copy(alpha = dotAlpha),
                                                shape = androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                }
                            }
                        } else if (isWordSync && lineWords != null) {
                            val currentPos = if (isCurrent) synchronizedLyrics.currentPositionMs else 0L
                            val maxLiftPx = with(density) { 2.4.dp.toPx() }

                            FlowRow(
                                horizontalArrangement = when (PlayerPreferences.lyricsTextPosition) {
                                    LyricsTextPosition.Left -> Arrangement.Start
                                    LyricsTextPosition.Center -> Arrangement.Center
                                    LyricsTextPosition.Right -> Arrangement.End
                                },
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .let {
                                        if (PlayerPreferences.lyricsChangeOnClick) it.clickable {
                                            val targetTime = synchronizedLyrics.sentences.keys.toList().getOrNull(index)
                                            if (targetTime != null) {
                                                val seekPos = (targetTime - lyricsState.offset).coerceAtLeast(0L)
                                                binder?.player?.seekTo(seekPos)
                                            }
                                        } else it
                                    }
                                    .padding(vertical = 10.dp, horizontal = 16.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        this.translationX = translationX
                                        transformOrigin = TransformOrigin(
                                            when (PlayerPreferences.lyricsTextPosition) {
                                                LyricsTextPosition.Left -> 0f
                                                LyricsTextPosition.Center -> 0.5f
                                                LyricsTextPosition.Right -> 1f
                                            },
                                            0.5f
                                        )
                                    }
                            ) {
                                val isDynamicColors = PlayerPreferences.lyricsWordSyncDynamicColors
                                val activeColor = if (isDynamicColors) colorPalette.accent else Color.White
                                val isLinePast = index < synchronizedLyrics.index
                                val isLineFuture = index > synchronizedLyrics.index

                                lineWords.forEach { wordTiming ->
                                    val wordDuration = (wordTiming.endTime - wordTiming.startTime).coerceAtLeast(1L)
                                    val rawProgress = (currentPos - wordTiming.startTime).toFloat() / wordDuration.toFloat()
                                    val wordProgress = when {
                                        isLinePast -> 1f
                                        isLineFuture -> 0f
                                        else -> rawProgress.coerceIn(0f, 1f)
                                    }

                                    val isWordActive = isCurrent && wordProgress > 0f && wordProgress < 1f
                                    val isWordCompleted = isLinePast || (isCurrent && wordProgress >= 1f)

                                    // BitChord / Apple Music continuous syllable wave lift & scale
                                    val activeWave = if (isWordActive) {
                                        kotlin.math.sin(wordProgress * Math.PI).toFloat()
                                    } else 0f
                                    val wordScale = 1f + (0.042f * activeWave)
                                    val wordLiftY = when {
                                        isWordActive -> -maxLiftPx * (0.35f + 0.65f * wordProgress)
                                        isWordCompleted && isCurrent -> -maxLiftPx * 0.65f
                                        else -> 0f
                                    }

                                    val bgVocalAlphaScale = if (wordTiming.isBackgroundVocal) 0.78f else 1f
                                    val effectiveTextSize = if (wordTiming.isBackgroundVocal) textSize * 0.88f else textSize
                                    val unsungColor = Color.White.copy(
                                        alpha = (if (isCurrent) 0.38f else 0.28f) * bgVocalAlphaScale
                                    )
                                    val sungColor = (if (isDynamicColors && isCurrent) activeColor else Color.White).copy(
                                        alpha = (if (isLinePast) 0.30f else 0.98f) * bgVocalAlphaScale
                                    )

                                    // Soft-feathered horizontal gradient sweep across the active word
                                    val fillBrush = if (isWordActive) {
                                        val feather = 0.16f
                                        val sweepCenter = wordProgress * (1f + feather) - (feather * 0.5f)
                                        val leadEdge = (sweepCenter - feather * 0.5f).coerceIn(0f, 0.99f)
                                        val trailEdge = (sweepCenter + feather * 0.5f).coerceIn(leadEdge + 0.01f, 1f)
                                        Brush.horizontalGradient(
                                            0.0f to sungColor,
                                            leadEdge to sungColor,
                                            trailEdge to unsungColor,
                                            1.0f to unsungColor
                                        )
                                    } else null

                                    val staticWordColor = if (isWordCompleted) sungColor else unsungColor

                                    BasicText(
                                        text = "${wordTiming.word} ",
                                        style = (if (fillBrush != null) {
                                            typography.l.semiBold.copy(brush = fillBrush)
                                        } else {
                                            typography.l.semiBold.color(staticWordColor)
                                        }).copy(
                                            fontSize = effectiveTextSize,
                                            lineHeight = effectiveTextSize * PlayerPreferences.lyricsLineSpacing,
                                            textAlign = textAlign,
                                            shadow = when {
                                                isWordActive -> Shadow(
                                                    color = activeColor.copy(alpha = 0.55f + 0.30f * activeWave),
                                                    blurRadius = 12f + 6f * activeWave
                                                )
                                                isWordCompleted && isCurrent && blurRadius > 0f -> Shadow(
                                                    color = activeColor.copy(alpha = 0.38f),
                                                    blurRadius = blurRadius
                                                )
                                                else -> null
                                            }
                                        ),
                                        modifier = Modifier
                                            .graphicsLayer {
                                                scaleX = wordScale
                                                scaleY = wordScale
                                                translationY = wordLiftY
                                            }
                                            .clickable {
                                                if (PlayerPreferences.lyricsChangeOnClick) {
                                                    val seekPos = (wordTiming.startTime - lyricsState.offset).coerceAtLeast(0L)
                                                    binder?.player?.seekTo(seekPos)
                                                }
                                            }
                                    )
                                }
                            }
                        } else {
                            BasicText(
                                text = cleanedText,
                                style = typography.l.semiBold.color(
                                    if (isCurrent) Color.White else Color.White.copy(alpha = finalAlpha)
                                ).copy(
                                    fontSize = textSize,
                                    lineHeight = textSize * PlayerPreferences.lyricsLineSpacing,
                                    textAlign = textAlign,
                                    shadow = if (isCurrent && blurRadius > 0f) {
                                        Shadow(
                                            color = colorPalette.accent.copy(alpha = 0.55f),
                                            blurRadius = blurRadius
                                        )
                                    } else null
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .let {
                                        if (PlayerPreferences.lyricsChangeOnClick) it.clickable {
                                            val targetTime = synchronizedLyrics.sentences.keys.toList().getOrNull(index)
                                            if (targetTime != null) {
                                                val seekPos = (targetTime - lyricsState.offset).coerceAtLeast(0L)
                                                binder?.player?.seekTo(seekPos)
                                            }
                                        } else it
                                    }
                                    .padding(vertical = 10.dp, horizontal = 16.dp)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        this.translationX = translationX
                                        transformOrigin = TransformOrigin(
                                            when (PlayerPreferences.lyricsTextPosition) {
                                                LyricsTextPosition.Left -> 0f
                                                LyricsTextPosition.Center -> 0.5f
                                                LyricsTextPosition.Right -> 1f
                                            },
                                            0.5f
                                        )
                                    }
                            )
                        }
                    }
                    item(key = "footer", contentType = 0) {
                        Spacer(modifier = Modifier.height(maxHeight / 2.2f))
                    }
                }
            } else if (!error && !lyrics?.fixed.isNullOrBlank()) {
                val textAlign = when (PlayerPreferences.lyricsTextPosition) {
                    LyricsTextPosition.Left -> TextAlign.Left
                    LyricsTextPosition.Center -> TextAlign.Center
                    LyricsTextPosition.Right -> TextAlign.Right
                }
                BasicText(
                    text = lyrics?.fixed.orEmpty().trim(),
                    style = typography.m.semiBold.color(Color.White.copy(alpha = 0.92f)).copy(
                        lineHeight = 26.sp,
                        textAlign = textAlign
                    ),
                    modifier = Modifier
                        .verticalFadingEdge()
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                        .padding(vertical = maxHeight / 5, horizontal = 28.dp)
                )
            }
        }

        // Clean centered fallback state when lyrics are unavailable
        AnimatedVisibility(
            visible = error,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .background(
                        color = colorPalette.background1.copy(alpha = 0.88f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.musical_notes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.accent),
                    modifier = Modifier.size(28.dp)
                )
                BasicText(
                    text = stringResource(
                        if (shouldShowSynchronizedLyrics) R.string.synchronized_lyrics_not_available
                        else R.string.lyrics_not_available
                    ),
                    style = typography.s.semiBold.center.color(colorPalette.text),
                    maxLines = if (pip) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!pip) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (shouldShowSynchronizedLyrics && !lyrics?.fixed.isNullOrBlank()) {
                            BasicText(
                                text = stringResource(R.string.show_unsynchronized_lyrics),
                                style = typography.xxs.semiBold.color(colorPalette.text),
                                modifier = Modifier
                                    .background(
                                        color = colorPalette.background2,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
                                    )
                                    .clickable { setShouldShowSynchronizedLyrics(false) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        if (shouldShowSynchronizedLyrics) {
                            BasicText(
                                text = stringResource(R.string.pick_from_lrclib),
                                style = typography.xxs.semiBold.color(colorPalette.accent),
                                modifier = Modifier
                                    .background(
                                        color = colorPalette.accent.copy(alpha = 0.16f),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
                                    )
                                    .clickable { picking = true }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        BasicText(
                            text = stringResource(R.string.edit_lyrics),
                            style = typography.xxs.semiBold.color(colorPalette.textSecondary),
                            modifier = Modifier
                                .background(
                                    color = colorPalette.background2,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50)
                                )
                                .clickable { editing = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (text == null && !error) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .shimmer()
                .padding(horizontal = 32.dp)
        ) {
            repeat(4) {
                TextPlaceholder(
                    color = colorPalette.onOverlayShimmer,
                    modifier = Modifier.alpha(1f - it * 0.2f)
                )
            }
        }

        if (showControls) {
            if (onOpenDialog != null) Image(
                painter = painterResource(R.drawable.expand),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.onOverlay),
                modifier = Modifier
                    .padding(all = 4.dp)
                    .clickable(
                        indication = ripple(bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onOpenDialog()
                        }
                    )
                    .padding(all = 8.dp)
                    .size(20.dp)
                    .align(Alignment.BottomStart)
            )

            Image(
                painter = painterResource(R.drawable.ellipsis_horizontal),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.onOverlay),
                modifier = Modifier
                    .padding(all = 4.dp)
                    .clickable(
                        indication = ripple(bounded = false),
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onMenuLaunch()
                            menuState.display {
                                Menu {
                                    MenuEntry(
                                        icon = R.drawable.time,
                                        text = stringResource(
                                            if (shouldShowSynchronizedLyrics) R.string.show_unsynchronized_lyrics
                                            else R.string.show_synchronized_lyrics
                                        ),
                                        secondaryText = if (shouldShowSynchronizedLyrics) null
                                        else stringResource(R.string.provided_lyrics_by),
                                        onClick = {
                                            menuState.hide()
                                            setShouldShowSynchronizedLyrics(!shouldShowSynchronizedLyrics)
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.pencil,
                                        text = stringResource(R.string.edit_lyrics),
                                        onClick = {
                                            menuState.hide()
                                            editing = true
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.search,
                                        text = stringResource(R.string.search_lyrics_online),
                                        onClick = {
                                            menuState.hide()
                                            val mediaMetadata = currentMediaMetadataProvider()

                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_WEB_SEARCH).apply {
                                                        putExtra(
                                                            SearchManager.QUERY,
                                                            "${mediaMetadata.title} ${mediaMetadata.artist} lyrics"
                                                        )
                                                    }
                                                )
                                            } catch (_: ActivityNotFoundException) {
                                                context.toast(context.getString(R.string.no_browser_installed))
                                            }
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.sync,
                                        text = stringResource(R.string.refetch_lyrics),
                                        enabled = lyrics != null,
                                        onClick = {
                                            menuState.hide()

                                            transaction {
                                                runCatching {
                                                    currentEnsureSongInserted()

                                                    Database.upsert(
                                                        if (shouldShowSynchronizedLyrics) Lyrics(
                                                            songId = mediaId,
                                                            fixed = lyrics?.fixed,
                                                            synced = null
                                                        ) else Lyrics(
                                                            songId = mediaId,
                                                            fixed = null,
                                                            synced = lyrics?.synced
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    )

                                    if (shouldShowSynchronizedLyrics) {
                                        MenuEntry(
                                            icon = R.drawable.download,
                                            text = stringResource(R.string.pick_from_lrclib),
                                            onClick = {
                                                menuState.hide()
                                                picking = true
                                            }
                                        )
                                    }

                                    MenuEntry(
                                        icon = R.drawable.sparkles,
                                        text = "Default lyrics provider",
                                        secondaryText = "${PlayerPreferences.defaultLyricsProvider.title} (Fallback: ${if (PlayerPreferences.lyricsFallbackEnabled) "On" else "Off"})",
                                        onClick = {
                                            menuState.hide()
                                            selectingProvider = true
                                        }
                                    )

                                    MenuEntry(
                                        icon = R.drawable.sync,
                                        text = "Reset lyrics settings to default",
                                        secondaryText = "Reset provider, fallback & lyrics style settings",
                                        onClick = {
                                            menuState.hide()
                                            PlayerPreferences.resetLyricsSettingsToDefault()
                                            transaction {
                                                runCatching {
                                                    currentEnsureSongInserted()
                                                    Database.upsert(
                                                        Lyrics(
                                                            songId = mediaId,
                                                            fixed = null,
                                                            synced = null
                                                        )
                                                    )
                                                }
                                            }
                                            context.toast("Lyrics settings reset to default")
                                        }
                                    )
                                }
                            }
                        }
                    )
                    .padding(all = 8.dp)
                    .size(20.dp)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun LrcLibSearchDialog(
    query: String,
    setQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Track) -> Unit,
    modifier: Modifier = Modifier
) = DefaultDialog(
    onDismiss = onDismiss,
    horizontalPadding = 0.dp,
    modifier = modifier
) {
    val (_, typography) = LocalAppearance.current

    val tracks = remember { mutableStateListOf<Track>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        loading = true
        error = false

        delay(1000)

        LrcLib.lyrics(
            query = query,
            synced = true
        )?.onSuccess { newTracks ->
            tracks.clear()
            tracks.addAll(newTracks.filter { !it.syncedLyrics.isNullOrBlank() })
            loading = false
            error = false
        }?.onFailure {
            loading = false
            error = true
            it.printStackTrace()
        } ?: run { loading = false }
    }

    TextField(
        value = query,
        onValueChange = setQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        maxLines = 1,
        singleLine = true
    )
    Spacer(modifier = Modifier.height(8.dp))

    when {
        loading -> CircularProgressIndicator(
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        error || tracks.isEmpty() -> BasicText(
            text = stringResource(R.string.no_lyrics_found),
            style = typography.s.semiBold.center,
            modifier = Modifier
                .padding(all = 24.dp)
                .align(Alignment.CenterHorizontally)
        )

        else -> ValueSelectorDialogBody(
            onDismiss = onDismiss,
            title = stringResource(R.string.choose_lyric_track),
            selectedValue = null,
            values = tracks.toImmutableList(),
            onValueSelect = {
                transaction {
                    onPick(it)
                    onDismiss()
                }
            },
            valueText = {
                "${it.artistName} - ${it.trackName} (${
                    it.duration.seconds.toComponents { minutes, seconds, _ ->
                        "$minutes:${seconds.toString().padStart(2, '0')}"
                    }
                })"
            }
        )
    }
}
