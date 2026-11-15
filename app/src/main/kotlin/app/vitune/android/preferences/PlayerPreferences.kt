package app.vitune.android.preferences

import android.media.audiofx.PresetReverb
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.vitune.android.GlobalPreferencesHolder
import app.vitune.android.R

object PlayerPreferences : GlobalPreferencesHolder() {
    val isInvincibilityEnabledProperty = boolean(false)
    var isInvincibilityEnabled by isInvincibilityEnabledProperty
    val audioQualityProperty = enum(AudioQuality.Auto)
    var audioQuality by audioQualityProperty

    val trackLoopEnabledProperty = boolean(false)
    var trackLoopEnabled by trackLoopEnabledProperty
    val queueLoopEnabledProperty = boolean(true)
    var queueLoopEnabled by queueLoopEnabledProperty
    val skipSilenceProperty = boolean(false)
    var skipSilence by skipSilenceProperty
    val volumeNormalizationProperty = boolean(false)
    var volumeNormalization by volumeNormalizationProperty
    val volumeNormalizationBaseGainProperty = float(5.00f)
    var volumeNormalizationBaseGain by volumeNormalizationBaseGainProperty
    val bassBoostProperty = boolean(false)
    var bassBoost by bassBoostProperty
    val bassBoostLevelProperty = int(5)
    var bassBoostLevel by bassBoostLevelProperty
    val reverbProperty = enum(Reverb.None)
    var reverb by reverbProperty
    val resumePlaybackWhenDeviceConnectedProperty = boolean(false)
    var resumePlaybackWhenDeviceConnected by resumePlaybackWhenDeviceConnectedProperty
    val speedProperty = float(1f)
    var speed by speedProperty
    val pitchProperty = float(1f)
    var pitch by pitchProperty
    var minimumSilence by long(2_000_000L)
    var persistentQueue by boolean(true)
    var stopWhenClosed by boolean(false)
    var stopOnMinimumVolume by boolean(true)

    var isShowingLyrics by boolean(false)
    var isShowingSynchronizedLyrics by boolean(false)

    var isShowingPrevButtonCollapsed by boolean(false)
    var horizontalSwipeToClose by boolean(false)
    var horizontalSwipeToRemoveItem by boolean(false)

    var playerLayout by enum(PlayerLayout.New)
    var playerAnimatedGradient by boolean(true)
    var animatedArtworkEnabled by boolean(true)
    var spotifyCanvasEnabled by boolean(false)
    var fullScreenPlayer by boolean(false)
    var showInlineLyrics by boolean(true)
    var seekBarStyle by enum(SeekBarStyle.Wavy)
    var wavySeekBarQuality by enum(WavySeekBarQuality.Great)
    var showLike by boolean(false)
    var showItag by boolean(false)
    var showRemaining by boolean(false)
    var lyricsKeepScreenAwake by boolean(false)
    var lyricsShowSystemBars by boolean(true)

    var lyricsV2Enabled by boolean(false)
    var lyricsWordSyncDynamicColors by boolean(true)
    var showLyricsRomanizeButton by boolean(true)
    var lyricsRomanizationEnabled by boolean(false)
    var showLyricsTranslateButton by boolean(true)
    var lyricsTranslationEnabled by boolean(false)
    var lyricsTranslationLanguage by string("en")
    var lyricsTextPosition by enum(LyricsTextPosition.Center)
    var lyricsChangeOnClick by boolean(true)
    var lyricsAutoScroll by boolean(true)
    var lyricsTextSize by int(26)
    var lyricsLineSpacing by float(1.3f)

    enum class LyricsTextPosition(val displayName: @Composable () -> String) {
        Left(displayName = { "Left" }),
        Center(displayName = { "Center" }),
        Right(displayName = { "Right" })
    }

    val defaultLyricsProviderProperty = enum(LyricsProvider.Auto)
    var defaultLyricsProvider by defaultLyricsProviderProperty

    val lyricsFallbackEnabledProperty = boolean(true)
    var lyricsFallbackEnabled by lyricsFallbackEnabledProperty

    enum class LyricsProvider(
        val title: String,
        val shortName: String,
        val displayName: @Composable () -> String
    ) {
        Auto(
            title = "Auto (Default + Fallback)",
            shortName = "Auto",
            displayName = { "Auto (Default + Fallback)" }
        ),
        Unison(
            title = "Unison (unison.boidu.dev)",
            shortName = "Unison",
            displayName = { "Unison (unison.boidu.dev)" }
        ),
        YouLyPlus(
            title = "YouLyPlus (LyricsPlus)",
            shortName = "LyricsPlus",
            displayName = { "YouLyPlus (LyricsPlus)" }
        ),
        Paxsenix(
            title = "Paxsenix (lyrics.paxsenix.org)",
            shortName = "Paxsenix",
            displayName = { "Paxsenix (lyrics.paxsenix.org)" }
        ),
        LrcLib(
            title = "LrcLib",
            shortName = "LrcLib",
            displayName = { "LrcLib" }
        ),
        KuGou(
            title = "KuGou",
            shortName = "KuGou",
            displayName = { "KuGou" }
        ),
        Innertube(
            title = "YouTube Music (Innertube)",
            shortName = "YouTube Music",
            displayName = { "YouTube Music (Innertube)" }
        )
    }

    fun resetLyricsSettingsToDefault() {
        defaultLyricsProvider = LyricsProvider.Auto
        lyricsFallbackEnabled = true
        isShowingSynchronizedLyrics = false
        lyricsKeepScreenAwake = false
        lyricsShowSystemBars = true
        lyricsV2Enabled = false
        lyricsWordSyncDynamicColors = true
        showLyricsRomanizeButton = true
        lyricsRomanizationEnabled = false
        showLyricsTranslateButton = true
        lyricsTranslationEnabled = false
        lyricsTranslationLanguage = "en"
        lyricsTextPosition = LyricsTextPosition.Center
        lyricsChangeOnClick = true
        lyricsAutoScroll = true
        lyricsTextSize = 26
        lyricsLineSpacing = 1.3f
    }



    var skipOnError by boolean(false)
    var handleAudioFocus by boolean(true)

    var pauseCache by boolean(false)

    val sponsorBlockEnabledProperty = boolean(false)
    var sponsorBlockEnabled by sponsorBlockEnabledProperty

    val crossfadeEnabledProperty = boolean(false)
    var crossfadeEnabled by crossfadeEnabledProperty
    val crossfadeDurationProperty = int(5)
    var crossfadeDuration by crossfadeDurationProperty

    enum class AudioQuality(val displayName: String) {
        Auto("Auto"),
        VeryHigh("Very High"),
        High("High"),
        Medium("Medium"),
        Low("Low");

        val youtubeFormatName: String
            get() = name

        val youtubeDownloadDisplayName: String
            get() = displayName
    }

    enum class PlayerLayout(val displayName: @Composable () -> String) {
        Classic(displayName = { stringResource(R.string.classic_player_layout_name) }),
        New(displayName = { stringResource(R.string.new_player_layout_name) }),
        Material(displayName = { stringResource(R.string.material_player_layout_name) }),
        Apple(displayName = { stringResource(R.string.apple_player_layout_name) })
    }

    enum class SeekBarStyle(val displayName: @Composable () -> String) {
        Static(displayName = { stringResource(R.string.static_seek_bar_name) }),
        Wavy(displayName = { stringResource(R.string.wavy_seek_bar_name) })
    }

    enum class WavySeekBarQuality(
        val quality: Float,
        val displayName: @Composable () -> String
    ) {
        Poor(quality = 50f, displayName = { stringResource(R.string.seek_bar_quality_poor) }),
        Low(quality = 25f, displayName = { stringResource(R.string.seek_bar_quality_low) }),
        Medium(quality = 15f, displayName = { stringResource(R.string.seek_bar_quality_medium) }),
        High(quality = 5f, displayName = { stringResource(R.string.seek_bar_quality_high) }),
        Great(quality = 1f, displayName = { stringResource(R.string.seek_bar_quality_great) }),
        Subpixel(
            quality = 0.5f,
            displayName = { stringResource(R.string.seek_bar_quality_subpixel) }
        )
    }

    enum class Reverb(
        val preset: Short,
        val displayName: @Composable () -> String
    ) {
        None(
            preset = PresetReverb.PRESET_NONE,
            displayName = { stringResource(R.string.none) }
        ),
        SmallRoom(
            preset = PresetReverb.PRESET_SMALLROOM,
            displayName = { stringResource(R.string.reverb_small_room) }
        ),
        MediumRoom(
            preset = PresetReverb.PRESET_MEDIUMROOM,
            displayName = { stringResource(R.string.reverb_medium_room) }
        ),
        LargeRoom(
            preset = PresetReverb.PRESET_LARGEROOM,
            displayName = { stringResource(R.string.reverb_large_room) }
        ),
        MediumHall(
            preset = PresetReverb.PRESET_MEDIUMHALL,
            displayName = { stringResource(R.string.reverb_medium_hall) }
        ),
        LargeHall(
            preset = PresetReverb.PRESET_LARGEHALL,
            displayName = { stringResource(R.string.reverb_large_hall) }
        ),
        Plate(
            preset = PresetReverb.PRESET_PLATE,
            displayName = { stringResource(R.string.reverb_plate) }
        )
    }
}
