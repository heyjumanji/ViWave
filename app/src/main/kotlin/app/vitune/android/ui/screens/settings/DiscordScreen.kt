package app.vitune.android.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.ui.components.themed.Switch
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.utils.bold
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.discord.DiscordActivity
import app.vitune.android.utils.discord.DiscordDefaults
import app.vitune.android.utils.discord.DiscordRpcManager
import app.vitune.android.utils.discord.DiscordTemplateRenderer
import app.vitune.android.utils.medium
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.LocalAppearance
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val DiscordBlurple = Color(0xFF5865F2)
private val DiscordGreen = Color(0xFF23A55A)
private val DiscordYellow = Color(0xFFF0B232)
private val DiscordRed = Color(0xFFF23F43)
private val DiscordCardOuter = Color(0xFF111214)
private val DiscordCardInner = Color(0xFF1E1F22)
private val DiscordButtonBg = Color(0xFF2B2D31)
private val DiscordButtonBorder = Color(0xFF3F4147)
private val DiscordMutedText = Color(0xFFB5BAC1)

private val ACTIVITY_TYPE_LABELS = listOf(
    DiscordActivity.TYPE_LISTENING to "Listening",
    DiscordActivity.TYPE_PLAYING to "Playing",
    DiscordActivity.TYPE_WATCHING to "Watching",
    DiscordActivity.TYPE_COMPETING to "Competing"
)

private val DISCORD_STATUS_OPTIONS = listOf(
    DiscordDefaults.USER_STATUS to "Online",
    DiscordDefaults.STATUS_IDLE to "Idle",
    DiscordDefaults.STATUS_DND to "Do Not Disturb"
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun statusDotColor(status: String): Color = when (status) {
    DiscordDefaults.STATUS_IDLE -> DiscordYellow
    DiscordDefaults.STATUS_DND -> DiscordRed
    else -> DiscordGreen
}

private fun humanizeDiscordError(code: String): String = when (code) {
    "discord_error_public_client_off" ->
        "Public Client is turned OFF in Discord Developer Portal. Enable 'Public Client' under OAuth2."
    "discord_error_invalid_scope" ->
        "Discord Social SDK scope was rejected. Ensure your account is added to the app's Social SDK test list in Discord Developer Portal."
    "discord_error_redirect_uri_mismatch" ->
        "Redirect URI mismatch. Ensure 'viwaves://oauth2redirect' is added under OAuth2 Redirects in Discord Developer Portal."
    "discord_error_loopback_timeout" ->
        "Authorization did not return to ViWaves. Please try again and complete the authorization in your browser."
    "discord_error_user_cancelled" ->
        "Discord authorization was cancelled or denied."
    "discord_error_state_mismatch" ->
        "OAuth state mismatch. Please tap Login and try again."
    "discord_error_network" ->
        "Network error while connecting to Discord. Check your internet connection and try again."
    "discord_error_no_browser" ->
        "No web browser found to open Discord OAuth2."
    "discord_error_token_refresh_failed" ->
        "Discord session expired. Please log in again."
    else -> code
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViWavesDiscordSettingsSection() = with(DataPreferences) {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    val (colorPalette, typography) = LocalAppearance.current

    var discordName by remember { mutableStateOf(discordDisplayName) }
    var discordUser by remember { mutableStateOf(discordUsername) }
    var discordAvatar by remember { mutableStateOf(discordAvatarUrl) }

    val accessToken by DiscordRpcManager.accessTokenFlow.collectAsState()
    val connectionStatus by DiscordRpcManager.connectionStatus.collectAsState()
    val lastError by DiscordRpcManager.lastError.collectAsState()
    val currentUser by DiscordRpcManager.currentUser.collectAsState()
    val isLoggedIn = !accessToken.isNullOrEmpty()

    LaunchedEffect(Unit) {
        if (discordButton2Text == "Visit Metrolist") {
            discordButton2Text = DiscordDefaults.BUTTON2_LABEL
        }
        if (discordButton2Url.contains("MetrolistGroup/Metrolist", ignoreCase = true)) {
            discordButton2Url = DiscordDefaults.BUTTON2_URL
        }
        DiscordRpcManager.init(context)
    }

    LaunchedEffect(accessToken) {
        val token = accessToken
        if (!token.isNullOrEmpty()) {
            launch(Dispatchers.IO) {
                DiscordRpcManager.fetchCurrentUser(token)?.let { user ->
                    discordName = user.name
                    discordUser = user.username
                    discordDisplayName = user.name
                    discordUsername = user.username
                    user.avatar?.let { url ->
                        discordAvatar = url
                        discordAvatarUrl = url
                    }
                }
            }
        }
    }

    LaunchedEffect(currentUser) {
        currentUser?.let { user ->
            discordName = user.name
            discordUser = user.username
            discordDisplayName = user.name
            discordUsername = user.username
            user.avatar?.let { url ->
                discordAvatar = url
                discordAvatarUrl = url
            }
        }
    }

    var currentMediaItem by remember { mutableStateOf(binder?.player?.currentMediaItem) }
    var position by rememberSaveable { mutableLongStateOf(0L) }
    var duration by rememberSaveable { mutableLongStateOf(0L) }

    DisposableEffect(binder?.player) {
        val player = binder?.player
        if (player == null) return@DisposableEffect onDispose { }
        currentMediaItem = player.currentMediaItem
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentMediaItem = mediaItem
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED
                    )
                ) {
                    currentMediaItem = player.currentMediaItem
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    LaunchedEffect(binder?.player) {
        while (isActive) {
            val player = binder?.player
            if (player != null) {
                position = player.currentPosition.coerceAtLeast(0L)
                val d = player.duration
                duration = if (d > 0L && d != C.TIME_UNSET) d else 0L
            }
            delay(1000L)
        }
    }

    var showActivityNameDialog by rememberSaveable { mutableStateOf(false) }
    var showStateDialog by rememberSaveable { mutableStateOf(false) }
    var showDetailsDialog by rememberSaveable { mutableStateOf(false) }
    var showButton1TextDialog by rememberSaveable { mutableStateOf(false) }
    var showButton1UrlDialog by rememberSaveable { mutableStateOf(false) }
    var showButton2TextDialog by rememberSaveable { mutableStateOf(false) }
    var showButton2UrlDialog by rememberSaveable { mutableStateOf(false) }

    if (showActivityNameDialog) {
        ViWavesTemplateDialog(
            title = "Activity Name",
            initialValue = discordActivityName,
            defaultValue = DiscordDefaults.DEFAULT_ACTIVITY_NAME,
            hint = "Default: ${DiscordDefaults.DEFAULT_ACTIVITY_NAME}",
            onConfirm = {
                discordActivityName = it
                showActivityNameDialog = false
                DiscordRpcManager.notifySettingsChanged()
            },
            onDismiss = { showActivityNameDialog = false }
        )
    }

    if (showDetailsDialog) {
        ViWavesTemplateDialog(
            title = "Details Line (Top)",
            initialValue = discordDetailsTemplate,
            defaultValue = DiscordDefaults.DETAILS_TEMPLATE,
            hint = DiscordDefaults.DETAILS_TEMPLATE,
            onConfirm = {
                discordDetailsTemplate = it
                showDetailsDialog = false
                DiscordRpcManager.notifySettingsChanged()
            },
            onDismiss = { showDetailsDialog = false }
        )
    }

    if (showStateDialog) {
        ViWavesTemplateDialog(
            title = "State Line (Subtitle)",
            initialValue = discordStateTemplate,
            defaultValue = DiscordDefaults.STATE_TEMPLATE,
            hint = DiscordDefaults.STATE_TEMPLATE,
            onConfirm = {
                discordStateTemplate = it
                showStateDialog = false
                DiscordRpcManager.notifySettingsChanged()
            },
            onDismiss = { showStateDialog = false }
        )
    }

    if (showButton1TextDialog) {
        ViWavesTemplateDialog(
            title = "Button 1 Label",
            initialValue = discordButton1Text,
            defaultValue = DiscordDefaults.BUTTON1_LABEL,
            hint = DiscordDefaults.BUTTON1_LABEL,
            onConfirm = {
                discordButton1Text = it
                showButton1TextDialog = false
                DiscordRpcManager.notifySettingsChanged()
            },
            onDismiss = { showButton1TextDialog = false }
        )
    }

    if (showButton1UrlDialog) {
        ViWavesTemplateDialog(
            title = "Button 1 URL",
            initialValue = discordButton1Url,
            defaultValue = DiscordDefaults.BUTTON1_URL_TEMPLATE,
            hint = DiscordDefaults.BUTTON1_URL_TEMPLATE,
            onConfirm = {
                discordButton1Url = it
                showButton1UrlDialog = false
                DiscordRpcManager.notifySettingsChanged()
            },
            onDismiss = { showButton1UrlDialog = false }
        )
    }

    if (showButton2TextDialog) {
        ViWavesTemplateDialog(
            title = "Button 2 Label",
            initialValue = discordButton2Text,
            defaultValue = DiscordDefaults.BUTTON2_LABEL,
            hint = DiscordDefaults.BUTTON2_LABEL,
            onConfirm = {
                discordButton2Text = it
                showButton2TextDialog = false
                DiscordRpcManager.notifySettingsChanged()
            },
            onDismiss = { showButton2TextDialog = false }
        )
    }

    if (showButton2UrlDialog) {
        ViWavesTemplateDialog(
            title = "Button 2 URL",
            initialValue = discordButton2Url,
            defaultValue = DiscordDefaults.BUTTON2_URL,
            hint = DiscordDefaults.BUTTON2_URL,
            onConfirm = {
                discordButton2Url = it
                showButton2UrlDialog = false
                DiscordRpcManager.notifySettingsChanged()
            },
            onDismiss = { showButton2UrlDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Info Banner (Dismissible)
        AnimatedVisibility(visible = !discordInfoDismissed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                DiscordBlurple.copy(alpha = 0.18f),
                                colorPalette.background1
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = DiscordBlurple.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(DiscordBlurple.copy(alpha = 0.22f))
                    ) {
                        Image(
                            painter = painterResource(R.drawable.information),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(DiscordBlurple),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = "ViWaves Safe Discord RPC",
                            style = typography.xs.semiBold.color(colorPalette.text)
                        )
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = "Sign in with official Discord OAuth2 to display your live ViWaves playback on your Discord profile.",
                            style = typography.xxs.secondary
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colorPalette.background2)
                            .clickable { discordInfoDismissed = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicText(
                            text = "Dismiss",
                            style = typography.xxs.semiBold.color(DiscordBlurple)
                        )
                    }
                }
            }
        }

        // 2. Account & Status Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colorPalette.background1)
                .border(
                    width = 1.dp,
                    color = if (isLoggedIn) DiscordBlurple.copy(alpha = 0.35f) else colorPalette.background2,
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.size(48.dp)) {
                    if (isLoggedIn && discordAvatar.isNotEmpty()) {
                        AsyncImage(
                            model = discordAvatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isLoggedIn) DiscordBlurple.copy(alpha = 0.25f)
                                    else colorPalette.background2
                                )
                        ) {
                            Image(
                                painter = painterResource(R.drawable.person),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(
                                    if (isLoggedIn) DiscordBlurple else colorPalette.textSecondary
                                ),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    val badgeColor = when {
                        !isLoggedIn -> colorPalette.textDisabled
                        connectionStatus == DiscordRpcManager.Status.Connected -> statusDotColor(discordStatus)
                        connectionStatus == DiscordRpcManager.Status.Authorizing -> DiscordYellow
                        else -> colorPalette.textSecondary
                    }

                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(colorPalette.background1)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = if (isLoggedIn) discordName.ifEmpty { "Discord Connected" } else "Not connected to Discord",
                        style = typography.s.semiBold.color(colorPalette.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(2.dp))

                    val statusSubtitle = when {
                        !isLoggedIn -> "Sign in with Discord to share your music"
                        discordUser.isNotEmpty() && connectionStatus == DiscordRpcManager.Status.Connected ->
                            "@$discordUser • Live on Discord"
                        discordUser.isNotEmpty() && connectionStatus == DiscordRpcManager.Status.Authorizing ->
                            "@$discordUser • Connecting…"
                        discordUser.isNotEmpty() -> "@$discordUser • Ready"
                        connectionStatus == DiscordRpcManager.Status.Authorizing -> "Connecting to Discord…"
                        connectionStatus == DiscordRpcManager.Status.Connected -> "Connected to Discord Gateway"
                        else -> "Signed in"
                    }

                    BasicText(
                        text = statusSubtitle,
                        style = typography.xxs.color(
                            if (connectionStatus == DiscordRpcManager.Status.Connected) DiscordGreen
                            else colorPalette.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.width(10.dp))

                if (isLoggedIn) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colorPalette.red.copy(alpha = 0.15f))
                            .border(
                                width = 1.dp,
                                color = colorPalette.red.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(50)
                            )
                            .clickable {
                                discordName = ""
                                discordUser = ""
                                discordAvatar = ""
                                clearDiscordSession()
                                DiscordRpcManager.logout()
                            }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        BasicText(
                            text = "Logout",
                            style = typography.xs.semiBold.color(colorPalette.red)
                        )
                    }
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(DiscordBlurple)
                            .clickable(enabled = connectionStatus != DiscordRpcManager.Status.Authorizing) {
                                DiscordRpcManager.clearLastError()
                                context.findActivity()?.let { act ->
                                    DiscordRpcManager.authorize(act) { }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                            .testTag("discord_login_button")
                    ) {
                        BasicText(
                            text = if (connectionStatus == DiscordRpcManager.Status.Authorizing) "Signing in…" else "Login",
                            style = typography.xs.semiBold.color(Color.White)
                        )
                    }
                }
            }

            if (lastError != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorPalette.red.copy(alpha = 0.14f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    BasicText(
                        text = humanizeDiscordError(lastError!!),
                        style = typography.xxs.semiBold.color(colorPalette.red),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = "Dismiss",
                        style = typography.xxs.bold.color(colorPalette.text),
                        modifier = Modifier.clickable { DiscordRpcManager.clearLastError() }
                    )
                }
            }
        }

        // 3. Options Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colorPalette.background1)
                .border(1.dp, colorPalette.background2, RoundedCornerShape(18.dp))
        ) {
            ViWavesSwitchItem(
                title = "Enable Discord RPC",
                subtitle = if (discordRpcEnabled) "Sharing your ViWaves playback on Discord" else "Rich Presence is paused",
                checked = discordRpcEnabled,
                enabled = true,
                onCheckedChange = { discordRpcEnabled = it }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(colorPalette.background2)
            )

            ViWavesSwitchItem(
                title = "Advanced customization",
                subtitle = "Customize activity type, templates, buttons & online status",
                checked = discordAdvancedMode,
                enabled = discordRpcEnabled,
                onCheckedChange = {
                    discordAdvancedMode = it
                    DiscordRpcManager.notifySettingsChanged()
                }
            )
        }

        // 4. Advanced Customization Card
        AnimatedVisibility(visible = discordRpcEnabled && discordAdvancedMode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colorPalette.background1)
                    .border(1.dp, colorPalette.background2, RoundedCornerShape(18.dp))
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BasicText(
                    text = "PRESENCE CUSTOMIZATION",
                    style = typography.xxs.bold.color(DiscordBlurple),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )

                // Activity Type Pills
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    BasicText(
                        text = "Activity type",
                        style = typography.xs.semiBold.color(colorPalette.text)
                    )
                    Spacer(Modifier.height(8.dp))
                    val selectedType = discordActivityType.toIntOrNull() ?: DiscordActivity.TYPE_LISTENING
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ACTIVITY_TYPE_LABELS.forEach { (code, label) ->
                            val isSelected = selectedType == code
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (isSelected) DiscordBlurple else colorPalette.background2
                                    )
                                    .clickable {
                                        discordActivityType = code.toString()
                                        DiscordRpcManager.notifySettingsChanged()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                BasicText(
                                    text = label,
                                    style = typography.xxs.semiBold.color(
                                        if (isSelected) Color.White else colorPalette.textSecondary
                                    )
                                )
                            }
                        }
                    }
                }

                // Discord Status Pills
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    BasicText(
                        text = "Discord status",
                        style = typography.xs.semiBold.color(colorPalette.text)
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DISCORD_STATUS_OPTIONS.forEach { (statusKey, label) ->
                            val isSelected = discordStatus == statusKey
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (isSelected) DiscordBlurple.copy(alpha = 0.22f)
                                        else colorPalette.background2
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) DiscordBlurple else Color.Transparent,
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        discordStatus = statusKey
                                        DiscordRpcManager.notifySettingsChanged()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusDotColor(statusKey))
                                )
                                BasicText(
                                    text = label,
                                    style = typography.xxs.semiBold.color(
                                        if (isSelected) colorPalette.text else colorPalette.textSecondary
                                    )
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(1.dp)
                        .background(colorPalette.background2)
                )

                ViWavesFieldRow(
                    title = "Activity name",
                    value = discordActivityName.ifBlank { DiscordDefaults.DEFAULT_ACTIVITY_NAME },
                    onClick = { showActivityNameDialog = true }
                )

                ViWavesFieldRow(
                    title = "Details template (Top line)",
                    value = discordDetailsTemplate.ifBlank { DiscordDefaults.DETAILS_TEMPLATE },
                    onClick = { showDetailsDialog = true }
                )

                ViWavesFieldRow(
                    title = "State template (Subtitle)",
                    value = discordStateTemplate.ifBlank { DiscordDefaults.STATE_TEMPLATE },
                    onClick = { showStateDialog = true }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(1.dp)
                        .background(colorPalette.background2)
                )

                ViWavesSwitchItem(
                    title = "Button 1 (Listen on YouTube Music)",
                    subtitle = if (discordButton1Visible) discordButton1Text else "Hidden on profile",
                    checked = discordButton1Visible,
                    onCheckedChange = {
                        discordButton1Visible = it
                        DiscordRpcManager.notifySettingsChanged()
                    }
                )

                if (discordButton1Visible) {
                    ViWavesFieldRow(
                        title = "Button 1 label",
                        value = discordButton1Text,
                        onClick = { showButton1TextDialog = true }
                    )
                    ViWavesFieldRow(
                        title = "Button 1 URL",
                        value = discordButton1Url,
                        onClick = { showButton1UrlDialog = true }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(1.dp)
                        .background(colorPalette.background2)
                )

                ViWavesSwitchItem(
                    title = "Button 2 (Visit ViWaves)",
                    subtitle = if (discordButton2Visible) discordButton2Text else "Hidden on profile",
                    checked = discordButton2Visible,
                    onCheckedChange = {
                        discordButton2Visible = it
                        DiscordRpcManager.notifySettingsChanged()
                    }
                )

                if (discordButton2Visible) {
                    ViWavesFieldRow(
                        title = "Button 2 label",
                        value = discordButton2Text,
                        onClick = { showButton2TextDialog = true }
                    )
                    ViWavesFieldRow(
                        title = "Button 2 URL",
                        value = discordButton2Url,
                        onClick = { showButton2UrlDialog = true }
                    )
                }
            }
        }

        // 5. Authentic Dark Discord Profile Preview Card
        BasicText(
            text = "LIVE DISCORD PREVIEW",
            style = typography.xxs.bold.color(colorPalette.accent),
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )

        ViWavesRichPresencePreviewCard(
            mediaItem = currentMediaItem,
            position = position,
            duration = duration,
            advancedMode = discordAdvancedMode,
            activityType = discordActivityType.toIntOrNull() ?: DiscordActivity.TYPE_LISTENING,
            activityName = discordActivityName,
            detailsTemplate = discordDetailsTemplate,
            stateTemplate = discordStateTemplate,
            button1Visible = discordButton1Visible,
            button1Text = discordButton1Text,
            button1Url = discordButton1Url,
            button2Visible = discordButton2Visible,
            button2Text = discordButton2Text,
            button2Url = discordButton2Url,
            statusKey = if (discordAdvancedMode) discordStatus else DiscordDefaults.USER_STATUS
        )
    }
}

@Composable
private fun ViWavesSwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = typography.xs.semiBold.color(colorPalette.text)
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                BasicText(
                    text = subtitle,
                    style = typography.xxs.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(isChecked = checked)
    }
}

@Composable
private fun ViWavesFieldRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = title,
                style = typography.xs.medium.color(colorPalette.text)
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = value,
                style = typography.xxs.color(DiscordBlurple),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colorPalette.background2)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            BasicText(
                text = "Edit",
                style = typography.xxs.semiBold.color(colorPalette.textSecondary)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViWavesTemplateDialog(
    title: String,
    initialValue: String,
    defaultValue: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    var textValue by rememberSaveable { mutableStateOf(initialValue) }

    DefaultDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BasicText(
                text = title,
                style = typography.s.semiBold.color(colorPalette.text)
            )

            TextField(
                value = textValue,
                onValueChange = { textValue = it },
                hintText = hint,
                modifier = Modifier.fillMaxWidth()
            )

            BasicText(
                text = "Tap a variable to insert:",
                style = typography.xxs.secondary
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiscordTemplateRenderer.PLACEHOLDERS.forEach { token ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(DiscordBlurple.copy(alpha = 0.2f))
                            .border(1.dp, DiscordBlurple.copy(alpha = 0.45f), RoundedCornerShape(50))
                            .clickable {
                                textValue += token
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        BasicText(
                            text = token,
                            style = typography.xxs.semiBold.color(Color.White)
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                DialogTextButton(
                    text = "Reset",
                    onClick = { textValue = defaultValue }
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogTextButton(
                        text = "Cancel",
                        onClick = onDismiss
                    )
                    DialogTextButton(
                        text = "Save",
                        primary = true,
                        onClick = { onConfirm(textValue.trim()) }
                    )
                }
            }
        }
    }
}

private fun formatDurationMmSs(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ViWavesRichPresencePreviewCard(
    mediaItem: MediaItem?,
    position: Long,
    duration: Long,
    advancedMode: Boolean,
    activityType: Int,
    activityName: String,
    detailsTemplate: String,
    stateTemplate: String,
    button1Visible: Boolean,
    button1Text: String,
    button1Url: String,
    button2Visible: Boolean,
    button2Text: String,
    button2Url: String,
    statusKey: String
) {
    val uriHandler = LocalUriHandler.current
    val (colorPalette, _) = LocalAppearance.current

    val songName = mediaItem?.mediaMetadata?.title?.toString()?.takeIf { it.isNotBlank() } ?: "Midnight City"
    val songId = mediaItem?.mediaId?.removePrefix("https://youtube.com/watch?v=")?.takeIf { it.isNotBlank() } ?: "dQw4w9WgXcQ"
    val artistName = mediaItem?.mediaMetadata?.artist?.toString()?.takeIf { it.isNotBlank() } ?: "M83"
    val albumName = mediaItem?.mediaMetadata?.albumTitle?.toString()?.takeIf { it.isNotBlank() } ?: "Hurry Up, We're Dreaming"
    val artworkUri = mediaItem?.mediaMetadata?.artworkUri

    val render: (String) -> String = { template ->
        DiscordTemplateRenderer.render(
            template = template,
            title = songName,
            artist = artistName,
            album = albumName,
            songId = songId
        )
    }

    val effectiveType = if (advancedMode) activityType else DiscordActivity.TYPE_LISTENING
    val effectiveName = if (advancedMode && activityName.isNotBlank()) {
        render(activityName)
    } else {
        DiscordDefaults.DEFAULT_ACTIVITY_NAME
    }
    val effectiveDetails = if (advancedMode) render(detailsTemplate.ifBlank { DiscordDefaults.DETAILS_TEMPLATE }) else songName
    val effectiveState = if (advancedMode) render(stateTemplate.ifBlank { DiscordDefaults.STATE_TEMPLATE }) else artistName

    val typeVerb = when (effectiveType) {
        DiscordActivity.TYPE_PLAYING -> "Playing"
        DiscordActivity.TYPE_LISTENING -> "Listening to"
        DiscordActivity.TYPE_WATCHING -> "Watching"
        DiscordActivity.TYPE_COMPETING -> "Competing in"
        else -> "Listening to"
    }

    val resolvedActivityName = effectiveName.ifBlank { DiscordDefaults.DEFAULT_ACTIVITY_NAME }.trim()
    val headerLabel = "$typeVerb $resolvedActivityName"
    val showViWavesHeaderLogo = resolvedActivityName.equals(DiscordDefaults.DEFAULT_ACTIVITY_NAME, ignoreCase = true)
    val previewPos = if (mediaItem != null) position else 84_000L
    val previewDur = if (mediaItem != null && duration > 0L) duration else 243_000L
    val progress = if (previewDur > 0L) (previewPos.toFloat() / previewDur.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DiscordCardOuter)
            .border(
                width = 1.dp,
                color = DiscordBlurple.copy(alpha = 0.28f),
                shape = RoundedCornerShape(20.dp)
            )
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DiscordCardInner)
                .padding(14.dp)
        ) {
            // Header row with "Listening to ViWaves" + notification logo (only when ViWaves is set)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BasicText(
                        text = headerLabel,
                        style = TextStyle(
                            color = DiscordMutedText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    if (showViWavesHeaderLogo) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(DiscordMutedText.copy(alpha = 0.22f))
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_notification),
                                contentDescription = "ViWaves",
                                colorFilter = ColorFilter.tint(DiscordMutedText),
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(DiscordBlurple.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(statusDotColor(statusKey))
                    )
                    BasicText(
                        text = "ViWaves RPC",
                        style = TextStyle(
                            color = DiscordBlurple,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Artwork (clean without small icon overlay) + Metadata row (Title + Artist only, no sub-text below artist)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (artworkUri != null) {
                    AsyncImage(
                        model = artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        DiscordBlurple,
                                        colorPalette.accent
                                    )
                                )
                            )
                    ) {
                        Image(
                            painter = painterResource(R.drawable.musical_notes),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = effectiveDetails,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    BasicText(
                        text = effectiveState,
                        style = TextStyle(
                            color = DiscordMutedText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Discord-style Progress Bar
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(DiscordButtonBg)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(DiscordBlurple, Color(0xFF8EA1E1))
                                )
                            )
                    )
                }

                Spacer(Modifier.height(5.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BasicText(
                        text = formatDurationMmSs(previewPos),
                        style = TextStyle(
                            color = DiscordMutedText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    BasicText(
                        text = formatDurationMmSs(previewDur),
                        style = TextStyle(
                            color = DiscordMutedText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            // Action Buttons
            val showBtn1 = if (advancedMode) button1Visible else true
            val btn1Label = if (advancedMode) {
                render(button1Text.ifBlank { DiscordDefaults.BUTTON1_LABEL })
            } else {
                DiscordDefaults.BUTTON1_LABEL
            }
            val btn1Target = if (advancedMode) {
                render(button1Url.ifBlank { DiscordDefaults.BUTTON1_URL_TEMPLATE })
            } else {
                "${DiscordDefaults.YOUTUBE_WATCH_URL}$songId"
            }

            val normalizedB2Text = if (button2Text == "Visit Metrolist") DiscordDefaults.BUTTON2_LABEL else button2Text
            val normalizedB2Url = if (button2Url.contains("MetrolistGroup/Metrolist", ignoreCase = true)) {
                DiscordDefaults.BUTTON2_URL
            } else {
                button2Url
            }

            val showBtn2 = if (advancedMode) button2Visible else true
            val btn2Label = if (advancedMode) {
                render(normalizedB2Text.ifBlank { DiscordDefaults.BUTTON2_LABEL })
            } else {
                DiscordDefaults.BUTTON2_LABEL
            }
            val btn2Target = if (advancedMode) {
                render(normalizedB2Url.ifBlank { DiscordDefaults.BUTTON2_URL })
            } else {
                DiscordDefaults.BUTTON2_URL
            }

            if ((showBtn1 && btn1Label.isNotBlank()) || (showBtn2 && btn2Label.isNotBlank())) {
                Spacer(Modifier.height(12.dp))
            }

            if (showBtn1 && btn1Label.isNotBlank()) {
                DiscordActionButton(
                    label = btn1Label,
                    onClick = { runCatching { uriHandler.openUri(btn1Target) } }
                )
            }

            if (showBtn1 && btn1Label.isNotBlank() && showBtn2 && btn2Label.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
            }

            if (showBtn2 && btn2Label.isNotBlank()) {
                DiscordActionButton(
                    label = btn2Label,
                    onClick = { runCatching { uriHandler.openUri(btn2Target) } }
                )
            }
        }
    }
}

@Composable
private fun DiscordActionButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DiscordButtonBg)
            .border(1.dp, DiscordButtonBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


