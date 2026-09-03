package com.justdataplease.spoon.ui.details

import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal sealed interface InlineVideoSource {
    val originalUrl: String

    data class YouTube(
        override val originalUrl: String,
        val videoId: String,
        val embedUrl: String,
    ) : InlineVideoSource

    data class Vimeo(
        override val originalUrl: String,
        val videoId: String,
        val embedUrl: String,
    ) : InlineVideoSource

    data class Direct(
        override val originalUrl: String,
    ) : InlineVideoSource
}

private val youtubeHosts = setOf(
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtube-nocookie.com",
    "www.youtube-nocookie.com",
)
private val directVideoExtensions = setOf("mp4", "m4v", "webm", "m3u8")
private val youtubeIdPattern = Regex("^[A-Za-z0-9_-]{6,32}$")
private val vimeoIdPattern = Regex("^[0-9]{5,15}$")
internal const val InlineVideoOrigin = "https://spoon.justdataplease.com"
internal const val InlineVideoReferer = "$InlineVideoOrigin/"

internal fun normalizeRecipeLink(rawUrl: String): String? {
    // One publisher record contains a valid YouTube URL followed by an unescaped
    // sponsor label. A URL cannot contain raw whitespace, so keep only the URL
    // token while retaining the same HTTPS/host safety checks below.
    val trimmed = rawUrl.trim()
    val urlToken = trimmed.takeWhile { character -> !character.isWhitespace() }
    val candidate = when {
        urlToken.startsWith("//") -> "https:$urlToken"
        urlToken.startsWith("/") -> "https://akispetretzikis.com$urlToken"
        else -> urlToken
    }
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) {
        return null
    }
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
    if (host.isBlank() || host.isLocalNetworkHost()) return null
    return candidate
}

/** Returns an embeddable source only for a strict HTTPS YouTube or direct-video URL. */
internal fun resolveInlineVideoSource(rawUrl: String): InlineVideoSource? {
    val url = normalizeRecipeLink(rawUrl) ?: return null
    val uri = URI(url)

    val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
    val videoId = when {
        host == "youtu.be" -> uri.pathSegments().firstOrNull()
        host in youtubeHosts -> uri.youtubeVideoId()
        else -> null
    }?.takeIf(youtubeIdPattern::matches)

    if (videoId != null) {
        return InlineVideoSource.YouTube(
            originalUrl = url,
            videoId = videoId,
            embedUrl = "https://www.youtube-nocookie.com/embed/$videoId" +
                "?autoplay=0&playsinline=1&rel=0&enablejsapi=1" +
                "&origin=https%3A%2F%2Fspoon.justdataplease.com" +
                "&widget_referrer=https%3A%2F%2Fspoon.justdataplease.com%2F",
        )
    }

    val vimeoId = when (host) {
        "vimeo.com", "www.vimeo.com" -> uri.pathSegments().firstOrNull()
        "player.vimeo.com" -> uri.pathSegments().takeIf { it.firstOrNull() == "video" }?.getOrNull(1)
        else -> null
    }?.takeIf(vimeoIdPattern::matches)
    if (vimeoId != null) {
        return InlineVideoSource.Vimeo(
            originalUrl = url,
            videoId = vimeoId,
            embedUrl = "https://player.vimeo.com/video/$vimeoId?autoplay=0&playsinline=1",
        )
    }

    val extension = uri.path.orEmpty().substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (host !in youtubeHosts && host != "youtu.be" && extension in directVideoExtensions) {
        InlineVideoSource.Direct(originalUrl = url)
    } else {
        null
    }
}

internal fun inlineVideoRequestHeaders(source: InlineVideoSource): Map<String, String> =
    if (source is InlineVideoSource.YouTube) mapOf("Referer" to InlineVideoReferer) else emptyMap()

private fun String.isLocalNetworkHost(): Boolean {
    val host = removePrefix("[").removeSuffix("]")
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true

    if (':' in host) {
        // A colon in a URI host denotes an IPv6 literal. Reject malformed/scoped literals too.
        if ('%' in host) return true
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        val bytes = address.address
        val isUniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress ||
            isUniqueLocalIpv6
    }

    val looksNumeric = host.all { it.isDigit() || it == '.' }
    if (!looksNumeric) {
        // Avoid alternate hexadecimal IPv4 forms that network stacks may interpret numerically.
        return host.startsWith("0x", ignoreCase = true)
    }
    val octets = host.split('.')
    if (
        octets.size != 4 ||
        octets.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) || it.toIntOrNull() !in 0..255 }
    ) {
        return true
    }
    val bytes = octets.map { it.toInt().toByte() }.toByteArray()
    val address = InetAddress.getByAddress(bytes)
    return address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
}

internal fun isAllowedVideoNavigation(url: String, source: InlineVideoSource): Boolean {
    if (url == "about:blank") return source is InlineVideoSource.Direct
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    return when (source) {
        is InlineVideoSource.YouTube ->
            host == "www.youtube-nocookie.com" && uri.path == "/embed/${source.videoId}"
        is InlineVideoSource.Vimeo ->
            host == "player.vimeo.com" && uri.path == "/video/${source.videoId}"
        is InlineVideoSource.Direct -> false
    }
}

internal fun inlineVideoHtml(source: InlineVideoSource): String = when (source) {
    is InlineVideoSource.YouTube -> youtubeVideoHtml(source)
    is InlineVideoSource.Vimeo -> vimeoVideoHtml(source)
    is InlineVideoSource.Direct -> directVideoHtml(source)
}

private fun youtubeVideoHtml(source: InlineVideoSource.YouTube): String = """
    <!doctype html>
    <html lang="el">
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src https://www.youtube-nocookie.com; script-src 'unsafe-inline' https://www.youtube.com https://www.youtube-nocookie.com https://s.ytimg.com; connect-src https:; img-src https: data:; style-src 'unsafe-inline'">
        <style>${playerCss()}</style>
      </head>
      <body>
        <iframe id="player" title="Πρόγραμμα αναπαραγωγής βίντεο YouTube" src="${source.embedUrl.htmlAttributeEscape()}" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          let spoonReported = false;
          function spoonReady(){ if(!spoonReported){ spoonReported=true; console.log('SPOON_VIDEO_READY'); } }
          function spoonError(code){ if(!spoonReported){ spoonReported=true; console.error('SPOON_VIDEO_ERROR:'+code); } }
          function onYouTubeIframeAPIReady(){
            try {
              new YT.Player('player', {events:{onReady:spoonReady,onError:function(e){spoonError(e.data || 'youtube');}}});
            } catch(e) { spoonError('youtube-init'); }
          }
          window.addEventListener('error', function(){ spoonError('youtube-script'); });
        </script>
      </body>
    </html>
""".trimIndent()

private fun vimeoVideoHtml(source: InlineVideoSource.Vimeo): String = """
    <!doctype html>
    <html lang="el">
      <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src https://player.vimeo.com; script-src 'unsafe-inline' https://player.vimeo.com; connect-src https:; img-src https: data:; style-src 'unsafe-inline'">
        <style>${playerCss()}</style>
      </head>
      <body>
        <iframe id="player" title="Πρόγραμμα αναπαραγωγής βίντεο Vimeo" src="${source.embedUrl.htmlAttributeEscape()}" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen></iframe>
        <script src="https://player.vimeo.com/api/player.js"></script>
        <script>
          let spoonReported = false;
          function spoonReady(){ if(!spoonReported){ spoonReported=true; console.log('SPOON_VIDEO_READY'); } }
          function spoonError(){ if(!spoonReported){ spoonReported=true; console.error('SPOON_VIDEO_ERROR:vimeo'); } }
          try {
            const player = new Vimeo.Player(document.getElementById('player'));
            player.ready().then(spoonReady).catch(spoonError);
            player.on('error', spoonError);
          } catch(e) { spoonError(); }
          window.addEventListener('error', spoonError);
        </script>
      </body>
    </html>
""".trimIndent()

internal fun directVideoHtml(source: InlineVideoSource.Direct): String {
    val safeUrl = source.originalUrl.htmlAttributeEscape()
    return """
        <!doctype html>
        <html lang="el">
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; media-src https:; script-src 'unsafe-inline'; style-src 'unsafe-inline'">
            <style>
              ${playerCss()}
              video{width:100%;height:100%;object-fit:contain;background:#17130f}
            </style>
          </head>
          <body>
            <video controls playsinline preload="metadata" src="$safeUrl"
              onloadedmetadata="console.log('SPOON_VIDEO_READY')"
              oncanplay="console.log('SPOON_VIDEO_READY')"
              onerror="console.error('SPOON_VIDEO_ERROR:direct')">
              Το βίντεο δεν υποστηρίζεται από τη συσκευή.
            </video>
          </body>
        </html>
    """.trimIndent()
}

private fun playerCss(): String =
    "html,body{height:100%;margin:0;background:#17130f;overflow:hidden}" +
        "iframe{width:100%;height:100%;border:0;background:#17130f}"

private fun URI.youtubeVideoId(): String? {
    val segments = pathSegments()
    return when {
        path == "/watch" -> queryValue("v")
        segments.firstOrNull() in setOf("embed", "shorts", "live") -> segments.getOrNull(1)
        else -> null
    }
}

private fun URI.pathSegments(): List<String> =
    path.orEmpty().split('/').filter(String::isNotBlank)

private fun URI.queryValue(name: String): String? = rawQuery
    ?.split('&')
    ?.asSequence()
    ?.map { part -> part.substringBefore('=') to part.substringAfter('=', missingDelimiterValue = "") }
    ?.firstOrNull { (key) -> key == name }
    ?.second
    ?.let { value -> runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull() }

private fun String.htmlAttributeEscape(): String = buildString(length) {
    this@htmlAttributeEscape.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '"' -> "&quot;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '\'' -> "&#39;"
                else -> character
            },
        )
    }
}
