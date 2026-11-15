package app.vitune.android.ui.screens.spotify

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.utils.SpotifySyncManager
import app.vitune.android.utils.center
import app.vitune.android.utils.secondary
import app.vitune.android.utils.semiBold
import app.vitune.core.ui.LocalAppearance
import kotlinx.coroutines.launch

private const val SPOTIFY_LOGIN_URL =
    "https://accounts.spotify.com/en/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

private fun extractSpDcFromCookies(): String? {
    val cookieManager = CookieManager.getInstance()
    val urls = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://spotify.com"
    )
    for (url in urls) {
        val cookieStr = cookieManager.getCookie(url) ?: continue
        val parts = cookieStr.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("sp_dc=")) {
                val value = trimmed.removePrefix("sp_dc=").trim()
                if (value.isNotBlank()) return value
            }
        }
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val scope = rememberCoroutineScope()

    var activeTab by rememberSaveable { mutableIntStateOf(0) } // 0 = In-App Login, 1 = Cookie Paste
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var manualCookie by rememberSaveable { mutableStateOf("") }
    var isPageLoading by remember { mutableStateOf(true) }
    var hasCapturedCookie by remember { mutableStateOf(false) }

    fun handleCapturedCookie(cookie: String) {
        if (hasCapturedCookie || isVerifying) return
        hasCapturedCookie = true
        isVerifying = true
        errorMessage = null

        // Immediately scrub the plaintext cookie from WebView's unencrypted SQLite store
        // once captured so it only lives in AndroidKeyStore-encrypted DataPreferences.
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }

        scope.launch {
            val success = SpotifySyncManager.loginWithCookie(cookie)
            isVerifying = false
            if (success) {
                onLoginSuccess()
            } else {
                hasCapturedCookie = false
                errorMessage = "Could not verify Spotify session. Please try logging in again."
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(activeTab, isVerifying, hasCapturedCookie) {
        if (activeTab == 0 && !isVerifying && !hasCapturedCookie) {
            while (true) {
                val cookie = extractSpDcFromCookies()
                if (cookie != null) {
                    handleCapturedCookie(cookie)
                    break
                }
                kotlinx.coroutines.delay(800L)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorPalette.background1,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(if (activeTab == 0) 0.86f else 0.52f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = "Log in to Spotify",
                        style = typography.m.semiBold
                    )

                    DialogTextButton(
                        text = "Close",
                        onClick = onDismiss,
                        modifier = Modifier.testTag("spotify_login_close_button")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    DialogTextButton(
                        text = "In-App Login",
                        primary = activeTab == 0,
                        onClick = {
                            activeTab = 0
                            errorMessage = null
                        },
                        modifier = Modifier.testTag("spotify_tab_webview")
                    )
                    DialogTextButton(
                        text = "Paste sp_dc Cookie",
                        primary = activeTab == 1,
                        onClick = {
                            activeTab = 1
                            errorMessage = null
                        },
                        modifier = Modifier.testTag("spotify_tab_cookie")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                errorMessage?.let { msg ->
                    BasicText(
                        text = msg,
                        style = typography.xs.semiBold.center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }

                if (isVerifying) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                            BasicText(
                                text = "Connecting your Spotify account...",
                                style = typography.xs.semiBold.center
                            )
                        }
                    }
                } else if (activeTab == 0) {
                    BasicText(
                        text = "Sign in to your Spotify account below. Your session is encrypted with Android Keystore.",
                        style = typography.xxs.secondary.center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (isPageLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colorPalette.background0)
                    ) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("spotify_login_webview"),
                            factory = { context ->
                                WebView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = false
                                        allowFileAccess = false
                                        allowContentAccess = false
                                        setSupportMultipleWindows(false)
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        userAgentString =
                                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"
                                    }

                                    webChromeClient = WebChromeClient()
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: Bitmap?
                                        ) {
                                            super.onPageStarted(view, url, favicon)
                                            isPageLoading = true
                                            extractSpDcFromCookies()?.let { cookie ->
                                                view?.clearCache(true)
                                                view?.clearHistory()
                                                handleCapturedCookie(cookie)
                                            }
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val uri = request?.url
                                            val targetUrl = uri?.toString().orEmpty()
                                            extractSpDcFromCookies()?.let { cookie ->
                                                view?.clearCache(true)
                                                view?.clearHistory()
                                                handleCapturedCookie(cookie)
                                                return true
                                            }
                                            if (targetUrl.startsWith("spotify:") ||
                                                targetUrl.startsWith("intent:")
                                            ) {
                                                return true
                                            }
                                            val scheme = uri?.scheme?.lowercase().orEmpty()
                                            if (scheme != "https") {
                                                return true
                                            }
                                            return false
                                        }

                                        override fun doUpdateVisitedHistory(
                                            view: WebView?,
                                            url: String?,
                                            isReload: Boolean
                                        ) {
                                            super.doUpdateVisitedHistory(view, url, isReload)
                                            extractSpDcFromCookies()?.let { cookie ->
                                                view?.clearCache(true)
                                                view?.clearHistory()
                                                handleCapturedCookie(cookie)
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isPageLoading = false
                                            CookieManager.getInstance().flush()
                                            extractSpDcFromCookies()?.let { cookie ->
                                                view?.clearCache(true)
                                                view?.clearHistory()
                                                handleCapturedCookie(cookie)
                                            }
                                        }
                                    }

                                    // Check if cookie already exists in CookieManager
                                    val existing = extractSpDcFromCookies()
                                    if (existing != null) {
                                        handleCapturedCookie(existing)
                                    } else {
                                        loadUrl(SPOTIFY_LOGIN_URL)
                                    }
                                }
                            }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BasicText(
                            text = "Paste your Spotify sp_dc cookie from open.spotify.com:",
                            style = typography.xs.secondary.center,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        OutlinedTextField(
                            value = manualCookie,
                            onValueChange = { manualCookie = it },
                            label = { Text("sp_dc Cookie") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("spotify_cookie_input")
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        DialogTextButton(
                            text = "Connect Spotify",
                            primary = true,
                            enabled = manualCookie.isNotBlank(),
                            onClick = { handleCapturedCookie(manualCookie) },
                            modifier = Modifier.testTag("spotify_connect_cookie_button")
                        )
                    }
                }
            }
        }
    }
}
