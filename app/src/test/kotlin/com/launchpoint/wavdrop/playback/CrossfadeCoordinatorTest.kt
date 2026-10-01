package com.launchpoint.wavdrop.playback

import com.launchpoint.wavdrop.playback.CrossfadeCommand.AbandonSecondary
import com.launchpoint.wavdrop.playback.CrossfadeCommand.ApplyGains
import com.launchpoint.wavdrop.playback.CrossfadeCommand.PrepareSecondary
import com.launchpoint.wavdrop.playback.CrossfadeCommand.RequestHandoff
import com.launchpoint.wavdrop.playback.CrossfadeCommand.RestorePrimaryGain
import com.launchpoint.wavdrop.playback.CrossfadeCommand.StartSecondary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeCoordinatorTest {

    private val keyA = CrossfadeTransitionKey(queueGeneration = 1L, fromPlaybackIndex = 0, toPlaybackIndex = 1)
    private val keyB = CrossfadeTransitionKey(queueGeneration = 1L, fromPlaybackIndex = 1, toPlaybackIndex = 2)
    private val duration = 6_000L

    private fun plan(next: Int, durationMs: Long = duration, startAt: Long = 100_000L) =
        CrossfadeTransitionPlan.Eligible(next, durationMs, startAt)

    private fun armed(key: CrossfadeTransitionKey = keyA): CrossfadeState =
        reduceCrossfade(CrossfadeState.Idle, CrossfadeEvent.Arm(key, plan(key.toPlaybackIndex))).state

    private fun ready(key: CrossfadeTransitionKey = keyA): CrossfadeState =
        reduceCrossfade(armed(key), CrossfadeEvent.SecondaryReady(key)).state

    private fun fading(key: CrossfadeTransitionKey = keyA, startedAt: Long = 1_000L, initial: Long = 0L): CrossfadeState =
        reduceCrossfade(ready(key), CrossfadeEvent.BeginFade(key, startedAt, initial)).state

    private fun handoffPending(key: CrossfadeTransitionKey = keyA): CrossfadeState =
        reduceCrossfade(fading(key, 1_000L), CrossfadeEvent.FadeTick(key, 1_000L + duration)).state

    private fun assertNoOp(state: CrossfadeState, event: CrossfadeEvent) {
        val r = reduceCrossfade(state, event)
        assertEquals(state, r.state)
        assertTrue(r.commands.isEmpty())
        assertNull(r.cancelReason)
    }

    // ── Idle ────────────────────────────────────────────────────────────────────

    @Test fun idleCancelIsNoOp() {
        CrossfadeCancelReason.entries.forEach { assertNoOp(CrossfadeState.Idle, CrossfadeEvent.Cancel(it)) }
    }

    @Test fun idleStaleAsyncEventsAreNoOps() {
        listOf(
            CrossfadeEvent.SecondaryReady(keyA),
            CrossfadeEvent.SecondaryFailed(keyA),
            CrossfadeEvent.BeginFade(keyA, 0L, 0L),
            CrossfadeEvent.FadeTick(keyA, 10L),
            CrossfadeEvent.HandoffSucceeded(keyA),
            CrossfadeEvent.HandoffFailed(keyA),
        ).forEach { assertNoOp(CrossfadeState.Idle, it) }
    }

    // ── Arming ──────────────────────────────────────────────────────────────────

    @Test fun validArmGoesArmedWithSinglePrepare() {
        val r = reduceCrossfade(CrossfadeState.Idle, CrossfadeEvent.Arm(keyA, plan(1, startAt = 90_000L)))
        assertEquals(CrossfadeState.Armed(keyA, duration, 90_000L), r.state)
        assertEquals(listOf<CrossfadeCommand>(PrepareSecondary(keyA)), r.commands)
        assertNull(r.armRejection)
    }

    @Test fun keyCarriesGenerationAndBothOccurrencePositions() {
        val k = CrossfadeTransitionKey(7L, 3, 4)
        assertEquals(7L, k.queueGeneration)
        assertEquals(3, k.fromPlaybackIndex)
        assertEquals(4, k.toPlaybackIndex)
        // Identity is positional only: different generation or either index => different transition.
        assertTrue(k != k.copy(queueGeneration = 8L))
        assertTrue(k != k.copy(fromPlaybackIndex = 2))
        assertTrue(k != k.copy(toPlaybackIndex = 5))
    }

    private fun assertRejected(
        expected: CrossfadeArmRejection,
        key: CrossfadeTransitionKey,
        plan: CrossfadeTransitionPlan.Eligible,
    ) {
        val r = reduceCrossfade(CrossfadeState.Idle, CrossfadeEvent.Arm(key, plan))
        assertEquals(expected, r.armRejection)
        assertEquals(CrossfadeState.Idle, r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test fun malformedArmsAreRejected() {
        assertRejected(CrossfadeArmRejection.TargetMismatch, keyA, plan(2))
        assertRejected(CrossfadeArmRejection.SameOccurrence, keyA.copy(toPlaybackIndex = 0), plan(0))
        assertRejected(CrossfadeArmRejection.NegativeQueueGeneration, keyA.copy(queueGeneration = -1L), plan(1))
        assertRejected(CrossfadeArmRejection.NegativePlaybackIndex, keyA.copy(fromPlaybackIndex = -1), plan(1))
        assertRejected(CrossfadeArmRejection.NegativePlaybackIndex, keyA.copy(toPlaybackIndex = -1), plan(-1))
        assertRejected(CrossfadeArmRejection.DurationBelowMinimum, keyA, plan(1, durationMs = 999L))
        assertRejected(CrossfadeArmRejection.DurationBelowMinimum, keyA, plan(1, durationMs = 0L))
        assertRejected(CrossfadeArmRejection.DurationAboveMaximum, keyA, plan(1, durationMs = 12_001L))
        assertRejected(CrossfadeArmRejection.NegativeStartPosition, keyA, plan(1, startAt = -1L))
    }

    @Test fun duration_boundsAreAccepted() {
        for (d in listOf(CrossfadeRules.MIN_ENABLED_DURATION_MS, CrossfadeRules.MAX_DURATION_MS)) {
            val r = reduceCrossfade(CrossfadeState.Idle, CrossfadeEvent.Arm(keyA, plan(1, durationMs = d)))
            assertTrue(r.state is CrossfadeState.Armed)
        }
    }

    @Test fun malformedArmDoesNotCorruptActiveTransition() {
        for (active in listOf(armed(), ready(), fading(), handoffPending())) {
            val r = reduceCrossfade(active, CrossfadeEvent.Arm(keyB, plan(99)))
            assertEquals(CrossfadeArmRejection.TargetMismatch, r.armRejection)
            assertEquals(active, r.state)
            assertTrue(r.commands.isEmpty())
        }
    }

    // ── Rearming ────────────────────────────────────────────────────────────────

    @Test fun rearmFromArmedAbandonsOldThenPreparesNew() {
        val r = reduceCrossfade(armed(keyA), CrossfadeEvent.Arm(keyB, plan(2)))
        assertEquals(listOf<CrossfadeCommand>(AbandonSecondary(keyA), PrepareSecondary(keyB)), r.commands)
        assertEquals(CrossfadeState.Armed(keyB, duration, 100_000L), r.state)
        assertNull(r.cancelReason)
    }

    @Test fun rearmFromReadyAbandonsOldThenPreparesNew() {
        val r = reduceCrossfade(ready(keyA), CrossfadeEvent.Arm(keyB, plan(2)))
        assertEquals(listOf<CrossfadeCommand>(AbandonSecondary(keyA), PrepareSecondary(keyB)), r.commands)
    }

    @Test fun rearmFromFadingRestoresThenAbandonsThenPrepares() {
        val r = reduceCrossfade(fading(keyA), CrossfadeEvent.Arm(keyB, plan(2)))
        assertEquals(
            listOf<CrossfadeCommand>(RestorePrimaryGain(keyA), AbandonSecondary(keyA), PrepareSecondary(keyB)),
            r.commands,
        )
        assertTrue(r.state is CrossfadeState.Armed)
    }

    @Test fun rearmFromHandoffPendingCleansUpOld() {
        val r = reduceCrossfade(handoffPending(keyA), CrossfadeEvent.Arm(keyB, plan(2)))
        assertEquals(
            listOf<CrossfadeCommand>(RestorePrimaryGain(keyA), AbandonSecondary(keyA), PrepareSecondary(keyB)),
            r.commands,
        )
    }

    @Test fun lateEventsForSupersededTransitionCannotAffectNew() {
        val b = reduceCrossfade(armed(keyA), CrossfadeEvent.Arm(keyB, plan(2))).state
        listOf(
            CrossfadeEvent.SecondaryReady(keyA),
            CrossfadeEvent.SecondaryFailed(keyA),
            CrossfadeEvent.BeginFade(keyA, 5L, 0L),
            CrossfadeEvent.FadeTick(keyA, 10L),
            CrossfadeEvent.HandoffSucceeded(keyA),
            CrossfadeEvent.HandoffFailed(keyA),
        ).forEach { assertNoOp(b, it) }
    }

    @Test fun cancelThenRearmThenLateReadyLeavesNewUnchanged() {
        val cancelled = reduceCrossfade(armed(keyA), CrossfadeEvent.Cancel(CrossfadeCancelReason.Pause)).state
        val b = reduceCrossfade(cancelled, CrossfadeEvent.Arm(keyB, plan(2))).state
        assertNoOp(b, CrossfadeEvent.SecondaryReady(keyA))
    }

    // ── Ready ───────────────────────────────────────────────────────────────────

    @Test fun matchingReadyMovesArmedToReadyWithoutCommands() {
        val r = reduceCrossfade(armed(), CrossfadeEvent.SecondaryReady(keyA))
        assertEquals(CrossfadeState.Ready(keyA, duration, 100_000L), r.state)
        assertTrue(r.commands.isEmpty())
    }

    @Test fun staleReadyIgnored() {
        assertNoOp(armed(keyA), CrossfadeEvent.SecondaryReady(keyB))
        assertNoOp(armed(keyA), CrossfadeEvent.SecondaryReady(keyA.copy(queueGeneration = 2L)))
    }

    @Test fun duplicateReadyIsIdempotent() {
        assertNoOp(ready(), CrossfadeEvent.SecondaryReady(keyA))
        assertNoOp(fading(), CrossfadeEvent.SecondaryReady(keyA))
    }

    // ── Begin ───────────────────────────────────────────────────────────────────

    @Test fun beginFromReadyStartsFadeWithProgressZeroGains() {
        val r = reduceCrossfade(ready(), CrossfadeEvent.BeginFade(keyA, 5_000L, 0L))
        assertEquals(CrossfadeState.Fading(keyA, duration, 5_000L, 0L), r.state)
        assertEquals(
            listOf<CrossfadeCommand>(StartSecondary(keyA, CrossfadeGainCurve.equalPower(0f).incoming), ApplyGains(keyA, CrossfadeGainCurve.equalPower(0f))),
            r.commands,
        )
        val gains = (r.commands[1] as ApplyGains).gains
        assertEquals(1f, gains.outgoing, 0f)
        assertEquals(0f, gains.incoming, 0f)
    }

    @Test fun staleBeginIgnored() = assertNoOp(ready(keyA), CrossfadeEvent.BeginFade(keyB, 1L, 0L))

    @Test fun beginWhileArmedDoesNotStart() = assertNoOp(armed(), CrossfadeEvent.BeginFade(keyA, 1L, 0L))

    @Test fun beginInIdleOrHandoffPendingDoesNotStart() {
        assertNoOp(CrossfadeState.Idle, CrossfadeEvent.BeginFade(keyA, 1L, 0L))
        assertNoOp(handoffPending(), CrossfadeEvent.BeginFade(keyA, 1L, 0L))
    }

    @Test fun duplicateBeginDoesNotStartSecondaryTwice() {
        assertNoOp(fading(), CrossfadeEvent.BeginFade(keyA, 9_999L, 0L))
    }

    @Test fun negativeStartTimestampIsIgnored() = assertNoOp(ready(), CrossfadeEvent.BeginFade(keyA, -1L, 0L))

    // ── Fading ──────────────────────────────────────────────────────────────────

    private fun tickGains(elapsed: Long): CrossfadeGains {
        val r = reduceCrossfade(fading(startedAt = 1_000L), CrossfadeEvent.FadeTick(keyA, 1_000L + elapsed))
        assertTrue(r.state is CrossfadeState.Fading)
        assertEquals(1, r.commands.size)
        return (r.commands.single() as ApplyGains).gains
    }

    @Test fun progressionUsesCf1Curve() {
        assertEquals(CrossfadeGainCurve.equalPower(0.25f), tickGains(1_500L))
        assertEquals(CrossfadeGainCurve.equalPower(0.5f), tickGains(3_000L))
        assertEquals(CrossfadeGainCurve.equalPower(0.75f), tickGains(4_500L))
        val mid = tickGains(3_000L)
        assertEquals(0.7071f, mid.outgoing, 0.0001f)
        assertEquals(0.7071f, mid.incoming, 0.0001f)
    }

    @Test fun tickAtStartYieldsProgressZeroGains() {
        assertEquals(CrossfadeGainCurve.equalPower(0f), tickGains(0L))
    }

    @Test fun tickJustBeforeCompletionDoesNotHandOff() {
        val r = reduceCrossfade(fading(startedAt = 1_000L), CrossfadeEvent.FadeTick(keyA, 1_000L + duration - 1))
        assertTrue(r.state is CrossfadeState.Fading)
        assertTrue(r.commands.none { it is RequestHandoff })
    }

    @Test fun exactCompletionEmitsFinalGainsThenOneHandoff() {
        val r = reduceCrossfade(fading(startedAt = 1_000L), CrossfadeEvent.FadeTick(keyA, 1_000L + duration))
        assertEquals(CrossfadeState.HandoffPending(keyA, duration), r.state)
        assertEquals(
            listOf<CrossfadeCommand>(ApplyGains(keyA, CrossfadeGainCurve.equalPower(1f)), RequestHandoff(keyA)),
            r.commands,
        )
    }

    @Test fun finalGainsAreEffectivelyZeroAndOne() {
        val r = reduceCrossfade(fading(startedAt = 1_000L), CrossfadeEvent.FadeTick(keyA, 1_000L + duration))
        val g = (r.commands.first() as ApplyGains).gains
        assertEquals(0f, g.outgoing, 1e-6f)
        assertEquals(1f, g.incoming, 1e-6f)
    }

    @Test fun tickBeyondCompletionBehavesTheSame() {
        val r = reduceCrossfade(fading(startedAt = 1_000L), CrossfadeEvent.FadeTick(keyA, 1_000L + duration + 5_000L))
        assertEquals(CrossfadeState.HandoffPending(keyA, duration), r.state)
        assertEquals(
            listOf<CrossfadeCommand>(ApplyGains(keyA, CrossfadeGainCurve.equalPower(1f)), RequestHandoff(keyA)),
            r.commands,
        )
    }

    @Test fun laterTicksInHandoffPendingEmitNothing() {
        val pending = handoffPending()
        assertNoOp(pending, CrossfadeEvent.FadeTick(keyA, 50_000L))
        assertNoOp(pending, CrossfadeEvent.FadeTick(keyA, Long.MAX_VALUE))
    }

    @Test fun tickOutsideFadingIsNoOp() {
        assertNoOp(armed(), CrossfadeEvent.FadeTick(keyA, 5_000L))
        assertNoOp(ready(), CrossfadeEvent.FadeTick(keyA, 5_000L))
    }

    @Test fun staleTickIgnored() = assertNoOp(fading(keyA), CrossfadeEvent.FadeTick(keyB, 9_000L))

    @Test fun clockRegressionCancelsFailClosed() {
        val r = reduceCrossfade(fading(startedAt = 1_000L), CrossfadeEvent.FadeTick(keyA, 999L))
        assertEquals(CrossfadeState.Idle, r.state)
        assertEquals(CrossfadeCancelReason.ClockRegression, r.cancelReason)
        assertEquals(listOf<CrossfadeCommand>(RestorePrimaryGain(keyA), AbandonSecondary(keyA)), r.commands)
    }

    @Test fun extremeElapsedValuesDoNotOverflow() {
        val r = reduceCrossfade(fading(startedAt = 0L), CrossfadeEvent.FadeTick(keyA, Long.MAX_VALUE))
        assertTrue(r.state is CrossfadeState.HandoffPending)
        val r2 = reduceCrossfade(fading(startedAt = Long.MAX_VALUE), CrossfadeEvent.FadeTick(keyA, Long.MAX_VALUE))
        assertTrue(r2.state is CrossfadeState.Fading)
        val r3 = reduceCrossfade(fading(startedAt = 1L), CrossfadeEvent.FadeTick(keyA, Long.MIN_VALUE))
        assertEquals(CrossfadeCancelReason.ClockRegression, r3.cancelReason)
    }

    // ── Handoff ─────────────────────────────────────────────────────────────────

    // -- Lateness-aware begin (CF-2C3) ----------------------------------------------

    private fun begin(initial: Long, now: Long = 10_000L) =
        reduceCrossfade(ready(), CrossfadeEvent.BeginFade(keyA, now, initial))

    private fun assertBeginGains(initial: Long, progress: Float) {
        val r = begin(initial)
        assertEquals(CrossfadeState.Fading(keyA, duration, 10_000L, initial), r.state)
        val gains = CrossfadeGainCurve.equalPower(progress)
        assertEquals(
            listOf<CrossfadeCommand>(StartSecondary(keyA, gains.incoming), ApplyGains(keyA, gains)),
            r.commands,
        )
        // The start gain and the applied incoming gain are the very same value.
        assertEquals((r.commands[0] as StartSecondary).initialIncomingGain, (r.commands[1] as ApplyGains).gains.incoming, 0f)
    }

    @Test fun beginAtZeroLatenessUsesProgressZeroGains() = assertBeginGains(0L, 0f)

    @Test fun lateBeginAtQuarterUsesQuarterProgressGains() = assertBeginGains(1_500L, 0.25f)

    @Test fun lateBeginAtMidpointUsesHalfProgressGains() = assertBeginGains(3_000L, 0.5f)

    @Test fun startSecondaryCarriesOnlyKeyAndGain() {
        val fields = StartSecondary::class.java.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        assertEquals(setOf("key", "initialIncomingGain"), fields)
    }

    @Test fun invalidBeginInputsAreNoOps() {
        val r = ready()
        assertNoOp(r, CrossfadeEvent.BeginFade(keyA, -1L, 0L)) // negative now
        assertNoOp(r, CrossfadeEvent.BeginFade(keyA, 10_000L, -1L)) // negative initial elapsed
        assertNoOp(r, CrossfadeEvent.BeginFade(keyA, 10_000L, duration)) // fully elapsed
        assertNoOp(r, CrossfadeEvent.BeginFade(keyA, 10_000L, duration + 1)) // beyond duration
        assertNoOp(r, CrossfadeEvent.BeginFade(keyA, 10_000L, Long.MAX_VALUE))
        assertNoOp(r, CrossfadeEvent.BeginFade(keyB, 10_000L, 0L)) // wrong key
        assertNoOp(CrossfadeState.Idle, CrossfadeEvent.BeginFade(keyA, 10_000L, 1_500L))
        assertNoOp(armed(), CrossfadeEvent.BeginFade(keyA, 10_000L, 1_500L))
        assertNoOp(fading(), CrossfadeEvent.BeginFade(keyA, 10_000L, 1_500L))
        assertNoOp(handoffPending(), CrossfadeEvent.BeginFade(keyA, 10_000L, 1_500L))
    }

    @Test fun oneMillisecondBeforeFullDurationStillBegins() {
        val r = begin(duration - 1)
        assertTrue(r.state is CrossfadeState.Fading)
    }

    @Test fun tickAfterLateBeginContinuesFromTheInitialOffset() {
        // duration 6000, initial 1500, began at 10000; tick at 11000 -> total elapsed 2500 (not 1000).
        val state = fading(startedAt = 10_000L, initial = 1_500L)
        val r = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, 11_000L))
        assertEquals(state, r.state)
        val expected = CrossfadeGainCurve.equalPower(2_500f / 6_000f)
        assertEquals(listOf<CrossfadeCommand>(ApplyGains(keyA, expected)), r.commands)
        assertTrue(expected != CrossfadeGainCurve.equalPower(1_000f / 6_000f))
    }

    @Test fun tickAtTheBeginInstantMatchesTheInitialGains() {
        val state = fading(startedAt = 10_000L, initial = 1_500L)
        val r = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, 10_000L))
        assertEquals(listOf<CrossfadeCommand>(ApplyGains(keyA, CrossfadeGainCurve.equalPower(0.25f))), r.commands)
    }

    @Test fun lateBeginCompletionUsesTheRemainingDurationOnly() {
        val state = fading(startedAt = 10_000L, initial = 1_500L) // remaining 4500
        val before = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, 14_499L))
        assertTrue(before.state is CrossfadeState.Fading)
        assertTrue(before.commands.none { it is RequestHandoff })
        val at = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, 14_500L))
        assertEquals(CrossfadeState.HandoffPending(keyA, duration), at.state)
        assertEquals(
            listOf<CrossfadeCommand>(ApplyGains(keyA, CrossfadeGainCurve.equalPower(1f)), RequestHandoff(keyA)),
            at.commands,
        )
    }

    @Test fun lateBeginClockRegressionFailsClosedWithAudibleCleanup() {
        val r = reduceCrossfade(fading(startedAt = 10_000L, initial = 1_500L), CrossfadeEvent.FadeTick(keyA, 9_999L))
        assertEquals(CrossfadeState.Idle, r.state)
        assertEquals(CrossfadeCancelReason.ClockRegression, r.cancelReason)
        assertEquals(listOf<CrossfadeCommand>(RestorePrimaryGain(keyA), AbandonSecondary(keyA)), r.commands)
    }

    @Test fun zeroLatenessBeginMatchesLegacyTimeline() {
        val state = fading(startedAt = 1_000L, initial = 0L)
        val mid = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, 1_000L + 3_000L))
        assertEquals(listOf<CrossfadeCommand>(ApplyGains(keyA, CrossfadeGainCurve.equalPower(0.5f))), mid.commands)
        val end = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, 1_000L + duration))
        assertEquals(CrossfadeState.HandoffPending(keyA, duration), end.state)
    }

    @Test fun extremeMonotonicValuesDoNotOverflowWithLateBegin() {
        // began near the top of the clock range: 100 ms later is still inside the 4500 ms remainder, and the
        // progress is the exact (1500 + 100) / 6000, with no overflow or negative timing.
        val nearMax = Long.MAX_VALUE - 100L
        val state = fading(startedAt = nearMax, initial = 1_500L)
        val still = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, Long.MAX_VALUE))
        assertEquals(state, still.state)
        assertEquals(
            listOf<CrossfadeCommand>(ApplyGains(keyA, CrossfadeGainCurve.equalPower(1_600f / 6_000f))),
            still.commands,
        )
    }

    @Test fun handoffAtTheExtremeEndOfTheClockIsDeterministic() {
        val began = 0L
        val state = fading(startedAt = began, initial = 1_500L)
        val end = reduceCrossfade(state, CrossfadeEvent.FadeTick(keyA, Long.MAX_VALUE))
        assertEquals(CrossfadeState.HandoffPending(keyA, duration), end.state)
        val regress = reduceCrossfade(fading(startedAt = Long.MAX_VALUE, initial = 1_500L), CrossfadeEvent.FadeTick(keyA, 0L))
        assertEquals(CrossfadeCancelReason.ClockRegression, regress.cancelReason)
        val same = reduceCrossfade(fading(startedAt = Long.MAX_VALUE, initial = 1_500L), CrossfadeEvent.FadeTick(keyA, Long.MAX_VALUE))
        assertEquals(listOf<CrossfadeCommand>(ApplyGains(keyA, CrossfadeGainCurve.equalPower(0.25f))), same.commands)
    }

    @Test fun matchingHandoffSuccessGoesIdleOnce() {
        val r = reduceCrossfade(handoffPending(), CrossfadeEvent.HandoffSucceeded(keyA))
        assertEquals(CrossfadeState.Idle, r.state)
        assertTrue(r.commands.isEmpty())
        assertNoOp(r.state, CrossfadeEvent.HandoffSucceeded(keyA))
    }

    @Test fun staleOrMistimedHandoffSuccessIgnored() {
        assertNoOp(handoffPending(keyA), CrossfadeEvent.HandoffSucceeded(keyB))
        assertNoOp(fading(), CrossfadeEvent.HandoffSucceeded(keyA))
        assertNoOp(ready(), CrossfadeEvent.HandoffSucceeded(keyA))
    }

    @Test fun handoffFailureCleansUpAndGoesIdle() {
        val r = reduceCrossfade(handoffPending(), CrossfadeEvent.HandoffFailed(keyA))
        assertEquals(CrossfadeState.Idle, r.state)
        assertEquals(CrossfadeCancelReason.HandoffFailed, r.cancelReason)
        assertEquals(listOf<CrossfadeCommand>(RestorePrimaryGain(keyA), AbandonSecondary(keyA)), r.commands)
    }

    @Test fun secondaryFailureCancelsFromAnyActivePhase() {
        for (s in listOf(armed(), ready(), fading(), handoffPending())) {
            val r = reduceCrossfade(s, CrossfadeEvent.SecondaryFailed(keyA))
            assertEquals(CrossfadeState.Idle, r.state)
            assertEquals(CrossfadeCancelReason.SecondaryError, r.cancelReason)
        }
    }

    // ── Cancellation ────────────────────────────────────────────────────────────

    @Test fun everyReasonReturnsEveryActivePhaseToIdle() {
        for (reason in CrossfadeCancelReason.entries) {
            for (s in listOf(armed(), ready(), fading(), handoffPending())) {
                val r = reduceCrossfade(s, CrossfadeEvent.Cancel(reason))
                assertEquals(CrossfadeState.Idle, r.state)
                assertEquals(reason, r.cancelReason)
            }
        }
    }

    @Test fun preAudibleCancelOnlyAbandonsSecondary() {
        for (s in listOf(armed(), ready())) {
            val r = reduceCrossfade(s, CrossfadeEvent.Cancel(CrossfadeCancelReason.Seek))
            assertEquals(listOf<CrossfadeCommand>(AbandonSecondary(keyA)), r.commands)
        }
    }

    @Test fun audibleCancelRestoresPrimaryBeforeAbandoning() {
        for (s in listOf(fading(), handoffPending())) {
            val r = reduceCrossfade(s, CrossfadeEvent.Cancel(CrossfadeCancelReason.ManualNavigation))
            assertEquals(listOf<CrossfadeCommand>(RestorePrimaryGain(keyA), AbandonSecondary(keyA)), r.commands)
        }
    }

    @Test fun cancelIsIdempotent() {
        val first = reduceCrossfade(fading(), CrossfadeEvent.Cancel(CrossfadeCancelReason.Pause))
        assertNoOp(first.state, CrossfadeEvent.Cancel(CrossfadeCancelReason.Pause))
    }

    // ── Occurrence identity ─────────────────────────────────────────────────────

    @Test fun sameIndicesInNewerGenerationAreADifferentTransition() {
        val newer = keyA.copy(queueGeneration = keyA.queueGeneration + 1)
        val state = armed(newer)
        listOf(
            CrossfadeEvent.SecondaryReady(keyA),
            CrossfadeEvent.SecondaryFailed(keyA),
            CrossfadeEvent.BeginFade(keyA, 1L, 0L),
            CrossfadeEvent.FadeTick(keyA, 2L),
            CrossfadeEvent.HandoffSucceeded(keyA),
            CrossfadeEvent.HandoffFailed(keyA),
        ).forEach { assertNoOp(state, it) }
        assertNoOp(fading(newer), CrossfadeEvent.FadeTick(keyA, 5_000L))
        assertNoOp(handoffPending(newer), CrossfadeEvent.HandoffSucceeded(keyA))
    }

    @Test fun rearmWithSameIndicesInNewerGenerationSupersedesOld() {
        val newer = keyA.copy(queueGeneration = 2L)
        val r = reduceCrossfade(armed(keyA), CrossfadeEvent.Arm(newer, plan(1)))
        assertEquals(listOf<CrossfadeCommand>(AbandonSecondary(keyA), PrepareSecondary(newer)), r.commands)
    }

    @Test fun identityIsPositionalWithNoSongIdDependency() {
        // The key's only components are generation + two playback positions, so a queue like
        // [A, B, A] (same song at positions 0 and 2) yields structurally valid, distinct transitions.
        val fields = CrossfadeTransitionKey::class.java.declaredFields
            .filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("queueGeneration", "fromPlaybackIndex", "toPlaybackIndex"), fields)
        val t0 = CrossfadeTransitionKey(1L, 0, 1)
        val t1 = CrossfadeTransitionKey(1L, 1, 2)
        val r = reduceCrossfade(
            reduceCrossfade(CrossfadeState.Idle, CrossfadeEvent.Arm(t0, plan(1))).state,
            CrossfadeEvent.Arm(t1, plan(2)),
        )
        assertEquals(CrossfadeState.Armed(t1, duration, 100_000L), r.state)
    }

    @Test fun commandsNeverCarryAnythingButKeysAndGains() {
        // Sanity: every command exposes the key of the transition it acts on.
        val all = listOf(
            PrepareSecondary(keyA), StartSecondary(keyA, 0f), ApplyGains(keyA, CrossfadeGains(1f, 0f)),
            RequestHandoff(keyA), RestorePrimaryGain(keyA), AbandonSecondary(keyA),
        )
        all.forEach { assertEquals(keyA, it.key) }
    }
}
