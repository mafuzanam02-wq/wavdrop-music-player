package com.launchpoint.wavdrop.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * DIAGNOSTIC, NOT A GATE. Prints per-operation costs of the façade's timeline identity path against REAL ExoPlayers so the
 * numbers can be reported before/after a change. It deliberately asserts nothing about wall-clock time (a flaky timing assertion
 * is exactly what this project does not want); the deterministic regression guards live in [CrossfadeTimelineIdentityTest].
 *
 * Opt-in: `-Dwavdrop.bench=1` (forwarded as the `wavdrop.bench` system property), otherwise the test is a no-op.
 * Operations are measured SEPARATELY:
 *  - setMediaItems: full queue install on a physical player (the architecture's O(N) queue copy; identity-independent)
 *  - stateRebuild:  one façade state evaluation (Media3 converts the physical timeline and builds the PlaylistTimeline)
 *  - promotion:     replaceDelegate (state evaluation of the new delegate + uid aliasing + presented timeline)
 *  - uidLookup:     Timeline.getIndexOfPeriod for 200 spread uids on the presented timeline
 *  - hash spread:   distinct period-uid hash codes of the presented timeline (the structural invariant)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CrossfadeTimelineIdentityBenchmarkTest {

    private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000

    @Test fun report() {
        if (System.getProperty("wavdrop.bench") == null && System.getenv("WAVDROP_BENCH") == null) return
        val context = RuntimeEnvironment.getApplication()
        val sizes = (System.getenv("WAVDROP_BENCH_SIZES") ?: "100,1000,5000,10000,25000").split(",").map { it.trim().toInt() }
        for (n in sizes) {
            val items = (1L..n).map { RealMedia3QueueRig.song(it % 50 + 1).toPlaybackMediaItem() } // heavy duplicate song ids
            val p1 = TestMediaSource.newPlayer(context)
            val p2 = TestMediaSource.newPlayer(context)
            var t = System.nanoTime(); p1.setMediaItems(items, 3, 0L); p1.prepare(); val install1 = ms(t)
            p2.setMediaItems(items, 3, 0L); p2.prepare()
            TestMediaSource.settle(300)
            t = System.nanoTime(); val facade = SessionFacade(p1); val construct = ms(t)
            t = System.nanoTime(); facade.invalidateLogicalState(); val rebuild = ms(t)
            t = System.nanoTime(); facade.replaceDelegate(p2, presentAsAutoTransition = true); val promotion = ms(t)
            val timeline = facade.currentTimeline
            val period = Timeline.Period()
            val uids = (0 until timeline.periodCount step maxOf(1, timeline.periodCount / 200)).map { timeline.getPeriod(it, period, true).uid!! }
            t = System.nanoTime(); uids.forEach { timeline.getIndexOfPeriod(it) }; val lookup = ms(t)
            val distinctHashes = (0 until timeline.periodCount).map { timeline.getUidOfPeriod(it).hashCode() }.toSet().size
            t = System.nanoTime(); facade.invalidateLogicalState(); val rebuildAfter = ms(t)
            assertEquals(n, timeline.windowCount)
            println(
                "BENCH n=$n installQueue=${install1}ms facadeConstruct=${construct}ms stateRebuild=${rebuild}ms promotion=${promotion}ms " +
                    "stateRebuildAfterPromotion=${rebuildAfter}ms uidLookup(${uids.size})=${lookup}ms distinctPeriodUidHashes=$distinctHashes/$n",
            )
            p1.release(); p2.release()
        }
    }
}
