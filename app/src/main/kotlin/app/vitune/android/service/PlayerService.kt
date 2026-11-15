package app.vitune.android.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import android.text.format.DateUtils
import androidx.annotation.OptIn
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import app.vitune.android.Database
import app.vitune.android.Dependencies
import app.vitune.android.MainActivity
import app.vitune.android.R
import app.vitune.android.models.Event
import app.vitune.android.models.Format
import app.vitune.android.models.QueuedMediaItem
import app.vitune.android.models.Song
import app.vitune.android.models.SongWithContentLength
import app.vitune.android.preferences.AppearancePreferences
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.PlayerPreferences
import app.vitune.android.query
import app.vitune.android.transaction
import app.vitune.android.utils.ActionReceiver
import app.vitune.android.utils.ConditionalCacheDataSourceFactory
import app.vitune.android.utils.GlyphInterface
import app.vitune.android.utils.InvincibleService
import app.vitune.android.utils.TimerJob
import app.vitune.android.utils.YouTubeDLResponse
import app.vitune.android.utils.YouTubeRadio
import app.vitune.android.utils.activityPendingIntent
import app.vitune.android.utils.asDataSource
import app.vitune.android.utils.broadcastPendingIntent
import app.vitune.android.utils.defaultDataSource
import app.vitune.android.utils.findCause
import app.vitune.android.utils.jiosaavn.JioSaavnResolver
import app.vitune.android.utils.jiosaavn.TrackQuery
import app.vitune.android.utils.PlaybackSourceState
import app.vitune.android.utils.findNextMediaItemById
import app.vitune.android.utils.forcePlayFromBeginning
import app.vitune.android.utils.forceSeekToNext
import app.vitune.android.utils.forceSeekToPrevious
import app.vitune.android.utils.withPlaybackSource
import app.vitune.android.utils.get
import app.vitune.android.utils.handleUnknownErrors
import app.vitune.android.utils.intent
import app.vitune.android.utils.mediaItems
import app.vitune.android.utils.progress
import app.vitune.android.utils.readOnlyWhen
import app.vitune.android.utils.setPlaybackPitch
import app.vitune.android.utils.shouldBePlaying
import app.vitune.android.utils.thumbnail
import app.vitune.android.utils.timer
import app.vitune.android.utils.toast
import app.vitune.compose.preferences.SharedPreferencesProperty
import app.vitune.core.data.enums.ExoPlayerDiskCacheSize
import app.vitune.core.data.utils.UriCache
import app.vitune.core.ui.utils.EqualizerIntentBundleAccessor
import app.vitune.core.ui.utils.isAtLeastAndroid10
import app.vitune.core.ui.utils.isAtLeastAndroid12
import app.vitune.core.ui.utils.isAtLeastAndroid13
import app.vitune.core.ui.utils.isAtLeastAndroid6
import app.vitune.core.ui.utils.isAtLeastAndroid8
import app.vitune.core.ui.utils.isAtLeastAndroid9
import app.vitune.core.ui.utils.songBundle
import app.vitune.core.ui.utils.streamVolumeFlow
import app.vitune.providers.innertube.Innertube
import app.vitune.providers.innertube.models.NavigationEndpoint
import app.vitune.providers.innertube.models.bodies.PlayerBody
import app.vitune.providers.innertube.models.bodies.SearchBody
import app.vitune.providers.innertube.requests.player
import app.vitune.providers.innertube.requests.searchPage
import app.vitune.providers.innertube.utils.from
import app.vitune.providers.lastfm.LastFmApi
import app.vitune.providers.sponsorblock.SponsorBlock
import app.vitune.providers.sponsorblock.models.Action
import app.vitune.providers.sponsorblock.models.Category
import app.vitune.providers.sponsorblock.requests.segments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import app.vitune.android.utils.discord.DiscordActivity
import app.vitune.android.utils.discord.DiscordActivityBuilder
import app.vitune.android.utils.discord.DiscordDefaults
import app.vitune.android.utils.discord.DiscordRpcManager
import app.vitune.android.utils.discord.PresenceStatus
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import android.os.Binder as AndroidBinder

const val LOCAL_KEY_PREFIX = "local:"
private const val TAG = "PlayerService"

@get:OptIn(UnstableApi::class)
val DataSpec.isLocal get() = key?.startsWith(LOCAL_KEY_PREFIX) == true

val MediaItem.isLocal get() = mediaId.startsWith(LOCAL_KEY_PREFIX)
val Song.isLocal get() = id.startsWith(LOCAL_KEY_PREFIX)

private const val LIKE_ACTION = "app.vitune.android.LIKE"
private const val LOOP_ACTION = "app.vitune.android.LOOP"

@kotlin.OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass", "TooManyFunctions") // intended in this class: it is a service
@OptIn(UnstableApi::class)
class PlayerService : InvincibleService(), Player.Listener, PlaybackStatsListener.Callback {
    private lateinit var mediaSession: MediaSession
    private lateinit var cache: Cache
    private lateinit var player: ExoPlayer

    private val defaultActions =
        PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_PLAY_FROM_SEARCH

    private val stateBuilder
        get() = PlaybackState.Builder().setActions(
            defaultActions.let {
                if (isAtLeastAndroid12) it or PlaybackState.ACTION_SET_PLAYBACK_SPEED else it
            }
        ).addCustomAction(
            PlaybackState.CustomAction.Builder(
                /* action = */ LIKE_ACTION,
                /* name   = */ getString(R.string.like),
                /* icon   = */
                if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline
            ).build()
        ).addCustomAction(
            PlaybackState.CustomAction.Builder(
                /* action = */ LOOP_ACTION,
                /* name   = */ getString(R.string.queue_loop),
                /* icon   = */
                if (PlayerPreferences.trackLoopEnabled) R.drawable.repeat_on else R.drawable.repeat
            ).build()
        )

    private val playbackStateMutex = Mutex()
    private val metadataBuilder = MediaMetadata.Builder()

    private var timerJob: TimerJob? by mutableStateOf(null)
    private var radio: YouTubeRadio? = null

    private lateinit var bitmapProvider: BitmapProvider

    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    private var preferenceUpdaterJob: Job? = null
    private var volumeNormalizationJob: Job? = null
    private var sponsorBlockJob: Job? = null
    private var lastFmJob: Job? = null
    private var crossfadeJob: Job? = null
    private var fadeInJob: Job? = null
    private var outgoingFadeJob: Job? = null
    private var prewarmJob: Job? = null
    private var outgoingCrossfadePlayer: ExoPlayer? = null
    private var prepreparedOutgoingMediaId: String? = null
    private var prewarmedNextMediaId: String? = null
    private var isCrossfadeTransitioning = false

    private var lastAutoRecoveryMediaId: String? = null
    private var autoRecoveryAttempts: Int = 0
    private val sharedUriCache = UriCache<String, Long?>()

    override var isInvincibilityEnabled by mutableStateOf(false)

    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var reverb: PresetReverb? = null

    private val binder = Binder()

    private var isNotificationStarted = false
    private var isNotificationDismissed = false
    override val notificationId get() = ServiceNotifications.default.notificationId!!
    private val notificationActionReceiver = NotificationActionReceiver()

    private val mediaItemState = MutableStateFlow<MediaItem?>(null)
    private val isLikedState = mediaItemState
        .flatMapMerge { item ->
            item?.mediaId?.let {
                Database
                    .likedAt(it)
                    .distinctUntilChanged()
                    .cancellable()
            } ?: flowOf(null)
        }
        .map { it != null }
        .onEach {
            updateNotification()
        }
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

    private val glyphInterface by lazy { GlyphInterface(applicationContext) }

    private var poiTimestamp: Long? by mutableStateOf(null)

    override fun onBind(intent: Intent?): AndroidBinder {
        super.onBind(intent)
        return binder
    }

    @Suppress("CyclomaticComplexMethod")
    override fun onCreate() {
        super.onCreate()

        glyphInterface.tryInit()
        notificationActionReceiver.register(flags = ContextCompat.RECEIVER_EXPORTED)

        bitmapProvider = BitmapProvider(
            getBitmapSize = {
                (512 * resources.displayMetrics.density)
                    .roundToInt()
                    .coerceAtMost(AppearancePreferences.maxThumbnailSize)
            },
            getColor = { isSystemInDarkMode ->
                if (isSystemInDarkMode) Color.BLACK else Color.WHITE
            },
            context = this
        )

        cache = createCache(this)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val fastLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this, createRendersFactory(), createMediaSourceFactory())
            .setLoadControl(fastLoadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(audioAttributes, PlayerPreferences.handleAudioFocus)
            .setUsePlatformDiagnostics(false)
            .build()
            .apply {
                skipSilenceEnabled = PlayerPreferences.skipSilence
                addListener(this@PlayerService)
                addAnalyticsListener(
                    PlaybackStatsListener(
                        /* keepHistory = */ false,
                        /* callback = */ this@PlayerService
                    )
                )
            }

        outgoingCrossfadePlayer = runCatching {
            ExoPlayer.Builder(this, createRendersFactory(), createMediaSourceFactory())
                .setHandleAudioBecomingNoisy(false)
                .setWakeMode(C.WAKE_MODE_NONE)
                .setAudioAttributes(audioAttributes, false)
                .setUsePlatformDiagnostics(false)
                .build()
        }.getOrNull()

        updateRepeatMode()
        maybeRestorePlayerQueue()

        mediaSession = MediaSession(baseContext, TAG).apply {
            setCallback(SessionCallback())
            setPlaybackState(stateBuilder.build())
            setSessionActivity(activityPendingIntent<MainActivity>())
            isActive = true
        }

        coroutineScope.launch {
            var first = true
            combine(mediaItemState, isLikedState) { mediaItem, _ ->
                // work around NPE in other processes
                if (first) {
                    first = false
                    return@combine
                }

                if (mediaItem == null) return@combine
                withContext(Dispatchers.Main) {
                    updatePlaybackState()
                    updateNotification()
                }
            }.collect()
        }

        maybeResumePlaybackWhenDeviceConnected()

        preferenceUpdaterJob = coroutineScope.launch {
            fun <T : Any> subscribe(
                prop: SharedPreferencesProperty<T>,
                callback: (T) -> Unit
            ) = launch { prop.stateFlow.collectLatest { handler.post { callback(it) } } }

            var lastAudioQuality = PlayerPreferences.audioQuality
            subscribe(PlayerPreferences.audioQualityProperty) { newQuality ->
                if (newQuality != lastAudioQuality) {
                    lastAudioQuality = newQuality
                    sharedUriCache.clear()
                    prewarmedNextMediaId = null
                    val currentItem = player.currentMediaItem
                    if (currentItem != null && !currentItem.isLocal) {
                        val currentId = currentItem.mediaId.removePrefix("https://youtube.com/watch?v=").trim()
                        val currentIdx = player.currentMediaItemIndex
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val shouldPlay = player.playWhenReady
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching { cache.removeResource(currentId) }
                            withContext(Dispatchers.Main) {
                                runCatching {
                                    player.stop()
                                    player.seekTo(currentIdx, pos)
                                    player.prepare()
                                    player.playWhenReady = shouldPlay
                                }
                            }
                        }
                    }
                } else {
                    maybeRecoverPlaybackError()
                }
            }

            subscribe(AppearancePreferences.isShowingThumbnailInLockscreenProperty) {
                maybeShowSongCoverInLockScreen()
            }

            subscribe(PlayerPreferences.bassBoostLevelProperty) { maybeBassBoost() }
            subscribe(PlayerPreferences.bassBoostProperty) { maybeBassBoost() }
            subscribe(PlayerPreferences.reverbProperty) { maybeReverb() }
            subscribe(PlayerPreferences.isInvincibilityEnabledProperty) {
                this@PlayerService.isInvincibilityEnabled = it
            }
            subscribe(PlayerPreferences.pitchProperty) {
                player.setPlaybackPitch(it.coerceAtLeast(0.01f))
            }
            subscribe(PlayerPreferences.queueLoopEnabledProperty) { updateRepeatMode() }
            subscribe(PlayerPreferences.resumePlaybackWhenDeviceConnectedProperty) {
                maybeResumePlaybackWhenDeviceConnected()
            }
            subscribe(PlayerPreferences.skipSilenceProperty) { player.skipSilenceEnabled = it }
            subscribe(PlayerPreferences.speedProperty) {
                player.setPlaybackSpeed(it.coerceAtLeast(0.01f))
                publishDiscordPresence()
            }
            subscribe(PlayerPreferences.trackLoopEnabledProperty) {
                updateRepeatMode()
                updateNotification()
            }
            subscribe(PlayerPreferences.volumeNormalizationBaseGainProperty) { maybeNormalizeVolume() }
            subscribe(PlayerPreferences.volumeNormalizationProperty) { maybeNormalizeVolume() }
            subscribe(PlayerPreferences.sponsorBlockEnabledProperty) { maybeSponsorBlock() }
            subscribe(PlayerPreferences.crossfadeEnabledProperty) { maybeCrossfade() }
            subscribe(PlayerPreferences.crossfadeDurationProperty) { maybeCrossfade() }
            subscribe(DataPreferences.lastFmEnabledProperty) { maybeLastFmScrobble() }
            subscribe(DataPreferences.discordRpcEnabledProperty) { enabled ->
                if (!enabled) {
                    DiscordRpcManager.disconnect()
                } else {
                    publishDiscordPresence()
                }
            }

            DiscordRpcManager.init(this@PlayerService)
            launch {
                DiscordRpcManager.accessTokenFlow.collectLatest { token ->
                    if (!token.isNullOrEmpty() && DataPreferences.discordRpcEnabled) {
                        handler.post { publishDiscordPresence() }
                    }
                }
            }
            launch {
                DiscordRpcManager.connectionStatus.collectLatest { status ->
                    if (status == DiscordRpcManager.Status.Connected && DataPreferences.discordRpcEnabled) {
                        handler.post { publishDiscordPresence() }
                    }
                }
            }
            launch {
                DiscordRpcManager.settingsChanged.collectLatest {
                    if (DataPreferences.discordRpcEnabled) {
                        handler.post { publishDiscordPresence() }
                    }
                }
            }

            maybeLastFmScrobble()
            handler.post { publishDiscordPresence() }
            maybeCrossfade()

            launch {
                val audioManager = getSystemService<AudioManager>()
                val stream = AudioManager.STREAM_MUSIC

                val min = when {
                    audioManager == null -> 0
                    isAtLeastAndroid9 -> audioManager.getStreamMinVolume(stream)

                    else -> 0
                }

                streamVolumeFlow(stream).collectLatest {
                    if (PlayerPreferences.stopOnMinimumVolume && it == min) handler.post(player::pause)
                }
            }
        }
    }

    private fun updateRepeatMode() {
        player.repeatMode = when {
            PlayerPreferences.trackLoopEnabled -> Player.REPEAT_MODE_ONE
            PlayerPreferences.queueLoopEnabled -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.shouldBePlaying || PlayerPreferences.stopWhenClosed) {
            dismissNotification()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            isNotificationDismissed = false
            if (::mediaSession.isInitialized && !mediaSession.isActive) {
                mediaSession.isActive = true
            }
        } else {
            stopOutgoingCrossfadePlayer()
        }
        maybeSavePlayerQueue()
    }

    override fun onDestroy() {
        runCatching {
            maybeSavePlayerQueue()

            stopOutgoingCrossfadePlayer()
            outgoingCrossfadePlayer?.release()
            outgoingCrossfadePlayer = null

            player.removeListener(this)
            player.stop()
            player.release()

            unregisterReceiver(notificationActionReceiver)

            mediaSession.isActive = false
            mediaSession.release()
            cache.release()

            loudnessEnhancer?.release()
            preferenceUpdaterJob?.cancel()

            DiscordRpcManager.disconnect()
            DiscordRpcManager.destroy()

            coroutineScope.cancel()
            glyphInterface.close()
        }

        super.onDestroy()
    }

    override fun shouldBeInvincible() = !player.shouldBePlaying

    override fun onConfigurationChanged(newConfig: Configuration) {
        handler.post {
            if (!bitmapProvider.setDefaultBitmap() || player.currentMediaItem == null) return@post
            updateNotification()
        }

        super.onConfigurationChanged(newConfig)
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val totalPlayTimeMs = playbackStats.totalPlayTimeMs
        if (totalPlayTimeMs < 5000) return

        val mediaItem = eventTime.timeline[eventTime.windowIndex].mediaItem

        if (!DataPreferences.pausePlaytime) query {
            runCatching {
                Database.incrementTotalPlayTimeMs(mediaItem.mediaId, totalPlayTimeMs)
            }
        }

        if (!DataPreferences.pauseHistory) query {
            runCatching {
                Database.insert(
                    Event(
                        songId = mediaItem.mediaId,
                        timestamp = System.currentTimeMillis(),
                        playTime = totalPlayTimeMs
                    )
                )
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (
            AppearancePreferences.hideExplicit &&
            mediaItem?.mediaMetadata?.extras?.songBundle?.explicit == true
        ) {
            player.forceSeekToNext()
            return
        }

        mediaItemState.update { mediaItem }

        maybeRecoverPlaybackError()
        maybeNormalizeVolume()
        maybeProcessRadio()
        triggerCrossfadeIn(reason)

        val nextIdx = player.nextMediaItemIndex
        if (nextIdx != C.INDEX_UNSET && nextIdx in 0 until player.mediaItemCount) {
            runCatching { prewarmNextTrackUri(player.getMediaItemAt(nextIdx)) }
        }

        bitmapProvider.load(mediaItem?.mediaMetadata?.artworkUri) {
            maybeShowSongCoverInLockScreen()
            updateNotification()
        }

        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            updateMediaSessionQueue(player.currentTimeline)

        publishDiscordPresence()

        if (mediaItem != null) {
            if (app.vitune.android.utils.SpotifySyncManager.isLoggedIn() &&
                app.vitune.android.utils.SpotifySyncManager.isSyncPlaybackEnabled()
            ) {
                runCatching {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build()
                    player.setAudioAttributes(attrs, false)
                }
            }
            app.vitune.android.utils.SpotifySyncManager.onSongPlayed(
                songId = mediaItem.mediaId,
                fallbackTitle = mediaItem.mediaMetadata.title?.toString(),
                fallbackArtist = mediaItem.mediaMetadata.artist?.toString(),
                positionMs = 0L
            )
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
        updateMediaSessionQueue(timeline)
        maybeSavePlayerQueue()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        val currentItem = player.currentMediaItem
        val currentId = currentItem?.mediaId?.removePrefix("https://youtube.com/watch?v=")?.trim()
        val responseCode = error.findCause<InvalidResponseCodeException>()?.responseCode

        if (responseCode == 416) {
            player.pause()
            player.prepare()
            player.play()
            return
        }

        // Seamless mid-song or transient stream failure recovery before showing any error
        if (currentItem != null && !currentId.isNullOrBlank() && !currentItem.isLocal) {
            if (lastAutoRecoveryMediaId != currentId) {
                lastAutoRecoveryMediaId = currentId
                autoRecoveryAttempts = 0
            }
            if (autoRecoveryAttempts < 2) {
                autoRecoveryAttempts++
                val resumePosition = player.currentPosition.coerceAtLeast(0L)
                val currentIdx = player.currentMediaItemIndex
                val shouldPlay = player.playWhenReady || player.shouldBePlaying
                Log.w(TAG, "Recovering playback for $currentId at ${resumePosition}ms (attempt $autoRecoveryAttempts)", error)
                sharedUriCache.clear()
                coroutineScope.launch(Dispatchers.IO) {
                    if (responseCode == 403 || responseCode == 404 || error.findCause<UnplayableException>() != null) {
                        runCatching { cache.removeResource(currentId) }
                    }
                    withContext(Dispatchers.Main) {
                        runCatching {
                            player.seekTo(currentIdx, resumePosition)
                            player.prepare()
                            player.playWhenReady = shouldPlay
                            if (shouldPlay) player.play()
                        }
                    }
                }
                return
            }
        }

        if (!PlayerPreferences.skipOnError || !player.hasNextMediaItem()) return

        val prev = player.currentMediaItem ?: return
        player.seekToNextMediaItem()

        ServiceNotifications.autoSkip.sendNotification(this) {
            this
                .setSmallIcon(R.drawable.ic_notification)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setOnlyAlertOnce(false)
                .setContentIntent(activityPendingIntent<MainActivity>())
                .setContentText(
                    prev.mediaMetadata.title?.let {
                        getString(R.string.skip_on_error_notification, it)
                    } ?: getString(R.string.skip_on_error_notification_unknown_song)
                )
                .setContentTitle(getString(R.string.skip_on_error))
        }
    }

    private fun updateMediaSessionQueue(timeline: Timeline) {
        val builder = MediaDescription.Builder()

        val currentMediaItemIndex = player.currentMediaItemIndex
        val lastIndex = timeline.windowCount - 1
        var startIndex = currentMediaItemIndex - 7
        var endIndex = currentMediaItemIndex + 7

        if (startIndex < 0) endIndex -= startIndex

        if (endIndex > lastIndex) {
            startIndex -= (endIndex - lastIndex)
            endIndex = lastIndex
        }

        startIndex = startIndex.coerceAtLeast(0)

        mediaSession.setQueue(
            List(endIndex - startIndex + 1) { index ->
                val mediaItem = timeline.getWindow(index + startIndex, Timeline.Window()).mediaItem
                MediaSession.QueueItem(
                    builder
                        .setMediaId(mediaItem.mediaId)
                        .setTitle(mediaItem.mediaMetadata.title)
                        .setSubtitle(mediaItem.mediaMetadata.artist)
                        .setIconUri(mediaItem.mediaMetadata.artworkUri)
                        .build(),
                    (index + startIndex).toLong()
                )
            }
        )
    }

    private fun maybeRecoverPlaybackError() {
        if (player.playerError != null) player.prepare()
    }

    private fun maybeProcessRadio() {
        if (player.mediaItemCount - player.currentMediaItemIndex > 3) return

        radio?.let { radio ->
            coroutineScope.launch(Dispatchers.Main) {
                player.addMediaItems(radio.process())
            }
        }
    }

    private fun maybeSavePlayerQueue() {
        if (!PlayerPreferences.persistentQueue) return

        val mediaItems = player.currentTimeline.mediaItems
        val mediaItemIndex = player.currentMediaItemIndex
        val mediaItemPosition = player.currentPosition

        transaction {
            runCatching {
                Database.clearQueue()
                Database.insert(
                    mediaItems.mapIndexed { index, mediaItem ->
                        QueuedMediaItem(
                            mediaItem = mediaItem,
                            position = if (index == mediaItemIndex) mediaItemPosition else null
                        )
                    }
                )
            }
        }
    }

    private fun maybeRestorePlayerQueue() {
        if (!PlayerPreferences.persistentQueue) return

        transaction {
            val queue = Database.queue()
            if (queue.isEmpty()) return@transaction
            Database.clearQueue()

            val index = queue
                .indexOfFirst { it.position != null }
                .coerceAtLeast(0)

            handler.post {
                runCatching {
                    player.setMediaItems(
                        /* mediaItems = */ queue.map { item ->
                            item.mediaItem.buildUpon()
                                .setUri(item.mediaItem.mediaId)
                                .setCustomCacheKey(item.mediaItem.mediaId)
                                .build()
                                .apply {
                                    mediaMetadata.extras?.songBundle?.apply {
                                        isFromPersistentQueue = true
                                    }
                                }
                        },
                        /* startIndex = */ index,
                        /* startPositionMs = */ queue[index].position ?: C.TIME_UNSET
                    )
                    player.prepare()
                    mediaItemState.update { player.currentMediaItem }
                    bitmapProvider.load(player.currentMediaItem?.mediaMetadata?.artworkUri) {
                        maybeShowSongCoverInLockScreen()
                        if (player.shouldBePlaying) updateNotification()
                    }
                }
            }
        }
    }

    private fun maybeNormalizeVolume() {
        if (!PlayerPreferences.volumeNormalization) {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            volumeNormalizationJob?.cancel()
            volumeNormalizationJob?.invokeOnCompletion { volumeNormalizationJob = null }
            if (fadeInJob?.isActive != true && !PlayerPreferences.crossfadeEnabled) {
                player.volume = 1f
            }
            return
        }

        runCatching {
            if (loudnessEnhancer == null) loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)
        }.onFailure { return }

        val songId = player.currentMediaItem?.mediaId ?: return
        volumeNormalizationJob?.cancel()
        volumeNormalizationJob = coroutineScope.launch {
            runCatching {
                fun Float?.toMb() = ((this ?: 0f) * 100).toInt()

                Database.loudnessDb(songId).cancellable().collectLatest { loudness ->
                    val loudnessMb = loudness.toMb().let {
                        if (it !in -2000..2000) {
                            withContext(Dispatchers.Main) {
                                toast(
                                    getString(
                                        R.string.loudness_normalization_extreme,
                                        getString(R.string.format_db, (it / 100f).toString())
                                    )
                                )
                            }

                            0
                        } else it
                    }

                    Database.loudnessBoost(songId).cancellable().collectLatest { boost ->
                        withContext(Dispatchers.Main) {
                            loudnessEnhancer?.setTargetGain(
                                PlayerPreferences.volumeNormalizationBaseGain.toMb() + boost.toMb() - loudnessMb
                            )
                            loudnessEnhancer?.enabled = true
                        }
                    }
                }
            }
        }
    }

    private fun stopOutgoingCrossfadePlayer() {
        outgoingFadeJob?.cancel()
        outgoingFadeJob = null
        prepreparedOutgoingMediaId = null
        handler.post {
            runCatching {
                outgoingCrossfadePlayer?.playWhenReady = false
                outgoingCrossfadePlayer?.volume = 0f
                outgoingCrossfadePlayer?.stop()
                outgoingCrossfadePlayer?.clearMediaItems()
            }
        }
    }

    private fun prewarmNextTrackUri(nextItem: MediaItem) {
        val rawId = nextItem.mediaId.removePrefix("https://youtube.com/watch?v=").trim()
        if (rawId.isBlank() || nextItem.isLocal || rawId == prewarmedNextMediaId) return
        if (sharedUriCache[rawId] != null) {
            prewarmedNextMediaId = rawId
            return
        }
        prewarmedNextMediaId = rawId
        prewarmJob?.cancel()
        prewarmJob = coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val body = Innertube.player(PlayerBody(videoId = rawId))?.getOrNull()
                val title = nextItem.mediaMetadata.title?.toString()
                val artist = nextItem.mediaMetadata.artist?.toString()
                val album = nextItem.mediaMetadata.albumTitle?.toString()

                if (PlayerPreferences.audioQuality == PlayerPreferences.AudioQuality.VeryHigh &&
                    !title.isNullOrBlank() && !artist.isNullOrBlank()
                ) {
                    val jioResult = jioSaavnResolver.resolve(
                        TrackQuery(
                            title = title,
                            artist = artist,
                            album = album
                        ),
                        kbps = 320
                    )
                    if (jioResult != null) {
                        val jioUri = jioResult.downloadUrl.toUri()
                        val probedLength = getContentLength(jioResult.downloadUrl)
                        sharedUriCache.push(key = rawId, meta = null, uri = jioUri)
                        transaction {
                            runCatching {
                                Database.insert(nextItem)
                                Database.insert(
                                    Format(
                                        songId = rawId,
                                        itag = 320,
                                        mimeType = "audio/mp4",
                                        bitrate = 320_000L,
                                        loudnessDb = body?.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                        contentLength = probedLength,
                                        lastModified = null
                                    )
                                )
                            }
                        }
                        return@launch
                    }
                }
                val dl = Dependencies.runDownload(rawId, PlayerPreferences.audioQuality.youtubeDownloadDisplayName)
                val info = YouTubeDLResponse.fromString(dl)
                val extractedUrl = info.url?.takeIf { it.isNotBlank() } ?: return@launch
                val uri = extractedUrl.toUri()
                val contentLength = info.fileSize.takeIf { it > 0 } ?: getContentLength(extractedUrl)
                sharedUriCache.push(key = rawId, meta = contentLength, uri = uri)

                val youtubeFormat = body?.streamingData?.getFormat(PlayerPreferences.audioQuality.youtubeFormatName)
                val format = info.formats?.firstOrNull { it.formatId == info.formatId }
                val calculatedBitrate = format?.abr?.let { (it * 1000).toLong() }
                    ?: youtubeFormat?.bitrate?.toLong()
                transaction {
                    runCatching {
                        Database.insert(nextItem)
                        Database.insert(
                            Format(
                                songId = rawId,
                                itag = info.formatId?.toIntOrNull() ?: youtubeFormat?.itag,
                                mimeType = youtubeFormat?.mimeType,
                                bitrate = calculatedBitrate,
                                loudnessDb = body?.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                contentLength = contentLength,
                                lastModified = youtubeFormat?.lastModified
                            )
                        )
                    }
                }
            }
        }
    }

    private fun triggerCrossfadeIn(reason: Int) {
        fadeInJob?.cancel()
        if (!PlayerPreferences.crossfadeEnabled) {
            isCrossfadeTransitioning = false
            player.volume = 1f
            return
        }

        // If user manually skipped without an active auto-crossfade, stop any leftover outgoing tail
        if (!isCrossfadeTransitioning && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            stopOutgoingCrossfadePlayer()
        }

        val durationSec = PlayerPreferences.crossfadeDuration.coerceIn(1, 12)
        val isAutoOrCrossfade = isCrossfadeTransitioning || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        val fadeInMs = if (isAutoOrCrossfade) {
            (durationSec * 1000L).coerceIn(1000L, 12_000L)
        } else {
            450L
        }

        val startVol = if (isAutoOrCrossfade) 0.02f else 0.25f
        player.volume = startVol
        isCrossfadeTransitioning = false

        fadeInJob = coroutineScope.launch(Dispatchers.Main) {
            // Wait until the incoming track has finished buffering and is actively rendering audio
            var waitedMs = 0L
            while (isActive && waitedMs < 6000L) {
                if (player.playbackState == Player.STATE_READY && player.isPlaying) break
                delay(35L)
                waitedMs += 35L
            }

            val steps = 40
            val stepDelay = (fadeInMs / steps).coerceAtLeast(20L)
            for (i in 1..steps) {
                if (!isActive || !PlayerPreferences.crossfadeEnabled) break
                if (!player.playWhenReady) break
                val linear = i.toFloat() / steps.toFloat()
                // Equal-power sine fade-in curve matching cosine fade-out
                val curved = kotlin.math.sin(linear * (Math.PI / 2.0)).toFloat()
                player.volume = (startVol + (1f - startVol) * curved).coerceIn(0f, 1f)
                delay(stepDelay)
            }
            player.volume = 1f
        }
    }

    private fun maybeCrossfade() {
        crossfadeJob?.cancel()
        if (!PlayerPreferences.crossfadeEnabled) {
            fadeInJob?.cancel()
            prewarmJob?.cancel()
            stopOutgoingCrossfadePlayer()
            isCrossfadeTransitioning = false
            handler.post { player.volume = 1f }
            return
        }

        crossfadeJob = coroutineScope.launch {
            var lastCrossfadedMediaId: String? = null
            var fallbackFadingMediaId: String? = null
            var nextSleepMs = 400L
            val ctx = currentCoroutineContext()
            while (ctx.isActive) {
                delay(nextSleepMs)
                if (!PlayerPreferences.crossfadeEnabled) {
                    withContext(Dispatchers.Main) {
                        stopOutgoingCrossfadePlayer()
                        player.volume = 1f
                    }
                    break
                }

                withContext(Dispatchers.Main) {
                    if (!player.isPlaying || player.playbackState != Player.STATE_READY) {
                        nextSleepMs = 600L
                        return@withContext
                    }
                    val durationMs = player.duration
                    val positionMs = player.currentPosition
                    if (durationMs <= 10_000L || positionMs <= 0L || durationMs == C.TIME_UNSET) {
                        nextSleepMs = 600L
                        return@withContext
                    }

                    val canTransition = player.repeatMode == Player.REPEAT_MODE_ONE ||
                        player.repeatMode == Player.REPEAT_MODE_ALL ||
                        player.hasNextMediaItem()
                    if (!canTransition) return@withContext

                    val totalCrossfadeMs = (PlayerPreferences.crossfadeDuration.coerceIn(1, 12) * 1000L)
                        .coerceAtMost(durationMs / 3)
                    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
                    val currentItem = player.currentMediaItem ?: return@withContext
                    val currentId = currentItem.mediaId

                    // Step 1: Pre-warm the next song's stream URI ~18s before crossfade so transition is instant
                    if (remainingMs <= totalCrossfadeMs + 18_000L && player.repeatMode != Player.REPEAT_MODE_ONE) {
                        val nextIdx = player.nextMediaItemIndex
                        if (nextIdx != C.INDEX_UNSET && nextIdx < player.mediaItemCount) {
                            val nextItem = player.getMediaItemAt(nextIdx)
                            prewarmNextTrackUri(nextItem)
                        }
                    }

                    // Step 2: Pre-prepare the outgoing auxiliary player ~3.5s before the crossfade window
                    val auxPlayer = outgoingCrossfadePlayer
                    if (
                        auxPlayer != null &&
                        player.repeatMode != Player.REPEAT_MODE_ONE &&
                        currentId != lastCrossfadedMediaId &&
                        prepreparedOutgoingMediaId != currentId &&
                        remainingMs in (totalCrossfadeMs + 250L)..(totalCrossfadeMs + 3800L)
                    ) {
                        runCatching {
                            outgoingFadeJob?.cancel()
                            auxPlayer.playWhenReady = false
                            auxPlayer.volume = 0f
                            auxPlayer.stop()
                            auxPlayer.clearMediaItems()
                            val clonedItem = currentItem.buildUpon()
                                .setUri(currentItem.mediaId)
                                .setCustomCacheKey(currentItem.mediaId)
                                .build()
                            val targetCrossfadeStartPos = (durationMs - totalCrossfadeMs).coerceAtLeast(0L)
                            auxPlayer.setMediaItem(clonedItem, targetCrossfadeStartPos)
                            auxPlayer.playbackParameters = player.playbackParameters
                            auxPlayer.prepare()
                            prepreparedOutgoingMediaId = currentId
                        }
                    }

                    // Step 3: Trigger dual-player crossfade overlap when entering the crossfade window
                    if (remainingMs in 300L..totalCrossfadeMs && currentId != lastCrossfadedMediaId) {
                        lastCrossfadedMediaId = currentId

                        var overlapStarted = false
                        if (auxPlayer != null && player.repeatMode != Player.REPEAT_MODE_ONE) {
                            runCatching {
                                outgoingFadeJob?.cancel()
                                if (prepreparedOutgoingMediaId != currentId || auxPlayer.mediaItemCount == 0) {
                                    auxPlayer.stop()
                                    auxPlayer.clearMediaItems()
                                    val clonedItem = currentItem.buildUpon()
                                        .setUri(currentItem.mediaId)
                                        .setCustomCacheKey(currentItem.mediaId)
                                        .build()
                                    auxPlayer.setMediaItem(clonedItem, positionMs)
                                    auxPlayer.playbackParameters = player.playbackParameters
                                    auxPlayer.volume = 0f
                                    auxPlayer.prepare()
                                    prepreparedOutgoingMediaId = currentId
                                } else {
                                    auxPlayer.playbackParameters = player.playbackParameters
                                    auxPlayer.seekTo(positionMs)
                                }
                                auxPlayer.volume = 0f
                                auxPlayer.playWhenReady = true
                            }

                            var readyWaitMs = 0L
                            while (readyWaitMs < 1200L && ctx.isActive) {
                                if (auxPlayer.playbackState == Player.STATE_READY && auxPlayer.isPlaying) {
                                    overlapStarted = true
                                    break
                                }
                                delay(25L)
                                readyWaitMs += 25L
                            }
                        }

                        if (overlapStarted && auxPlayer != null) {
                            val fadeOutDuration = (durationMs - player.currentPosition).coerceIn(1000L, totalCrossfadeMs)
                            val initialOutVol = player.volume.coerceIn(0.45f, 1f)
                            auxPlayer.seekTo(player.currentPosition)
                            auxPlayer.volume = initialOutVol

                            outgoingFadeJob = coroutineScope.launch(Dispatchers.Main) {
                                val steps = 40
                                val stepDelay = (fadeOutDuration / steps).coerceAtLeast(20L)
                                for (i in 1..steps) {
                                    if (!isActive || !PlayerPreferences.crossfadeEnabled || !player.playWhenReady) break
                                    val linear = i.toFloat() / steps.toFloat()
                                    val curved = kotlin.math.cos(linear * (Math.PI / 2.0)).toFloat()
                                    auxPlayer.volume = (initialOutVol * curved).coerceIn(0f, 1f)
                                    delay(stepDelay)
                                }
                                stopOutgoingCrossfadePlayer()
                            }

                            isCrossfadeTransitioning = true
                            fadeInJob?.cancel()
                            player.volume = 0.02f
                            if (player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ALL) {
                                player.seekToNextMediaItem()
                                player.play()
                            }
                        } else {
                            // Single-player crossfade fallback if auxiliary player couldn't attach
                            stopOutgoingCrossfadePlayer()
                            fallbackFadingMediaId = currentId
                        }
                    } else if (remainingMs in 1L..totalCrossfadeMs && fallbackFadingMediaId == currentId) {
                        fadeInJob?.cancel()
                        val switchRemainingMs = (totalCrossfadeMs / 2L).coerceIn(700L, 2500L)
                        val fadeOutSpan = (totalCrossfadeMs - switchRemainingMs).coerceAtLeast(400L)
                        val progressInFadeOut = ((remainingMs - switchRemainingMs).toFloat() / fadeOutSpan.toFloat())
                            .coerceIn(0f, 1f)
                        val curved = kotlin.math.sin(progressInFadeOut * (Math.PI / 2.0)).toFloat()
                        player.volume = curved.coerceIn(0.03f, 1f)

                        if (remainingMs <= switchRemainingMs) {
                            fallbackFadingMediaId = null
                            isCrossfadeTransitioning = true
                            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                                player.seekTo(0L)
                                triggerCrossfadeIn(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                            } else if (player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ALL) {
                                player.seekToNextMediaItem()
                                player.play()
                            }
                        }
                    } else if (remainingMs > totalCrossfadeMs + 1500L) {
                        if (lastCrossfadedMediaId == currentId && positionMs < durationMs / 2) {
                            // Reset if the user seeked backward in the same track
                            lastCrossfadedMediaId = null
                            fallbackFadingMediaId = null
                        }
                        if (fadeInJob?.isActive != true && player.volume < 0.99f) {
                            player.volume = 1f
                        }
                    }

                    nextSleepMs = when {
                        remainingMs <= totalCrossfadeMs + 3500L -> 90L
                        remainingMs <= totalCrossfadeMs + 12_000L -> 300L
                        else -> 1000L
                    }
                }
            }
        }
    }

    private fun maybeLastFmScrobble() {
        if (!DataPreferences.lastFmEnabled || DataPreferences.lastFmSessionKey.isBlank()) {
            lastFmJob?.cancel()
            lastFmJob = null
            return
        }

        lastFmJob?.cancel()
        lastFmJob = coroutineScope.launch {
            mediaItemState.collectLatest { mediaItem ->
                if (mediaItem == null) return@collectLatest

                val sessionKey = DataPreferences.lastFmSessionKey
                if (sessionKey.isBlank() || !DataPreferences.lastFmEnabled) return@collectLatest

                val title = mediaItem.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@collectLatest
                val artist = mediaItem.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@collectLatest
                val album = mediaItem.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }

                val trackStartTimestamp = System.currentTimeMillis() / 1000
                var hasScrobbled = false

                val apiKey = DataPreferences.lastFmCustomApiKey.ifBlank { LastFmApi.DEFAULT_API_KEY }
                val apiSecret = DataPreferences.lastFmCustomApiSecret.ifBlank { LastFmApi.DEFAULT_API_SECRET }

                runCatching {
                    val durationSeconds = withContext(Dispatchers.Main) {
                        (player.duration.takeIf { it > 0 }?.div(1000))?.toInt()
                    }
                    LastFmApi.updateNowPlaying(
                        artist = artist,
                        track = title,
                        sessionKey = sessionKey,
                        album = album,
                        duration = durationSeconds,
                        apiKey = apiKey,
                        apiSecret = apiSecret
                    )
                }

                val ctx = currentCoroutineContext()
                while (ctx.isActive && !hasScrobbled) {
                    delay(2000.milliseconds)

                    if (!DataPreferences.lastFmEnabled) break

                    val (positionMs, durationMs, isPlaying) = withContext(Dispatchers.Main) {
                        Triple(player.currentPosition, player.duration, player.isPlaying)
                    }

                    if (!isPlaying) continue

                    val isValidDuration = durationMs <= 0 || durationMs >= 30_000
                    val scrobblePointMs = if (durationMs > 0) {
                        (durationMs / 2).coerceAtMost(240_000L)
                    } else 240_000L

                    if (isValidDuration && positionMs >= 30_000 && positionMs >= scrobblePointMs) {
                        hasScrobbled = true
                        val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else null
                        runCatching {
                            LastFmApi.scrobble(
                                artist = artist,
                                track = title,
                                timestamp = trackStartTimestamp,
                                sessionKey = sessionKey,
                                album = album,
                                duration = durationSec,
                                apiKey = apiKey,
                                apiSecret = apiSecret
                            )
                        }
                    }
                }
            }
        }
    }

    private val discordPresenceRunnable = Runnable {
        publishDiscordPresenceInternal()
    }

    private fun parseDurationTextMs(durationText: String?): Long {
        if (durationText.isNullOrBlank()) return 0L
        val parts = durationText.trim().split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> 0L
        }
    }

    private fun publishDiscordPresence() {
        handler.removeCallbacks(discordPresenceRunnable)
        handler.postDelayed(discordPresenceRunnable, 50L)
    }

    private fun publishDiscordPresenceInternal() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(discordPresenceRunnable)
            return
        }
        if (!::player.isInitialized) return

        runCatching {
            val hasToken = !DiscordRpcManager.getAccessToken().isNullOrEmpty()
            if (!DataPreferences.discordRpcEnabled || !hasToken) {
                if (DiscordRpcManager.isReady() || DiscordRpcManager.isAuthorized()) {
                    DiscordRpcManager.disconnect()
                }
                return
            }

            val item = player.currentMediaItem
            val state = player.playbackState
            if (item == null || state == Player.STATE_ENDED || !player.playWhenReady) {
                if (DiscordRpcManager.isReady()) {
                    DiscordRpcManager.clear()
                }
                return
            }

            val title = item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: run {
                if (DiscordRpcManager.isReady()) {
                    DiscordRpcManager.clear()
                }
                return
            }
            val songId = item.mediaId.removePrefix("https://youtube.com/watch?v=").trim()
            val artist = item.mediaMetadata.artist?.toString().orEmpty()
            val album = item.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }
            val artworkUrl = item.mediaMetadata.artworkUri?.toString()?.takeIf { it.isNotBlank() }
                ?: songId.takeIf { it.isNotBlank() }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            val rawPlayerDuration = player.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
            val durationMs = if (rawPlayerDuration > 0L) {
                rawPlayerDuration
            } else {
                parseDurationTextMs(item.mediaMetadata.extras?.songBundle?.durationText)
            }
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val speed = player.playbackParameters.speed.coerceAtLeast(0.1f)
            val now = System.currentTimeMillis()
            val adjustedStart = now - (positionMs / speed).toLong()
            val adjustedEnd = if (durationMs > 0L) adjustedStart + (durationMs / speed).toLong() else null

            val advancedMode = DataPreferences.discordAdvancedMode
            val activityType = DataPreferences.discordActivityType.toIntOrNull() ?: DiscordActivity.TYPE_LISTENING
            val activityName = DataPreferences.discordActivityName
            val detailsTemplate = DataPreferences.discordDetailsTemplate
            val stateTemplate = DataPreferences.discordStateTemplate
            val button1Visible = DataPreferences.discordButton1Visible
            val button1Text = DataPreferences.discordButton1Text
            val button1Url = DataPreferences.discordButton1Url
            val button2Visible = DataPreferences.discordButton2Visible
            val button2Text = DataPreferences.discordButton2Text
            val button2Url = DataPreferences.discordButton2Url
            val statusKey = if (advancedMode) DataPreferences.discordStatus else DiscordDefaults.USER_STATUS
            val presenceStatus = when (statusKey) {
                DiscordDefaults.STATUS_IDLE -> PresenceStatus.Idle
                DiscordDefaults.STATUS_DND -> PresenceStatus.Dnd
                else -> PresenceStatus.Online
            }

            val activity = DiscordActivityBuilder.build(
                songId = songId,
                songTitle = title,
                artistName = artist,
                albumName = album,
                thumbnailUrl = artworkUrl,
                artistThumbnail = null,
                startTimestamp = adjustedStart,
                endTimestamp = adjustedEnd,
                advancedMode = advancedMode,
                activityType = activityType,
                activityName = activityName,
                stateTemplate = stateTemplate,
                detailsTemplate = detailsTemplate,
                btn1Enabled = button1Visible,
                btn1Label = button1Text,
                btn1Url = button1Url,
                btn2Enabled = button2Visible,
                btn2Label = button2Text,
                btn2Url = button2Url
            )

            DiscordRpcManager.setActivity(
                activity = activity,
                songId = songId,
                isPlaying = player.playWhenReady,
                status = presenceStatus
            )
        }
    }

    @Suppress("CyclomaticComplexMethod") // TODO: evaluate CyclomaticComplexMethod threshold
    private fun maybeSponsorBlock() {
        poiTimestamp = null

        if (!PlayerPreferences.sponsorBlockEnabled) {
            sponsorBlockJob?.cancel()
            sponsorBlockJob?.invokeOnCompletion { sponsorBlockJob = null }
            return
        }

        sponsorBlockJob?.cancel()
        sponsorBlockJob = coroutineScope.launch {
            mediaItemState.onStart { emit(mediaItemState.value) }.collectLatest { mediaItem ->
                poiTimestamp = null
                val videoId = mediaItem?.mediaId
                    ?.removePrefix("https://youtube.com/watch?v=")
                    ?.takeIf { it.isNotBlank() } ?: return@collectLatest

                SponsorBlock
                    .segments(videoId)
                    ?.onSuccess { segments ->
                        poiTimestamp =
                            segments.find { it.category == Category.PoiHighlight }?.start?.inWholeMilliseconds
                    }
                    ?.map { segments ->
                        segments
                            .sortedBy { it.start.inWholeMilliseconds }
                            .filter { it.action == Action.Skip }
                    }
                    ?.mapCatching { segments ->
                        suspend fun posMillis() =
                            withContext(Dispatchers.Main) { player.currentPosition }

                        suspend fun speed() =
                            withContext(Dispatchers.Main) { player.playbackParameters.speed }

                        suspend fun seek(millis: Long) =
                            withContext(Dispatchers.Main) { player.seekTo(millis) }

                        val ctx = currentCoroutineContext()
                        val lastSegmentEnd =
                            segments.lastOrNull()?.end?.inWholeMilliseconds ?: return@mapCatching

                        @Suppress("LoopWithTooManyJumpStatements")
                        do {
                            if (lastSegmentEnd < posMillis()) {
                                yield()
                                continue
                            }

                            val nextSegment =
                                segments.firstOrNull { posMillis() < it.end.inWholeMilliseconds }
                                    ?: continue

                            // Wait for next segment
                            if (nextSegment.start.inWholeMilliseconds > posMillis()) {
                                val timeNextSegment =
                                    nextSegment.start.inWholeMilliseconds - posMillis()
                                val speed = speed().toDouble()
                                delay((timeNextSegment / speed).milliseconds)
                            }

                            if (posMillis().milliseconds !in nextSegment.start..nextSegment.end) {
                                // Player is not in the segment for some reason, maybe the user seeked in the meantime
                                yield()
                                continue
                            }

                            seek(nextSegment.end.inWholeMilliseconds)
                        } while (ctx.isActive)
                    }?.onFailure {
                        it.printStackTrace()
                    }
            }
        }
    }

    private fun maybeBassBoost() {
        if (!PlayerPreferences.bassBoost) {
            runCatching {
                bassBoost?.enabled = false
                bassBoost?.release()
            }
            bassBoost = null
            maybeNormalizeVolume()
            return
        }

        runCatching {
            if (bassBoost == null) bassBoost = BassBoost(0, player.audioSessionId)
            bassBoost?.setStrength(PlayerPreferences.bassBoostLevel.toShort())
            bassBoost?.enabled = true
        }.onFailure {
            toast(getString(R.string.error_bassboost_init))
        }
    }

    private fun maybeReverb() {
        if (PlayerPreferences.reverb == PlayerPreferences.Reverb.None) {
            runCatching {
                reverb?.enabled = false
                player.clearAuxEffectInfo()
                reverb?.release()
            }
            reverb = null
            return
        }

        runCatching {
            if (reverb == null) reverb = PresetReverb(1, player.audioSessionId)
            reverb?.preset = PlayerPreferences.reverb.preset
            reverb?.enabled = true
            reverb?.id?.let { player.setAuxEffectInfo(AuxEffectInfo(it, 1f)) }
        }
    }

    private fun maybeShowSongCoverInLockScreen() = handler.post {
        val bitmap = if (isAtLeastAndroid13 || AppearancePreferences.isShowingThumbnailInLockscreen)
            bitmapProvider.bitmap else null
        val uri = player.mediaMetadata.artworkUri?.toString()?.thumbnail(512)

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_ART_URI, uri)

        metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, uri)

        if (isAtLeastAndroid13 && player.currentMediaItemIndex == 0) metadataBuilder.putText(
            MediaMetadata.METADATA_KEY_TITLE,
            "${player.mediaMetadata.title} "
        )

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun maybeResumePlaybackWhenDeviceConnected() {
        if (!isAtLeastAndroid6) return

        if (!PlayerPreferences.resumePlaybackWhenDeviceConnected) {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallback = null
            return
        }
        if (audioManager == null) audioManager = getSystemService<AudioManager>()

        audioDeviceCallback = object : AudioDeviceCallback() {
            private fun canPlayMusic(audioDeviceInfo: AudioDeviceInfo) =
                audioDeviceInfo.isSink && (
                    audioDeviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        audioDeviceInfo.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    )
                    .let {
                        if (!isAtLeastAndroid8) it else
                            it || audioDeviceInfo.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }

            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                if (!player.isPlaying && addedDevices.any(::canPlayMusic)) player.play()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = Unit
        }

        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, handler)
    }

    private fun openEqualizer() =
        EqualizerIntentBundleAccessor.sendOpenEqualizer(player.audioSessionId)

    private fun closeEqualizer() =
        EqualizerIntentBundleAccessor.sendCloseEqualizer(player.audioSessionId)

    private fun updatePlaybackState() = coroutineScope.launch {
        playbackStateMutex.withLock {
            withContext(Dispatchers.Main) {
                mediaSession.setPlaybackState(
                    stateBuilder
                        .setState(
                            player.androidPlaybackState,
                            player.currentPosition,
                            player.playbackParameters.speed,
                            SystemClock.elapsedRealtime()
                        )
                        .setBufferedPosition(player.bufferedPosition)
                        .build()
                )
            }
        }
    }

    private val Player.androidPlaybackState
        get() = when (playbackState) {
            Player.STATE_BUFFERING -> if (playWhenReady) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PAUSED
            Player.STATE_READY -> if (playWhenReady) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            Player.STATE_IDLE -> PlaybackState.STATE_NONE
            else -> PlaybackState.STATE_NONE
        }

    // legacy behavior may cause inconsistencies, but not available on sdk 24 or lower
    @Suppress("DEPRECATION")
    override fun onEvents(player: Player, events: Player.Events) {
        if (player.duration != C.TIME_UNSET) mediaSession.setMetadata(
            metadataBuilder
                .putText(
                    MediaMetadata.METADATA_KEY_TITLE,
                    player.mediaMetadata.title?.toString().orEmpty()
                )
                .putText(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    player.mediaMetadata.artist?.toString().orEmpty()
                )
                .putText(
                    MediaMetadata.METADATA_KEY_ALBUM,
                    player.mediaMetadata.albumTitle?.toString().orEmpty()
                )
                .putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
                .build()
        )

        updatePlaybackState()

        if (
            !events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_IS_LOADING_CHANGED,
                Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION
            )
        ) return

        val notification = notification()

        if (notification == null) {
            dismissNotification()
            return
        }

        if (
            events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_MEDIA_METADATA_CHANGED
            )
        ) {
            publishDiscordPresence()
        }

        if (player.shouldBePlaying && !isNotificationStarted) {
            isNotificationDismissed = false
            isNotificationStarted = true
            if (!mediaSession.isActive) mediaSession.isActive = true
            startForegroundService(this@PlayerService, intent<PlayerService>())
            startForeground()
            makeInvincible(false)
            openEqualizer()
        } else {
            if (!player.shouldBePlaying) {
                isNotificationStarted = false
                @Suppress("DEPRECATION")
                stopForeground(false)
                makeInvincible(true)
                closeEqualizer()
            }
            if (!isNotificationDismissed || player.shouldBePlaying) {
                updateNotification()
            }
        }
    }

    internal fun dismissNotification() {
        isNotificationDismissed = true
        isNotificationStarted = false
        handler.post {
            runCatching {
                player.pause()
                stopOutgoingCrossfadePlayer()
                makeInvincible(false)
                closeEqualizer()
                if (::mediaSession.isInitialized) {
                    mediaSession.setPlaybackState(
                        stateBuilder
                            .setState(PlaybackState.STATE_STOPPED, player.currentPosition, 0f, SystemClock.elapsedRealtime())
                            .build()
                    )
                    mediaSession.isActive = false
                }
                @Suppress("DEPRECATION")
                stopForeground(true)
                ServiceNotifications.default.cancel(this@PlayerService)
            }
        }
    }

    private fun notification(): (NotificationCompat.Builder.() -> NotificationCompat.Builder)? {
        if (player.currentMediaItem == null) return null

        val mediaMetadata = player.mediaMetadata

        if (bitmapProvider.lastUri != mediaMetadata.artworkUri) {
            bitmapProvider.load(mediaMetadata.artworkUri) {
                maybeShowSongCoverInLockScreen()
                updateNotification()
            }
        }

        return {
            this
                .setContentTitle(mediaMetadata.title?.toString().orEmpty())
                .setContentText(mediaMetadata.artist?.toString().orEmpty())
                .setSubText(player.playerError?.message)
                .setLargeIcon(bitmapProvider.bitmap)
                .setAutoCancel(!player.shouldBePlaying)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setSmallIcon(
                    player.playerError?.let { R.drawable.alert_circle } ?: R.drawable.ic_notification
                )
                .setOngoing(player.shouldBePlaying)
                .setContentIntent(
                    activityPendingIntent<MainActivity>(flags = PendingIntent.FLAG_UPDATE_CURRENT)
                )
                .setDeleteIntent(notificationActionReceiver.dismiss.pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(
                    R.drawable.play_skip_back,
                    getString(R.string.skip_back),
                    notificationActionReceiver.previous.pendingIntent
                )
                .let {
                    if (player.shouldBePlaying) it.addAction(
                        R.drawable.pause,
                        getString(R.string.pause),
                        notificationActionReceiver.pause.pendingIntent
                    )
                    else it.addAction(
                        R.drawable.play,
                        getString(R.string.play),
                        notificationActionReceiver.play.pendingIntent
                    )
                }
                .addAction(
                    R.drawable.play_skip_forward,
                    getString(R.string.skip_forward),
                    notificationActionReceiver.next.pendingIntent
                )
                .addAction(
                    if (isLikedState.value) R.drawable.heart else R.drawable.heart_outline,
                    getString(R.string.like),
                    notificationActionReceiver.like.pendingIntent
                )
                .let {
                    if (player.shouldBePlaying) {
                        it.addAction(
                            if (PlayerPreferences.trackLoopEnabled) R.drawable.repeat_on else R.drawable.repeat,
                            getString(R.string.queue_loop),
                            notificationActionReceiver.loop.pendingIntent
                        )
                    } else {
                        it.addAction(
                            R.drawable.close,
                            "Close",
                            notificationActionReceiver.dismiss.pendingIntent
                        )
                    }
                }
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                        .setMediaSession(MediaSessionCompat.Token.fromToken(mediaSession.sessionToken))
                        .setCancelButtonIntent(notificationActionReceiver.dismiss.pendingIntent)
                        .setShowCancelButton(true)
                )
        }
    }

    private fun updateNotification() = runCatching {
        handler.post {
            if (isNotificationDismissed && !player.shouldBePlaying) return@post
            notification()?.let { ServiceNotifications.default.sendNotification(this, it) }
        }
    }

    override fun startForeground() {
        isNotificationDismissed = false
        notification()
            ?.let { ServiceNotifications.default.startForeground(this, it) }
    }

    private fun createMediaSourceFactory() = DefaultMediaSourceFactory(
        /* dataSourceFactory = */ createYouTubeDataSourceResolverFactory(
            findMediaItem = { videoId ->
                withContext(Dispatchers.Main) {
                    player.findNextMediaItemById(videoId)
                }
            },
            context = applicationContext,
            cache = cache,
            uriCache = sharedUriCache
        ),
        /* extractorsFactory = */ DefaultExtractorsFactory()
    ).setLoadErrorHandlingPolicy(
        object : DefaultLoadErrorHandlingPolicy() {
            override fun isEligibleForFallback(exception: IOException) = true
        }
    )

    private fun createRendersFactory() = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            val minimumSilenceDuration =
                PlayerPreferences.minimumSilence.coerceIn(1000L..2_000_000L)

            @Suppress("DEPRECATION")
            return DefaultAudioSink.Builder(applicationContext)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioOffloadSupportProvider(
                    DefaultAudioOffloadSupportProvider(applicationContext)
                )
                .setAudioProcessorChain(
                    DefaultAudioProcessorChain(
                        arrayOf(),
                        SilenceSkippingAudioProcessor(
                            /* minimumSilenceDurationUs = */ minimumSilenceDuration,
                            /* silenceRetentionRatio = */ 0.01f,
                            /* maxSilenceToKeepDurationUs = */ minimumSilenceDuration,
                            /* minVolumeToKeepPercentageWhenMuting = */ 0,
                            /* silenceThresholdLevel = */ 256
                        ),
                        SonicAudioProcessor()
                    )
                )
                .build()
                .apply {
                    if (isAtLeastAndroid10) setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                }
        }
    }

    @Stable
    inner class Binder : AndroidBinder() {
        val player: ExoPlayer
            get() = this@PlayerService.player

        val cache: Cache
            get() = this@PlayerService.cache

        val mediaSession
            get() = this@PlayerService.mediaSession

        val sleepTimerMillisLeft: StateFlow<Long?>?
            get() = timerJob?.millisLeft

        private var radioJob: Job? = null

        var isLoadingRadio by mutableStateOf(false)
            private set

        var invincible
            get() = isInvincibilityEnabled
            set(value) {
                isInvincibilityEnabled = value
            }

        val poiTimestamp get() = this@PlayerService.poiTimestamp

        fun setBitmapListener(listener: ((Bitmap?) -> Unit)?) = bitmapProvider.setListener(listener)
        fun addBitmapListener(listener: (Bitmap?) -> Unit) = bitmapProvider.addListener(listener)
        fun removeBitmapListener(listener: (Bitmap?) -> Unit) = bitmapProvider.removeListener(listener)

        @kotlin.OptIn(FlowPreview::class)
        fun startSleepTimer(delayMillis: Long) {
            timerJob?.cancel()

            timerJob = coroutineScope.timer(delayMillis) {
                ServiceNotifications.sleepTimer.sendNotification(this@PlayerService) {
                    this
                        .setContentTitle(getString(R.string.sleep_timer_ended))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(true)
                        .setSmallIcon(R.drawable.ic_notification)
                }

                handler.post {
                    player.pause()
                    player.stop()

                    glyphInterface.glyph {
                        turnOff()
                    }
                }
            }.also { job ->
                glyphInterface.progress(
                    job
                        .millisLeft
                        .takeWhile { it != null }
                        .debounce(500.milliseconds)
                        .map { ((it ?: 0L) / delayMillis.toFloat() * 100).toInt() }
                )
            }
        }

        fun cancelSleepTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        fun setupRadio(endpoint: NavigationEndpoint.Endpoint.Watch?) =
            startRadio(endpoint = endpoint, justAdd = true)

        fun playRadio(endpoint: NavigationEndpoint.Endpoint.Watch?, source: String? = null) =
            startRadio(endpoint = endpoint, justAdd = false, source = source)

        private fun startRadio(
            endpoint: NavigationEndpoint.Endpoint.Watch?,
            justAdd: Boolean,
            source: String? = null
        ) {
            radioJob?.cancel()
            radio = null

            val activeSource = source ?: PlaybackSourceState.currentSource

            YouTubeRadio(
                endpoint?.videoId,
                endpoint?.playlistId,
                endpoint?.playlistSetVideoId,
                endpoint?.params
            ).let { radioData ->
                isLoadingRadio = true
                radioJob = coroutineScope.launch {
                    val items = radioData.process().let { Database.filterBlacklistedSongs(it) }

                    withContext(Dispatchers.Main) {
                        if (justAdd) {
                            val tagged = if (!activeSource.isNullOrBlank()) {
                                items.drop(1).map { it.withPlaybackSource(activeSource) }
                            } else {
                                items.drop(1)
                            }
                            player.addMediaItems(tagged)
                        } else {
                            player.forcePlayFromBeginning(items, source = activeSource)
                        }
                    }

                    radio = radioData
                    isLoadingRadio = false
                }
            }
        }

        fun stopRadio() {
            isLoadingRadio = false
            radioJob?.cancel()
            radio = null
        }

        /**
         * This method should ONLY be called when the application (sc. activity) is in the foreground!
         */
        fun restartForegroundOrStop() {
            player.pause()
            isInvincibilityEnabled = false
            stopSelf()
        }

        fun isCached(song: SongWithContentLength) =
            song.contentLength?.let { cache.isCached(song.song.id, 0L, it) } ?: false

        fun playFromSearch(query: String) {
            coroutineScope.launch {
                Innertube.searchPage(
                    body = SearchBody(
                        query = query,
                        params = Innertube.SearchFilter.Song.value
                    ),
                    fromMusicShelfRendererContent = Innertube.SongItem.Companion::from
                )
                    ?.getOrNull()
                    ?.items
                    ?.firstOrNull()
                    ?.info
                    ?.endpoint
                    ?.let { playRadio(it) }
            }
        }
    }

    private fun likeAction() = mediaItemState.value?.let { mediaItem ->
        val newLikedAt = if (isLikedState.value) null else System.currentTimeMillis()
        query {
            runCatching {
                Database.like(
                    songId = mediaItem.mediaId,
                    likedAt = newLikedAt
                )
            }
        }
        if (newLikedAt != null) {
            app.vitune.android.utils.SpotifySyncManager.onSongLiked(
                songId = mediaItem.mediaId,
                fallbackTitle = mediaItem.mediaMetadata.title?.toString(),
                fallbackArtist = mediaItem.mediaMetadata.artist?.toString()
            )
        }
    }.let { }

    private fun loopAction() {
        PlayerPreferences.trackLoopEnabled = !PlayerPreferences.trackLoopEnabled
    }

    private inner class SessionCallback : MediaSession.Callback() {
        override fun onPlay() = player.play()
        override fun onPause() = player.pause()
        override fun onSkipToPrevious() = runCatching(player::forceSeekToPrevious).let { }
        override fun onSkipToNext() = runCatching(player::forceSeekToNext).let { }
        override fun onSeekTo(pos: Long) = player.seekTo(pos)
        override fun onStop() = player.pause()
        override fun onRewind() = player.seekToDefaultPosition()
        override fun onSkipToQueueItem(id: Long) =
            runCatching { player.seekToDefaultPosition(id.toInt()) }.let { }

        override fun onSetPlaybackSpeed(speed: Float) {
            PlayerPreferences.speed = speed.coerceIn(0.01f..2f)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) return
            binder.playFromSearch(query)
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            super.onCustomAction(action, extras)
            when (action) {
                LIKE_ACTION -> likeAction()
                LOOP_ACTION -> loopAction()
            }
        }
    }

    inner class NotificationActionReceiver internal constructor() :
        ActionReceiver("app.vitune.android") {
        val pause by action { _, _ ->
            player.pause()
        }
        val play by action { _, _ ->
            player.play()
        }
        val next by action { _, _ ->
            player.forceSeekToNext()
        }
        val previous by action { _, _ ->
            player.forceSeekToPrevious()
        }
        val like by action { _, _ ->
            likeAction()
        }
        val loop by action { _, _ ->
            loopAction()
        }
        val dismiss by action { _, _ ->
            dismissNotification()
        }
    }

    class NotificationDismissReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = with(context) {
            ServiceNotifications.default.cancel(this)
            stopService(intent<PlayerService>())
            Unit
        }
    }

    companion object {
        private const val DEFAULT_CACHE_DIRECTORY = "exoplayer"
        private const val DEFAULT_CHUNK_LENGTH = 10 * 1024 * 1024L
        private val jioSaavnResolver by lazy { JioSaavnResolver() }
        private val resolverMetadataScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private fun getContentLength(url: String): Long? {
            return runCatching {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    instanceFollowRedirects = true
                }
                var len = connection.contentLengthLong.takeIf { it > 0 }
                if (len == null) {
                    val contentRange = connection.getHeaderField("Content-Range")
                    if (contentRange != null && contentRange.contains("/")) {
                        len = contentRange.substringAfter("/").toLongOrNull()?.takeIf { it > 0 }
                    }
                }
                connection.disconnect()

                if (len == null) {
                    val getConn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                        setRequestProperty("Range", "bytes=0-0")
                        instanceFollowRedirects = true
                    }
                    val range = getConn.getHeaderField("Content-Range")
                    if (range != null && range.contains("/")) {
                        len = range.substringAfter("/").toLongOrNull()?.takeIf { it > 0 }
                    } else if (getConn.responseCode == 200) {
                        len = getConn.contentLengthLong.takeIf { it > 0 }
                    }
                    getConn.disconnect()
                }
                len
            }.getOrNull()
        }

        fun createDatabaseProvider(context: Context) = StandaloneDatabaseProvider(context)
        fun createCache(
            context: Context,
            directoryName: String = DEFAULT_CACHE_DIRECTORY,
            size: ExoPlayerDiskCacheSize = DataPreferences.exoPlayerDiskCacheMaxSize
        ) = with(context) {
            val cacheEvictor = when (size) {
                ExoPlayerDiskCacheSize.Unlimited -> NoOpCacheEvictor()
                else -> LeastRecentlyUsedCacheEvictor(size.bytes)
            }

            val directory = cacheDir.resolve(directoryName).apply {
                if (!exists()) mkdir()
            }

            SimpleCache(directory, cacheEvictor, createDatabaseProvider(context))
        }

        @Suppress("CyclomaticComplexMethod")
        fun createYouTubeDataSourceResolverFactory(
            context: Context,
            cache: Cache,
            chunkLength: Long? = DEFAULT_CHUNK_LENGTH,
            findMediaItem: suspend (videoId: String) -> MediaItem? = { null },
            uriCache: UriCache<String, Long?> = UriCache()
        ): DataSource.Factory = ResolvingDataSource.Factory(
            ConditionalCacheDataSourceFactory(
                cacheDataSourceFactory = cache.readOnlyWhen { PlayerPreferences.pauseCache }.asDataSource,
                upstreamDataSourceFactory = context.defaultDataSource,
                shouldCache = { !it.isLocal }
            )
        ) { dataSpec ->
            val mediaId = dataSpec.key?.removePrefix("https://youtube.com/watch?v=")
                ?: error("A key must be set")

            // Do not corrupt dataSpec.position or send out-of-bounds inclusive Range headers;
            // DefaultHttpDataSource natively handles byte-range requests from dataSpec.position & length.
            fun DataSpec.ranged(contentLength: Long?): DataSpec {
                if (contentLength == null || contentLength <= 0L || chunkLength == null) return this
                val start = this.position.coerceAtLeast(0L)
                val remaining = contentLength - start
                if (remaining <= 0L) return this
                val length = if (this.length != C.LENGTH_UNSET.toLong() && this.length > 0L) {
                    this.length.coerceAtMost(remaining).coerceAtMost(chunkLength)
                } else {
                    remaining.coerceAtMost(chunkLength)
                }
                return this.subrange(0L, length)
            }

            val checkLength = if (dataSpec.length != C.LENGTH_UNSET.toLong() && dataSpec.length > 0L) {
                dataSpec.length
            } else {
                chunkLength ?: (512 * 1024L)
            }

            if (
                dataSpec.isLocal || cache.isCached(
                    /* key = */ mediaId,
                    /* position = */ dataSpec.position,
                    /* length = */ checkLength
                )
            ) dataSpec
            else uriCache[mediaId]?.let { cachedUri ->
                dataSpec
                    .withUri(cachedUri.uri)
                    .ranged(cachedUri.meta)
            } ?: run {
                val mediaItem = runCatching {
                    runBlocking(Dispatchers.IO) { findMediaItem(mediaId) }
                }.getOrNull()

                val dbSong = if (mediaItem?.mediaMetadata?.title.isNullOrBlank() ||
                    mediaItem?.mediaMetadata?.artist.isNullOrBlank()
                ) {
                    runCatching {
                        runBlocking(Dispatchers.IO) { Database.song(mediaId).firstOrNull() }
                    }.getOrNull()
                } else null

                val title = mediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() }
                    ?: dbSong?.title?.takeIf { it.isNotBlank() }
                val artist = mediaItem?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() }
                    ?: dbSong?.artistsText?.takeIf { it.isNotBlank() }
                val album = mediaItem?.mediaMetadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() }

                val parsedDurationMs: Long? = runCatching {
                    val durText = mediaItem?.mediaMetadata?.extras?.songBundle?.durationText ?: dbSong?.durationText
                    if (!durText.isNullOrBlank()) {
                        val parts = durText.trim().split(":").mapNotNull { it.toLongOrNull() }
                        when (parts.size) {
                            2 -> (parts[0] * 60L + parts[1]) * 1000L
                            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
                            else -> null
                        }
                    } else null
                }.getOrNull()

                suspend fun resolveJioSaavnFallback(): android.net.Uri? {
                    if (title.isNullOrBlank() || artist.isNullOrBlank()) return null
                    val query = TrackQuery(
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = parsedDurationMs
                    )
                    val jioResult = runCatching {
                        jioSaavnResolver.resolve(query, kbps = 320)
                    }.getOrNull() ?: return null

                    val jioUri = jioResult.downloadUrl.toUri()
                    uriCache.push(
                        key = mediaId,
                        meta = null,
                        uri = jioUri
                    )
                    resolverMetadataScope.launch {
                        val probedLength = getContentLength(jioResult.downloadUrl)
                        transaction {
                            runCatching {
                                mediaItem?.let(Database::insert)
                                Database.insert(
                                    Format(
                                        songId = mediaId,
                                        itag = 320,
                                        mimeType = "audio/mp4",
                                        bitrate = 320_000L,
                                        loudnessDb = null,
                                        contentLength = probedLength,
                                        lastModified = null
                                    )
                                )
                            }
                        }
                    }
                    return jioUri
                }

                // If quality is VeryHigh, try JioSaavn AAC 320 stream first
                if (PlayerPreferences.audioQuality == PlayerPreferences.AudioQuality.VeryHigh &&
                    !title.isNullOrBlank() && !artist.isNullOrBlank()
                ) {
                    val jioUri = runBlocking(Dispatchers.IO) { resolveJioSaavnFallback() }
                    if (jioUri != null) {
                        return@run dataSpec.withUri(jioUri)
                    } else {
                        Log.w(TAG, "JioSaavn AAC 320 resolve returned null for $title - $artist; falling back to YouTube")
                    }
                }

                val (body, info) = runBlocking(Dispatchers.IO) {
                    coroutineScope {
                        val bodyDeferred = async {
                            runCatching { Innertube.player(PlayerBody(videoId = mediaId))?.getOrNull() }.getOrNull()
                        }
                        val retryAttempts = 2
                        var dlInfo: YouTubeDLResponse? = null
                        var attempt = 0
                        while (attempt <= retryAttempts) {
                            dlInfo = runCatching {
                                Dependencies.runDownload(mediaId, PlayerPreferences.audioQuality.youtubeDownloadDisplayName)
                            }.mapCatching {
                                YouTubeDLResponse.fromString(it)
                            }.onFailure {
                                Log.e(TAG, "Attempt $attempt: Failed to run download for $mediaId", it)
                            }.getOrNull()
                            if (dlInfo != null && dlInfo.id == mediaId && !dlInfo.url.isNullOrBlank()) {
                                break
                            }
                            if (attempt < retryAttempts) delay(250L)
                            attempt++
                        }
                        Pair(bodyDeferred.await(), dlInfo)
                    }
                }

                val youtubeFormat = body?.streamingData?.getFormat(PlayerPreferences.audioQuality.youtubeFormatName)
                    ?: body?.streamingData?.highestQualityFormat

                // Fallback 1: If yt-dlp failed or returned empty URL, check Innertube streamingData direct URL or JioSaavn
                if (info == null || info.url.isNullOrBlank()) {
                    val innertubeUrl = youtubeFormat?.url?.takeIf { it.isNotBlank() }
                    if (innertubeUrl != null) {
                        val directUri = innertubeUrl.toUri()
                        val directLen = youtubeFormat?.contentLength?.takeIf { it > 0 }
                        uriCache.push(key = mediaId, meta = directLen, uri = directUri)
                        return@run dataSpec.withUri(directUri).ranged(directLen)
                    }
                    val fallbackJioUri = runBlocking(Dispatchers.IO) { resolveJioSaavnFallback() }
                    if (fallbackJioUri != null) {
                        return@run dataSpec.withUri(fallbackJioUri)
                    }
                    throw UnplayableException(Exception("Could not extract media info for $mediaId"))
                }

                if (info.id != mediaId) throw VideoIdMismatchException()
                val format = info.formats?.firstOrNull { it.formatId == info.formatId }

                val uri = runCatching { info.url?.toUri() }.getOrNull()
                    ?: throw UnplayableException(Exception("Empty URL extracted for $mediaId"))

                val extras = mediaItem?.mediaMetadata?.extras?.songBundle
                if (extras?.durationText == null) youtubeFormat
                    ?.approxDurationMs
                    ?.div(1000)
                    ?.let(DateUtils::formatElapsedTime)
                    ?.removePrefix("0")
                    ?.let { durationText ->
                        extras?.durationText = durationText
                        Database.updateDurationText(mediaId, durationText)
                    }

                val finalContentLength: Long? = info.fileSize.takeIf { it > 0 }
                    ?: uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }
                    ?: youtubeFormat?.contentLength?.takeIf { it > 0 }

                val calculatedBitrate = format?.abr?.let { (it * 1000).toLong() }
                    ?: youtubeFormat?.bitrate?.toLong()

                transaction {
                    runCatching {
                        mediaItem?.let(Database::insert)
                        Database.insert(
                            Format(
                                songId = mediaId,
                                itag = info.formatId?.toIntOrNull() ?: youtubeFormat?.itag,
                                mimeType = youtubeFormat?.mimeType,
                                bitrate = calculatedBitrate,
                                loudnessDb = body?.playerConfig?.audioConfig?.normalizedLoudnessDb,
                                contentLength = finalContentLength,
                                lastModified = youtubeFormat?.lastModified
                            )
                        )
                    }
                }

                uriCache.push(
                    key = mediaId,
                    meta = finalContentLength,
                    uri = uri
                )

                dataSpec
                    .withUri(uri)
                    .ranged(finalContentLength)
            }
        }.handleUnknownErrors()
    }
}
