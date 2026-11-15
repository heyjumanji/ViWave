package app.vitune.android.utils.discord

import android.util.Log

object DiscordActivityBuilder {
    private const val TAG = "DiscordSvc"

    fun build(
        songId: String,
        songTitle: String,
        artistName: String,
        albumName: String?,
        thumbnailUrl: String?,
        artistThumbnail: String? = null,
        startTimestamp: Long,
        endTimestamp: Long?,
        advancedMode: Boolean,
        activityType: Int = DiscordActivity.TYPE_LISTENING,
        activityName: String? = null,
        stateTemplate: String = DiscordDefaults.STATE_TEMPLATE,
        detailsTemplate: String = DiscordDefaults.DETAILS_TEMPLATE,
        btn1Enabled: Boolean = true,
        btn1Label: String = DiscordDefaults.BUTTON1_LABEL,
        btn1Url: String = DiscordDefaults.BUTTON1_URL_TEMPLATE,
        btn2Enabled: Boolean = true,
        btn2Label: String = DiscordDefaults.BUTTON2_LABEL,
        btn2Url: String = DiscordDefaults.BUTTON2_URL,
    ): DiscordActivity {
        val state: String
        val details: String?
        val renderedBtn1Label: String?
        val renderedBtn1Url: String?
        val renderedBtn2Label: String?
        val renderedBtn2Url: String?

        val normalizedBtn2Label = if (btn2Label == "Visit Metrolist") DiscordDefaults.BUTTON2_LABEL else btn2Label
        val normalizedBtn2Url = if (btn2Url.contains("MetrolistGroup/Metrolist", ignoreCase = true)) {
            DiscordDefaults.BUTTON2_URL
        } else {
            btn2Url
        }

        if (advancedMode) {
            state = DiscordTemplateRenderer.render(
                stateTemplate.ifEmpty { DiscordDefaults.STATE_TEMPLATE },
                songTitle, artistName, albumName, songId
            )
            details = DiscordTemplateRenderer.render(
                detailsTemplate.ifEmpty { DiscordDefaults.DETAILS_TEMPLATE },
                songTitle, artistName, albumName, songId
            )
            renderedBtn1Label = if (btn1Enabled) {
                DiscordTemplateRenderer.render(
                    btn1Label.ifEmpty { DiscordDefaults.BUTTON1_LABEL },
                    songTitle, artistName, albumName, songId
                )
            } else null
            renderedBtn1Url = if (btn1Enabled) {
                DiscordTemplateRenderer.render(
                    btn1Url.ifEmpty { DiscordDefaults.BUTTON1_URL_TEMPLATE },
                    songTitle, artistName, albumName, songId
                )
            } else null
            renderedBtn2Label = if (btn2Enabled) {
                DiscordTemplateRenderer.render(
                    normalizedBtn2Label.ifEmpty { DiscordDefaults.BUTTON2_LABEL },
                    songTitle, artistName, albumName, songId
                )
            } else null
            renderedBtn2Url = if (btn2Enabled) {
                DiscordTemplateRenderer.render(
                    normalizedBtn2Url.ifEmpty { DiscordDefaults.BUTTON2_URL },
                    songTitle, artistName, albumName, songId
                )
            } else null
        } else {
            state = artistName
            details = songTitle
            renderedBtn1Label = DiscordDefaults.BUTTON1_LABEL
            renderedBtn1Url = "${DiscordDefaults.YOUTUBE_WATCH_URL}$songId"
            renderedBtn2Label = DiscordDefaults.BUTTON2_LABEL
            renderedBtn2Url = DiscordDefaults.BUTTON2_URL
        }

        val renderedName = if (advancedMode && !activityName.isNullOrEmpty()) {
            DiscordTemplateRenderer.render(
                activityName,
                songTitle, artistName, albumName, songId,
            )
        } else {
            DiscordDefaults.DEFAULT_ACTIVITY_NAME
        }

        val result = DiscordActivity(
            activityType = activityType,
            name = renderedName,
            state = state,
            details = details,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            largeImage = thumbnailUrl,
            largeText = null,
            smallImage = null,
            smallText = null,
            button1Label = renderedBtn1Label,
            button1Url = renderedBtn1Url,
            button2Label = renderedBtn2Label,
            button2Url = renderedBtn2Url,
        )

        Log.d(
            TAG,
            "build: result — name=${result.name}, type=${result.activityType}, state=${result.state}, details=${result.details}, btn1=${result.button1Label?.take(30)}, btn2=${result.button2Label?.take(30)}"
        )

        return result
    }
}
