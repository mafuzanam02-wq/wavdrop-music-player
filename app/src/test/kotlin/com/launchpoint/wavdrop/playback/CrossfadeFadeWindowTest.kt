package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeFadeWindowTest {

    // -- Pure decision -----------------------------------------------------------

    private fun due(key: CrossfadeTransitionKey, latenessMs: Long = 0L) = FadeWindowObservation.Due(key, latenessMs)

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
        fun ready(attempt: Long, durationMs: Long = 180_000L) = callbacks!!.onReady(attempt, durationMs)
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
    private val backend = FakeBackend()
    private val runtime = CrossfadePreparationRuntime({ snap }, { backend })
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
        assertEquals(due(keyA), observe(196_000L))
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

    // -- Safety ------------------------------------------------------------------

    @Test fun normalFlowNeverExecutesAudibleCommandsOrReachesFading() {
        makeReady()
        observe(startA - 1)
        observe(startA)
        observe(startA + 500)
        observe(startA + MAX_FADE_START_LATENESS_MS + 10)
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
        assertTrue(runtime.state !is CrossfadeState.Fading)
    }

    @Test fun secondaryBackendStillHasOnlySilentOperations() {
        val names = SecondaryPlayerBackend::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("prepare", "reset", "release"), names)
    }

    @Test fun productionGateRemainsFalse() {
        org.junit.Assert.assertFalse(PlaybackService.CROSSFADE_SECONDARY_RUNTIME_ENABLED)
    }
}
