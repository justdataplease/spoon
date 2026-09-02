package com.justdataplease.spoon.ui.details

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

internal fun normalizeRecipeLink(rawUrl: String): String? {
    val candidate = when {
        rawUrl.trim().startsWith("//") -> "https:${rawUrl.trim()}"
        rawUrl.trim().startsWith("/") -> "https://akispetretzikis.com${rawUrl.trim()}"
        else -> rawUrl.trim()
    }
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null || uri.host.isNullOrBlank()) {
        return null
    }
    return candidate
}

/** Returns an embeddable source only for a strict HTTPS YouTube or direct-video URL. */
internal fun resolveInlineVideoSource(rawUrl: String): InlineVideoSource? {
    val url = rawUrl.trim()
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null

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
            embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=0&playsinline=1&rel=0",
        )
    }

    val extension = uri.path.orEmpty().substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (host !in youtubeHosts && host != "youtu.be" && extension in directVideoExtensions) {
        InlineVideoSource.Direct(originalUrl = url)
    } else {
        null
    }
}

internal fun isAllowedVideoNavigation(url: String, source: InlineVideoSource): Boolean {
    if (url == "about:blank") return source is InlineVideoSource.Direct
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    return when (source) {
        is InlineVideoSource.YouTube ->
            host == "www.youtube-nocookie.com" && uri.path == "/embed/${source.videoId}"
        is InlineVideoSource.Direct -> false
    }
}

internal fun directVideoHtml(source: InlineVideoSource.Direct): String {
    val safeUrl = source.originalUrl.htmlAttributeEscape()
    return """
        <!doctype html>
        <html lang="el">
          <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; media-src https:; style-src 'unsafe-inline'">
            <style>
              html,body{height:100%;margin:0;background:#17130f;overflow:hidden}
              video{width:100%;height:100%;object-fit:contain;background:#17130f}
            </style>
          </head>
          <body>
            <video controls playsinline preload="metadata" src="$safeUrl">
              Το βίντεο δεν υποστηρίζεται από τη συσκευή.
            </video>
          </body>
        </html>
    """.trimIndent()
}

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
