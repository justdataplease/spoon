package com.justdataplease.spoon.resources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {
    @Test
    fun `manifest uses the adaptive launcher icons`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertEquals(2, Regex("@mipmap/ic_launcher\"").findAll(manifest).count())
        assertEquals(2, Regex("@mipmap/ic_launcher_round\"").findAll(manifest).count())
        assertFalse(manifest.contains("ic_launcher_v"))
    }

    @Test
    fun `adaptive icon wires background, foreground and monochrome layers`() {
        listOf("ic_launcher", "ic_launcher_round").forEach { name ->
            val adaptive = projectFile("app/src/main/res/mipmap-anydpi-v26/$name.xml").readText()

            assertTrue(adaptive.contains("@color/ic_launcher_background"))
            assertTrue(adaptive.contains("@drawable/ic_launcher_foreground"))
            assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome"))
        }
        assertTrue(
            projectFile("app/src/main/res/values/ic_launcher_background.xml").readText()
                .contains("#B8442E"),
        )
    }

    @Test
    fun `launcher is a place setting with a question mark, drawn only in the app palette`() {
        val foreground = projectFile("app/src/main/res/drawable/ic_launcher_foreground.xml").readText()
        val palette = setOf("#FFF8F1", "#F3E7D8", "#B8442E", "#8D2E1E")
        val usedColors = Regex("#[0-9A-Fa-f]{6}").findAll(foreground).map(MatchResult::value).toSet()

        assertTrue("unexpected colours: ${usedColors - palette}", palette.containsAll(usedColors))
        // fork (handle, tines, middle tine), spoon (handle, bowl), plate (shadow, disc, rim),
        // question mark (hook, dot)
        assertEquals(10, Regex("<path(?:\\s|>)").findAll(foreground).count())
        assertFalse(foreground.contains("gradient"))
    }

    @Test
    fun `monochrome layer is a single-colour silhouette`() {
        val monochrome = projectFile("app/src/main/res/drawable/ic_launcher_monochrome.xml").readText()
        val usedColors = Regex("#[0-9A-Fa-f]{6}").findAll(monochrome).map(MatchResult::value).toSet()

        assertEquals(setOf("#000000"), usedColors)
    }

    @Test
    fun `no stale versioned launcher resources remain`() {
        val res = projectFile("app/src/main/res/values/strings.xml").parentFile.parentFile
        val stale = res.walk().filter { it.isFile && it.name.matches(Regex("ic_launcher.*_v\\d+\\.xml")) }.toList()

        assertTrue("stale launcher resources: $stale", stale.isEmpty())
    }

    private fun projectFile(relativePath: String): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return sequenceOf(
            File(workingDirectory, relativePath),
            File(workingDirectory, relativePath.removePrefix("app/")),
        ).firstOrNull(File::isFile)
            ?: error("Could not find project file: $relativePath from $workingDirectory")
    }
}
