package app.vitune.android.ui.screens.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.vitune.core.ui.utils.songBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSupported
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.vitune.android.Database
import app.vitune.android.R
import app.vitune.android.models.Lyrics
import app.vitune.android.models.ui.UiMedia
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.service.PlayerService
import app.vitune.android.transaction
import app.vitune.android.ui.components.BottomSheetState
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.themed.QueuedMediaItemMenu
import app.vitune.android.ui.components.themed.ValueSelectorDialog
import app.vitune.android.ui.modifiers.swipeToClose
import app.vitune.android.ui.screens.albumRoute
import app.vitune.android.ui.screens.artistRoute
import app.vitune.android.utils.ExternalLyricsProviders
import app.vitune.android.utils.PlaybackSourceState
import app.vitune.android.utils.cleanLrcText
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.mediaItems
import app.vitune.android.utils.seamlessPlay
import app.vitune.android.utils.shouldBePlaying
import app.vitune.android.utils.shuffleQueue
import app.vitune.android.utils.thumbnail
import app.vitune.android.utils.windows
import app.vitune.compose.reordering.animateItemPlacement
import app.vitune.compose.reordering.draggedItem
import app.vitune.compose.reordering.rememberReorderingState
import app.vitune.compose.reordering.reorder
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isLandscape
import app.vitune.core.ui.utils.px
import app.vitune.providers.translate.models.Language
import kotlinx.collections.immutable.toImmutableList
import app.vitune.android.preferences.DataPreferences
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.bodies.NextBody
import app.vitune.providers.innertube.requests.nextPage
import app.vitune.providers.kugou.KuGou
import app.vitune.providers.lrclib.LrcLib
import app.vitune.providers.spotify.requests.SpotifyApi
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// 1. FadeCurve.kt & FadingEdges.kt (BitChord verbatim)
// ============================================================================

internal fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

internal const val FADE_CURVE_STOPS = 16
private val NoFade = { 0f }

internal fun Modifier.fadingEdges(
    width: Dp,
    start: () -> Float = NoFade,
    end: () -> Float = NoFade,
): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val fade = width.toPx().coerceAtMost(size.width / 2f).coerceAtLeast(1f)
            val stops = Array(FADE_CURVE_STOPS + 1) { i ->
                val t = i / FADE_CURVE_STOPS.toFloat()
                t to Color.Black.copy(alpha = (1f - smootherStep(t)).coerceIn(0f, 1f))
            }
            val leftBrush = Brush.horizontalGradient(*stops, startX = 0f, endX = fade)
            val rightBrush = Brush.horizontalGradient(*stops, startX = size.width, endX = (size.width - fade).coerceAtLeast(0f))
            val rtl = layoutDirection == LayoutDirection.Rtl
            onDrawWithContent {
                drawContent()
                if (size.width <= 1f || size.height <= 1f) return@onDrawWithContent
                val left = (if (rtl) end() else start()).coerceIn(0f, 1f)
                if (left > 0f) {
                    drawRect(leftBrush, Offset.Zero, Size(fade, size.height), alpha = left, blendMode = BlendMode.DstOut)
                }
                val right = (if (rtl) start() else end()).coerceIn(0f, 1f)
                if (right > 0f) {
                    drawRect(
                        rightBrush,
                        Offset((size.width - fade).coerceAtLeast(0f), 0f),
                        Size(fade, size.height),
                        alpha = right,
                        blendMode = BlendMode.DstOut,
                    )
                }
            }
        }

// ============================================================================
// 2. PlayerChrome.kt (BitChord verbatim)
// ============================================================================

internal val LocalPlayerChrome = compositionLocalOf { true }

internal const val CHROME_FULL_ALPHA = 0.73f
private const val CHROME_ALPHA_DROP = 0.15f

@Composable
@ReadOnlyComposable
internal fun chromeWhite(alpha: Float): Color =
    Color.White.copy(alpha = (if (LocalPlayerChrome.current) alpha - CHROME_ALPHA_DROP else alpha).coerceIn(0f, 1f))

internal val VibrantChromeInk = Color(0xFF999999).copy(alpha = 1f - CHROME_ALPHA_DROP)
private const val VIBRANT_CHROME_FLOOR = 0.12f

internal fun Modifier.additiveChrome(enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithCache {
            val layer = obtainGraphicsLayer().apply { blendMode = BlendMode.Plus }
            onDrawWithContent {
                if (size.width <= 0f || size.height <= 0f) {
                    drawContent()
                    return@onDrawWithContent
                }
                layer.record { this@onDrawWithContent.drawContent() }
                drawLayer(layer)
            }
        }
    }

internal fun Modifier.vibrantChrome(enabled: Boolean): Modifier =
    if (!enabled || !BlendMode.ColorDodge.isSupported()) {
        additiveChrome(enabled)
    } else {
        drawWithCache {
            val floor = obtainGraphicsLayer().apply {
                blendMode = BlendMode.Plus
                alpha = VIBRANT_CHROME_FLOOR
            }
            val dodge = obtainGraphicsLayer().apply { blendMode = BlendMode.ColorDodge }
            onDrawWithContent {
                if (size.width <= 0f || size.height <= 0f) {
                    drawContent()
                    return@onDrawWithContent
                }
                floor.record { this@onDrawWithContent.drawContent() }
                dodge.record { this@onDrawWithContent.drawContent() }
                drawLayer(floor)
                drawLayer(dodge)
            }
        }
    }

// ============================================================================
// 3. BitChordIcons.kt (BitChord verbatim + Material Rounded icons)
// ============================================================================

internal object BitChordIcons {
    private inline fun strokeIcon(
        name: String,
        strokeWidth: Float = 2f,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun ImageVector.Builder.strokePath(
        strokeWidth: Float = 2f,
        cap: StrokeCap = StrokeCap.Round,
        join: StrokeJoin = StrokeJoin.Round,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = strokeWidth,
            strokeLineCap = cap,
            strokeLineJoin = join,
            pathBuilder = block,
        )
    }

    private fun ImageVector.Builder.fillPath(
        fillType: PathFillType = PathFillType.NonZero,
        block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ) {
        path(
            fill = SolidColor(Color.White),
            pathFillType = fillType,
            pathBuilder = block,
        )
    }

    private fun svgIcon(
        name: String,
        pathData: String,
        autoMirror: Boolean = false,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.White),
        )
    }.build()

    val Queue: ImageVector by lazy {
        strokeIcon("Queue") {
            fillPath {
                moveTo(4f, 6f)
                arcToRelative(1.35f, 1.35f, 0f, true, true, 0f, 2.7f)
                arcToRelative(1.35f, 1.35f, 0f, false, true, 0f, -2.7f)
                close()
                moveTo(4f, 11.15f)
                arcToRelative(1.35f, 1.35f, 0f, true, true, 0f, 2.7f)
                arcToRelative(1.35f, 1.35f, 0f, false, true, 0f, -2.7f)
                close()
                moveTo(4f, 16.3f)
                arcToRelative(1.35f, 1.35f, 0f, true, true, 0f, 2.7f)
                arcToRelative(1.35f, 1.35f, 0f, false, true, 0f, -2.7f)
                close()
            }
            strokePath(strokeWidth = 2.2f) {
                moveTo(8.5f, 7.35f); horizontalLineTo(20f)
                moveTo(8.5f, 12.5f); horizontalLineTo(20f)
                moveTo(8.5f, 17.65f); horizontalLineTo(20f)
            }
        }
    }

    val LyricsQuote: ImageVector by lazy {
        strokeIcon("LyricsQuote") {
            strokePath {
                moveTo(6f, 4f)
                horizontalLineTo(18f)
                arcTo(3f, 3f, 0f, false, true, 21f, 7f)
                verticalLineTo(15f)
                arcTo(3f, 3f, 0f, false, true, 18f, 18f)
                horizontalLineTo(11f)
                lineTo(6.5f, 21.5f)
                verticalLineTo(18f)
                horizontalLineTo(6f)
                arcTo(3f, 3f, 0f, false, true, 3f, 15f)
                verticalLineTo(7f)
                arcTo(3f, 3f, 0f, false, true, 6f, 4f)
                close()
            }
            fillPath {
                moveTo(8.2f, 8.2f)
                horizontalLineToRelative(2.6f)
                verticalLineToRelative(2.6f)
                curveToRelative(0f, 1.5f, -0.9f, 2.5f, -2.3f, 2.9f)
                lineToRelative(-0.4f, -0.9f)
                curveToRelative(0.8f, -0.3f, 1.3f, -0.9f, 1.3f, -2.0f)
                horizontalLineToRelative(-1.2f)
                close()
                moveTo(13.2f, 8.2f)
                horizontalLineToRelative(2.6f)
                verticalLineToRelative(2.6f)
                curveToRelative(0f, 1.5f, -0.9f, 2.5f, -2.3f, 2.9f)
                lineToRelative(-0.4f, -0.9f)
                curveToRelative(0.8f, -0.3f, 1.3f, -0.9f, 1.3f, -2.0f)
                horizontalLineToRelative(-1.2f)
                close()
            }
        }
    }

    val Star: ImageVector by lazy {
        strokeIcon("Star") {
            strokePath(strokeWidth = 1.9f) {
                moveTo(12f, 3.3f)
                lineTo(14.55f, 8.75f)
                lineTo(20.5f, 9.5f)
                lineTo(16.1f, 13.6f)
                lineTo(17.25f, 19.5f)
                lineTo(12f, 16.6f)
                lineTo(6.75f, 19.5f)
                lineTo(7.9f, 13.6f)
                lineTo(3.5f, 9.5f)
                lineTo(9.45f, 8.75f)
                close()
            }
        }
    }

    val StarFilled: ImageVector by lazy {
        strokeIcon("StarFilled") {
            path(
                fill = SolidColor(Color.White),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.3f)
                lineTo(14.55f, 8.75f)
                lineTo(20.5f, 9.5f)
                lineTo(16.1f, 13.6f)
                lineTo(17.25f, 19.5f)
                lineTo(12f, 16.6f)
                lineTo(6.75f, 19.5f)
                lineTo(7.9f, 13.6f)
                lineTo(3.5f, 9.5f)
                lineTo(9.45f, 8.75f)
                close()
            }
        }
    }

    val Shuffle: ImageVector by lazy {
        strokeIcon("Shuffle") {
            strokePath(strokeWidth = 2.0f) {
                moveTo(17f, 4f); lineTo(20.5f, 7f); lineTo(17f, 10f)
                moveTo(17f, 14f); lineTo(20.5f, 17f); lineTo(17f, 20f)
                moveTo(3.5f, 7f)
                horizontalLineTo(7f)
                curveTo(10.5f, 7f, 12.5f, 17f, 16f, 17f)
                horizontalLineTo(20f)
                moveTo(3.5f, 17f)
                horizontalLineTo(7f)
                curveTo(8.3f, 17f, 9.4f, 15.8f, 10.4f, 14.2f)
                moveTo(13.6f, 9.8f)
                curveTo(14.6f, 8.2f, 15.7f, 7f, 17f, 7f)
                horizontalLineTo(20f)
            }
        }
    }

    val Repeat: ImageVector by lazy {
        strokeIcon("Repeat") {
            strokePath(strokeWidth = 2.0f) {
                moveTo(17f, 3.5f); lineTo(20f, 6.5f); lineTo(17f, 9.5f)
                moveTo(4f, 11.5f)
                verticalLineTo(10f)
                arcTo(3.5f, 3.5f, 0f, false, true, 7.5f, 6.5f)
                horizontalLineTo(19.5f)
                moveTo(7f, 20.5f); lineTo(4f, 17.5f); lineTo(7f, 14.5f)
                moveTo(20f, 12.5f)
                verticalLineTo(14f)
                arcTo(3.5f, 3.5f, 0f, false, true, 16.5f, 17.5f)
                horizontalLineTo(4.5f)
            }
        }
    }

    val Infinity: ImageVector by lazy {
        strokeIcon("Infinity") {
            strokePath(strokeWidth = 2.1f) {
                moveTo(12f, 12f)
                curveTo(10.2f, 9.3f, 8.5f, 8f, 6.5f, 8f)
                arcTo(4f, 4f, 0f, true, false, 6.5f, 16f)
                curveTo(8.5f, 16f, 10.2f, 14.7f, 12f, 12f)
                curveTo(13.8f, 9.3f, 15.5f, 8f, 17.5f, 8f)
                arcTo(4f, 4f, 0f, true, true, 17.5f, 16f)
                curveTo(15.5f, 16f, 13.8f, 14.7f, 12f, 12f)
                close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            strokePath(strokeWidth = 2.4f) {
                moveTo(9f, 5.5f)
                lineTo(15.5f, 12f)
                lineTo(9f, 18.5f)
            }
        }
    }

    val MusicNote: ImageVector by lazy {
        strokeIcon("MusicNote") {
            strokePath(strokeWidth = 2f) {
                moveTo(9f, 17.5f)
                verticalLineTo(6f)
                lineTo(19f, 4f)
                verticalLineTo(15.5f)
                moveTo(9f, 10f)
                lineTo(19f, 8f)
            }
            fillPath {
                moveTo(6.5f, 15f)
                arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, 5f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, 0f, -5f)
                close()
                moveTo(16.5f, 13f)
                arcToRelative(2.5f, 2.5f, 0f, true, true, 0f, 5f)
                arcToRelative(2.5f, 2.5f, 0f, false, true, 0f, -5f)
                close()
            }
        }
    }

    val Instrumental: ImageVector by lazy { MusicNote }

    val MoreHoriz: ImageVector by lazy {
        svgIcon(
            name = "MoreHoriz",
            pathData = "M6,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2S7.1,10 6,10zM18,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2S19.1,10 18,10zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2s2,-0.9 2,-2S13.1,10 12,10z",
        )
    }

    val VolumeDown: ImageVector by lazy {
        svgIcon(
            name = "VolumeDown",
            pathData = "M18.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM5,10v4c0,0.55 0.45,1 1,1h3l3.29,3.29c0.63,0.63 1.71,0.18 1.71,-0.71V6.41c0,-0.89 -1.08,-1.34 -1.71,-0.71L9,9H6c-0.55,0 -1,0.45 -1,1z",
            autoMirror = true,
        )
    }

    val VolumeUp: ImageVector by lazy {
        svgIcon(
            name = "VolumeUp",
            pathData = "M3,10v4c0,0.55 0.45,1 1,1h3l3.29,3.29c0.63,0.63 1.71,0.18 1.71,-0.71V6.41c0,-0.89 -1.08,-1.34 -1.71,-0.71L7,9H4c-0.55,0 -1,0.45 -1,1zm13.5,2c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,4.45v0.2c0,0.38 0.25,0.71 0.6,0.85C17.18,6.53 19,9.06 19,12s-1.82,5.47 -4.4,6.5c-0.36,0.14 -0.6,0.47 -0.6,0.85v0.2c0,0.63 0.63,1.07 1.21,0.85C18.6,19.11 21,15.84 21,12s-2.4,-7.11 -5.79,-8.4c-0.58,-0.23 -1.21,0.22 -1.21,0.85z",
            autoMirror = true,
        )
    }

    val Cast: ImageVector by lazy {
        svgIcon(
            name = "Cast",
            pathData = "M21,3H3c-1.1,0 -2,0.9 -2,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1V6c0,-0.55 0.45,-1 1,-1h16c0.55,0 1,0.45 1,1v12c0,0.55 -0.45,1 -1,1h-5c-0.55,0 -1,0.45 -1,1s0.45,1 1,1h6c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM1,18v3h3c0,-1.66 -1.34,-3 -3,-3zm0,-4v1c2.76,0 5,2.24 5,5h1c0,-3.31 -2.69,-6 -6,-6zm0,-4v1c4.97,0 9,4.03 9,9h1c0,-5.52 -4.48,-10 -10,-10z",
        )
    }

    val Globe: ImageVector by lazy {
        svgIcon(
            name = "Globe",
            pathData = "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM18.92,8h-2.95c-0.32,-1.25 -0.78,-2.45 -1.38,-3.56c1.84,0.63 3.37,1.91 4.33,3.56zM12,4.04c0.83,1.2 1.48,2.53 1.91,3.96h-3.82c0.43,-1.43 1.08,-2.76 1.91,-3.96zM4.26,14C4.1,13.36 4,12.69 4,12s0.1,-1.36 0.26,-2h3.38c-0.08,0.66 -0.14,1.32 -0.14,2c0,0.68 0.06,1.34 0.14,2H4.26zM5.08,16h2.95c0.32,1.25 0.78,2.45 1.38,3.56c-1.84,-0.63 -3.37,-1.9 -4.33,-3.56zM8.03,8H5.08c0.96,-1.66 2.49,-2.93 4.33,-3.56C8.81,5.55 8.35,6.75 8.03,8zM12,19.96c-0.83,-1.2 -1.48,-2.53 -1.91,-3.96h3.82c-0.43,1.43 -1.08,2.76 -1.91,3.96zM14.34,14H9.66c-0.09,-0.66 -0.16,-1.32 -0.16,-2c0,-0.68 0.07,-1.35 0.16,-2h4.68c0.09,0.65 0.16,1.32 0.16,2c0,0.68 -0.07,1.34 -0.16,2zM14.59,19.56c0.6,-1.11 1.06,-2.31 1.38,-3.56h2.95c-0.96,1.65 -2.49,2.93 -4.33,3.56zM16.36,14c0.08,-0.66 0.14,-1.32 0.14,-2c0,-0.68 -0.06,-1.34 -0.14,-2h3.38c0.16,0.64 0.26,1.31 0.26,2s-0.1,1.36 -0.26,2h-3.38z",
        )
    }

    val Translate: ImageVector by lazy {
        svgIcon(
            name = "Translate",
            pathData = "M12.87,15.07l-2.54,-2.51l0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35C8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5l3.11,3.11L12.87,15.07zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2L18.5,10zM15.88,17l1.62,-4.33L19.12,17H15.88z",
        )
    }
}

// ============================================================================
// 4. ArtworkMeshBackdrop.kt (BitChord verbatim)
// ============================================================================

private const val MESH_GRID = 6
private const val MESH_BITMAP_SIZE = 32
private const val MESH_BLUR_RADIUS = 1
private const val MESH_BLUR_PASSES = 3
private const val MESH_VIBRANCE = 1.12f
private const val MESH_TOP_SHADOW = 0.06f
private const val MESH_BOTTOM_SHADOW = 0.30f
private const val HERO_FADE_FRACTION = 0.42f

@Immutable
internal class ArtworkMesh(
    val bitmap: ImageBitmap,
    val seam: List<Color>,
)

private val NeutralMesh: ArtworkMesh by lazy {
    val bmp = Bitmap.createBitmap(
        IntArray(MESH_BITMAP_SIZE * MESH_BITMAP_SIZE) { Color(0xFF1C1C22).toArgb() },
        MESH_BITMAP_SIZE,
        MESH_BITMAP_SIZE,
        Bitmap.Config.ARGB_8888,
    ).asImageBitmap()
    val seam = List(MESH_BITMAP_SIZE) { Color(0xFF1C1C22) }
    ArtworkMesh(bmp, seam)
}

internal fun meshOf(sourceBitmap: Bitmap): ArtworkMesh = runCatching {
    if (sourceBitmap.isRecycled) return@runCatching NeutralMesh
    val softBitmap = sourceBitmap.ensureSoftwareBitmap()
    if (softBitmap.isRecycled || softBitmap.width <= 0 || softBitmap.height <= 0) return@runCatching NeutralMesh
    val sampleDim = 48
    val scaled = if (softBitmap.width > sampleDim || softBitmap.height > sampleDim) {
        Bitmap.createScaledBitmap(softBitmap, sampleDim, sampleDim, true).ensureSoftwareBitmap()
    } else {
        softBitmap
    }
    val sw = scaled.width
    val sh = scaled.height
    if (sw <= 0 || sh <= 0) return@runCatching NeutralMesh

    val pixels = IntArray(sw * sh)
    scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)

    val grid = Array(MESH_GRID) { gy ->
        val y0 = gy * sh / MESH_GRID
        val y1 = maxOf(y0 + 1, (gy + 1) * sh / MESH_GRID)
        Array(MESH_GRID) { gx ->
            val x0 = gx * sw / MESH_GRID
            val x1 = maxOf(x0 + 1, (gx + 1) * sw / MESH_GRID)
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0
            for (y in y0 until y1) {
                val row = y * sw
                for (x in x0 until x1) {
                    val c = pixels[row + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
            }
            if (count == 0) {
                Color(0xFF1C1C22)
            } else {
                Color(
                    red = ((r / count) / 255f).coerceIn(0f, 1f),
                    green = ((g / count) / 255f).coerceIn(0f, 1f),
                    blue = ((b / count) / 255f).coerceIn(0f, 1f),
                )
            }
        }
    }

    val flipped = grid.reversedArray()
    val controls = rotatedBelowSeam(flipped)
    val sampled = resampleMesh(controls, MESH_BITMAP_SIZE)
    repeat(MESH_BLUR_PASSES) { boxBlur(sampled, MESH_BITMAP_SIZE, MESH_BLUR_RADIUS) }

    val n = MESH_BITMAP_SIZE
    val argb = IntArray(n * n)
    for (i in 0 until n * n) {
        val c = liftSaturation(sampled[i], MESH_VIBRANCE)
        sampled[i] = c
        argb[i] = c.toArgb()
    }
    val seam = List(n) { x -> sampled[x] }
    val outBitmap = Bitmap.createBitmap(argb, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    ArtworkMesh(outBitmap, seam)
}.getOrElse { NeutralMesh }

private fun rotatedBelowSeam(grid: Array<Array<Color>>): Array<Array<Color>> {
    val h = grid.size
    val w = grid[0].size
    return Array(h) { gy ->
        if (gy == 0) {
            grid[0].clone()
        } else {
            val u = (gy - 1f) / (h - 2f).coerceAtLeast(1f)
            Array(w) { gx ->
                val v = 1f - gx.toFloat() / (w - 1).coerceAtLeast(1)
                sampleGrid(grid, x = u * (w - 1), y = 1f + v * (h - 2))
            }
        }
    }
}

private fun sampleGrid(grid: Array<Array<Color>>, x: Float, y: Float): Color {
    val maxY = grid.lastIndex
    val maxX = grid[0].lastIndex
    val x0 = x.toInt().coerceIn(0, maxX)
    val y0 = y.toInt().coerceIn(0, maxY)
    val x1 = (x0 + 1).coerceAtMost(maxX)
    val y1 = (y0 + 1).coerceAtMost(maxY)
    val tx = (x - x0).coerceIn(0f, 1f)
    val ty = (y - y0).coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.lerp(
        androidx.compose.ui.graphics.lerp(grid[y0][x0], grid[y0][x1], tx),
        androidx.compose.ui.graphics.lerp(grid[y1][x0], grid[y1][x1], tx),
        ty,
    )
}

private fun resampleMesh(grid: Array<Array<Color>>, size: Int): Array<Color> {
    val gh = grid.size
    val gw = grid[0].size
    val out = Array(size * size) { Color.Black }
    for (y in 0 until size) {
        val gy = y.toFloat() * (gh - 1) / (size - 1)
        val y0 = gy.toInt().coerceIn(0, gh - 1)
        val y1 = (y0 + 1).coerceAtMost(gh - 1)
        val ty = smoothStep(gy - y0)
        for (x in 0 until size) {
            val gx = x.toFloat() * (gw - 1) / (size - 1)
            val x0 = gx.toInt().coerceIn(0, gw - 1)
            val x1 = (x0 + 1).coerceAtMost(gw - 1)
            val tx = smoothStep(gx - x0)
            val top = androidx.compose.ui.graphics.lerp(grid[y0][x0], grid[y0][x1], tx)
            val bottom = androidx.compose.ui.graphics.lerp(grid[y1][x0], grid[y1][x1], tx)
            out[y * size + x] = androidx.compose.ui.graphics.lerp(top, bottom, ty)
        }
    }
    return out
}

private fun boxBlur(pixels: Array<Color>, size: Int, radius: Int) {
    val temp = pixels.clone()
    val span = 2 * radius + 1
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (dx in -radius..radius) {
                val c = pixels[row + (x + dx).coerceIn(0, size - 1)]
                r += c.red
                g += c.green
                b += c.blue
            }
            temp[row + x] = Color(r / span, g / span, b / span)
        }
    }
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (dy in -radius..radius) {
                val c = temp[(y + dy).coerceIn(0, size - 1) * size + x]
                r += c.red
                g += c.green
                b += c.blue
            }
            pixels[row + x] = Color(r / span, g / span, b / span)
        }
    }
}

private fun liftSaturation(color: Color, factor: Float): Color {
    val luma = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return Color(
        red = (luma + (color.red - luma) * factor).coerceIn(0f, 1f),
        green = (luma + (color.green - luma) * factor).coerceIn(0f, 1f),
        blue = (luma + (color.blue - luma) * factor).coerceIn(0f, 1f),
    )
}

@Composable
internal fun ArtworkMeshBackdrop(
    mesh: ArtworkMesh?,
    seamY: Dp = 0.dp,
    animated: Boolean = PlayerPreferences.playerAnimatedGradient,
    fullPlayerHeightPx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val target = mesh ?: NeutralMesh
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val blend = remember { Animatable(1f) }

    LaunchedEffect(target) {
        if (target === to) return@LaunchedEffect
        from = to
        to = target
        blend.snapTo(0f)
        blend.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
    }

    val infiniteTransition = if (animated) {
        rememberInfiniteTransition(label = "appleMeshFluidTransition")
    } else {
        null
    }
    val phase1State = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "meshPhase1",
    )
    val phase2State = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "meshPhase2",
    )

    Canvas(modifier.fillMaxSize()) {
        val effectiveHeight = if (fullPlayerHeightPx > 1f) fullPlayerHeightPx else size.height
        val anchor = seamY.toPx().coerceIn(0f, effectiveHeight)
        val t = blend.value
        val phase1 = phase1State?.value ?: 0f
        val phase2 = phase2State?.value ?: 0f
        if (t < 1f) {
            drawAnchoredMesh(
                mesh = from,
                seamY = anchor,
                alpha = 1f,
                animated = animated,
                phase1 = phase1,
                phase2 = phase2,
                fullHeightPx = effectiveHeight,
            )
        }
        drawAnchoredMesh(
            mesh = to,
            seamY = anchor,
            alpha = t,
            animated = animated,
            phase1 = phase1,
            phase2 = phase2,
            fullHeightPx = effectiveHeight,
        )
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = MESH_TOP_SHADOW),
                1f to Color.Black.copy(alpha = MESH_BOTTOM_SHADOW),
                startY = 0f,
                endY = effectiveHeight.coerceAtLeast(1f),
            ),
        )
    }
}

private fun DrawScope.drawAnchoredMesh(
    mesh: ArtworkMesh,
    seamY: Float,
    alpha: Float,
    animated: Boolean = false,
    phase1: Float = 0f,
    phase2: Float = 0f,
    fullHeightPx: Float = 0f,
) {
    val w = size.width.roundToInt().coerceAtLeast(1)
    val h = (if (fullHeightPx > 1f) fullHeightPx else size.height).roundToInt().coerceAtLeast(1)
    val seamPx = seamY.roundToInt().coerceIn(0, h)

    if (seamPx > 0) {
        val cols = mesh.seam.size
        val colWidth = size.width / cols
        for (i in 0 until cols) {
            drawRect(
                color = mesh.seam[i],
                topLeft = Offset(i * colWidth, 0f),
                size = Size(colWidth + 1f, seamPx.toFloat()),
                alpha = alpha,
            )
        }
    }

    val meshHeight = (h - seamPx).coerceAtLeast(1)
    drawImage(
        image = mesh.bitmap,
        dstOffset = IntOffset(0, seamPx),
        dstSize = IntSize(w, meshHeight),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )

    if (animated && meshHeight > 1) {
        // Apple Music / BitChord fluid living mesh layer: slowly orbiting & breathing secondary mesh layers
        // with a graduated top fade at seamPx so any hero artwork seam remains 100% seamless
        clipRect(
            left = 0f,
            top = seamPx.toFloat(),
            right = size.width,
            bottom = size.height,
        ) {
            val driftX1 = (sin(phase1.toDouble()).toFloat() * w * 0.14f).roundToInt()
            val driftY1 = (cos(phase1.toDouble()).toFloat() * meshHeight * 0.12f).roundToInt()
            val expandW1 = (w * 1.32f).roundToInt().coerceAtLeast(1)
            val expandH1 = (meshHeight * 1.32f).roundToInt().coerceAtLeast(1)
            val offsetX1 = -((expandW1 - w) / 2) + driftX1
            val offsetY1 = seamPx - ((expandH1 - meshHeight) / 2) + driftY1

            drawImage(
                image = mesh.bitmap,
                dstOffset = IntOffset(offsetX1, offsetY1),
                dstSize = IntSize(expandW1, expandH1),
                alpha = (alpha * (0.42f + 0.14f * sin(phase2.toDouble()).toFloat())).coerceIn(0f, 1f),
                filterQuality = FilterQuality.Low,
            )

            val driftX2 = (cos(phase2.toDouble()).toFloat() * w * 0.16f).roundToInt()
            val driftY2 = (sin(phase1.toDouble() + 1.2).toFloat() * meshHeight * 0.14f).roundToInt()
            val expandW2 = (w * 1.40f).roundToInt().coerceAtLeast(1)
            val expandH2 = (meshHeight * 1.40f).roundToInt().coerceAtLeast(1)
            val offsetX2 = -((expandW2 - w) / 2) - driftX2
            val offsetY2 = seamPx - ((expandH2 - meshHeight) / 2) - driftY2

            scale(scaleX = -1f, scaleY = 1f, pivot = Offset(size.width / 2f, seamPx + meshHeight / 2f)) {
                drawImage(
                    image = mesh.bitmap,
                    dstOffset = IntOffset(offsetX2, offsetY2),
                    dstSize = IntSize(expandW2, expandH2),
                    alpha = (alpha * (0.30f + 0.12f * cos(phase1.toDouble()).toFloat())).coerceIn(0f, 1f),
                    filterQuality = FilterQuality.Low,
                )
            }

            // Re-anchor top seam graduation so hero artwork dissolves cleanly into the animated mesh
            if (seamPx > 0) {
                val gradHeight = (meshHeight * 0.22f).coerceAtLeast(24f)
                val cols = mesh.seam.size
                val colWidth = size.width / cols
                for (i in 0 until cols) {
                    val seamColor = mesh.seam[i]
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to seamColor.copy(alpha = alpha * 0.85f),
                            1f to Color.Transparent,
                            startY = seamPx.toFloat(),
                            endY = seamPx + gradHeight,
                        ),
                        topLeft = Offset(i * colWidth, seamPx.toFloat()),
                        size = Size(colWidth + 1f, gradHeight),
                    )
                }
            }
        }
    }
}

// ============================================================================
// 5. ThinSlider.kt (BitChord verbatim)
// ============================================================================

@Composable
internal fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    loading: Boolean = false,
    idleHeight: Dp = 7.dp,
    activeHeight: Dp = 12.dp,
    activeColor: Color = Color.White.copy(alpha = 0.92f),
    inactiveColor: Color = Color.White.copy(alpha = 0.26f),
) {
    var dragging by remember { mutableStateOf(false) }
    val height by animateDpAsState(
        targetValue = if (dragging) activeHeight else idleHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "sliderHeight",
    )
    var shownLoading by remember { mutableStateOf(loading) }
    var sheenStart by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(loading) {
        if (loading) {
            sheenStart = System.nanoTime()
            shownLoading = true
        } else {
            val remaining = 600L - (System.nanoTime() - sheenStart) / 1_000_000L
            if (remaining > 0) delay(remaining)
            shownLoading = false
        }
    }
    val sheenVisible = shownLoading
    val fillFactor by animateFloatAsState(
        targetValue = if (sheenVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "fillFactor",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(activeHeight + 22.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    onValueChange((down.position.x / size.width).coerceIn(0f, 1f))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        if (pointer.positionChanged()) {
                            onValueChange((pointer.position.x / size.width).coerceIn(0f, 1f))
                            pointer.consume()
                        }
                    }

                    dragging = false
                    onValueChangeFinished?.invoke()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(color = inactiveColor, cornerRadius = radius)
            val fraction = value.coerceIn(0f, 1f)
            val filled = size.width * fraction * fillFactor
            if (filled > 0f) {
                drawRoundRect(
                    color = activeColor,
                    size = Size(
                        filled.coerceAtLeast(size.height * fillFactor).coerceAtMost(size.width),
                        size.height,
                    ),
                    cornerRadius = radius,
                )
            }
        }
        AnimatedVisibility(
            visible = sheenVisible,
            enter = fadeIn(tween(durationMillis = 450, easing = FastOutSlowInEasing)) +
                scaleIn(
                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                    initialScale = 0f,
                    transformOrigin = TransformOrigin(0f, 0.5f),
                ),
            exit = fadeOut(tween(durationMillis = 450, easing = FastOutSlowInEasing)),
        ) {
            val transition = rememberInfiniteTransition(label = "mixSheen")
            val phase by transition.animateFloat(
                initialValue = -0.34f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "mixSheenPhase",
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(height),
            ) {
                val band = size.width * 0.34f
                val x = phase * size.width
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.5f to activeColor,
                            1f to Color.Transparent,
                        ),
                        startX = x,
                        endX = x + band,
                    ),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(size.height / 2f),
                )
            }
        }
    }
}

// ============================================================================
// 6. ExplicitBadge.kt & MarqueeText / CircleGlyph / PlayerControls.kt (BitChord verbatim)
// ============================================================================

private val BADGE_LETTER_SIZE = 9.5.sp
private const val BADGE_CAP_SHARE = 0.52f
private const val CAP_HEIGHT = 0.705f

@Composable
internal fun ExplicitBadge(color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(
        fontSize = BADGE_LETTER_SIZE,
        fontWeight = FontWeight.W800,
        letterSpacing = 0.sp,
    )
    val letter = remember(measurer, style) { measurer.measure("E", style) }
    val density = LocalDensity.current
    val capPx = with(density) { style.fontSize.toPx() } * CAP_HEIGHT
    val side = with(density) { (capPx / BADGE_CAP_SHARE).toDp() }
    Canvas(
        modifier
            .size(side)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        drawRoundRect(color = color, cornerRadius = CornerRadius(2.dp.toPx()))
        drawText(
            textLayoutResult = letter,
            topLeft = Offset(
                (this.size.width - letter.size.width) / 2f,
                (this.size.height + capPx) / 2f - letter.firstBaseline,
            ),
            blendMode = BlendMode.DstOut,
        )
    }
}

private const val MARQUEE_HOLD_MS = 3_000L
internal const val MARQUEE_ARTIST_STAGGER_MS = 1_500L
private val MARQUEE_GAP = 40.dp
private val MARQUEE_FADE = 16.dp
private val MARQUEE_VELOCITY = 26.dp

@Composable
internal fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    startDelayMillis: Long = 0L,
    onOverflowChange: ((Boolean) -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    },
) {
    val density = LocalDensity.current
    val gapPx = with(density) { MARQUEE_GAP.roundToPx() }
    val fadePx = with(density) { MARQUEE_FADE.toPx() }
    val velocityPxPerSec = with(density) { MARQUEE_VELOCITY.toPx() }
    val badgeGapPx = with(density) { 6.dp.roundToPx() }

    var overflow by remember(text) { mutableStateOf(false) }
    var cyclePx by remember(text) { mutableIntStateOf(0) }
    val offset = remember(text) { Animatable(0f) }

    LaunchedEffect(overflow, onOverflowChange) {
        onOverflowChange?.invoke(overflow)
    }

    LaunchedEffect(text, overflow, cyclePx, enabled, startDelayMillis) {
        offset.snapTo(0f)
        if (!enabled || !overflow || cyclePx <= 0) return@LaunchedEffect
        delay(MARQUEE_HOLD_MS + startDelayMillis)
        val durationMs = ((cyclePx / velocityPxPerSec) * 1_000f).roundToInt().coerceAtLeast(1)
        while (true) {
            offset.animateTo(
                targetValue = -cyclePx.toFloat(),
                animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
            )
            offset.snapTo(0f)
            delay(MARQUEE_HOLD_MS)
        }
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        SubcomposeLayout(
            modifier = Modifier
                .weight(1f, fill = false)
                .clipToBounds()
                .fadingEdges(
                    width = MARQUEE_FADE,
                    start = {
                        if (!overflow) 0f else {
                            val scrolled = -offset.value
                            val sinceStart = scrolled.coerceAtMost((cyclePx - scrolled).coerceAtLeast(0f))
                            (sinceStart / fadePx).coerceIn(0f, 1f)
                        }
                    },
                    end = { if (overflow) 1f else 0f },
                ),
        ) { constraints ->
            val unbounded = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
            val main = subcompose("main", content).first().measure(unbounded)
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else main.width
            val fits = main.width <= maxW || maxW <= 0
            val newOverflow = !fits
            val newCycle = if (fits) 0 else main.width + gapPx
            if (newOverflow != overflow || newCycle != cyclePx) {
                overflow = newOverflow
                cyclePx = newCycle
            }

            if (fits) {
                layout(main.width.coerceAtMost(maxW.coerceAtLeast(0)), main.height) {
                    main.placeRelative(0, 0)
                }
            } else {
                val width = maxW.coerceAtLeast(1)
                val copy = subcompose("copy", content).first().measure(unbounded)
                layout(width, main.height) {
                    val x = offset.value.roundToInt()
                    if (rtl) {
                        val base = width - main.width - x
                        main.place(base, 0)
                        copy.place(base - cyclePx, 0)
                    } else {
                        main.place(x, 0)
                        copy.place(x + cyclePx, 0)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    buttonSize: Dp = 34.dp,
    iconSize: Dp = 19.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "circleGlyphScale",
    )
    val discAlpha by animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        animationSpec = tween(durationMillis = 180),
        label = "circleGlyphDisc",
    )
    val chrome = LocalPlayerChrome.current
    val dodgeDisc = chrome && !active
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .vibrantChrome(dodgeDisc)
            .clip(CircleShape)
            .background((if (dodgeDisc) VibrantChromeInk else Color.White).copy(alpha = discAlpha))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (dodgeDisc) Color.White.copy(alpha = CHROME_FULL_ALPHA) else Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

internal val SCRUBBER_LABEL_LIFT = 9.dp
internal val VOLUME_ROW_HEIGHT = 32.dp
internal val PLAYER_SKIP_ICON_SIZE = 53.dp

private fun formatDuration(ms: Long): String {
    if (ms <= 0L || ms == C.TIME_UNSET) return "0:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
internal fun PlayerScrubber(
    positionMs: Long,
    durationMs: Long,
    loading: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    centerLabel: @Composable BoxScope.() -> Unit = {},
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val progress = if (scrubbing) {
        scrubFraction
    } else if (durationMs > 0L && durationMs != C.TIME_UNSET) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val displayMs = if (scrubbing && durationMs > 0L && durationMs != C.TIME_UNSET) {
        (scrubFraction * durationMs).toLong()
    } else {
        positionMs.coerceAtLeast(0L)
    }

    val chrome = LocalPlayerChrome.current
    ThinSlider(
        value = progress,
        onValueChange = { fraction ->
            scrubbing = true
            scrubFraction = fraction
            onScrub(fraction)
        },
        onValueChangeFinished = {
            onScrubFinished()
            scrubbing = false
        },
        loading = loading,
        idleHeight = 7.dp,
        activeHeight = 14.dp,
        activeColor = Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 0.92f),
        inactiveColor = if (chrome) VibrantChromeInk.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.26f),
        modifier = Modifier
            .height(28.dp)
            .vibrantChrome(chrome),
    )

    val timestampColor = chromeWhite(if (scrubbing) 0.85f else 0.5f)
    val timestampStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = -SCRUBBER_LABEL_LIFT),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .additiveChrome(chrome),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatDuration(displayMs),
                style = timestampStyle,
                color = timestampColor,
            )
            Text(
                text = if (durationMs > 0L && durationMs != C.TIME_UNSET) {
                    "-${formatDuration((durationMs - displayMs).coerceAtLeast(0L))}"
                } else {
                    "-0:00"
                },
                style = timestampStyle,
                color = timestampColor,
            )
        }
        centerLabel()
    }
}

@Composable
internal fun TransportRow(
    isPlaying: Boolean,
    isLoading: Boolean,
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    playSize: Dp = 74.dp,
    skipSize: Dp = PLAYER_SKIP_ICON_SIZE,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportGlyph(
            iconRes = R.drawable.ic_bitchord_previous,
            contentDescription = "Previous",
            iconSize = skipSize,
            touchSize = (skipSize + 18.dp).coerceAtLeast(52.dp),
            heightScale = 0.85f,
            nudgeDirection = -1f,
            enabled = previousEnabled,
            onClick = onPrevious,
        )

        PlayPauseGlyph(
            isPlaying = isPlaying,
            isLoading = isLoading,
            iconSize = playSize,
            touchSize = (playSize + 18.dp).coerceAtLeast(60.dp),
            onClick = onPlayPause,
        )

        TransportGlyph(
            iconRes = R.drawable.ic_bitchord_next,
            contentDescription = stringResource(R.string.play_next),
            iconSize = skipSize,
            touchSize = (skipSize + 18.dp).coerceAtLeast(52.dp),
            heightScale = 0.85f,
            nudgeDirection = 1f,
            enabled = nextEnabled,
            onClick = onNext,
        )
    }
}

@Composable
private fun PlayPauseGlyph(
    isPlaying: Boolean,
    isLoading: Boolean,
    iconSize: Dp,
    touchSize: Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "playPauseScale",
    )
    val discAlpha = remember { Animatable(0f) }
    val discScale = remember { Animatable(0.7f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    launch { discScale.snapTo(0.75f); discScale.animateTo(1f, tween(160)) }
                    launch { discAlpha.animateTo(0.22f, tween(90)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    launch { discScale.animateTo(1.08f, tween(260)) }
                    launch { discAlpha.animateTo(0f, tween(260)) }
                }
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(touchSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawBehind {
                val a = discAlpha.value
                if (a > 0f) {
                    drawCircle(
                        color = Color.White.copy(alpha = a),
                        radius = (size.minDimension / 2f) * discScale.value,
                    )
                }
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(iconSize * 0.62f),
                strokeWidth = 3.dp,
                color = Color.White,
            )
        } else {
            val enter = scaleIn(
                initialScale = 0.55f,
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis = 150))
            val exit = scaleOut(
                targetScale = 0.55f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis = 130))

            androidx.compose.animation.AnimatedVisibility(
                visible = isPlaying,
                enter = enter,
                exit = exit,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bitchord_pause),
                    contentDescription = stringResource(R.string.pause),
                    tint = Color.White,
                    modifier = Modifier.size(iconSize),
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !isPlaying,
                enter = enter,
                exit = exit,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bitchord_play),
                    contentDescription = stringResource(R.string.play),
                    tint = Color.White,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun TransportGlyph(
    iconRes: Int,
    contentDescription: String,
    iconSize: Dp,
    touchSize: Dp,
    heightScale: Float = 1f,
    nudgeDirection: Float = 0f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.84f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "transportScale",
    )
    val enabledAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.32f,
        animationSpec = tween(durationMillis = 150),
        label = "transportEnabledAlpha",
    )
    val discAlpha = remember { Animatable(0f) }
    val discScale = remember { Animatable(0.7f) }
    val nudgeX = remember { Animatable(0f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    launch { discScale.snapTo(0.75f); discScale.animateTo(1f, tween(160)) }
                    launch { discAlpha.animateTo(0.22f, tween(90)) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    launch { discScale.animateTo(1.08f, tween(260)) }
                    launch { discAlpha.animateTo(0f, tween(260)) }
                }
            }
        }
    }

    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(touchSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = nudgeX.value
            }
            .drawBehind {
                val a = discAlpha.value
                if (a > 0f) {
                    drawCircle(
                        color = Color.White.copy(alpha = a),
                        radius = (size.minDimension / 2f) * discScale.value,
                    )
                }
            }
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (nudgeDirection != 0f) {
                    scope.launch {
                        nudgeX.snapTo(nudgeDirection * 22f)
                        nudgeX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = enabledAlpha),
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { scaleY = heightScale },
        )
    }
}

@Composable
internal fun VolumeRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalPlayerChrome.current
    val iconTint = if (chrome) VibrantChromeInk.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.55f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .vibrantChrome(chrome),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = BitChordIcons.VolumeDown,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        ThinSlider(
            value = value,
            onValueChange = onValueChange,
            idleHeight = 6.dp,
            activeHeight = 10.dp,
            activeColor = Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 0.92f),
            inactiveColor = if (chrome) VibrantChromeInk.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.26f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = BitChordIcons.VolumeUp,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private val PillCorner = 12.dp
private val PillShape = RoundedCornerShape(PillCorner)
private const val PILL_TINT_ALPHA = 0.14f
private val StandaloneTileShape = RoundedCornerShape(7.dp)

@Composable
internal fun PlayerActionRow(
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    statsOpen: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    autoplayEnabled: Boolean,
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onOpenAudioOutput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repeatActive = repeatMode != Player.REPEAT_MODE_OFF
    val repeatOne = repeatMode == Player.REPEAT_MODE_ONE

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = queueOpen,
                transitionSpec = {
                    val enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                        scaleIn(initialScale = 0.9f, animationSpec = tween(220, easing = FastOutSlowInEasing))
                    val exit = fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                        scaleOut(targetScale = 0.9f, animationSpec = tween(150, easing = FastOutSlowInEasing))
                    enter togetherWith exit
                },
                contentAlignment = Alignment.Center,
                label = "centerSlot",
            ) { showQueueToggles ->
                if (showQueueToggles) {
                    val chrome = LocalPlayerChrome.current
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(PillShape)
                            .background(Color.White.copy(alpha = PILL_TINT_ALPHA))
                            .additiveChrome(chrome),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PillSegment(
                            icon = BitChordIcons.Shuffle,
                            contentDescription = "Shuffle",
                            active = shuffleEnabled,
                            onClick = onToggleShuffle,
                        )
                        PillDivider(suppress = shuffleEnabled || repeatActive)
                        PillSegment(
                            icon = BitChordIcons.Repeat,
                            contentDescription = "Repeat",
                            active = repeatActive,
                            badge = if (repeatOne) "1" else null,
                            onClick = onCycleRepeat,
                        )
                        PillDivider(suppress = repeatActive || autoplayEnabled)
                        PillSegment(
                            icon = BitChordIcons.Infinity,
                            contentDescription = "Autoplay",
                            active = autoplayEnabled,
                            onClick = onToggleAutoplay,
                        )
                    }
                } else {
                    Spacer(Modifier.height(44.dp))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionGlyph(
                    icon = BitChordIcons.LyricsQuote,
                    contentDescription = stringResource(R.string.lyrics),
                    highlighted = lyricsOpen,
                    onClick = onToggleLyrics,
                )
                ActionGlyph(
                    icon = BitChordIcons.Queue,
                    contentDescription = "Queue",
                    highlighted = queueOpen,
                    onClick = onToggleQueue,
                )
            }
        }
    }
}

@Composable
private fun PillDivider(suppress: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (suppress) 0f else 0.14f,
        animationSpec = tween(durationMillis = 180),
        label = "pillDividerAlpha",
    )
    Box(
        Modifier
            .width(1.dp)
            .height(20.dp)
            .background(Color.White.copy(alpha = alpha)),
    )
}

@Composable
private fun PillSegment(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pillSegmentScale",
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (active) 0.92f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "pillSegmentFill",
    )
    val chrome = LocalPlayerChrome.current
    val iconAlpha by animateFloatAsState(
        targetValue = if (active) 1f else (if (chrome) 0.72f - CHROME_ALPHA_DROP else 0.72f),
        animationSpec = tween(durationMillis = 180),
        label = "pillSegmentIcon",
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 44.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                if (fillAlpha > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = fillAlpha),
                        cornerRadius = CornerRadius(PillCorner.toPx()),
                        blendMode = BlendMode.SrcOut,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = iconAlpha),
            modifier = Modifier.size(21.dp),
        )
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.sp,
                ),
                color = Color.White.copy(alpha = iconAlpha),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 9.dp),
            )
        }
    }
}

@Composable
private fun ActionGlyph(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    iconSize: Dp = 26.dp,
    standaloneScale: Float = 1f,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "actionGlyphScale",
    )
    val fillAlpha by animateFloatAsState(
        targetValue = if (highlighted) 0.92f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "actionGlyphFill",
    )
    val chrome = LocalPlayerChrome.current
    val iconAlpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else (if (chrome) 0.72f - CHROME_ALPHA_DROP else 0.72f),
        animationSpec = tween(durationMillis = 180),
        label = "actionGlyphIcon",
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .size(44.dp)
            .additiveChrome(chrome)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(35.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(StandaloneTileShape)
                .drawWithContent {
                    drawContent()
                    if (fillAlpha > 0f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = fillAlpha),
                            cornerRadius = CornerRadius(7.dp.toPx()),
                            blendMode = BlendMode.SrcOut,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = iconAlpha),
                modifier = Modifier.size(iconSize * standaloneScale),
            )
        }
    }
}

// ============================================================================
// 7. LyricClock.kt & PlayerLyrics.kt (BitChord verbatim)
// ============================================================================

internal class LyricClock(startMs: Long) {
    var displayedMs: Double = startMs.toDouble()
        private set

    private var lastNowMs = Double.NaN
    private var lastReportMs = Long.MIN_VALUE
    private var anchorAtMs = Double.NaN
    private var anchorMs = 0.0
    private var rate = 1.0
    private var glideStartMs = Double.NaN
    private var glideOffsetMs = 0.0

    fun restart() {
        lastNowMs = Double.NaN
        glideStartMs = Double.NaN
    }

    fun hold(positionMs: Long, nowMs: Double) {
        restart()
        displayedMs = positionMs.toDouble()
    }

    fun frame(nowMs: Double, reportedMs: Long): Long {
        val firstOfRun = lastNowMs.isNaN()
        val isNew = reportedMs != lastReportMs
        lastReportMs = reportedMs

        if (firstOfRun) {
            lastNowMs = nowMs
            anchorAtMs = nowMs
            anchorMs = reportedMs.toDouble()
            displayedMs = anchorMs
            return displayedMs.toLong()
        }

        val dt = (nowMs - lastNowMs).coerceAtLeast(0.0)
        lastNowMs = nowMs

        if (isNew) {
            val prevExpected = anchorMs + (nowMs - anchorAtMs) * rate
            val diff = reportedMs - prevExpected
            anchorAtMs = nowMs
            anchorMs = reportedMs.toDouble()
            if (abs(diff) > 1500.0) {
                displayedMs = anchorMs
                glideStartMs = Double.NaN
                return displayedMs.toLong()
            } else if (abs(diff) > 280.0) {
                glideStartMs = nowMs
                glideOffsetMs = displayedMs - anchorMs
            }
        }

        val target = anchorMs + (nowMs - anchorAtMs) * rate
        val step = dt * rate
        val error = target - (displayedMs + step)
        when {
            !glideStartMs.isNaN() -> {
                val p = (nowMs - glideStartMs) / 260.0
                if (p >= 1.0) {
                    displayedMs = target
                    glideStartMs = Double.NaN
                } else {
                    val s = p.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
                    displayedMs = target + glideOffsetMs * (1.0 - s)
                }
            }
            abs(error) >= 2500.0 -> displayedMs = target
            else -> {
                val closing = error * (dt / (dt + 600.0))
                displayedMs += step + closing.coerceIn(-0.5 * step, 0.25 * step)
            }
        }
        return displayedMs.toLong()
    }
}

@Composable
internal fun rememberBitChordLyricClock(
    trackKey: Any,
    binder: PlayerService.Binder,
    playerState: Int,
    durationMs: Long,
    isPlaying: Boolean,
): MutableLongState {
    val clock = remember(trackKey) {
        val initialPos = if (
            binder.player.playbackState == Player.STATE_READY &&
            binder.player.duration > 0L &&
            binder.player.duration != C.TIME_UNSET
        ) {
            binder.player.currentPosition.coerceAtLeast(0L)
        } else {
            0L
        }
        mutableLongStateOf(initialPos)
    }
    val engine = remember(trackKey) { LyricClock(clock.longValue) }

    val hasValidDuration = durationMs > 0L && durationMs != C.TIME_UNSET
    val isActuallyAdvancing = isPlaying && playerState == Player.STATE_READY && hasValidDuration

    LaunchedEffect(engine, binder, isActuallyAdvancing, playerState, hasValidDuration) {
        if (!isActuallyAdvancing) {
            val rawPos = binder.player.currentPosition.coerceAtLeast(0L)
            val safeHoldPos = if (!hasValidDuration || (playerState != Player.STATE_READY && rawPos <= 1500L)) {
                0L
            } else {
                rawPos
            }
            engine.hold(safeHoldPos, System.nanoTime() / 1_000_000.0)
            clock.longValue = safeHoldPos
            return@LaunchedEffect
        }
        engine.restart()
        while (true) {
            withFrameNanos { frameNanos ->
                val nowMs = frameNanos / 1_000_000.0
                val reported = binder.player.currentPosition.coerceAtLeast(0L)
                if (binder.player.playbackState != Player.STATE_READY || !binder.player.isPlaying) {
                    val holdPos = if (binder.player.duration <= 0L || binder.player.duration == C.TIME_UNSET) 0L else reported
                    engine.hold(holdPos, nowMs)
                    clock.longValue = holdPos
                } else {
                    clock.longValue = engine.frame(nowMs = nowMs, reportedMs = reported)
                }
            }
        }
    }
    return clock
}

@Immutable
internal data class BitChordWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val charRange: IntRange,
)

@Immutable
internal data class BitChordLyricLine(
    val timeMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<BitChordWord>,
    val isGap: Boolean = false,
) {
    val isWordSynced: Boolean get() = words.isNotEmpty()
    val animatesFromMs: Long get() = (timeMs - 500L).coerceAtLeast(0L)
    val animatesUntilMs: Long get() = endMs + 800L

    fun revealedChars(positionMs: Long): Float {
        if (text.isEmpty()) return 0f
        if (positionMs <= timeMs) return 0f
        if (positionMs >= endMs) return text.length.toFloat()
        if (words.isEmpty()) {
            val dur = (endMs - timeMs).coerceAtLeast(1L)
            return ((positionMs - timeMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f) * text.length
        }
        for (i in words.indices) {
            val w = words[i]
            if (positionMs < w.startMs) {
                return w.charRange.first.toFloat()
            }
            if (positionMs <= w.endMs) {
                val dur = (w.endMs - w.startMs).coerceAtLeast(1L)
                val t = ((positionMs - w.startMs).toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                val len = (w.charRange.last - w.charRange.first + 1).coerceAtLeast(1)
                return w.charRange.first + t * len
            }
        }
        return text.length.toFloat()
    }

    fun wordLift(wordIndex: Int, positionMs: Long): Float {
        val w = words.getOrNull(wordIndex) ?: return 0f
        if (positionMs < w.startMs) return 0f
        val riseWindow = 180f
        val settleWindow = 420f
        return when {
            positionMs <= w.endMs -> {
                val t = ((positionMs - w.startMs) / riseWindow).coerceIn(0f, 1f)
                smoothStep(t)
            }
            positionMs <= w.endMs + settleWindow.toLong() -> {
                val t = ((positionMs - w.endMs) / settleWindow).coerceIn(0f, 1f)
                1f - smoothStep(t)
            }
            else -> 0f
        }
    }
}

private val LRC_LINE_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val LRC_WORD_REGEX = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>\s*([^<]+)""")

internal fun parseBitChordLyrics(
    rawLrc: String?,
    durationMs: Long,
    enableWordSync: Boolean = true,
): List<BitChordLyricLine> {
    if (rawLrc.isNullOrBlank()) return emptyList()
    data class RawEntry(val timeMs: Long, val rawBody: String)
    val entries = mutableListOf<RawEntry>()

    for (rawLine in rawLrc.lines()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue
        val matches = LRC_LINE_REGEX.findAll(trimmed).toList()
        if (matches.isEmpty()) continue
        val body = trimmed.substring(matches.last().range.last + 1).trim()
        for (m in matches) {
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues[2].toLongOrNull() ?: 0L
            val fracStr = m.groupValues[3]
            val ms = when (fracStr.length) {
                1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                3 -> fracStr.toLongOrNull() ?: 0L
                else -> 0L
            }
            entries += RawEntry(min * 60_000L + sec * 1_000L + ms, body)
        }
    }

    if (entries.isEmpty()) {
        val plain = rawLrc.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return plain.map { BitChordLyricLine(0L, 0L, it, emptyList()) }
    }

    entries.sortBy { it.timeMs }
    val result = mutableListOf<BitChordLyricLine>()

    for (i in entries.indices) {
        val entry = entries[i]
        val nextStart = entries.getOrNull(i + 1)?.timeMs
            ?: durationMs.takeIf { it > entry.timeMs }
            ?: (entry.timeMs + 4_000L)

        if (!enableWordSync) {
            val cleaned = cleanLrcText(entry.rawBody)
            if (cleaned.isEmpty() || cleaned == "♪" || cleaned == "...") {
                result += BitChordLyricLine(entry.timeMs, nextStart, "", emptyList(), isGap = true)
            } else {
                result += BitChordLyricLine(entry.timeMs, nextStart, cleaned, emptyList())
            }
            continue
        }

        val wordMatches = LRC_WORD_REGEX.findAll(entry.rawBody).toList()
        if (wordMatches.isNotEmpty()) {
            val sb = StringBuilder()
            val parsedWords = mutableListOf<Triple<Long, String, IntRange>>()
            for (wm in wordMatches) {
                val min = wm.groupValues[1].toLongOrNull() ?: 0L
                val sec = wm.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = wm.groupValues[3]
                val ms = when (fracStr.length) {
                    1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                    3 -> fracStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val wTime = min * 60_000L + sec * 1_000L + ms
                val token = cleanLrcText(wm.groupValues[4].trim())
                if (token.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append(' ')
                val startIdx = sb.length
                sb.append(token)
                val endIdx = sb.length - 1
                parsedWords += Triple(wTime, token, startIdx..endIdx)
            }
            val fullText = sb.toString().trim()
            if (fullText.isEmpty()) {
                result += BitChordLyricLine(entry.timeMs, nextStart, "", emptyList(), isGap = true)
            } else {
                val words = parsedWords.mapIndexed { idx, (wStart, token, range) ->
                    val wEnd = parsedWords.getOrNull(idx + 1)?.first ?: nextStart
                    BitChordWord(token, wStart, wEnd.coerceAtLeast(wStart + 80L), range)
                }
                result += BitChordLyricLine(entry.timeMs, words.last().endMs.coerceAtMost(nextStart), fullText, words)
            }
        } else {
            val cleaned = cleanLrcText(entry.rawBody)
            if (cleaned.isEmpty() || cleaned == "♪" || cleaned == "...") {
                result += BitChordLyricLine(entry.timeMs, nextStart, "", emptyList(), isGap = true)
            } else {
                // Synthesize natural syllable-weighted word timings so every synced song sweeps smoothly like BitChord
                val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val sb = StringBuilder()
                val ranges = mutableListOf<Pair<String, IntRange>>()
                for (tok in tokens) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    val s = sb.length
                    sb.append(tok)
                    ranges += tok to (s until sb.length)
                }
                val lineDur = (nextStart - entry.timeMs - 160L).coerceIn(400L, 6_500L)
                val weights = ranges.map { (tok, _) -> (tok.length.coerceIn(2, 10)).toFloat() }
                val totalWeight = weights.sum().coerceAtLeast(1f)
                var cursor = entry.timeMs
                val words = ranges.mapIndexed { idx, (tok, range) ->
                    val share = (lineDur * (weights[idx] / totalWeight)).toLong().coerceAtLeast(90L)
                    val wStart = cursor
                    val wEnd = if (idx == ranges.lastIndex) entry.timeMs + lineDur else (wStart + share)
                    cursor = wEnd
                    BitChordWord(tok, wStart, wEnd, range)
                }
                result += BitChordLyricLine(entry.timeMs, entry.timeMs + lineDur, sb.toString(), words)
            }
        }
    }
    return result
}

private const val UNSUNG_ALPHA = 0.45f
private const val UNSUNG_ALPHA_STRIP = 0.55f
private val WIPE_FEATHER = 30.dp
private val WORD_RISE = 2.dp
private val STRIP_LINE_HEIGHT = 24.sp
private const val LYRIC_FADE_FRACTION = 0.14f
private const val LYRIC_FADE_MIN_MS = 180f
private const val LYRIC_FADE_MAX_MS = 450f

@Composable
private fun SweptLyricLine(
    line: BitChordLyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    feather: Boolean = false,
    rise: Boolean = true,
    color: Color = Color.White,
    contentAlignment: Alignment = AbsoluteAlignment.TopLeft,
) {
    var layout by remember(line, style.textAlign) { mutableStateOf<TextLayoutResult?>(null) }

    val phase = remember(line, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> 0
                now > line.animatesUntilMs -> 2
                else -> 1
            }
        }
    }
    val drawnAt: () -> Long = {
        when (phase.value) {
            1 -> clock.longValue
            0 -> line.animatesFromMs - 1
            else -> line.animatesUntilMs + 1
        }
    }

    val riseModifier: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        val pos = drawnAt()
                        val peak = WORD_RISE.toPx()
                        var anyLifted = false
                        for (i in line.words.indices) {
                            if (line.wordLift(i, pos) > 0.01f) {
                                anyLifted = true
                                break
                            }
                        }
                        if (!anyLifted) {
                            drawContent()
                        } else {
                            for (visualLine in 0 until measured.lineCount) {
                                val lineStart = measured.getLineStart(visualLine)
                                val lineEnd = measured.getLineEnd(visualLine, visibleEnd = true)
                                val top = measured.getLineTop(visualLine)
                                val bottom = measured.getLineBottom(visualLine)
                                var edge = measured.getLineLeft(visualLine)
                                var at = lineStart
                                for (idx in line.words.indices) {
                                    val span = line.words[idx].charRange
                                    val start = maxOf(span.first, lineStart)
                                    val end = minOf(span.last + 1, lineEnd)
                                    if (start >= end) continue
                                    val lift = line.wordLift(idx, pos)
                                    if (lift <= 0.01f) continue
                                    val from = measured.getHorizontalPosition(start, usePrimaryDirection = true)
                                    val to = measured.getHorizontalPosition(end, usePrimaryDirection = true)
                                    if (to <= from) continue
                                    if (start > at) {
                                        clipRect(left = edge, top = top, right = from, bottom = bottom) {
                                            this@drawWithContent.drawContent()
                                        }
                                    }
                                    clipRect(left = from, top = top - peak, right = to, bottom = bottom) {
                                        translate(top = -lift * peak) {
                                            this@drawWithContent.drawContent()
                                        }
                                    }
                                    at = end
                                    edge = to
                                }
                                val rightEdge = measured.getLineRight(visualLine)
                                if (edge < rightEdge) {
                                    clipRect(left = edge, top = top, right = rightEdge, bottom = bottom) {
                                        this@drawWithContent.drawContent()
                                    }
                                }
                            }
                        }
                    }
                }
                .then(inner)
        }
    }

    val sweep = Modifier.drawWithContent {
        val position = drawnAt()
        when {
            position >= line.endMs -> drawContent()
            position <= line.timeMs -> Unit
            else -> layout?.let { measured ->
                if (measured.lineCount <= 0) return@let
                val textLen = measured.layoutInput.text.length
                if (textLen <= 0) return@let
                val revealed = line.revealedChars(position)
                val charIdx = revealed.toInt().coerceIn(0, textLen)
                val charFrac = (revealed - charIdx).coerceIn(0f, 1f)
                val targetLine = if (charIdx >= textLen) {
                    (measured.lineCount - 1).coerceAtLeast(0)
                } else {
                    measured.getLineForOffset(charIdx).coerceIn(0, measured.lineCount - 1)
                }
                val featherPx = if (feather) WIPE_FEATHER.toPx() else 12.dp.toPx()

                for (vLine in 0..targetLine) {
                    val top = measured.getLineTop(vLine) - WORD_RISE.toPx()
                    val bottom = measured.getLineBottom(vLine)
                    val lineLeft = measured.getLineLeft(vLine)
                    val lineRight = measured.getLineRight(vLine)
                    if (vLine < targetLine) {
                        clipRect(left = lineLeft, top = top, right = lineRight, bottom = bottom) {
                            this@drawWithContent.drawContent()
                        }
                    } else {
                        val c0 = measured.getHorizontalPosition(charIdx.coerceAtMost(textLen), usePrimaryDirection = true)
                        val c1 = if (charIdx < textLen) {
                            measured.getHorizontalPosition((charIdx + 1).coerceAtMost(textLen), usePrimaryDirection = true)
                        } else {
                            lineRight
                        }
                        val wipeX = c0 + (c1 - c0) * charFrac
                        val endX = (wipeX + featherPx * 0.5f).coerceAtMost(lineRight)
                        val startFadeX = (endX - featherPx).coerceAtLeast(lineLeft)
                        if (endX > lineLeft) {
                            clipRect(left = lineLeft, top = top, right = endX, bottom = bottom) {
                                this@drawWithContent.drawContent()
                                if (feather && endX > startFadeX + 0.5f) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            0f to Color.Black,
                                            1f to Color.Transparent,
                                            startX = startFadeX,
                                            endX = endX,
                                        ),
                                        topLeft = Offset(startFadeX, top),
                                        size = Size(endX - startFadeX, (bottom - top).coerceAtLeast(1f)),
                                        blendMode = BlendMode.DstIn,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = riseModifier(Modifier),
        )
        Text(
            text = line.text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            modifier = riseModifier(
                Modifier
                    .graphicsLayer {
                        compositingStrategy = if (feather) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                    }
                    .then(sweep),
            ),
        )
    }
}

@Composable
internal fun CurrentLyricStrip(
    lines: List<BitChordLyricLine>,
    trackKey: Any,
    clock: MutableLongState,
    durationMs: Long,
    lyricsUnavailable: Boolean,
    isLoadingLyrics: Boolean,
    onClick: () -> Unit,
) {
    val stripStyle = MaterialTheme.typography.titleMedium.copy(
        lineHeight = STRIP_LINE_HEIGHT,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

    val stripAlignment = when (PlayerPreferences.lyricsTextPosition) {
        PlayerPreferences.LyricsTextPosition.Left -> Alignment.CenterStart
        PlayerPreferences.LyricsTextPosition.Center -> Alignment.Center
        PlayerPreferences.LyricsTextPosition.Right -> Alignment.CenterEnd
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = 6.dp),
        contentAlignment = stripAlignment,
    ) {
        if (lines.isNotEmpty()) {
            val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
            if (!isSynced) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClick)
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = BitChordIcons.Instrumental,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.lyrics),
                        style = stripStyle,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = BitChordIcons.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                val index by remember(lines) {
                    derivedStateOf { lines.indexOfLast { it.timeMs <= clock.longValue } }
                }
                val current = lines.getOrNull(index)
                val instrumental = current == null || current.isGap
                val text = if (instrumental) "Instrumental" else current?.text.orEmpty()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClick)
                        .padding(vertical = 4.dp),
                ) {
                    if (instrumental) {
                        Icon(
                            imageVector = BitChordIcons.Instrumental,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    AnimatedContent(
                        targetState = Triple(index, current, text),
                        transitionSpec = {
                            val duration = 340
                            slideInVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { h -> (h * 0.35f).toInt() }
                                .togetherWith(
                                    fadeOut(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
                                        slideOutVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { h -> -(h * 0.35f).toInt() }
                                ).using(
                                    SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration, easing = FastOutSlowInEasing) })
                                )
                        },
                        label = "currentLyricTransition",
                        modifier = Modifier.weight(1f, fill = false),
                    ) { (itemIndex, lineItem, lineText) ->
                        val itemInstrumental = lineItem == null || lineItem.isGap
                        val swept = lineItem?.takeIf { !itemInstrumental && it.isWordSynced }
                        val fading = Modifier.graphicsLayer {
                            if (itemInstrumental) return@graphicsLayer
                            val start = lines.getOrNull(itemIndex)?.timeMs ?: 0L
                            val end = lines.getOrNull(itemIndex + 1)?.timeMs
                                ?: durationMs.takeIf { it > start }
                                ?: (start + 4_000L)
                            val fade = ((end - start) * LYRIC_FADE_FRACTION)
                                .coerceIn(LYRIC_FADE_MIN_MS, LYRIC_FADE_MAX_MS)
                            val remaining = (end - clock.longValue).toFloat()
                            alpha = 0.78f * (remaining / fade).coerceIn(0f, 1f)
                        }
                        if (swept != null) {
                            val sweptColor = if (PlayerPreferences.lyricsWordSyncDynamicColors) {
                                LocalAppearance.current.colorPalette.accent
                            } else {
                                Color.White
                            }
                            SweptLyricLine(
                                line = swept,
                                clock = clock,
                                style = stripStyle,
                                color = sweptColor,
                                dimAlpha = UNSUNG_ALPHA_STRIP,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                rise = false,
                                modifier = fading,
                            )
                        } else {
                            Text(
                                modifier = fading,
                                text = lineText,
                                style = stripStyle.copy(
                                    color = if (itemInstrumental) Color.White.copy(alpha = 0.5f) else Color.White,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = BitChordIcons.ChevronRight,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else if (isLoadingLyrics && !lyricsUnavailable) {
            Text(
                text = "Looking for lyrics…",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

private val LINE_FALLOFF_ALPHA_PHONE = floatArrayOf(1f, 0.74f, 0.6f, 0.48f, 0.4f, 0.34f)
private val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

@Composable
internal fun BitChordLyricsPanel(
    lines: List<BitChordLyricLine>,
    clock: MutableLongState,
    isLoading: Boolean,
    onSeekToLine: (Long) -> Unit,
    modifier: Modifier = Modifier,
    romanizations: Map<Int, String> = emptyMap(),
    isRomanizationEnabled: Boolean = false,
    translations: Map<Int, String> = emptyMap(),
    isTranslationEnabled: Boolean = false,
    onOpenProviderSelector: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    val activeIndex by remember(lines) {
        derivedStateOf {
            if (!isSynced) -1 else lines.indexOfLast { it.timeMs <= clock.longValue }
        }
    }

    val density = LocalDensity.current
    LaunchedEffect(activeIndex, lines.size) {
        if (activeIndex >= 0 && !listState.isScrollInProgress) {
            val anchorOffset = with(density) { -64.dp.roundToPx() }
            listState.animateScrollToItem(index = activeIndex.coerceAtLeast(0), scrollOffset = anchorOffset)
        }
    }

    val textPosition = PlayerPreferences.lyricsTextPosition
    val textAlign = when (textPosition) {
        PlayerPreferences.LyricsTextPosition.Left -> TextAlign.Left
        PlayerPreferences.LyricsTextPosition.Center -> TextAlign.Center
        PlayerPreferences.LyricsTextPosition.Right -> TextAlign.Right
    }
    val boxAlignment = when (textPosition) {
        PlayerPreferences.LyricsTextPosition.Left -> Alignment.CenterStart
        PlayerPreferences.LyricsTextPosition.Center -> Alignment.Center
        PlayerPreferences.LyricsTextPosition.Right -> Alignment.CenterEnd
    }
    val columnAlignment = when (textPosition) {
        PlayerPreferences.LyricsTextPosition.Left -> Alignment.Start
        PlayerPreferences.LyricsTextPosition.Center -> Alignment.CenterHorizontally
        PlayerPreferences.LyricsTextPosition.Right -> Alignment.End
    }
    val originPivotX = when (textPosition) {
        PlayerPreferences.LyricsTextPosition.Left -> 0f
        PlayerPreferences.LyricsTextPosition.Center -> 0.5f
        PlayerPreferences.LyricsTextPosition.Right -> 1f
    }

    val panelLineStyle = MaterialTheme.typography.headlineLarge.copy(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        textAlign = textAlign,
    )

    val translationLineStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val topFade = 28.dp.toPx()
                val bottomFade = 44.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black,
                        startY = 0f,
                        endY = topFade,
                    ),
                    size = Size(size.width, topFade),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startY = size.height - bottomFade,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - bottomFade),
                    size = Size(size.width, bottomFade),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        when {
            isLoading && lines.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.5.dp)
                }
            }
            lines.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.no_lyrics_found),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                    if (onOpenProviderSelector != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Change lyrics provider (${PlayerPreferences.defaultLyricsProvider.shortName})",
                            style = MaterialTheme.typography.labelLarge.copy(
                                textDecoration = TextDecoration.Underline,
                            ),
                            color = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenProviderSelector)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 28.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    horizontalAlignment = columnAlignment,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lines, key = { idx, item -> "$idx-${item.timeMs}" }) { index, line ->
                        if (line.isGap) {
                            if (index == activeIndex) {
                                val dotBaseColor = if (PlayerPreferences.lyricsWordSyncDynamicColors) {
                                    LocalAppearance.current.colorPalette.accent
                                } else {
                                    Color.White
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(vertical = 8.dp),
                                ) {
                                    repeat(3) { dotIdx ->
                                        val span = (line.endMs - line.timeMs).coerceAtLeast(1L)
                                        val p = ((clock.longValue - line.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                                        val dotProgress = ((p * 3f) - dotIdx).coerceIn(0f, 1f)
                                        Box(
                                            Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(dotBaseColor.copy(alpha = 0.25f + 0.75f * dotProgress)),
                                        )
                                    }
                                }
                            }
                        } else {
                            val dist = if (!isSynced || activeIndex < 0) 0 else abs(index - activeIndex)
                            val targetAlpha = if (!isSynced || listState.isScrollInProgress) {
                                0.85f
                            } else {
                                LINE_FALLOFF_ALPHA_PHONE[dist.coerceAtMost(LINE_FALLOFF_ALPHA_PHONE.lastIndex)]
                            }
                            val targetBlur = if (!isSynced || listState.isScrollInProgress) {
                                0.dp
                            } else {
                                LINE_FALLOFF_BLUR[dist.coerceAtMost(LINE_FALLOFF_BLUR.lastIndex)]
                            }
                            val targetScale = if (!isSynced || index == activeIndex) 1f else 0.98f

                            val animAlpha by animateFloatAsState(
                                targetValue = targetAlpha,
                                animationSpec = tween(400, easing = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)),
                                label = "lyricLineAlpha",
                            )
                            val animScale by animateFloatAsState(
                                targetValue = targetScale,
                                animationSpec = tween(400, easing = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)),
                                label = "lyricLineScale",
                            )

                            val romanizedText = if (isRomanizationEnabled) romanizations[index] else null
                            val translatedText = if (isTranslationEnabled) translations[index] else null

                            Column(
                                horizontalAlignment = columnAlignment,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = animAlpha
                                        scaleX = animScale
                                        scaleY = animScale
                                        transformOrigin = TransformOrigin(originPivotX, 0.5f)
                                    }
                                    .then(if (targetBlur > 0.dp) Modifier.blur(targetBlur, BlurredEdgeTreatment.Unbounded) else Modifier)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (isSynced) onSeekToLine(line.timeMs)
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                if (isSynced && index == activeIndex && line.isWordSynced) {
                                    val sweptColor = if (PlayerPreferences.lyricsWordSyncDynamicColors) {
                                        LocalAppearance.current.colorPalette.accent
                                    } else {
                                        Color.White
                                    }
                                    SweptLyricLine(
                                        line = line,
                                        clock = clock,
                                        style = panelLineStyle,
                                        color = sweptColor,
                                        dimAlpha = UNSUNG_ALPHA,
                                        feather = true,
                                        rise = true,
                                        contentAlignment = boxAlignment,
                                    )
                                } else {
                                    Text(
                                        text = line.text,
                                        style = panelLineStyle,
                                        color = Color.White,
                                    )
                                }
                                if (!romanizedText.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = romanizedText,
                                        style = translationLineStyle,
                                        color = Color.White.copy(alpha = 0.72f),
                                    )
                                }
                                if (!translatedText.isNullOrBlank() &&
                                    !translatedText.equals(romanizedText, ignoreCase = true)
                                ) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = translatedText,
                                        style = translationLineStyle,
                                        color = Color.White.copy(alpha = 0.64f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 8. InlineQueue (BitChord PlayerQueue.kt style)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BitChordInlineQueue(
    binder: PlayerService.Binder,
    modifier: Modifier = Modifier,
) {
    val menuState = LocalMenuState.current
    val hapticFeedback = LocalHapticFeedback.current
    var windows by remember { mutableStateOf(binder.player.currentTimeline.windows) }
    var currentIndex by remember { mutableIntStateOf(binder.player.currentMediaItemIndex) }

    DisposableEffect(binder) {
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                windows = timeline.windows
                currentIndex = binder.player.currentMediaItemIndex
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = binder.player.currentMediaItemIndex
            }
        }
        binder.player.addListener(listener)
        onDispose { binder.player.removeListener(listener) }
    }

    val listState = rememberLazyListState()
    val reorderingState = rememberReorderingState(
        lazyListState = listState,
        key = windows,
        onDragEnd = { fromIndex, toIndex ->
            binder.player.moveMediaItem(fromIndex, toIndex)
        },
        extraItemCount = 0,
    )

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < windows.size && !reorderingState.isDragging) {
            listState.scrollToItem(currentIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Playing Next",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                ),
                color = Color.White,
            )
            Text(
                text = "${(windows.size - currentIndex - 1).coerceAtLeast(0)} songs",
                style = MaterialTheme.typography.labelMedium,
                color = chromeWhite(0.55f),
            )
        }

        LookaheadScope {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(
                    items = windows,
                    key = { _, window -> window.uid.hashCode() },
                    contentType = { _, _ -> BitChordQueueContentType.Window },
                ) { idx, window ->
                    val item = window.mediaItem
                    val isCurrent = idx == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItemPlacement(reorderingState)
                            .draggedItem(
                                reorderingState = reorderingState,
                                index = idx,
                            )
                            .then(
                                if (PlayerPreferences.horizontalSwipeToRemoveItem && !isCurrent) {
                                    Modifier.swipeToClose(
                                        key = windows,
                                        delay = 100.milliseconds,
                                        requireUnconsumed = true,
                                        onClose = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            binder.player.removeMediaItem(idx)
                                        },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isCurrent) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    if (isCurrent) {
                                        if (binder.player.shouldBePlaying) {
                                            binder.player.pause()
                                        } else {
                                            binder.player.play()
                                        }
                                    } else {
                                        binder.player.seekToDefaultPosition(idx)
                                        binder.player.playWhenReady = true
                                    }
                                },
                                onLongClick = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuState.display {
                                        QueuedMediaItemMenu(
                                            mediaItem = item,
                                            indexInQueue = if (isCurrent) null else idx,
                                            onDismiss = menuState::hide,
                                        )
                                    }
                                },
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = item.mediaMetadata.artworkUri?.toString()?.thumbnail(120),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.1f)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.mediaMetadata.title?.toString().orEmpty(),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 15.sp,
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.mediaMetadata.artist?.toString().orEmpty(),
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = chromeWhite(0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .reorder(
                                    reorderingState = reorderingState,
                                    index = idx,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.reorder),
                                contentDescription = "Reorder",
                                tint = chromeWhite(0.5f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class BitChordQueueContentType {
    Window
}

// ============================================================================
// 9. Audio Output & Device Volume Helpers
// ============================================================================

@Composable
private fun rememberAudioOutputLabel(): String {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun resolveLabel(): String {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    val productName = d.productName?.toString()?.trim()
                    if (!productName.isNullOrEmpty()) return productName
                }
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    return "Headphones"
                }
            }
        }
        val deviceName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            } else null
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }
                .getOrNull()?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
        return deviceName ?: "Phone"
    }

    var label by remember { mutableStateOf(resolveLabel()) }
    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                label = resolveLabel()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                label = resolveLabel()
            }
        }
        runCatching { audioManager.registerAudioDeviceCallback(callback, null) }
        onDispose { runCatching { audioManager.unregisterAudioDeviceCallback(callback) } }
    }
    return label
}

@Composable
private fun rememberSystemVolumeState(): Pair<Float, (Float) -> Unit> {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVol = remember(audioManager) {
        runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(15).coerceAtLeast(1)
    }
    var volumeFraction by remember {
        mutableFloatStateOf(
            runCatching {
                (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
            }.getOrDefault(0.5f)
        )
    }

    DisposableEffect(context, audioManager, maxVol) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val current = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
                    volumeFraction = (current.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val setVolume: (Float) -> Unit = { newFraction ->
        val clamped = newFraction.coerceIn(0f, 1f)
        volumeFraction = clamped
        val targetStep = (clamped * maxVol).roundToInt().coerceIn(0, maxVol)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0) }
    }
    return volumeFraction to setVolume
}

// ============================================================================
// 10. BitChordNowPlayingScreen (NowPlayingScreen.kt verbatim layout & animations)
// ============================================================================

private val PLAYER_GUTTER = 30.dp
private val PLAYER_MAX_WIDTH = 420.dp
private val DISMISS_STRIP_HEIGHT = 32.dp
private val ART_BOX_TOP_PAD = 16.dp
private val HEADER_HEIGHT = 60.dp
private val ART_TITLE_GAP = 20.dp
private val THUMB_SIZE = 54.dp
private val CONTROL_GAP_SPREAD_MAX = 20.dp
private val CollapseEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val COLLAPSE_DURATION_MS = 420

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BitChordNowPlayingScreen(
    mediaItem: MediaItem,
    media: UiMedia,
    binder: PlayerService.Binder,
    shouldBePlaying: Boolean,
    position: Long,
    likedAt: Long?,
    setLikedAt: (Long?) -> Unit,
    isShowingLyrics: Boolean,
    setIsShowingLyrics: (Boolean) -> Unit,
    isShowingStatsForNerds: Boolean,
    setIsShowingStatsForNerds: (Boolean) -> Unit,
    onOpenLyricsDialog: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenSpeedDialog: () -> Unit,
    activeArtworkBitmap: Bitmap?,
    onBitmapLoaded: (Bitmap) -> Unit,
    layoutState: BottomSheetState,
    windowInsets: WindowInsets,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var queueOpen by remember { mutableStateOf(false) }
    val lyricsOpen = isShowingLyrics

    // Load high-res artwork bitmap + compute BitChord's exact 6x6 flipped+rotated 32x32 ArtworkMesh
    var loadedBitmap by remember(mediaItem.mediaId) { mutableStateOf<Bitmap?>(activeArtworkBitmap) }
    var artworkLoaded by remember(mediaItem.mediaId) { mutableStateOf(activeArtworkBitmap != null) }

    LaunchedEffect(activeArtworkBitmap) {
        if (activeArtworkBitmap != null && !activeArtworkBitmap.isRecycled) {
            loadedBitmap = activeArtworkBitmap
            artworkLoaded = true
        }
    }

    var mesh by remember { mutableStateOf<ArtworkMesh?>(null) }
    LaunchedEffect(loadedBitmap) {
        val bmp = loadedBitmap?.takeUnless { it.isRecycled } ?: return@LaunchedEffect
        mesh = withContext(Dispatchers.Default) { meshOf(bmp) }
    }

    // Fetch & parse word-by-word synced lyrics for the inline CurrentLyricStrip and full LyricsPanel
    var dbLyrics by remember(mediaItem.mediaId) { mutableStateOf<Lyrics?>(null) }
    var isLoadingLyrics by remember(mediaItem.mediaId) { mutableStateOf(false) }
    var lyricsUnavailable by remember(mediaItem.mediaId) { mutableStateOf(false) }
    var resolvedProviderName by remember(mediaItem.mediaId) { mutableStateOf<String?>(null) }
    var providerRefreshTrigger by remember(mediaItem.mediaId) { mutableIntStateOf(0) }
    var selectingLyricsProvider by remember { mutableStateOf(false) }

    // Lyrics romanization (Earth/Globe button) & translation (Translate 文A button) state — persisted across tracks like BitChord
    val isRomanizationEnabled = PlayerPreferences.lyricsRomanizationEnabled
    var romanizedLyricsMap by remember(mediaItem.mediaId) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val isTranslationEnabled = PlayerPreferences.lyricsTranslationEnabled
    var translatedLyricsMap by remember(mediaItem.mediaId) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var selectingTranslationLanguage by remember { mutableStateOf(false) }

    // Resolve artistId and albumId reliably from extras, Database, or artist name fallback
    val artistSplitRegex = remember {
        Regex("\\s*(?:,|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)
    }
    var resolvedAlbumId by remember(mediaItem.mediaId) {
        mutableStateOf(mediaItem.mediaMetadata.extras?.songBundle?.albumId?.takeIf { it.isNotBlank() })
    }
    var resolvedArtistId by remember(mediaItem.mediaId, media.artist) {
        val fromExtras = mediaItem.mediaMetadata.extras?.songBundle?.artistIds?.firstOrNull()?.takeIf { it.isNotBlank() }
        val primaryName = media.artist.split(artistSplitRegex).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        mutableStateOf(fromExtras ?: primaryName?.let { "name:$it" })
    }

    LaunchedEffect(mediaItem.mediaId, media.artist) {
        withContext(Dispatchers.IO) {
            if (resolvedAlbumId == null) {
                resolvedAlbumId = runCatching { Database.songAlbumInfo(mediaItem.mediaId)?.id }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            val dbArtists = runCatching {
                Database.songArtistInfo(mediaItem.mediaId).filter { info ->
                    val n = info.name?.trim().orEmpty()
                    n.isNotBlank() && !(':' in n && n.all { it.isDigit() || it == ':' }) && !info.id.startsWith("MPREb_")
                }
            }.getOrDefault(emptyList())

            val firstDbArtist = dbArtists.firstOrNull()
            val primaryName = firstDbArtist?.name?.split(artistSplitRegex)?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: media.artist.split(artistSplitRegex).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

            if (firstDbArtist != null && firstDbArtist.id.startsWith("UC")) {
                resolvedArtistId = firstDbArtist.id
            } else if (primaryName != null) {
                val sameName = runCatching { Database.artistsByNameExact(primaryName) }.getOrDefault(emptyList())
                val existingMatch = sameName.firstOrNull { it.id.startsWith("UC") }
                    ?: sameName.firstOrNull { it.bookmarkedAt != null }
                    ?: sameName.firstOrNull()
                resolvedArtistId = when {
                    existingMatch != null && existingMatch.id.isNotBlank() -> existingMatch.id
                    firstDbArtist != null && firstDbArtist.id.isNotBlank() -> firstDbArtist.id
                    else -> "name:$primaryName"
                }
            }
        }
    }

    LaunchedEffect(mediaItem.mediaId) {
        runCatching {
            Database.lyrics(mediaItem.mediaId).distinctUntilChanged().collect { lyr ->
                dbLyrics = lyr
            }
        }
    }

    val wordSyncEnabled = PlayerPreferences.lyricsV2Enabled
    val currentPreferredProvider = PlayerPreferences.defaultLyricsProvider
    val currentFallbackEnabled = PlayerPreferences.lyricsFallbackEnabled

    LaunchedEffect(
        mediaItem.mediaId,
        media.title,
        media.artist,
        media.duration,
        wordSyncEnabled,
        currentPreferredProvider,
        currentFallbackEnabled,
        providerRefreshTrigger,
    ) {
        val current = if (providerRefreshTrigger > 0) null else dbLyrics
        val hasExisting = current != null && (!current.synced.isNullOrBlank() || !current.fixed.isNullOrBlank())
        val needsWordSyncUpgrade = wordSyncEnabled &&
            !current?.synced.isNullOrBlank() &&
            !ExternalLyricsProviders.hasWordLevelTimestamps(current?.synced)
        if (hasExisting && !needsWordSyncUpgrade && providerRefreshTrigger == 0) {
            resolvedProviderName = ExternalLyricsProviders.getResolvedProviderName(
                videoId = mediaItem.mediaId,
                synced = current?.synced,
                fixed = current?.fixed,
                preferredProvider = currentPreferredProvider,
            )
            return@LaunchedEffect
        }
        if (!hasExisting || providerRefreshTrigger > 0) {
            isLoadingLyrics = true
        }
        lyricsUnavailable = false
        val fetchedPair = runCatching {
            withContext(Dispatchers.IO) {
                val album = mediaItem.mediaMetadata.albumTitle?.toString()
                val durationSec = if (media.duration > 0L) (media.duration / 1000L).toInt() else 0
                val directFromSelected = if (providerRefreshTrigger > 0 && currentPreferredProvider != PlayerPreferences.LyricsProvider.Auto) {
                    ExternalLyricsProviders.fetchFromSingleProvider(
                        provider = currentPreferredProvider,
                        videoId = mediaItem.mediaId,
                        title = media.title,
                        artist = media.artist,
                        album = album,
                        durationSec = durationSec,
                    )
                } else null

                val resolved = directFromSelected ?: ExternalLyricsProviders.resolveLyricsWithFallback(
                    videoId = mediaItem.mediaId,
                    title = media.title,
                    artist = media.artist,
                    album = album,
                    durationMs = media.duration.coerceAtLeast(1L),
                    preferredProvider = currentPreferredProvider,
                    fallbackEnabled = currentFallbackEnabled,
                    existingFixed = current?.fixed,
                    existingSynced = current?.synced,
                    preferWordSync = wordSyncEnabled,
                )
                if (!resolved.synced.isNullOrBlank() || !resolved.fixed.isNullOrBlank()) {
                    val model = Lyrics(
                        songId = mediaItem.mediaId,
                        fixed = resolved.fixed,
                        synced = resolved.synced,
                    )
                    val providerLabel = resolved.providerName ?: ExternalLyricsProviders.getResolvedProviderName(
                        videoId = mediaItem.mediaId,
                        synced = resolved.synced,
                        fixed = resolved.fixed,
                        preferredProvider = currentPreferredProvider,
                    )
                    transaction {
                        runCatching {
                            Database.insert(mediaItem)
                            Database.upsert(model)
                        }
                    }
                    model to providerLabel
                } else {
                    null
                }
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            null
        }
        if (fetchedPair != null) {
            dbLyrics = fetchedPair.first
            resolvedProviderName = fetchedPair.second
        } else if (dbLyrics == null) {
            lyricsUnavailable = true
        }
        isLoadingLyrics = false
    }

    val effectiveDurationMs = media.duration.takeIf { it > 0L && it != C.TIME_UNSET }
        ?: binder.player.duration.takeIf { it > 0L && it != C.TIME_UNSET }
        ?: 0L

    val rawLyricsText = dbLyrics?.synced?.takeIf { it.isNotBlank() } ?: dbLyrics?.fixed
    val parsedLyrics = remember(rawLyricsText, effectiveDurationMs, wordSyncEnabled) {
        parseBitChordLyrics(rawLyricsText, effectiveDurationMs, enableWordSync = wordSyncEnabled)
    }

    LaunchedEffect(mediaItem.mediaId, parsedLyrics, isRomanizationEnabled) {
        if (!isRomanizationEnabled || parsedLyrics.isEmpty()) {
            return@LaunchedEffect
        }
        val romanized = runCatching {
            ExternalLyricsProviders.romanizeLyricsLines(
                videoId = mediaItem.mediaId,
                lines = parsedLyrics.map { it.text },
            )
        }.getOrDefault(emptyMap())
        romanizedLyricsMap = romanized
    }

    val targetTranslationLang = PlayerPreferences.lyricsTranslationLanguage
    LaunchedEffect(mediaItem.mediaId, parsedLyrics, isTranslationEnabled, targetTranslationLang) {
        if (!isTranslationEnabled || parsedLyrics.isEmpty()) {
            return@LaunchedEffect
        }
        val translated = runCatching {
            ExternalLyricsProviders.translateLyricsLines(
                videoId = mediaItem.mediaId,
                lines = parsedLyrics.map { it.text },
                targetLangCode = targetTranslationLang,
            )
        }.getOrDefault(emptyMap())
        translatedLyricsMap = translated
    }

    var shuffleEnabled by remember { mutableStateOf(binder.player.shuffleModeEnabled) }
    var repeatMode by remember { mutableIntStateOf(binder.player.repeatMode) }
    var playerState by remember { mutableIntStateOf(binder.player.playbackState) }

    DisposableEffect(binder) {
        val listener = object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                shuffleEnabled = shuffleModeEnabled
            }
            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = mode
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                playerState = playbackState
            }
        }
        binder.player.addListener(listener)
        onDispose { binder.player.removeListener(listener) }
    }

    val lyricClock = rememberBitChordLyricClock(
        trackKey = mediaItem.mediaId,
        binder = binder,
        playerState = playerState,
        durationMs = effectiveDurationMs,
        isPlaying = shouldBePlaying,
    )

    if (selectingLyricsProvider) {
        ValueSelectorDialog(
            onDismiss = { selectingLyricsProvider = false },
            title = "Lyrics Provider",
            selectedValue = PlayerPreferences.defaultLyricsProvider,
            values = PlayerPreferences.LyricsProvider.entries.toImmutableList(),
            onValueSelect = { provider ->
                PlayerPreferences.defaultLyricsProvider = provider
                selectingLyricsProvider = false
                ExternalLyricsProviders.clearCacheFor(mediaItem.mediaId)
                dbLyrics = null
                resolvedProviderName = if (provider != PlayerPreferences.LyricsProvider.Auto) provider.shortName else null
                isLoadingLyrics = true
                lyricsUnavailable = false
                transaction {
                    runCatching {
                        Database.insert(mediaItem)
                        Database.upsert(
                            Lyrics(
                                songId = mediaItem.mediaId,
                                fixed = null,
                                synced = null,
                            )
                        )
                    }
                }
                providerRefreshTrigger++
            },
            valueText = { it.title },
        )
    }

    if (selectingTranslationLanguage) {
        val availableLanguages = remember {
            Language.entries.filter { it != Language.Auto }.toImmutableList()
        }
        val selectedLang = remember(targetTranslationLang) {
            availableLanguages.firstOrNull { it.code.equals(targetTranslationLang, ignoreCase = true) }
                ?: Language.English
        }
        ValueSelectorDialog(
            onDismiss = { selectingTranslationLanguage = false },
            title = "Translate Lyrics To",
            selectedValue = selectedLang,
            values = availableLanguages,
            onValueSelect = { lang ->
                PlayerPreferences.lyricsTranslationLanguage = lang.code
                PlayerPreferences.lyricsTranslationEnabled = true
                selectingTranslationLanguage = false
            },
            valueText = { it.name },
        )
    }

    // Collapse progress p: 0f = expanded full-bleed hero artwork, 1f = collapsed header thumbnail (for Lyrics or Queue)
    val collapseAnim = remember { Animatable(if (lyricsOpen || queueOpen) 1f else 0f) }
    LaunchedEffect(lyricsOpen || queueOpen) {
        collapseAnim.animateTo(
            targetValue = if (lyricsOpen || queueOpen) 1f else 0f,
            animationSpec = tween(durationMillis = COLLAPSE_DURATION_MS, easing = CollapseEasing),
        )
    }
    val p: () -> Float = { collapseAnim.value.coerceIn(0f, 1f) }

    // Hero full-bleed transition: 1f only when artwork is loaded AND Full screen player (edge-to-edge) is enabled in settings
    val isEdgeToEdgeArtwork = PlayerPreferences.fullScreenPlayer
    val heroT by animateFloatAsState(
        targetValue = if (artworkLoaded && isEdgeToEdgeArtwork) 1f else 0f,
        animationSpec = tween(durationMillis = COLLAPSE_DURATION_MS, easing = CollapseEasing),
        label = "heroT",
    )

    val artScale by animateFloatAsState(
        targetValue = if (shouldBePlaying) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artworkScale",
    )

    var heroHeight by remember { mutableStateOf(0.dp) }
    var playerBounds by remember { mutableStateOf(IntSize.Zero) }
    var controlSpread by remember { mutableStateOf(0.dp) }

    val statusBarTop = windowInsets.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
    val navBarBottom = windowInsets.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()

    // Context caption under the dismiss pill (e.g. "Playing from Search" / album / queue)
    var dbAlbumTitle by remember(mediaItem.mediaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaItem.mediaId) {
        Database.songAlbumTitle(mediaItem.mediaId).distinctUntilChanged().collect { title ->
            dbAlbumTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    val activeSource = PlaybackSourceState.currentSource
    val contextCaption = remember(mediaItem, activeSource, dbAlbumTitle) {
        val subtitle = mediaItem.mediaMetadata.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val source = subtitle ?: activeSource?.trim()?.takeIf { it.isNotEmpty() }
        val album = mediaItem.mediaMetadata.albumTitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: dbAlbumTitle
        val artist = mediaItem.mediaMetadata.artist?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        when {
            !source.isNullOrBlank() -> "Playing from $source"
            !album.isNullOrBlank() -> "Playing from $album"
            !artist.isNullOrBlank() -> "Playing from $artist"
            else -> "Playing from Library"
        }
    }
    val topStrip = DISMISS_STRIP_HEIGHT + 16.dp

    val (volumeFraction, setVolumeFraction) = rememberSystemVolumeState()

    val isExplicit = remember(mediaItem, media) {
        media.explicit ||
            mediaItem.mediaMetadata.extras?.songBundle?.explicit == true ||
            media.title.contains("Explicit", ignoreCase = true)
    }

    // Optional Animated Music Artwork (Apple Music editorialVideo) & Spotify Canvas
    val isAppleAnimatedArtEnabled = PlayerPreferences.animatedArtworkEnabled
    val isSpotifyCanvasEnabled = PlayerPreferences.spotifyCanvasEnabled
    val isAnyAnimatedArtEnabled = isAppleAnimatedArtEnabled || isSpotifyCanvasEnabled
    var animatedArtworkUrl by remember(
        mediaItem.mediaId,
        isAppleAnimatedArtEnabled,
        isSpotifyCanvasEnabled,
    ) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(
        mediaItem.mediaId,
        media.title,
        media.artist,
        dbAlbumTitle,
        isAppleAnimatedArtEnabled,
        isSpotifyCanvasEnabled,
    ) {
        if (!isAnyAnimatedArtEnabled || media.title.isBlank() || media.artist.isBlank()) {
            animatedArtworkUrl = null
            return@LaunchedEffect
        }
        val currentAlbum = mediaItem.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: dbAlbumTitle
        animatedArtworkUrl = withContext(Dispatchers.IO) {
            runCatching {
                val validSpotifyToken = if (isSpotifyCanvasEnabled && app.vitune.android.utils.SpotifySyncManager.isLoggedIn()) {
                    runCatching { app.vitune.android.utils.SpotifySyncManager.getValidAccessToken() }.getOrNull()
                } else {
                    DataPreferences.spotifyAccessToken.takeIf { it.isNotBlank() }
                }
                SpotifyApi.getTrackCanvasUrl(
                    title = media.title,
                    artist = media.artist,
                    spDcCookie = DataPreferences.spotifySpDcCookie.takeIf { it.isNotBlank() },
                    cachedAccessToken = validSpotifyToken,
                    album = currentAlbum,
                    preferTall = false,
                    enableAnimatedArtwork = isAppleAnimatedArtEnabled,
                    enableSpotifyCanvas = isSpotifyCanvasEnabled,
                )
            }.getOrNull()
        }
    }

    // Apple-style Next / Previous track transition animation state (exclusive to BitChordNowPlayingScreen)
    var trackChangeDirection by remember { mutableIntStateOf(1) } // +1 = Next, -1 = Previous
    var previousMediaId by remember { mutableStateOf(mediaItem.mediaId) }
    var previousMediaIndex by remember { mutableIntStateOf(binder.player.currentMediaItemIndex) }
    val trackSlideAnim = remember { Animatable(0f) }
    val trackScaleAnim = remember { Animatable(1f) }

    LaunchedEffect(mediaItem.mediaId) {
        if (mediaItem.mediaId != previousMediaId) {
            val currentIdx = binder.player.currentMediaItemIndex
            if (currentIdx != previousMediaIndex && previousMediaIndex >= 0 && currentIdx >= 0) {
                trackChangeDirection = if (currentIdx >= previousMediaIndex) 1 else -1
            }
            previousMediaId = mediaItem.mediaId
            previousMediaIndex = currentIdx

            val dir = trackChangeDirection.toFloat()
            trackSlideAnim.snapTo(dir * 96f)
            trackScaleAnim.snapTo(0.88f)
            launch {
                trackSlideAnim.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
            launch {
                trackScaleAnim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }
        }
    }

    val skipToNextWithAnimation: () -> Unit = {
        trackChangeDirection = 1
        binder.player.forceSeekToNext()
    }
    val skipToPreviousWithAnimation: () -> Unit = {
        trackChangeDirection = -1
        binder.player.forceSeekToPrevious()
    }

    CompositionLocalProvider(LocalPlayerChrome provides true) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color(0xFF141418))
                .onSizeChanged { playerBounds = it },
        ) {
            // 1. BitChord's exact 6x6 flipped+rotated 32x32 ArtworkMeshBackdrop anchored at the hero seam (or y=0 when in card/landscape mode)
            val seamY = if (isLandscape) 0.dp else heroHeight * (1f - HERO_FADE_FRACTION * 0.5f) * heroT
            ArtworkMeshBackdrop(
                mesh = mesh,
                seamY = seamY,
                animated = PlayerPreferences.playerAnimatedGradient,
                modifier = Modifier.fillMaxSize(),
            )

            if (isLandscape) {
                // =========================================================================
                // HORIZONTAL (LANDSCAPE) APPLE STYLE LAYOUT (Matches BitChord Reference)
                // =========================================================================
                val haptic = LocalHapticFeedback.current
                val landscapeSwipeOffset = remember { Animatable(0f) }

                // Top centered dismiss pill handle
                val pillInteraction = remember { MutableInteractionSource() }
                val pillPressed by pillInteraction.collectIsPressedAsState()
                val pressT by animateFloatAsState(
                    targetValue = if (pillPressed) 1f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "landscapeDismissPillPress",
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = statusBarTop.coerceAtLeast(6.dp),
                            bottom = navBarBottom.coerceAtLeast(8.dp),
                            start = 28.dp,
                            end = 28.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Centered top grab handle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clickable(
                                interactionSource = pillInteraction,
                                indication = null,
                            ) {
                                layoutState.collapseSoft()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(
                                    width = (36.dp * (1f + 0.14f * pressT)).coerceAtLeast(1.dp),
                                    height = (4.5.dp * (1f + 0.2f * pressT)).coerceAtLeast(1.dp),
                                )
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = (0.38f + 0.37f * pressT).coerceIn(0f, 1f))),
                        )
                    }

                    // Two-Column Body
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        // ---- LEFT COLUMN: Centered Square Artwork + Bottom PlayerActionRow ----
                        Column(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                val cardSide = minOf(maxWidth, maxHeight - 8.dp).coerceAtLeast(120.dp)
                                Box(
                                    modifier = Modifier
                                        .size(cardSide)
                                        .graphicsLayer {
                                            val combinedScale = artScale * trackScaleAnim.value
                                            scaleX = combinedScale
                                            scaleY = combinedScale
                                            translationX = landscapeSwipeOffset.value + trackSlideAnim.value
                                            shadowElevation = if (artworkLoaded) 12.dp.toPx() else 0f
                                            this.shape = RoundedCornerShape(12.dp)
                                            clip = true
                                        }
                                        .pointerInput(isShowingStatsForNerds) {
                                            detectTapGestures(
                                                onLongPress = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    setIsShowingStatsForNerds(true)
                                                },
                                            )
                                        }
                                        .pointerInput(Unit) {
                                            var totalDrag = 0f
                                            detectHorizontalDragGestures(
                                                onDragStart = { totalDrag = 0f },
                                                onHorizontalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    totalDrag += dragAmount
                                                    scope.launch { landscapeSwipeOffset.snapTo(totalDrag * 0.35f) }
                                                },
                                                onDragEnd = {
                                                    val threshold = size.width * 0.22f
                                                    if (totalDrag < -threshold) {
                                                        skipToNextWithAnimation()
                                                    } else if (totalDrag > threshold) {
                                                        skipToPreviousWithAnimation()
                                                    }
                                                    scope.launch {
                                                        landscapeSwipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                                    }
                                                },
                                                onDragCancel = {
                                                    scope.launch {
                                                        landscapeSwipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                                    }
                                                },
                                            )
                                        },
                                ) {
                                    val currentArtworkUri by rememberUpdatedState(mediaItem.mediaMetadata.artworkUri)
                                    AnimatedContent(
                                        targetState = mediaItem.mediaId,
                                        transitionSpec = {
                                            val dir = trackChangeDirection
                                            ((slideInHorizontally(
                                                animationSpec = spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                                initialOffsetX = { fullWidth -> dir * (fullWidth * 3 / 5) },
                                            ) + fadeIn(tween(260)) + scaleIn(
                                                initialScale = 0.86f,
                                                animationSpec = spring(
                                                    dampingRatio = 0.82f,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            )) togetherWith (slideOutHorizontally(
                                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                                                targetOffsetX = { fullWidth -> -dir * (fullWidth / 2) },
                                            ) + fadeOut(tween(200)) + scaleOut(
                                                targetScale = 0.86f,
                                                animationSpec = tween(220),
                                            ))).using(SizeTransform(clip = false))
                                        },
                                        label = "landscapeArtworkTransition",
                                    ) { artMediaId ->
                                        val artUri = currentArtworkUri
                                        val artworkUrl = remember(artMediaId, artUri) {
                                            artUri?.toString()?.thumbnail(1200)
                                        }
                                        val imageRequest = remember(context, artworkUrl) {
                                            ImageRequest.Builder(context)
                                                .data(artworkUrl)
                                                .allowHardware(false)
                                                .build()
                                        }
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            AsyncImage(
                                                model = imageRequest,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                onSuccess = { state ->
                                                    val bmp = runCatching {
                                                        state.result.image.run { toBitmap(width, height) }.ensureSoftwareBitmap()
                                                    }.getOrNull()
                                                    if (bmp != null && !bmp.isRecycled) {
                                                        loadedBitmap = bmp
                                                        artworkLoaded = true
                                                        onBitmapLoaded(bmp)
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            val activeAnimatedUrl = animatedArtworkUrl
                                            if (artMediaId == mediaItem.mediaId && isAnyAnimatedArtEnabled && !activeAnimatedUrl.isNullOrBlank()) {
                                                SpotifyCanvasVideoOverlay(
                                                    videoUrl = activeAnimatedUrl,
                                                    isPlaying = shouldBePlaying,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                            if (!artworkLoaded) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.18f)),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        imageVector = BitChordIcons.MusicNote,
                                                        contentDescription = null,
                                                        tint = Color.White.copy(alpha = 0.35f),
                                                        modifier = Modifier.size(40.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                            ) {
                                PlayerActionRow(
                                    lyricsOpen = lyricsOpen,
                                    queueOpen = queueOpen,
                                    statsOpen = isShowingStatsForNerds,
                                    shuffleEnabled = shuffleEnabled,
                                    repeatMode = repeatMode,
                                    autoplayEnabled = PlayerPreferences.trackLoopEnabled,
                                    onToggleLyrics = {
                                        if (lyricsOpen) {
                                            setIsShowingLyrics(false)
                                        } else {
                                            queueOpen = false
                                            setIsShowingLyrics(true)
                                        }
                                    },
                                    onToggleQueue = {
                                        if (queueOpen) {
                                            queueOpen = false
                                        } else {
                                            setIsShowingLyrics(false)
                                            queueOpen = true
                                        }
                                    },
                                    onToggleShuffle = {
                                        binder.player.shuffleQueue()
                                        shuffleEnabled = !shuffleEnabled
                                    },
                                    onCycleRepeat = {
                                        val nextMode = when (repeatMode) {
                                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                            else -> Player.REPEAT_MODE_OFF
                                        }
                                        binder.player.repeatMode = nextMode
                                        repeatMode = nextMode
                                    },
                                    onToggleAutoplay = {
                                        PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
                                    },
                                    onOpenAudioOutput = {
                                        setIsShowingStatsForNerds(!isShowingStatsForNerds)
                                    },
                                )
                            }
                        }

                        // ---- RIGHT COLUMN: Controls Deck OR Lyrics Panel OR Queue Panel ----
                        Box(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                lyricsOpen -> {
                                    // Reference Image 3: Full Lyrics on Right + Bottom Bar with Globe, "Lyrics by ...", Translate
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth(),
                                        ) {
                                            BitChordLyricsPanel(
                                                lines = parsedLyrics,
                                                clock = lyricClock,
                                                isLoading = isLoadingLyrics,
                                                onSeekToLine = { targetMs ->
                                                    binder.player.seekTo(targetMs)
                                                },
                                                romanizations = romanizedLyricsMap,
                                                isRomanizationEnabled = isRomanizationEnabled,
                                                translations = translatedLyricsMap,
                                                isTranslationEnabled = isTranslationEnabled,
                                                onOpenProviderSelector = { selectingLyricsProvider = true },
                                            )
                                        }

                                        // Bottom row: Globe (left), "Lyrics by ..." (center), Translate (right)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp, bottom = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (PlayerPreferences.showLyricsRomanizeButton && parsedLyrics.isNotEmpty()) {
                                                CircleGlyph(
                                                    icon = BitChordIcons.Globe,
                                                    contentDescription = "Romanize lyrics",
                                                    active = isRomanizationEnabled,
                                                    buttonSize = 36.dp,
                                                    iconSize = 19.dp,
                                                    onClick = {
                                                        PlayerPreferences.lyricsRomanizationEnabled = !isRomanizationEnabled
                                                    },
                                                    onLongClick = { selectingLyricsProvider = true },
                                                )
                                            } else {
                                                Spacer(Modifier.size(36.dp))
                                            }

                                            if (parsedLyrics.isNotEmpty()) {
                                                val displayProviderName = resolvedProviderName
                                                    ?: ExternalLyricsProviders.getResolvedProviderName(
                                                        videoId = mediaItem.mediaId,
                                                        synced = dbLyrics?.synced,
                                                        fixed = dbLyrics?.fixed,
                                                        preferredProvider = PlayerPreferences.defaultLyricsProvider,
                                                    )
                                                Text(
                                                    text = "Lyrics by $displayProviderName",
                                                    style = MaterialTheme.typography.labelLarge.copy(
                                                        textDecoration = TextDecoration.Underline,
                                                    ),
                                                    color = chromeWhite(0.55f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .clickable { selectingLyricsProvider = true }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                        .additiveChrome(LocalPlayerChrome.current),
                                                )
                                            }

                                            if (PlayerPreferences.showLyricsTranslateButton && parsedLyrics.isNotEmpty()) {
                                                CircleGlyph(
                                                    icon = BitChordIcons.Translate,
                                                    contentDescription = "Translate lyrics",
                                                    active = isTranslationEnabled,
                                                    buttonSize = 36.dp,
                                                    iconSize = 19.dp,
                                                    onClick = {
                                                        PlayerPreferences.lyricsTranslationEnabled = !isTranslationEnabled
                                                    },
                                                    onLongClick = { selectingTranslationLanguage = true },
                                                )
                                            } else {
                                                Spacer(Modifier.size(36.dp))
                                            }
                                        }
                                    }
                                }

                                queueOpen -> {
                                    // Inline Queue on Right Column
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = media.title,
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                    ),
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = media.artist,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = chromeWhite(0.55f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            val isLiked = likedAt != null
                                            CircleGlyph(
                                                icon = if (isLiked) BitChordIcons.StarFilled else BitChordIcons.Star,
                                                contentDescription = stringResource(R.string.favorites),
                                                onClick = {
                                                    setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                                                },
                                                active = isLiked,
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            CircleGlyph(
                                                icon = BitChordIcons.MoreHoriz,
                                                contentDescription = "More",
                                                onClick = onOpenMenu,
                                            )
                                        }
                                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                            BitChordInlineQueue(binder = binder)
                                        }
                                    }
                                }

                                else -> {
                                    // Reference Image 1: Default Controls Column on Right
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        // "Playing from ..." caption above the song title
                                        Text(
                                            text = contextCaption,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.62f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .padding(bottom = 4.dp)
                                                .additiveChrome(LocalPlayerChrome.current),
                                        )

                                        // Title + Artist + Star + More Row
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                var titleOverflowing by remember { mutableStateOf(false) }
                                                AnimatedContent(
                                                    targetState = Triple(mediaItem.mediaId, media.title, media.artist),
                                                    modifier = Modifier.additiveChrome(LocalPlayerChrome.current),
                                                    transitionSpec = {
                                                        val dir = trackChangeDirection
                                                        (slideInHorizontally(
                                                            animationSpec = spring(
                                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                                stiffness = Spring.StiffnessMediumLow,
                                                            ),
                                                            initialOffsetX = { fullWidth -> dir * (fullWidth / 4) },
                                                        ) + fadeIn(tween(220))) togetherWith (slideOutHorizontally(
                                                            animationSpec = tween(180),
                                                            targetOffsetX = { fullWidth -> -dir * (fullWidth / 4) },
                                                        ) + fadeOut(tween(150)))
                                                    },
                                                    label = "landscapePlayerCredits",
                                                ) { (_, songTitle, songArtist) ->
                                                    val artistColor = chromeWhite(0.55f)
                                                    Column {
                                                        MarqueeText(
                                                            text = songTitle,
                                                            style = MaterialTheme.typography.titleLarge.copy(
                                                                fontSize = 20.sp,
                                                                fontWeight = FontWeight.Bold,
                                                            ),
                                                            color = Color.White,
                                                            enabled = true,
                                                            leading = if (isExplicit) {
                                                                { ExplicitBadge(color = artistColor) }
                                                            } else {
                                                                null
                                                            },
                                                            onOverflowChange = { titleOverflowing = it },
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .clickable {
                                                                    val targetAlbumId = resolvedAlbumId
                                                                        ?: mediaItem.mediaMetadata.extras?.songBundle?.albumId?.takeIf { it.isNotBlank() }
                                                                    if (targetAlbumId != null) {
                                                                        layoutState.collapseSoft()
                                                                        albumRoute.global(targetAlbumId)
                                                                    }
                                                                },
                                                        )
                                                        MarqueeText(
                                                            text = songArtist,
                                                            style = MaterialTheme.typography.titleLarge.copy(
                                                                fontWeight = FontWeight.W500,
                                                                fontSize = 18.sp,
                                                            ),
                                                            color = artistColor,
                                                            enabled = true,
                                                            startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .clickable(enabled = songArtist.isNotBlank()) {
                                                                    val fallbackPrimary = songArtist
                                                                        .split(artistSplitRegex)
                                                                        .firstOrNull()
                                                                        ?.trim()
                                                                        ?.takeIf { it.isNotBlank() }
                                                                    val targetArtistId = resolvedArtistId
                                                                        ?: mediaItem.mediaMetadata.extras?.songBundle?.artistIds?.firstOrNull()?.takeIf { it.isNotBlank() }
                                                                        ?: fallbackPrimary?.let { "name:$it" }
                                                                    if (!targetArtistId.isNullOrBlank()) {
                                                                        layoutState.collapseSoft()
                                                                        artistRoute.global(targetArtistId)
                                                                    }
                                                                },
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(Modifier.width(10.dp))

                                            val isLiked = likedAt != null
                                            CircleGlyph(
                                                icon = if (isLiked) BitChordIcons.StarFilled else BitChordIcons.Star,
                                                contentDescription = stringResource(R.string.favorites),
                                                onClick = {
                                                    setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                                                },
                                                active = isLiked,
                                            )
                                            Spacer(Modifier.width(8.dp))

                                            CircleGlyph(
                                                icon = BitChordIcons.MoreHoriz,
                                                contentDescription = "More",
                                                onClick = onOpenMenu,
                                            )
                                        }

                                        // Inline current lyric strip directly under Title/Artist (as in Reference Image 1)
                                        val lyricSlotHeight = with(density) { STRIP_LINE_HEIGHT.toDp() + 6.dp }
                                        if (PlayerPreferences.showInlineLyrics) {
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(lyricSlotHeight),
                                                contentAlignment = Alignment.CenterStart,
                                            ) {
                                                CurrentLyricStrip(
                                                    lines = parsedLyrics,
                                                    trackKey = mediaItem.mediaId,
                                                    clock = lyricClock,
                                                    durationMs = effectiveDurationMs,
                                                    lyricsUnavailable = lyricsUnavailable,
                                                    isLoadingLyrics = isLoadingLyrics,
                                                    onClick = {
                                                        queueOpen = false
                                                        setIsShowingLyrics(true)
                                                    },
                                                )
                                            }
                                        } else {
                                            Spacer(Modifier.height(10.dp))
                                        }

                                        // Scrubber
                                        var pendingSeekFraction by remember { mutableFloatStateOf(0f) }
                                        val isTrackLoading = playerState == Player.STATE_BUFFERING
                                        val scrubberPositionMs = if (
                                            effectiveDurationMs <= 0L ||
                                            effectiveDurationMs == C.TIME_UNSET ||
                                            (isTrackLoading && lyricClock.longValue <= 1500L)
                                        ) {
                                            0L
                                        } else {
                                            lyricClock.longValue
                                        }
                                        PlayerScrubber(
                                            positionMs = scrubberPositionMs,
                                            durationMs = effectiveDurationMs,
                                            loading = isTrackLoading,
                                            onScrub = { frac -> pendingSeekFraction = frac },
                                            onScrubFinished = {
                                                if (effectiveDurationMs > 0L && effectiveDurationMs != C.TIME_UNSET) {
                                                    binder.player.seekTo((pendingSeekFraction * effectiveDurationMs).toLong())
                                                }
                                            },
                                        )

                                        Spacer(Modifier.height(10.dp))

                                        // Transport Row (Rewind, Play/Pause, Fast-Forward)
                                        TransportRow(
                                            isPlaying = shouldBePlaying,
                                            isLoading = playerState == Player.STATE_BUFFERING,
                                            previousEnabled = true,
                                            nextEnabled = true,
                                            onPrevious = skipToPreviousWithAnimation,
                                            onPlayPause = {
                                                if (shouldBePlaying) {
                                                    binder.player.pause()
                                                } else {
                                                    if (binder.player.playbackState == Player.STATE_IDLE) {
                                                        binder.player.prepare()
                                                    }
                                                    binder.player.play()
                                                }
                                            },
                                            onNext = skipToNextWithAnimation,
                                        )

                                        Spacer(Modifier.height(12.dp))

                                        // Volume Row
                                        VolumeRow(
                                            value = volumeFraction,
                                            onValueChange = setVolumeFraction,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // =========================================================================
                // PORTRAIT APPLE STYLE LAYOUT
                // =========================================================================
                // 2. Subtle top scrim over bright hero banners so status bar & "Playing from ..." stay legible
                val topScrimHeight = statusBarTop + DISMISS_STRIP_HEIGHT + 24.dp
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(topScrimHeight)
                        .graphicsLayer { alpha = (0.22f * heroT * (1f - p())).coerceIn(0f, 1f) }
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black,
                                1f to Color.Transparent,
                            ),
                        ),
                )

                // 3. Main Foreground Column (matches BitChord NowPlayingScreen.kt lines 2594-3515)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = PLAYER_GUTTER)
                        .padding(bottom = navBarBottom),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(statusBarTop))

                    // Dismiss handle + "Playing from ..." caption
                    val pillInteraction = remember { MutableInteractionSource() }
                    val pillPressed by pillInteraction.collectIsPressedAsState()
                    val pressT by animateFloatAsState(
                        targetValue = if (pillPressed) 1f else 0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "dismissPillPress",
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(topStrip)
                            .clickable(
                                interactionSource = pillInteraction,
                                indication = null,
                            ) {
                                layoutState.collapseSoft()
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(DISMISS_STRIP_HEIGHT),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(
                                        width = (36.dp * (1f + 0.14f * pressT)).coerceAtLeast(1.dp),
                                        height = (4.5.dp * (1f + 0.2f * pressT)).coerceAtLeast(1.dp),
                                    )
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = (0.38f + 0.37f * pressT).coerceIn(0f, 1f))),
                            )
                        }
                        Text(
                            text = contextCaption,
                            style = MaterialTheme.typography.labelSmall.copy(
                                shadow = Shadow(
                                    color = Color.Black.copy(alpha = (0.35f * heroT * (1f - p())).coerceIn(0f, 1f)),
                                    blurRadius = 8f,
                                ),
                            ),
                            color = Color.White.copy(alpha = (0.72f * (1f - p())).coerceIn(0f, 1f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .offset(y = (-4).dp)
                                .padding(horizontal = 24.dp),
                        )
                    }

                    // ---- Upper weighted region: Sleeve/Hero Banner, Title+Star+More Row, and Lyrics/Queue Panels ----
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = PLAYER_MAX_WIDTH)
                            .fillMaxWidth()
                            .padding(top = ART_BOX_TOP_PAD),
                    ) {
                        val safeMaxWidth = maxWidth.coerceAtLeast(THUMB_SIZE)
                        val safeMaxHeight = maxHeight.coerceAtLeast(THUMB_SIZE + HEADER_HEIGHT + ART_TITLE_GAP)
                        val naturalArt = minOf(
                            safeMaxWidth,
                            (safeMaxHeight - HEADER_HEIGHT - ART_TITLE_GAP).coerceAtLeast(THUMB_SIZE),
                        )
                        val fullArt = minOf(safeMaxWidth, naturalArt + controlSpread)
                        val slack = (safeMaxWidth - naturalArt).coerceAtLeast(0.dp)
                        val targetSpread = with(density) {
                            val half = slack.coerceAtMost(CONTROL_GAP_SPREAD_MAX * 2).toPx().div(2f).roundToInt()
                            (half * 2).toDp()
                        }
                        if (!lyricsOpen && !queueOpen && targetSpread != controlSpread) {
                            SideEffect { controlSpread = targetSpread }
                        }

                        val groupTop = (safeMaxHeight - fullArt - ART_TITLE_GAP - HEADER_HEIGHT)
                            .coerceAtLeast(0.dp) / 2

                        fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p()).coerceAtLeast(1.dp)
                        fun artTop(): Dp = lerp(groupTop, 0.dp, p())
                        fun artStart(): Dp = lerp((safeMaxWidth - fullArt) / 2, 0.dp, p())
                        fun currentTitleTop(): Dp = lerp(groupTop + fullArt + ART_TITLE_GAP, 0.dp, p())
                        fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p()).coerceAtLeast(0.dp)

                        val bannerBottom = statusBarTop + topStrip + ART_BOX_TOP_PAD +
                            groupTop + fullArt + ART_TITLE_GAP / 2
                        if ((!lyricsOpen && !queueOpen && bannerBottom != heroHeight) || heroHeight == 0.dp) {
                            SideEffect { heroHeight = bannerBottom }
                        }

                        val bannerTopPx = with(density) {
                            -(statusBarTop.roundToPx() + topStrip.roundToPx() + ART_BOX_TOP_PAD.roundToPx())
                        }
                        val boxWidthPx = constraints.maxWidth.coerceAtLeast(1)
                        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                        fun fromStart(start: Float, width: Float): Float =
                            if (rtl) boxWidthPx - start - width else start
                        fun bannerShare(): Float =
                            if (heroHeight > 0.dp && playerBounds.width > 0) heroT.coerceIn(0f, 1f) else 0f
                        fun bannerShown(): Float = (bannerShare() * (1f - p())).coerceIn(0f, 1f)
                        fun cardScale(): Float = artScale + (1f - artScale) * p()

                        fun cardRect(): Rect = with(density) {
                            val side = artSize().roundToPx().toFloat().coerceAtLeast(1f)
                            Rect(
                                offset = Offset(
                                    fromStart(artStart().roundToPx().toFloat(), side),
                                    artTop().roundToPx().toFloat(),
                                ),
                                size = Size(side, side),
                            )
                        }

                        fun sleeveRect(): Rect {
                            val card = cardRect()
                            val share = bannerShare()
                            if (share == 0f) return card
                            val banner = with(density) {
                                Rect(
                                    offset = Offset(-(playerBounds.width - boxWidthPx) / 2f, bannerTopPx.toFloat()),
                                    size = Size(
                                        playerBounds.width.toFloat().coerceAtLeast(1f),
                                        heroHeight.roundToPx().toFloat().coerceAtLeast(1f),
                                    ),
                                )
                            }
                            val thumbSide = with(density) { THUMB_SIZE.roundToPx().toFloat().coerceAtLeast(1f) }
                            val thumb = Rect(Offset(fromStart(0f, thumbSide), 0f), Size(thumbSide, thumbSide))
                            return lerp(card, lerp(banner, thumb, p()), share)
                        }

                        fun sleeveCorner(): Dp = (12.dp * (1f - bannerShown())).coerceAtLeast(0.dp)

                        // Horizontal swipe on the artwork area to skip tracks
                        val swipeOffset = remember { Animatable(0f) }

                        // The sleeve proper — full-bleed hero banner when expanded, morphing to 54dp thumbnail when collapsed
                        Box(
                            modifier = Modifier
                                .layout { measurable, layoutConstraints ->
                                    val rect = sleeveRect()
                                    val placeable = measurable.measure(
                                        Constraints.fixed(
                                            rect.width.roundToInt().coerceAtLeast(1),
                                            rect.height.roundToInt().coerceAtLeast(1),
                                        ),
                                    )
                                    layout(layoutConstraints.maxWidth.coerceAtLeast(0), 0) {
                                        placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                                    }
                                }
                                .graphicsLayer {
                                    val share = bannerShare()
                                    val idle = cardScale() * (trackScaleAnim.value + (1f - trackScaleAnim.value) * p())
                                    val scale = idle + (1f - idle) * share
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = (swipeOffset.value + trackSlideAnim.value) * (1f - p()) * (1f - share)
                                }
                                .graphicsLayer {
                                    val shown = bannerShown()
                                    shadowElevation = if (artworkLoaded) {
                                        10.dp.toPx() * (1f - 2f * shown).coerceAtLeast(0f)
                                    } else {
                                        0f
                                    }
                                    this.shape = RoundedCornerShape(sleeveCorner())
                                    clip = artworkLoaded
                                }
                                .graphicsLayer {
                                    this.shape = RoundedCornerShape(sleeveCorner())
                                    clip = true
                                    compositingStrategy = if (!isAnyAnimatedArtEnabled && bannerShown() > 0f && animatedArtworkUrl.isNullOrBlank()) {
                                        CompositingStrategy.Offscreen
                                    } else {
                                        CompositingStrategy.Auto
                                    }
                                }
                                // BitChord's exact smootherstep DstIn bottom dissolve into ArtworkMeshBackdrop
                                .drawWithContent {
                                    drawContent()
                                    val shown = bannerShown()
                                    if (!isAnyAnimatedArtEnabled && shown > 0f && size.height > 1f && animatedArtworkUrl.isNullOrBlank()) {
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                                                    val t = i / FADE_CURVE_STOPS.toFloat()
                                                    t to Color.Black.copy(alpha = (1f - shown * smootherStep(t)).coerceIn(0f, 1f))
                                                },
                                                startY = size.height * (1f - HERO_FADE_FRACTION),
                                                endY = size.height,
                                            ),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                },
                        ) {
                            val currentArtworkUri by rememberUpdatedState(mediaItem.mediaMetadata.artworkUri)
                            AnimatedContent(
                                targetState = mediaItem.mediaId,
                                transitionSpec = {
                                    val dir = trackChangeDirection
                                    ((slideInHorizontally(
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                        initialOffsetX = { fullWidth -> dir * (fullWidth * 3 / 5) },
                                    ) + fadeIn(tween(260)) + scaleIn(
                                        initialScale = 0.86f,
                                        animationSpec = spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )) togetherWith (slideOutHorizontally(
                                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                                        targetOffsetX = { fullWidth -> -dir * (fullWidth / 2) },
                                    ) + fadeOut(tween(200)) + scaleOut(
                                        targetScale = 0.86f,
                                        animationSpec = tween(220),
                                    ))).using(SizeTransform(clip = false))
                                },
                                label = "portraitArtworkTransition",
                            ) { artMediaId ->
                                val artUri = currentArtworkUri
                                val artworkUrl = remember(artMediaId, artUri) {
                                    artUri?.toString()?.thumbnail(1200)
                                }
                                val imageRequest = remember(context, artworkUrl) {
                                    ImageRequest.Builder(context)
                                        .data(artworkUrl)
                                        .allowHardware(false)
                                        .build()
                                }
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = imageRequest,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        onSuccess = { state ->
                                            val bmp = runCatching {
                                                state.result.image.run { toBitmap(width, height) }.ensureSoftwareBitmap()
                                            }.getOrNull()
                                            if (bmp != null && !bmp.isRecycled) {
                                                loadedBitmap = bmp
                                                artworkLoaded = true
                                                onBitmapLoaded(bmp)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    val activeAnimatedUrl = animatedArtworkUrl
                                    if (artMediaId == mediaItem.mediaId && isAnyAnimatedArtEnabled && !activeAnimatedUrl.isNullOrBlank()) {
                                        SpotifyCanvasVideoOverlay(
                                            videoUrl = activeAnimatedUrl,
                                            isPlaying = shouldBePlaying,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .graphicsLayer {
                                                    alpha = (1f - p() * 1.4f).coerceIn(0f, 1f)
                                                },
                                        )
                                    }
                                    if (!artworkLoaded) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.18f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = BitChordIcons.MusicNote,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.35f),
                                                modifier = Modifier.size(40.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Sibling leaf Offscreen layer that dissolves the bottom HERO_FADE_FRACTION (42%) of the hero banner
                        // (including hardware TextureView Apple Music / Spotify Canvas videos) pixel-for-pixel into ArtworkMeshBackdrop
                        if (isAnyAnimatedArtEnabled || !animatedArtworkUrl.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .layout { measurable, layoutConstraints ->
                                        val rect = sleeveRect()
                                        val placeable = measurable.measure(
                                            Constraints.fixed(
                                                rect.width.roundToInt().coerceAtLeast(1),
                                                rect.height.roundToInt().coerceAtLeast(1),
                                            ),
                                        )
                                        layout(layoutConstraints.maxWidth.coerceAtLeast(0), 0) {
                                            placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                                        }
                                    }
                                    .graphicsLayer {
                                        val share = bannerShare()
                                        val idle = cardScale() * (trackScaleAnim.value + (1f - trackScaleAnim.value) * p())
                                        val scale = idle + (1f - idle) * share
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = (swipeOffset.value + trackSlideAnim.value) * (1f - p()) * (1f - share)
                                    }
                                    .graphicsLayer {
                                        this.shape = RoundedCornerShape(sleeveCorner())
                                        clip = true
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    }
                                    .drawWithContent {
                                        val shown = bannerShown()
                                        if (shown > 0f && size.height > 1f) {
                                            drawContent()
                                            val fadeStartY = size.height * (1f - HERO_FADE_FRACTION)
                                            drawRect(
                                                color = Color.Transparent,
                                                topLeft = Offset.Zero,
                                                size = Size(size.width, fadeStartY),
                                                blendMode = BlendMode.Clear,
                                            )
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    colorStops = Array(FADE_CURVE_STOPS + 1) { i ->
                                                        val t = i / FADE_CURVE_STOPS.toFloat()
                                                        t to Color.Black.copy(alpha = (shown * smootherStep(t)).coerceIn(0f, 1f))
                                                    },
                                                    startY = fadeStartY,
                                                    endY = size.height,
                                                ),
                                                topLeft = Offset(0f, fadeStartY),
                                                size = Size(size.width, (size.height - fadeStartY).coerceAtLeast(1f)),
                                                blendMode = BlendMode.DstIn,
                                            )
                                        }
                                    },
                            ) {
                                ArtworkMeshBackdrop(
                                    mesh = mesh,
                                    seamY = seamY,
                                    animated = PlayerPreferences.playerAnimatedGradient,
                                    fullPlayerHeightPx = playerBounds.height.toFloat(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        // Card gesture slot (handles swipe left/right to skip, long-press for Stats for Nerds, & tap when collapsed)
                        val haptic = LocalHapticFeedback.current
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(artStart().roundToPx(), artTop().roundToPx()) }
                                .layout { measurable, layoutConstraints ->
                                    val side = artSize().roundToPx().coerceAtLeast(1)
                                    val placeable = measurable.measure(
                                        layoutConstraints.constrain(Constraints.fixed(side, side)),
                                    )
                                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                                }
                                .pointerInput(lyricsOpen, queueOpen, isShowingStatsForNerds) {
                                    detectTapGestures(
                                        onTap = {
                                            if (queueOpen || lyricsOpen) {
                                                queueOpen = false
                                                setIsShowingLyrics(false)
                                            }
                                        },
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            setIsShowingStatsForNerds(true)
                                        },
                                    )
                                }
                                .pointerInput(lyricsOpen, queueOpen) {
                                    if (!lyricsOpen && !queueOpen) {
                                        var totalDrag = 0f
                                        detectHorizontalDragGestures(
                                            onDragStart = { totalDrag = 0f },
                                            onHorizontalDrag = { change, dragAmount ->
                                                change.consume()
                                                totalDrag += dragAmount
                                                scope.launch { swipeOffset.snapTo(totalDrag * 0.35f) }
                                            },
                                            onDragEnd = {
                                                val threshold = size.width * 0.22f
                                                if (totalDrag < -threshold) {
                                                    skipToNextWithAnimation()
                                                } else if (totalDrag > threshold) {
                                                    skipToPreviousWithAnimation()
                                                }
                                                scope.launch {
                                                    swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                                }
                                            },
                                            onDragCancel = {
                                                scope.launch {
                                                    swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                                }
                                            },
                                        )
                                    }
                                },
                        )

                        // ---- Title + Artist + Star + More Row ----
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset {
                                    IntOffset(
                                        x = 0,
                                        y = (
                                            currentTitleTop() -
                                                lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, p())
                                        ).roundToPx(),
                                    )
                                }
                                .layout { measurable, layoutConstraints ->
                                    val start = titleStart().roundToPx()
                                    val placeable = measurable.measure(layoutConstraints.offset(horizontal = -start))
                                    layout(
                                        layoutConstraints.constrainWidth(placeable.width + start),
                                        layoutConstraints.constrainHeight(placeable.height),
                                    ) { placeable.placeRelative(start, 0) }
                                }
                                .height(HEADER_HEIGHT),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        val scale = 1f - 0.2f * p()
                                        scaleX = scale
                                        scaleY = scale
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    },
                            ) {
                                var titleOverflowing by remember { mutableStateOf(false) }
                                val scrolling = !lyricsOpen && !queueOpen && p() < 0.05f

                                AnimatedContent(
                                    targetState = Triple(mediaItem.mediaId, media.title, media.artist),
                                    modifier = Modifier.additiveChrome(LocalPlayerChrome.current),
                                    transitionSpec = {
                                        val dir = trackChangeDirection
                                        (slideInHorizontally(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioNoBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                            initialOffsetX = { fullWidth -> dir * (fullWidth / 4) },
                                        ) + fadeIn(tween(220))) togetherWith (slideOutHorizontally(
                                            animationSpec = tween(180),
                                            targetOffsetX = { fullWidth -> -dir * (fullWidth / 4) },
                                        ) + fadeOut(tween(150)))
                                    },
                                    label = "playerCredits",
                                ) { (_, songTitle, songArtist) ->
                                    val artistColor = chromeWhite(0.55f)
                                    Column {
                                        MarqueeText(
                                            text = songTitle,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                            color = Color.White,
                                            enabled = scrolling,
                                            leading = if (isExplicit) {
                                                { ExplicitBadge(color = artistColor) }
                                            } else {
                                                null
                                            },
                                            onOverflowChange = { titleOverflowing = it },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable {
                                                    val targetAlbumId = resolvedAlbumId
                                                        ?: mediaItem.mediaMetadata.extras?.songBundle?.albumId?.takeIf { it.isNotBlank() }
                                                    if (targetAlbumId != null) {
                                                        layoutState.collapseSoft()
                                                        albumRoute.global(targetAlbumId)
                                                    }
                                                },
                                        )
                                        MarqueeText(
                                            text = songArtist,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.W500,
                                                fontSize = 20.sp,
                                            ),
                                            color = artistColor,
                                            enabled = scrolling,
                                            startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable(enabled = songArtist.isNotBlank()) {
                                                    val fallbackPrimary = songArtist
                                                        .split(artistSplitRegex)
                                                        .firstOrNull()
                                                        ?.trim()
                                                        ?.takeIf { it.isNotBlank() }
                                                    val targetArtistId = resolvedArtistId
                                                        ?: mediaItem.mediaMetadata.extras?.songBundle?.artistIds?.firstOrNull()?.takeIf { it.isNotBlank() }
                                                        ?: fallbackPrimary?.let { "name:$it" }
                                                    if (!targetArtistId.isNullOrBlank()) {
                                                        layoutState.collapseSoft()
                                                        artistRoute.global(targetArtistId)
                                                    }
                                                },
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            val isLiked = likedAt != null
                            CircleGlyph(
                                icon = if (isLiked) BitChordIcons.StarFilled else BitChordIcons.Star,
                                contentDescription = stringResource(R.string.favorites),
                                onClick = {
                                    setLikedAt(if (likedAt == null) System.currentTimeMillis() else null)
                                },
                                active = isLiked,
                            )
                            Spacer(Modifier.width(8.dp))

                            CircleGlyph(
                                icon = BitChordIcons.MoreHoriz,
                                contentDescription = "More",
                                onClick = onOpenMenu,
                            )
                        }

                        // ---- Full Lyrics Panel (when lyricsOpen) ----
                        val panelAlpha by animateFloatAsState(
                            targetValue = if ((lyricsOpen || queueOpen) && p() > 0.7f) 1f else 0f,
                            animationSpec = tween(durationMillis = 220),
                            label = "panelAlpha",
                        )
                        if (lyricsOpen && p() > 0.5f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = HEADER_HEIGHT)
                                    .graphicsLayer { alpha = panelAlpha },
                            ) {
                                BitChordLyricsPanel(
                                    lines = parsedLyrics,
                                    clock = lyricClock,
                                    isLoading = isLoadingLyrics,
                                    onSeekToLine = { targetMs ->
                                        binder.player.seekTo(targetMs)
                                    },
                                    romanizations = romanizedLyricsMap,
                                    isRomanizationEnabled = isRomanizationEnabled,
                                    translations = translatedLyricsMap,
                                    isTranslationEnabled = isTranslationEnabled,
                                    onOpenProviderSelector = { selectingLyricsProvider = true },
                                )

                                // BitChord floating circular controls at bottom of lyrics view:
                                // Bottom-Left: Earth/Globe button (🌐) to toggle Romanized Lyrics (long-press to change lyrics provider)
                                // Bottom-Right: Translate button (文A) to toggle Translated Lyrics (long-press to select target language)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (PlayerPreferences.showLyricsRomanizeButton && parsedLyrics.isNotEmpty()) {
                                        CircleGlyph(
                                            icon = BitChordIcons.Globe,
                                            contentDescription = "Romanize lyrics",
                                            active = isRomanizationEnabled,
                                            buttonSize = 38.dp,
                                            iconSize = 20.dp,
                                            onClick = {
                                                PlayerPreferences.lyricsRomanizationEnabled = !isRomanizationEnabled
                                            },
                                            onLongClick = { selectingLyricsProvider = true },
                                        )
                                    } else {
                                        Spacer(Modifier.size(38.dp))
                                    }
                                    if (PlayerPreferences.showLyricsTranslateButton && parsedLyrics.isNotEmpty()) {
                                        CircleGlyph(
                                            icon = BitChordIcons.Translate,
                                            contentDescription = "Translate lyrics",
                                            active = isTranslationEnabled,
                                            buttonSize = 38.dp,
                                            iconSize = 20.dp,
                                            onClick = {
                                                PlayerPreferences.lyricsTranslationEnabled = !isTranslationEnabled
                                            },
                                            onLongClick = { selectingTranslationLanguage = true },
                                        )
                                    } else {
                                        Spacer(Modifier.size(38.dp))
                                    }
                                }
                            }
                        }

                        // ---- Inline Queue Panel (when queueOpen) ----
                        if (!lyricsOpen && queueOpen && p() > 0.5f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = HEADER_HEIGHT)
                                    .graphicsLayer { alpha = panelAlpha },
                            ) {
                                BitChordInlineQueue(binder = binder)
                            }
                        }
                    }

                    // ---- Bottom Control Deck: CurrentLyricStrip, Scrubber, Transport, Volume, Action Row ----
                    Column(
                        modifier = Modifier
                            .widthIn(max = PLAYER_MAX_WIDTH)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val lyricSlotHeight = with(density) { STRIP_LINE_HEIGHT.toDp() + 8.dp }
                        if (PlayerPreferences.showInlineLyrics || lyricsOpen) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(lyricSlotHeight),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (!lyricsOpen) {
                                    CurrentLyricStrip(
                                        lines = parsedLyrics,
                                        trackKey = mediaItem.mediaId,
                                        clock = lyricClock,
                                        durationMs = effectiveDurationMs,
                                        lyricsUnavailable = lyricsUnavailable,
                                        isLoadingLyrics = isLoadingLyrics,
                                        onClick = {
                                            queueOpen = false
                                            setIsShowingLyrics(true)
                                        },
                                    )
                                } else if (parsedLyrics.isNotEmpty()) {
                                    val displayProviderName = resolvedProviderName
                                        ?: ExternalLyricsProviders.getResolvedProviderName(
                                            videoId = mediaItem.mediaId,
                                            synced = dbLyrics?.synced,
                                            fixed = dbLyrics?.fixed,
                                            preferredProvider = PlayerPreferences.defaultLyricsProvider,
                                        )
                                    Text(
                                        text = "Lyrics by $displayProviderName",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                        color = chromeWhite(0.55f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .offset(y = 6.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { selectingLyricsProvider = true }
                                            .padding(vertical = 4.dp)
                                            .additiveChrome(LocalPlayerChrome.current),
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.height(10.dp))
                        }

                        var pendingSeekFraction by remember { mutableFloatStateOf(0f) }
                        val isTrackLoading = playerState == Player.STATE_BUFFERING
                        val scrubberPositionMs = if (
                            effectiveDurationMs <= 0L ||
                            effectiveDurationMs == C.TIME_UNSET ||
                            (isTrackLoading && lyricClock.longValue <= 1500L)
                        ) {
                            0L
                        } else {
                            lyricClock.longValue
                        }
                        PlayerScrubber(
                            positionMs = scrubberPositionMs,
                            durationMs = effectiveDurationMs,
                            loading = isTrackLoading,
                            onScrub = { frac -> pendingSeekFraction = frac },
                            onScrubFinished = {
                                if (effectiveDurationMs > 0L && effectiveDurationMs != C.TIME_UNSET) {
                                    binder.player.seekTo((pendingSeekFraction * effectiveDurationMs).toLong())
                                }
                            },
                        )

                        Spacer(Modifier.height(8.dp + controlSpread / 2))

                        TransportRow(
                            isPlaying = shouldBePlaying,
                            isLoading = playerState == Player.STATE_BUFFERING,
                            previousEnabled = true,
                            nextEnabled = true,
                            onPrevious = skipToPreviousWithAnimation,
                            onPlayPause = {
                                if (shouldBePlaying) {
                                    binder.player.pause()
                                } else {
                                    if (binder.player.playbackState == Player.STATE_IDLE) {
                                        binder.player.prepare()
                                    }
                                    binder.player.play()
                                }
                            },
                            onNext = skipToNextWithAnimation,
                        )

                        Spacer(Modifier.height(12.dp + controlSpread / 2))

                        VolumeRow(
                            value = volumeFraction,
                            onValueChange = setVolumeFraction,
                        )

                        Spacer(Modifier.height(6.dp))

                        PlayerActionRow(
                            lyricsOpen = lyricsOpen,
                            queueOpen = queueOpen,
                            statsOpen = isShowingStatsForNerds,
                            shuffleEnabled = shuffleEnabled,
                            repeatMode = repeatMode,
                            autoplayEnabled = PlayerPreferences.trackLoopEnabled,
                            onToggleLyrics = {
                                if (lyricsOpen) {
                                    setIsShowingLyrics(false)
                                } else {
                                    queueOpen = false
                                    setIsShowingLyrics(true)
                                }
                            },
                            onToggleQueue = {
                                if (queueOpen) {
                                    queueOpen = false
                                } else {
                                    setIsShowingLyrics(false)
                                    queueOpen = true
                                }
                            },
                            onToggleShuffle = {
                                binder.player.shuffleQueue()
                                shuffleEnabled = !shuffleEnabled
                            },
                            onCycleRepeat = {
                                val nextMode = when (repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                binder.player.repeatMode = nextMode
                                repeatMode = nextMode
                            },
                            onToggleAutoplay = {
                                PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
                            },
                            onOpenAudioOutput = {
                                setIsShowingStatsForNerds(!isShowingStatsForNerds)
                            },
                        )

                        Spacer(Modifier.height(18.dp))
                    }
                }
            }

            // Stats for Nerds overlay on Apple Style layout (Portrait & Landscape)
            StatsForNerds(
                mediaId = mediaItem.mediaId,
                isDisplayed = isShowingStatsForNerds,
                onDismiss = { setIsShowingStatsForNerds(false) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
