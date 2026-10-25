package com.launchpoint.wavdrop.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-C: bounded window MOVEMENT (refill, trim, prepend, re-anchor), exact-occurrence navigation and current-occurrence
 * resolution, on REAL Media3 (ExoPlayer behind a MediaSession, driven through a MediaController) with the real physical
 * acknowledgement. The adapter is exercised directly; the rollout gate stays false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalWindowMovementTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()

    @After fun tearDown() { rigs.forEach { it.releaseAll() }; rigs.clear() }

    private fun distinct(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }

    private fun rig(queue: List<Song>): RealMedia3QueueRig =
        RealMedia3QueueRig(queue, startIndex = 0, loadInitialQueue = false, physicalAck = true).also { rigs += it }

    private class H(val r: RealMedia3QueueRig, val queue: List<Song>, var repeat: RepeatMode = RepeatMode.OFF) {
        var generation = 7L
        var materialized = 0
        val outcomes = mutableListOf<BoundedInstallOutcome>()
        val adapter = PhysicalWindowAdapter(
            currentLogicalGeneration = { generation },
            materialize = { songs -> materialized += songs.size; songs.map(Song::toPlaybackMediaItem) },
        )
        val verifier = PhysicalQueueObservationCommand.verifier(r.controller)

        fun install(start: Int, positionMs: Long = 0L) {
            adapter.install(r.controller, queue, generation, start, positionMs, repeat, verifier) { outcomes += it }
            awaitIdle()
        }

        /** Pumps the looper until the adapter has no operation in flight and the chain of maintenance has quiesced. */
        fun awaitIdle() {
            TestMediaSource.awaitCondition { !adapter.isBusy }
        }

        fun maintainUntilQuiet(maxRounds: Int = 50): Int {
            var rounds = 0
            while (rounds < maxRounds) {
                when (val m = adapter.maintain(r.controller, queue, repeat, verifier) { outcomes += it }) {
                    is MaintenanceResult.NoChange -> return rounds
                    is MaintenanceResult.Submitted -> { rounds++; awaitIdle() }
                    is MaintenanceResult.Busy -> awaitIdle()
                    else -> error("maintenance: $m")
                }
            }
            error("maintenance did not quiesce in $maxRounds rounds")
        }

        fun navigate(target: Int, positionMs: Long = 0L): BoundedNavigationResult {
            val result = adapter.navigateTo(r.controller, queue, target, positionMs, repeat, verifier) { outcomes += it }
            awaitIdle()
            return result
        }

        fun physicalIds(): List<String> = r.serverIds()
        fun logicalIds(): List<Long> = physicalIds().map { it.toLong() }
        fun currentLogical(): Int? = adapter.currentLogical(r.controller)
    }

    private fun h(queue: List<Song>, repeat: RepeatMode = RepeatMode.OFF) = H(rig(queue), queue, repeat)

    private fun assertWindowMatchesLogical(h: H) {
        val a = h.adapter.active!!
        assertEquals((0 until a.installedCount).map { h.queue[a.physicalToLogical(it)!!].id }, h.logicalIds())
        assertTrue("window bound", a.installedCount <= PhysicalWindowPolicy.W_MAX)
        assertEquals(a.installedCount, h.r.player.mediaItemCount)
    }

    // ── A. navigation ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun jumpInsideTheWindowIsAPlainSeekAtTheMappedIndex() {
        val q = distinct(25_000); val h = h(q)
        h.install(12_500)
        val before = h.r.recording.calls.size
        val result = h.navigate(12_510)
        assertTrue(result is BoundedNavigationResult.SeekIssued)
        assertEquals(30, (result as BoundedNavigationResult.SeekIssued).physicalIndex) // window start 12480
        assertEquals(12_510, h.currentLogical())
        assertEquals(listOf("seekTo(30,0)"), h.r.recording.calls.drop(before).filter { it.startsWith("seek") })
        assertEquals(0, h.r.recording.calls.drop(before).count { it.startsWith("setMediaItems") })
    }

    @Test fun jumpOutsideTheWindowReanchorsWithABoundedInstallOnTheExactTarget() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        val before = h.r.recording.calls.size
        val result = h.navigate(20_000)
        assertTrue(result is BoundedNavigationResult.Reanchoring)
        assertEquals(20_000, h.adapter.active!!.logicalCurrent)
        assertEquals(q[20_000].id.toString(), h.r.snapshot().mediaId)
        assertEquals(listOf("setMediaItems(100)"), h.r.recording.calls.drop(before).filter { it.startsWith("setMediaItems") })
        assertWindowMatchesLogical(h)
    }

    @Test fun firstAndLastSongAreExactTargets() {
        val q = distinct(25_000); val h = h(q)
        h.install(12_000)
        h.navigate(0)
        assertEquals(0, h.currentLogical()); assertEquals("1", h.r.snapshot().mediaId)
        h.navigate(24_999)
        assertEquals(24_999, h.currentLogical()); assertEquals("25000", h.r.snapshot().mediaId)
        assertTrue(h.adapter.active!!.window.endsAtLogicalEnd)
    }

    @Test fun duplicateSongIdsStayDistinctOccurrencesWhenNavigating() {
        val q = List(2_000) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }
        val h = h(q)
        h.install(1_000)
        // Same song id (1) at 1000, 1002, 1004: navigate to the exact positions in the window.
        for (target in intArrayOf(1_002, 1_004, 1_000, 996)) {
            val r = h.navigate(target)
            assertTrue(r is BoundedNavigationResult.SeekIssued)
            assertEquals("target $target", target, h.currentLogical())
            assertEquals(h.adapter.active!!.logicalToPhysical(target), h.r.controller.currentMediaItemIndex)
        }
    }

    @Test fun shuffledOrderNavigatesByPlaybackPosition() {
        val q = distinct(25_000).shuffled(java.util.Random(7)); val h = h(q)
        h.install(5_000)
        h.navigate(5_003)
        assertEquals(5_003, h.currentLogical()); assertEquals(q[5_003].id.toString(), h.r.snapshot().mediaId)
        h.navigate(19_999)
        assertEquals(19_999, h.currentLogical()); assertEquals(q[19_999].id.toString(), h.r.snapshot().mediaId)
    }

    @Test fun latestOfSeveralRapidJumpsWins() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.adapter.navigateTo(h.r.controller, q, 5_000, 0L, h.repeat, h.verifier) { h.outcomes += it }
        h.adapter.navigateTo(h.r.controller, q, 9_000, 0L, h.repeat, h.verifier) { h.outcomes += it }
        h.adapter.navigateTo(h.r.controller, q, 15_000, 0L, h.repeat, h.verifier) { h.outcomes += it }
        h.awaitIdle()
        assertEquals(15_000, h.adapter.active!!.logicalCurrent)
        assertEquals(q[15_000].id.toString(), h.r.snapshot().mediaId)
        assertEquals(1, h.outcomes.count { it is BoundedInstallOutcome.Active && it.installed.logicalCurrent == 15_000 })
        assertEquals(0, h.outcomes.count { it is BoundedInstallOutcome.Active && it.installed.logicalCurrent in setOf(5_000, 9_000) })
        assertWindowMatchesLogical(h)
    }

    @Test fun navigationWithoutAVerifiedWindowReanchorsOnTheExactTarget() {
        val q = distinct(25_000); val h = h(q)
        // No window at all (Idle): the exact target is known, so the answer is a bounded install around it.
        assertTrue(h.navigate(777) is BoundedNavigationResult.Reanchoring)
        assertEquals(777, h.adapter.active!!.logicalCurrent)
        // Provisional (unacknowledged) mapping must not authorize a seek: with a stub verifier that never answers.
        val r2 = rig(q); val h2 = H(r2, q)
        val silent = PhysicalInstallVerifier { SettableFuture.create<PhysicalObservation>() }
        h2.adapter.install(r2.controller, q, h2.generation, 100, 0L, RepeatMode.OFF, silent)
        TestMediaSource.awaitCondition { h2.adapter.state is WindowInstallState.Provisional }
        assertNull(h2.adapter.active)
        val before = r2.recording.calls.size
        val result = h2.adapter.navigateTo(r2.controller, q, 105, 0L, RepeatMode.OFF, silent)
        assertTrue("provisional mapping must not seek: $result", result is BoundedNavigationResult.Reanchoring)
        assertEquals(0, r2.recording.calls.drop(before).count { it.startsWith("seekTo") })
    }

    @Test fun navigationTargetOutsideTheQueueIsRejectedWithoutTouchingThePlayer() {
        val q = distinct(1_000); val h = h(q)
        h.install(500)
        val before = h.r.recording.calls.size
        assertEquals(BoundedNavigationResult.Rejected("target_out_of_range"), h.navigate(-1))
        assertEquals(BoundedNavigationResult.Rejected("target_out_of_range"), h.navigate(1_000))
        assertEquals(before, h.r.recording.calls.size)
        assertEquals(500, h.currentLogical())
    }

    @Test fun repeatAllPreviousFromLogicalZeroReanchorsAtTheLastSongThroughTheRing() {
        val q = distinct(25_000); val h = h(q, RepeatMode.ALL)
        h.install(0)
        val prev = QueueNavigator.previousAction(25_000, 0, 0L, RepeatMode.ALL, 3_000L)
        assertEquals(PreviousQueueAction.MoveTo(24_999), prev)
        assertTrue(h.navigate(24_999) is BoundedNavigationResult.Reanchoring)
        val a = h.adapter.active!!
        assertEquals(24_999, a.logicalCurrent)
        assertTrue("ring window wraps past the end", a.window.wraps)
        assertEquals(0, a.physicalToLogical(a.physicalStart + 1)) // next after the last song is logical 0
    }

    // ── B. window movement ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun forwardWalkAcrossMoreThanOneWindowKeepsTheCurrentExactAndTheWindowBounded() {
        val q = distinct(25_000); val h = h(q)
        h.install(0)
        var maxPhysical = 0
        var refills = 0
        for (step in 1..260) {
            val target = step
            val result = h.navigate(target)
            assertTrue("step $target: $result", result is BoundedNavigationResult.SeekIssued || result is BoundedNavigationResult.Reanchoring)
            refills += h.maintainUntilQuiet()
            assertEquals("step $target", target, h.currentLogical())
            assertEquals(q[target].id.toString(), h.r.snapshot().mediaId)
            maxPhysical = maxOf(maxPhysical, h.r.player.mediaItemCount)
        }
        assertTrue("never beyond W_MAX (saw $maxPhysical)", maxPhysical <= PhysicalWindowPolicy.W_MAX)
        assertTrue("moved through more than one window (refills=$refills)", refills >= 2)
        assertWindowMatchesLogical(h)
        // History is trimmed: the window start has moved well past 0.
        assertTrue(h.adapter.active!!.window.start > 100)
    }

    @Test fun backwardWalkRecoversHistoryWithoutExceedingTheBound() {
        val q = distinct(25_000); val h = h(q)
        h.install(12_500)
        for (step in 1..120) {
            val target = 12_500 - step
            h.navigate(target)
            h.maintainUntilQuiet()
            assertEquals(target, h.currentLogical())
            assertTrue(h.r.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        }
        assertWindowMatchesLogical(h)
        assertTrue("history was prepended", h.adapter.active!!.window.start < 12_480)
    }

    @Test fun aheadRefillRunsAtTheLowWatermarkInBoundedBatches() {
        val q = distinct(25_000); val h = h(q)
        h.install(0) // window [0..79], current at 0
        h.navigate(60) // ahead = 19 <= AHEAD_LOW(30)
        h.maintainUntilQuiet()
        val adds = h.r.recording.calls.filter { it.startsWith("append(") }
        assertTrue("refilled: ${h.r.recording.calls}", adds.isNotEmpty())
        assertTrue(adds.all { Regex("append\\((\\d+)\\)").find(it)!!.groupValues[1].toInt() <= PhysicalWindowPolicy.REFILL_BATCH })
        assertTrue(h.adapter.active!!.installedCount <= PhysicalWindowPolicy.W_MAX)
        assertTrue(h.adapter.active!!.window.aheadCount(h.adapter.active!!.logicalToPhysical(60)!!)!! > PhysicalWindowPolicy.AHEAD_LOW)
    }

    @Test fun historyAboveTheMaximumIsTrimmedWithoutTouchingTheCurrentItem() {
        val q = distinct(25_000); val h = h(q)
        h.install(100) // window [80..179]
        h.navigate(150) // history = 70 > HISTORY_MAX(40)
        val currentBefore = h.r.snapshot().mediaId
        h.maintainUntilQuiet()
        assertEquals(currentBefore, h.r.snapshot().mediaId)
        assertEquals(150, h.currentLogical())
        assertTrue(h.r.recording.calls.any { it.startsWith("remove(0,") })
        val a = h.adapter.active!!
        assertTrue(a.window.historyCount(a.logicalToPhysical(150)!!)!! <= PhysicalWindowPolicy.HISTORY_MAX)
        assertWindowMatchesLogical(h)
    }

    @Test fun movementCausesNoSpuriousMediaItemTransitions() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.navigate(150)
        val server = RealMedia3QueueRig.Callbacks().also { h.r.player.addListener(it) }
        h.maintainUntilQuiet()
        TestMediaSource.awaitCondition { !h.adapter.isBusy }
        val transitions = server.events.filter { it.startsWith("transition(") }
        assertTrue("only the unchanged current item may be named: $transitions", transitions.all { it.startsWith("transition(151,") })
        assertFalse(server.events.any { it.startsWith("discontinuity(") && !it.contains("->") })
    }

    @Test fun repeatAllRingWindowKeepsGrowingPastTheLogicalEnd() {
        val q = distinct(25_000); val h = h(q, RepeatMode.ALL)
        h.install(24_950)
        h.maintainUntilQuiet()
        val a = h.adapter.active!!
        assertTrue(a.window.wraps)
        assertEquals(24_950, h.currentLogical())
        assertTrue(a.installedCount <= PhysicalWindowPolicy.W_MAX)
        h.navigate(24_999)
        h.maintainUntilQuiet()
        assertEquals(0, h.adapter.active!!.physicalToLogical(h.adapter.active!!.logicalToPhysical(24_999)!! + 1))
    }

    // ── C. current occurrence and automatic transitions ─────────────────────────────────────────────────────────────────

    @Test fun nativeAutoTransitionInsideTheWindowIsTranslatedWithoutAnyQueueOperation() {
        val q = distinct(25_000); val h = h(q)
        h.install(500)
        val before = h.r.recording.calls.size
        h.r.player.seekToNextMediaItem() // what Media3 does at a natural boundary, on the physical player
        TestMediaSource.awaitCondition { h.r.controller.currentMediaItemIndex == h.adapter.active!!.logicalToPhysical(501) }
        assertEquals(501, h.currentLogical())
        assertEquals(q[501].id.toString(), h.r.snapshot().mediaId)
        assertEquals(0, h.r.recording.calls.drop(before).count { it.startsWith("setMediaItems") })
    }

    @Test fun mismatchingPhysicalIdMakesTheOccurrenceUnprovenInsteadOfSearchingForAnother() {
        val q = distinct(25_000); val h = h(q)
        h.install(500)
        // Replace the physical current with another song while the adapter still believes in the window.
        val phys = h.r.controller.currentMediaItemIndex
        h.r.player.replaceMediaItem(phys, RealMedia3QueueRig.song(99_999L).toPlaybackMediaItem())
        TestMediaSource.awaitCondition { h.r.controller.currentMediaItem?.mediaId == "99999" }
        assertTrue(h.adapter.resolveCurrent(h.r.controller) is CurrentResolution.Unproven)
        assertNull(h.currentLogical())
    }

    @Test fun repeatOffEndsOnlyAtTheGenuineLogicalEnd() {
        val q = distinct(25_000); val h = h(q)
        h.install(24_998)
        h.maintainUntilQuiet()
        val a = h.adapter.active!!
        assertTrue(a.window.endsAtLogicalEnd)
        assertNull(a.physicalToLogical(a.installedCount)) // nothing follows the last logical item
        assertEquals(null, QueueNavigator.automaticNextIndex(25_000, 24_999, RepeatMode.OFF))
    }

    // ── D. authority and lifecycle ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun olderAdjustmentIsInvalidatedByALogicalGenerationChange() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.navigate(150)
        val stub = StubAckVerifier()
        h.adapter.maintain(h.r.controller, q, h.repeat, stub) { h.outcomes += it }
        assertTrue(h.adapter.isBusy)
        h.generation += 1
        val (token, ack) = stub.requests.single()
        ack.set(PhysicalObservation.observe(h.r.player, token))
        TestMediaSource.awaitCondition { !h.adapter.isBusy }
        assertNull(h.adapter.active)
        assertEquals(StaleReason.LogicalGenerationMoved, (h.outcomes.last() as BoundedInstallOutcome.Stale).reason)
    }

    @Test fun olderAdjustmentIsInvalidatedByANewerInstallAndItsAckCannotActivate() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.navigate(150)
        val stub = StubAckVerifier()
        h.adapter.maintain(h.r.controller, q, h.repeat, stub) { h.outcomes += it }
        val (oldToken, oldAck) = stub.requests.single()
        h.navigate(20_000) // out of window: newer install supersedes the pending adjustment
        assertEquals(20_000, h.adapter.active!!.logicalCurrent)
        val activeToken = h.adapter.active!!.token
        oldAck.set(PhysicalObservation.observe(h.r.player, oldToken))
        TestMediaSource.awaitCondition { true }
        idleMainLooper()
        assertEquals("a late ack of the older window changes nothing", activeToken, h.adapter.active!!.token)
        assertTrue(h.outcomes.any { it is BoundedInstallOutcome.Stale && it.reason == StaleReason.Superseded })
    }

    @Test fun adjustmentIsOnlyPublishedAfterThePhysicalAcknowledgement() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.navigate(150) // history 70 -> trimFront (indices shift)
        val old = h.adapter.active!!
        val stub = StubAckVerifier()
        assertTrue(h.adapter.maintain(h.r.controller, q, h.repeat, stub) { h.outcomes += it } is MaintenanceResult.Submitted)
        val (baselineToken, baselineAck) = stub.requests[0]
        baselineAck.set(PhysicalObservation.observe(h.r.player, baselineToken)) // the physical baseline, answered for real
        TestMediaSource.awaitCondition { stub.requests.size == 2 }
        TestMediaSource.awaitCondition { h.r.player.mediaItemCount != old.installedCount || h.r.player.getMediaItemAt(0).mediaId != old.expectedSongIdAt(0).toString() }
        assertTrue(h.adapter.state is WindowInstallState.Adjusting)
        assertNull("shifted indices have no mapping before the ack", h.adapter.active)
        assertNull(h.adapter.logicalToPhysical(150))
        // (An index-bearing command here would re-anchor on its exact target; see the navigation tests.)
        val (token, ack) = stub.requests[1]
        ack.set(PhysicalObservation.observe(h.r.player, token))
        TestMediaSource.awaitCondition { !h.adapter.isBusy }
        assertNotNull(h.adapter.active)
        assertEquals(150, h.adapter.active!!.logicalCurrent)
        assertEquals(1, h.outcomes.count { it is BoundedInstallOutcome.Active && it.installed.token == token })
    }

    @Test fun failedAdjustmentAckRecoversOnceWithABoundedReanchorAndNeverTheFullQueue() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.navigate(150)
        // Request 0 is the physical baseline (answered for real); request 1 is the adjustment's acknowledgement and fails; every later one (the recovery re-anchor) is answered for real.
        val failing = SettableFuture.create<PhysicalObservation>()
        var asked = 0
        val flaky = PhysicalInstallVerifier { token -> if (asked++ == 1) failing else h.verifier.observe(token) }
        h.adapter.maintain(h.r.controller, q, h.repeat, flaky) { h.outcomes += it }
        val callsBefore = h.r.recording.calls.size
        failing.setException(IllegalStateException("session refused"))
        TestMediaSource.awaitCondition { h.outcomes.any { it is BoundedInstallOutcome.Active && it.installed.token.windowGeneration > 1L } }
        h.awaitIdle()
        assertTrue(h.outcomes.any { it is BoundedInstallOutcome.Failed })
        val installs = h.r.recording.calls.drop(callsBefore).filter { it.startsWith("setMediaItems") }
        assertEquals("exactly one bounded recovery install", listOf("setMediaItems(100)"), installs)
        assertEquals(150, h.adapter.active!!.logicalCurrent) // recovered around the last proven occurrence
    }

    @Test fun resetBeforeAcknowledgementDiscardsTheAdjustment() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.navigate(150)
        val stub = StubAckVerifier()
        h.adapter.maintain(h.r.controller, q, h.repeat, stub) { h.outcomes += it }
        h.adapter.reset()
        val (token, ack) = stub.requests.single()
        ack.set(PhysicalObservation.observe(h.r.player, token))
        idleMainLooper()
        assertNull(h.adapter.active)
        assertEquals(WindowInstallState.Idle, h.adapter.state)
    }

    @Test fun replacedPlayerDropsTheMappingAndOwesABoundedReinstall() {
        val q = distinct(25_000); val h = h(q)
        h.install(500)
        h.adapter.onPlayerReplaced()
        assertNull(h.adapter.active)
        val state = h.adapter.state as WindowInstallState.NeedsReinstall
        assertEquals(500, state.recovery.logicalStart)
        val before = h.r.recording.calls.size
        assertTrue(h.navigate(500) is BoundedNavigationResult.Reanchoring)
        assertEquals(500, h.adapter.active!!.logicalCurrent)
        assertEquals(1, h.r.recording.calls.drop(before).count { it.startsWith("setMediaItems") })
    }

    @Test fun controllerOptimisticStateNeverBecomesTheMappingWhenThePhysicalPlayerDropsAnAdjustment() {
        val q = distinct(25_000); val h = h(q)
        h.install(100)
        h.navigate(150)
        // The physical player ignores removals (the controller still masks them optimistically).
        val dropping = object : ForwardingPlayer(h.r.controller) {
            override fun removeMediaItems(fromIndex: Int, toIndex: Int) = Unit
        }
        val result = h.adapter.maintain(dropping, q, h.repeat, h.verifier) { h.outcomes += it }
        assertTrue(result is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { !h.adapter.isBusy }
        // The probe shows the unchanged physical window, so nothing wrong was published (the recovery re-anchor then ran).
        assertTrue(h.outcomes.any { it is BoundedInstallOutcome.Incomplete })
        assertWindowMatchesLogical(h)
    }

    @Test fun boundedStructureNeverMaterializesOrInstallsTheFullQueue() {
        val q = distinct(25_000); val h = h(q)
        h.install(12_000)
        h.navigate(12_040); h.maintainUntilQuiet()
        h.navigate(1); h.maintainUntilQuiet()
        val sizes = h.r.recording.calls.mapNotNull { Regex("(?:setMediaItems|append|add)\\(?(?:\\d+,)?(\\d+)\\)").find(it)?.groupValues?.get(1)?.toInt() }
        assertTrue("every list operation is bounded: ${h.r.recording.calls}", sizes.all { it <= PhysicalWindowPolicy.W_MAX })
        assertTrue("materialized ${h.materialized} of 25000", h.materialized < 1_000)
        assertEquals(25_000, q.size)
    }

    // ── E. evidence ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun largeQueueEvidence() {
        println("P5-C evidence: logical | physical | materialized(install) | ops(install) | refill batches | reanchor items | nav accuracy | ms")
        for (n in intArrayOf(1_000, 5_000, 10_000, 25_000, 50_000)) {
            val q = distinct(n); val h = h(q)
            val t0 = System.nanoTime()
            h.install(n / 2)
            val installMaterialized = h.materialized
            val installOps = h.r.recording.calls.filter { it.startsWith("setMediaItems") }
            var ok = 0; var total = 0
            var batches = 0
            for (step in 1..60) {
                val target = n / 2 + step
                h.navigate(target); batches += h.maintainUntilQuiet()
                total++; if (h.currentLogical() == target && h.r.snapshot().mediaId == q[target].id.toString()) ok++
            }
            val far = h.navigate(if (n / 2 > n / 4) n / 4 else 3)
            total++; if (far is BoundedNavigationResult.Reanchoring && h.currentLogical() == h.adapter.active!!.logicalCurrent) ok++
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            println("P5-C evidence: $n | ${h.r.player.mediaItemCount} | $installMaterialized | $installOps | $batches | ${h.adapter.active!!.installedCount} | $ok/$total | %.1f".format(ms))
            assertEquals("navigation accuracy at $n", total, ok)
            assertTrue(h.r.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
            h.r.releaseAll()
        }
    }

    private class StubAckVerifier : PhysicalInstallVerifier {
        val requests = mutableListOf<Pair<PlanToken, SettableFuture<PhysicalObservation>>>()
        override fun observe(token: PlanToken): ListenableFuture<PhysicalObservation> =
            SettableFuture.create<PhysicalObservation>().also { requests += token to it }
    }
}
