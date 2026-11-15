package app.vitune.android.ui.screens.player

import android.content.ClipData
import android.content.ClipDescription
import android.text.format.Formatter
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Format
import app.vitune.android.utils.bold
import app.vitune.android.utils.color
import app.vitune.android.utils.medium
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.toast
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.onOverlay
import app.vitune.core.ui.overlay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun StatsForNerds(
    mediaId: String,
    isDisplayed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) = AnimatedVisibility(
    visible = isDisplayed,
    enter = fadeIn(),
    exit = fadeOut()
) {
    val (colorPalette, typography) = LocalAppearance.current
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    val clipboardManager = LocalClipboard.current

    val coroutineScope = rememberCoroutineScope()

    var cachedBytes by remember(binder, mediaId) {
        mutableLongStateOf(binder?.cache?.getCachedBytes(mediaId, 0, -1) ?: 0L)
    }

    var format by remember { mutableStateOf<Format?>(null) }

    LaunchedEffect(mediaId) {
        Database
            .format(mediaId)
            .distinctUntilChanged()
            .collectLatest { currentFormat ->
                if (currentFormat?.itag != null || currentFormat?.mimeType != null) {
                    format = currentFormat
                }
            }
    }

    DisposableEffect(binder, mediaId) {
        val currentBinder = binder ?: return@DisposableEffect onDispose { }

        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) {
                cachedBytes += span.length
            }

            override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
                cachedBytes -= span.length
            }

            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }

        currentBinder.cache.addListener(mediaId, listener)

        onDispose {
            currentBinder.cache.removeListener(mediaId, listener)
        }
    }

    val mediaMetadata = binder?.player?.currentMediaItem?.mediaMetadata
    val trackTitle = mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
    val trackArtist = mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }
    val trackAlbum = mediaMetadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }

    val bitrateText = when {
        format?.contentLength?.let { cl -> cl > 0 && binder?.player?.duration != null && binder.player.duration > 0 } == true -> {
            val durationInSeconds = binder!!.player.duration / 1000.0
            val sizeInKilobits = (format!!.contentLength!! * 8) / 1000.0
            val averageBitrate = sizeInKilobits / durationInSeconds
            stringResource(R.string.format_kbps, averageBitrate.toLong())
        }
        format?.bitrate?.let { br -> br > 0 } == true ->
            stringResource(R.string.format_kbps, format!!.bitrate!! / 1000L)
        else -> stringResource(R.string.unknown)
    }

    val codecBadge = remember(format) {
        format?.mimeType?.let { mime ->
            when {
                mime.contains("opus", ignoreCase = true) -> "OPUS"
                mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true) -> "AAC"
                mime.contains("flac", ignoreCase = true) -> "FLAC"
                mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true) -> "MP3"
                else -> mime.substringAfter("/").substringBefore(";").uppercase()
            }
        } ?: "AUDIO"
    }

    val cacheFraction = remember(cachedBytes, format?.contentLength) {
        val total = format?.contentLength ?: 0L
        if (total > 0L) (cachedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .background(colorPalette.overlay.copy(alpha = 0.84f))
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colorPalette.background1.copy(alpha = 0.92f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = "Track & Stream Metadata",
                        style = typography.s.bold.color(colorPalette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!trackTitle.isNullOrBlank()) {
                        BasicText(
                            text = buildString {
                                append(trackTitle)
                                if (!trackArtist.isNullOrBlank()) append(" • $trackArtist")
                            },
                            style = typography.xxs.medium.color(colorPalette.textSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colorPalette.accent.copy(alpha = 0.18f))
                        .border(
                            width = 1.dp,
                            color = colorPalette.accent.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(50)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    BasicText(
                        text = codecBadge,
                        style = typography.xxs.bold.color(colorPalette.accent)
                    )
                }
            }

            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                thickness = 1.dp
            )

            @Composable
            fun MetadataRow(
                label: String,
                value: String,
                onClick: (() -> Unit)? = null,
                badgeText: String? = null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = label,
                        style = typography.xxs.medium.color(colorPalette.textSecondary),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.weight(1f)
                    ) {
                        BasicText(
                            text = value,
                            style = typography.xs.semiBold.color(colorPalette.text).copy(
                                textAlign = TextAlign.End
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (badgeText != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colorPalette.background2)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                BasicText(
                                    text = badgeText,
                                    style = typography.xxs.semiBold.color(colorPalette.accent)
                                )
                            }
                        }
                    }
                }
            }

            if (!trackAlbum.isNullOrBlank()) {
                MetadataRow(
                    label = "Album",
                    value = trackAlbum
                )
            }

            MetadataRow(
                label = stringResource(R.string.id),
                value = mediaId,
                badgeText = "Copy",
                onClick = {
                    coroutineScope.launch {
                        clipboardManager.setText(mediaId)
                        context.toast("Copied Track ID")
                    }
                }
            )

            MetadataRow(
                label = stringResource(R.string.itag),
                value = format?.itag?.toString() ?: stringResource(R.string.unknown)
            )

            MetadataRow(
                label = stringResource(R.string.bitrate),
                value = bitrateText
            )

            MetadataRow(
                label = "MIME / Codec",
                value = format?.mimeType ?: stringResource(R.string.unknown)
            )

            MetadataRow(
                label = stringResource(R.string.loudness),
                value = format?.loudnessDb?.let {
                    stringResource(R.string.format_db, "%.2f".format(it))
                } ?: stringResource(R.string.unknown)
            )

            MetadataRow(
                label = stringResource(R.string.size),
                value = when (val length = format?.contentLength) {
                    null, 0L -> stringResource(R.string.unknown)
                    else -> Formatter.formatShortFileSize(context, length)
                }
            )

            MetadataRow(
                label = stringResource(R.string.cached),
                value = buildString {
                    append(Formatter.formatShortFileSize(context, cachedBytes))
                    format?.contentLength?.takeIf { it > 0 }?.let {
                        append(" (${(cachedBytes.toFloat() / it * 100).roundToInt().coerceIn(0, 100)}%)")
                    }
                }
            )

            if (cacheFraction != null) {
                Spacer(modifier = Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { cacheFraction },
                    color = colorPalette.accent,
                    trackColor = colorPalette.background2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                )
            }
        }
    }
}

suspend fun Clipboard.setText(
    text: String,
    description: String? = null,
    mimeTypes: List<String> = listOf("text/plain")
) = setClipEntry(
    ClipEntry(
        ClipData(
            /* description = */ ClipDescription(
                /* label = */ description ?: text,
                /* mimeTypes = */ mimeTypes.toTypedArray()
            ),
            /* item = */ ClipData.Item(text)
        )
    )
)
