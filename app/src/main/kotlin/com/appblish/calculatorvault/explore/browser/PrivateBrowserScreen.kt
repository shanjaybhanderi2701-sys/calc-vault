package com.appblish.calculatorvault.explore.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.appblish.calculatorvault.explore.ExploreStore
import com.appblish.calculatorvault.ui.theme.VaultTheme
import com.appblish.calculatorvault.vault.ui.VaultTopBar

/**
 * Private Browser. A single-tab incognito browser: history, cache, and form data are off,
 * and cookies are cleared when the screen leaves composition, so nothing survives the
 * session. Every navigation is checked against the shared Website Blocker list — an enabled
 * blocked host never loads; a red interstitial explains why instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PrivateBrowserScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing

    var address by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var blockedHost by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun go(raw: String) {
        val url = toUrl(raw)
        val host = ExploreStore.normalizeDomain(url)
        if (ExploreStore.isBlocked(host)) {
            blockedHost = host
            return
        }
        blockedHost = null
        webView?.loadUrl(url)
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Column(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        VaultTopBar(title = "Private Browser", subtitle = "Incognito — nothing is saved", onBack = onBack)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.xs),
        ) {
            IconButton(onClick = { webView?.goBack() }, enabled = canGoBack) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "Page back",
                    tint = if (canGoBack) colors.textPrimary else colors.textDisabled
                )
            }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = { Text("Search or type a URL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { webView?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = colors.textPrimary)
            }
        }

        if (loading) {
            LinearProgressIndicator(color = colors.accent, modifier = Modifier.fillMaxWidth())
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.saveFormData = false
                        @Suppress("DEPRECATION")
                        settings.savePassword = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val host = request?.url?.host.orEmpty()
                                    return if (ExploreStore.isBlocked(host)) {
                                        blockedHost = ExploreStore.normalizeDomain(host)
                                        true
                                    } else {
                                        false
                                    }
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    loading = true
                                    url?.let { address = Uri.parse(it).host ?: it }
                                }

                                override fun onPageFinished(
                                    view: WebView?,
                                    url: String?,
                                ) {
                                    loading = false
                                    canGoBack = view?.canGoBack() == true
                                }
                            }
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            blockedHost?.let { host ->
                BlockedInterstitial(host = host, onDismiss = { blockedHost = null })
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                clearHistory()
                clearCache(true)
                clearFormData()
            }
            CookieManager.getInstance().removeAllCookies(null)
        }
    }
}

@Composable
private fun BlockedInterstitial(
    host: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VaultTheme.colors
    val spacing = VaultTheme.spacing
    Column(
        modifier = modifier.fillMaxSize().background(colors.canvas).padding(spacing.xxl),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.destructive, modifier = Modifier.size(48.dp))
        Text(
            text = "$host is blocked",
            style = VaultTheme.typography.titleLarge,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = spacing.lg),
        )
        Text(
            text = "This site is on your Website Blocker list. Remove it there to open it here.",
            style = VaultTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = spacing.sm),
        )
        Row(modifier = Modifier.padding(top = spacing.xl)) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Dismiss", tint = colors.textPrimary)
            }
            Spacer(Modifier.width(spacing.sm))
            Text(
                text = "Go back",
                style = VaultTheme.typography.titleMedium,
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

/** Turns a user entry into a loadable URL: a bare query becomes a web search. */
internal fun toUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "about:blank"
    val looksLikeDomain = trimmed.contains('.') && !trimmed.contains(' ')
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        looksLikeDomain -> "https://$trimmed"
        else -> "https://duckduckgo.com/?q=" + Uri.encode(trimmed)
    }
}
