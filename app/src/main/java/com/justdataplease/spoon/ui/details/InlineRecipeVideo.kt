package com.justdataplease.spoon.ui.details

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay

private const val InlinePlayerTimeoutMillis = 12_000L
private const val YouTubeProbeDelayMillis = 350L
private const val YouTubeProbeAttempts = 24

internal enum class YouTubePlayerProbeResult { LOADING, READY, ERROR }

internal fun parseYouTubePlayerProbeResult(rawResult: String?): YouTubePlayerProbeResult = when (
    rawResult?.trim()?.trim('"')?.lowercase()
) {
    "ready" -> YouTubePlayerProbeResult.READY
    "error" -> YouTubePlayerProbeResult.ERROR
    else -> YouTubePlayerProbeResult.LOADING
}

internal val YouTubePlayerProbeScript =
    "(function(){try{" +
        "var error=document.querySelector('.ytp-error-content-wrap,.ytp-error');" +
        "if(error&&error.offsetWidth>0&&error.offsetHeight>0&&error.textContent.trim().length>0)return 'error';" +
        "var player=document.getElementById('movie_player');" +
        "var video=document.querySelector('video.html5-main-video');" +
        "var controls=document.querySelector('.ytp-chrome-bottom,.ytp-large-play-button');" +
        "var bounds=player?player.getBoundingClientRect():null;" +
        "return player&&bounds&&bounds.width>0&&bounds.height>0&&(video||controls)?'ready':'loading';" +
        "}catch(ignored){return 'loading';}})()"

internal val YouTubeViewportFixScript =
    "(function(){" +
        "var h=(window.innerHeight||document.documentElement.clientHeight||200)+'px';" +
        "document.documentElement.style.setProperty('height',h,'important');" +
        "document.body.style.setProperty('height',h,'important');" +
        "var root=document.getElementById('player');if(root)root.style.setProperty('height',h,'important');" +
        "var player=document.getElementById('movie_player');if(player)player.style.setProperty('height',h,'important');" +
        "window.dispatchEvent(new Event('resize'));return h;})()"

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
    val safeExternalUrl = source?.originalUrl ?: normalizeRecipeLink(originalUrl)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (source != null && requestedPlayback) {
                SecureInlineVideo(
                    source = source,
                    onOpenExternal = onOpenExternal,
                    modifier = Modifier.fillMaxWidth().height(210.dp),
                )
            } else {
                VideoPlaceholder(
                    canPlayInline = source != null,
                    onPlay = { requestedPlayback = true },
                    modifier = Modifier.fillMaxWidth().height(210.dp),
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
                TextButton(
                    onClick = { safeExternalUrl?.let(onOpenExternal) },
                    enabled = safeExternalUrl != null,
                ) {
                    Text("Άνοιγμα εκτός εφαρμογής")
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
    var attempt by rememberSaveable(source.originalUrl) { mutableIntStateOf(0) }
    var playerState by remember(source, attempt) { mutableStateOf<InlinePlayerState>(InlinePlayerState.Loading) }
    val context = LocalContext.current

    val webView = remember(source, context, attempt) {
        WebViewHolder.create(
            context = context,
            source = source,
            onReady = { playerState = InlinePlayerState.Ready },
            onError = { message -> playerState = InlinePlayerState.Error(message) },
            onExternalNavigation = onOpenExternal,
        )
    }

    LaunchedEffect(source, attempt, playerState) {
        if (playerState is InlinePlayerState.Loading) {
            delay(InlinePlayerTimeoutMillis)
            if (playerState is InlinePlayerState.Loading) {
                playerState = InlinePlayerState.Error("Το βίντεο αργεί να φορτώσει.")
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.onPause()
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    Box(modifier = modifier.background(Color(0xFF17130F))) {
        AndroidView(
            factory = { webView },
            update = { it.onResume() },
            modifier = Modifier.fillMaxSize(),
        )

        when (val state = playerState) {
            InlinePlayerState.Loading -> Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xE617130F),
                contentColor = Color.White,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Φόρτωση βίντεο…", modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { onOpenExternal(source.originalUrl) }) {
                        Text("Άνοιγμα εκτός εφαρμογής")
                    }
                }
            }
            InlinePlayerState.Ready -> Unit
            is InlinePlayerState.Error -> Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xF217130F),
                contentColor = Color.White,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(32.dp))
                    Text(state.message)
                    TextButton(onClick = { attempt++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Text("Νέα προσπάθεια", modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { onOpenExternal(source.originalUrl) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                        Text("Άνοιγμα εκτός εφαρμογής", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

private sealed interface InlinePlayerState {
    data object Loading : InlinePlayerState
    data object Ready : InlinePlayerState
    data class Error(val message: String) : InlinePlayerState
}

private object WebViewHolder {
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        source: InlineVideoSource,
        onReady: () -> Unit,
        onError: (String) -> Unit,
        onExternalNavigation: (String) -> Unit,
    ): WebView = WebView(context).apply {
        setBackgroundColor(AndroidColor.rgb(23, 19, 15))
        setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val message = consoleMessage?.message().orEmpty()
                return when {
                    message == "SPOON_VIDEO_READY" -> {
                        onReady()
                        true
                    }
                    message.startsWith("SPOON_VIDEO_ERROR:") -> {
                        onError("Δεν ήταν δυνατή η αναπαραγωγή μέσα στην εφαρμογή.")
                        true
                    }
                    else -> super.onConsoleMessage(consoleMessage)
                }
            }
        }
        webViewClient = object : WebViewClient() {
            private var youtubeProbeGeneration = 0

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != false) {
                    onError("Η σύνδεση για το βίντεο απέτυχε.")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame != false && (errorResponse?.statusCode ?: 0) >= 400) {
                    onError("Ο πάροχος του βίντεο επέστρεψε σφάλμα.")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                onError("Δεν ήταν ασφαλής η σύνδεση του βίντεο.")
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                onError("Η αναπαραγωγή βίντεο σταμάτησε απρόσμενα.")
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val youtubeSource = source as? InlineVideoSource.YouTube ?: return
                val playerView = view ?: return
                if (url == null || !isAllowedVideoNavigation(url, youtubeSource)) return
                val generation = ++youtubeProbeGeneration
                playerView.evaluateJavascript(YouTubeViewportFixScript) {
                    if (generation == youtubeProbeGeneration) {
                        probeYouTubePlayer(playerView, generation, YouTubeProbeAttempts)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                if (!request.isForMainFrame) return false
                return handleNavigation(target)
            }

            private fun handleNavigation(target: String): Boolean {
                if (isAllowedVideoNavigation(target, source)) return false
                if (target.startsWith("https://", ignoreCase = true)) onExternalNavigation(target)
                return true
            }

            private fun probeYouTubePlayer(view: WebView, generation: Int, attemptsRemaining: Int) {
                view.evaluateJavascript(YouTubePlayerProbeScript) { rawResult ->
                    if (generation != youtubeProbeGeneration) return@evaluateJavascript
                    when (parseYouTubePlayerProbeResult(rawResult)) {
                        YouTubePlayerProbeResult.READY -> onReady()
                        YouTubePlayerProbeResult.ERROR ->
                            onError("Το YouTube δεν μπόρεσε να αναπαράγει αυτό το βίντεο μέσα στην εφαρμογή.")
                        YouTubePlayerProbeResult.LOADING -> if (attemptsRemaining > 0) {
                            view.postDelayed(
                                { probeYouTubePlayer(view, generation, attemptsRemaining - 1) },
                                YouTubeProbeDelayMillis,
                            )
                        }
                    }
                }
            }
        }

        when (source) {
            is InlineVideoSource.YouTube ->
                loadUrl(source.embedUrl, inlineVideoRequestHeaders(source).toMutableMap())
            else -> loadDataWithBaseURL(
                InlineVideoReferer,
                inlineVideoHtml(source),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }
}
