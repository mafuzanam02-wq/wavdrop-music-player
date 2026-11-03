package com.launchpoint.wavdrop.ui.components.motion

import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gesture-holder lifecycle tests: the real [PullRefreshController] with a frame-stepped fake animator. */
class PullRefreshControllerTest {

    /** Animator the test steps by hand; records each animation and lets the test deliver frames / finish it. */
    private class ManualAnimator {
        class Run(val from: Float, val to: Float, val onValue: (Float) -> Unit) {
            var finish: (() -> Unit)? = null
        }
        val runs = mutableListOf<Run>()
        val animator: OffsetAnimator = { from, to, onValue ->
            suspendCancellableCoroutine<Unit> { cont ->
                val run = Run(from, to, onValue).also { it.finish = { if (cont.isActive) cont.resume(Unit) } }
                runs += run
            }
        }
    }

    private val rest = 140f
    private fun newController(manual: ManualAnimator = ManualAnimator()) =
        PullRefreshController(CoroutineScope(Dispatchers.Unconfined), manual.animator).also {
            it.configure(viewportPx = 1800f, thresholdPx = 210f) // cap 684, threshold 210
        }

    private fun PullRefreshController.drag(vararg deltas: Float, onCross: () -> Unit = {}) =
        deltas.forEach { pull(it, onCross) }

    private fun PullRefreshController.release(
        refreshing: Boolean = false,
        reduced: Boolean = false,
        fired: () -> Unit = {},
        target: Float = 0f,
    ) = finishGesture(refreshing, reduced, onFire = fired, target = { target })

    @Test fun `short pull released below the threshold returns to zero`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(40f, 20f)
        assertTrue(c.offsetPx > 0f)
        var fires = 0
        assertFalse(c.release(fired = { fires++ }))
        assertEquals(0, fires)
        assertFalse(c.isDragging)
        // A settle to zero starts on its own although no resting target changed anywhere.
        assertEquals(0f, manual.runs.single().to, 0f)
        manual.runs.single().finish!!()
        assertEquals(0f, c.offsetPx, 0f)
    }

    @Test fun `slow drag released later still settles - no fling is required`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        repeat(30) { c.drag(2f) }
        c.release() // pointer release; no fling ever arrives
        manual.runs.single().finish!!()
        assertEquals(0f, c.offsetPx, 0f)
    }

    @Test fun `pull above the threshold fires once and settles to the compact position`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(300f)
        var fires = 0
        assertTrue(c.release(fired = { fires++ }, target = rest))
        assertEquals(1, fires)
        assertEquals(rest, manual.runs.single().to, 0f)
        manual.runs.single().finish!!()
        assertEquals(rest, c.offsetPx, 0f)
        // a second completion signal (pointer release then fling) must not fire again
        assertFalse(c.release(fired = { fires++ }, target = rest))
        assertEquals(1, fires)
    }

    @Test fun `pull past the threshold then reverse below it and release cancels and returns to zero`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(300f)
        c.drag(-290f)
        var fires = 0
        assertFalse(c.release(fired = { fires++ }))
        assertEquals(0, fires)
        manual.runs.single().finish!!()
        assertEquals(0f, c.offsetPx, 0f)
    }

    @Test fun `release while a scan is already active never fires and holds the compact position`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(400f)
        var fires = 0
        assertFalse(c.release(refreshing = true, fired = { fires++ }, target = rest))
        assertEquals(0, fires)
        assertEquals(rest, manual.runs.single().to, 0f)
    }

    @Test fun `a new pull during settlement takes over and the old animation cannot overwrite it`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(100f)
        c.release()
        val settle = manual.runs.single()
        settle.onValue(c.offsetPx * 0.5f) // mid-settle frame
        val mid = c.offsetPx
        c.drag(30f) // finger lands and pulls again
        val withFinger = c.offsetPx
        assertTrue(withFinger > mid)
        settle.onValue(0f) // stale frame from the cancelled settle
        settle.finish!!()
        assertEquals("stale animation must not move content under the finger", withFinger, c.offsetPx, 0f)
        assertTrue(c.isDragging)
    }

    @Test fun `rapid successive gestures each settle and fire at most once`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        var fires = 0
        repeat(3) {
            c.drag(300f)
            // the first gesture requests; later ones happen while that scan is active (refreshing = true)
            c.release(refreshing = it > 0, fired = { fires++ }, target = rest)
        }
        assertEquals(1, fires)
        assertFalse(c.isDragging)
        manual.runs.last().finish!!()
        assertEquals(rest, c.offsetPx, 0f)
    }

    @Test fun `target change while settling retargets from the current position`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(100f)
        c.release(target = 0f)
        val first = manual.runs.single()
        first.onValue(30f)
        c.settleTo(rest, reducedMotion = false) // e.g. the scan became active mid-settle
        assertEquals(2, manual.runs.size)
        assertEquals(30f, manual.runs[1].from, 0f)
        assertEquals(rest, manual.runs[1].to, 0f)
        first.onValue(0f) // stale
        assertEquals(30f, c.offsetPx, 0f)
        manual.runs[1].onValue(rest)
        manual.runs[1].finish!!()
        assertEquals(rest, c.offsetPx, 0f)
    }

    @Test fun `settling to the same target twice does not restart the animation`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(100f)
        c.release(target = 0f)
        c.settleTo(0f, reducedMotion = false)
        assertEquals(1, manual.runs.size)
    }

    @Test fun `settle is a no-op while a finger is down`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(100f)
        val before = c.offsetPx
        c.settleTo(0f, reducedMotion = false)
        assertEquals(before, c.offsetPx, 0f)
        assertTrue(manual.runs.isEmpty())
    }

    @Test fun `reduced motion snaps immediately with no animation`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(100f)
        c.release(reduced = true)
        assertEquals(0f, c.offsetPx, 0f)
        assertTrue(manual.runs.isEmpty())
    }

    @Test fun `disposal stops the settle and ignores late frames`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(100f)
        c.release()
        val run = manual.runs.single()
        val frozen = c.offsetPx
        c.cancel()
        run.onValue(0f)
        assertEquals(frozen, c.offsetPx, 0f)
        assertFalse(c.isDragging)
    }

    @Test fun `disposal mid-drag leaves nothing dragging and no fling debt`() {
        val c = newController()
        c.drag(100f)
        c.cancel()
        assertFalse(c.isDragging)
        assertFalse(c.consumeFling())
    }

    @Test fun `the fling after a pulled gesture is swallowed once, a stale one is cleared by the next touch`() {
        val c = newController()
        c.drag(100f)
        c.release()
        assertTrue(c.consumeFling())
        assertFalse(c.consumeFling())
        c.drag(100f)
        c.release()
        c.onPointerDown()
        assertFalse("an ordinary list fling after a new touch is not swallowed", c.consumeFling())
    }

    @Test fun `no gesture means nothing to finish and no fling is consumed`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        assertFalse(c.release())
        assertFalse(c.consumeFling())
        assertTrue(manual.runs.isEmpty())
    }

    @Test fun `grabbing the compact indicator and returning to the top still settles back to compact when refreshing`() {
        val manual = ManualAnimator()
        val c = newController(manual)
        c.drag(300f)
        c.release(target = rest)
        manual.runs.single().onValue(rest)
        manual.runs.single().finish!!()
        c.drag(-1000f) // grab it and drag back to the top
        assertEquals(0f, c.offsetPx, 0f)
        c.release(refreshing = true, target = rest)
        assertEquals(rest, manual.runs.last().to, 0f)
    }

    @Test fun `haptic callback fires once per crossing gesture`() {
        val c = newController()
        var buzz = 0
        c.drag(60f, 60f, 60f, 60f, 60f, onCross = { buzz++ })
        c.drag(-250f, onCross = { buzz++ })
        c.drag(60f, 60f, 60f, 60f, onCross = { buzz++ })
        assertEquals(1, buzz)
    }

    // ---- Requested vs confirmed scanning ----
    @Test fun `a requested refresh shows a static starting state, not a spinner`() {
        assertEquals(RefreshIndicatorMode.Starting, indicatorMode(ManualRefreshPhase.Requested, isPulling = false))
        assertEquals(RefreshIndicatorMode.Scanning, indicatorMode(ManualRefreshPhase.Scanning, isPulling = false))
        assertTrue(holdsCompactPosition(RefreshIndicatorMode.Starting))
    }
}
