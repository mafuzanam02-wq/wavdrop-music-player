package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val order = mutableListOf<String>() // CF-2L2: ordered gain/reset log across both players
    private val transferLogs = mutableListOf<String>()
    private var primaryWriteResult = true

    private inner class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        val gains = mutableListOf<Float>()
        var setGainResult = true
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var snapshotPositionMs = 6_000L
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean { gains += gain; order += "sg:$gain"; return setGainResult }
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = SecondaryHandoffSnapshot(snapshotPositionMs, 180_000L)
        override fun reset() { resets++; events += "s:reset"; order += "reset" }
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
        primaryGainBackend = PrimaryGainBackend { primaryWrites += it; events += "p:$it"; order += "pg:$it"; primaryWriteResult },
        reconcilePrimary = { _, _ -> error("legacy cross-item reconciliation must not run in the natural handoff") },
        scheduler = scheduler,
        clock = { clockNow },
        configuredDurationMsProvider = { 6_000L },
        primaryDurationMs = { 200_000L },
        primaryPositionMs = { position },
        naturalTransferLog = { transferLogs += it },
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
        order.clear()
        backend.gains.clear()
    }

    /** CF-2L2: lets the whole internal envelope elapse on the monotonic clock and runs the final driver-style evaluation. */
    private fun finishTransfer() {
        clockNow += NATURAL_TAKEOVER_TRANSFER_DURATION_MS
        assertEquals(CrossfadeHandoffExecutionResult.Succeeded, runtime.executeHandoff(key))
    }

    /** Qualifies the primary [deltaMs] behind the fresh secondary (6_100) so the soft transfer has just begun. */
    private fun beginTransfer(deltaMs: Long = 0L) {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L - deltaMs)
        auto(2)
        assertTrue(runtime.isNaturalTransferInProgress)
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
        advanceCrossfadeHandoff(runtime) // e.g. the READY callback: only BEGINS the soft transfer
        assertEquals(listOf("seek:7250"), events)
        assertTrue(runtime.isNaturalTransferInProgress)
        finishTransfer()
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
        // later: fresh secondary 9100, primary 9050 -> within the entry tolerance -> the soft transfer may begin
        backend.snapshotPositionMs = 9_100L
        primaryOnTarget(positionMs = 9_050L)
        assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer), runtime.executeHandoff(key))
        finishTransfer()
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
        primaryOnTarget(positionMs = 6_050L)
        auto(2)
        assertTrue(reconcileCalls.isEmpty())
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        finishTransfer()
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
        primaryOnTarget(positionMs = 6_050L)
        backend.snapshotPositionMs = 6_100L
        auto(2)
        finishTransfer()
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
        assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer), runtime.executeHandoff(key))
        finishTransfer()
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
        primaryOnTarget(positionMs = 6_050L)
        auto(0)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        finishTransfer()
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
        primaryOnTarget(positionMs = 6_050L)
        auto(2)
        finishTransfer()
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
        primaryOnTarget(positionMs = 6_050L)
        auto(2) // AUTO observed and the primary is ready and continuous: the soft transfer BEGINS (event driven)
        assertTrue(runtime.isNaturalTransferInProgress)
        assertTrue(events.isEmpty()) // nothing written, nothing reset yet
        scheduler.runNext() // the pending pulse now runs at the finer transfer cadence
        assertEquals(CrossfadeTimingDriver.TRANSFER_TICK_INTERVAL_MS, scheduler.activeNow.single().delayMs)
        clockNow += NATURAL_TAKEOVER_TRANSFER_DURATION_MS
        scheduler.runNext() // final evaluation completes the transfer
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(listOf("p:1.0", "s:reset"), events.takeLast(2))
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, scheduler.activeNow.single().delayMs)
    }

    // ── CF-2L2: continuity-qualified soft ownership transfer ────────────────────

    private fun assertNoTransferAtDelta(delta: Long) {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L - delta)
        auto(2)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(backend.gains.isEmpty()) // neither side's gain touched: primary stays silent
        assertEquals(0f, primaryWrites.last(), 1e-6f)
        assertEquals(1, reconcileCalls.size) // reconciliation continues instead
    }

    @Test fun noTransferStartsWhenThePrimaryIsBehindBeyondTheEntryTolerance() =
        assertNoTransferAtDelta(NATURAL_TRANSFER_ENTRY_TOLERANCE_MS + 1)

    @Test fun noTransferStartsWhenThePrimaryIsAheadBeyondTheEntryTolerance() =
        assertNoTransferAtDelta(-(NATURAL_TRANSFER_ENTRY_TOLERANCE_MS + 1))

    @Test fun theOldThreeHundredFiftyMsBoundIsNotTransferPermission() = assertNoTransferAtDelta(NATURAL_TAKEOVER_MAX_LAG_MS)

    private fun assertTransferStartsAtDelta(delta: Long) {
        beginTransfer(delta)
        assertTrue(reconcileCalls.isEmpty())
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
    }

    @Test fun transferStartsWhenAligned() = assertTransferStartsAtDelta(0L)

    @Test fun transferStartsAtTheEntryToleranceBehind() = assertTransferStartsAtDelta(NATURAL_TRANSFER_ENTRY_TOLERANCE_MS)

    @Test fun transferStartsAtTheEntryToleranceAhead() = assertTransferStartsAtDelta(-NATURAL_TRANSFER_ENTRY_TOLERANCE_MS)

    @Test fun transferBeginsFromPrimarySilentAndSecondaryFullWithoutResettingEitherPlayer() {
        beginTransfer(40L)
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer),
            runtime.executeHandoff(key),
        ) // same instant: progress 0
        assertTrue(backend.gains.all { it == 1f })
        assertEquals(0f, primaryWrites.last(), 1e-6f)
        assertEquals(0, backend.resets)
        assertTrue(transferLogs.first().startsWith("TRANSFER_START"))
        assertTrue(transferLogs.first().contains("deltaMs=40"))
        assertTrue(transferLogs.first().contains("primaryPositionMs=6060"))
    }

    @Test fun midpointUsesComplementaryEqualPowerGainsSecondaryWrittenFirst() {
        beginTransfer()
        clockNow += NATURAL_TAKEOVER_TRANSFER_DURATION_MS / 2
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer),
            runtime.executeHandoff(key),
        )
        val mid = CrossfadeGainCurve.equalPower(0.5f)
        assertEquals(mid.outgoing, backend.gains.last(), 1e-6f) // secondary falls on the outgoing curve
        assertEquals(mid.incoming, primaryWrites.last(), 1e-6f) // primary rises on the incoming curve
        assertEquals(1.0, (backend.gains.last() * backend.gains.last() + primaryWrites.last() * primaryWrites.last()).toDouble(), 1e-5)
        assertEquals(listOf("sg:${mid.outgoing}", "pg:${mid.incoming}"), order) // secondary FIRST, then primary
        assertTrue(transferLogs.any { it.startsWith("TRANSFER_TICK") && it.contains("progress=0.5") })
    }

    @Test fun completionSilencesSecondaryBeforeRestoringPrimaryAndAbandoningIt() {
        beginTransfer()
        finishTransfer()
        assertEquals(listOf("sg:0.0", "pg:1.0", "reset"), order) // never 1/1 then reset, never an intentional 0/0
        assertEquals(0f, backend.gains.last(), 0f) // exactly silent before reset
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(transferLogs.any { it.startsWith("TRANSFER_COMPLETE") })
        assertTrue(transferLogs.last().startsWith("HANDOFF_SETTLED")) // only after the full cleanup
    }

    @Test fun secondaryIsNeverAbandonedWhileStillAudibleAndSuccessOnlyFollowsTheCompletedTransfer() {
        beginTransfer()
        var elapsed = 0L
        while (elapsed < NATURAL_TAKEOVER_TRANSFER_DURATION_MS - 10L) {
            elapsed += 10L
            clockNow += 10L
            val result = runtime.executeHandoff(key)
            assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer), result)
            assertEquals(0, backend.resets) // not reset mid-transfer
            assertTrue(runtime.state is CrossfadeState.HandoffPending)
        }
        assertTrue(backend.gains.last() > 0f) // still audible when the last tick ran
        finishTransfer()
        assertEquals("reset", order.last())
        assertEquals(0f, backend.gains.last(), 0f)
        // monotone envelope: the secondary never rises and the primary never falls while transferring
        val sec = backend.gains
        val pri = primaryWrites.takeLast(sec.size)
        assertTrue(sec.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(pri.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun primaryIsNotAudibleBeforeAutoReadyExactOccurrenceAndContinuity() {
        toHandoffPending()
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        advanceCrossfadeHandoff(runtime) // no AUTO yet
        assertFalse(runtime.isNaturalTransferInProgress)
        logicalIndex = 2
        primaryOnTarget(ready = false, positionMs = 6_100L)
        auto(2) // AUTO but not READY
        assertFalse(runtime.isNaturalTransferInProgress)
        facts = PrimaryTakeoverFacts(physicalIndex = 3, isReady = true, positionMs = 6_100L)
        advanceCrossfadeHandoff(runtime) // READY but the wrong occurrence
        assertFalse(runtime.isNaturalTransferInProgress)
        primaryOnTarget(positionMs = 3_000L)
        advanceCrossfadeHandoff(runtime) // right occurrence, READY, not continuous
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(backend.gains.isEmpty())
        assertEquals(0f, primaryWrites.last(), 1e-6f)
    }

    @Test fun divergenceDuringTransferReturnsAuthorityToTheSecondaryAndNeverSucceeds() {
        beginTransfer()
        clockNow += 75L
        runtime.executeHandoff(key) // mid transfer, both partially audible
        order.clear()
        primaryOnTarget(positionMs = 6_100L - NATURAL_TRANSFER_ABORT_TOLERANCE_MS - 1L) // the primary jumped away
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek),
            runtime.executeHandoff(key),
        )
        assertEquals(listOf("sg:1.0", "pg:0.0"), order) // secondary back to full FIRST, then primary silent
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets)
        assertTrue(transferLogs.any { it.startsWith("TRANSFER_ABORT reason=divergence") })
        // reconciliation resumes (a reposition is issued) and even a lot of elapsed time cannot complete anything
        reconcileCalls.clear()
        clockNow += 10_000L
        assertTrue(runtime.executeHandoff(key) is CrossfadeHandoffExecutionResult.Awaiting)
        assertEquals(1, reconcileCalls.size)
        assertEquals(0, backend.resets)
    }

    @Test fun smallDriftDuringTheEnvelopeDoesNotAbort() {
        beginTransfer()
        clockNow += 75L
        primaryOnTarget(positionMs = 6_100L - NATURAL_TRANSFER_ABORT_TOLERANCE_MS) // at the abort tolerance, not beyond it
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.OwnershipTransfer),
            runtime.executeHandoff(key),
        )
        assertTrue(runtime.isNaturalTransferInProgress)
    }

    @Test fun primaryNoLongerReadyMidTransferReturnsAuthorityToTheSecondary() {
        beginTransfer()
        clockNow += 75L
        runtime.executeHandoff(key)
        order.clear()
        primaryOnTarget(ready = false)
        assertEquals(
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimaryReady),
            runtime.executeHandoff(key),
        )
        assertEquals(listOf("sg:1.0", "pg:0.0"), order)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(0, backend.resets)
    }

    @Test fun noRetryHistoryOrElapsedTimeBypassesContinuity() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 9_000L
        primaryOnTarget(positionMs = 7_000L)
        auto(2)
        repeat(20) {
            clockNow += 60_000L
            primaryOnTarget(positionMs = if (it % 2 == 0) 7_000L else 8_700L) // in flight, then landed-but-behind: never within the entry tolerance
            assertTrue(runtime.executeHandoff(key) is CrossfadeHandoffExecutionResult.Awaiting)
            assertFalse(runtime.isNaturalTransferInProgress)
        }
        assertTrue(backend.gains.isEmpty())
        assertEquals(0f, primaryWrites.last(), 1e-6f)
        assertEquals(0, backend.resets)
    }

    @Test fun duplicateOccurrenceTransfersOnlyForTheExactPosition() {
        queue = listOf(song(1), song(2), song(2), song(4))
        toHandoffPending()
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        auto(1) // same song id, wrong occurrence
        assertFalse(runtime.isNaturalTransferInProgress)
        logicalIndex = 2
        auto(2)
        assertTrue(runtime.isNaturalTransferInProgress)
        finishTransfer()
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun repeatAllWrapTransfersOntoTheFirstOccurrence() {
        repeat = RepeatMode.ALL
        logicalIndex = 3
        key = CrossfadeTransitionKey(5L, 3, 0)
        toHandoffPending()
        logicalIndex = 0
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        auto(0)
        assertTrue(runtime.isNaturalTransferInProgress)
        clockNow += 75L
        runtime.executeHandoff(key)
        finishTransfer()
        assertEquals("reset", order.last())
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun staleGenerationDuringTransferCannotComplete() {
        beginTransfer()
        clockNow += 75L
        runtime.executeHandoff(key)
        generation = 6L
        clockNow += NATURAL_TAKEOVER_TRANSFER_DURATION_MS
        assertEquals(
            CrossfadeHandoffExecutionResult.Cancelled(CrossfadeCancelReason.QueueMutation),
            runtime.executeHandoff(key),
        )
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(1f, primaryWrites.last(), 0f) // not stuck lowered
        assertEquals(1, backend.resets) // no ghost secondary
    }

    private fun assertCancellationDuringTransferIsClean(reason: CrossfadeCancelReason) {
        beginTransfer()
        clockNow += 75L
        runtime.executeHandoff(key)
        val resetsBefore = backend.resets
        runtime.cancel(reason)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(resetsBefore + 1, backend.resets)
        assertEquals(listOf("pg:1.0", "reset"), order.takeLast(2)) // primary restored, then secondary reset
    }

    @Test fun manualNavigationDuringTransferIsClean() = assertCancellationDuringTransferIsClean(CrossfadeCancelReason.ManualNavigation)

    @Test fun queueMutationDuringTransferIsClean() = assertCancellationDuringTransferIsClean(CrossfadeCancelReason.QueueMutation)

    @Test fun repeatChangeDuringTransferIsClean() = assertCancellationDuringTransferIsClean(CrossfadeCancelReason.RepeatChanged)

    @Test fun pauseDuringTransferCarriesAMateriallyDivergentPrimaryToTheAudiblePosition() {
        beginTransfer()
        clockNow += 75L
        runtime.executeHandoff(key)
        primaryOnTarget(positionMs = 6_100L - NATURAL_TAKEOVER_MAX_LAG_MS - 100L) // far behind, not yet re-evaluated
        reconcileCalls.clear()
        events.clear()
        recoverCrossfadeFromExplicitPause(runtime)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, reconcileCalls.size)
        assertEquals(6_100L, reconcileCalls.single().second.positionMs) // B never rewinds because the owner changed
        assertEquals(listOf("seek:6100", "p:1.0", "s:reset"), events)
    }

    @Test fun pauseDuringTransferWithAContinuousPrimaryDoesNotSeek() {
        beginTransfer(30L)
        clockNow += 75L
        runtime.executeHandoff(key)
        reconcileCalls.clear()
        recoverCrossfadeFromExplicitPause(runtime)
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
    }

    @Test fun secondaryGainWriteFailureDuringTransferFailsClosedWithoutSilencingThePrimary() {
        beginTransfer()
        backend.setGainResult = false
        clockNow += 75L
        val result = runtime.executeHandoff(key)
        assertEquals(CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.SecondaryGainWriteFailed), result)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f) // the primary was never lowered further and ends restored
        assertEquals(1, backend.resets)
        assertFalse(runtime.isNaturalTransferInProgress)
    }

    @Test fun primaryGainWriteFailureDuringTransferFailsClosed() {
        beginTransfer()
        primaryWriteResult = false
        clockNow += 75L
        val result = runtime.executeHandoff(key)
        assertEquals(CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.PrimaryGainWriteFailed), result)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun driverAdvancesTheEnvelopeThroughEvaluationsOnlyAtTheFinerCadence() {
        toFading()
        clockNow += 6_000L
        scheduler.runNext() // terminal tick -> HandoffPending
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        logicalIndex = 2
        auto(2)
        assertTrue(runtime.isNaturalTransferInProgress)
        scheduler.runNext()
        assertEquals(CrossfadeTimingDriver.TRANSFER_TICK_INTERVAL_MS, scheduler.activeNow.single().delayMs)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        clockNow += 80L
        scheduler.runNext()
        assertTrue(backend.gains.last() in 0.01f..0.99f) // mid-envelope, driven by the driver evaluation
        assertEquals(0, backend.resets)
        clockNow += 80L
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals("reset", order.last())
    }

    @Test fun transferLogsCarryNoPathsOrTitles() {
        beginTransfer(10L)
        clockNow += 75L
        runtime.executeHandoff(key)
        finishTransfer()
        assertTrue(transferLogs.isNotEmpty())
        assertTrue(transferLogs.none { it.contains("content://") || it.contains("S2") })
    }

    // ── CF-2L3: lifecycle settlement, stale-callback inertness, scheduler boundedness ──────────────

    private fun settled() = isCrossfadeFullySettled(runtime.settlementSnapshot())

    @Test fun successfulHandoffIsFullySettledSynchronouslyBeforeAnythingIsScheduled() {
        beginTransfer()
        clockNow += NATURAL_TAKEOVER_TRANSFER_DURATION_MS
        assertEquals(CrossfadeHandoffExecutionResult.Succeeded, runtime.executeHandoff(key))
        assertTrue(settled()) // proven at return, not at some later pulse
    }

    @Test fun successfulHandoffClearsEveryPieceOfNaturalBookkeepingAndOwnership() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 7_250L
        primaryOnTarget(positionMs = 100L)
        auto(2) // a reposition: seek target + lead are now set
        val mid = runtime.settlementSnapshot()
        assertTrue(mid.hasSeekTarget && mid.hasNaturalObservedKey && mid.primaryGainOwnerKey != null && mid.secondaryOwned && mid.secondaryStarted)
        backend.snapshotPositionMs = 7_300L
        primaryOnTarget(positionMs = 7_260L)
        advanceCrossfadeHandoff(runtime)
        assertTrue(runtime.settlementSnapshot().transferActive)
        finishTransfer()
        val s = runtime.settlementSnapshot()
        assertEquals(CrossfadeState.Idle, s.state)
        assertFalse(s.hasNaturalObservedKey)
        assertFalse(s.hasSeekTarget)
        assertEquals(0L, s.seekLeadMs)
        assertFalse(s.transferActive)
        assertNull(s.primaryGainOwnerKey)
        assertFalse(s.secondaryOwned)
        assertFalse(s.secondaryStarted)
        assertEquals(0, s.reconcileRequestCount)
        assertTrue(isCrossfadeFullySettled(s))
        assertTrue(transferLogs.last().startsWith("HANDOFF_SETTLED"))
        assertTrue(transferLogs.last().contains("settled=true"))
        assertTrue(transferLogs.last().contains("primaryGainOwned=false") && transferLogs.last().contains("secondaryOwned=false"))
    }

    @Test fun staleAutoReadyAndPendingAdvanceAfterSuccessAreInert() {
        beginTransfer()
        finishTransfer()
        val resets = backend.resets
        val gainsSeen = backend.gains.size
        val primarySeen = primaryWrites.size
        val seeks = reconcileCalls.size
        auto(2) // late AUTO for the dead transition
        advanceCrossfadeHandoff(runtime) // late READY
        runtime.advancePendingHandoff()
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(key))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(resets, backend.resets)
        assertEquals(gainsSeen, backend.gains.size)
        assertEquals(primarySeen, primaryWrites.size)
        assertEquals(seeks, reconcileCalls.size) // no reconciliation after HandoffSucceeded
        assertTrue(settled())
    }

    @Test fun staleTimingPulseAfterSuccessCannotTouchTheOldHandoffAndAFutureTransitionStillArms() {
        toFading()
        clockNow += 6_000L
        scheduler.runNext() // terminal tick -> HandoffPending
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        logicalIndex = 2
        auto(2)
        scheduler.runNext() // transfer evaluation at progress 0
        clockNow += NATURAL_TAKEOVER_TRANSFER_DURATION_MS
        scheduler.runNext() // completes
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(settled())
        val seeks = reconcileCalls.size
        val resets = backend.resets
        val primarySeen = primaryWrites.size
        position = 1_000L // a fresh track: nothing near its fade window
        scheduler.runNext() // the next normal pre-fade pulse
        assertEquals(seeks, reconcileCalls.size)
        assertEquals(resets, backend.resets)
        assertEquals(primarySeen, primaryWrites.size)
        val armed = runtime.state as CrossfadeState.Armed // a later DISTINCT transition still works
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), armed.key)
        assertTrue(armed.key != key)
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, scheduler.activeNow.single().delayMs)
    }

    @Test fun onlyOneReconciliationIsInFlightAtATime() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 7_250L
        primaryOnTarget(positionMs = 100L)
        auto(2)
        repeat(8) {
            assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PrimarySeek), runtime.executeHandoff(key))
            advanceCrossfadeHandoff(runtime) // READY churn must not multiply seeks
        }
        assertEquals(1, reconcileCalls.size)
        assertEquals(1, runtime.settlementSnapshot().reconcileRequestCount)
        assertTrue(transferLogs.first().startsWith("RECONCILE_REQUEST"))
        assertTrue(transferLogs.first().contains("stage=initial") && transferLogs.first().contains("requestedPositionMs=7250"))
        assertTrue(transferLogs[1].startsWith("RECONCILE_RESULT") && transferLogs[1].endsWith("succeeded"))
    }

    @Test fun rejectedReconciliationIsLoggedWithItsReason() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 7_250L
        primaryOnTarget(positionMs = 100L)
        reconcileResult = CrossfadePrimaryReconciliationResult.Rejected(CrossfadePrimaryReconciliationRejection.PhysicalIndexMismatch)
        auto(2)
        assertTrue(transferLogs.any { it.startsWith("RECONCILE_RESULT") && it.contains("rejected reason=PhysicalIndexMismatch") })
    }

    @Test fun noReconciliationSeekIsEmittedOnceTheTransferHasStarted() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 100L)
        auto(2) // seek issued
        primaryOnTarget(positionMs = 6_100L) // landed and aligned
        advanceCrossfadeHandoff(runtime)
        assertTrue(runtime.isNaturalTransferInProgress)
        val seeks = reconcileCalls.size
        repeat(5) {
            clockNow += 20L
            advanceCrossfadeHandoff(runtime)
        }
        assertEquals(seeks, reconcileCalls.size) // no seek during the envelope
        finishTransfer()
        assertEquals(seeks, reconcileCalls.size) // nor at completion
    }

    @Test fun transferAbortClearsTransferStateBeforeReconciliationResumes() {
        beginTransfer()
        primaryOnTarget(positionMs = 6_100L - NATURAL_TRANSFER_ABORT_TOLERANCE_MS - 50L)
        runtime.executeHandoff(key) // abort
        val s = runtime.settlementSnapshot()
        assertFalse(s.transferActive) // cleared first
        assertEquals(0, s.reconcileRequestCount) // and the abort itself issued no seek
        assertTrue(s.state is CrossfadeState.HandoffPending)
        runtime.executeHandoff(key) // reconciliation resumes on the next evaluation
        assertEquals(1, runtime.settlementSnapshot().reconcileRequestCount)
    }

    // cadence / scheduler boundedness

    private fun transferViaDriver() {
        toFading()
        clockNow += 6_000L
        scheduler.runNext()
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        logicalIndex = 2
        auto(2)
        assertTrue(runtime.isNaturalTransferInProgress)
        scheduler.runNext() // at most one callback ever pending
        assertEquals(CrossfadeTimingDriver.TRANSFER_TICK_INTERVAL_MS, scheduler.activeNow.single().delayMs)
    }

    @Test fun afterSuccessTheDriverIsSettledWithExactlyOnePreFadeCallbackAndNoTransferCadence() {
        transferViaDriver()
        clockNow += NATURAL_TAKEOVER_TRANSFER_DURATION_MS
        scheduler.runNext()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(settled()) // already settled when the next callback was scheduled
        val pending = scheduler.activeNow.single()
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, pending.delayMs)
        assertFalse(runtime.isNaturalTransferInProgress)
        repeat(3) { // stale callbacks cannot create a second loop
            auto(2)
            advanceCrossfadeHandoff(runtime)
            assertEquals(1, scheduler.activeNow.size)
        }
    }

    @Test fun cancellationDuringTransferLeavesNoSixteenMsLoop() {
        transferViaDriver()
        runtime.cancel(CrossfadeCancelReason.ManualNavigation)
        assertTrue(settled())
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(1, scheduler.activeNow.size) // the one already-pending pulse
        scheduler.runNext()
        assertEquals(CrossfadeTimingDriver.PRE_FADE_POLL_INTERVAL_MS, scheduler.activeNow.single().delayMs) // never 16 ms again
    }

    @Test fun handoffFailureDuringTransferLeavesNoSpinningDriverAndIsSettled() {
        transferViaDriver()
        backend.setGainResult = false
        clockNow += 40L
        scheduler.runNext() // the tick's secondary write fails -> HandoffFailed
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(scheduler.activeNow.isEmpty()) // the driver halted
        assertTrue(settled())
    }

    @Test fun stopAndCloseClearThePendingCallbackEvenMidTransfer() {
        transferViaDriver()
        assertEquals(1, scheduler.activeNow.size)
        graph.timingDriver.stop()
        assertTrue(scheduler.activeNow.isEmpty())
        graph.timingDriver.start()
        assertEquals(1, scheduler.activeNow.size)
        graph.timingDriver.close()
        assertTrue(scheduler.activeNow.isEmpty())
    }

    // cancellation matrix: every cancel reason ends Idle and fully settled

    private fun assertCancelSettlesDuringTransfer(reason: CrossfadeCancelReason) {
        beginTransfer()
        clockNow += 75L
        runtime.executeHandoff(key)
        assertTrue(runtime.settlementSnapshot().transferActive)
        runtime.cancel(reason)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue("reason=$reason", settled())
        assertFalse(runtime.isNaturalTransferInProgress)
    }

    @Test fun pauseSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.Pause)
    @Test fun seekSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.Seek)
    @Test fun manualNavigationSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.ManualNavigation)
    @Test fun repeatChangeSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.RepeatChanged)
    @Test fun shuffleChangeSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.ShuffleChanged)
    @Test fun queueMutationSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.QueueMutation)
    @Test fun controllerDisconnectSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.ControllerDisconnected)
    @Test fun primaryErrorSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.PlaybackError)
    @Test fun equalizerInvalidationSettles() = assertCancelSettlesDuringTransfer(CrossfadeCancelReason.PlanInvalidated)
    @Test fun audioFocusInterruptionSettles() {
        beginTransfer()
        recoverCrossfadeFromPrimaryInterruption(runtime) // CF-2F5: Media3 focus / noisy / suppression recovery
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(settled())
    }

    @Test fun handoffFailureCleanupIsSettledWhenRestorationSucceeds() {
        beginTransfer()
        backend.setGainResult = false
        clockNow += 75L
        assertTrue(runtime.executeHandoff(key) is CrossfadeHandoffExecutionResult.Failed)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(settled())
    }

    @Test fun aFailedPrimaryRestoreIsNeverReportedAsSettled() {
        beginTransfer()
        primaryWriteResult = false // the primary gain cannot be restored
        runtime.cancel(CrossfadeCancelReason.ManualNavigation)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertNotNull(runtime.settlementSnapshot().primaryGainOwnerKey) // ownership deliberately retained for retry
        assertFalse(settled())
    }

    @Test fun abandoningTheSecondaryMeansNoActiveSecondaryOwnership() {
        beginTransfer()
        assertTrue(runtime.settlementSnapshot().secondaryOwned)
        finishTransfer()
        assertFalse(runtime.settlementSnapshot().secondaryOwned)
        assertFalse(runtime.settlementSnapshot().secondaryStarted)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
