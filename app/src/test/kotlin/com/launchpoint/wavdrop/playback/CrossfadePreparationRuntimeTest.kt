package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadePreparationRuntimeTest {

    private class FakeBackend : SecondaryPlayerBackend {
        data class Prepared(val attempt: Long, val item: MediaItem)

        val prepared = mutableListOf<Prepared>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var releases = 0
        var onPrepare: (() -> Unit)? = null

        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += Prepared(attempt, item)
            this.callbacks = callbacks
            onPrepare?.invoke()
        }

        override fun reset() { resets++ }
        override fun release() { releases++ }
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = null
        override fun setGain(gain: Float): Boolean = true

        val starts = mutableListOf<Float>()
        var startResult = true
        override fun start(initialGain: Float): Boolean { starts += initialGain; return startResult }

        fun ready(attempt: Long, durationMs: Long = 180_000L) = callbacks!!.onReady(attempt, durationMs)
        fun error(attempt: Long) = callbacks!!.onError(attempt)
    }

    private fun song(id: Long, tag: Long = 0L, durationMs: Long = 200_000L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = durationMs, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private val queue = listOf(song(1, 0), song(2, 1), song(3, 2), song(4, 3))

    private var snap = snapshot()
    private val backend = FakeBackend()
    private val runtime = CrossfadePreparationRuntime(
        snapshotProvider = { snap },
        backendFactory = { backend },
    )

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
        queueGeneration = generation,
        playbackQueue = queue,
        currentPlaybackIndex = index,
        repeatMode = repeat,
        shuffleEnabled = false,
        isPlaying = playing,
        isExternalPlayback = external,
        playerQueueNeedsSync = dirty,
        controllerConnected = connected,
    )

    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private fun evaluate(configured: Long = 6_000L, current: Long? = null) =
        runtime.evaluatePreparation(configured, current)

    private fun assertIdleAndNothingPrepared() {
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.prepared.isEmpty())
    }

    // ── Arming ──────────────────────────────────────────────────────────────────

    @Test fun eligibleSnapshotPreparesExactPositionalTargetOnce() {
        evaluate()
        assertEquals(CrossfadeState.Armed(keyA, 6_000L, 194_000L), runtime.state)
        assertEquals(1, backend.prepared.size)
        assertEquals(queue[2].id.toString(), backend.prepared.single().item.mediaId)
    }

    @Test fun duplicateIdsUseToPlaybackIndexNotFirstMatchingId() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        snap = snapshot(queue = dup, index = 2)
        val requested = mutableListOf<Song>()
        val r = CrossfadePreparationRuntime(
            snapshotProvider = { snap },
            backendFactory = { backend },
            mediaItemFactory = { requested += it; MediaItem.Builder().setMediaId(it.id.toString()).build() },
        )
        r.evaluatePreparation(6_000L, null)
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (r.state as CrossfadeState.Armed).key)
        // Ids 10 sit at positions 0, 2 and 3: the exact positional occurrence (tag 3) is prepared.
        assertEquals(listOf(3L), requested.map { it.dateAdded })
    }

    @Test fun repeatedEvaluationDoesNotRePrepare() {
        evaluate(); evaluate(); evaluate()
        assertEquals(1, backend.prepared.size)
        assertEquals(0, backend.resets)
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    @Test fun repeatedEvaluationWhileReadyKeepsReady() {
        evaluate()
        backend.ready(backend.prepared[0].attempt)
        assertTrue(runtime.state is CrossfadeState.Ready)
        evaluate()
        assertTrue(runtime.state is CrossfadeState.Ready)
        assertEquals(1, backend.prepared.size)
    }

    @Test fun differentKeySupersedesOldPreparation() {
        evaluate()
        snap = snapshot(index = 2)
        evaluate()
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (runtime.state as CrossfadeState.Armed).key)
        assertEquals(2, backend.prepared.size)
        assertTrue(backend.resets >= 1) // old key was abandoned first
    }

    @Test fun newerQueueGenerationCannotReuseOldPreparation() {
        evaluate()
        val oldAttempt = backend.prepared[0].attempt
        snap = snapshot(generation = 6L)
        evaluate()
        assertEquals(CrossfadeTransitionKey(6L, 1, 2), (runtime.state as CrossfadeState.Armed).key)
        assertEquals(2, backend.prepared.size)
        backend.ready(oldAttempt) // late callback of the old generation
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    // ── Fail closed while idle ──────────────────────────────────────────────────

    @Test fun disconnectedControllerFailsClosed() { snap = snapshot(connected = false); evaluate(); assertIdleAndNothingPrepared() }
    @Test fun externalPlaybackFailsClosed() { snap = snapshot(external = true); evaluate(); assertIdleAndNothingPrepared() }
    @Test fun dirtyQueueFailsClosed() { snap = snapshot(dirty = true); evaluate(); assertIdleAndNothingPrepared() }
    @Test fun pausedFailsClosed() { snap = snapshot(playing = false); evaluate(); assertIdleAndNothingPrepared() }
    @Test fun repeatOneFailsClosed() { snap = snapshot(repeat = RepeatMode.ONE); evaluate(); assertIdleAndNothingPrepared() }
    @Test fun invalidCurrentOccurrenceFailsClosed() { snap = snapshot(index = null); evaluate(); assertIdleAndNothingPrepared() }
    @Test fun noNextOccurrenceFailsClosed() { snap = snapshot(index = 3); evaluate(); assertIdleAndNothingPrepared() }
    @Test fun configurationOffFailsClosed() { evaluate(configured = 0L); assertIdleAndNothingPrepared() }

    @Test fun unknownCurrentDurationFailsClosed() {
        snap = snapshot(queue = listOf(song(1, 0, durationMs = 0L), song(2, 1)), index = 0)
        evaluate(current = null)
        assertIdleAndNothingPrepared()
        evaluate(current = -5L)
        assertIdleAndNothingPrepared()
    }

    // ── Ownership loss ──────────────────────────────────────────────────────────

    private fun armThenChange(change: () -> Unit) {
        evaluate()
        assertTrue(runtime.state is CrossfadeState.Armed)
        change()
        evaluate()
    }

    @Test fun pauseAbandonsAndReturnsToIdle() {
        armThenChange { snap = snapshot(playing = false) }
        assertEquals(CrossfadeState.Idle, runtime.state); assertEquals(1, backend.resets)
    }

    @Test fun externalPlaybackAbandonsAndReturnsToIdle() {
        armThenChange { snap = snapshot(external = true) }
        assertEquals(CrossfadeState.Idle, runtime.state); assertEquals(1, backend.resets)
    }

    @Test fun dirtyQueueAbandonsAndReturnsToIdle() {
        armThenChange { snap = snapshot(dirty = true) }
        assertEquals(CrossfadeState.Idle, runtime.state); assertEquals(1, backend.resets)
    }

    @Test fun controllerDisconnectAbandonsAndReturnsToIdle() {
        armThenChange { snap = snapshot(connected = false) }
        assertEquals(CrossfadeState.Idle, runtime.state); assertEquals(1, backend.resets)
    }

    @Test fun repeatOneAbandonsAndReturnsToIdle() {
        armThenChange { snap = snapshot(repeat = RepeatMode.ONE) }
        assertEquals(CrossfadeState.Idle, runtime.state); assertEquals(1, backend.resets)
    }

    @Test fun repeatChangeRemovingNextOccurrenceAbandonsToIdle() {
        // Repeat ALL wrapped last -> 0; switching to OFF at the last index leaves no next occurrence.
        snap = snapshot(index = 3, repeat = RepeatMode.ALL)
        evaluate()
        assertEquals(CrossfadeTransitionKey(5L, 3, 0), (runtime.state as CrossfadeState.Armed).key)
        snap = snapshot(index = 3, repeat = RepeatMode.OFF)
        evaluate()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun configurationTurnedOffAbandonsAndReturnsToIdle() {
        evaluate()
        evaluate(configured = 0L)
        assertEquals(CrossfadeState.Idle, runtime.state); assertEquals(1, backend.resets)
    }

    @Test fun generationChangeAbandonsOldBeforeArmingNew() {
        armThenChange { snap = snapshot(generation = 9L) }
        assertEquals(1, backend.resets)
        assertEquals(9L, (runtime.state as CrossfadeState.Armed).key.queueGeneration)
    }

    @Test fun occurrenceChangeAbandonsOldBeforeArmingNew() {
        armThenChange { snap = snapshot(index = 0) }
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeTransitionKey(5L, 0, 1), (runtime.state as CrossfadeState.Armed).key)
    }

    @Test fun ownershipLossReasonsAreMappedHonestly() {
        val k = keyA
        assertNull(crossfadeOwnershipLossReason(snapshot(), k))
        assertEquals(CrossfadeCancelReason.ControllerDisconnected, crossfadeOwnershipLossReason(snapshot(connected = false), k))
        assertEquals(CrossfadeCancelReason.ExternalPlayback, crossfadeOwnershipLossReason(snapshot(external = true), k))
        assertEquals(CrossfadeCancelReason.QueueBecameDirty, crossfadeOwnershipLossReason(snapshot(dirty = true), k))
        assertEquals(CrossfadeCancelReason.Pause, crossfadeOwnershipLossReason(snapshot(playing = false), k))
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snapshot(generation = 6L), k))
        assertEquals(CrossfadeCancelReason.ManualNavigation, crossfadeOwnershipLossReason(snapshot(index = 2), k))
        assertEquals(CrossfadeCancelReason.ManualNavigation, crossfadeOwnershipLossReason(snapshot(index = null), k))
        assertEquals(CrossfadeCancelReason.RepeatChanged, crossfadeOwnershipLossReason(snapshot(repeat = RepeatMode.ONE), k))
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snapshot(queue = queue.take(2)), k))
    }

    // ── Ready ───────────────────────────────────────────────────────────────────

    @Test fun matchingReadyMovesArmedToReady() {
        evaluate()
        backend.ready(backend.prepared[0].attempt)
        assertEquals(CrossfadeState.Ready(keyA, 6_000L, 194_000L), runtime.state)
    }

    @Test fun staleReadyIsIgnoredAfterSupersession() {
        evaluate()
        val oldAttempt = backend.prepared[0].attempt
        snap = snapshot(index = 2)
        evaluate()
        backend.ready(oldAttempt)
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (runtime.state as CrossfadeState.Armed).key)
    }

    @Test fun readyAfterCancellationIsIgnored() {
        evaluate()
        val attempt = backend.prepared[0].attempt
        runtime.cancel(CrossfadeCancelReason.Pause)
        backend.ready(attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun duplicateReadyDoesNotAlterState() {
        evaluate()
        val attempt = backend.prepared[0].attempt
        backend.ready(attempt)
        val before = runtime.state
        backend.ready(attempt)
        assertEquals(before, runtime.state)
    }

    @Test fun readyWithLostOwnershipCancelsInsteadOfReady() {
        evaluate()
        snap = snapshot(generation = 6L)
        backend.ready(backend.prepared[0].attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun nonPositivePreparedDurationFailsClosed() {
        for (d in listOf(0L, -1L)) {
            val b = FakeBackend()
            val r = CrossfadePreparationRuntime({ snap }, { b })
            r.evaluatePreparation(6_000L, null)
            b.ready(b.prepared[0].attempt, d)
            assertEquals(CrossfadeState.Idle, r.state)
        }
    }

    @Test fun preparedDurationThatInvalidatesOverlapFailsClosed() {
        evaluate() // effective overlap 6000 -> needs prepared >= 12000
        backend.ready(backend.prepared[0].attempt, 11_999L)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun validPreparedDurationPreservesEffectiveOverlap() {
        evaluate()
        backend.ready(backend.prepared[0].attempt, 12_000L)
        assertEquals(6_000L, (runtime.state as CrossfadeState.Ready).effectiveDurationMs)
    }

    @Test fun preparedDurationPolicyIsPure() {
        assertTrue(isPreparedDurationCompatible(6_000L, 12_000L))
        assertFalse(isPreparedDurationCompatible(6_000L, 11_999L))
        assertFalse(isPreparedDurationCompatible(6_000L, 0L))
        assertFalse(isPreparedDurationCompatible(6_000L, -1L))
        assertFalse(isPreparedDurationCompatible(999L, 100_000L))
    }

    // ── Failure ─────────────────────────────────────────────────────────────────

    @Test fun matchingFailureReturnsToIdle() {
        evaluate()
        backend.error(backend.prepared[0].attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun staleFailureCannotCancelNewerTransition() {
        evaluate()
        val oldAttempt = backend.prepared[0].attempt
        snap = snapshot(index = 2)
        evaluate()
        backend.error(oldAttempt)
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    @Test fun failureAfterCancellationIsHarmless() {
        evaluate()
        val attempt = backend.prepared[0].attempt
        runtime.cancel(CrossfadeCancelReason.Pause)
        backend.error(attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    // ── Commands / safety ───────────────────────────────────────────────────────

    @Test fun secondaryBackendHasOnlyPrepareStartResetRelease() {
        val names = SecondaryPlayerBackend::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("prepare", "start", "setGain", "handoffSnapshot", "reset", "release"), names)
    }

    @Test fun runtimeHoldsNoPrimaryPlayerReference() {
        val offending = CrossfadePreparationRuntime::class.java.declaredFields.map { it.type.name }
            .filter { it.startsWith("androidx.media3") }
        assertTrue("unexpected media3 fields: $offending", offending.isEmpty())
    }

    @Test fun normalFlowNeverExecutesAudibleCommands() {
        evaluate()
        backend.ready(backend.prepared[0].attempt)
        evaluate()
        runtime.cancel(CrossfadeCancelReason.Pause)
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
    }

    @Test fun audibleCommandsAreRefusedAndFailClosed() {
        evaluate()
        backend.ready(backend.prepared[0].attempt)
        val readyState = runtime.state
        val resetsBefore = backend.resets
        runtime.applyReduction(
            CrossfadeReduction(
                readyState,
                listOf(
                    CrossfadeCommand.StartSecondary(keyA, 0f),
                    CrossfadeCommand.ApplyGains(keyA, CrossfadeGains(1f, 0f)),
                ),
            ),
            snap,
        )
        assertEquals(listOf<CrossfadeCommand>(CrossfadeCommand.StartSecondary(keyA, 0f)), runtime.rejectedAudibleCommands)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(resetsBefore + 1, backend.resets) // only pre-audible cleanup
    }

    @Test fun closeIsIdempotentAndReleasesOnce() {
        evaluate()
        runtime.close()
        runtime.close()
        assertEquals(1, backend.releases)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun callbacksAfterCloseAreIgnored() {
        evaluate()
        val attempt = backend.prepared[0].attempt
        runtime.close()
        backend.ready(attempt)
        backend.error(attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun evaluationAfterCloseDoesNotResurrect() {
        runtime.close()
        evaluate()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.prepared.isEmpty())
    }

    @Test fun synchronousSupersessionDuringPrepareDoesNotCorruptNewOwner() {
        // While the secondary is preparing A, playback moves on and a nested evaluation arms B.
        backend.onPrepare = {
            if (backend.prepared.size == 1) {
                snap = snapshot(index = 2)
                evaluate()
            }
        }
        evaluate()
        assertEquals(2, backend.prepared.size)
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (runtime.state as CrossfadeState.Armed).key)
        backend.ready(backend.prepared[0].attempt) // A is stale
        assertTrue(runtime.state is CrossfadeState.Armed)
        backend.ready(backend.prepared[1].attempt)
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    @Test fun immediateReadyDuringPrepareReachesReady() {
        backend.onPrepare = { backend.ready(backend.prepared.last().attempt) }
        evaluate()
        assertTrue(runtime.state is CrossfadeState.Ready)
        assertEquals(1, backend.prepared.size)
    }

    // ── Same key is not the same plan ───────────────────────────────────────────

    @Test fun identicalKeyAndPlanRemainsIdempotent() {
        evaluate(configured = 6_000L, current = 200_000L)
        evaluate(configured = 6_000L, current = 200_000L)
        assertEquals(1, backend.prepared.size)
        assertEquals(0, backend.resets)
    }

    @Test fun changedConfiguredDurationReplacesPreparationWithNewDuration() {
        evaluate(configured = 6_000L)
        evaluate(configured = 4_000L)
        assertEquals(CrossfadeState.Armed(keyA, 4_000L, 196_000L), runtime.state)
        assertEquals(2, backend.prepared.size)
        assertEquals(1, backend.resets) // old preparation abandoned exactly once
        assertEquals(queue[2].id.toString(), backend.prepared[1].item.mediaId) // same positional target
    }

    @Test fun shrinkingLiveCurrentDurationObeysHalfCurrentBound() {
        evaluate(configured = 6_000L, current = null)
        assertEquals(CrossfadeState.Armed(keyA, 6_000L, 194_000L), runtime.state)
        evaluate(configured = 6_000L, current = 8_000L)
        assertEquals(CrossfadeState.Armed(keyA, 4_000L, 4_000L), runtime.state)
        assertEquals(2, backend.prepared.size)
        assertEquals(1, backend.resets)
    }

    @Test fun startPositionChangeWithUnchangedEffectiveDurationIsNotRetained() {
        evaluate(configured = 6_000L, current = 200_000L)
        evaluate(configured = 6_000L, current = 150_000L)
        assertEquals(CrossfadeState.Armed(keyA, 6_000L, 144_000L), runtime.state)
        assertEquals(2, backend.prepared.size)
        assertEquals(1, backend.resets)
    }

    @Test fun readyStateIsNotRetainedWhenThePlanChanges() {
        evaluate(configured = 6_000L)
        backend.ready(backend.prepared[0].attempt)
        assertTrue(runtime.state is CrossfadeState.Ready)
        evaluate(configured = 4_000L)
        assertEquals(CrossfadeState.Armed(keyA, 4_000L, 196_000L), runtime.state)
        // The old attempt can no longer make the new plan Ready.
        backend.ready(backend.prepared[0].attempt)
        assertTrue(runtime.state is CrossfadeState.Armed)
        backend.ready(backend.prepared[1].attempt)
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    @Test fun duplicateSongQueueStaysPositionalAcrossPlanReplacement() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3))
        snap = snapshot(queue = dup, index = 2)
        val requested = mutableListOf<Song>()
        val r = CrossfadePreparationRuntime(
            snapshotProvider = { snap },
            backendFactory = { backend },
            mediaItemFactory = { requested += it; MediaItem.Builder().setMediaId(it.id.toString()).build() },
        )
        r.evaluatePreparation(6_000L, null)
        r.evaluatePreparation(4_000L, null)
        assertEquals(CrossfadeTransitionKey(5L, 2, 3), (r.state as CrossfadeState.Armed).key)
        assertEquals(listOf(3L, 3L), requested.map { it.dateAdded }) // always the exact occurrence at position 3
    }

    @Test fun retainabilityHelperOnlyAcceptsMatchingArmedOrReady() {
        val plan = CrossfadeTransitionPlan.Eligible(2, 6_000L, 194_000L)
        assertTrue(isRetainablePlan(CrossfadeState.Armed(keyA, 6_000L, 194_000L), plan))
        assertTrue(isRetainablePlan(CrossfadeState.Ready(keyA, 6_000L, 194_000L), plan))
        assertFalse(isRetainablePlan(CrossfadeState.Armed(keyA, 5_000L, 194_000L), plan))
        assertFalse(isRetainablePlan(CrossfadeState.Ready(keyA, 6_000L, 190_000L), plan))
        assertFalse(isRetainablePlan(CrossfadeState.Fading(keyA, 6_000L, 1L, 0L), plan))
        assertFalse(isRetainablePlan(CrossfadeState.HandoffPending(keyA, 6_000L), plan))
    }

    // ── Repeat ownership validates the bound automatic target ───────────────────

    @Test fun repeatAllLastToFirstThenOffThenReadyWithoutEvaluationGoesIdle() {
        snap = snapshot(index = 3, repeat = RepeatMode.ALL)
        evaluate()
        assertEquals(CrossfadeTransitionKey(5L, 3, 0), (runtime.state as CrossfadeState.Armed).key)
        snap = snapshot(index = 3, repeat = RepeatMode.OFF)
        backend.ready(backend.prepared[0].attempt) // no evaluatePreparation in between
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun harmlessRepeatChangeKeepsOwnershipWhenNextOccurrenceIsUnchanged() {
        snap = snapshot(index = 1, repeat = RepeatMode.ALL)
        evaluate()
        snap = snapshot(index = 1, repeat = RepeatMode.OFF)
        assertNull(crossfadeOwnershipLossReason(snap, keyA))
        evaluate()
        assertEquals(1, backend.prepared.size) // not re-prepared
        backend.ready(backend.prepared[0].attempt)
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    @Test fun repeatOneStillCancelsWithRepeatChangedEvenOnReady() {
        evaluate()
        snap = snapshot(repeat = RepeatMode.ONE)
        assertEquals(CrossfadeCancelReason.RepeatChanged, crossfadeOwnershipLossReason(snap, keyA))
        backend.ready(backend.prepared[0].attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
    }

    @Test fun repeatOwnershipUsesExactTargetNotJustValidIndices() {
        val lastToFirst = CrossfadeTransitionKey(5L, 3, 0)
        assertNull(crossfadeOwnershipLossReason(snapshot(index = 3, repeat = RepeatMode.ALL), lastToFirst))
        assertEquals(
            CrossfadeCancelReason.RepeatChanged,
            crossfadeOwnershipLossReason(snapshot(index = 3, repeat = RepeatMode.OFF), lastToFirst),
        )
    }

    @Test fun duplicateSongQueueRepeatWrapStaysPositional() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2))
        snap = snapshot(queue = dup, index = 2, repeat = RepeatMode.ALL)
        evaluate()
        // Last -> first wraps between two occurrences of the same song id; identity is positional.
        assertEquals(CrossfadeTransitionKey(5L, 2, 0), (runtime.state as CrossfadeState.Armed).key)
        snap = snapshot(queue = dup, index = 2, repeat = RepeatMode.OFF)
        backend.ready(backend.prepared[0].attempt)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    // ── Production gate ─────────────────────────────────────────────────────────

    @Test fun productionGateRemainsFalse() {
        assertFalse(PlaybackService.CROSSFADE_SECONDARY_RUNTIME_ENABLED)
    }
}
