package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-selection-cleanup-2026-10] Long-press / drag gestures (and
 * coordinate taps landing on text) leave the WebView in its text-selection
 * state; the blue highlighted region then persists over the page and pollutes
 * later screenshots (users saw a giant selection "dome"). Every
 * side-effecting action must collapse the selection in its wrap-up.
 */
class SelectionCleanupWiringTest {

    private val manager = File("src/main/java/com/openminis/app/browser/BrowserUseManager.kt").readText()
    private val js = File("src/main/java/com/openminis/app/browser/BrowserUseJS.kt").readText()

    @Test
    fun `visual-change action wrap-up clears the selection`() {
        // The wrap-up runs BEFORE attachSnapshot so the auto-snapshot and the
        // subsequent user screenshot see a clean page.
        val idx = manager.indexOf("if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {")
        val wrapUp = manager.substring(idx, idx + 1400)
        assertTrue("wrap-up runs CLEAR_SELECTION_JS", wrapUp.contains("BrowserUseJS.CLEAR_SELECTION_JS"))
        assertTrue(
            "clear runs before the snapshot, not after",
            wrapUp.indexOf("CLEAR_SELECTION_JS") < wrapUp.indexOf("attachSnapshot"),
        )
    }

    @Test
    fun `the clearing snippet is idempotent and focus-safe`() {
        assertTrue("collapses ranges", js.contains("sel.removeAllRanges()"))
        assertTrue("keeps INPUT focus for typing flows", js.contains("tagName !== 'INPUT'"))
        assertTrue("keeps TEXTAREA focus for typing flows", js.contains("tagName !== 'TEXTAREA'"))
        assertTrue("keeps contenteditable focus for rich editors", js.contains("isContentEditable"))
    }
}
