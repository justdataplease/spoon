package com.justdataplease.spoon.ui.details

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun RecipeVideoSection(
    videoUrls: List<String>,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val urls = remember(videoUrls) { videoUrls.map(String::trim).filter(String::isNotBlank).distinct() }
    if (urls.isEmpty()) return

    DetailSectionCard(
        title = if (urls.size == 1) "Βίντεο" else "Βίντεο (${urls.size})",
        icon = Icons.Outlined.Movie,
        modifier = modifier,
    ) {
        urls.forEachIndexed { index, url ->
            VideoCard(
                number = index + 1,
                source = resolveInlineVideoSource(url),
                originalUrl = url,
                onOpenExternal = onOpenExternal,
            )
        }
    }
}

@Composable
private fun VideoCard(
    number: Int,
    source: InlineVideoSource?,
    originalUrl: String,
    onOpenExternal: (String) -> Unit,
) {
    var requestedPlayback by rememberSaveable(originalUrl) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (source != null && requestedPlayback) {
                SecureInlineVideo(
                    source = source,
                    onOpenExternal = onOpenExternal,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            } else {
                VideoPlaceholder(
                    canPlayInline = source != null,
                    onPlay = { requestedPlayback = true },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Βίντεο $number",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = { onOpenExternal(originalUrl) }) {
                    Text("Άνοιγμα")
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 6.dp).size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPlaceholder(
    canPlayInline: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(Color(0xFF33271F), Color(0xFF151311))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (canPlayInline) {
            Button(onClick = onPlay, shape = CircleShape) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                Text("Αναπαραγωγή εδώ", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                Text(
                    "Η συγκεκριμένη μορφή βίντεο ανοίγει στην πηγή.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SecureInlineVideo(
    source: InlineVideoSource,
    onOpenExternal: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isLoading by remember(source) { mutableStateOf(true) }
    var hasError by remember(source) { mutableStateOf(false) }
    val context = LocalContext.current

    val webView = remember(source, context) {
        WebViewHolder.create(
            context = context,
            source = source,
            onLoadingChanged = { loading -> isLoading = loading },
            onError = { hasError = true },
            onExternalNavigation = onOpenExternal,
        )
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    Box(modifier = modifier.background(Color(0xFF17130F))) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )

        if (isLoading && !hasError) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
        if (hasError) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(20.dp),
                color = Color.Black.copy(alpha = 0.78f),
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Δεν ήταν δυνατή η αναπαραγωγή μέσα στην εφαρμογή.")
                    TextButton(onClick = { onOpenExternal(source.originalUrl) }) {
                        Text("Άνοιγμα βίντεο")
                    }
                }
            }
        }
    }
}

private object WebViewHolder {
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        source: InlineVideoSource,
        onLoadingChanged: (Boolean) -> Unit,
        onError: () -> Unit,
        onExternalNavigation: (String) -> Unit,
    ): WebView = WebView(context).apply {
        setBackgroundColor(AndroidColor.rgb(23, 19, 15))
        settings.apply {
            javaScriptEnabled = source is InlineVideoSource.YouTube
            domStorageEnabled = source is InlineVideoSource.YouTube
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                onLoadingChanged(true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onLoadingChanged(false)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != false) {
                    onLoadingChanged(false)
                    onError()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                if (!request.isForMainFrame) return false
                return handleNavigation(target)
            }

            @Deprecated("Compatibility callback")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let(::handleNavigation) ?: true

            private fun handleNavigation(target: String): Boolean {
                if (isAllowedVideoNavigation(target, source)) return false
                if (target.startsWith("https://", ignoreCase = true)) onExternalNavigation(target)
                return true
            }
        }

        when (source) {
            is InlineVideoSource.YouTube -> loadUrl(
                source.embedUrl,
                mapOf("Referer" to "https://akispetretzikis.com/"),
            )
            is InlineVideoSource.Direct -> loadDataWithBaseURL(
                "https://akispetretzikis.com/",
                directVideoHtml(source),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }
}
