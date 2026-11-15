package app.vitune.android.utils.discord

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class ActivityType(val code: Int) {
    Playing(0),
    Streaming(1),
    Listening(2),
    Watching(3),
    Custom(4),
    Competing(5),
}

enum class PresenceStatus(val wire: String) {
    Online("online"),
    Idle("idle"),
    Dnd("dnd"),
    Invisible("invisible"),
}

data class ActivityPayload(
    val applicationId: String = DiscordDefaults.DISCORD_APP_ID_STR,
    val name: String = "",
    val type: Int,
    val details: String? = null,
    val state: String? = null,
    val url: String? = null,
    val largeImage: String? = null,
    val largeText: String? = null,
    val smallImage: String? = null,
    val smallText: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val buttons: List<Pair<String, String>> = emptyList(),
)

object DiscordPresence {

    fun buildActivity(
        applicationId: String = DiscordDefaults.activeAppId(),
        name: String = "",
        type: ActivityType,
        details: String? = null,
        state: String? = null,
        url: String? = null,
        largeImage: String? = null,
        largeText: String? = null,
        smallImage: String? = null,
        smallText: String? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        buttons: List<Pair<String, String>> = emptyList(),
    ): ActivityPayload = ActivityPayload(
        applicationId = applicationId,
        name = name,
        type = type.code,
        details = details,
        state = state,
        url = url,
        largeImage = largeImage,
        largeText = largeText,
        smallImage = smallImage,
        smallText = smallText,
        startMs = startMs,
        endMs = endMs,
        buttons = buttons,
    ).also {
        Log.d(
            TAG,
            "buildActivity: appId=$applicationId, name=$name, type=${type.code}, details=${details?.take(40)}, state=${state?.take(40)}, buttons=${buttons.size}, hasLargeImage=${largeImage != null}, hasSmallImage=${smallImage != null}"
        )
    }

    fun buildPresenceUpdate(
        status: PresenceStatus,
        afk: Boolean = false,
        since: Long = 0,
        activities: List<ActivityPayload>,
    ): String {
        val d = JSONObject()
        d.put("since", since)
        d.put("activities", JSONArray().apply { activities.forEach { put(activityToJson(it)) } })
        d.put("status", status.wire)
        d.put("afk", afk)

        val root = JSONObject()
        root.put("op", 3)
        root.put("d", d)
        val json = root.toString()
        Log.v(TAG, "buildPresenceUpdate: activities=${activities.size}, status=${status.wire}")
        return json
    }

    private fun activityToJson(activity: ActivityPayload): JSONObject {
        val obj = JSONObject()
        obj.put("application_id", activity.applicationId.ifBlank { DiscordDefaults.DISCORD_APP_ID_STR })
        obj.put("name", activity.name)
        obj.put("type", activity.type)
        activity.details?.takeIf { it.isNotBlank() }?.let { obj.put("details", it) }
        activity.state?.takeIf { it.isNotBlank() }?.let { obj.put("state", it) }
        activity.url?.takeIf { it.isNotBlank() }?.let { obj.put("url", it) }

        if (activity.startMs != null || activity.endMs != null) {
            val timestamps = JSONObject()
            activity.startMs?.let { timestamps.put("start", it) }
            activity.endMs?.let { timestamps.put("end", it) }
            obj.put("timestamps", timestamps)
        }

        // Include assets block when image paths are available.
        // Do NOT send large_text unless explicitly set, because Discord renders large_text
        // as a 3rd sub-text line below the artist name on Listening cards.
        if (!activity.largeImage.isNullOrBlank() || !activity.smallImage.isNullOrBlank()) {
            val assets = JSONObject()
            activity.largeImage?.takeIf { it.isNotBlank() }?.let { assets.put("large_image", it) }
            activity.largeText?.takeIf { it.isNotBlank() }?.let { assets.put("large_text", it) }
            activity.smallImage?.takeIf { it.isNotBlank() }?.let { assets.put("small_image", it) }
            activity.smallText?.takeIf { it.isNotBlank() }?.let { assets.put("small_text", it) }
            obj.put("assets", assets)
        }

        if (activity.buttons.isNotEmpty()) {
            val arr = JSONArray()
            val urlArr = JSONArray()
            activity.buttons.forEach { (label, url) ->
                arr.put(label)
                if (url.isNotEmpty()) urlArr.put(url)
            }
            obj.put("buttons", arr)
            if (urlArr.length() > 0) {
                val metadata = JSONObject()
                metadata.put("button_urls", urlArr)
                obj.put("metadata", metadata)
            }
        }

        return obj
    }

    private const val TAG = "DiscordSvc"
}
