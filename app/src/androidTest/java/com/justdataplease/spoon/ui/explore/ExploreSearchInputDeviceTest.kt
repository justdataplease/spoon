package com.justdataplease.spoon.ui.explore

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.justdataplease.spoon.MainActivity
import com.justdataplease.spoon.ui.SpoonViewModel
import org.junit.After
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Sends the real Android IME protocol to the focused Compose editor.
 *
 * Run with -e isolatedIme true and competing IME services temporarily disabled
 * in a disposable emulator user. Restore their original settings afterwards.
 * A live keyboard otherwise sends its own composition edits alongside this
 * manually driven connection. No app hooks, query setter calls, reflection or
 * additional Compose-test dependency is required. The same instrumentation can
 * target the previous debug APK because it uses its existing activity/state API.
 * Run in the disposable emulator user; this test never changes account data.
 */
class ExploreSearchInputDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var currentActivity: MainActivity? = null

    @Before
    fun requireIsolatedInputProtocol() {
        assumeTrue(
            "This protocol test requires an isolated IME: see docs/release-0.9.1.md",
            InstrumentationRegistry.getArguments().getString("isolatedIme") == "true",
        )
    }

    @After
    fun finishActivity() {
        currentActivity?.let { activity ->
            onMain { if (!activity.isDestroyed) activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun rapidGreekCompositionSurvivesAsynchronousResults() {
        val activity = launchExplore()
        val editor = focusEditor(activity)
        val generation = resultGeneration(activity)

        // Separate IME messages, deliberately without waiting for Compose idle
        // or the debounced catalog query between keystrokes.
        composePrefixes(editor, "κοτόπουλο")
        awaitEditor(editor, "κοτόπουλο", "κοτόπουλο".length)
        awaitResultsAfter(activity, generation)

        // Keep composition open across the actual results update. Losing its
        // range appends/duplicates this text instead of replacing the preedit.
        val completeQuery = "κοτόπουλο με ρύζι"
        composePrefixes(editor, completeQuery, firstLength = "κοτόπουλο".length + 1)
        ime(editor) { finishComposingText() }
        awaitEditor(editor, completeQuery, completeQuery.length)
        assertTrue("The activity must retain its window focus", onMain { activity.hasWindowFocus() })
    }

    @Test
    fun middleCursorCompositionClearAndRapidRefillKeepTextSelectionAndFocus() {
        val activity = launchExplore()
        val editor = focusEditor(activity)
        val initialQuery = "κοτόπουλο με ρύζι"
        val initialGeneration = resultGeneration(activity)
        ime(editor) { commitText(initialQuery, 1) }
        awaitEditor(editor, initialQuery, initialQuery.length)
        awaitResultsAfter(activity, initialGeneration)

        val insertionPoint = initialQuery.indexOf("με")
        ime(editor) { setSelection(insertionPoint, insertionPoint) }
        awaitEditor(editor, initialQuery, insertionPoint)
        val composingGeneration = resultGeneration(activity)
        val insertion = "λεμονάτο"
        composePrefixes(editor, insertion)
        val composingText = initialQuery.substring(0, insertionPoint) +
            insertion + initialQuery.substring(insertionPoint)
        awaitEditor(editor, composingText, insertionPoint + insertion.length)
        awaitResultsAfter(activity, composingGeneration)

        // Commit replaces the composing word in the middle, preserving the
        // untouched suffix and the cursor rather than moving it to the end.
        ime(editor) { commitText("$insertion ", 1) }
        val editedText = initialQuery.substring(0, insertionPoint) +
            "$insertion " + initialQuery.substring(insertionPoint)
        awaitEditor(editor, editedText, insertionPoint + insertion.length + 1)
        val beforeClear = resultGeneration(activity)

        clickLabel("Καθαρισμός αναζήτησης")
        awaitCondition("Clear should empty the focused editor") {
            editableNode()?.let { it.isFocused && it.text?.toString().orEmpty().isEmpty() } == true
        }
        // Clear can legitimately ask Android to restart its input connection.
        // Reconnect as an IME would, without re-clicking or restoring focus.
        val afterClear = connectFocusedEditor(activity)
        awaitEditor(afterClear, "", 0)
        awaitResultsAfter(activity, beforeClear)

        val refill = "φακές σαλάτα"
        refill.forEach { character ->
            ime(afterClear) { commitText(character.toString(), 1) }
        }
        awaitEditor(afterClear, refill, refill.length)
        assertTrue("Clear and results updates must not dismiss input focus", onMain { afterClear.owner.hasFocus() })
    }

    private fun launchExplore(): MainActivity {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        currentActivity = activity
        awaitCondition("MainActivity should have window focus") { onMain { activity.hasWindowFocus() } }
        clickLabel("Βρες")
        awaitCondition("Explore should show its searchable catalog") {
            editableNode() != null && onMain {
                val state = ViewModelProvider(activity)[SpoonViewModel::class.java].uiState.value
                state.exploreTotalRecipeCount > 0 && !state.exploreIsLoadingPage
            }
        }
        return activity
    }

    private fun focusEditor(activity: MainActivity): EditorSession {
        val node = editableNode() ?: throw AssertionError("Explore search editor is missing")
        assertTrue("Search editor should accept focus", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitCondition("Search editor should retain input focus") { editableNode()?.isFocused == true }
        return connectFocusedEditor(activity).also { awaitEditor(it, "", 0) }
    }

    private fun connectFocusedEditor(activity: MainActivity): EditorSession {
        var editor: EditorSession? = null
        awaitCondition("Focused Compose host should expose its Android InputConnection") {
            editor = onMain {
                val owner = findTextEditor(activity.window.decorView) ?: return@onMain null
                owner.onCreateInputConnection(EditorInfo())?.let { EditorSession(owner, it) }
            }
            editor != null
        }
        return checkNotNull(editor)
    }

    private fun findTextEditor(view: View): View? {
        if (view.hasFocus() && view.onCheckIsTextEditor()) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextEditor(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun composePrefixes(editor: EditorSession, text: String, firstLength: Int = 1) {
        for (length in firstLength..text.length) {
            ime(editor) { setComposingText(text.take(length), 1) }
        }
    }

    private fun ime(editor: EditorSession, edit: InputConnection.() -> Boolean) {
        assertTrue("The focused InputConnection rejected an IME edit", onMain { edit(editor.connection) })
    }

    private fun awaitEditor(editor: EditorSession, text: String, cursor: Int) {
        var actual: EditorSnapshot? = null
        try {
            awaitCondition("Expected editor text <$text> with cursor $cursor") {
                actual = onMain {
                    editor.connection.getExtractedText(
                        ExtractedTextRequest().apply { hintMaxChars = 512; hintMaxLines = 1 },
                        0,
                    )?.let { EditorSnapshot(it.text.toString(), it.selectionStart, it.selectionEnd) }
                }
                actual == EditorSnapshot(text, cursor, cursor) && editableNode()?.isFocused == true
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}\n${editorDiagnostics(editor, actual)}", failure)
        }
        assertEquals(EditorSnapshot(text, cursor, cursor), actual)
    }

    private fun editorDiagnostics(editor: EditorSession, last: EditorSnapshot?): String {
        val node = editableNode()
        val accessibility = node?.let {
            "text=<${it.text}>, selection=${it.textSelectionStart}..${it.textSelectionEnd}, " +
                "focused=${it.isFocused}, accessibilityFocused=${it.isAccessibilityFocused}, " +
                "visible=${it.isVisibleToUser}, windowId=${it.windowId}, class=${it.className}"
        } ?: "<no visible editable accessibility node>"
        val window = instrumentation.uiAutomation.rootInActiveWindow?.let {
            "package=${it.packageName}, windowId=${it.windowId}, class=${it.className}"
        } ?: "<no active accessibility window>"
        val platform = onMain {
            val owner = editor.owner
            val manager = owner.context.getSystemService(InputMethodManager::class.java)
            "owner=${owner.javaClass.name}@${System.identityHashCode(owner)}, " +
                "attached=${owner.isAttachedToWindow}, focused=${owner.hasFocus()}, " +
                "windowFocus=${owner.hasWindowFocus()}, visible=${owner.visibility}, " +
                "currentFocusedView=${owner.rootView.findFocus()?.javaClass?.name}, " +
                "imeActiveForOwner=${manager?.isActive(owner)}, " +
                "imeAcceptingText=${manager?.isAcceptingText}, " +
                "connection=${editor.connection.javaClass.name}@${System.identityHashCode(editor.connection)}, " +
                "beforeCursor=<${editor.connection.getTextBeforeCursor(512, 0)}>, " +
                "selected=<${editor.connection.getSelectedText(0)}>, " +
                "afterCursor=<${editor.connection.getTextAfterCursor(512, 0)}>"
        }
        // Diagnostic only: the getter was introduced by the fix, so an old APK
        // may not expose it. A missing method is reported without changing the
        // public InputConnection/accessibility assertions or baseline behavior.
        val appEditor = onMain {
            runCatching {
                val activity = checkNotNull(currentActivity)
                val value = ViewModelProvider(activity)[SpoonViewModel::class.java].exploreQueryValue
                "text=<${value.text}>, selection=${value.selection}, composition=${value.composition}"
            }.getOrElse { "unavailable (${it.javaClass.simpleName}: ${it.message})" }
        }
        return "Last captured InputConnection: $last\n" +
            "Rendered editor: $accessibility\nActive window: $window\n" +
            "Platform input: $platform\nSynchronous app editor: $appEditor"
    }

    private fun resultGeneration(activity: MainActivity): Long = onMain {
        ViewModelProvider(activity)[SpoonViewModel::class.java].uiState.value.exploreResultGeneration
    }

    private fun awaitResultsAfter(activity: MainActivity, generation: Long) {
        awaitCondition("A real catalog results update should occur while the editor remains focused") {
            onMain {
                val state = ViewModelProvider(activity)[SpoonViewModel::class.java].uiState.value
                state.exploreResultGeneration > generation && !state.exploreIsLoadingPage
            } && editableNode()?.isFocused == true
        }
    }

    private fun clickLabel(label: String) {
        awaitCondition("Expected actionable control: $label") {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@awaitCondition false
            var node = findNode(root) {
                it.text?.toString() == label || it.contentDescription?.toString() == label
            }
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return@awaitCondition true
                }
                node = node.parent
            }
            false
        }
    }

    private fun editableNode(): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return findNode(root) { it.isEditable && it.isVisibleToUser }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (matches(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNode(child, matches)?.let { return it }
        }
        return null
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        if (!condition()) fail(description)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private data class EditorSession(val owner: View, val connection: InputConnection)
    private data class EditorSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
