package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-C correction: an incremental window adjustment is acknowledged only if the PHYSICAL current is exactly the occurrence that was
 * captured at planning (from the physical player itself), advanced by the natural transitions that player counted. Matching the whole
 * playlist is necessary but NOT sufficient; these tests change only the current item and show the adjustment is rejected anyway.
 * Real Media3 (ExoPlayer behind a MediaSession, driven through a MediaController) with the real physical probe; the ordering of the
 * acknowledgement is controlled by the test, never by sleeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalWindowCurrentOccurrenceTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()

    @After fun tearDown() {
        rigs.forEach { it.releaseAll() }; rigs.clear()
    }

    private fun distinct(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }

    /**
     * Answers the first [skip] requests immediately (the baseline), then HOLDS every later acknowledgement until [release], so the
     * test decides what the physical player looks like when the acknowledgement is finally sampled.
     */
    private class Controlled(val r: RealMedia3QueueRig) : PhysicalInstallVerifier {
        var holding = false
        var skip = 0
        var live = false
        val held = mutableListOf<Pair<PlanToken, SettableFuture<PhysicalObservation>>>()
        var requests = 0

        override fun observe(token: PlanToken): ListenableFuture<PhysicalObservation> {
            requests++
            if (holding && !live) {
                if (skip > 0) { skip--; return Futures.immediateFuture(PhysicalObservation.observe(r.player, token)) }
                return SettableFuture.create<PhysicalObservation>().also { held += token to it }
            }
            return Futures.immediateFuture(PhysicalObservation.observe(r.player, token))
        }

        /** Hold every acknowledgement after the baseline of the next adjustment. */
        fun holdAfterBaseline() { holding = true; skip = 1; live = false }

        /** From now on answer from the physical player as it is, including everything held. */
        fun release() {
            live = true
            held.forEach { (token, future) -> future.set(PhysicalObservation.observe(r.player, token)) }
            held.clear()
        }
    }

    private inner class W(
        val q: List<Song>,
        policy: PhysicalWindowPolicy = PhysicalWindowPolicy.DEFAULT,
        var repeat: RepeatMode = RepeatMode.OFF,
    ) {
        val r = RealMedia3QueueRig(q, 0, loadInitialQueue = false, physicalAck = true).also { rigs += it }
        var generation = 7L
        val outcomes = mutableListOf<BoundedInstallOutcome>()
        val adapter = PhysicalWindowAdapter(policy = policy, currentLogicalGeneration = { generation })
        val ctl = Controlled(r)

        fun idle() = TestMediaSource.awaitCondition { !adapter.isBusy }

        fun install(start: Int) {
            adapter.install(r.port, q, generation, start, 0L, repeat, ctl) { outcomes += it }
            idle()
        }

        fun navigate(target: Int): BoundedNavigationResult {
            val result = adapter.navigateTo(r.port, q, target, 0L, repeat, ctl) { outcomes += it }
            idle()
            return result
        }

        fun maintain(): MaintenanceResult = adapter.maintain(r.port, q, repeat, ctl) { outcomes += it }

        fun quiet(max: Int = 30) {
            repeat(max) {
                when (maintain()) {
                    is MaintenanceResult.NoChange -> return
                    else -> idle()
                }
            }
            error("did not quiesce")
        }

        fun serverIds(): List<String> = r.serverIds()

        /**
         * A natural advance: the physical player REALLY moves to the next item, and the AUTO notification ExoPlayer would send for it is
         * delivered to the physical player's own natural-transition listener (a trackless JVM source cannot play to its end).
         */
        fun naturalAdvance() {
            r.player.seekToNextMediaItem()
            PhysicalObservation.naturalTransitionListener(r.player).onMediaItemTransition(r.player.currentMediaItem, androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }
        fun physicalCurrentId(): String? = r.player.currentMediaItem?.mediaId

        /** The mutations of the held adjustment have been applied by the physical player. */
        fun awaitApplied(expectedCount: Int) = TestMediaSource.awaitCondition { r.player.mediaItemCount == expectedCount }

        fun assertWindowIsConsistent() {
            val a = adapter.active!!
            assertEquals((0 until a.installedCount).map { q[a.physicalToLogical(it)!!].id.toString() }, serverIds())
            assertEquals(a.physicalStart, r.player.currentMediaItemIndex)
            assertTrue(a.installedCount <= PhysicalWindowPolicy.W_MAX)
        }
    }

    // ── the preserved occurrence, per adjustment shape ──────────────────────────────────────────────────────────────────

    @Test fun appendOnlyRefillPreservesTheCurrentOccurrenceAtTheSamePhysicalIndex() {
        val w = W(distinct(25_000), PhysicalWindowPolicy(PhysicalWindowPolicy.Config(historyMax = 60)))
        w.install(0) // window [0..79]
        w.navigate(50)
        val before = w.r.recording.calls.size
        w.quiet()
        val ops = w.r.recording.calls.drop(before)
        assertTrue("append-only: $ops", ops.any { it.startsWith("append(") } && ops.none { it.startsWith("remove(") || it.startsWith("add(") })
        assertEquals("51", w.physicalCurrentId())
        assertEquals(50, w.r.player.currentMediaItemIndex) // append never moves existing indices
        assertEquals(50, w.adapter.active!!.logicalCurrent)
        assertEquals(100, w.r.player.mediaItemCount)
        w.assertWindowIsConsistent()
    }

    @Test fun frontTrimPreservesTheCurrentOccurrenceAfterThePhysicalIndexShifts() {
        val w = W(distinct(25_000))
        w.install(100) // window [80..179]
        w.navigate(150)
        assertEquals("before the trim the current sits at physical 70", 70, w.r.player.currentMediaItemIndex)
        val before = w.r.recording.calls.size
        w.quiet()
        val ops = w.r.recording.calls.drop(before)
        assertTrue("front trim: $ops", ops.any { it.startsWith("remove(0,") })
        assertEquals("151", w.physicalCurrentId())
        assertTrue("the physical index shifted down", w.r.player.currentMediaItemIndex < 70)
        assertEquals(150, w.adapter.active!!.logicalCurrent)
        assertEquals(w.adapter.active!!.logicalToPhysical(150), w.r.player.currentMediaItemIndex)
        w.assertWindowIsConsistent()
    }

    @Test fun historyPrependAndTailTrimPreserveTheCurrentOccurrence() {
        val w = W(distinct(25_000))
        w.install(5_000) // window [4980..5079]
        w.navigate(4_985) // history 5 < HISTORY_MIN: prepend, making room by trimming the far tail
        val before = w.r.recording.calls.size
        w.quiet()
        val ops = w.r.recording.calls.drop(before)
        assertTrue("tail trim + prepend: $ops", ops.any { it.startsWith("remove(") } && ops.any { it.startsWith("add(0,") })
        assertEquals("4986", w.physicalCurrentId())
        assertEquals(4_985, w.adapter.active!!.logicalCurrent)
        assertTrue("history was recovered in front of the current", w.r.player.currentMediaItemIndex > 5)
        assertTrue(w.r.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        w.assertWindowIsConsistent()
    }

    @Test fun tailTrimOnlyAfterLeavingRepeatAllPreservesTheCurrentOccurrence() {
        val w = W(distinct(25_000), repeat = RepeatMode.ALL)
        w.install(24_990) // ring window: wraps past the logical end
        assertTrue(w.adapter.active!!.window.wraps)
        val indexBefore = w.r.player.currentMediaItemIndex
        w.repeat = RepeatMode.OFF
        val before = w.r.recording.calls.size
        w.quiet()
        val ops = w.r.recording.calls.drop(before)
        assertTrue("tail trim only: $ops", ops.any { it.startsWith("remove(") } && ops.none { it.startsWith("add(") || it.startsWith("append(") })
        assertFalse(w.adapter.active!!.window.wraps)
        assertEquals("24991", w.physicalCurrentId())
        assertEquals(indexBefore, w.r.player.currentMediaItemIndex)
        assertEquals(24_990, w.adapter.active!!.logicalCurrent)
        w.assertWindowIsConsistent()
    }

    // ── the playlist matches, the current does not ──────────────────────────────────────────────────────────────────────

    @Test fun unexpectedPhysicalCurrentChangeIsRejectedEvenThoughTheWholePlaylistMatches() {
        val w = W(distinct(25_000))
        w.install(100)
        w.navigate(150)
        w.ctl.holdAfterBaseline()
        assertTrue(w.maintain() is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.r.player.mediaItemCount != 100 || w.r.player.getMediaItemAt(0).mediaId != "81" }
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        val idsWhenApplied = w.serverIds()
        // Change ONLY the current item (a different occurrence of the same, correct playlist).
        val moved = (w.r.player.currentMediaItemIndex + 3).coerceAtMost(w.r.player.mediaItemCount - 1)
        w.r.player.seekTo(moved, 0L)
        TestMediaSource.awaitCondition { w.r.player.currentMediaItemIndex == moved }
        assertEquals("the playlist itself is exactly the intended window", idsWhenApplied, w.serverIds())
        val callsBefore = w.r.recording.calls.size
        w.ctl.release()
        TestMediaSource.awaitCondition { w.outcomes.any { it is BoundedInstallOutcome.Incomplete } }
        val incomplete = w.outcomes.filterIsInstance<BoundedInstallOutcome.Incomplete>().first()
        assertTrue(incomplete.detail, incomplete.detail.startsWith("physical: current index"))
        assertTrue("never published as the adjusted window", w.outcomes.dropWhile { it !is BoundedInstallOutcome.Incomplete }.none { it is BoundedInstallOutcome.Active && it.installed.logicalCurrent != 150 })
        // Bounded recovery only: ONE install of at most W_MAX around the last authoritative occurrence; never the full queue.
        w.idle()
        val installs = w.r.recording.calls.drop(callsBefore).filter { it.startsWith("setMediaItems") }
        assertEquals(listOf("setMediaItems(100)"), installs)
        assertEquals(150, w.adapter.active!!.logicalCurrent)
        assertEquals("151", w.physicalCurrentId())
    }

    @Test fun anUncountedForwardMoveIsAmbiguousAndFailsClosed() {
        val w = W(distinct(25_000))
        w.install(100)
        w.navigate(150)
        w.ctl.holdAfterBaseline()
        assertTrue(w.maintain() is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        TestMediaSource.awaitCondition { w.r.player.getMediaItemAt(0).mediaId != "81" }
        val before = PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions
        w.r.player.seekToNextMediaItem() // looks like an advance, but it is a SEEK: the physical player counts no natural transition
        TestMediaSource.awaitCondition { w.physicalCurrentId() == "152" }
        assertEquals(before, PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions)
        w.ctl.release()
        TestMediaSource.awaitCondition { w.outcomes.any { it is BoundedInstallOutcome.Incomplete } }
        w.idle()
        assertTrue(w.outcomes.none { it is BoundedInstallOutcome.Active && it.installed.logicalCurrent == 151 })
        assertEquals("fail closed back to the last authoritative occurrence", 150, w.adapter.active!!.logicalCurrent)
    }

    // ── a legitimate natural transition while the acknowledgement is pending ────────────────────────────────────────────

    @Test fun aNaturalTransitionWhileTheAckIsPendingIsAcceptedAtTheExactSuccessor() {
        val w = W(distinct(25_000))
        w.install(100)
        w.navigate(150) // paused on the short item
        w.ctl.holdAfterBaseline()
        assertTrue(w.maintain() is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        TestMediaSource.awaitCondition { w.r.player.getMediaItemAt(0).mediaId != "81" }
        val auto0 = PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions
        w.naturalAdvance() // the physical player moves to the successor, reported as a NATURAL transition
        assertEquals(auto0 + 1, PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions)
        assertEquals("152", w.physicalCurrentId())
        w.ctl.release()
        TestMediaSource.awaitCondition { w.outcomes.any { it is BoundedInstallOutcome.Active && it.installed.window.windowGeneration >= 1 } }
        val active = w.adapter.active!!
        assertEquals("exactly the successor of the planned occurrence", 151, active.logicalCurrent)
        assertEquals("152", w.physicalCurrentId())
        assertEquals(active.logicalToPhysical(151), w.r.player.currentMediaItemIndex)
        assertTrue("no recovery install was needed", w.r.recording.calls.count { it.startsWith("setMediaItems") } == 1)
    }

    @Test fun aNaturalTransitionPlusAnExtraUncountedMoveIsRejected() {
        val w = W(distinct(25_000))
        w.install(100)
        w.navigate(150)
        w.ctl.holdAfterBaseline()
        assertTrue(w.maintain() is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        TestMediaSource.awaitCondition { w.r.player.getMediaItemAt(0).mediaId != "81" }
        val auto0 = PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions
        w.naturalAdvance()
        assertEquals(auto0 + 1, PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions)
        w.r.player.seekToNextMediaItem() // one move beyond the single counted natural transition
        TestMediaSource.awaitCondition { w.physicalCurrentId() == "153" }
        w.ctl.release()
        TestMediaSource.awaitCondition { w.outcomes.any { it is BoundedInstallOutcome.Incomplete } }
        w.idle()
        assertTrue(w.outcomes.none { it is BoundedInstallOutcome.Active && it.installed.logicalCurrent == 152 })
        assertEquals(150, w.adapter.active!!.logicalCurrent)
    }

    // ── adjacent duplicates: an id can never prove the occurrence ───────────────────────────────────────────────────────

    private fun adjacentDuplicates(): List<Song> =
        distinct(25_000).mapIndexed { i, s -> if (i in 149..151) RealMedia3QueueRig.song(77_777L) else s }

    @Test fun adjacentDuplicatesAreHandledByPositionOnACleanAdjustment() {
        val w = W(adjacentDuplicates())
        w.install(100)
        w.navigate(150) // the MIDDLE one of three identical neighbours
        w.quiet()
        assertEquals(150, w.adapter.active!!.logicalCurrent)
        assertEquals("77777", w.physicalCurrentId())
        assertEquals(w.adapter.active!!.logicalToPhysical(150), w.r.player.currentMediaItemIndex)
        w.assertWindowIsConsistent()
    }

    @Test fun anAdjacentDuplicateNeighbourIsNotAcceptedAsTheCurrentOccurrence() {
        val w = W(adjacentDuplicates())
        w.install(100)
        w.navigate(150)
        w.ctl.holdAfterBaseline()
        assertTrue(w.maintain() is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        TestMediaSource.awaitCondition { w.r.player.getMediaItemAt(0).mediaId != "81" }
        val idsWhenApplied = w.serverIds()
        // Step to the neighbouring occurrence of the SAME song: same id, same playlist, wrong occurrence.
        val idx0 = w.r.player.currentMediaItemIndex
        w.r.player.seekToPreviousMediaItem()
        TestMediaSource.awaitCondition { w.r.player.currentMediaItemIndex == idx0 - 1 }
        assertEquals(idsWhenApplied, w.serverIds())
        assertEquals("the song id alone cannot tell the occurrences apart", "77777", w.physicalCurrentId())
        w.ctl.release()
        TestMediaSource.awaitCondition { w.outcomes.any { it is BoundedInstallOutcome.Incomplete } }
        w.idle()
        assertNotNull(w.adapter.active)
        assertEquals(150, w.adapter.active!!.logicalCurrent)
    }

    // ── routine adjustments are silent ──────────────────────────────────────────────────────────────────────────────────

    @Test fun aRoutineAdjustmentCausesNoTransitionNotificationsOrNaturalTransitionCount() {
        val w = W(distinct(25_000))
        w.install(100)
        w.navigate(150)
        val auto0 = PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions
        val server = RealMedia3QueueRig.Callbacks().also { w.r.player.addListener(it) }
        val controller = RealMedia3QueueRig.Callbacks().also { w.r.controller.addListener(it) }
        w.quiet()
        val named = { events: List<String> -> events.filter { it.startsWith("transition(") }.filterNot { it.startsWith("transition(151,") } }
        assertEquals("server: ${server.events}", emptyList<String>(), named(server.events))
        assertEquals("controller: ${controller.events}", emptyList<String>(), named(controller.events))
        assertEquals(auto0, PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions)
        assertEquals(150, w.adapter.active!!.logicalCurrent)
    }
}
