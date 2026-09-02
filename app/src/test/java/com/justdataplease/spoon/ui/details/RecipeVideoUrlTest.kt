package com.justdataplease.spoon.ui.details

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeVideoUrlTest {
    @Test
    fun `youtu be becomes privacy enhanced embed without autoplay`() {
        val source = resolveInlineVideoSource("https://youtu.be/dQw4w9WgXcQ?t=30") as InlineVideoSource.YouTube
        assertEquals("dQw4w9WgXcQ", source.videoId)
        assertEquals(
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=0&playsinline=1&rel=0",
            source.embedUrl,
        )
        assertFalse(source.embedUrl.contains("autoplay=1"))
    }

    @Test
    fun `watch shorts live and embed formats resolve`() {
        listOf(
            "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/live/dQw4w9WgXcQ?si=test",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        ).forEach { url ->
            assertEquals(url, "dQw4w9WgXcQ", (resolveInlineVideoSource(url) as InlineVideoSource.YouTube).videoId)
        }
    }

    @Test
    fun `only https direct media extensions resolve`() {
        assertTrue(resolveInlineVideoSource("https://media.example.test/video.mp4?x=1") is InlineVideoSource.Direct)
        assertTrue(resolveInlineVideoSource("https://media.example.test/video.m3u8") is InlineVideoSource.Direct)
        assertNull(resolveInlineVideoSource("http://media.example.test/video.mp4"))
        assertNull(resolveInlineVideoSource("https://media.example.test/video.avi"))
    }

    @Test
    fun `unsafe malformed and lookalike urls are rejected`() {
        listOf(
            "javascript:alert(1)",
            "http://youtu.be/dQw4w9WgXcQ",
            "https://user@youtu.be/dQw4w9WgXcQ",
            "https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/no",
            "https://www.youtube.com/watch?v=%3Cscript%3E",
            "not a url",
            "",
        ).forEach { assertNull(it, resolveInlineVideoSource(it)) }
    }

    @Test
    fun `navigation allows only the exact nocookie embed`() {
        val source = resolveInlineVideoSource("https://youtu.be/dQw4w9WgXcQ") as InlineVideoSource.YouTube
        assertTrue(isAllowedVideoNavigation(source.embedUrl, source))
        assertFalse(isAllowedVideoNavigation("https://www.youtube.com/embed/dQw4w9WgXcQ", source))
        assertFalse(isAllowedVideoNavigation("https://user@www.youtube-nocookie.com/embed/dQw4w9WgXcQ", source))
        assertFalse(isAllowedVideoNavigation("http://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", source))
        assertFalse(isAllowedVideoNavigation("javascript:alert(1)", source))
        assertFalse(isAllowedVideoNavigation("about:blank", source))
    }

    @Test
    fun `direct player only permits its inert blank document`() {
        val source = InlineVideoSource.Direct("https://media.example.test/video.mp4")
        assertTrue(isAllowedVideoNavigation("about:blank", source))
        assertFalse(isAllowedVideoNavigation(source.originalUrl, source))
    }

    @Test
    fun `direct markup has controls no autoplay and escaped source`() {
        val quote = '"'
        val url = "https://media.example.test/a.mp4?x=1&label=" + quote + "test" + quote
        val html = directVideoHtml(InlineVideoSource.Direct(url))
        assertTrue(html.contains("<video controls playsinline"))
        assertTrue(html.contains("preload=" + quote + "metadata" + quote))
        assertFalse(html.contains("autoplay"))
        assertTrue(html.contains("x=1&amp;label=&quot;test&quot;"))
        assertTrue(html.contains("default-src 'none'"))
    }

    @Test
    fun `recipe links normalize only to safe https`() {
        assertEquals("https://akispetretzikis.com/recipe/1/test", normalizeRecipeLink("/recipe/1/test"))
        assertEquals("https://cdn.example.test/image.jpg", normalizeRecipeLink("//cdn.example.test/image.jpg"))
        assertEquals("https://example.test/path", normalizeRecipeLink(" https://example.test/path "))
        assertNull(normalizeRecipeLink("http://example.test/path"))
        assertNull(normalizeRecipeLink("https://user@example.test/path"))
        assertNull(normalizeRecipeLink("javascript:alert(1)"))
    }
}
