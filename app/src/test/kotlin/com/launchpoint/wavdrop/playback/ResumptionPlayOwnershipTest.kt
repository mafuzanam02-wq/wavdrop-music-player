package com.launchpoint.wavdrop.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
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
 * F6 review correction: WHICH PLAY belongs to which resumption request.
 *
 * Media3 issues one PLAY on the session player when its callback for a `onPlaybackResumption(isForPlayback = true)` request runs, and
 * that callback runs on `Util.postOrRun(applicationHandler)`: inline when the completing thread is the application looper, otherwise
 * posted. Nothing guarantees when it runs relative to other commands, and a request that was superseded must not be allowed to start
 * playback, while a newer, legitimate PLAY must never be refused because an older request is still waiting for its callback.
 *
 * Every test here drives a request through [ResumptionOrderRig.Media3Request] — the production handler, attached to Media3's callback by
 * `Futures.addCallback` and running Media3's apply-then-PLAY step — with the callback held until the test calls `deliver()`, so the
 * test, not a timing accident, decides the order. Commands that are not Media3's PLAY (a PLAY or PAUSE from a controller) go straight
 * to the production [PreviousBehaviorPlayer]. No test sleeps to create an ordering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ResumptionPlayOwnershipTest {

    private val rigs = mutableListOf<ResumptionOrderRig>()
    private fun rig(bounded: Boolean = true) = ResumptionOrderRig(bounded).also { rigs += it }

    @After fun tearDown() {
        rigs.forEach { it.release() }
        rigs.clear()
        PlaybackStartupTrace.resetForTest()
    }

    private val boundedSize = 2_000
    private val boundedIndex = 1_234
    private val fullSize = 200
    private val fullIndex = 50

    private fun bounded(): ResumptionOrderRig = rig(true).also { it.saveSimpleSession(boundedSize, boundedIndex, 33_000L) }
    private fun full(): ResumptionOrderRig = rig(false).also { it.saveSimpleSession(fullSize, fullIndex, 7_000L) }

    /** A request whose snapshot load is held by a gate, so it is "received" now and completes when the gate opens. */
    private fun ResumptionOrderRig.gated(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { snapshotGate = it }

    private fun assertBoundedRestored(r: ResumptionOrderRig, label: String) {
        assertEquals("$label: logical queue restored", boundedSize, r.logical.queue.size)
        assertEquals("$label: saved occurrence", boundedIndex, r.logical.currentIndex)
        assertEquals("$label: saved song", "${boundedIndex + 1}", r.currentMediaId)
        assertTrue("$label: bounded window (${r.engine.currentPlayer.mediaItemCount})", r.engine.currentPlayer.mediaItemCount <= PhysicalWindowPolicy.W_MAX)
        assertNotNull("$label: window verified", r.adapter.active)
        assertEquals("$label: exactly one install", 1, r.windowInstalls())
        assertEquals("$label: nothing rejected", 0, r.windowRejections())
    }

    private fun assertFullRestored(r: ResumptionOrderRig, label: String) {
        assertEquals("$label: queue restored", fullSize, r.logical.queue.size)
        assertEquals("$label: saved occurrence", fullIndex, r.logical.currentIndex)
        assertEquals("$label: saved song", "${fullIndex + 1}", r.currentMediaId)
        assertEquals("$label: whole queue installed by Media3 once", 1, r.recording.count("setMediaItems"))
        assertEquals("$label: no bounded window operation", 0, r.trace.count { it.startsWith("window_") })
    }

    private fun assertIdle(r: ResumptionOrderRig, label: String) {
        assertFalse("$label: no cold PLAY left pending", r.pc.coldPlayAuthority.hasPendingPlay)
    }

    // ── a legitimate PLAY while a superseded request awaits Media3's PLAY ────────────────────────────────────────────────

    @Test fun aLegitimatePlayWhileASupersededRequestAwaitsItsCallbackIsNeverSuppressed() {
        val r = bounded()
        val gate = r.gated()
        val request = r.Media3Request()
        r.sessionPlayer.pause() // a newer PAUSE supersedes the request
        gate.complete(Unit); request.awaitCompletion(); r.quiesce()
        assertTrue("Media3's callback has not run: the request still awaits its PLAY", request.callbackPending)
        assertFalse(r.playWhenReady)

        r.sessionPlayer.play() // a newer, legitimate PLAY (a controller's PLAY on the already restored window)
        assertTrue("the legitimate PLAY is honoured", r.playWhenReady)
        assertEquals(1, r.recording.count("play"))
        assertEquals("nothing was refused so far", 0, r.suppressed())

        assertEquals("the obsolete request's own PLAY is the one refused", 0, request.deliver())
        assertEquals(1, r.suppressed())
        assertTrue("the newest command was PLAY and playback continues", r.playWhenReady)
        assertBoundedRestored(r, "legit play")
        assertIdle(r, "legit play")
    }

    @Test fun aPlayWhenEverySupersededRequestIsStillWaitingIsNeverSuppressed() {
        val r = bounded()
        val gate = r.gated()
        val first = r.Media3Request(); r.sessionPlayer.pause()
        val second = r.Media3Request(); r.sessionPlayer.pause()
        gate.complete(Unit); first.awaitCompletion(); second.awaitCompletion(); r.quiesce()

        r.sessionPlayer.play()
        assertTrue(r.playWhenReady)
        assertEquals("the controller's PLAY reached the player", 1, r.recording.count("play"))
        assertEquals(0, first.deliver())
        assertEquals(0, second.deliver())
        assertEquals("both obsolete PLAYs refused, the legitimate one kept", 2, r.suppressed())
        assertTrue(r.playWhenReady)
        assertBoundedRestored(r, "all superseded")
    }

    // ── an obsolete PLAY must never override a newer PAUSE ───────────────────────────────────────────────────────────────

    @Test fun anObsoleteMedia3PlayCannotOverrideANewerPauseWhateverItsCallbackDelay() {
        val r = bounded()
        val request = r.Media3Request() // still the newest command when it completes
        request.awaitCompletion(); r.quiesce()
        r.sessionPlayer.play(); r.sessionPlayer.pause() // later commands, both newer than the request
        assertFalse(r.playWhenReady)

        assertEquals("the obsolete PLAY did not start playback", 0, request.deliver())
        assertFalse("the newest command was PAUSE", r.playWhenReady)
        assertEquals(1, r.suppressed())
        assertBoundedRestored(r, "newer pause")
        assertIdle(r, "newer pause")
    }

    @Test fun aSupersededRequestStaysSupersededAcrossManyLooperTurnsBeforeItsCallbackRuns() {
        val r = bounded()
        val gate = r.gated()
        val request = r.Media3Request()
        r.sessionPlayer.pause()
        gate.complete(Unit); request.awaitCompletion(); r.quiesce()
        // many main-looper turns pass before Media3's callback finally runs (nothing may time the refusal out)
        repeat(20) { idleMainLooper(); TestMediaSource.settle(10) }
        assertEquals(0, request.deliver())
        assertFalse(r.playWhenReady)
        assertEquals(1, r.suppressed())
        assertBoundedRestored(r, "many turns")
    }

    @Test fun aStillCurrentRequestKeepsItsPlayAcrossManyLooperTurns() {
        val r = bounded()
        val request = r.Media3Request()
        request.awaitCompletion(); r.quiesce()
        repeat(20) { idleMainLooper(); TestMediaSource.settle(10) }
        assertEquals("the newest command was this request's PLAY", 1, request.deliver())
        assertTrue(r.playWhenReady)
        assertEquals(0, r.suppressed())
        assertBoundedRestored(r, "current request")
        assertIdle(r, "current request")
    }

    // ── overlapping requests completing and being delivered in different orders ─────────────────────────────────────────

    private fun overlappingOrders(r: ResumptionOrderRig, deliverNewerFirst: Boolean) {
        val gate = r.gated()
        val older = r.Media3Request()
        r.sessionPlayer.pause() // supersedes the older request
        val newer = r.Media3Request() // the newest command is this PLAY
        gate.complete(Unit); older.awaitCompletion(); newer.awaitCompletion(); r.quiesce()

        if (deliverNewerFirst) { newer.deliver(); older.deliver() } else { older.deliver(); newer.deliver() }
        // The newest request's PLAY is admitted. (A request that lost the queue-authority race in Full mode is dropped and its PLAY then
        // takes the established explicit-PLAY hydration route, which finishes asynchronously, so wait for it rather than assume it is immediate.)
        TestMediaSource.awaitCondition { r.playWhenReady }
        assertTrue("the newest command was PLAY: playing", r.playWhenReady)
        assertEquals("exactly one PLAY reached the player", 1, r.recording.count("play"))
        assertEquals("only the superseded request's PLAY was refused", 1, r.suppressed())
    }

    @Test fun overlappingRequestsDeliveredOlderFirstBounded() {
        val r = bounded(); overlappingOrders(r, deliverNewerFirst = false)
        assertBoundedRestored(r, "older first"); assertIdle(r, "older first")
    }

    @Test fun overlappingRequestsDeliveredNewerFirstBounded() {
        val r = bounded(); overlappingOrders(r, deliverNewerFirst = true)
        assertBoundedRestored(r, "newer first"); assertIdle(r, "newer first")
    }

    @Test fun overlappingRequestsDeliveredNewerFirstFull() {
        val r = full(); overlappingOrders(r, deliverNewerFirst = true)
        assertFullRestored(r, "Full newer first"); assertIdle(r, "Full newer first")
    }

    @Test fun overlappingRequestsWhereTheNewestIsPausedAgainRefuseBothInEitherOrder() {
        for (newerFirst in listOf(false, true)) {
            val r = bounded()
            val gate = r.gated()
            val older = r.Media3Request(); r.sessionPlayer.pause()
            val newer = r.Media3Request(); r.sessionPlayer.pause()
            gate.complete(Unit); older.awaitCompletion(); newer.awaitCompletion(); r.quiesce()
            val plays = if (newerFirst) newer.deliver() + older.deliver() else older.deliver() + newer.deliver()
            assertEquals("newerFirst=$newerFirst: no obsolete PLAY started playback", 0, plays)
            assertFalse(r.playWhenReady)
            assertEquals(2, r.suppressed())
            assertBoundedRestored(r, "newerFirst=$newerFirst")
            r.release(); rigs.remove(r)
        }
    }

    // ── delayed or missing callbacks and cleanup ────────────────────────────────────────────────────────────────────────

    @Test fun aCallbackThatNeverRunsLeavesNothingThatCouldRefuseALaterPlay() {
        val r = bounded()
        val gate = r.gated()
        val request = r.Media3Request()
        r.sessionPlayer.pause()
        gate.complete(Unit); request.awaitCompletion(); r.quiesce()
        // Media3's callback is never delivered (e.g. `postOrRun` found the looper thread dead). Nothing may linger.
        assertTrue(request.callbackPending)

        r.sessionPlayer.play()
        assertTrue("a later PLAY is never refused on account of a request that never reported back", r.playWhenReady)
        assertEquals(1, r.recording.count("play"))
        assertEquals(0, r.suppressed())
        assertIdle(r, "never delivered")
        assertBoundedRestored(r, "never delivered")
    }

    @Test fun anAbandonedRequestDoesNotKeepTheDesiredStatePlaying() {
        val r = bounded()
        val request = r.Media3Request()
        assertTrue("a request in flight counts as desired playing (a toggle becomes a pause)", r.pc.coldPlayAuthority.desiredPlaying(false))
        r.sessionPlayer.pause()
        assertFalse(r.pc.coldPlayAuthority.desiredPlaying(false))
        r.snapshotGate?.complete(Unit)
        request.awaitCompletion(); r.quiesce()
        assertIdle(r, "abandoned")
    }

    // ── teardown ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun teardownWhileARequestIsPendingStartsNothingAndLeavesNothingPending() {
        val r = bounded()
        val gate = r.gated()
        val request = r.Media3Request()
        assertTrue(r.pc.coldPlayAuthority.hasPendingPlay)
        r.pc.release() // PlaybackService.onDestroy → PlayerController.release(): ends every cold PLAY request
        assertFalse("teardown supersedes the request", r.pc.coldPlayAuthority.hasPendingPlay)
        gate.complete(Unit)
        request.awaitCompletion()
        if (request.callbackPending) assertEquals("an obsolete request's PLAY after teardown is refused", 0, request.deliver())
        assertFalse(r.playWhenReady)
        assertIdle(r, "teardown")
    }

    @Test fun cancellingTheServiceScopeBeforeTheRequestCompletesLeavesNoPendingState() {
        val r = bounded()
        r.gated()
        val request = r.Media3Request()
        assertTrue(r.pc.coldPlayAuthority.hasPendingPlay)
        r.pc.release()
        r.release() // cancels the session scope: the request can never complete
        rigs.remove(r)
        assertFalse(request.future.isDone)
        assertFalse(r.pc.coldPlayAuthority.hasPendingPlay)
    }

    // ── Full mode ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fullModeObsoleteRequestIsRefusedWhileTheQueueIsStillRestored() {
        val r = full()
        val gate = r.gated()
        val request = r.Media3Request()
        r.sessionPlayer.pause()
        gate.complete(Unit); request.awaitCompletion(); r.quiesce()
        assertEquals(0, request.deliver())
        assertFalse(r.playWhenReady)
        assertEquals(1, r.suppressed())
        assertFullRestored(r, "Full obsolete")
        assertIdle(r, "Full obsolete")
    }

    @Test fun fullModeObsoleteCallbackCannotOverrideANewerPause() {
        val r = full()
        val request = r.Media3Request()
        request.awaitCompletion(); r.quiesce()
        r.sessionPlayer.pause()
        assertEquals(0, request.deliver())
        assertFalse(r.playWhenReady)
        assertFullRestored(r, "Full newer pause")
    }

    @Test fun fullModeCurrentRequestPlaysExactlyOnce() {
        val r = full()
        val request = r.Media3Request()
        request.awaitCompletion(); r.quiesce()
        assertEquals(1, request.deliver())
        assertTrue(r.playWhenReady)
        assertEquals(0, r.suppressed())
        assertFullRestored(r, "Full current")
        assertIdle(r, "Full current")
    }

    // ── the failure paths of a request own their PLAY the same way ──────────────────────────────────────────────────────

    @Test fun aSupersededRequestThatFailsHasItsFallbackPlayRefusedToo() {
        val r = rig(true) // no saved session: Media3 will see a failure and still issue a PLAY
        r.library = r.songs(10)
        val gate = r.gated()
        val request = r.Media3Request()
        r.sessionPlayer.pause()
        gate.complete(Unit); request.awaitCompletion()
        assertTrue("it failed: nothing saved", runCatching { request.future.get() }.isFailure)
        assertEquals("the failure's PLAY is the obsolete request's too: refused", 0, request.deliver())
        assertFalse(r.playWhenReady)
        assertEquals(1, r.suppressed())
        assertIdle(r, "failed")
    }
}
