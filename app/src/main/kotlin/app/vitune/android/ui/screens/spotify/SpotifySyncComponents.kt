package app.vitune.android.ui.screens.spotify

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.SpotifySyncLogEntry
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.utils.SpotifySyncIndicatorState
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.LocalAppearance
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SpotifyEmerald = Color(0xFF1DB954)
private val SyncWarningAmber = Color(0xFFFFB300)
private val SyncErrorRed = Color(0xFFE53935)

@Composable
fun SpotifySyncStatusIndicator(
    modifier: Modifier = Modifier,
    alwaysVisibleWhenConnected: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val (colorPalette, typography) = LocalAppearance.current
    val indicatorState by SpotifySyncManager.syncIndicatorState.collectAsState()
    val history by SpotifySyncManager.syncHistory.collectAsState()
    val isConnected = DataPreferences.spotifySpDcCookie.isNotBlank()
    val autoSyncOn = DataPreferences.spotifyAutoSyncLikedSongs

    LaunchedEffect(Unit) {
        SpotifySyncManager.refreshHistoryFromPreferences()
    }

    val isVisible = when (indicatorState) {
        is SpotifySyncIndicatorState.Syncing,
        is SpotifySyncIndicatorState.Success,
        is SpotifySyncIndicatorState.Error -> true
        SpotifySyncIndicatorState.Idle -> alwaysVisibleWhenConnected && isConnected
    }

    val infiniteTransition = rememberInfiniteTransition(label = "syncPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(250)),
        modifier = modifier
    ) {
        val (dotColor, statusText) = when (val state = indicatorState) {
            is SpotifySyncIndicatorState.Syncing -> SyncWarningAmber to state.message
            is SpotifySyncIndicatorState.Success -> SpotifyEmerald to state.message
            is SpotifySyncIndicatorState.Error -> SyncErrorRed to state.message
            SpotifySyncIndicatorState.Idle -> {
                val lastEntry = history.firstOrNull()
                if (!isConnected) {
                    colorPalette.textDisabled to "Spotify Disconnected"
                } else if (lastEntry != null) {
                    val dot = if (lastEntry.success) SpotifyEmerald else SyncErrorRed
                    val prefix = if (autoSyncOn) "Auto-Sync Active" else "Auto-Sync Paused"
                    dot to "$prefix • Last: ${lastEntry.songTitle}"
                } else {
                    val dot = if (autoSyncOn) SpotifyEmerald else colorPalette.textDisabled
                    val text = if (autoSyncOn) "Spotify Auto-Sync Ready" else "Spotify Auto-Sync Off"
                    dot to text
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colorPalette.background2.copy(alpha = 0.9f))
                .let {
                    if (onClick != null) it.clickable(onClick = onClick) else it
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("spotify_sync_status_indicator")
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (indicatorState is SpotifySyncIndicatorState.Syncing) pulseAlpha else 1f)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            BasicText(
                text = statusText,
                style = typography.xxs.semiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SpotifySyncHistoryDialog(
    onDismiss: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val history by SpotifySyncManager.syncHistory.collectAsState()

    LaunchedEffect(Unit) {
        SpotifySyncManager.refreshHistoryFromPreferences()
    }

    val dateFormatter = remember {
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
    }

    val successCount = remember(history) { history.count { it.success } }
    val failCount = remember(history) { history.count { !it.success } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorPalette.background1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = "Spotify Sync History",
                            style = typography.m.semiBold
                        )
                        BasicText(
                            text = "$successCount succeeded • $failCount failed",
                            style = typography.xxs.secondary
                        )
                    }

                    if (history.isNotEmpty()) {
                        DialogTextButton(
                            text = "Clear Log",
                            onClick = { SpotifySyncManager.clearSyncHistory() },
                            modifier = Modifier.testTag("spotify_clear_history_button")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                SpotifySyncStatusIndicator(
                    alwaysVisibleWhenConnected = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.background0),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            text = "No sync events recorded yet.\nLike any song in the app or import a playlist to see live logs here.",
                            style = typography.xs.secondary.center,
                            modifier = Modifier.padding(20.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.background0)
                    ) {
                        items(
                            items = history,
                            key = { "${it.id}-${it.songTitle}" }
                        ) { entry ->
                            SpotifySyncHistoryRow(
                                entry = entry,
                                formattedTime = dateFormatter.format(Date(entry.timestamp))
                            )
                            HorizontalDivider(
                                color = colorPalette.background1,
                                thickness = 1.dp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                DialogTextButton(
                    text = "Close",
                    primary = true,
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("spotify_history_close_button")
                )
            }
        }
    }
}

@Composable
private fun SpotifySyncHistoryRow(
    entry: SpotifySyncLogEntry,
    formattedTime: String
) {
    val (_, typography) = LocalAppearance.current
    val badgeColor = if (entry.success) SpotifyEmerald else SyncErrorRed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(badgeColor)
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = entry.songTitle,
                    style = typography.xs.semiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                BasicText(
                    text = formattedTime,
                    style = typography.xxs.secondary
                )
            }

            if (entry.artistName.isNotBlank()) {
                BasicText(
                    text = entry.artistName,
                    style = typography.xxs.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            BasicText(
                text = "${entry.action} — ${entry.details}",
                style = typography.xxs.color(badgeColor),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
