package app.vitune.android.preferences

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import app.vitune.android.GlobalPreferencesHolder
import app.vitune.android.R
import app.vitune.core.data.enums.CoilDiskCacheSize
import app.vitune.core.data.enums.ExoPlayerDiskCacheSize
import app.vitune.providers.innertube.Innertube
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Immutable
@Serializable
data class SpotifySyncLogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val songTitle: String = "",
    val artistName: String = "",
    val action: String = "",
    val success: Boolean = true,
    val details: String = ""
)

@Immutable
@Serializable
data class CachedLastFmSong(
    val id: String,
    val title: String,
    val artistsText: String? = null,
    val durationText: String? = null,
    val thumbnailUrl: String? = null,
    val explicit: Boolean = false
)

object DataPreferences : GlobalPreferencesHolder() {
    var coilDiskCacheMaxSize by enum(CoilDiskCacheSize.`128MB`)
    var exoPlayerDiskCacheMaxSize by enum(ExoPlayerDiskCacheSize.`2GB`)
    var pauseHistory by boolean(false)
    var pausePlaytime by boolean(false)
    var pauseSearchHistory by boolean(false)
    val topListLengthProperty = int(50)
    var topListLength by topListLengthProperty
    val topListPeriodProperty = enum(TopListPeriod.AllTime)
    var topListPeriod by topListPeriodProperty
    var quickPicksSource by enum(QuickPicksSource.Trending)
    var versionCheckPeriod by enum(VersionCheckPeriod.Daily)
    var shouldCacheQuickPicks by boolean(true)
    var cachedQuickPicks by json(Innertube.RelatedPage())
    var cachedLastFmPicks by json(emptyList<CachedLastFmSong>())
    var cachedSpotifySmartPicks by json(emptyList<CachedLastFmSong>())
    var cachedSpotifyReleases by json(emptyList<app.vitune.providers.spotify.models.SpotifyReleaseItem>())
    var autoSyncPlaylists by boolean(true)

    val lastFmEnabledProperty = boolean(false)
    var lastFmEnabled by lastFmEnabledProperty
    var lastFmUsername by string("")
    var lastFmSessionKey by string("")
    var lastFmCustomApiKey by string("")
    var lastFmCustomApiSecret by string("")

    private var rawSpotifySpDcCookie by string("")
    private var rawSpotifyAccessToken by string("")
    var spotifyTokenExpirationMs by long(0L)
    var spotifyUsername by string("")
    var spotifyClientId by string("")
    private var rawSpotifySpTCookie by string("")

    var spotifySpDcCookie: String
        get() {
            val raw = rawSpotifySpDcCookie.ifBlank {
                getString("rawSpotifySpDcCookie", "")?.takeIf { it.isNotBlank() }
                    ?: getString("spotifySpDcCookie", "").orEmpty()
            }
            if (raw.isBlank()) return ""
            val decrypted = app.vitune.android.utils.SpotifyTokenCipher.decrypt(raw)
            if (decrypted.isNotBlank() && !app.vitune.android.utils.SpotifyTokenCipher.isEncrypted(raw)) {
                rawSpotifySpDcCookie = app.vitune.android.utils.SpotifyTokenCipher.encrypt(decrypted)
                edit().remove("spotifySpDcCookie").apply()
            }
            return decrypted
        }
        set(value) {
            rawSpotifySpDcCookie = if (value.isBlank()) "" else app.vitune.android.utils.SpotifyTokenCipher.encrypt(value)
            edit().remove("spotifySpDcCookie").apply()
        }

    var spotifyAccessToken: String
        get() {
            val raw = rawSpotifyAccessToken.ifBlank {
                getString("rawSpotifyAccessToken", "")?.takeIf { it.isNotBlank() }
                    ?: getString("spotifyAccessToken", "").orEmpty()
            }
            if (raw.isBlank()) return ""
            val decrypted = app.vitune.android.utils.SpotifyTokenCipher.decrypt(raw)
            if (decrypted.isNotBlank() && !app.vitune.android.utils.SpotifyTokenCipher.isEncrypted(raw)) {
                rawSpotifyAccessToken = app.vitune.android.utils.SpotifyTokenCipher.encrypt(decrypted)
                edit().remove("spotifyAccessToken").apply()
            }
            return decrypted
        }
        set(value) {
            rawSpotifyAccessToken = if (value.isBlank()) "" else app.vitune.android.utils.SpotifyTokenCipher.encrypt(value)
            edit().remove("spotifyAccessToken").apply()
        }

    var spotifySpTCookie: String
        get() {
            val raw = rawSpotifySpTCookie.ifBlank {
                getString("rawSpotifySpTCookie", "")?.takeIf { it.isNotBlank() }
                    ?: getString("spotifySpTCookie", "").orEmpty()
            }
            if (raw.isBlank()) return ""
            val decrypted = app.vitune.android.utils.SpotifyTokenCipher.decrypt(raw)
            if (decrypted.isNotBlank() && !app.vitune.android.utils.SpotifyTokenCipher.isEncrypted(raw)) {
                rawSpotifySpTCookie = app.vitune.android.utils.SpotifyTokenCipher.encrypt(decrypted)
                edit().remove("spotifySpTCookie").apply()
            }
            return decrypted
        }
        set(value) {
            rawSpotifySpTCookie = if (value.isBlank()) "" else app.vitune.android.utils.SpotifyTokenCipher.encrypt(value)
            edit().remove("spotifySpTCookie").apply()
        }

    val spotifyAutoSyncLikedSongsProperty = boolean(true)
    var spotifyAutoSyncLikedSongs by spotifyAutoSyncLikedSongsProperty
    val spotifySyncPlaybackProperty = boolean(true)
    var spotifySyncPlayback by spotifySyncPlaybackProperty
    var spotifySyncHistory by json(emptyList<SpotifySyncLogEntry>())

    fun clearSpotifySession() {
        rawSpotifySpDcCookie = ""
        rawSpotifyAccessToken = ""
        rawSpotifySpTCookie = ""
        edit()
            .remove("spotifySpDcCookie")
            .remove("spotifyAccessToken")
            .remove("spotifySpTCookie")
            .remove("rawSpotifySpDcCookie")
            .remove("rawSpotifyAccessToken")
            .remove("rawSpotifySpTCookie")
            .apply()
        spotifyTokenExpirationMs = 0L
        spotifyUsername = ""
        spotifyClientId = ""
        cachedSpotifySmartPicks = emptyList()
        cachedSpotifyReleases = emptyList()
    }

    val discordRpcEnabledProperty = boolean(true)
    var discordRpcEnabled by discordRpcEnabledProperty

    val discordInfoDismissedProperty = boolean(false)
    var discordInfoDismissed by discordInfoDismissedProperty

    var discordUsername by string("")
    var discordDisplayName by string("")
    var discordAvatarUrl by string("")

    val discordAdvancedModeProperty = boolean(false)
    var discordAdvancedMode by discordAdvancedModeProperty

    val discordActivityTypeProperty = string("2")
    var discordActivityType by discordActivityTypeProperty

    val discordActivityNameProperty = string("")
    var discordActivityName by discordActivityNameProperty

    val discordStateTemplateProperty = string("{artist.name}")
    var discordStateTemplate by discordStateTemplateProperty

    val discordDetailsTemplateProperty = string("{song.name}")
    var discordDetailsTemplate by discordDetailsTemplateProperty

    val discordButton1VisibleProperty = boolean(true)
    var discordButton1Visible by discordButton1VisibleProperty

    val discordButton1TextProperty = string("Listen on YouTube Music")
    var discordButton1Text by discordButton1TextProperty

    val discordButton1UrlProperty = string("https://music.youtube.com/watch?v={song.id}")
    var discordButton1Url by discordButton1UrlProperty

    val discordButton2VisibleProperty = boolean(true)
    var discordButton2Visible by discordButton2VisibleProperty

    val discordButton2TextProperty = string("Visit ViWaves")
    var discordButton2Text by discordButton2TextProperty

    val discordButton2UrlProperty = string("https://github.com/heyjumanji/Vitune-Repatched")
    var discordButton2Url by discordButton2UrlProperty

    val discordStatusProperty = string("online")
    var discordStatus by discordStatusProperty

    fun clearDiscordSession() {
        discordUsername = ""
        discordDisplayName = ""
        discordAvatarUrl = ""
    }

    enum class TopListPeriod(
        val displayName: @Composable () -> String,
        val duration: Duration? = null
    ) {
        PastDay(displayName = { stringResource(R.string.past_24_hours) }, duration = 1.days),
        PastWeek(displayName = { stringResource(R.string.past_week) }, duration = 7.days),
        PastMonth(displayName = { stringResource(R.string.past_month) }, duration = 30.days),
        PastYear(displayName = { stringResource(R.string.past_year) }, 365.days),
        AllTime(displayName = { stringResource(R.string.all_time) })
    }

    enum class QuickPicksSource(val displayName: @Composable () -> String) {
        Trending(displayName = { stringResource(R.string.trending) }),
        LastInteraction(displayName = { stringResource(R.string.last_interaction) })
    }

    enum class VersionCheckPeriod(
        val displayName: @Composable () -> String,
        val period: Duration?
    ) {
        Off(displayName = { stringResource(R.string.off_text) }, period = null),
        Hourly(displayName = { stringResource(R.string.hourly) }, period = 1.hours),
        Daily(displayName = { stringResource(R.string.daily) }, period = 1.days),
        Weekly(displayName = { stringResource(R.string.weekly) }, period = 7.days)
    }
}
