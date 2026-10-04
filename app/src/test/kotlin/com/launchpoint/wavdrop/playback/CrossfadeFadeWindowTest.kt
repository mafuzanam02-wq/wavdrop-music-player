package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeFadeWindowTest {

    // -- Pure decision -----------------------------------------------------------

    private fun due(
        key: CrossfadeTransitionKey,
        latenessMs: Long = 0L,
        effectiveDurationMs: Long = 6_000L,
        startAtPositionMs: Long = 194_000L,
    ) = FadeWindowObservation.Due(key, effectiveDurationMs, startAtPositionMs, latenessMs)

    private fun decide(position: Long, start: Long = 194_000L, effective: Long = 6_000L) =
        decideFadeWindow(start, effective, position)

    @Test fun beforeStartIsWaiting() {
        assertEquals(FadeWindowDecision.Waiting, decide(0L))
        assertEquals(FadeWindowDecision.Waiting, decide(193_999L))
    }

    @Test fun exactlyStartIsDueWithZeroLateness() =
        assertEquals(FadeWindowDecision.Due(0L), decide(194_000L))

    @Test fun smallThresholdCrossingIsDueWithoutEquality() {
        // previous observation 193_800, next 194_250 (coarse ~500 ms ticker)
        assertEquals(FadeWindowDecision.Waiting, decide(193_800L))
        assertEquals(FadeWindowDecision.Due(250L), decide(194_250L))
    }

    @Test fun invalidInputsFailClosed() {
        assertEquals(FadeWindowDecision.Invalid, decide(-1L))
        assertEquals(FadeWindowDecision.Invalid, decide(Long.MIN_VALUE))
        assertEquals(FadeWindowDecision.Invalid, decideFadeWindow(-1L, 6_000L, 10L))
        assertEquals(FadeWindowDecision.Invalid, decideFadeWindow(100L, 0L, 10L))
        assertEquals(FadeWindowDecision.Invalid, decideFadeWindow(100L, -5L, 10L))
    }

    @Test fun lateBoundaryIsDeterministic() {
        // tolerance = min(1500, 6000/2) = 1500
        assertEquals(
            FadeWindowDecision.Due(MAX_FADE_START_LATENESS_MS),
            decide(194_000L + MAX_FADE_START_LATENESS_MS),
        )
        assertEquals(FadeWindowDecision.Missed, decide(194_000L + MAX_FADE_START_LATENESS_MS + 1))
    }

    @Test fun toleranceIsBoundedByHalfTheEffectiveDuration() {
        // effective 1000 -> tolerance 500
        assertEquals(FadeWindowDecision.Due(500L), decideFadeWindow(10_000L, 1_000L, 10_500L))
        assertEquals(FadeWindowDecision.Missed, decideFadeWindow(10_000L, 1_000L, 10_501L))
    }

    @Test fun obviouslyLatePositionIsMissedAndExtremeValuesDoNotOverflow() {
        assertEquals(FadeWindowDecision.Missed, decide(199_000L))
        assertEquals(FadeWindowDecision.Missed, decide(Long.MAX_VALUE))
        assertEquals(FadeWindowDecision.Waiting, decideFadeWindow(Long.MAX_VALUE, 6_000L, 5L))
    }

    // -- Runtime harness ---------------------------------------------------------

    private class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var releases = 0
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun reset() { resets++ }
        override fun release() { releases++ }
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = null
        override fun setGain(gain: Float): Boolean = true

        val starts = mutableListOf<Float>()
        var startResult = true
        var onStart: (() -> Unit)? = null
        override fun start(initialGain: Float): Boolean {
            starts += initialGain
            onStart?.invoke()
            return startResult
        }
        fun ready(attempt: Long, durationMs: Long = 180_000L) = callbacks!!.onReady(attempt, durationMs)
        fun error(attempt: Long) = callbacks!!.onError(attempt)
    }

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1, 0), song(2, 1), song(3, 2), song(4, 3))

    private fun snapshot(
        queue: List<Song> = this.queue,
        index: Int? = 1,
        generation: Long = 5L,
        repeat: RepeatMode = RepeatMode.OFF,
        playing: Boolean = true,
        external: Boolean = false,
        dirty: Boolean = false,
        connected: Boolean = true,
    ) = CrossfadeRuntimeSnapshot(
        queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = index,
        repeatMode = repeat, shuffleEnabled = false, isPlaying = playing, isExternalPlayback = external,
        playerQueueNeedsSync = dirty, controllerConnected = connected,
    )

    private var snap = snapshot()
    private class FakePrimary : PrimaryGainBackend {
        val gains = mutableListOf<Float>()
        var failWhen: (Float) -> Boolean = { false }
        var throwWhen: (Float) -> Boolean = { false }
        var onWrite: (Float) -> Unit = {}
        override fun setGain(gain: Float): Boolean {
            gains += gain
            onWrite(gain)
            if (throwWhen(gain)) throw IllegalStateException("boom")
            return !failWhen(gain)
        }
    }

    private val backend = FakeBackend()
    private val primary = FakePrimary()
    private val runtime = CrossfadePreparationRuntime({ snap }, { backend }, primaryGainBackend = primary)
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L // configured 6000 on 200_000 ms tracks

    private fun evaluate(configured: Long = 6_000L, current: Long? = null) =
        runtime.evaluatePreparation(configured, current)

    private fun makeReady() {
        evaluate()
        backend.ready(backend.prepared.last())
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    private fun observe(position: Long, key: CrossfadeTransitionKey = keyA) =
        runtime.observePrimaryPosition(key, position)

    // -- Runtime: Waiting / Due / one-shot ---------------------------------------

    @Test fun armedDoesNotReportDue() {
        evaluate()
        assertTrue(runtime.state is CrossfadeState.Armed)
        assertEquals(FadeWindowObservation.Inactive, observe(startA))
    }

    @Test fun idleReportsInactive() = assertEquals(FadeWindowObservation.Inactive, observe(startA))

    @Test fun readyBeforeWindowStaysReady() {
        makeReady()
        assertEquals(FadeWindowObservation.Waiting, observe(startA - 1))
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    @Test fun readyAtWindowReportsDueOnceAndStaysReady() {
        makeReady()
        assertEquals(due(keyA), observe(startA))
        assertTrue(runtime.state is CrossfadeState.Ready) // never Fading in this slice
    }

    @Test fun crossingThresholdBetweenObservationsReportsDueOnce() {
        makeReady()
        assertEquals(FadeWindowObservation.Waiting, observe(startA - 200))
        assertEquals(due(keyA, 250L), observe(startA + 250))
    }

    @Test fun repeatedObservationsDoNotDuplicateDue() {
        makeReady()
        assertEquals(due(keyA), observe(startA))
        assertEquals(FadeWindowObservation.AlreadyReported, observe(startA + 500))
        assertEquals(FadeWindowObservation.AlreadyReported, observe(startA + 1_000))
    }

    @Test fun invalidPositionFailsClosedAndAbandonsSecondary() {
        makeReady()
        assertEquals(FadeWindowObservation.Cancelled(CrossfadeCancelReason.PlanInvalidated), observe(-1L))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets) // abandoned exactly once
        // Nothing can fire until a fresh preparation is explicitly armed.
        assertEquals(FadeWindowObservation.Inactive, observe(startA))
        assertEquals(1, backend.resets)
    }

    @Test fun runtimeDuePreservesLatenessFromThePureDecision() {
        for (late in listOf(0L, 250L, 1_000L, MAX_FADE_START_LATENESS_MS)) {
            val b = FakeBackend()
            val r = CrossfadePreparationRuntime({ snap }, { b })
            r.evaluatePreparation(6_000L, null)
            b.ready(b.prepared.last())
            val pure = decideFadeWindow(startA, 6_000L, startA + late) as FadeWindowDecision.Due
            assertEquals(late, pure.latenessMs)
            assertEquals(due(keyA, pure.latenessMs), r.observePrimaryPosition(keyA, startA + late))
        }
    }

    @Test fun firstDueReportsLatenessThenAlreadyReported() {
        makeReady()
        assertEquals(FadeWindowObservation.Waiting, observe(startA - 200)) // 193_800
        assertEquals(due(keyA, 250L), observe(startA + 250)) // 194_250
        assertEquals(FadeWindowObservation.AlreadyReported, observe(startA + 750))
    }

    @Test fun sameKeySamePlanRemainsOneShotAcrossIdempotentEvaluation() {
        makeReady()
        assertEquals(due(keyA), observe(startA))
        evaluate() // identical plan: retained, not re-armed
        assertEquals(FadeWindowObservation.AlreadyReported, observe(startA + 500))
        assertEquals(1, backend.prepared.size)
    }

    @Test fun sameKeyChangedPlanGetsFreshTimingOwnership() {
        makeReady()
        assertEquals(due(keyA), observe(startA))
        evaluate(configured = 4_000L) // same key, effective 4000 / start 196000
        backend.ready(backend.prepared.last())
        assertEquals(CrossfadeState.Ready(keyA, 4_000L, 196_000L), runtime.state)
        assertEquals(FadeWindowObservation.Waiting, observe(startA)) // old start no longer applies
        assertEquals(due(keyA, 0L, effectiveDurationMs = 4_000L, startAtPositionMs = 196_000L), observe(196_000L))
    }

    @Test fun reArmedSameKeyAndPlanAfterCancellationReportsDueAgain() {
        makeReady()
        assertEquals(due(keyA), observe(startA))
        runtime.cancel(CrossfadeCancelReason.Pause)
        evaluate()
        backend.ready(backend.prepared.last())
        assertEquals(due(keyA), observe(startA)) // a fresh preparation is a fresh window
    }

    // -- Runtime: staleness ------------------------------------------------------

    @Test fun staleKeyCannotReportDue() {
        makeReady()
        assertEquals(FadeWindowObservation.Inactive, observe(startA, key = CrossfadeTransitionKey(5L, 0, 1)))
        assertEquals(FadeWindowObservation.Inactive, observe(startA, key = keyA.copy(queueGeneration = 4L)))
    }

    @Test fun observationForReplacedTransitionDoesNotAffectNewOne() {
        makeReady()
        snap = snapshot(index = 2)
        evaluate() // A replaced by B = (5, 2, 3)
        backend.ready(backend.prepared.last())
        val keyB = CrossfadeTransitionKey(5L, 2, 3)
        assertEquals(FadeWindowObservation.Inactive, observe(startA, key = keyA)) // late observation for A
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (runtime.state as CrossfadeState.Ready).key)
        assertEquals(due(keyB), observe(startA, key = keyB))
    }

    @Test fun generationChangeCancelsAndNeverReportsDueForOldGeneration() {
        makeReady()
        snap = snapshot(generation = 6L)
        assertEquals(FadeWindowObservation.Cancelled(CrossfadeCancelReason.QueueMutation), observe(startA))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    private fun assertCancelsOn(newSnapshot: CrossfadeRuntimeSnapshot, reason: CrossfadeCancelReason) {
        makeReady()
        snap = newSnapshot
        assertEquals(FadeWindowObservation.Cancelled(reason), observe(startA))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun occurrenceChangeCancels() = assertCancelsOn(snapshot(index = 2), CrossfadeCancelReason.ManualNavigation)
    @Test fun pauseCancels() = assertCancelsOn(snapshot(playing = false), CrossfadeCancelReason.Pause)
    @Test fun externalPlaybackCancels() = assertCancelsOn(snapshot(external = true), CrossfadeCancelReason.ExternalPlayback)
    @Test fun dirtyQueueCancels() = assertCancelsOn(snapshot(dirty = true), CrossfadeCancelReason.QueueBecameDirty)
    @Test fun controllerDisconnectCancels() = assertCancelsOn(snapshot(connected = false), CrossfadeCancelReason.ControllerDisconnected)
    @Test fun repeatOneCancels() = assertCancelsOn(snapshot(repeat = RepeatMode.ONE), CrossfadeCancelReason.RepeatChanged)

    @Test fun repeatTargetChangeCancels() {
        snap = snapshot(index = 3, repeat = RepeatMode.ALL)
        evaluate()
        backend.ready(backend.prepared.last())
        val wrapKey = CrossfadeTransitionKey(5L, 3, 0)
        snap = snapshot(index = 3, repeat = RepeatMode.OFF)
        assertEquals(
            FadeWindowObservation.Cancelled(CrossfadeCancelReason.RepeatChanged),
            runtime.observePrimaryPosition(wrapKey, startA),
        )
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    // -- Runtime: missed window --------------------------------------------------

    @Test fun missedWindowCancelsWithMissedWindow() {
        makeReady()
        assertEquals(
            FadeWindowObservation.Cancelled(CrossfadeCancelReason.MissedWindow),
            observe(startA + MAX_FADE_START_LATENESS_MS + 1),
        )
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun lateButWithinToleranceIsStillDue() {
        makeReady()
        assertEquals(due(keyA, MAX_FADE_START_LATENESS_MS), observe(startA + MAX_FADE_START_LATENESS_MS))
    }

    // -- Runtime: lifecycle / positional -----------------------------------------

    @Test fun observationsAfterCloseAreHarmless() {
        makeReady()
        runtime.close()
        assertEquals(FadeWindowObservation.Inactive, observe(startA))
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun duplicateSongQueueStaysStrictlyPositional() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        snap = snapshot(queue = dup, index = 2)
        evaluate()
        backend.ready(backend.prepared.last())
        val key = CrossfadeTransitionKey(5L, 2, 3)
        assertEquals(FadeWindowObservation.Inactive, observe(startA, key = CrossfadeTransitionKey(5L, 0, 2)))
        assertEquals(due(key), observe(startA, key = key))
    }

    @Test fun repeatAllLastToFirstRemainsPositional() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2))
        snap = snapshot(queue = dup, index = 2, repeat = RepeatMode.ALL)
        evaluate()
        backend.ready(backend.prepared.last())
        val key = CrossfadeTransitionKey(5L, 2, 0)
        assertEquals(due(key), observe(startA, key = key))
        assertEquals(FadeWindowObservation.AlreadyReported, observe(startA + 500, key = key))
    }

    // -- CF-2C4: plan-bound Due -> BeginFade bridge ----------------------------------

    private fun readyDue(late: Long = 0L): FadeWindowObservation.Due {
        makeReady()
        return observe(startA + late) as FadeWindowObservation.Due
    }

    private fun assertBridgeRejectedWithoutSideEffects(d: FadeWindowObservation.Due, now: Long = 10_000L) {
        val before = runtime.state
        val resets = backend.resets
        assertNull(runtime.beginFadeEvent(d, now))
        assertEquals(before, runtime.state)
        assertEquals(resets, backend.resets)
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun dueCarriesExactPlanFactsFromCurrentReady() {
        val d = readyDue(250L)
        assertEquals(FadeWindowObservation.Due(keyA, 6_000L, 194_000L, 250L), d)
    }

    @Test fun bridgeMapsLatenessToInitialElapsedExactly() {
        val d = readyDue(250L)
        assertEquals(CrossfadeEvent.BeginFade(keyA, 10_000L, 250L), runtime.beginFadeEvent(d, 10_000L))
    }

    @Test fun bridgeZeroLatenessGivesZeroInitialElapsed() {
        val d = readyDue(0L)
        assertEquals(CrossfadeEvent.BeginFade(keyA, 10_000L, 0L), runtime.beginFadeEvent(d, 10_000L))
        assertEquals(CrossfadeEvent.BeginFade(keyA, 0L, 0L), runtime.beginFadeEvent(d, 0L))
    }

    @Test fun bridgeAcceptsExactToleranceBoundaryAndRejectsBeyond() {
        // configured 1000 -> effective 1000, start 199000, tolerance min(1500, 500) = 500
        evaluate(configured = 1_000L)
        backend.ready(backend.prepared.last())
        assertEquals(CrossfadeState.Ready(keyA, 1_000L, 199_000L), runtime.state)
        val d = observe(199_500L) as FadeWindowObservation.Due
        assertEquals(CrossfadeEvent.BeginFade(keyA, 7L, 500L), runtime.beginFadeEvent(d, 7L))
        assertBridgeRejectedWithoutSideEffects(d.copy(latenessMs = 501L))
    }

    @Test fun bridgeRejectsNegativeMonotonicTimeWithoutCancelling() {
        val d = readyDue(250L)
        assertBridgeRejectedWithoutSideEffects(d, now = -1L)
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    @Test fun bridgeRejectsFabricatedInvalidLateness() {
        val d = readyDue(0L)
        assertBridgeRejectedWithoutSideEffects(d.copy(latenessMs = -1L))
        assertBridgeRejectedWithoutSideEffects(d.copy(latenessMs = d.effectiveDurationMs))
        assertBridgeRejectedWithoutSideEffects(d.copy(latenessMs = MAX_FADE_START_LATENESS_MS + 1))
        assertEquals(CrossfadeState.Ready(keyA, 6_000L, 194_000L), runtime.state)
    }

    @Test fun bridgeRejectsStaleKeyWithoutCancellingCurrentOwner() {
        val d = readyDue(0L)
        assertBridgeRejectedWithoutSideEffects(d.copy(key = CrossfadeTransitionKey(5L, 2, 3)))
        assertBridgeRejectedWithoutSideEffects(d.copy(key = keyA.copy(queueGeneration = 4L)))
        assertEquals(CrossfadeState.Ready(keyA, 6_000L, 194_000L), runtime.state)
    }

    @Test fun staleDueFromReplacedPlanIsHarmlessForSameKey() {
        val dueA = readyDue(0L) // plan A: 6000 @ 194000
        evaluate(configured = 4_000L) // same key, plan B: 4000 @ 196000
        backend.ready(backend.prepared.last())
        val readyB = CrossfadeState.Ready(keyA, 4_000L, 196_000L)
        assertEquals(readyB, runtime.state)
        assertBridgeRejectedWithoutSideEffects(dueA)
        assertEquals(readyB, runtime.state)
        // Plan B's own Due still bridges normally.
        val dueB = observe(196_000L) as FadeWindowObservation.Due
        assertEquals(CrossfadeEvent.BeginFade(keyA, 9L, 0L), runtime.beginFadeEvent(dueB, 9L))
    }

    private fun assertBridgeCancelsOn(newSnapshot: CrossfadeRuntimeSnapshot, reason: CrossfadeCancelReason, key: CrossfadeTransitionKey = keyA) {
        val d = readyDue(0L).copy(key = key)
        snap = newSnapshot
        assertNull(runtime.beginFadeEvent(d, 10_000L))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets) // abandoned once, via existing pre-audible cleanup
        assertTrue(backend.starts.isEmpty())
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
        // Cancellation reason is the existing ownership-loss reason.
        assertEquals(reason, crossfadeOwnershipLossReason(newSnapshot, key))
    }

    @Test fun bridgePauseCancelsAndReturnsNull() = assertBridgeCancelsOn(snapshot(playing = false), CrossfadeCancelReason.Pause)
    @Test fun bridgeGenerationChangeCancelsAndReturnsNull() = assertBridgeCancelsOn(snapshot(generation = 6L), CrossfadeCancelReason.QueueMutation)
    @Test fun bridgeOccurrenceChangeCancelsAndReturnsNull() = assertBridgeCancelsOn(snapshot(index = 2), CrossfadeCancelReason.ManualNavigation)
    @Test fun bridgeExternalPlaybackCancelsAndReturnsNull() = assertBridgeCancelsOn(snapshot(external = true), CrossfadeCancelReason.ExternalPlayback)

    @Test fun bridgeRepeatTargetChangeCancelsAndReturnsNull() {
        snap = snapshot(index = 3, repeat = RepeatMode.ALL)
        evaluate()
        backend.ready(backend.prepared.last())
        val wrapKey = CrossfadeTransitionKey(5L, 3, 0)
        val d = observe(startA, key = wrapKey) as FadeWindowObservation.Due
        snap = snapshot(index = 3, repeat = RepeatMode.OFF)
        assertNull(runtime.beginFadeEvent(d, 10_000L))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun successfulBridgeIsNonMutatingAndDeterministicWhenRepeated() {
        val d = readyDue(250L)
        val resets = backend.resets
        val first = runtime.beginFadeEvent(d, 10_000L)
        val second = runtime.beginFadeEvent(d, 10_000L)
        assertEquals(CrossfadeEvent.BeginFade(keyA, 10_000L, 250L), first)
        assertEquals(first, second)
        assertEquals(CrossfadeState.Ready(keyA, 6_000L, 194_000L), runtime.state)
        assertEquals(resets, backend.resets)
        assertEquals(1, backend.prepared.size)
        assertTrue(backend.starts.isEmpty())
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
    }

    @Test fun bridgeAfterCloseOrWhenNotReadyReturnsNull() {
        val d = readyDue(0L)
        runtime.close()
        assertNull(runtime.beginFadeEvent(d, 10_000L))
        val idle = CrossfadePreparationRuntime({ snap }, { FakeBackend() })
        assertNull(idle.beginFadeEvent(d, 10_000L))
    }

    @Test fun audibleCommandRefusalRemainsInPreparationRuntime() {
        makeReady()
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.BeginFade(keyA, 10_000L, 0L))
        runtime.applyReduction(reduction, snap)
        assertTrue(runtime.rejectedAudibleCommands.isNotEmpty())
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.starts.isEmpty())
    }

    // -- CF-2C5: BeginFade execution + exact secondary start ---------------------------

    private val now = 10_000L

    @Test fun onTimeExecutionStartsSecondaryAtZeroGainAndEntersFading() {
        val d = readyDue(0L)
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Fading(keyA, 6_000L, now, 0L), runtime.state)
        assertEquals(listOf(0f), backend.starts)
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
    }

    @Test fun lateExecutionUsesCoordinatorEqualPowerGain() {
        val d = readyDue(MAX_FADE_START_LATENESS_MS) // 1500 / 6000 = 0.25
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Fading(keyA, 6_000L, now, 1_500L), runtime.state)
        assertEquals(listOf(CrossfadeGainCurve.equalPower(0.25f).incoming), backend.starts)
    }

    @Test fun backendGainEqualsCoordinatorStartAndApplyGains() {
        val d = readyDue(1_000L)
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.BeginFade(keyA, now, 1_000L))
        val start = reduction.commands[0] as CrossfadeCommand.StartSecondary
        val apply = reduction.commands[1] as CrossfadeCommand.ApplyGains
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(start.initialIncomingGain, apply.gains.incoming, 0f)
        assertEquals(listOf(start.initialIncomingGain), backend.starts)
    }

    @Test fun initialOutgoingGainIsAppliedToPrimaryFromTheSameReduction() {
        val d = readyDue(1_000L)
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.BeginFade(keyA, now, 1_000L))
        val apply = reduction.commands[1] as CrossfadeCommand.ApplyGains
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(listOf(apply.gains.outgoing), primary.gains)
        assertEquals(CrossfadeGainCurve.equalPower(1_000f / 6_000f).outgoing, primary.gains.single(), 0f)
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
        assertEquals(0, backend.resets)
    }

    @Test fun duplicateExecutionDoesNotStartTwice() {
        val d = readyDue(0L)
        assertTrue(runtime.executeBeginFade(d, now))
        assertFalse(runtime.executeBeginFade(d, now + 10))
        assertEquals(1, backend.starts.size)
        assertEquals(1, primary.gains.size)
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    @Test fun stateIsFadingBeforeBackendStartIsCalled() {
        val d = readyDue(0L)
        var seen: CrossfadeState? = null
        backend.onStart = { seen = runtime.state }
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Fading(keyA, 6_000L, now, 0L), seen)
    }

    @Test fun backendRefusingStartFailsClosedToIdle() {
        val d = readyDue(0L)
        backend.startResult = false
        assertFalse(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue("primary must stay untouched", primary.gains.isEmpty())
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
    }

    @Test fun synchronousErrorDuringStartIsNotOverwrittenByFading() {
        val d = readyDue(0L)
        val attempt = backend.prepared.last()
        backend.onStart = { backend.error(attempt) }
        assertFalse(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets) // one terminal reset path
        assertEquals(1, backend.starts.size)
        assertTrue("primary must stay untouched", primary.gains.isEmpty())
    }

    @Test fun reentrantReplacementDuringStartKeepsNewOwner() {
        val d = readyDue(0L)
        backend.onStart = {
            snap = snapshot(index = 2)
            runtime.evaluatePreparation(6_000L, null) // old audible owner lost: cancelled, NO same-call re-arm (CF-2C7C)
        }
        assertFalse(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Idle, runtime.state)
        runtime.evaluatePreparation(6_000L, null) // a later evaluation arms B = (5, 2, 3)
        val armed = runtime.state as CrossfadeState.Armed
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), armed.key)
        assertTrue("primary must stay untouched", primary.gains.isEmpty())
        assertEquals(1, backend.starts.size)
    }

    @Test fun secondaryErrorAfterSuccessfulStartReturnsToIdle() {
        val d = readyDue(0L)
        val attempt = backend.prepared.last()
        assertTrue(runtime.executeBeginFade(d, now))
        backend.error(attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.starts.size)
        assertEquals(1, backend.resets)
        assertEquals(listOf(1f, 1f), primary.gains) // on-time outgoing = 1, then restore
    }

    @Test fun cancelAfterSuccessfulStartResetsSecondaryOnceAndRestoresPrimary() {
        val d = readyDue(0L)
        assertTrue(runtime.executeBeginFade(d, now))
        runtime.cancel(CrossfadeCancelReason.Pause)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertEquals(listOf(1f, 1f), primary.gains)
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
    }

    @Test fun cf2c4RejectionsNeverStartTheSecondary() {
        val d = readyDue(0L)
        assertFalse(runtime.executeBeginFade(d.copy(key = CrossfadeTransitionKey(5L, 2, 3)), now)) // stale key
        assertFalse(runtime.executeBeginFade(d, -1L)) // negative now
        assertEquals(CrossfadeState.Ready(keyA, 6_000L, 194_000L), runtime.state)
        evaluate(configured = 4_000L) // same key, replaced plan
        backend.ready(backend.prepared.last())
        assertFalse(runtime.executeBeginFade(d, now)) // stale plan Due
        assertEquals(CrossfadeState.Ready(keyA, 4_000L, 196_000L), runtime.state)
        assertTrue(backend.starts.isEmpty())
        snap = snapshot(playing = false) // lost live ownership
        val dueB = d.copy(effectiveDurationMs = 4_000L, startAtPositionMs = 196_000L)
        assertFalse(runtime.executeBeginFade(dueB, now))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.starts.isEmpty())
    }

    @Test fun duplicateSongStartBelongsToExactPositionalKey() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        snap = snapshot(queue = dup, index = 2)
        evaluate()
        backend.ready(backend.prepared.last())
        val key = CrossfadeTransitionKey(5L, 2, 3)
        val d = observe(startA, key = key) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Fading(key, 6_000L, now, 0L), runtime.state)
        assertEquals(1, backend.starts.size)
    }

    @Test fun runtimeHoldsNoPlayerReference() {
        val offending = CrossfadePreparationRuntime::class.java.declaredFields.map { it.type.name }
            .filter { it.startsWith("androidx.media3") }
        assertTrue(offending.isEmpty())
    }

    @Test fun genericApplyReductionStillRefusesStartSecondary() {
        makeReady()
        runtime.applyReduction(
            CrossfadeReduction(runtime.state, listOf(CrossfadeCommand.StartSecondary(keyA, 0f))),
            snap,
        )
        assertEquals(listOf<CrossfadeCommand>(CrossfadeCommand.StartSecondary(keyA, 0f)), runtime.rejectedAudibleCommands)
        assertTrue(backend.starts.isEmpty())
    }

    // -- CF-2C6: occurrence-owned primary gain -----------------------------------------

    @Test fun onTimeBeginAppliesFullPrimaryGainAndZeroSecondaryGain() {
        assertTrue(runtime.executeBeginFade(readyDue(0L), now))
        assertEquals(listOf(0f), backend.starts)
        assertEquals(listOf(1f), primary.gains)
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    @Test fun lateBeginAppliesCoordinatorOutgoingAndIncomingGains() {
        assertTrue(runtime.executeBeginFade(readyDue(MAX_FADE_START_LATENESS_MS), now))
        val g = CrossfadeGainCurve.equalPower(0.25f)
        assertEquals(listOf(g.incoming), backend.starts)
        assertEquals(listOf(g.outgoing), primary.gains)
    }

    @Test fun primaryIsNotLoweredBeforeSecondaryHasStarted() {
        val d = readyDue(1_000L)
        var primarySeenAtStart: List<Float>? = null
        backend.onStart = { primarySeenAtStart = primary.gains.toList() }
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(emptyList<Float>(), primarySeenAtStart)
    }

    @Test fun primaryApplyFailureCancelsRestoresAndAbandonsSecondary() {
        val d = readyDue(1_000L)
        primary.failWhen = { it < 1f }
        assertFalse(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertEquals(2, primary.gains.size)
        assertEquals(1f, primary.gains.last(), 0f) // restoration attempted despite the failed lowering write
    }

    @Test fun primaryApplyExceptionIsContainedAndRestored() {
        val d = readyDue(1_000L)
        primary.throwWhen = { it < 1f }
        assertFalse(runtime.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primary.gains.last(), 0f)
    }

    @Test fun cancelReasonsAfterBeginAllRestorePrimary() {
        for (reason in listOf(
            CrossfadeCancelReason.Pause,
            CrossfadeCancelReason.Seek,
            CrossfadeCancelReason.QueueMutation,
            CrossfadeCancelReason.SecondaryError,
        )) {
            val b = FakeBackend()
            val p = FakePrimary()
            val r = CrossfadePreparationRuntime({ snap }, { b }, primaryGainBackend = p)
            r.evaluatePreparation(6_000L, null)
            b.ready(b.prepared.last())
            val d = r.observePrimaryPosition(keyA, startA + 1_000L) as FadeWindowObservation.Due
            assertTrue(r.executeBeginFade(d, now))
            r.cancel(reason)
            assertEquals(CrossfadeState.Idle, r.state)
            assertEquals(1f, p.gains.last(), 0f)
            assertEquals(1, b.resets)
        }
    }

    @Test fun secondaryFailureAfterPrimaryLoweredRestoresPrimary() {
        val d = readyDue(1_000L)
        val attempt = backend.prepared.last()
        assertTrue(runtime.executeBeginFade(d, now))
        assertTrue(primary.gains.single() < 1f)
        backend.error(attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primary.gains.last(), 0f)
    }

    @Test fun failedRestoreStillAbandonsSecondaryAndStaysFailClosed() {
        val d = readyDue(1_000L)
        assertTrue(runtime.executeBeginFade(d, now))
        primary.failWhen = { it == 1f }
        runtime.cancel(CrossfadeCancelReason.Pause)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun closeRestoresPrimaryBeforeReleasingSecondaryAndIsIdempotent() {
        val d = readyDue(1_000L)
        assertTrue(runtime.executeBeginFade(d, now))
        var releasesWhenRestored = -1
        primary.onWrite = { if (it == 1f) releasesWhenRestored = backend.releases }
        runtime.close()
        runtime.close()
        assertEquals(0, releasesWhenRestored) // restore happened before the secondary release
        assertEquals(1, backend.releases)
        assertEquals(2, primary.gains.size) // lowered, then exactly one restore
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun staleDueNeverTouchesPrimary() {
        val dueA = readyDue(0L)
        evaluate(configured = 4_000L)
        backend.ready(backend.prepared.last())
        assertFalse(runtime.executeBeginFade(dueA, now))
        assertTrue(primary.gains.isEmpty())
    }

    @Test fun duplicateSongPrimaryOwnershipIsPositional() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        snap = snapshot(queue = dup, index = 2)
        evaluate()
        backend.ready(backend.prepared.last())
        val key = CrossfadeTransitionKey(5L, 2, 3)
        val d = observe(startA, key = key) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(d, now))
        assertEquals(listOf(1f), primary.gains)
        runtime.cancel(CrossfadeCancelReason.Pause)
        assertEquals(listOf(1f, 1f), primary.gains)
    }

    @Test fun genericApplyReductionStillRefusesApplyGains() {
        makeReady()
        val apply = CrossfadeCommand.ApplyGains(keyA, CrossfadeGains(0.5f, 0.5f))
        runtime.applyReduction(CrossfadeReduction(runtime.state, listOf(apply)), snap)
        assertEquals(listOf<CrossfadeCommand>(apply), runtime.rejectedAudibleCommands)
        assertTrue(primary.gains.isEmpty())
    }

    private fun unsupportedCommands(): List<CrossfadeCommand> = listOf(
        CrossfadeCommand.ApplyGains(keyA, CrossfadeGains(0.5f, 0.5f)),
        CrossfadeCommand.StartSecondary(keyA, 0.5f),
        CrossfadeCommand.RequestHandoff(keyA),
    )

    @Test fun unsupportedCommandWhileFadingRestoresLoweredPrimaryThenAbandons() {
        val d = readyDue(1_000L) // non-zero lateness: primary genuinely lowered
        assertTrue(runtime.executeBeginFade(d, now))
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertTrue(primary.gains.last() < 1f)
        assertEquals(1, backend.starts.size)
        val apply = CrossfadeCommand.ApplyGains(keyA, CrossfadeGains(0.5f, 0.5f))
        runtime.applyReduction(CrossfadeReduction(runtime.state, listOf(apply)), snap)
        assertEquals(listOf<CrossfadeCommand>(apply), runtime.rejectedAudibleCommands)
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(2, primary.gains.size)
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun failedRestoreDuringRefusalStillAbandonsSecondaryAndGoesIdle() {
        val d = readyDue(1_000L)
        assertTrue(runtime.executeBeginFade(d, now))
        primary.failWhen = { it == 1f }
        val apply = CrossfadeCommand.ApplyGains(keyA, CrossfadeGains(0.5f, 0.5f))
        runtime.applyReduction(CrossfadeReduction(runtime.state, listOf(apply)), snap)
        assertEquals(2, primary.gains.size) // lowered, then the (failed) restore attempt
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeState.Idle, runtime.state)
        // Ownership retained: a later restore attempt (e.g. on close) retries the write.
        primary.failWhen = { false }
        runtime.close()
        assertEquals(3, primary.gains.size)
        assertEquals(1f, primary.gains.last(), 0f)
    }

    @Test fun everyUnsupportedCommandUsesTheSameRestoreThenAbandonPath() {
        for (command in unsupportedCommands()) {
            val b = FakeBackend()
            val p = FakePrimary()
            val r = CrossfadePreparationRuntime({ snap }, { b }, primaryGainBackend = p)
            r.evaluatePreparation(6_000L, null)
            b.ready(b.prepared.last())
            val d = r.observePrimaryPosition(keyA, startA + 1_000L) as FadeWindowObservation.Due
            assertTrue(r.executeBeginFade(d, now))
            r.applyReduction(CrossfadeReduction(r.state, listOf(command)), snap)
            assertEquals(listOf(command), r.rejectedAudibleCommands)
            assertEquals(1f, p.gains.last(), 0f)
            assertEquals(1, b.resets)
            assertEquals(CrossfadeState.Idle, r.state)
        }
    }

    @Test fun unsupportedCommandWhileReadyDoesNotTouchPrimary() {
        makeReady()
        runtime.applyReduction(CrossfadeReduction(runtime.state, listOf(unsupportedCommands().first())), snap)
        assertTrue(primary.gains.isEmpty())
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun missingPrimaryBackendFailsClosedInsteadOfLeavingPrimaryAtFullVolume() {
        val b = FakeBackend()
        val r = CrossfadePreparationRuntime({ snap }, { b }) // default: Unavailable
        r.evaluatePreparation(6_000L, null)
        b.ready(b.prepared.last())
        val d = r.observePrimaryPosition(keyA, startA) as FadeWindowObservation.Due
        assertFalse(r.executeBeginFade(d, now))
        assertEquals(CrossfadeState.Idle, r.state)
        assertEquals(1, b.resets)
    }

    // -- Safety ------------------------------------------------------------------

    @Test fun normalFlowNeverExecutesAudibleCommandsOrReachesFading() {
        makeReady()
        observe(startA - 1)
        observe(startA)
        observe(startA + 500)
        observe(startA + MAX_FADE_START_LATENESS_MS + 10)
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
        assertTrue("Due must never start the secondary", backend.starts.isEmpty())
        assertTrue(runtime.state !is CrossfadeState.Fading)
    }

    @Test fun secondaryBackendHasOnlyPrepareStartResetRelease() {
        val names = SecondaryPlayerBackend::class.java.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet()
        assertEquals(setOf("prepare", "start", "setGain", "handoffSnapshot", "audioSessionSnapshot", "reset", "release"), names)
    }

    @Test fun productionGateRemainsFalse() {
        org.junit.Assert.assertFalse(CrossfadeRolloutPolicy.RUNTIME_ENABLED)
    }
}
