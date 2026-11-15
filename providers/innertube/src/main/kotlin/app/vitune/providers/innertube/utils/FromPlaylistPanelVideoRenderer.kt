package app.vitune.providers.innertube.utils

import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.PlaylistPanelVideoRenderer
import app.vitune.providers.innertube.models.isExplicit

fun Innertube.SongItem.Companion.from(renderer: PlaylistPanelVideoRenderer) = Innertube.SongItem(
    info = Innertube.Info(
        name = renderer
            .title
            ?.text,
        endpoint = renderer
            .navigationEndpoint
            ?.watchEndpoint
    ),
    authors = renderer
        .longBylineText
        ?.splitBySeparator()
        ?.getOrNull(0)
        ?.map(Innertube::Info),
    album = renderer
        .longBylineText
        ?.splitBySeparator()
        ?.getOrNull(1)
        ?.getOrNull(0)
        ?.let(Innertube::Info),
    thumbnail = renderer
        .thumbnail
        ?.thumbnails
        ?.let { thumbnails ->
            thumbnails.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) } ?: thumbnails.lastOrNull()
        },
    durationText = renderer
        .lengthText
        ?.text,
    explicit = renderer.badges.isExplicit
).takeIf { it.info?.endpoint?.videoId != null }
