package app.vitune.android.utils.discord

import android.util.Log

object DiscordTemplateRenderer {
    private const val TAG = "DiscordSvc"

    fun render(
        template: String,
        title: String,
        artist: String,
        album: String?,
        songId: String = "",
    ): String {
        val result = template
            .replace("{song.name}", title)
            .replace("{song.id}", songId)
            .replace("{artist.name}", artist)
            .replace("{album.name}", album ?: DiscordDefaults.UNKNOWN_ALBUM)
        Log.v(TAG, "render: template=$template -> result=$result")
        return result
    }

    val PLACEHOLDERS = listOf(
        "{song.name}",
        "{artist.name}",
        "{album.name}",
        "{song.id}",
    )
}
