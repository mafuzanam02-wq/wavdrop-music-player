package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CF-2L1: natural-AUTO terminal handoff. After the fade the secondary stays audible (gain 1) and the primary silent (gain 0)
 * until the authoritative AUTO transition lands the primary on the exact target, READY, at the fresh secondary position.
 * Everything here is state driven; there is no delay anywhere.
 */
class CrossfadeNaturalHandoffTest {

    private val events = mutableListOf<String>()

    private class FakeScheduler : CrossfadeTimingScheduler {
        class Pending(val delayMs: Long, val block: () -> Unit) { var active = true }
        val all = mutableListOf<Pending>()
        val activeNow get() = all.filter { it.active }
        override fun postDelayed(delayMs: Long, block: () -> Unit) { all += Pending(delayMs, block) }
        override fun cancelAll() { all.forEach { it.active = false } }
        fun runNext() { val p = activeNow.single(); p.active = false; p.block() }
    }

    private inner class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var snapshotPositionMs = 6_000L
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean = true
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = SecondaryHandoffSnapshot(snapshotPositionMs, 180_000L)
        override fun reset() { resets++; events += "s:reset" }
        override fun release() {}
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private fun song(id: Long) = Song(
        id = id, title = "S$id", artist = "A", album = "B", albumId = 0L, duration = 200_000L,
        uri = "content://media/$id", dateAdded = 0L, trackNumber = 0, year = 2020,
    )

    private var queue = listOf(song(1), song(2), song(3), song(4))
    private var generation = 5L
    private var repeat = RepeatMode.OFF
    private var logicalIndex = 1
    private var isPlaying = true
    private var queueDirty = false
    private var clockNow = 10_000L
    private var position = 0L
    private val scheduler = FakeScheduler()
    private val backend = FakeBackend()
    private val primaryWrites = mutableListOf<Float>()
    private var facts: PrimaryTakeoverFacts? = null
    private val reconcileCalls = mutableListOf<Pair<CrossfadeTransitionKey, SecondaryHandoffSnapshot>>()
    private var reconcileResult: CrossfadePrimaryReconciliationResult = CrossfadePrimaryReconciliationResult.Succeeded
    private var key = CrossfadeTransitionKey(5L, 1, 2)

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = logicalIndex,
                repeatMode = repeat, shuffleEnabled = false, isPlaying = isPlaying, isExternalPlayback = false,
                playerQueueNeedsSync = queueDirty, controllerConnected = true,
            )
        },
        backendFactory = { backend },
        mediaItemFactory = { MediaItem.Builder().setMediaId(it.id.toString()).build() },
        primaryGainBackend = PrimaryGainBackend { primaryWrites += it; events += "p:$it"; true },
        reconcilePrimary = { _, _ -> error("legacy cross-item reconciliation must not run in the natural handoff") },
        scheduler = scheduler,
        clock = { clockNow },
        configuredDurationMsProvider = { 6_000L },
        primaryDurationMs = { 200_000L },
        primaryPositionMs = { position },
        primaryTakeoverFacts = { facts },
        reconcilePrimaryAfterNaturalTransition = { k, s ->
            reconcileCalls += k to s
            events += "seek:${s.positionMs}"
            reconcileResult
        },
    )
    private val runtime get() = graph.runtime
    private val startA = 194_000L

    private fun toFading() {
        graph.timingDriver.start()
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Armed)
        backend.ready()
        position = startA + 1_000L
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    /** Terminal tick leaves HandoffPending; the immediate handoff attempt only waits for the AUTO transition. */
    private fun toHandoffPending() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(key, clockNow + 6_000L))
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.NaturalTransition),
            runtime.executeHandoff(key),
        )
        events.clear()
    }

    private fun auto(index: Int) = observeCrossfadeNaturalTransition(runtime, index, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

    private fun primaryOnTarget(ready: Boolean = true, positionMs: Long = 100L) {
        facts = PrimaryTakeoverFacts(physicalIndex = key.toPlaybackIndex, isReady = ready, positionMs = positionMs)
    }

    // ── waiting without effects ─────────────────────────────────────────────────

    @Test fun terminalFadeWaitsForAutoWithSecondaryAudibleAndPrimarySilent() {
        toHandoffPending()
        assertEquals(0f, primaryWrites.last(), 1e-6f)
        assertEquals(0, backend.resets)
        assertTrue(reconcileCalls.isEmpty())
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
    }

    @Test fun autoToExpectedTargetIsAcceptedButNotReadyKeepsEverythingAlive() {
        toHandoffPending()
        logicalIndex = 2
        primaryOnTarget(ready = false)
        auto(2)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets) // secondary not reset
        assertEquals(0f, primaryWrites.last(), 1e-6f) // primary still silent
        assertFalse(events.contains("p:1.0")) // no restore
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimaryReady),
            runtime.executeHandoff(key),
        )
    }

    @Test fun primaryStillOnSourceIsNotReady() {
        toHandoffPending()
        auto(2)
        facts = PrimaryTakeoverFacts(physicalIndex = 1, isReady = true, positionMs = 193_000L)
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimaryReady),
            runtime.executeHandoff(key),
        )
        assertEquals(0, backend.resets)
    }

    @Test fun nonAutoReasonsAreIgnored() {
        toHandoffPending()
        listOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
        ).forEach { observeCrossfadeNaturalTransition(runtime, 2, it) }
        primaryOnTarget()
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.NaturalTransition),
            runtime.executeHandoff(key),
        )
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets)
    }

    @Test fun autoOntoTheWrongIndexIsIgnored() {
        toHandoffPending()
        auto(3)
        primaryOnTarget()
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.NaturalTransition),
            runtime.executeHandoff(key),
        )
    }

    // ── cancellation / stale ────────────────────────────────────────────────────

    @Test fun autoWithAStaleGenerationLosesOwnershipAndDoesNotHandOff() {
        toHandoffPending()
        generation = 6L
        primaryOnTarget()
        auto(2)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(reconcileCalls.isEmpty())
    }

    @Test fun autoAfterCancellationIsInert() {
        toHandoffPending()
        runtime.cancel(CrossfadeCancelReason.Pause)
        val resets = backend.resets
        val writes = primaryWrites.size
        primaryOnTarget()
        auto(2)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(resets, backend.resets)
        assertEquals(writes, primaryWrites.size)
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(key))
    }

    @Test fun autoWithNoActiveCrossfadeLeavesNormalPlaybackAlone() {
        primaryOnTarget()
        auto(2)
        advanceCrossfadeHandoff(runtime)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertTrue(reconcileCalls.isEmpty())
        observeCrossfadeNaturalTransition(null, 2, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) // gate false: null runtime
    }

    @Test fun bufferingAfterAutoDoesNotLoseOwnershipButBeforeAutoItStillCancels() {
        toHandoffPending()
        auto(2)
        isPlaying = false // the primary is repositioning (BUFFERING)
        primaryOnTarget(ready = false)
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimaryReady),
            runtime.executeHandoff(key),
        )
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets)
    }

    @Test fun pauseBeforeAutoStillCancelsViaTheSnapshotFallback() {
        toHandoffPending()
        isPlaying = false
        assertEquals(CrossfadeHandoffExecutionResult.Cancelled(CrossfadeCancelReason.Pause), runtime.executeHandoff(key))
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    // ── takeover, freshness, ordering ───────────────────────────────────────────

    @Test fun readyOnTargetSeeksToTheFreshPositionThenTakesOverInOrder() {
        toHandoffPending()
        backend.snapshotPositionMs = 7_250L // the secondary kept playing since the terminal snapshot (6_000)
        logicalIndex = 2
        primaryOnTarget(positionMs = 100L)
        auto(2)
        assertEquals(1, reconcileCalls.size)
        assertEquals(7_250L, reconcileCalls.single().second.positionMs) // fresh, not 6_000
        assertEquals(key, reconcileCalls.single().first)
        assertEquals(listOf("seek:7250"), events) // nothing reset or restored yet
        assertTrue(runtime.state is CrossfadeState.HandoffPending)

        backend.snapshotPositionMs = 7_320L
        primaryOnTarget(positionMs = 7_260L) // the reposition landed
        advanceCrossfadeHandoff(runtime) // e.g. the READY callback
        assertEquals(listOf("seek:7250", "p:1.0", "s:reset"), events)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, reconcileCalls.size) // no second reposition
    }

    @Test fun nothingIsResetOrRestoredWhileTheRepositionIsInFlight() {
        toHandoffPending()
        backend.snapshotPositionMs = 7_250L
        primaryOnTarget(positionMs = 100L)
        auto(2)
        repeat(3) { // primary has not landed yet: no second seek, no effects
            assertEquals(
                CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek),
                runtime.executeHandoff(key),
            )
        }
        assertEquals(1, reconcileCalls.size)
        assertEquals(0, backend.resets)
        assertEquals(0f, primaryWrites.last(), 1e-6f)
    }

    @Test fun stalePositionIsNeverTakenOverEvenAfterSeveralRepositions() {
        toHandoffPending()
        backend.snapshotPositionMs = 9_000L
        primaryOnTarget(positionMs = 7_250L)
        auto(2) // first reposition (target 9000)
        repeat(4) { step ->
            // whether the reposition is in flight or landed-but-behind (secondary moved on to ~9.8 s+), a takeover is never taken
            primaryOnTarget(positionMs = 8_700L)
            backend.snapshotPositionMs = 9_800L + step * 100L
            val result = runtime.executeHandoff(key)
            assertTrue(result is CrossfadeHandoffExecutionResult.Awaiting)
        }
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets) // secondary alive
        assertEquals(0f, primaryWrites.last(), 1e-6f) // primary still silent
        assertFalse(events.contains("p:1.0"))
    }

    @Test fun takeoverOnlyOnceTheTimelineIsContinuous() {
        toHandoffPending()
        backend.snapshotPositionMs = 9_000L
        primaryOnTarget(positionMs = 7_250L)
        auto(2) // reposition to 9000 issued
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        // reported position 7250 vs secondary 9000 after landing: forbidden to succeed
        primaryOnTarget(positionMs = 7_250L)
        assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek), runtime.executeHandoff(key))
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        // later: fresh secondary 9150, primary 9050 -> within tolerance -> success allowed
        backend.snapshotPositionMs = 9_150L
        primaryOnTarget(positionMs = 9_050L)
        assertEquals(CrossfadeHandoffExecutionResult.Succeeded, runtime.executeHandoff(key))
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun pauseDuringTakeoverCarriesTheBestKnownPositionBeforeCleanup() {
        toHandoffPending()
        backend.snapshotPositionMs = 8_000L
        primaryOnTarget(positionMs = 100L) // materially behind, handoff still pending
        logicalIndex = 2
        // the AUTO fact arrives but the primary cannot be repositioned yet (controller not propagated) -> still pending
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
        auto(2)
        reconcileResult = CrossfadePrimaryReconciliationResult.Succeeded
        events.clear()
        reconcileCalls.clear()
        recoverCrossfadeFromExplicitPause(runtime) // the user pauses
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, reconcileCalls.size)
        assertEquals(8_000L, reconcileCalls.single().second.positionMs) // not left anchored to the older primary position
        assertEquals(listOf("seek:8000", "p:1.0", "s:reset"), events) // carry first, then restore, then abandon
    }

    @Test fun pauseDuringTakeoverWithAMateriallyAheadPrimarySeeksBackToTheAudibleTimeline() {
        toHandoffPending()
        backend.snapshotPositionMs = 8_000L
        primaryOnTarget(positionMs = 10_000L) // the silent primary leads the audible secondary
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
        auto(2)
        reconcileResult = CrossfadePrimaryReconciliationResult.Succeeded
        events.clear()
        reconcileCalls.clear()
        recoverCrossfadeFromExplicitPause(runtime)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, reconcileCalls.size)
        assertEquals(8_000L, reconcileCalls.single().second.positionMs) // back to the last audible B position, not 10 s
        assertEquals(listOf("seek:8000", "p:1.0", "s:reset"), events)
    }

    @Test fun pauseWithASlightlyAheadPrimaryWithinToleranceDoesNotSeek() {
        toHandoffPending()
        backend.snapshotPositionMs = 8_000L
        primaryOnTarget(positionMs = 100L)
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
        auto(2)
        primaryOnTarget(positionMs = 8_200L) // slightly ahead, within tolerance, handoff still pending
        reconcileCalls.clear()
        events.clear()
        recoverCrossfadeFromExplicitPause(runtime)
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(listOf("p:1.0", "s:reset"), events)
    }

    @Test fun pauseWithAnAlreadyContinuousPrimaryDoesNotSeek() {
        toHandoffPending()
        backend.snapshotPositionMs = 8_000L
        primaryOnTarget(positionMs = 100L)
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
        auto(2)
        primaryOnTarget(positionMs = 7_900L)
        events.clear()
        reconcileCalls.clear()
        recoverCrossfadeFromExplicitPause(runtime)
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(listOf("p:1.0", "s:reset"), events)
    }

    @Test fun nonPauseCancellationDoesNotCarryAPosition() {
        toHandoffPending()
        backend.snapshotPositionMs = 8_000L
        primaryOnTarget(positionMs = 100L)
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
        auto(2)
        reconcileCalls.clear()
        runtime.cancel(CrossfadeCancelReason.ManualNavigation)
        assertTrue(reconcileCalls.isEmpty())
    }

    @Test fun pauseBeforeTheAutoTransitionDoesNotCarryAPosition() {
        toHandoffPending() // the target is not yet the logical track: no carry
        primaryOnTarget(positionMs = 100L)
        runtime.cancel(CrossfadeCancelReason.Pause)
        assertTrue(reconcileCalls.isEmpty())
    }

    @Test fun alreadyPositionedPrimaryTakesOverWithoutASeek() {
        toHandoffPending()
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_000L)
        auto(2)
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun controllerNotYetPropagatedIsRetriedNotFailed() {
        toHandoffPending()
        backend.snapshotPositionMs = 7_250L
        primaryOnTarget(positionMs = 100L)
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
        auto(2)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets)
        reconcileResult = CrossfadePrimaryReconciliationResult.Succeeded
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek),
            runtime.executeHandoff(key),
        )
        assertEquals(2, reconcileCalls.size) // the retry did not consume the reposition budget
    }

    @Test fun otherRejectionsFailClosedWithRestoreThenAbandon() {
        toHandoffPending()
        backend.snapshotPositionMs = 7_250L
        primaryOnTarget(positionMs = 100L)
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PlayerQueueDirty)
        auto(2)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(listOf("seek:7250", "p:1.0", "s:reset"), events)
    }

    @Test fun staleHandoffAfterSuccessIsInactive() {
        toHandoffPending()
        primaryOnTarget(positionMs = 6_000L)
        backend.snapshotPositionMs = 6_100L
        auto(2)
        assertEquals(CrossfadeState.Idle, runtime.state)
        val resets = backend.resets
        val writes = primaryWrites.size
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(key))
        assertEquals(resets, backend.resets)
        assertEquals(writes, primaryWrites.size)
    }

    // ── AUTO racing the terminal tick ───────────────────────────────────────────

    @Test fun autoBeforeTheTerminalTickIsRememberedForTheExactKey() {
        toFading()
        logicalIndex = 2 // the controller already moved to the target
        auto(2)
        assertTrue(runtime.state is CrossfadeState.Fading) // ownership tolerated the target index
        assertEquals(FadeTickExecutionResult.Applied, runtime.executeFadeTick(key, clockNow + 1_000L))
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(key, clockNow + 6_000L))
        backend.snapshotPositionMs = 6_050L
        primaryOnTarget(positionMs = 6_000L)
        assertEquals(CrossfadeHandoffExecutionResult.Succeeded, runtime.executeHandoff(key))
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun rememberedAutoDoesNotLeakIntoALaterTransition() {
        toFading()
        auto(2)
        runtime.cancel(CrossfadeCancelReason.Pause)
        // a brand new pending transition of the very same key must start with no remembered AUTO
        clockNow += 1_000L
        isPlaying = true
        logicalIndex = 1
        graph.timingDriver.stop()
        graph.timingDriver.start()
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Armed)
        backend.ready()
        position = startA + 1_000L
        scheduler.runNext()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(key, clockNow + 6_000L))
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.NaturalTransition),
            runtime.executeHandoff(key),
        )
    }

    // ── Repeat ALL wrap and duplicates ──────────────────────────────────────────

    @Test fun repeatAllWrapHandsOffToTheFirstOccurrence() {
        repeat = RepeatMode.ALL
        logicalIndex = 3
        key = CrossfadeTransitionKey(5L, 3, 0)
        toHandoffPending()
        logicalIndex = 0
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_000L)
        auto(0)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(listOf("p:1.0", "s:reset"), events)
    }

    @Test fun duplicateSongsAreHandedOffByPosition() {
        queue = listOf(song(1), song(2), song(2), song(4)) // index 1 and 2 are the same song
        toHandoffPending()
        auto(1) // the same song id but the wrong occurrence
        primaryOnTarget(positionMs = 6_000L)
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.NaturalTransition),
            runtime.executeHandoff(key),
        )
        logicalIndex = 2
        backend.snapshotPositionMs = 6_100L
        auto(2)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    // ── driver integration (real state polling, no delays) ──────────────────────

    @Test fun driverKeepsReevaluatingARealStateAndResumesPollingAfterTakeover() {
        toFading()
        clockNow += 6_000L
        scheduler.runNext() // terminal tick + immediate handoff attempt -> waiting for AUTO
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(CrossfadeTimingDriver.FADE_TICK_INTERVAL_MS, scheduler.activeNow.single().delayMs)
        events.clear()
        scheduler.runNext() // nothing happened to the player: still waiting, no effects
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertTrue(events.isEmpty())
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_000L)
        auto(2) // AUTO observed and the primary is ready: takeover happens immediately, event driven
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        scheduler.runNext() // the already-pending pulse now resumes the pre-fade cadence
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, scheduler.activeNow.single().delayMs)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
