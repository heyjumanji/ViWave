package app.vitune.android.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.credentials.CredentialManager
import app.vitune.android.Database
import app.vitune.android.LocalCredentialManager
import app.vitune.android.R
import app.vitune.android.models.PipedSession
import app.vitune.android.preferences.DataPreferences
import app.vitune.providers.lastfm.LastFmApi
import app.vitune.android.transaction
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.ConfirmationDialog
import app.vitune.android.ui.components.themed.ConfirmationDialogBody
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.ui.components.themed.IconButton
import app.vitune.android.ui.components.themed.TextField
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.spotify.SpotifyImportDialog
import app.vitune.android.ui.screens.spotify.SpotifyLoginDialog
import app.vitune.android.ui.screens.spotify.SpotifySyncHistoryDialog
import app.vitune.android.ui.screens.spotify.SpotifySyncStatusIndicator
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.center
import app.vitune.android.utils.get
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.android.utils.upsert
import app.vitune.compose.persist.persistList
import app.vitune.core.ui.LocalAppearance
import app.vitune.providers.piped.Piped
import app.vitune.providers.piped.models.Instance
import io.ktor.http.Url
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

@Route
@Composable
fun SyncSettings(
    credentialManager: CredentialManager = LocalCredentialManager.current
) {
    val coroutineScope = rememberCoroutineScope()

    val (colorPalette, typography) = LocalAppearance.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val pipedSessions by Database.pipedSessions().collectAsState(initial = listOf())

    var linkingPiped by remember { mutableStateOf(false) }
    if (linkingPiped) DefaultDialog(
        onDismiss = { linkingPiped = false },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var isLoading by rememberSaveable { mutableStateOf(false) }
        var hasError by rememberSaveable { mutableStateOf(false) }
        var successful by remember { mutableStateOf(false) }

        when {
            successful -> BasicText(
                text = stringResource(R.string.piped_session_created_successfully),
                style = typography.xs.semiBold.center,
                modifier = Modifier.padding(all = 24.dp)
            )

            hasError -> ConfirmationDialogBody(
                text = stringResource(R.string.error_piped_link),
                onDismiss = { },
                onCancel = { linkingPiped = false },
                onConfirm = { hasError = false }
            )

            isLoading -> CircularProgressIndicator(modifier = Modifier.padding(all = 8.dp))

            else -> Box(modifier = Modifier.fillMaxWidth()) {
                var backgroundLoading by rememberSaveable { mutableStateOf(false) }
                if (backgroundLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.TopEnd))

                Column(modifier = Modifier.fillMaxWidth()) {
                    var instances by persistList<Instance>(tag = "settings/sync/piped/instances")
                    var loadingInstances by rememberSaveable { mutableStateOf(true) }
                    var selectedInstance: Int? by rememberSaveable { mutableStateOf(null) }
                    var username by rememberSaveable { mutableStateOf("") }
                    var password by rememberSaveable { mutableStateOf("") }
                    var canSelect by rememberSaveable { mutableStateOf(false) }
                    var instancesUnavailable by rememberSaveable { mutableStateOf(false) }
                    var customInstance: String? by rememberSaveable { mutableStateOf(null) }

                    LaunchedEffect(Unit) {
                        Piped.getInstances()?.getOrNull()?.let {
                            selectedInstance = null
                            instances = it.toImmutableList()
                            canSelect = true
                        } ?: run { instancesUnavailable = true }
                        loadingInstances = false

                        backgroundLoading = true
                        runCatching {
                            credentialManager.get(context)?.let {
                                username = it.id
                                password = it.password
                            }
                        }.getOrNull()
                        backgroundLoading = false
                    }

                    BasicText(
                        text = stringResource(R.string.piped),
                        style = typography.m.semiBold
                    )

                    if (customInstance == null) ValueSelectorSettingsEntry(
                        title = stringResource(R.string.instance),
                        selectedValue = selectedInstance,
                        values = instances.indices.toImmutableList(),
                        onValueSelect = { selectedInstance = it },
                        valueText = { idx ->
                            idx?.let { instances.getOrNull(it)?.name }
                                ?: if (instancesUnavailable) stringResource(R.string.error_piped_instances_unavailable)
                                else stringResource(R.string.click_to_select)
                        },
                        isEnabled = !instancesUnavailable && canSelect,
                        usePadding = false,
                        trailingContent = if (loadingInstances) {
                            { CircularProgressIndicator() }
                        } else null
                    )
                    SwitchSettingsEntry(
                        title = stringResource(R.string.custom_instance),
                        text = null,
                        isChecked = customInstance != null,
                        onCheckedChange = {
                            customInstance = if (customInstance == null) "" else null
                        },
                        usePadding = false
                    )
                    customInstance?.let { instance ->
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(
                            value = instance,
                            onValueChange = { customInstance = it },
                            hintText = stringResource(R.string.base_api_url),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = username,
                        onValueChange = { username = it },
                        hintText = stringResource(R.string.username),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        hintText = stringResource(R.string.password),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                password()
                            }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    DialogTextButton(
                        text = stringResource(R.string.login),
                        primary = true,
                        enabled = (customInstance?.isNotBlank() == true || selectedInstance != null) &&
                            username.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            @Suppress("Wrapping") // thank you ktlint
                            (customInstance?.let {
                                runCatching {
                                    Url(it)
                                }.getOrNull() ?: runCatching {
                                    Url("https://$it")
                                }.getOrNull()
                            } ?: selectedInstance?.let { instances[it].apiBaseUrl })?.let { url ->
                                coroutineScope.launch {
                                    isLoading = true
                                    val session = Piped.login(
                                        apiBaseUrl = url,
                                        username = username,
                                        password = password
                                    )?.getOrNull()
                                    isLoading = false
                                    if (session == null) {
                                        hasError = true
                                        return@launch
                                    }

                                    transaction {
                                        Database.insert(
                                            PipedSession(
                                                apiBaseUrl = session.apiBaseUrl,
                                                username = username,
                                                token = session.token
                                            )
                                        )
                                    }

                                    successful = true

                                    runCatching {
                                        credentialManager.upsert(
                                            context = context,
                                            username = username,
                                            password = password
                                        )
                                    }

                                    linkingPiped = false
                                }
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }

    var deletingPipedSession: Int? by rememberSaveable { mutableStateOf(null) }
    if (deletingPipedSession != null) ConfirmationDialog(
        text = stringResource(R.string.confirm_delete_piped_session),
        onDismiss = {
            deletingPipedSession = null
        },
        onConfirm = {
            deletingPipedSession?.let {
                transaction { Database.delete(pipedSessions[it]) }
            }
        }
    )

    var linkingLastFm by remember { mutableStateOf(false) }
    if (linkingLastFm) DefaultDialog(
        onDismiss = { linkingLastFm = false },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        var isLoading by rememberSaveable { mutableStateOf(false) }
        var hasError by rememberSaveable { mutableStateOf(false) }
        var activeTab by rememberSaveable { mutableStateOf(0) }
        var currentToken by rememberSaveable { mutableStateOf<String?>(null) }

        var username by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }

        when {
            hasError -> ConfirmationDialogBody(
                text = stringResource(R.string.error_lastfm_login),
                onDismiss = { },
                onCancel = { linkingLastFm = false },
                onConfirm = { hasError = false }
            )

            isLoading -> CircularProgressIndicator(modifier = Modifier.padding(all = 8.dp))

            else -> Column(modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = stringResource(R.string.lastfm),
                    style = typography.m.semiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DialogTextButton(
                        text = stringResource(R.string.lastfm_login_browser),
                        primary = activeTab == 0,
                        onClick = { activeTab = 0 }
                    )
                    DialogTextButton(
                        text = stringResource(R.string.lastfm_login_credentials),
                        primary = activeTab == 1,
                        onClick = { activeTab = 1 }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (activeTab) {
                    0 -> {
                        BasicText(
                            text = stringResource(R.string.lastfm_auth_instructions),
                            style = typography.xs.secondary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        DialogTextButton(
                            text = stringResource(R.string.lastfm_open_browser),
                            onClick = {
                                coroutineScope.launch {
                                    isLoading = true
                                    val tokenRes = LastFmApi.getToken()?.getOrNull()
                                    isLoading = false
                                    val token = tokenRes?.token
                                    if (token != null) {
                                        currentToken = token
                                        uriHandler.openUri(
                                            "https://www.last.fm/api/auth/?api_key=${LastFmApi.DEFAULT_API_KEY}&token=$token"
                                        )
                                    } else {
                                        hasError = true
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        DialogTextButton(
                            text = stringResource(R.string.lastfm_complete_login),
                            primary = true,
                            enabled = currentToken != null,
                            onClick = {
                                currentToken?.let { token ->
                                    coroutineScope.launch {
                                        isLoading = true
                                        val sessionRes = LastFmApi.getSession(token = token)?.getOrNull()
                                        isLoading = false
                                        val session = sessionRes?.session
                                        if (session != null) {
                                            DataPreferences.lastFmUsername = session.name
                                            DataPreferences.lastFmSessionKey = session.key
                                            DataPreferences.lastFmEnabled = true
                                            linkingLastFm = false
                                        } else {
                                            hasError = true
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }

                    1 -> {
                        TextField(
                            value = username,
                            onValueChange = { username = it },
                            hintText = stringResource(R.string.username),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            hintText = stringResource(R.string.password),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { password() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        DialogTextButton(
                            text = stringResource(R.string.login),
                            primary = true,
                            enabled = username.isNotBlank() && password.isNotBlank(),
                            onClick = {
                                coroutineScope.launch {
                                    isLoading = true
                                    val response = LastFmApi.getMobileSession(
                                        username = username,
                                        password = password
                                    )?.getOrNull()
                                    isLoading = false
                                    val session = response?.session
                                    if (session != null) {
                                        DataPreferences.lastFmUsername = session.name
                                        DataPreferences.lastFmSessionKey = session.key
                                        DataPreferences.lastFmEnabled = true
                                        linkingLastFm = false
                                    } else {
                                        hasError = true
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }

    var linkingSpotify by rememberSaveable { mutableStateOf(false) }
    var importingSpotify by rememberSaveable { mutableStateOf(false) }
    var showingSpotifyHistory by rememberSaveable { mutableStateOf(false) }
    var isSyncingFavoritesToSpotify by rememberSaveable { mutableStateOf(false) }
    var spotifySyncStatusText by rememberSaveable { mutableStateOf<String?>(null) }
    val spotifyHistory by SpotifySyncManager.syncHistory.collectAsState()

    if (linkingSpotify) {
        SpotifyLoginDialog(
            onDismiss = { linkingSpotify = false },
            onLoginSuccess = { linkingSpotify = false }
        )
    }

    if (importingSpotify) {
        SpotifyImportDialog(
            onDismiss = { importingSpotify = false }
        )
    }

    if (showingSpotifyHistory) {
        SpotifySyncHistoryDialog(
            onDismiss = { showingSpotifyHistory = false }
        )
    }

    if (isSyncingFavoritesToSpotify || spotifySyncStatusText != null) {
        DefaultDialog(
            onDismiss = {
                if (!isSyncingFavoritesToSpotify) spotifySyncStatusText = null
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isSyncingFavoritesToSpotify) {
                CircularProgressIndicator(modifier = Modifier.padding(all = 12.dp))
            }
            BasicText(
                text = spotifySyncStatusText.orEmpty(),
                style = typography.xs.semiBold.center,
                modifier = Modifier.padding(all = 16.dp)
            )
            if (!isSyncingFavoritesToSpotify) {
                DialogTextButton(
                    text = "Done",
                    primary = true,
                    onClick = { spotifySyncStatusText = null }
                )
            }
        }
    }

    SettingsCategoryScreen(title = stringResource(R.string.sync)) {
        SettingsDescription(text = stringResource(R.string.sync_description))

        SettingsGroup(title = "Discord Rich Presence") {
            ViWavesDiscordSettingsSection()
        }

        SettingsGroup(title = stringResource(R.string.spotify)) {
            val isSpotifyConnected = DataPreferences.spotifySpDcCookie.isNotBlank()

            if (isSpotifyConnected) {
                SpotifySyncStatusIndicator(
                    alwaysVisibleWhenConnected = true,
                    onClick = { showingSpotifyHistory = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            SwitchSettingsEntry(
                title = stringResource(R.string.spotify_auto_sync_likes),
                text = stringResource(R.string.spotify_auto_sync_likes_description),
                isChecked = DataPreferences.spotifyAutoSyncLikedSongs,
                onCheckedChange = { DataPreferences.spotifyAutoSyncLikedSongs = it },
                isEnabled = isSpotifyConnected
            )

            SwitchSettingsEntry(
                title = stringResource(R.string.spotify_sync_playback),
                text = stringResource(R.string.spotify_sync_playback_description),
                isChecked = DataPreferences.spotifySyncPlayback,
                onCheckedChange = { DataPreferences.spotifySyncPlayback = it },
                isEnabled = isSpotifyConnected
            )

            SettingsEntry(
                title = stringResource(R.string.spotify_sync_history) +
                    if (spotifyHistory.isNotEmpty()) " (${spotifyHistory.size})" else "",
                text = stringResource(R.string.spotify_sync_history_description),
                onClick = { showingSpotifyHistory = true }
            )

            if (isSpotifyConnected) {
                SettingsEntry(
                    title = stringResource(R.string.spotify_import_playlists),
                    text = stringResource(R.string.spotify_import_playlists_description),
                    onClick = { importingSpotify = true }
                )

                SettingsEntry(
                    title = stringResource(R.string.spotify_sync_existing_favorites),
                    text = stringResource(R.string.spotify_sync_existing_favorites_description),
                    onClick = {
                        isSyncingFavoritesToSpotify = true
                        spotifySyncStatusText = "Preparing to sync Favorites to Spotify..."
                        coroutineScope.launch {
                            val syncedCount = SpotifySyncManager.syncAllLocalFavoritesToSpotify { current, total, title ->
                                spotifySyncStatusText = "Syncing ($current / $total): $title"
                            }
                            isSyncingFavoritesToSpotify = false
                            spotifySyncStatusText = "Added $syncedCount liked songs to your Spotify Liked Songs library!"
                        }
                    }
                )

                SettingsEntry(
                    title = stringResource(
                        R.string.spotify_logged_in_as,
                        DataPreferences.spotifyUsername.ifBlank { "Spotify User" }
                    ),
                    text = stringResource(R.string.logout),
                    onClick = {
                        SpotifySyncManager.logout()
                    }
                )
            } else {
                SettingsEntry(
                    title = stringResource(R.string.spotify_login),
                    text = stringResource(R.string.spotify_login_description),
                    onClick = { linkingSpotify = true }
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.lastfm)) {
            SwitchSettingsEntry(
                title = stringResource(R.string.lastfm_scrobble),
                text = stringResource(R.string.lastfm_scrobble_description),
                isChecked = DataPreferences.lastFmEnabled,
                onCheckedChange = { DataPreferences.lastFmEnabled = it },
                isEnabled = DataPreferences.lastFmSessionKey.isNotBlank()
            )

            if (DataPreferences.lastFmSessionKey.isNotBlank()) {
                SettingsEntry(
                    title = stringResource(R.string.lastfm_logged_in_as, DataPreferences.lastFmUsername),
                    text = stringResource(R.string.logout),
                    onClick = {
                        DataPreferences.lastFmSessionKey = ""
                        DataPreferences.lastFmUsername = ""
                        DataPreferences.lastFmEnabled = false
                        DataPreferences.cachedLastFmPicks = emptyList()
                    }
                )
            } else {
                SettingsEntry(
                    title = stringResource(R.string.lastfm_login),
                    text = null,
                    onClick = { linkingLastFm = true }
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.piped)) {
            SettingsEntry(
                title = stringResource(R.string.add_account),
                text = stringResource(R.string.add_account_description),
                onClick = { linkingPiped = true }
            )
            SettingsEntry(
                title = stringResource(R.string.learn_more),
                text = stringResource(R.string.learn_more_description),
                onClick = { uriHandler.openUri("https://github.com/TeamPiped/Piped/blob/master/README.md") }
            )
        }
        SettingsGroup(title = stringResource(R.string.piped_sessions)) {
            if (pipedSessions.isEmpty()) {
                SettingsGroupSpacer()

                BasicText(
                    text = stringResource(R.string.no_items_found),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    style = typography.s.semiBold.center
                )
            } else pipedSessions.fastForEachIndexed { i, session ->
                SettingsEntry(
                    title = session.username,
                    text = session.apiBaseUrl.toString(),
                    onClick = { },
                    trailingContent = {
                        IconButton(
                            onClick = { deletingPipedSession = i },
                            icon = R.drawable.delete,
                            color = colorPalette.text
                        )
                    }
                )
            }
        }
    }
}
