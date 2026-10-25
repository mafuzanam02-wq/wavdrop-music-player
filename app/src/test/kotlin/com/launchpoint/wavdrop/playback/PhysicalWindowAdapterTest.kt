package com.launchpoint.wavdrop.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.launchpoint.wavdrop.data.model.Song
import java.io.File
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
 * P5-B: the bounded physical-window installer on REAL Media3 (ExoPlayer behind a MediaSession, driven through a MediaController).
 * The adapter is exercised directly; the committed rollout gate stays false and PlayerController never reaches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalWindowAdapterTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()

    @After fun tearDown() { rigs.forEach { it.releaseAll() }; rigs.clear() }

    private fun rig(queue: List<Song>): RealMedia3QueueRig =
        RealMedia3QueueRig(queue, startIndex = 0, loadInitialQueue = false, physicalAck = true).also { rigs += it }

    private fun distinct(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }

    /** Ids cycle A B A C A (1,2,1,3,1): the same song repeats at different positions. */
    private fun duplicates(n: Int): List<Song> =
        List(n) { RealMedia3QueueRig.song(longArrayOf(1, 2, 1, 3, 1)[it % 5]) }

    private class Harness(val generation: Long = 7L) {
        var currentGeneration = generation
        var materialized = 0
        val outcomes = mutableListOf<BoundedInstallOutcome>()
        val adapter = PhysicalWindowAdapter(
            currentLogicalGeneration = { currentGeneration },
            materialize = { songs -> materialized += songs.size; songs.map(Song::toPlaybackMediaItem) },
        )
    }

    private fun Harness.install(
        rig: RealMedia3QueueRig,
        queue: List<Song>,
        start: Int,
        positionMs: Long = 0L,
        repeat: RepeatMode = RepeatMode.OFF,
        player: Player = rig.controller,
        verifier: PhysicalInstallVerifier? = PhysicalQueueObservationCommand.verifier(rig.controller),
    ): BoundedInstallOutcome = adapter.install(player, queue, currentGeneration, start, positionMs, repeat, verifier) { outcomes += it }

    private fun settle(rig: RealMedia3QueueRig) = rig.settle(150)

    private fun expectedIds(queue: List<Song>, active: InstalledWindow): List<String> =
        (0 until active.installedCount).map { queue[active.physicalToLogical(it)!!].id.toString() }

    // ── core installation ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun installs25kQueueAsBoundedWindowAtStartMiddleAndEnd() {
        val queue = distinct(25_000)
        for (start in intArrayOf(0, 12_500, 24_999)) {
            val r = rig(queue)
            val h = Harness()
            val submitted = h.install(r, queue, start, positionMs = 4_321L)
            assertTrue(submitted is BoundedInstallOutcome.Submitted)
            assertNull("not authoritative until verified", h.adapter.active)
            settle(r)
            val active = h.adapter.active
            assertNotNull("start=$start outcomes=${h.outcomes}", active)
            active!!
            assertTrue(active.installedCount <= PhysicalWindowPolicy.W_MAX)
            assertEquals(25_000, queue.size)
            assertEquals(start, active.logicalCurrent)
            assertEquals(start, active.physicalToLogical(active.physicalStart))
            assertEquals(queue[start].id, active.currentSongId)
            val snap = r.snapshot()
            assertEquals(active.installedCount, snap.count)
            assertEquals(active.physicalStart, snap.index)
            assertEquals(queue[start].id.toString(), snap.mediaId)
            assertEquals(expectedIds(queue, active), snap.ids)
            assertEquals("server truth equals intent", snap.ids, r.serverIds())
            assertTrue("position preserved ${snap.positionMs}", snap.positionMs >= 4_321L - 50)
            assertEquals(active.installedCount, h.materialized)
            assertEquals(1, r.recording.count("setMediaItems"))
            assertEquals("setMediaItems(${active.installedCount})", r.recording.calls.first { it.startsWith("setMediaItems") })
            assertEquals(0, r.recording.count("add") + r.recording.count("append") + r.recording.count("replace"))
            assertEquals(BoundedInstallOutcome.Active(active), h.outcomes.single())
        }
    }

    @Test fun windowGeometryAtTheQueueEndFollowsRepeatMode() {
        val queue = distinct(25_000)
        val all = Harness().also { h -> h.install(rig(queue), queue, 24_999, repeat = RepeatMode.ALL) }
        val off = Harness().also { h -> h.install(rig(queue), queue, 24_999, repeat = RepeatMode.OFF) }
        rigs.forEach { settle(it) }
        val ring = all.adapter.active!!
        assertTrue(ring.window.wraps)
        assertEquals(PhysicalWindowPolicy.W_MAX, ring.installedCount)
        assertEquals(0, ring.physicalToLogical(ring.physicalStart + 1)) // next after the last song is logical 0
        assertEquals(24_999, ring.physicalToLogical(ring.physicalStart))
        val linear = off.adapter.active!!
        assertFalse(linear.window.wraps)
        assertTrue(linear.window.endsAtLogicalEnd)
        assertEquals(21, linear.installedCount)
        assertNull(linear.physicalToLogical(linear.physicalStart + 1))
    }

    @Test fun physicalRepeatNeverLoopsInsideTheWindow() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 500, repeat = RepeatMode.ALL)
        settle(r)
        assertEquals(Player.REPEAT_MODE_OFF, r.controller.repeatMode)
        assertFalse(r.controller.shuffleModeEnabled)
        val one = rig(queue)
        Harness().install(one, queue, 500, repeat = RepeatMode.ONE)
        settle(one)
        assertEquals(Player.REPEAT_MODE_ONE, one.controller.repeatMode)
    }

    @Test fun duplicateSongsAtDifferentPositionsStayDistinctOccurrences() {
        val queue = duplicates(1_000)
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 502) // pattern index 2 -> song 1, the SECOND A of that cycle
        settle(r)
        val a = h.adapter.active!!
        assertEquals(1L, a.currentSongId)
        assertEquals(502, a.logicalCurrent)
        assertEquals(a.physicalStart, a.logicalToPhysical(502))
        val sameIdOtherOccurrences = listOf(500, 504, 497).map { a.logicalToPhysical(it) }
        assertEquals(3, sameIdOtherOccurrences.toSet().size)
        assertTrue(sameIdOtherOccurrences.none { it == a.physicalStart })
        assertEquals(expectedIds(queue, a), r.serverIds())
        assertEquals(a.installedCount, r.serverIds().size)
    }

    @Test fun shuffledPlaybackOrderInstallsTheExpectedOccurrences() {
        val queue = distinct(25_000).shuffled(java.util.Random(42))
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 777)
        settle(r)
        val a = h.adapter.active!!
        assertEquals(queue[777].id.toString(), r.snapshot().mediaId)
        assertEquals((a.window.start until a.window.start + a.installedCount).map { queue[it].id.toString() }, r.serverIds())
    }

    @Test fun smallQueuesStayFullAndAreRefusedByTheBoundedInstaller() {
        assertEquals(QueueMode.Full, PhysicalQueuePolicy.modeFor(300, boundedEnabled = true))
        assertEquals(QueueMode.Bounded, PhysicalQueuePolicy.modeFor(301, boundedEnabled = true))
        assertEquals(QueueMode.Full, PhysicalQueuePolicy.modeFor(25_000)) // committed gate is off
        val queue = distinct(300)
        val r = rig(queue)
        val outcome = Harness().install(r, queue, 10)
        assertEquals(BoundedInstallOutcome.Rejected(BoundedInstallRejection.QueueBelongsToFullMode), outcome)
        assertTrue(r.recording.calls.isEmpty())
    }

    // ── safety and authority ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun invalidStartIndexIsRejectedWithoutTouchingThePlayer() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        for (bad in intArrayOf(-1, 1_000, Int.MAX_VALUE)) {
            assertEquals(BoundedInstallOutcome.Rejected(BoundedInstallRejection.StartOutOfRange), h.install(r, queue, bad))
        }
        assertEquals(BoundedInstallOutcome.Rejected(BoundedInstallRejection.NegativePosition), h.install(r, queue, 5, positionMs = -1))
        assertEquals(BoundedInstallOutcome.Rejected(BoundedInstallRejection.EmptyQueue), h.install(r, emptyList(), 0))
        assertEquals(WindowInstallState.Idle, h.adapter.state)
        assertTrue(r.recording.calls.isEmpty())
        assertEquals(0, r.controller.mediaItemCount)
    }

    @Test fun movedLogicalGenerationCannotActivateAWindow() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 100)
        h.currentGeneration += 1 // a newer logical queue won before verification
        settle(r)
        assertNull(h.adapter.active)
        assertEquals(WindowInstallState.Idle, h.adapter.state)
        val stale = h.outcomes.single() as BoundedInstallOutcome.Stale
        assertEquals(StaleReason.LogicalGenerationMoved, stale.reason)
    }

    @Test fun tokensOfAnotherWindowGenerationAreNotValid() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 100)
        settle(r)
        val a = h.adapter.active!!
        assertTrue(a.token.isValidFor(a.window, h.currentGeneration))
        assertFalse(PlanToken(a.token.logicalGeneration, a.token.windowGeneration + 1).isValidFor(a.window, h.currentGeneration))
        assertFalse(PlanToken(a.token.logicalGeneration + 1, a.token.windowGeneration).isValidFor(a.window, h.currentGeneration))
    }

    @Test fun newerAcceptedInstallSupersedesAnOlderUnfinishedOne() {
        val queue = distinct(25_000)
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 100)
        h.install(r, queue, 20_000)
        assertEquals(StaleReason.Superseded, (h.outcomes.first() as BoundedInstallOutcome.Stale).reason)
        assertNull(h.adapter.active)
        settle(r)
        val a = h.adapter.active!!
        assertEquals(20_000, a.logicalCurrent)
        assertEquals(1L, a.window.windowGeneration) // generations are monotonic, never reused
        assertEquals(2, h.outcomes.size)
        assertEquals(queue[20_000].id.toString(), r.snapshot().mediaId)
    }

    @Test fun incompleteInstallIsNeverPublishedAndLeavesARecoverableState() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        // A player that swallows the install: the observed timeline never reflects the intended window.
        val dropping = object : ForwardingPlayer(r.controller) {
            override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
        }
        assertTrue(h.install(r, queue, 400, positionMs = 900L, player = dropping) is BoundedInstallOutcome.Submitted)
        settle(r)
        assertNull(h.adapter.active)
        assertNull(h.adapter.active)
        assertNull("no mapping is translated through an unverified window", h.adapter.logicalToPhysical(400))
        val state = h.adapter.state as WindowInstallState.NeedsReinstall
        assertEquals(400, state.recovery.logicalStart)
        assertEquals(900L, state.recovery.positionMs)
        assertTrue(h.outcomes.single() is BoundedInstallOutcome.Incomplete)
        assertEquals(0, r.controller.mediaItemCount)
    }

    @Test fun materializationFailureAndOversizedMaterializationNeverReachMedia3() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val throwing = PhysicalWindowAdapter(currentLogicalGeneration = { 1L }, materialize = { error("boom") })
        assertTrue(throwing.install(r.controller, queue, 1L, 10, 0L, RepeatMode.OFF) is BoundedInstallOutcome.Failed)
        assertTrue(throwing.state is WindowInstallState.NeedsReinstall)
        val oversized = PhysicalWindowAdapter(
            currentLogicalGeneration = { 1L },
            materialize = { songs -> List(songs.size + 1) { songs[0].toPlaybackMediaItem() } },
        )
        assertTrue(oversized.install(r.controller, queue, 1L, 10, 0L, RepeatMode.OFF) is BoundedInstallOutcome.Failed)
        assertNull(oversized.active)
        assertTrue("no list was ever pushed", r.recording.calls.none { it.startsWith("setMediaItems") })
        assertEquals(0, r.controller.mediaItemCount)
    }

    @Test fun mismatchedObservationIsNotActivated() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        // The player receives the right window but starts at the wrong physical item.
        val wrongStart = object : ForwardingPlayer(r.controller) {
            override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) =
                super.setMediaItems(mediaItems, startIndex + 1, startPositionMs)
        }
        h.install(r, queue, 400, player = wrongStart)
        settle(r)
        assertNull(h.adapter.active)
        assertTrue((h.outcomes.single() as BoundedInstallOutcome.Incomplete).detail.contains("physical: current index"))
    }

    // ── authoritative physical acknowledgement ──────────────────────────────────────────────────────────────────────────

    /** A verifier whose acknowledgements are completed by the test, to control their order relative to everything else. */
    private class StubVerifier : PhysicalInstallVerifier {
        val requests = mutableListOf<Pair<PlanToken, com.google.common.util.concurrent.SettableFuture<PhysicalObservation>>>()
        override fun observe(token: PlanToken): com.google.common.util.concurrent.ListenableFuture<PhysicalObservation> =
            com.google.common.util.concurrent.SettableFuture.create<PhysicalObservation>().also { requests += token to it }
    }

    private fun awaitPhysical(r: RealMedia3QueueRig, count: Int) = TestMediaSource.awaitCondition { r.player.mediaItemCount == count }

    /** What the authoritative (server-side) player holds now, as an acknowledgement for [token]. */
    private fun physicalAck(r: RealMedia3QueueRig, token: PlanToken) = PhysicalObservation.observe(r.player, token)

    @Test fun controllerReflectsTheRequestBeforeThePhysicalPlayerAcknowledgesAndStaysProvisional() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        val stub = StubVerifier()
        h.install(r, queue, 400, verifier = stub)
        awaitPhysical(r, 100)
        TestMediaSource.awaitCondition { r.controller.mediaItemCount == 100 && h.adapter.state is WindowInstallState.Provisional }
        assertNull("controller view alone must not publish", h.adapter.active)
        assertNull(h.adapter.logicalToPhysical(400))
        assertNull(h.adapter.physicalToLogical(20))
        assertTrue(h.outcomes.isEmpty())
        val (token, ack) = stub.requests.single()
        ack.set(physicalAck(r, token))
        idleMainLooper()
        assertEquals(token, h.adapter.active!!.token)
        assertEquals(1, h.outcomes.size)
    }

    @Test fun delayedAcknowledgementPublishesOnlyWhenItArrivesAndMatches() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        val stub = StubVerifier()
        h.install(r, queue, 400, verifier = stub)
        awaitPhysical(r, 100)
        idleMainLooper()
        assertNull(h.adapter.active)
        val (token, ack) = stub.requests.single()
        // An acknowledgement for a different window revision cannot activate this one.
        ack.set(physicalAck(r, PlanToken(token.logicalGeneration, token.windowGeneration + 1)))
        idleMainLooper()
        assertNull(h.adapter.active)
        assertTrue(h.outcomes.single() is BoundedInstallOutcome.Incomplete)
        assertTrue(h.adapter.state is WindowInstallState.NeedsReinstall)
    }

    @Test fun physicalPlayerThatDroppedTheInstallNeverActivatesEvenThoughTheControllerMaskedIt() {
        val queue = distinct(1_000)
        val r = rig(queue)
        r.recording.dropSetMediaItems = true
        val h = Harness()
        h.install(r, queue, 400)
        TestMediaSource.awaitCondition { h.outcomes.isNotEmpty() }
        val outcome = h.outcomes.single() as BoundedInstallOutcome.Incomplete
        assertTrue(outcome.detail, outcome.detail.startsWith("physical:"))
        assertNull(h.adapter.active)
        assertEquals(0, r.player.mediaItemCount)
        assertTrue(h.adapter.state is WindowInstallState.NeedsReinstall)
    }

    @Test fun failedAcknowledgementLeavesARecoverableStateAndNothingPublished() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        val stub = StubVerifier()
        h.install(r, queue, 400, positionMs = 77L, verifier = stub)
        stub.requests.single().second.setException(IllegalStateException("session refused"))
        idleMainLooper()
        assertTrue(h.outcomes.single() is BoundedInstallOutcome.Failed)
        assertNull(h.adapter.active)
        assertEquals(400, (h.adapter.state as WindowInstallState.NeedsReinstall).recovery.logicalStart)
    }

    @Test fun newerInstallSupersedesAnOlderPendingAcknowledgement() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        val stub = StubVerifier()
        h.install(r, queue, 100, verifier = stub)
        h.install(r, queue, 600, verifier = stub)
        val (tokenA, ackA) = stub.requests[0]
        val (tokenB, ackB) = stub.requests[1]
        awaitPhysical(r, 100)
        TestMediaSource.awaitCondition { r.player.currentMediaItem?.mediaId == queue[600].id.toString() }
        // The OLD acknowledgement arrives late, carrying the (now B) physical state under A's token.
        ackA.set(physicalAck(r, tokenA))
        idleMainLooper()
        assertNull("a stale acknowledgement cannot activate the older window", h.adapter.active)
        ackB.set(physicalAck(r, tokenB))
        idleMainLooper()
        assertEquals(600, h.adapter.active!!.logicalCurrent)
        assertEquals(2, h.outcomes.size)
        assertEquals(StaleReason.Superseded, (h.outcomes[0] as BoundedInstallOutcome.Stale).reason)
        assertTrue(h.outcomes[1] is BoundedInstallOutcome.Active)
    }

    @Test fun generationChangeBeforeAcknowledgementDiscardsIt() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        val stub = StubVerifier()
        h.install(r, queue, 400, verifier = stub)
        awaitPhysical(r, 100)
        h.currentGeneration += 1
        val (token, ack) = stub.requests.single()
        ack.set(physicalAck(r, token))
        idleMainLooper()
        assertNull(h.adapter.active)
        assertEquals(StaleReason.LogicalGenerationMoved, (h.outcomes.single() as BoundedInstallOutcome.Stale).reason)
        assertEquals(WindowInstallState.Idle, h.adapter.state)
    }

    @Test fun resetBeforeAcknowledgementDiscardsIt() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        val stub = StubVerifier()
        h.install(r, queue, 400, verifier = stub)
        awaitPhysical(r, 100)
        h.adapter.reset()
        val (token, ack) = stub.requests.single()
        ack.set(physicalAck(r, token))
        idleMainLooper()
        assertNull(h.adapter.active)
        assertEquals(WindowInstallState.Idle, h.adapter.state)
        assertEquals(StaleReason.Superseded, (h.outcomes.single() as BoundedInstallOutcome.Stale).reason)
    }

    @Test fun successfulPhysicalVerificationPublishesActiveExactlyOnce() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 400)
        TestMediaSource.awaitCondition { h.outcomes.isNotEmpty() }
        h.adapter.verifyPending()
        h.adapter.expirePending()
        idleMainLooper()
        assertEquals(1, h.outcomes.size)
        assertEquals(1, h.outcomes.count { it is BoundedInstallOutcome.Active })
        assertEquals(400, h.adapter.active!!.logicalCurrent)
    }

    @Test fun withoutAnAuthoritativeVerifierAnInstallNeverBecomesActive() {
        val queue = distinct(1_000)
        val r = rig(queue)
        val h = Harness()
        h.install(r, queue, 400, verifier = null)
        awaitPhysical(r, 100)
        TestMediaSource.awaitCondition { h.adapter.state is WindowInstallState.Provisional }
        h.adapter.verifyPending()
        assertNull(h.adapter.active)
        h.adapter.expirePending()
        assertTrue(h.outcomes.single() is BoundedInstallOutcome.Incomplete)
    }

    // ── operation shape, performance evidence ───────────────────────────────────────────────────────────────────────────

    @Test fun installationCostIsIndependentOfTheLogicalQueueSize() {
        println("P5-B evidence: logical | physical | materialized | media3 calls | install ms (JVM harness)")
        var previousMaterialized = -1
        for (n in intArrayOf(1_000, 5_000, 10_000, 25_000, 50_000)) {
            val queue = distinct(n)
            val r = rig(queue)
            val h = Harness()
            val t0 = System.nanoTime()
            h.install(r, queue, n / 2)
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            settle(r)
            val a = h.adapter.active!!
            println("P5-B evidence: $n | ${a.installedCount} | ${h.materialized} | ${r.recording.calls} | %.2f".format(ms))
            assertEquals(PhysicalWindowPolicy.W_MAX, a.installedCount)
            assertEquals(a.installedCount, h.materialized)
            assertEquals(listOf("setMediaItems(100)"), r.recording.calls.filter { it.startsWith("setMediaItems") })
            if (previousMaterialized >= 0) assertEquals(previousMaterialized, h.materialized)
            previousMaterialized = h.materialized
        }
    }

    // ── structure guards ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun adapterNeverCopiesOrMapsTheWholeQueue() {
        val code = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PhysicalWindowAdapter.kt").readLines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            .joinToString("\n")
        for (forbidden in listOf("queue.map", "queue.toList", "queue.forEach", "queue.subList", "replaceMediaItems", "clearMediaItems", "toMutableList")) {
            assertFalse("adapter must not use $forbidden", code.contains(forbidden))
        }
        assertEquals("exactly one list install", 1, Regex("""\.setMediaItems\(""").findAll(code).count())
        // P5-C: the only incremental additions are the bounded prepend and append batches of one adjustment.
        assertEquals(2, Regex("""\.addMediaItems\(""").findAll(code).count())
    }

    @Test fun gateIsOffAndTheControllerReachesTheAdapterOnlyThroughTheSeam() {
        assertFalse(PhysicalQueuePolicy.BOUNDED_WINDOW_ENABLED)
        val source = File("src/main/kotlin/com/launchpoint/wavdrop/playback/PlayerController.kt").readText()
        assertTrue(source.contains("internal var boundedInstallEnabledForTesting: Boolean = false"))
        assertEquals(1, Regex("""physicalWindowAdapter\.install\(""").findAll(source).count())
        // Every whole-playbackQueue push goes through the seam; direct calls left: the seam's Full branch and the 1-song external-URI install.
        val direct = Regex("""setMeasuredMediaItems\(\s*operation\s*=[^)]*songs = playbackQueue""").findAll(source).count()
        assertEquals(2, direct)
    }
}
