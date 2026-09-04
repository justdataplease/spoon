package com.justdataplease.spoon.resources

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconResourceTest {
    @Test
    fun `manifest uses the current spoon launcher icons`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertEquals(2, Regex("@mipmap/ic_launcher_v6").findAll(manifest).count())
        assertEquals(2, Regex("@mipmap/ic_launcher_round_v6").findAll(manifest).count())
        assertFalse(manifest.contains("@mipmap/ic_launcher_v5"))
    }

    @Test
    fun `launcher is one filled white spoon on solid terracotta`() {
        val colors = projectFile("app/src/main/res/values/colors.xml").readText()
        val foreground = projectFile(
            "app/src/main/res/drawable/ic_launcher_foreground_v6.xml",
        ).readText()
        val adaptive = projectFile(
            "app/src/main/res/mipmap-anydpi-v26/ic_launcher_v6.xml",
        ).readText()

        assertTrue(colors.contains("launcher_background"))
        assertTrue(colors.contains("#D96C4B"))
        assertTrue(colors.contains("launcher_foreground"))
        assertTrue(colors.contains("#FFFFFF"))
        assertEquals(1, Regex("<path(?:\\s|>)").findAll(foreground).count())
        assertTrue(foreground.contains("@color/launcher_foreground"))
        assertFalse(foreground.contains("strokeColor"))
        assertFalse(foreground.contains("gradient"))
        assertTrue(adaptive.contains("@color/launcher_background"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_foreground_v6"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome_v6"))
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
