package com.launchpoint.wavdrop.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-D ordering and authority at the adapter level, on REAL Media3 with the real physical probe: a mutation that arrives while a
 * refill or a re-anchor is in flight, held navigation, a physical operation that fails, a stale acknowledgement, a natural
 * transition during a mutation, an offline edit. The test owns the logical queue and the generation exactly as PlayerController does
 * (bump, then hand the adapter the new queue and the exact old->new occurrence map). Acknowledgement ordering is controlled by the
 * test, never by sleeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalWindowMutationLifecycleTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()

    @After fun tearDown() { rigs.forEach { it.releaseAll() }; rigs.clear() }

    private fun distinct(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }

    /** Answers immediately until [hold] is armed; then HOLDS the next [skip]-th and later requests until [release]. */
    private class Controlled(val r: RealMedia3QueueRig) : PhysicalInstallVerifier {
        var holding = false
        var skip = 0
        var live = false
        val held = mutableListOf<Pair<PlanToken, SettableFuture<PhysicalObservation>>>()

        override fun observe(token: PlanToken): ListenableFuture<PhysicalObservation> {
            if (holding && !live) {
                if (skip > 0) { skip--; return Futures.immediateFuture(PhysicalObservation.observe(r.player, token)) }
                return SettableFuture.create<PhysicalObservation>().also { held += token to it }
            }
            return Futures.immediateFuture(PhysicalObservation.observe(r.player, token))
        }

        fun hold(answerFirst: Int = 0) { holding = true; skip = answerFirst; live = false }
        fun release() {
            live = true
            held.forEach { (token, future) -> future.set(PhysicalObservation.observe(r.player, token)) }
            held.clear()
        }
    }

    private inner class W(var q: List<Song>, policy: PhysicalWindowPolicy = PhysicalWindowPolicy.DEFAULT) {
        val r = RealMedia3QueueRig(q, 0, loadInitialQueue = false, physicalAck = true).also { rigs += it }
        var generation = 3L
        var repeat = RepeatMode.OFF
        val outcomes = mutableListOf<BoundedInstallOutcome>()
        val adapter = PhysicalWindowAdapter(policy = policy, currentLogicalGeneration = { generation })
        val ctl = Controlled(r)

        fun idle() = TestMediaSource.awaitCondition { !adapter.isBusy }
        fun install(start: Int) { adapter.install(r.port, q, generation, start, 0L, repeat, ctl) { outcomes += it }; idle() }
        fun navigate(target: Int) = adapter.navigateTo(r.port, q, target, 0L, repeat, ctl) { outcomes += it }
        fun quiet() { repeat(30) { when (adapter.maintain(r.port, q, repeat, ctl) { outcomes += it }) { is MaintenanceResult.NoChange -> return; else -> idle() } }; error("no quiesce") }

        /** What PlayerController does: the logical queue and generation are final BEFORE the adapter hears about it. */
        fun mutate(
            newQueue: List<Song>, oldCurrent: Int?, map: (Int) -> Int?, continuation: Int? = null,
            player: PhysicalWindowPort? = r.port, verifier: PhysicalInstallVerifier? = ctl,
        ): MutationSubmission {
            generation++
            q = newQueue
            return adapter.applyMutation(QueueMutationRequest(player, newQueue, repeat, oldCurrent, map, continuation, verifier) { outcomes += it })
        }

        fun serverIds() = r.serverIds()
        fun naturalAdvance() {
            r.player.seekToNextMediaItem()
            PhysicalObservation.naturalTransitionListener(r.player).onMediaItemTransition(r.player.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        }

        fun assertConsistent(expectedLogicalCurrent: Int, label: String) {
            val a = adapter.active
            assertNotNull("$label: active (state=${adapter.state}, outcomes=${outcomes.takeLast(3)})", a)
            a!!
            assertEquals("$label: ids", (0 until a.installedCount).map { q[a.physicalToLogical(it)!!].id.toString() }, serverIds())
            val live = TestMediaSource.awaitCondition { adapter.resolveCurrent(r.port) is CurrentResolution.Proven }.let { adapter.resolveCurrent(r.port) as CurrentResolution.Proven }
            assertEquals("$label: logical current (proven live through the active window)", expectedLogicalCurrent, live.logical)
            assertEquals("$label: physical current song", q[expectedLogicalCurrent].id.toString(), r.player.currentMediaItem?.mediaId)
            assertEquals("$label: generation", generation, a.window.logicalGeneration)
            assertTrue("$label: bound", r.player.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        }
    }

    private fun insertAt(q: List<Song>, at: Int, vararg songs: Song) = q.toMutableList().also { it.addAll(at, songs.toList()) }
    private fun s(id: Long) = RealMedia3QueueRig.song(id)

    // ── mutation vs in-flight work ──────────────────────────────────────────────────────────────────────────────────────

    @Test fun aMutationDuringAnInFlightRefillStartsFromWhicheverWindowThePhysicalPlayerHolds() {
        val w = W(distinct(25_000))
        w.install(100)
        w.navigate(150); w.idle()
        w.ctl.hold(answerFirst = 1)                    // refill: baseline answered, its acknowledgement HELD
        assertTrue(w.adapter.maintain(w.r.port, w.q, w.repeat, w.ctl) { w.outcomes += it } is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        TestMediaSource.awaitCondition { w.r.player.getMediaItemAt(0).mediaId != "81" } // the trim reached the physical player
        w.ctl.live = true
        // The edit arrives now: after the current (150) one song is inserted.
        val newQ = insertAt(w.q, 151, s(90_001L))
        assertTrue(w.mutate(newQ, 150, OccurrenceMap.insert(151, 1)) is MutationSubmission.Submitted)
        w.ctl.release(); w.idle()
        w.assertConsistent(150, "mutation during refill")
        assertEquals("90001", w.r.player.getMediaItemAt(w.r.player.currentMediaItemIndex + 1).mediaId)
        assertTrue("the superseded refill was reported stale, never published", w.outcomes.any { it is BoundedInstallOutcome.Stale })
    }

    @Test fun aMutationDuringAReanchorSupersedesItAndReanchorsOnTheRebasedTarget() {
        val w = W(distinct(25_000))
        w.install(100)
        w.ctl.hold()                                   // the re-anchor's acknowledgement is HELD
        assertTrue(w.navigate(20_000) is BoundedNavigationResult.Reanchoring)
        val newQ = insertAt(w.q, 5, s(90_002L))        // an earlier insertion shifts the target by one
        w.ctl.live = true
        assertTrue(w.mutate(newQ, 20_000, OccurrenceMap.insert(5, 1)) is MutationSubmission.Submitted)
        w.ctl.release(); w.idle()
        w.assertConsistent(20_001, "mutation during re-anchor")
        assertEquals("20001", w.r.player.currentMediaItem?.mediaId) // the targeted occurrence (song 20001), now one position later
    }

    @Test fun anInWindowNavigationHeldDuringARefillFollowsItsOccurrenceThroughAMutation() {
        // An append-only refill keeps the previous mapping valid, so an in-window navigation is HELD (latest wins) until it settles.
        val w = W(distinct(25_000), PhysicalWindowPolicy(PhysicalWindowPolicy.Config(historyMax = 60)))
        w.install(0)                                   // window [0..79]
        w.navigate(50); w.idle()
        w.ctl.hold(answerFirst = 1)
        assertTrue(w.adapter.maintain(w.r.port, w.q, w.repeat, w.ctl) { w.outcomes += it } is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        assertTrue("held while the refill is verified", w.navigate(55) is BoundedNavigationResult.Held)
        val newQ = insertAt(w.q, 52, s(90_003L))       // an insertion before the held target shifts it
        w.ctl.live = true
        w.mutate(newQ, 55, OccurrenceMap.insert(52, 1))
        w.ctl.release(); w.idle()
        w.assertConsistent(56, "held navigation rebased")
        assertEquals("the held occurrence (song 56) now sits at logical 56", "56", w.r.player.currentMediaItem?.mediaId)
    }

    @Test fun anAcknowledgementOfASupersededMutationNeverActivatesAnything() {
        val w = W(distinct(25_000))
        w.install(500)
        w.ctl.hold()                                   // the first mutation's baseline is HELD
        val q1 = insertAt(w.q, 501, s(90_004L))
        w.mutate(q1, 500, OccurrenceMap.insert(501, 1))
        val staleToken = w.ctl.held.single().first
        val staleFuture = w.ctl.held.single().second
        w.ctl.live = true
        val q2 = insertAt(w.q, 502, s(90_005L))
        w.mutate(q2, 500, OccurrenceMap.insert(502, 1)) // the composed map starts from the ORIGINAL window's coordinates
        w.idle()
        w.assertConsistent(500, "second mutation")
        val ids = w.serverIds()
        assertEquals("both edits are applied, in order", ids.indexOf("90004") + 1, ids.indexOf("90005"))
        val activeToken = w.adapter.active!!.token
        staleFuture.set(PhysicalObservation.observe(w.r.player, staleToken))
        idleMainLooper()
        assertEquals("a late acknowledgement of the older mutation changes nothing", activeToken, w.adapter.active!!.token)
    }

    // ── fail closed ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun aFailedPhysicalOperationLeavesTheLogicalEditAndRecoversWithOneBoundedInstall() {
        val w = W(distinct(25_000))
        w.install(500)
        val dropping = object : ForwardingPlayer(w.r.recording) {
            override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) = Unit
            override fun removeMediaItems(fromIndex: Int, toIndex: Int) = Unit
        }
        val before = w.r.recording.calls.size
        val newQ = insertAt(w.q, 501, s(90_006L))
        w.mutate(newQ, 500, OccurrenceMap.insert(501, 1), player = PhysicalPlayerPort(dropping))
        TestMediaSource.awaitCondition { w.outcomes.any { it is BoundedInstallOutcome.Incomplete } }
        w.idle()
        assertEquals("the accepted logical edit stands", 25_001, w.q.size)
        val installs = w.r.recording.calls.drop(before).filter { it.startsWith("setMediaItems") }
        assertEquals("exactly one bounded recovery install", listOf("setMediaItems(100)"), installs)
        w.assertConsistent(500, "after recovery")
        assertEquals("90006", w.r.player.getMediaItemAt(w.r.player.currentMediaItemIndex + 1).mediaId)
    }

    @Test fun aNaturalTransitionDuringAMutationIsAcceptedAtTheExactSuccessorInTheNewQueue() {
        val w = W(distinct(25_000))
        w.install(500)
        w.ctl.hold(answerFirst = 1)                    // baseline answered, the edit's acknowledgement HELD
        val newQ = insertAt(w.q, 501, s(90_007L))      // the inserted song becomes the new successor
        w.mutate(newQ, 500, OccurrenceMap.insert(501, 1))
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        TestMediaSource.awaitCondition { w.r.player.mediaItemCount > 0 && w.serverIds().contains("90007") }
        w.naturalAdvance()                             // the physical player moves to the inserted song and counts it as natural
        w.ctl.release(); w.idle()
        w.assertConsistent(501, "natural transition during mutation")
        assertEquals("90007", w.r.player.currentMediaItem?.mediaId)
    }

    @Test fun anUncountedMoveDuringAMutationFailsClosed() {
        val w = W(distinct(25_000))
        w.install(500)
        w.ctl.hold(answerFirst = 1)
        val newQ = insertAt(w.q, 501, s(90_008L))
        w.mutate(newQ, 500, OccurrenceMap.insert(501, 1))
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        TestMediaSource.awaitCondition { w.serverIds().contains("90008") }
        w.r.player.seekToNextMediaItem()               // an advance nobody counted as natural
        w.ctl.release()
        TestMediaSource.awaitCondition { w.outcomes.any { it is BoundedInstallOutcome.Incomplete } }
        w.idle()
        assertTrue("never published the moved window", w.outcomes.none { it is BoundedInstallOutcome.Active && it.installed.logicalCurrent == 501 })
        w.assertConsistent(500, "recovered around the last authoritative occurrence")
    }

    // ── logical-only rebasing, offline, silence ─────────────────────────────────────────────────────────────────────────

    @Test fun aLogicalOnlyEditRebasesTheWindowOntoTheNewGenerationWithoutAnyPhysicalOperation() {
        val w = W(distinct(25_000))
        w.install(12_000)
        val old = w.adapter.active!!
        val before = w.r.recording.calls.size
        val newQ = w.q + List(500) { s(600_000L + it) }
        w.mutate(newQ, 12_000, OccurrenceMap.identity()); w.idle()
        w.assertConsistent(12_000, "logical only")
        assertTrue(w.adapter.active!!.window.windowGeneration > old.window.windowGeneration)
        assertTrue(w.adapter.active!!.window.logicalGeneration > old.window.logicalGeneration)
        assertEquals(emptyList<String>(), w.r.recording.calls.drop(before).filter { it.startsWith("add") || it.startsWith("append") || it.startsWith("remove") || it.startsWith("replace") || it.startsWith("setMediaItems") })
        // The ack/token of the OLD window can never be accepted for the new generation.
        assertTrue(!old.token.isValidFor(w.adapter.active!!.window, w.generation))
    }

    @Test fun anEditWithoutAPlayerDropsTheMappingAndOwesABoundedReinstallAroundTheRebasedCurrent() {
        val w = W(distinct(25_000))
        w.install(500)
        val newQ = insertAt(w.q, 100, s(90_009L))
        assertTrue(w.mutate(newQ, 500, OccurrenceMap.insert(100, 1), player = null) is MutationSubmission.Submitted)
        assertNull(w.adapter.active)
        assertEquals(501, (w.adapter.state as WindowInstallState.NeedsReinstall).recovery.logicalStart)
        assertEquals(501, w.adapter.trackedLogical)
        // Reconnect: one bounded install around the exact current of the NEW queue.
        w.adapter.onPlayerReplaced()
        val before = w.r.recording.calls.size
        assertTrue(w.adapter.navigateTo(w.r.port, w.q, 501, 0L, w.repeat, w.ctl) { w.outcomes += it } is BoundedNavigationResult.Reanchoring)
        w.idle()
        w.assertConsistent(501, "after reconnect")
        assertEquals(1, w.r.recording.calls.drop(before).count { it.startsWith("setMediaItems(") })
    }

    @Test fun aMutationWithoutAVerifierIsRejectedNotGuessed() {
        val w = W(distinct(25_000))
        w.install(500)
        val newQ = insertAt(w.q, 501, s(90_010L))
        assertTrue(w.mutate(newQ, 500, OccurrenceMap.insert(501, 1), player = w.r.unverifiablePort, verifier = null) is MutationSubmission.Rejected)
        assertNull("the old mapping must not describe the new queue", w.adapter.active)
    }

    @Test fun anEditInsideTheWindowCausesNoTransitionNotificationAndNoNaturalTransitionCount() {
        val w = W(distinct(25_000))
        w.install(500)
        val auto0 = PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions
        val server = RealMedia3QueueRig.Callbacks().also { w.r.player.addListener(it) }
        val controller = RealMedia3QueueRig.Callbacks().also { w.r.controller.addListener(it) }
        val newQ = insertAt(w.q, 505, s(90_011L), s(90_012L))
        w.mutate(newQ, 500, OccurrenceMap.insert(505, 2)); w.idle()
        w.assertConsistent(500, "silent edit")
        val named = { events: List<String> -> events.filter { it.startsWith("transition(") }.filterNot { it.startsWith("transition(501,") } }
        assertEquals("server: ${server.events}", emptyList<String>(), named(server.events))
        assertEquals("controller: ${controller.events}", emptyList<String>(), named(controller.events))
        assertEquals(auto0, PhysicalObservation.observe(w.r.player, PlanToken(0, 0)).autoTransitions)
    }

    @Test fun deletingTheCurrentOccurrenceReanchorsOnTheContinuationWithOneBoundedInstall() {
        val w = W(distinct(25_000))
        w.install(500)
        val before = w.r.recording.calls.size
        val newQ = w.q.toMutableList().also { it.removeAt(500) }
        w.mutate(newQ, 500, OccurrenceMap.remove(500), continuation = 500); w.idle()
        w.assertConsistent(500, "current removed")
        assertEquals("the successor (old 501) now plays", "502", w.r.player.currentMediaItem?.mediaId)
        assertEquals(listOf("setMediaItems(100)"), w.r.recording.calls.drop(before).filter { it.startsWith("setMediaItems(") })
    }
}
