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
        // CF-2L4: a physically realistic secondary: its raw position advances 1 ms per monotonic ms unless frozen (or a coarse model overrides it).
        private var secondaryBaseMs = 6_000L
        private var secondaryBaseAtMs = clockNow
        var snapshotPositionMs: Long
            get() = coarseSecondary?.invoke() ?: (secondaryBaseMs + if (positionsFrozen || secondaryStalled) 0L else clockNow - secondaryBaseAtMs)
            set(value) { secondaryBaseMs = value; secondaryBaseAtMs = clockNow }
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean { gains += gain; order += "sg:$gain"; return setGainResult }
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? =
            SecondaryHandoffSnapshot(snapshotPositionMs, 180_000L, isAdvancing = !secondaryStalled)
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
    // CF-2L4: the primary's raw position is live too (advances with the monotonic clock) unless frozen or overridden by a coarse model.
    private var factsBase: PrimaryTakeoverFacts? = null
    private var factsBaseAtMs = 0L
    private var positionsFrozen = false
    private var primaryFrozen = false

    // CF-2L4 correction: a BUFFERING / stalled stream keeps a valid snapshot but its raw position freezes and it is not advancing.
    private var primaryStalled = false
    private var secondaryStalled = false

    private fun stallPrimary() { facts = facts; primaryStalled = true }
    private fun resumePrimary() { facts = facts; primaryStalled = false }
    private fun stallSecondary() { backend.snapshotPositionMs = backend.snapshotPositionMs; secondaryStalled = true }
    private fun resumeSecondary() { backend.snapshotPositionMs = backend.snapshotPositionMs; secondaryStalled = false }

    /** Freezes a stream at its CURRENT raw value (it keeps reporting that same position while monotonic time passes). */
    private fun freezePositions() {
        facts = facts
        backend.snapshotPositionMs = backend.snapshotPositionMs
        positionsFrozen = true
    }
    private var coarseSecondary: (() -> Long)? = null
    private var coarsePrimary: (() -> Long)? = null
    private var facts: PrimaryTakeoverFacts?
        get() = factsBase?.let { base ->
            base.copy(
                positionMs = coarsePrimary?.invoke() ?: (base.positionMs + if (positionsFrozen || primaryFrozen || primaryStalled) 0L else clockNow - factsBaseAtMs),
                isAdvancing = base.isAdvancing && !primaryStalled,
            )
        }
        set(value) { factsBase = value; factsBaseAtMs = clockNow }
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
        confirmClocks()
        assertTrue(runtime.isNaturalTransferInProgress)
    }

    /**
     * CF-2L4: the first evaluation only anchors both position clocks (provisional); the next one, after the monotonic clock moved and
     * both raw positions advanced, calibrates them and is the first that can trust a projected comparison.
     */
    private fun confirmClocks(stepMs: Long = 20L) {
        clockNow += stepMs
        runtime.executeHandoff(key)
    }

    /** CF-2L4: after a primary reposition the primary clock was invalidated, so it needs one more evaluation to calibrate. */
    private fun confirmClocksAfterSeek(stepMs: Long = 20L) {
        confirmClocks(stepMs)
        confirmClocks(stepMs)
    }

    private fun auto(index: Int) = observeCrossfadeNaturalTransition(runtime, index, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

    private fun primaryOnTarget(ready: Boolean = true, positionMs: Long = 100L) {
        facts = PrimaryTakeoverFacts(physicalIndex = key.toPlaybackIndex, isReady = ready, positionMs = positionMs, isAdvancing = ready)
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
        facts = PrimaryTakeoverFacts(physicalIndex = 1, isReady = true, positionMs = 193_000L, isAdvancing = true)
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
        confirmClocksAfterSeek() // the primary clock re-calibrates after the seek; only then may the soft transfer BEGIN
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
        confirmClocksAfterSeek()
        assertTrue(runtime.isNaturalTransferInProgress)
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
        confirmClocks()
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
        confirmClocks()
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
        runtime.executeHandoff(key) // anchors both clocks
        confirmClocks()
        assertTrue(runtime.isNaturalTransferInProgress)
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
        confirmClocks()
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
        confirmClocks()
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
        auto(2) // AUTO observed: both position clocks are only anchored (provisional), so nothing may begin yet
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(events.isEmpty()) // nothing written, nothing reset yet
        clockNow += 20L
        scheduler.runNext() // the pulse calibrates both clocks and the soft transfer BEGINS at the finer cadence
        assertTrue(runtime.isNaturalTransferInProgress)
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
        assertTrue(reconcileCalls.isEmpty()) // unconfirmed clocks never seek on a sub-granularity difference
        confirmClocks() // both clocks are now trustworthy: the PROJECTED difference is real
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
        val start = transferLogs.first { it.startsWith("TRANSFER_START") }
        assertTrue(start.contains("rawDeltaMs=40") && start.contains("projectedDeltaMs=40"))
        assertTrue(start.contains("rawPrimaryMs=6080") && start.contains("projectedPrimaryMs=6080"))
        assertTrue(start.contains("positionDecision=primary:Calibrated/secondary:Calibrated"))
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
        facts = PrimaryTakeoverFacts(physicalIndex = 3, isReady = true, positionMs = 6_100L, isAdvancing = true)
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
            CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PositionConfidence),
            runtime.executeHandoff(key),
        )
        assertEquals(listOf("sg:1.0", "pg:0.0"), order) // secondary back to full FIRST, then primary silent
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets)
        assertTrue(transferLogs.any { it.startsWith("TRANSFER_ABORT reason=raw_discontinuity") })
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
        primaryOnTarget(positionMs = 6_100L + 20L + 75L - 90L) // a slightly stale raw (inside the agreement window): coarse, not divergent
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
        confirmClocks()
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
        confirmClocks()
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
        assertEquals(6_195L, reconcileCalls.single().second.positionMs) // B never rewinds because the owner changed
        assertEquals(listOf("seek:6195", "p:1.0", "s:reset"), events)
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
        clockNow += 20L
        scheduler.runNext() // the pulse calibrates both clocks and begins the transfer
        assertTrue(runtime.isNaturalTransferInProgress)
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
        confirmClocksAfterSeek()
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
        assertFalse(s.positionClocksActive)
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
        clockNow += 20L
        scheduler.runNext() // calibrates both clocks and begins the transfer
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
        val request = transferLogs.first { it.startsWith("RECONCILE_REQUEST") }
        assertTrue(request.contains("stage=initial") && request.contains("requestedPositionMs=7250"))
        assertTrue(request.contains("rawPrimaryMs=100") && request.contains("rawSecondaryMs=7250"))
        assertTrue(transferLogs.first { it.startsWith("RECONCILE_RESULT") }.endsWith("succeeded"))
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
        confirmClocksAfterSeek()
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
        confirmClocks() // reconciliation resumes once the clocks are trustworthy again
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
        clockNow += 20L
        scheduler.runNext() // the pulse calibrates both clocks and begins the transfer (at most one callback ever pending)
        assertTrue(runtime.isNaturalTransferInProgress)
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

    // ── CF-2L4: bounded monotonic projected position clock ──────────────────────────────────────────

    private fun logCount(prefix: String) = transferLogs.count { it.startsWith(prefix) }

    /**
     * Synthetic model of the CF-2L2 PHYSICAL failure CLASS (not any user media): both players genuinely advance 1 ms per ms, but their
     * raw positions are frozen between sparse updates (a coarse refresh every ~250 ms with different phases), the primary is 10 ms ahead
     * of the secondary in truth, and the driver evaluates every 16 ms. Raw position of a stream = its true position at its last update.
     */
    private fun installCoarseClocks(t0: Long, secondaryStepMs: Long, secondaryFirstUpdateAfterT0Ms: Long, primaryStepMs: Long, primaryFirstUpdateAfterT0Ms: Long) {
        val trueSecondary = 6_000L // true secondary position at t0
        val truePrimary = trueSecondary + 10L
        fun lastUpdate(now: Long, step: Long, firstAfter: Long): Long {
            val firstUpdate = t0 + firstAfter
            return if (now < firstUpdate) firstUpdate - step else firstUpdate + ((now - firstUpdate) / step) * step
        }
        coarseSecondary = { trueSecondary + (lastUpdate(clockNow, secondaryStepMs, secondaryFirstUpdateAfterT0Ms) - t0) }
        coarsePrimary = { truePrimary + (lastUpdate(clockNow, primaryStepMs, primaryFirstUpdateAfterT0Ms) - t0) }
    }

    @Test fun coarseRawRefreshesAt16msCadenceCompleteOneTransferWithoutAnAbortOrASeekStorm() {
        toFading()
        clockNow += 6_000L
        scheduler.runNext() // terminal tick -> HandoffPending
        val t0 = clockNow
        logicalIndex = 2
        primaryOnTarget(positionMs = 6_010L)
        installCoarseClocks(t0, secondaryStepMs = 250L, secondaryFirstUpdateAfterT0Ms = 20L, primaryStepMs = 260L, primaryFirstUpdateAfterT0Ms = 240L)
        auto(2) // AUTO at t0: both raw positions are stale and only anchored (provisional)
        var maxRawDelta = 0L
        var evaluations = 0
        while (runtime.state is CrossfadeState.HandoffPending && evaluations < 200) {
            clockNow += 16L
            evaluations++
            scheduler.runNext() // one driver evaluation
            if (runtime.isNaturalTransferInProgress) maxRawDelta = maxOf(maxRawDelta, kotlin.math.abs(coarseSecondary!!() - coarsePrimary!!()))
            assertTrue(scheduler.activeNow.size <= 1)
        }
        // the physical pattern reproduced: a naive RAW comparison would exceed even the abort tolerance
        assertTrue("raw delta $maxRawDelta", maxRawDelta > NATURAL_TRANSFER_ABORT_TOLERANCE_MS)
        // ... yet the projected comparison stayed continuous: one start, no abort, no reseek, completion, settlement
        assertEquals(1, logCount("TRANSFER_START"))
        assertEquals(0, logCount("TRANSFER_ABORT"))
        assertEquals(1, logCount("TRANSFER_COMPLETE"))
        assertTrue(reconcileCalls.isEmpty()) // no repeated reconciliation: START/ABORT/SEEK can no longer cycle
        assertEquals(0, logCount("RECONCILE_REQUEST"))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(settled())
        // the entry decision used PROJECTED, not raw, positions
        val start = transferLogs.first { it.startsWith("TRANSFER_START") }
        val rawDelta = Regex("rawDeltaMs=(-?\\d+)").find(start)!!.groupValues[1].toLong()
        val projectedDelta = Regex("projectedDeltaMs=(-?\\d+)").find(start)!!.groupValues[1].toLong()
        assertTrue("raw $rawDelta", kotlin.math.abs(rawDelta) > NATURAL_TRANSFER_ENTRY_TOLERANCE_MS)
        assertTrue("projected $projectedDelta", kotlin.math.abs(projectedDelta) <= NATURAL_TRANSFER_ENTRY_TOLERANCE_MS)
        assertEquals(0, logCount("POSITION_DISCONTINUITY"))
    }

    @Test fun aRefreshArrivingMidTransferIsAbsorbedNotTreatedAsDivergence() {
        // the secondary refreshes (+250 coarse step) a few ms after the transfer started: the old raw comparison aborted here
        toFading()
        clockNow += 6_000L
        scheduler.runNext()
        val t0 = clockNow
        logicalIndex = 2
        primaryOnTarget(positionMs = 6_010L)
        installCoarseClocks(t0, secondaryStepMs = 250L, secondaryFirstUpdateAfterT0Ms = 20L, primaryStepMs = 260L, primaryFirstUpdateAfterT0Ms = 240L)
        auto(2)
        var refreshedDuringTransfer = false
        var guard = 0
        while (runtime.state is CrossfadeState.HandoffPending && guard++ < 200) {
            clockNow += 16L
            scheduler.runNext()
            if (runtime.isNaturalTransferInProgress && transferLogs.any { it.startsWith("POSITION_REFRESH stream=secondary decision=Refreshed") }) refreshedDuringTransfer = true
        }
        assertTrue(refreshedDuringTransfer)
        assertEquals(0, logCount("TRANSFER_ABORT"))
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun primaryProjectionIsInvalidatedOnTheReconciliationSeekAndTheSecondaryKeepsItsClock() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 7_250L
        primaryOnTarget(positionMs = 100L)
        auto(2) // evaluation 1: both anchored; the primary is grossly behind so ONE reposition is issued and the primary clock dropped
        assertEquals(1, reconcileCalls.size)
        assertEquals(1, logCount("POSITION_ANCHOR stream=primary"))
        assertEquals(1, logCount("POSITION_ANCHOR stream=secondary"))
        primaryOnTarget(positionMs = 7_270L) // the seek landed
        confirmClocks() // evaluation 2
        assertEquals(2, logCount("POSITION_ANCHOR stream=primary")) // re-anchored after the seek, never projected through it
        assertEquals(1, logCount("POSITION_ANCHOR stream=secondary")) // the continuous secondary kept its clock
        assertTrue(transferLogs.any { it.startsWith("POSITION_REFRESH stream=secondary decision=Calibrated") })
    }

    @Test fun anUnconfirmedPrimaryNeverSeeksOnASubGranularityDifference() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 6_300L
        primaryOnTarget(positionMs = 6_100L) // 200 ms apart: inside anything coarse sampling can explain
        auto(2)
        assertTrue(reconcileCalls.isEmpty())
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PositionConfidence), runtime.executeHandoff(key))
    }

    @Test fun aPinnedPrimaryThatNeverAdvancesNeverBecomesAudible() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        primaryFrozen = true // the primary reports the same position forever (not rendering) while time passes
        auto(2)
        repeat(60) {
            clockNow += 50L
            runtime.executeHandoff(key)
        }
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(backend.gains.isEmpty())
        assertEquals(0f, primaryWrites.last(), 1e-6f)
        assertTrue(reconcileCalls.size <= 1) // only a GROSS (>350 ms) lag may reposition, once, then it waits for the landing: never a storm
        assertEquals(0, backend.resets)
    }

    @Test fun projectedDivergenceBeyondTheAbortToleranceStillAbortsEvenWhenEveryRawSampleLooksPlausible() {
        beginTransfer()
        // the primary decoder runs fast: each raw refresh is only +90 ms ahead of the timeline (inside the agreement window),
        // but the PROJECTED difference keeps growing until it exceeds the unchanged 200 ms abort tolerance
        var aborted = false
        var step = 0
        while (!aborted && step++ < 20) {
            clockNow += 10L
            facts = facts!!.copy(positionMs = facts!!.positionMs + 90L)
            runtime.executeHandoff(key)
            aborted = transferLogs.any { it.startsWith("TRANSFER_ABORT reason=projected_divergence") }
        }
        assertTrue(aborted)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(0, backend.resets)
        val line = transferLogs.first { it.startsWith("TRANSFER_ABORT reason=projected_divergence") }
        val projectedDelta = Regex("projectedDeltaMs=(-?\\d+)").find(line)!!.groupValues[1].toLong()
        assertTrue(kotlin.math.abs(projectedDelta) > NATURAL_TRANSFER_ABORT_TOLERANCE_MS)
    }

    @Test fun anExpiredProjectionAbortsTheTransferAndCanNeverCompleteTheHandoff() {
        beginTransfer()
        freezePositions() // no raw refresh from either stream any more
        clockNow += NATURAL_HANDOFF_MAX_PROJECTION_AGE_MS + NATURAL_TAKEOVER_TRANSFER_DURATION_MS + 1L // far past both the envelope and the age
        val result = runtime.executeHandoff(key)
        assertTrue(result is CrossfadeHandoffExecutionResult.Awaiting)
        assertFalse(result == CrossfadeHandoffExecutionResult.Succeeded)
        assertTrue(transferLogs.any { it.startsWith("TRANSFER_ABORT reason=position_projection_expired") })
        assertTrue(transferLogs.any { it.startsWith("POSITION_EXPIRED") })
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(0, backend.resets)
        assertEquals(1f, backend.gains.last(), 0f) // the secondary is the audible authority again
        assertEquals(0f, primaryWrites.last(), 1e-6f)
    }

    @Test fun positionClocksAreClearedBySuccessCancellationAndFailure() {
        beginTransfer()
        assertTrue(runtime.settlementSnapshot().positionClocksActive)
        finishTransfer()
        assertFalse(runtime.settlementSnapshot().positionClocksActive) // success
    }

    @Test fun positionClocksAreClearedByCancellation() {
        beginTransfer()
        assertTrue(runtime.settlementSnapshot().positionClocksActive)
        runtime.cancel(CrossfadeCancelReason.ManualNavigation)
        assertFalse(runtime.settlementSnapshot().positionClocksActive)
        assertTrue(settled())
    }

    @Test fun positionClocksAreClearedByHandoffFailureCleanup() {
        beginTransfer()
        backend.setGainResult = false
        clockNow += 75L
        assertTrue(runtime.executeHandoff(key) is CrossfadeHandoffExecutionResult.Failed)
        assertFalse(runtime.settlementSnapshot().positionClocksActive)
        assertTrue(settled())
    }

    @Test fun positionClocksAreClearedOnCloseAndStaleCallbacksCannotReviveThem() {
        beginTransfer()
        finishTransfer()
        auto(2)
        advanceCrossfadeHandoff(runtime)
        runtime.executeHandoff(key)
        assertFalse(runtime.settlementSnapshot().positionClocksActive) // late AUTO / READY / pulse never re-anchor a dead transition
        runtime.close()
        assertFalse(runtime.settlementSnapshot().positionClocksActive)
        assertTrue(settled())
    }

    @Test fun aLaterDistinctTransitionStartsFromFreshPositionAnchors() {
        beginTransfer()
        finishTransfer()
        assertEquals(1, logCount("POSITION_ANCHOR stream=primary"))
        assertEquals(1, logCount("POSITION_ANCHOR stream=secondary"))
        // the next transition (2 -> 3): arm, ready, fade, hand off - with its own brand-new clocks
        logicalIndex = 2
        key = CrossfadeTransitionKey(5L, 2, 3)
        position = 1_000L
        scheduler.runNext() // pre-fade pulse arms 2 -> 3
        assertTrue(runtime.state is CrossfadeState.Armed)
        backend.ready()
        position = 194_000L + 1_000L
        scheduler.runNext() // Ready -> fade window due
        clockNow += 6_000L
        scheduler.runNext() // fade ticks -> terminal -> HandoffPending (waiting for AUTO)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertFalse(runtime.settlementSnapshot().positionClocksActive) // nothing inherited from 1 -> 2
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        logicalIndex = 3
        auto(3)
        assertEquals(2, logCount("POSITION_ANCHOR stream=primary"))
        assertEquals(2, logCount("POSITION_ANCHOR stream=secondary"))
        confirmClocks()
        assertTrue(runtime.isNaturalTransferInProgress)
    }

    @Test fun pauseCarryStillUsesTheExistingPhysicalComparison() {
        // CF-2L1 Pause carry is intentionally unchanged: raw physical positions, 350 ms threshold, one same-item seek
        beginTransfer()
        clockNow += 75L
        runtime.executeHandoff(key)
        primaryOnTarget(positionMs = 6_195L - NATURAL_TAKEOVER_MAX_LAG_MS - 1L)
        reconcileCalls.clear()
        recoverCrossfadeFromExplicitPause(runtime)
        assertEquals(1, reconcileCalls.size)
        assertEquals(6_195L, reconcileCalls.single().second.positionMs)
    }

    // ── CF-2L4 correction: projection requires current advancement eligibility ─────────────────────

    private fun assertAbortReason(reason: String) = assertTrue(transferLogs.any { it.startsWith("TRANSFER_ABORT reason=$reason") })

    @Test fun aSecondaryThatBeginsBufferingMidTransferAbortsAndNeverCompletes() {
        beginTransfer()
        clockNow += 40L
        runtime.executeHandoff(key) // mid transfer, healthy
        order.clear()
        stallSecondary() // BUFFERING: still a valid owned snapshot, raw position frozen, not advancing
        clockNow += 40L
        assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PositionConfidence), runtime.executeHandoff(key))
        assertAbortReason("secondary_not_advancing")
        assertEquals(listOf("sg:1.0", "pg:0.0"), order) // the secondary is the audible authority again
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets) // never abandoned
        // its projection was DROPPED, not carried forward through the stall
        assertTrue(transferLogs.last { it.startsWith("TRANSFER_ABORT") }.contains("projectedSecondaryMs=n/a"))
        assertTrue(transferLogs.any { it.startsWith("POSITION_NOT_ADVANCING stream=secondary") })
    }

    @Test fun aPrimaryThatStopsAdvancingMidTransferAbortsAndNeverCompletes() {
        beginTransfer()
        clockNow += 40L
        runtime.executeHandoff(key)
        order.clear()
        stallPrimary()
        clockNow += 40L
        assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PositionConfidence), runtime.executeHandoff(key))
        assertAbortReason("primary_not_advancing")
        assertEquals(listOf("sg:1.0", "pg:0.0"), order)
        assertFalse(runtime.isNaturalTransferInProgress)
        assertEquals(0, backend.resets)
        assertTrue(transferLogs.last { it.startsWith("TRANSFER_ABORT") }.contains("projectedPrimaryMs=n/a"))
        assertTrue(transferLogs.any { it.startsWith("POSITION_NOT_ADVANCING stream=primary") })
    }

    @Test fun aStalledSecondaryCannotReachHandoffSucceededNoMatterHowMuchTimePasses() {
        beginTransfer()
        stallSecondary()
        repeat(40) {
            clockNow += 25L
            assertFalse(runtime.executeHandoff(key) == CrossfadeHandoffExecutionResult.Succeeded)
        }
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets)
        assertEquals(0, logCount("TRANSFER_COMPLETE"))
        assertEquals(0, logCount("HANDOFF_SETTLED"))
    }

    @Test fun aStalledPrimaryCannotReachHandoffSucceededEither() {
        beginTransfer()
        stallPrimary()
        repeat(40) {
            clockNow += 25L
            assertFalse(runtime.executeHandoff(key) == CrossfadeHandoffExecutionResult.Succeeded)
        }
        assertTrue(runtime.state is CrossfadeState.HandoffPending)
        assertEquals(0, backend.resets)
        assertEquals(0, logCount("TRANSFER_COMPLETE"))
    }

    @Test fun aTransferCannotBeginWhileTheSecondaryIsNotAdvancing() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        stallSecondary()
        auto(2)
        repeat(6) { confirmClocks() }
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(backend.gains.isEmpty())
        assertEquals(0f, primaryWrites.last(), 1e-6f)
        assertTrue(reconcileCalls.isEmpty()) // no seek merely because it is not advancing
        assertEquals(1, logCount("POSITION_NOT_ADVANCING stream=secondary")) // bounded: logged once per loss
        assertEquals(CrossfadeHandoffExecutionResult.Awaiting(CrossfadeHandoffWait.PositionConfidence), runtime.executeHandoff(key))
    }

    @Test fun aTransferCannotBeginWhileThePrimaryIsNotAdvancing() {
        toHandoffPending()
        logicalIndex = 2
        backend.snapshotPositionMs = 6_100L
        primaryOnTarget(positionMs = 6_100L)
        stallPrimary() // READY-looking but not progressing
        auto(2)
        repeat(6) { confirmClocks() }
        assertFalse(runtime.isNaturalTransferInProgress)
        assertTrue(backend.gains.isEmpty())
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(1, logCount("POSITION_NOT_ADVANCING stream=primary"))
    }

    @Test fun afterAdvancementResumesTheAffectedClockReanchorsAndReearnsConfidenceFromRealMovement() {
        beginTransfer()
        assertEquals(1, logCount("POSITION_ANCHOR stream=secondary"))
        stallSecondary()
        clockNow += 20L
        runtime.executeHandoff(key) // abort: the secondary clock is dropped
        assertFalse(runtime.isNaturalTransferInProgress)
        clockNow += 20L
        resumeSecondary() // advancing again (a short stall, so the streams are still within the entry tolerance)
        runtime.executeHandoff(key)
        assertEquals(2, logCount("POSITION_ANCHOR stream=secondary")) // a FRESH provisional anchor, not the old projection
        assertFalse(runtime.isNaturalTransferInProgress) // no transfer on a provisional anchor
        confirmClocks() // real raw movement observed: calibrated, confidence regained
        assertTrue(runtime.isNaturalTransferInProgress)
        assertEquals(2, logCount("TRANSFER_START"))
    }

    @Test fun anAdvancingPairStillCompletesTheCoarseClockRegressionWithoutAnyNotAdvancingEvent() {
        toFading()
        clockNow += 6_000L
        scheduler.runNext()
        val t0 = clockNow
        logicalIndex = 2
        primaryOnTarget(positionMs = 6_010L)
        installCoarseClocks(t0, secondaryStepMs = 250L, secondaryFirstUpdateAfterT0Ms = 20L, primaryStepMs = 260L, primaryFirstUpdateAfterT0Ms = 240L)
        auto(2)
        var guard = 0
        while (runtime.state is CrossfadeState.HandoffPending && guard++ < 200) {
            clockNow += 16L
            scheduler.runNext()
        }
        assertEquals(1, logCount("TRANSFER_START"))
        assertEquals(0, logCount("TRANSFER_ABORT"))
        assertEquals(0, logCount("POSITION_NOT_ADVANCING"))
        assertTrue(reconcileCalls.isEmpty())
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(settled())
    }

    @Test fun advancementBookkeepingIsClearedBySettlement() {
        beginTransfer()
        stallSecondary()
        clockNow += 20L
        runtime.executeHandoff(key)
        runtime.cancel(CrossfadeCancelReason.ManualNavigation)
        assertTrue(settled())
        assertFalse(runtime.settlementSnapshot().positionClocksActive)
    }

    @Test fun productionRolloutGateRemainsFalse() {
        assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
