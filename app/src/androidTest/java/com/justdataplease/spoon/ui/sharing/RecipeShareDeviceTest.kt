package com.justdataplease.spoon.ui.sharing

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.MainActivity
import com.justdataplease.spoon.ui.SpoonViewModel
import com.justdataplease.spoon.ui.model.SpoonUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real activity, catalog and Compose Back handler without a signed-in account. */
class RecipeShareDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var currentActivity: MainActivity? = null

    @After
    fun finishActivity() {
        currentActivity?.let { activity ->
            onMain { if (!activity.isDestroyed) activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun coldHttpsAndSuccessiveWarmLinksOpenExactCatalogRecipes() {
        val activity = launchCold("https://justdataplease.github.io/spoon/recipe/9959")
        awaitRecipe(activity, "9959", "Ρολό cheeseburger")

        deliverWarm(activity, "spoon://recipe/argiro_15369")
        awaitRecipe(activity, "argiro_15369", "Κουρκούτι για μπακαλιάρο")

        deliverWarm(activity, "https://justdataplease.github.io/spoon/recipe/gastronomos_100134")
        awaitRecipe(activity, "gastronomos_100134", "Τρουφάκια με κουραμπιέδες")
        assertFalse("Warm links must reuse the running activity", onMain { activity.isDestroyed })
    }

    @Test
    fun invalidAndMissingRecipeLinksDoNotOpenUnrelatedRecipes() {
        val activity = launchCold("spoon://recipe/9959")
        awaitRecipe(activity, "9959", "Ρολό cheeseburger")

        listOf(
            "https://justdataplease.github.io.evil.test/spoon/recipe/argiro_15369",
            "spoon://recipe/argiro_15369%2Fsteps",
            "spoon://recipe/custom_01234567-89ab-4def-8123-456789abcdef",
        ).forEach { invalidLink ->
            deliverWarm(activity, invalidLink)
            assertEquals("Invalid link changed the selected recipe: $invalidLink", "9959", state(activity).selectedRecipe?.recipeId)
            assertFalse(state(activity).isRecipeDetailsLoading)
        }

        deliverWarm(activity, "spoon://recipe/missing_shared_recipe_device_test")
        awaitCondition("Missing recipe should report an unavailable shared recipe") {
            val state = state(activity)
            state.selectedRecipe == null && !state.isRecipeDetailsLoading &&
                state.message?.contains("δεν βρέθηκε") == true
        }
        assertNull(state(activity).selectedRecipe)

        // A failed link must not leave the activity unable to receive the next valid share.
        deliverWarm(activity, "spoon://recipe/argiro_15369")
        awaitRecipe(activity, "argiro_15369", "Κουρκούτι για μπακαλιάρο")
    }

    @Test
    fun recreationRetainsSharedRecipeAndBackDoesNotReopenConsumedIntent() {
        val original = launchCold("https://justdataplease.github.io/spoon/recipe/9959")
        awaitRecipe(original, "9959", "Ρολό cheeseburger")
        val originalViewModel = onMain { ViewModelProvider(original)[SpoonViewModel::class.java] }

        val recreated = recreate(original)
        assertNotSame(original, recreated)
        assertSame(originalViewModel, onMain { ViewModelProvider(recreated)[SpoonViewModel::class.java] })
        awaitRecipe(recreated, "9959", "Ρολό cheeseburger")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitCondition("Android Back should dismiss the shared recipe") {
            val state = state(recreated)
            state.selectedRecipe == null && !state.isRecipeDetailsLoading
        }
        assertFalse("Back should return to the app, not finish it", onMain { recreated.isFinishing })

        val afterDismissal = recreate(recreated)
        instrumentation.waitForIdleSync()
        // Give asynchronous catalog/state work time to reveal an accidental intent replay.
        val dismissedDeadline = SystemClock.uptimeMillis() + 1_000L
        while (SystemClock.uptimeMillis() < dismissedDeadline) {
            assertNull("Recreation replayed an already dismissed share intent", state(afterDismissal).selectedRecipe)
            assertFalse(state(afterDismissal).isRecipeDetailsLoading)
            SystemClock.sleep(50)
        }

        deliverWarm(afterDismissal, "spoon://recipe/gastronomos_100134")
        awaitRecipe(afterDismissal, "gastronomos_100134", "Τρουφάκια με κουραμπιέδες")
    }

    private fun launchCold(url: String): MainActivity {
        val intent = recipeIntent(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        currentActivity = activity
        instrumentation.waitForIdleSync()
        awaitCondition("MainActivity should receive window focus") { onMain { activity.hasWindowFocus() } }
        return activity
    }

    private fun deliverWarm(activity: MainActivity, url: String) {
        val intent = recipeIntent(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // startActivitySync waits for a new activity; singleTop instead delivers onNewIntent.
        onMain { activity.startActivity(intent) }
        awaitCondition("Running MainActivity should receive $url") {
            onMain { activity.intent.dataString == url }
        }
        instrumentation.waitForIdleSync()
    }

    private fun recipeIntent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        setClass(instrumentation.targetContext, MainActivity::class.java)
        addCategory(Intent.CATEGORY_BROWSABLE)
    }

    private fun recreate(activity: MainActivity): MainActivity {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            onMain { activity.recreate() }
            val replacement = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MILLIS) as? MainActivity
                ?: throw AssertionError("MainActivity did not resume after recreation")
            currentActivity = replacement
            instrumentation.waitForIdleSync()
            awaitCondition("Recreated MainActivity should receive window focus") {
                onMain { replacement.hasWindowFocus() }
            }
            return replacement
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun awaitRecipe(activity: MainActivity, recipeId: String, title: String) {
        awaitCondition("Expected public recipe $recipeId ($title)") {
            val state = state(activity)
            state.selectedRecipe?.recipeId == recipeId &&
                state.selectedRecipe?.title == title && !state.isRecipeDetailsLoading
        }
        assertEquals(title, state(activity).selectedRecipe?.title)
        // Read the rendered Compose accessibility tree, without screen coordinates or extra UI libraries.
        awaitCondition("The recipe title should be displayed: $title") {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            root != null && accessibilityTreeContains(root, title)
        }
    }

    private fun accessibilityTreeContains(node: AccessibilityNodeInfo, title: String): Boolean {
        // Compose exposes virtual descendants whose platform text-query index can be empty.
        // Traverse their actual labels, including merged text, instead of relying on that index.
        if (node.text?.contains(title) == true || node.contentDescription?.contains(title) == true) {
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (accessibilityTreeContains(child, title)) return true
        }
        return false
    }

    private fun state(activity: MainActivity): SpoonUiState = onMain {
        ViewModelProvider(activity)[SpoonViewModel::class.java].uiState.value
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(description, condition())
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
