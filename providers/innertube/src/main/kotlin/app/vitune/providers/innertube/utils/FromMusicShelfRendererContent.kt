package app.vitune.providers.innertube.utils

import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.MusicShelfRenderer
import app.vitune.providers.innertube.models.NavigationEndpoint
import app.vitune.providers.innertube.models.isExplicit

// Possible configurations:
// "song" • author(s) • album • duration
// "song" • author(s) • duration
// author(s) • album • duration
// author(s) • duration

fun Innertube.SongItem.Companion.from(content: MusicShelfRenderer.Content) = runCatching {
    val (mainRuns, otherRuns) = content.runs

    val albumIndex = otherRuns.indexOfFirst { segment ->
        segment.firstOrNull()?.navigationEndpoint?.browseEndpoint?.let { ep ->
            ep.type == "MUSIC_PAGE_TYPE_ALBUM" || ep.browseId?.startsWith("MPREb_") == true
        } == true
    }

    val album: Innertube.Info<NavigationEndpoint.Endpoint.Browse>? = if (albumIndex >= 0) {
        otherRuns.getOrNull(albumIndex)?.firstOrNull()?.let(Innertube::Info)
    } else null

    val explicitAuthorSegment = otherRuns.firstOrNull { segment ->
        segment.any { run ->
            run.navigationEndpoint?.browseEndpoint?.let { ep ->
                ep.type == "MUSIC_PAGE_TYPE_ARTIST" ||
                    ep.type == "MUSIC_PAGE_TYPE_USER_CHANNEL" ||
                    ep.browseId?.startsWith("UC") == true
            } == true
        }
    }

    val fallbackAuthorSegment = otherRuns
        .filterIndexed { index, segment ->
            val firstText = segment.firstOrNull()?.text?.trim().orEmpty()
            val isTypeLabel = firstText.equals("song", ignoreCase = true) ||
                firstText.equals("video", ignoreCase = true)
            val isDuration = ':' in firstText && firstText.any { it.isDigit() }
            val isPlayCount = firstText.endsWith("plays", ignoreCase = true) ||
                firstText.endsWith("views", ignoreCase = true)
            index != albumIndex && !isTypeLabel && !isDuration && !isPlayCount && firstText.isNotEmpty()
        }
        .firstOrNull()

    val resolvedAuthors: List<Innertube.Info<NavigationEndpoint.Endpoint.Browse>>? =
        (explicitAuthorSegment ?: fallbackAuthorSegment)?.map(Innertube::Info)

    val durationText = otherRuns
        .firstOrNull { segment ->
            val t = segment.firstOrNull()?.text?.trim().orEmpty()
            ':' in t && t.any { it.isDigit() }
        }
        ?.firstOrNull()
        ?.text

    Innertube.SongItem(
        info = mainRuns
            .firstOrNull()
            ?.let(Innertube::Info),
        authors = resolvedAuthors,
        album = album,
        durationText = durationText,
        explicit = content.musicResponsiveListItemRenderer?.badges.isExplicit,
        thumbnail = content.thumbnail
    ).takeIf { it.info?.endpoint?.videoId != null }
}.getOrNull()

fun Innertube.VideoItem.Companion.from(content: MusicShelfRenderer.Content) = runCatching {
    val (mainRuns, otherRuns) = content.runs

    Innertube.VideoItem(
        info = mainRuns
            .firstOrNull()
            ?.let(Innertube::Info),
        authors = otherRuns
            .getOrNull(otherRuns.lastIndex - 2)
            ?.map(Innertube::Info),
        viewsText = otherRuns
            .getOrNull(otherRuns.lastIndex - 1)
            ?.firstOrNull()
            ?.text,
        durationText = otherRuns
            .getOrNull(otherRuns.lastIndex)
            ?.firstOrNull()
            ?.text,
        thumbnail = content.thumbnail
    ).takeIf { it.info?.endpoint?.videoId != null }
}.getOrNull()

fun Innertube.AlbumItem.Companion.from(content: MusicShelfRenderer.Content) = runCatching {
    val (mainRuns, otherRuns) = content.runs

    Innertube.AlbumItem(
        info = Innertube.Info(
            name = mainRuns
                .firstOrNull()
                ?.text,
            endpoint = content
                .musicResponsiveListItemRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
        ),
        authors = otherRuns
            .getOrNull(otherRuns.lastIndex - 1)
            ?.map(Innertube::Info),
        year = otherRuns
            .getOrNull(otherRuns.lastIndex)
            ?.firstOrNull()
            ?.text,
        thumbnail = content.thumbnail
    ).takeIf { it.info?.endpoint?.browseId != null }
}.getOrNull()

fun Innertube.ArtistItem.Companion.from(content: MusicShelfRenderer.Content) = runCatching {
    val (mainRuns, otherRuns) = content.runs

    Innertube.ArtistItem(
        info = Innertube.Info(
            name = mainRuns
                .firstOrNull()
                ?.text,
            endpoint = content
                .musicResponsiveListItemRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
        ),
        subscribersCountText = otherRuns
            .lastOrNull()
            ?.last()
            ?.text,
        thumbnail = content.thumbnail
    ).takeIf { it.info?.endpoint?.browseId != null }
}.getOrNull()

fun Innertube.PlaylistItem.Companion.from(content: MusicShelfRenderer.Content) = runCatching {
    val (mainRuns, otherRuns) = content.runs

    Innertube.PlaylistItem(
        info = Innertube.Info(
            name = mainRuns
                .firstOrNull()
                ?.text,
            endpoint = content
                .musicResponsiveListItemRenderer
                ?.navigationEndpoint
                ?.browseEndpoint
        ),
        channel = otherRuns
            .firstOrNull()
            ?.firstOrNull()
            ?.let(Innertube::Info),
        songCount = otherRuns
            .lastOrNull()
            ?.firstOrNull()
            ?.text
            ?.split(' ')
            ?.firstOrNull()
            ?.toIntOrNull(),
        thumbnail = content.thumbnail
    ).takeIf { it.info?.endpoint?.browseId != null }
}.getOrNull()
