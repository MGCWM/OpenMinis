package com.openminis.app.browser

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-shot-gpu-first-2026-10] The inspection round of 2026-10-17 found
 * heavy pages (GitCode dashboard) and WebGL canvases screenshotting as blank
 * grey frames: the pool WebView is detached between actions, Chromium drops
 * its rasterised tiles, and the software `View.draw` reads back a half-painted
 * re-raster. The GPU host solves exactly this — so the agent-facing
 * screenshot paths must TRY it first and only fall back to software.
 *
 * These are source-pinning tests (same style as HeadlessGpuCaptureWiringTest):
 * they assert the wiring a refactor could silently undo, since actually
 * exercising the GPU host needs a real display.
 */
class ScreenshotGpuFirstWiringTest {

    private val manager = File("src/main/java/com/openminis/app/browser/BrowserUseManager.kt").readText()

    @Test
    fun `viewport screenshots prefer the GPU host and fall back to software`() {
        assertTrue(
            "screenshot(): GPU first, software fallback",
            manager.contains("val bitmap = captureWebViewBitmap(gpu = true) ?: captureWebViewBitmap()"),
        )
    }

    @Test
    fun `element screenshots prefer the GPU host too`() {
        // The element path captures the whole viewport then crops, so it has
        // the identical blank-frame exposure as screenshot().
        val elementFun = Regex("""elementScreenshot\(selector: String.*?\n    private suspend fun""", RegexOption.DOT_MATCHES_ALL)
            .find(manager)?.value ?: ""
        assertTrue(
            "elementScreenshot(): GPU first, software fallback",
            elementFun.contains("captureWebViewBitmap(gpu = true) ?: captureWebViewBitmap()"),
        )
    }

    @Test
    fun `captures re-sync the layout box with the applied viewport every time`() {
        // set_viewport/reset used to strand the capture at the PREVIOUS
        // physical size (profile 412x915, 2688x1512 bitmap out). The box is
        // now validated against the applied viewport on EVERY capture, not
        // just when w<=0. The authority is lastAppliedViewport (the session
        // override the pool applied) — NOT the UA profile, which would fight
        // a set_viewport 800x600 and shrink the capture back to 412x915.
        assertTrue(
            manager.contains("if (w != targetW || h != targetH) {"),
        )
        val captureFun = Regex("""captureWebViewBitmap\(gpu: Boolean.*?HeadlessRenderHost\.capture""", RegexOption.DOT_MATCHES_ALL)
            .find(manager)?.value ?: ""
        assertTrue(
            "capture validates against lastAppliedViewport first",
            captureFun.contains("lastAppliedViewport ?: currentProfile.viewportSize"),
        )
    }
}
