package app.vitune.android.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.vitune.android.BuildConfig
import app.vitune.android.R
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.service.ServiceNotifications
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.screens.Route
import app.vitune.android.utils.bold
import app.vitune.android.utils.center
import app.vitune.android.utils.color
import app.vitune.android.utils.hasPermission
import app.vitune.android.utils.pendingIntent
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.data.utils.Version
import app.vitune.core.data.utils.version
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isAtLeastAndroid13
import app.vitune.core.ui.utils.isCompositionLaunched
import app.vitune.providers.github.GitHub
import app.vitune.providers.github.models.Release
import app.vitune.providers.github.requests.releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private val VERSION_NAME = BuildConfig.VERSION_NAME.substringBeforeLast("-").removePrefix("v")
private const val REPO_OWNER = "heyjumanji"
private const val REPO_NAME = "Vitune-Repatched"

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private val permission = Manifest.permission.POST_NOTIFICATIONS

private fun Release.findApkDownloadUrl(): String? {
    val apkAsset = assets.firstOrNull { asset ->
        asset.state == Release.Asset.State.Uploaded &&
            (asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType.equals("application/vnd.android.package-archive", ignoreCase = true))
    } ?: assets.firstOrNull { asset ->
        asset.name.endsWith(".apk", ignoreCase = true)
    }
    return apkAsset?.downloadUrl?.toString()
}

private fun Release.isNewerThan(currentVersion: Version, currentVersionName: String): Boolean {
    val releaseVersion = tag.version
    if (!releaseVersion.isEmpty && !currentVersion.isEmpty) {
        if (releaseVersion > currentVersion) return true
        if (releaseVersion < currentVersion) return false
    }
    val normalizedTag = tag.trim().removePrefix("v").removePrefix("V")
    val normalizedCurrent = currentVersionName.trim().removePrefix("v").removePrefix("V")
    return normalizedTag.isNotBlank() && !normalizedTag.equals(normalizedCurrent, ignoreCase = true) && releaseVersion >= currentVersion
}

private suspend fun fetchLatestGitHubRelease(
    repoOwner: String = REPO_OWNER,
    repoName: String = REPO_NAME
): Result<Release?>? = GitHub.releases(
    owner = repoOwner,
    repo = repoName
)?.mapCatching { releases ->
    releases
        .filter { !it.draft }
        .sortedWith(
            compareByDescending<Release> { !it.preRelease }
                .thenByDescending { it.publishedAt ?: it.createdAt }
        )
        .firstOrNull()
}

private suspend fun Version.getNewerVersion(
    repoOwner: String = REPO_OWNER,
    repoName: String = REPO_NAME
): Result<Release?>? = fetchLatestGitHubRelease(
    repoOwner = repoOwner,
    repoName = repoName
)?.mapCatching { latest ->
    latest?.takeIf { it.isNewerThan(this, VERSION_NAME) }
}

class VersionCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    companion object {
        private const val WORK_TAG = "version_check_worker"

        fun upsert(context: Context, period: Duration?) = runCatching {
            val workManager = WorkManager.getInstance(context)

            if (period == null) {
                workManager.cancelAllWorkByTag(WORK_TAG)
                return@runCatching
            }

            val request = PeriodicWorkRequestBuilder<VersionCheckWorker>(period.toJavaDuration())
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints(
                        requiredNetworkType = NetworkType.CONNECTED,
                        requiresBatteryNotLow = true
                    )
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                /* uniqueWorkName = */ WORK_TAG,
                /* existingPeriodicWorkPolicy = */ ExistingPeriodicWorkPolicy.KEEP,
                /* periodicWork = */ request
            )

            Unit
        }.also { it.exceptionOrNull()?.printStackTrace() }
    }

    override suspend fun doWork(): Result = with(applicationContext) {
        if (isAtLeastAndroid13 && !hasPermission(permission)) return Result.retry()

        val result = withContext(Dispatchers.IO) {
            VERSION_NAME.version
                .getNewerVersion()
                .also { it?.exceptionOrNull()?.printStackTrace() }
        }

        result?.getOrNull()?.let { release ->
            val targetUrl = release.findApkDownloadUrl() ?: release.frontendUrl.toString()
            val releaseTitle = release.name?.takeIf { it.isNotBlank() } ?: release.tag
            ServiceNotifications.version.sendNotification(applicationContext) {
                this
                    .setSmallIcon(R.drawable.download)
                    .setContentTitle("${getString(R.string.new_version_available)}: $releaseTitle")
                    .setContentText(
                        release.markdown?.lines()?.firstOrNull { it.isNotBlank() }
                            ?: getString(R.string.redirect_github)
                    )
                    .setContentIntent(
                        pendingIntent(
                            Intent(
                                /* action = */ Intent.ACTION_VIEW,
                                /* uri = */ Uri.parse(targetUrl)
                            )
                        )
                    )
                    .setAutoCancel(true)
                    .also {
                        it.setStyle(
                            NotificationCompat
                                .BigTextStyle(it)
                                .bigText(
                                    release.markdown?.trim()?.takeIf { body -> body.isNotBlank() }
                                        ?: getString(R.string.new_version_available)
                                )
                        )
                    }
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
            }
        }

        return when {
            result == null || result.isFailure -> Result.retry()
            result.isSuccess -> Result.success()
            else -> Result.failure()
        }
    }
}

@Route
@Composable
fun About() {
    val (colorPalette, typography) = LocalAppearance.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    var hasPermission by remember(isCompositionLaunched()) {
        mutableStateOf(
            if (isAtLeastAndroid13) context.applicationContext.hasPermission(permission)
            else true
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasPermission = it }
    )

    var isCheckingGitHub by remember { mutableStateOf(true) }
    var latestRelease by remember { mutableStateOf<Release?>(null) }
    var newerRelease by remember { mutableStateOf<Release?>(null) }
    var checkError by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var newVersionDialogOpened by rememberSaveable { mutableStateOf(false) }

    // Automatically check GitHub Releases when opening About (and ensure background worker is scheduled)
    LaunchedEffect(refreshTrigger) {
        isCheckingGitHub = true
        checkError = false
        withContext(Dispatchers.IO) {
            runCatching {
                VersionCheckWorker.upsert(context.applicationContext, DataPreferences.versionCheckPeriod.period)
            }
            val res = fetchLatestGitHubRelease()
            if (res == null || res.isFailure) {
                checkError = true
                res?.exceptionOrNull()?.printStackTrace()
            } else {
                val release = res.getOrNull()
                latestRelease = release
                newerRelease = release?.takeIf { it.isNewerThan(VERSION_NAME.version, VERSION_NAME) }
            }
        }
        isCheckingGitHub = false
    }

    val displayedVersionHeader = newerRelease?.let { release ->
        "${stringResource(R.string.format_version_credits, VERSION_NAME)} • Update: ${release.tag}"
    } ?: stringResource(
        R.string.format_version_credits,
        VERSION_NAME
    )

    SettingsCategoryScreen(
        title = stringResource(R.string.about),
        description = displayedVersionHeader
    ) {
        SettingsDescription(text = stringResource(R.string.about_description))

        SettingsGroup(title = stringResource(R.string.social)) {
            SettingsEntry(
                title = stringResource(R.string.github),
                text = stringResource(R.string.view_source),
                onClick = {
                    uriHandler.openUri("https://github.com/$REPO_OWNER/$REPO_NAME")
                }
            )
        }

        SettingsGroup(title = stringResource(R.string.contact)) {
            SettingsEntry(
                title = stringResource(R.string.report_bug),
                text = stringResource(R.string.report_bug_description),
                onClick = {
                    uriHandler.openUri(
                        @Suppress("MaximumLineLength")
                        "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=bug&template=bug_report.yaml"
                    )
                }
            )

            SettingsEntry(
                title = stringResource(R.string.request_feature),
                text = stringResource(R.string.redirect_github),
                onClick = {
                    uriHandler.openUri(
                        @Suppress("MaximumLineLength")
                        "https://github.com/$REPO_OWNER/$REPO_NAME/issues/new?assignees=&labels=enhancement&template=feature_request.md"
                    )
                }
            )
        }

        SettingsGroup(title = stringResource(R.string.version)) {
            val statusSubtitle = when {
                isCheckingGitHub -> stringResource(R.string.checking_github_releases, VERSION_NAME)
                newerRelease != null -> stringResource(
                    R.string.update_available_format,
                    newerRelease?.name?.takeIf { it.isNotBlank() } ?: newerRelease?.tag.orEmpty(),
                    VERSION_NAME
                )
                latestRelease != null -> stringResource(
                    R.string.up_to_date_format,
                    VERSION_NAME,
                    latestRelease?.tag.orEmpty()
                )
                else -> stringResource(R.string.current_version, VERSION_NAME)
            }

            SettingsEntry(
                title = stringResource(R.string.check_new_version),
                text = statusSubtitle,
                onClick = {
                    refreshTrigger++
                    newVersionDialogOpened = true
                }
            )

            // Automatically show Latest GitHub Release details & direct download when a release exists on GitHub
            latestRelease?.let { release ->
                val releaseTitle = release.name?.takeIf { it.isNotBlank() } ?: release.tag
                val apkUrl = release.findApkDownloadUrl()
                val bodyPreview = release.markdown
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                SettingsEntry(
                    title = stringResource(R.string.latest_github_release, releaseTitle),
                    text = bodyPreview ?: stringResource(R.string.redirect_github),
                    onClick = {
                        uriHandler.openUri(apkUrl ?: release.frontendUrl.toString())
                    }
                )
            }

            EnumValueSelectorSettingsEntry(
                title = stringResource(R.string.version_check),
                selectedValue = DataPreferences.versionCheckPeriod,
                onValueSelect = onSelect@{
                    DataPreferences.versionCheckPeriod = it
                    if (isAtLeastAndroid13 && it.period != null && !hasPermission)
                        launcher.launch(permission)

                    VersionCheckWorker.upsert(context.applicationContext, it.period)
                },
                valueText = { it.displayName() }
            )
        }

        if (newVersionDialogOpened) DefaultDialog(
            onDismiss = { newVersionDialogOpened = false }
        ) {
            when {
                isCheckingGitHub -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                newerRelease != null -> {
                    val release = newerRelease!!
                    val apkUrl = release.findApkDownloadUrl()
                    val releaseNotes = release.markdown?.trim()?.takeIf { it.isNotBlank() }

                    BasicText(
                        text = stringResource(R.string.new_version_available),
                        style = typography.xs.semiBold.center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    BasicText(
                        text = release.name?.takeIf { it.isNotBlank() } ?: release.tag,
                        style = typography.m.bold.center
                    )

                    if (releaseNotes != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorPalette.background2)
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            BasicText(
                                text = releaseNotes,
                                style = typography.xxs.secondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (apkUrl != null) {
                            SecondaryTextButton(
                                text = stringResource(R.string.download_update),
                                onClick = { uriHandler.openUri(apkUrl) }
                            )
                        }
                        SecondaryTextButton(
                            text = stringResource(R.string.more_information),
                            onClick = { uriHandler.openUri(release.frontendUrl.toString()) }
                        )
                    }
                }

                checkError -> {
                    BasicText(
                        text = stringResource(R.string.error_github),
                        style = typography.xs.semiBold.center,
                        modifier = Modifier.padding(all = 24.dp)
                    )
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BasicText(
                            text = stringResource(R.string.up_to_date),
                            style = typography.xs.semiBold.center
                        )
                        latestRelease?.let { release ->
                            Spacer(modifier = Modifier.height(8.dp))
                            BasicText(
                                text = stringResource(
                                    R.string.latest_github_release,
                                    release.name?.takeIf { it.isNotBlank() } ?: release.tag
                                ),
                                style = typography.xxs.secondary.center.color(colorPalette.textSecondary)
                            )
                            val notes = release.markdown?.trim()?.takeIf { it.isNotBlank() }
                            if (notes != null) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 180.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colorPalette.background2)
                                        .padding(12.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    BasicText(
                                        text = notes,
                                        style = typography.xxs.secondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            SecondaryTextButton(
                                text = stringResource(R.string.more_information),
                                onClick = { uriHandler.openUri(release.frontendUrl.toString()) }
                            )
                        }
                    }
                }
            }
        }
    }
}
