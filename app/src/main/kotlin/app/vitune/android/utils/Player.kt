package app.vitune.android.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import app.vitune.android.preferences.AppearancePreferences
import app.vitune.core.ui.utils.songBundle
import kotlin.time.Duration

object PlaybackSourceState {
    var currentSource by mutableStateOf<String?>(null)
}

fun MediaItem.withPlaybackSource(source: String?): MediaItem {
    val clean = source?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    return buildUpon()
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setSubtitle(clean)
                .build()
        )
        .build()
}

val Player.currentWindow: Timeline.Window?
    get() = if (mediaItemCount == 0) null else currentTimeline[currentMediaItemIndex]

inline val Timeline.windows: List<Timeline.Window>
    get() = List(windowCount) { this[it] }

inline val Timeline.mediaItems: List<MediaItem>
    get() = windows.map { it.mediaItem }

val Player.shouldBePlaying: Boolean
    get() = !(playbackState == Player.STATE_ENDED || !playWhenReady)

fun Player.removeMediaItems(range: IntRange) = removeMediaItems(range.first, range.last + 1)

fun Player.safeClearQueue() {
    if (currentMediaItemIndex > 0) removeMediaItems(0 until currentMediaItemIndex)
    if (currentMediaItemIndex < mediaItemCount - 1)
        removeMediaItems(currentMediaItemIndex + 1 until mediaItemCount)
}

fun Player.seamlessPlay(mediaItem: MediaItem, source: String? = null) =
    if (mediaItem.mediaId == currentMediaItem?.mediaId) {
        if (!source.isNullOrBlank()) PlaybackSourceState.currentSource = source.trim()
        safeClearQueue()
    } else {
        forcePlay(mediaItem, source)
    }

fun Player.shuffleQueue() {
    val mediaItems = currentTimeline
        .mediaItems
        .toMutableList()
        .apply { removeAt(currentMediaItemIndex) }
        .shuffled()

    safeClearQueue()
    addMediaItems(mediaItems)
}

fun Player.forcePlay(mediaItem: MediaItem, source: String? = null) {
    val cleanSource = source?.trim()?.takeIf { it.isNotEmpty() }
        ?: mediaItem.mediaMetadata.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    PlaybackSourceState.currentSource = cleanSource
    setMediaItem(mediaItem.withPlaybackSource(cleanSource), true)
    playWhenReady = true
    prepare()
}

fun Player.forcePlayAtIndex(
    items: List<MediaItem>,
    index: Int,
    source: String? = null
) {
    if (items.isEmpty()) return

    val cleanSource = source?.trim()?.takeIf { it.isNotEmpty() }
        ?: items.getOrNull(index)?.mediaMetadata?.subtitle?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    PlaybackSourceState.currentSource = cleanSource
    val taggedItems = if (cleanSource != null) {
        items.map { it.withPlaybackSource(cleanSource) }
    } else {
        items
    }

    setMediaItems(taggedItems, index, C.TIME_UNSET)
    playWhenReady = true
    prepare()
}

fun Player.forcePlayFromBeginning(items: List<MediaItem>, source: String? = null) =
    forcePlayAtIndex(items, 0, source)

fun Player.forceSeekToPrevious(
    hideExplicit: Boolean = AppearancePreferences.hideExplicit,
    seekToStart: Boolean = true
): Unit = when {
    seekToStart && currentPosition > maxSeekToPreviousPosition -> seekToPrevious()
    hideExplicit -> if (mediaItemCount <= 1) forceSeekToPrevious(hideExplicit = false)
    else {
        var i = currentMediaItemIndex - 1
        while (
            i !in (0 until mediaItemCount) ||
            getMediaItemAt(i).mediaMetadata.extras?.songBundle?.explicit == true
        ) {
            if (i <= 0) i = mediaItemCount - 1 else i--
        }
        seekTo(i, C.TIME_UNSET)
    }
    // fall back to default behavior if there is only a single song

    hasPreviousMediaItem() -> seekToPreviousMediaItem()
    mediaItemCount > 0 -> seekTo(mediaItemCount - 1, C.TIME_UNSET)
    else -> {}
}

fun Player.forceSeekToNext() =
    if (hasNextMediaItem()) seekToNext() else seekTo(0, C.TIME_UNSET)

fun Player.addNext(mediaItem: MediaItem, source: String? = null) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlay(mediaItem, source)
    else -> addMediaItem(currentMediaItemIndex + 1, mediaItem.withPlaybackSource(source ?: PlaybackSourceState.currentSource))
}

fun Player.enqueue(mediaItem: MediaItem, source: String? = null) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlay(mediaItem, source)
    else -> addMediaItem(mediaItemCount, mediaItem.withPlaybackSource(source ?: PlaybackSourceState.currentSource))
}

fun Player.enqueue(mediaItems: List<MediaItem>, source: String? = null) = when (playbackState) {
    Player.STATE_IDLE, Player.STATE_ENDED -> forcePlayFromBeginning(mediaItems, source)
    else -> {
        val activeSource = source ?: PlaybackSourceState.currentSource
        addMediaItems(
            mediaItemCount,
            if (activeSource != null) mediaItems.map { it.withPlaybackSource(activeSource) } else mediaItems
        )
    }
}

fun Player.findNextMediaItemById(mediaId: String): MediaItem? = runCatching {
    val cleanTarget = mediaId.removePrefix("https://youtube.com/watch?v=").trim()
    for (i in currentMediaItemIndex until mediaItemCount) {
        val item = getMediaItemAt(i)
        val itemCleanId = item.mediaId.removePrefix("https://youtube.com/watch?v=").trim()
        if (item.mediaId == mediaId || itemCleanId == cleanTarget) return item
    }
    for (i in 0 until currentMediaItemIndex.coerceAtMost(mediaItemCount)) {
        val item = getMediaItemAt(i)
        val itemCleanId = item.mediaId.removePrefix("https://youtube.com/watch?v=").trim()
        if (item.mediaId == mediaId || itemCleanId == cleanTarget) return item
    }
    return null
}.getOrNull()

fun Player.setPlaybackPitch(pitch: Float) {
    playbackParameters = PlaybackParameters(playbackParameters.speed, pitch)
}

operator fun Timeline.get(
    index: Int,
    window: Timeline.Window = Timeline.Window(),
    positionProjection: Duration = Duration.ZERO
) = getWindow(index, window, positionProjection.inWholeMicroseconds)
