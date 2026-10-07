package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ST-1: an armed sleep-timer terminal boundary outranks crossfade auto-continuation. Pure plan/ownership rules plus the REAL
 * two-slot engine (real ExoPlayer pair, the accepted CF-2M promotion runtime and NEXT preparation owner): no second crossfade
 * system exists, the boundary only feeds the existing eligibility and cancellation seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SleepBoundaryCrossfadeTest {

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 180_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private val queue = (1L..4L).map(::song)

    private fun plan(
        armed: Boolean, repeat: RepeatMode = RepeatMode.OFF, playing: Boolean = true, eq: Boolean = false, sync: Boolean = false,
        external: Boolean = false, configured: Long = 6_000L, index: Int = 1,
    ) = planCrossfadeTransition(
        configuredDurationMs = configured, playbackQueue = queue, currentPlaybackIndex = index, repeatMode = repeat, isPlaying = playing,
        isExternalPlayback = external, playerQueueNeedsSync = sync, equalizerEnabled = eq, sleepBoundaryArmed = armed,
    )

    private fun snapshot(armed: Boolean) = CrossfadeRuntimeSnapshot(
        queueGeneration = 7L, playbackQueue = queue, currentPlaybackIndex = 1, repeatMode = RepeatMode.OFF, shuffleEnabled = false,
        isPlaying = true, isExternalPlayback = false, playerQueueNeedsSync = false, controllerConnected = true, sleepBoundaryArmed = armed,
    )

    // ── pure eligibility / ownership ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `an armed boundary makes the transition unavailable and a released one restores eligibility`() {
        assertTrue(plan(armed = false) is CrossfadeTransitionPlan.Eligible)
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.SleepBoundaryArmed), plan(armed = true))
    }

    @Test fun `the boundary outranks Repeat One and every later rule but never an earlier one`() {
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.SleepBoundaryArmed), plan(armed = true, repeat = RepeatMode.ONE))
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.SleepBoundaryArmed), plan(armed = true, repeat = RepeatMode.ALL, index = 3))
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.Disabled), plan(armed = true, configured = 0L))
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.ExternalPlayback), plan(armed = true, external = true))
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.PlayerQueueNeedsSync), plan(armed = true, sync = true))
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.NotPlaying), plan(armed = true, playing = false))
        assertEquals(CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.EqualizerEnabled), plan(armed = true, eq = true))
    }

    @Test fun `the runtime snapshot carries the boundary into the shared planner`() {
        assertTrue(planCrossfadeFromRuntimeSnapshot(6_000L, snapshot(false), null) is CrossfadeTransitionPlan.Eligible)
        assertEquals(
            CrossfadeTransitionPlan.Unavailable(CrossfadeUnavailableReason.SleepBoundaryArmed),
            planCrossfadeFromRuntimeSnapshot(6_000L, snapshot(true), null),
        )
        assertFalse("the snapshot default never blocks crossfade", CrossfadeRuntimeSnapshot(1L, queue, 0, RepeatMode.OFF, false, true, false, false, true).sleepBoundaryArmed)
    }

    @Test fun `an owned transition is lost the moment the boundary arms`() {
        val key = CrossfadeTransitionKey(7L, 1, 2)
        assertNull(crossfadeOwnershipLossReason(snapshot(false), key))
        assertEquals(CrossfadeCancelReason.PlanInvalidated, crossfadeOwnershipLossReason(snapshot(true), key))
    }

    @Test fun `the boundary cancel reason reuses the shared family and settles an overlap to B`() {
        assertEquals(CrossfadeCancelReason.PlanInvalidated, SLEEP_BOUNDARY_CANCEL_REASON)
        assertEquals(PromotionInterruption.PlanInvalidated, PromotionInterruption.from(SLEEP_BOUNDARY_CANCEL_REASON))
        val sink = mutableListOf<CrossfadeCancelReason>()
        recoverCrossfadeFromSleepBoundary { sink += it }
        recoverCrossfadeFromSleepBoundary(null) // a null sink (crossfade gate/runtime absent) is a no-op
        assertEquals(listOf(SLEEP_BOUNDARY_CANCEL_REASON), sink)
    }

    // ── the real two-slot engine ────────────────────────────────────────────────────────────────────────────────────────

    private class FakeScheduler : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
        val all = mutableListOf<Pending>()
        override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
        override fun cancelAll() { all.forEach { it.active = false } }
    }

    private class Rig(test: SleepBoundaryCrossfadeTest, prepared: Boolean = true) {
        val base = PromotionRig().let { if (prepared) it.playAndPrepare() else it.also { r -> r.f.facade.play(); idleMainLooper() } }
        var armed = false
        var now = 1_000L
        val songs = (1L..4L).map(test::song)
        val scheduler = FakeScheduler()
        val snapshotProvider = { CrossfadeRuntimeSnapshot(
            queueGeneration = 7L, playbackQueue = songs, currentPlaybackIndex = base.from, repeatMode = RepeatMode.OFF, shuffleEnabled = false,
            isPlaying = true, isExternalPlayback = false, playerQueueNeedsSync = false, controllerConnected = true, sleepBoundaryArmed = armed,
        ) }
        val runtime = CrossfadePromotionRuntime(
            engine = base.engine, snapshotProvider = snapshotProvider, configuredDurationMsProvider = { 6_000L },
            scheduler = FakeScheduler(), clock = { now }, debugLog = null,
        )
        val driver = NextSlotPreparationDriver(
            preparation = base.engine.nextPreparation, snapshotProvider = snapshotProvider,
            materialize = { base.queue }, configuredDurationMsProvider = { 6_000L }, currentDurationMsProvider = { 180_000L },
            scheduler = scheduler,
        )
        val p1 get() = base.p1
        val p2 get() = base.p2
        fun position(ms: Long) = p1.mutate { setContentPositionMs(ms) }

        /** What the service's boundary listener does on arm. */
        fun arm() {
            armed = true
            recoverCrossfadeFromSleepBoundary(CrossfadeCancelSink { reason -> driver.cancel(reason); runtime.cancel(reason) })
        }
    }

    @Test fun `timer expires well before the fade window - nothing is ever prepared or promoted afterwards`() {
        val r = Rig(this, prepared = true)
        r.position(30_000L)
        r.arm()
        assertEquals("a prepared NEXT is released", NextSlotState.Idle, r.base.engine.nextPreparation.state)
        repeat(3) { r.driver.evaluate(); r.runtime.evaluate() }
        assertEquals("no re-preparation across the boundary", NextSlotState.Idle, r.base.engine.nextPreparation.state)
        r.position(175_000L)
        r.runtime.evaluate()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertTrue("NEXT never started", r.p2.commands.none { it.startsWith("play") })
        assertSame("the current track keeps playing as CURRENT", r.p1, r.base.engine.currentPlayer)
        assertEquals("full normal gain", 1f, r.p1.volume, 0f)
    }

    @Test fun `timer expires while NEXT is prepared but silent - it is released and stays silent`() {
        val r = Rig(this, prepared = true)
        assertTrue(r.base.engine.nextPreparation.state is NextSlotState.Ready)
        r.arm()
        assertEquals(NextSlotState.Idle, r.base.engine.nextPreparation.state)
        assertFalse(r.p2.playWhenReady)
    }

    @Test fun `timer expires exactly at the fade window boundary - the promotion never starts`() {
        val r = Rig(this, prepared = true)
        r.position(173_500L) // inside the fade window of the 180 s track
        r.arm()
        r.runtime.evaluate()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertSame(r.p1, r.base.engine.currentPlayer)
        assertTrue(r.p2.commands.none { it.startsWith("play") })
    }

    @Test fun `a promotion decided before expiry is the logical current - the boundary then cuts the retiring A and keeps B at full gain`() {
        val r = Rig(this, prepared = true)
        r.position(173_500L)
        r.runtime.evaluate()
        assertTrue("the overlap had begun: B is already the logical CURRENT", r.runtime.state is PromotionOverlapState.Overlap)
        assertSame(r.p2, r.base.engine.currentPlayer)
        r.now += 1_500L
        r.arm() // the timer expires NOW; the terminal occurrence is the resolved CURRENT (B), never A
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertSame("the authoritative occurrence after the settle is B", r.p2, r.base.engine.currentPlayer)
        assertSame(r.p2, r.base.f.facade.delegatePlayer)
        assertEquals("B plays at full normal gain", 1f, r.p2.volume, 0f)
        assertEquals("the retiring A is cut and cleared", 0, r.p1.mediaItemCount)
        assertEquals(1, r.base.f.events.events.count { it.startsWith("transition(") })
        // no second overlap can follow across the boundary
        r.driver.evaluate(); r.runtime.evaluate()
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
    }

    @Test fun `releasing the boundary re-enables the existing eligibility so preparation resumes`() {
        val r = Rig(this, prepared = false)
        r.arm()
        r.driver.evaluate()
        assertEquals(NextSlotState.Idle, r.base.engine.nextPreparation.state)
        r.armed = false // the boundary was consumed or cancelled
        r.driver.evaluate()
        idleMainLooper()
        assertFalse("preparation was requested again", r.base.engine.nextPreparation.state == NextSlotState.Idle)
    }

    @Test fun `a standalone end of current song behaves identically with crossfade enabled`() {
        val r = Rig(this, prepared = true)
        r.arm() // standalone arms immediately; the same seam as an expiring duration timer
        r.position(175_000L)
        repeat(2) { r.driver.evaluate(); r.runtime.evaluate() }
        assertEquals(PromotionOverlapState.Idle, r.runtime.state)
        assertEquals(NextSlotState.Idle, r.base.engine.nextPreparation.state)
    }

    @Test fun `finish OFF at expiry never touches crossfade - the boundary seam is not used`() {
        val r = Rig(this, prepared = true)
        // A pausing expiry does not arm anything: the prepared NEXT and the eligibility are untouched.
        assertTrue(r.base.engine.nextPreparation.state is NextSlotState.Ready)
        assertTrue(planCrossfadeFromRuntimeSnapshot(6_000L, r.snapshotProvider(), 180_000L) is CrossfadeTransitionPlan.Eligible)
    }
}
