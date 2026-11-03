package com.launchpoint.wavdrop.ui.components.motion

import com.launchpoint.wavdrop.ui.scan.LibraryScanUiState
import com.launchpoint.wavdrop.ui.theme.WavdropMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRefreshModelTest {

    // Galaxy A15-like content viewport (~700dp tall at ~2.6 density => use px) and a small one.
    private fun physics(viewportPx: Float, thresholdPx: Float = 210f) =
        PullRefreshPhysics(viewportPx * PullRefreshPhysics.VIEWPORT_CAP_FRACTION, thresholdPx)

    private val typical = physics(1800f)
    private val small = physics(900f)

    // ---- physics ----
    @Test fun `pull is capped at 38 percent of the viewport and never reaches it`() {
        assertEquals(0.38f, PullRefreshPhysics.VIEWPORT_CAP_FRACTION, 0f)
        for (p in listOf(typical, small)) {
            assertTrue(p.displayed(1_000_000f) <= p.maxPullPx)
            assertTrue(p.displayed(1_000_000f) > p.maxPullPx * 0.99f)
        }
        assertEquals(684f, typical.maxPullPx, 0.01f)
    }

    @Test fun `pull follows the finger at first then resists`() {
        assertEquals(0f, typical.displayed(0f), 0f)
        assertEquals(10f, typical.displayed(10f), 0.2f)
        var previousGain = Float.MAX_VALUE
        var previous = 0f
        for (raw in listOf(100f, 200f, 300f, 400f, 500f)) {
            val shown = typical.displayed(raw)
            val gain = shown - previous
            assertTrue("each further 100px moves the content less", gain < previousGain)
            previousGain = gain
            previous = shown
        }
        assertEquals(0f, typical.displayed(-50f), 0f)
    }

    @Test fun `rawFor inverts displayed`() {
        for (raw in listOf(0f, 50f, 300f, 800f)) assertEquals(raw, typical.rawFor(typical.displayed(raw)), 0.5f)
    }

    @Test fun `threshold is well below the cap and stays reachable on small viewports`() {
        assertTrue(typical.thresholdPx < typical.maxPullPx * 0.5f)
        val tiny = physics(300f, thresholdPx = 500f) // cap 114px
        assertTrue(tiny.thresholdPx <= tiny.maxPullPx * PullRefreshPhysics.MAX_THRESHOLD_FRACTION)
        assertTrue(tiny.displayed(100_000f) >= tiny.thresholdPx)
    }

    // ---- gesture ----
    @Test fun `short pull released below the threshold does not refresh`() {
        val g = PullGesture(typical)
        g.pull(60f)
        assertFalse(g.release(alreadyRefreshing = false))
        assertEquals(0f, g.displayedPx, 0f)
    }

    @Test fun `pull past the threshold refreshes once on release, and only once`() {
        val g = PullGesture(typical)
        g.pull(400f)
        assertTrue(g.displayedPx >= typical.thresholdPx)
        assertTrue(g.release(alreadyRefreshing = false))
        assertFalse("a second release without a new pull must not refresh", g.release(alreadyRefreshing = false))
    }

    @Test fun `reversing before release cancels the refresh`() {
        val g = PullGesture(typical)
        g.pull(400f)
        g.pull(-380f)
        assertFalse(g.release(alreadyRefreshing = false))
    }

    @Test fun `pulling back never goes above the start`() {
        val g = PullGesture(typical)
        assertEquals(0f, g.pull(-30f).consumedRawPx, 0f)
        g.pull(40f)
        assertEquals(-40f, g.pull(-500f).consumedRawPx, 0f)
        assertFalse(g.isPulling)
    }

    @Test fun `release while already refreshing never starts another refresh`() {
        val g = PullGesture(typical)
        g.pull(500f)
        assertFalse(g.release(alreadyRefreshing = true))
    }

    @Test fun `threshold haptic fires once per gesture even when crossed repeatedly`() {
        val g = PullGesture(typical)
        var buzzes = 0
        fun step(d: Float) { if (g.pull(d).crossedThresholdUp) buzzes++ }
        repeat(10) { step(20f) }     // crosses
        step(-400f)                  // back under
        repeat(10) { step(50f) }     // crosses again
        assertEquals(1, buzzes)
        g.release(false)
        repeat(30) { step(20f) }     // new gesture
        assertEquals(2, buzzes)
    }

    @Test fun `grabbing the compact indicator past the threshold does not buzz`() {
        val g = PullGesture(typical)
        g.begin(typical.thresholdPx + 5f)
        assertFalse(g.pull(5f).crossedThresholdUp)
    }

    // ---- scan-state mapping ----
    private fun run(vararg scans: LibraryScanUiState, from: ManualRefreshPhase) =
        scans.fold(from) { phase, scan -> reduceManualRefresh(phase, scan) }

    @Test fun `a user refresh invokes the action exactly once and succeeds only after Scanning then Complete`() {
        val request = requestManualRefresh(ManualRefreshPhase.None, LibraryScanUiState.Idle)
        assertTrue(request.invokeRefresh)
        assertEquals(ManualRefreshPhase.Requested, request.phase)
        val scanning = reduceManualRefresh(request.phase, LibraryScanUiState.Scanning)
        assertEquals(ManualRefreshPhase.Scanning, scanning)
        assertEquals(RefreshIndicatorMode.Scanning, indicatorMode(scanning, isPulling = false))
        val done = reduceManualRefresh(scanning, LibraryScanUiState.Complete)
        assertEquals(ManualRefreshPhase.Succeeded, done)
        assertEquals(RefreshIndicatorMode.Success, indicatorMode(done, false))
    }

    @Test fun `a stale Complete before the scan starts is never success`() {
        val request = requestManualRefresh(ManualRefreshPhase.None, LibraryScanUiState.Complete)
        assertEquals(ManualRefreshPhase.Requested, run(LibraryScanUiState.Complete, from = request.phase))
        assertEquals(ManualRefreshPhase.Requested, run(LibraryScanUiState.Error("old"), from = request.phase))
    }

    @Test fun `warning and error never show success`() {
        for (end in listOf(LibraryScanUiState.Warning("kept"), LibraryScanUiState.Error("failed"))) {
            val phase = run(LibraryScanUiState.Scanning, end, from = ManualRefreshPhase.Requested)
            assertEquals(ManualRefreshPhase.None, phase)
            assertEquals(RefreshIndicatorMode.Hidden, indicatorMode(phase, false))
        }
    }

    @Test fun `an interrupted scan (Scanning to Idle) is not success`() {
        assertEquals(ManualRefreshPhase.None, run(LibraryScanUiState.Scanning, LibraryScanUiState.Idle, from = ManualRefreshPhase.Requested))
    }

    @Test fun `repeated pulls while a refresh is in flight do not start another scan`() {
        for (phase in listOf(ManualRefreshPhase.Requested, ManualRefreshPhase.Scanning)) {
            val r = requestManualRefresh(phase, LibraryScanUiState.Scanning)
            assertFalse(r.invokeRefresh)
            assertEquals(phase, r.phase)
        }
    }

    @Test fun `a legitimate new request after success or failure is allowed`() {
        assertTrue(requestManualRefresh(ManualRefreshPhase.Succeeded, LibraryScanUiState.Complete).invokeRefresh)
        assertTrue(requestManualRefresh(ManualRefreshPhase.None, LibraryScanUiState.Error("x")).invokeRefresh)
    }

    @Test fun `pulling during an automatic initial scan adopts it instead of duplicating`() {
        val r = requestManualRefresh(ManualRefreshPhase.None, LibraryScanUiState.Scanning)
        assertFalse(r.invokeRefresh)
        assertEquals(ManualRefreshPhase.Scanning, r.phase)
        assertEquals(ManualRefreshPhase.Succeeded, reduceManualRefresh(r.phase, LibraryScanUiState.Complete))
    }

    @Test fun `an automatic scan alone never produces a manual phase or success`() {
        assertEquals(ManualRefreshPhase.None, run(LibraryScanUiState.Scanning, LibraryScanUiState.Complete, from = ManualRefreshPhase.None))
        assertEquals(RefreshIndicatorMode.Hidden, indicatorMode(ManualRefreshPhase.None, isPulling = false))
    }

    @Test fun `indicator modes and compact position`() {
        assertEquals(RefreshIndicatorMode.Pulling, indicatorMode(ManualRefreshPhase.None, isPulling = true))
        assertTrue(holdsCompactPosition(RefreshIndicatorMode.Scanning))
        assertTrue(holdsCompactPosition(RefreshIndicatorMode.Success))
        assertFalse(holdsCompactPosition(RefreshIndicatorMode.Hidden))
        assertFalse(holdsCompactPosition(RefreshIndicatorMode.Pulling))
    }

    @Test fun `success copy makes no claim about discovered songs`() {
        assertFalse(PullRefreshCopy.SUCCESS.contains(Regex("\\d|new|found|added", RegexOption.IGNORE_CASE)))
    }

    // ---- reduced motion + wiring guards (no Compose UI test dependency in unit tests) ----
    @Test fun `reduced motion policy is the shared one`() {
        assertTrue(WavdropMotion.isReducedMotion(0f))
        assertFalse(WavdropMotion.isReducedMotion(1f))
        val src = File("src/main/kotlin/com/launchpoint/wavdrop/ui/components/motion/PullToRefreshContainer.kt").readText()
        assertTrue("rememberReducedMotion()" in src)
        val controller = File("src/main/kotlin/com/launchpoint/wavdrop/ui/components/motion/PullRefreshController.kt").readText()
        assertTrue("reducedMotion || offsetPx == targetPx" in controller) // settle snaps instead of animating
        assertTrue("if (!reducedMotion) rotationZ" in src)         // no rotation under reduced motion
        assertTrue("WavdropHaptics.threshold(haptics)" in src)
    }

    @Test fun `the container reads offsets only in graphics layers so lists do not recompose per frame`() {
        val src = File("src/main/kotlin/com/launchpoint/wavdrop/ui/components/motion/PullToRefreshContainer.kt").readText()
        assertTrue("graphicsLayer { translationY = controller.offsetPx }" in src)
        assertFalse("no second scan authority", "LibraryScanCoordinator()" in src || "repository." in src || "MutableStateFlow" in src)
    }
}
