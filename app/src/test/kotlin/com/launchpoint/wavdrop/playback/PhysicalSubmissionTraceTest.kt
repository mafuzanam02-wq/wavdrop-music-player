package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.launchpoint.wavdrop.data.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P5-F1: the validation trace may report `window_submitted` only for a physical playlist operation the adapter ACTUALLY issued,
 * and a baseline observation is a different event. Real Media3 and the real physical probe; every submission the observer sees is
 * cross-checked against the calls the player really received. Acknowledgement ordering is controlled by the test, never by sleeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PhysicalSubmissionTraceTest {

    private val rigs = mutableListOf<RealMedia3QueueRig>()

    @After fun tearDown() {
        rigs.forEach { it.releaseAll() }
        rigs.clear()
        ValidationTrace.enabled = com.launchpoint.wavdrop.BuildConfig.DEBUG
        ValidationTrace.sink = { android.util.Log.d(ValidationTrace.TAG, it) }
    }

    private fun distinct(n: Int): List<Song> = List(n) { RealMedia3QueueRig.song(it + 1L) }
    private fun s(id: Long) = RealMedia3QueueRig.song(id)
    private fun insertAt(q: List<Song>, at: Int, vararg songs: Song) = q.toMutableList().also { it.addAll(at, songs.toList()) }

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
        val outcomes = mutableListOf<BoundedInstallOutcome>()
        val submissions = mutableListOf<PhysicalSubmission>()
        val adapter = PhysicalWindowAdapter(policy = policy, currentLogicalGeneration = { generation })
        val ctl = Controlled(r)
        val callback = TracedOutcome("op") { outcomes += it }

        init { adapter.submissionObserver = { submissions += it } }

        fun idle() = TestMediaSource.awaitCondition { !adapter.isBusy }
        fun install(start: Int) {
            adapter.install(r.port, q, generation, start, 0L, RepeatMode.OFF, ctl, callback); idle()
            submissions.clear(); outcomes.clear() // the setup install is not under test
        }
        fun navigate(target: Int) = adapter.navigateTo(r.port, q, target, 0L, RepeatMode.OFF, ctl, callback)
        fun mutate(newQueue: List<Song>, oldCurrent: Int?, map: (Int) -> Int?, continuation: Int? = null): MutationSubmission {
            generation++
            q = newQueue
            return adapter.applyMutation(QueueMutationRequest(r.port, newQueue, RepeatMode.OFF, oldCurrent, map, continuation, ctl, callback))
        }

        fun installs() = submissions.count { it.kind == "install" }
        fun adjusts() = submissions.count { it.kind == "adjust" }
        fun playerSetCalls(since: Int) = r.recording.calls.drop(since).count { it.startsWith("setMediaItems(") }
        fun playerEditCalls(since: Int) = r.recording.calls.drop(since).count { it.startsWith("add") || it.startsWith("append") || it.startsWith("remove") }
    }

    @Test fun baselineThenLogicalOnlyProducesNoPhysicalSubmission() {
        val w = W(distinct(25_000))
        w.install(12_000)
        val before = w.r.recording.calls.size
        val baselines = mutableListOf<MutationSubmission>()
        baselines += w.mutate(w.q + List(500) { s(600_000L + it) }, 12_000, OccurrenceMap.identity()); w.idle()
        assertEquals(MutationSubmission.Submitted("baseline"), baselines.single())
        assertEquals(0, w.submissions.size)
        assertEquals("the player really received no playlist operation", 0, w.playerSetCalls(before) + w.playerEditCalls(before))
        assertTrue("terminal verification still reported", w.outcomes.single() is BoundedInstallOutcome.Active)
    }

    @Test fun baselineThenBoundedEditProducesExactlyOneAdjustSubmission() {
        val w = W(distinct(25_000))
        w.install(500)
        val before = w.r.recording.calls.size
        assertEquals(MutationSubmission.Submitted("baseline"), w.mutate(insertAt(w.q, 501, s(90_001L)), 500, OccurrenceMap.insert(501, 1)))
        assertEquals("nothing is submitted while only the baseline is outstanding or classified", 0, w.installs())
        w.idle()
        assertEquals(1, w.adjusts())
        assertEquals(0, w.installs())
        assertTrue(w.submissions.single().inserts >= 1)
        assertTrue("the player really received the edit", w.playerEditCalls(before) >= 1)
        assertEquals("no whole-window install", 0, w.playerSetCalls(before))
        assertTrue(w.outcomes.single() is BoundedInstallOutcome.Active)
    }

    @Test fun baselineThenReanchorProducesExactlyOneInstallSubmission() {
        val w = W(distinct(25_000))
        w.install(500)
        val before = w.r.recording.calls.size
        w.mutate(w.q.toMutableList().also { it.removeAt(500) }, 500, OccurrenceMap.remove(500), continuation = 500); w.idle()
        assertEquals(1, w.installs())
        assertEquals(0, w.adjusts())
        assertEquals(1, w.playerSetCalls(before))
        assertTrue(w.submissions.single().onOutcome === w.callback)
        assertTrue(w.outcomes.single() is BoundedInstallOutcome.Active)
    }

    @Test fun aFailedBaselineIsNotASubmissionAndOnlyTheBoundedRecoveryItIssuesIsReported() {
        val w = W(distinct(25_000))
        w.install(500)
        val before = w.r.recording.calls.size
        w.ctl.hold()                                         // the baseline is never answered
        w.mutate(insertAt(w.q, 501, s(90_002L)), 500, OccurrenceMap.insert(501, 1))
        assertEquals("a pending baseline has submitted nothing", 0, w.submissions.size)
        w.adapter.expirePending()                            // the baseline fails (no acknowledgement)
        assertTrue(w.outcomes.single() is BoundedInstallOutcome.Incomplete)
        // The adapter then issues ONE bounded recovery install; that is a real playlist operation and is reported once, as an install.
        assertEquals(listOf("install"), w.submissions.map { it.kind })
        assertEquals(1, w.playerSetCalls(before))
        assertEquals(0, w.playerEditCalls(before))
    }

    @Test fun aRejectedMutationSubmitsNothing() {
        val w = W(distinct(25_000))
        w.install(500)
        w.generation++
        val result = w.adapter.applyMutation(
            QueueMutationRequest(w.r.unverifiablePort, insertAt(w.q, 501, s(90_010L)), RepeatMode.OFF, 500, OccurrenceMap.insert(501, 1), null, null, w.callback),
        )
        assertTrue(result is MutationSubmission.Rejected)
        assertEquals(0, w.submissions.size)
    }

    @Test fun aHeldNavigationThatLaterNeedsAPhysicalOperationIsReportedOnceAndAttributed() {
        val w = W(distinct(25_000), PhysicalWindowPolicy(PhysicalWindowPolicy.Config(historyMax = 60)))
        w.install(0)
        w.navigate(50); TestMediaSource.awaitCondition { !w.adapter.isBusy }
        w.submissions.clear(); w.outcomes.clear()
        val before = w.r.recording.calls.size
        w.ctl.hold(answerFirst = 1)
        assertTrue(w.adapter.maintain(w.r.port, w.q, RepeatMode.OFF, w.ctl, w.callback) is MaintenanceResult.Submitted)
        TestMediaSource.awaitCondition { w.ctl.held.isNotEmpty() }
        assertEquals("the refill baseline was answered, so its one append batch is the only submission so far", listOf("adjust"), w.submissions.map { it.kind })
        assertTrue(w.navigate(55) is BoundedNavigationResult.Held)
        assertEquals("a held request is not a submission", 1, w.submissions.size)
        w.adapter.expirePending()                            // the in-flight adjustment fails; the adapter must recover around the held target
        w.ctl.live = true; w.ctl.release(); w.idle()
        assertEquals("exactly one physical install, no duplicate for the held request", 1, w.installs())
        assertEquals(1, w.adjusts())
        assertEquals(w.installs(), w.playerSetCalls(before))
        assertTrue(w.submissions.all { it.onOutcome === w.callback })
    }

    @Test fun eachSubmissionIsEmittedExactlyOnceThroughTheTraceAndTerminalEventsFollow() {
        val lines = mutableListOf<String>()
        ValidationTrace.sink = { lines += it }
        ValidationTrace.enabled = true
        val w = W(distinct(25_000))
        w.adapter.submissionObserver = { ValidationTrace.physicalSubmitted(it, w.q.size) }
        val callback = TracedOutcome("play_from_queue") { ValidationTrace.windowTerminal("play_from_queue", 0L, it, w.q.size, w.r.player.mediaItemCount) }
        w.adapter.install(w.r.port, w.q, w.generation, 500, 0L, RepeatMode.OFF, w.ctl, callback); w.idle()
        assertEquals(1, lines.count { it.startsWith("window_submitted") })
        assertEquals("window_submitted op=play_from_queue kind=install logicalSize=25000", lines.first())
        assertTrue(lines.last().startsWith("window_verified op=play_from_queue"))
        assertEquals(2, lines.size)
    }

    @Test fun fullModeTracingProducesNoBoundedOperationEvents() {
        val lines = mutableListOf<String>()
        ValidationTrace.sink = { lines += it }
        ValidationTrace.enabled = true
        // A Full install never reaches the adapter: the controller only reports the request, with mode=Full.
        ValidationTrace.installRequested("play_from_queue", 300, QueueMode.Full, 0)
        assertEquals(listOf("install_requested op=play_from_queue mode=Full logicalSize=300 startLogical=0"), lines)
        val w = W(distinct(5))
        assertEquals("an idle adapter never reports anything", 0, w.submissions.size)
    }
}
