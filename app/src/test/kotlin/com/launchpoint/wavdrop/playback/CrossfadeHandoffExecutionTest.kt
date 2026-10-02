package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2D3: explicit runtime handoff execution from HandoffPending. */
class CrossfadeHandoffExecutionTest {

    private val events = mutableListOf<String>()

    private inner class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var snapshot: SecondaryHandoffSnapshot? = SecondaryHandoffSnapshot(6_125L, 180_000L)
        var snapshotCalls = 0
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean = true
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? {
            snapshotCalls++
            events += "s:snapshot"
            return snapshot
        }
        override fun reset() { resets++; events += "s:reset" }
        override fun release() { events += "s:release" }
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private inner class FakePrimary : PrimaryGainBackend {
        val gains = mutableListOf<Float>()
        var failWhen: (Float) -> Boolean = { false }
        var onWrite: (Float) -> Unit = {}
        override fun setGain(gain: Float): Boolean {
            gains += gain
            events += "p:$gain"
            onWrite(gain)
            return !failWhen(gain)
        }
    }

    private inner class FakeReconciler : CrossfadePrimaryReconciler {
        val calls = mutableListOf<Pair<CrossfadeTransitionKey, SecondaryHandoffSnapshot>>()
        var result: CrossfadePrimaryReconciliationResult = CrossfadePrimaryReconciliationResult.Succeeded
        var throws = false
        var onReconcile: () -> Unit = {}
        override fun reconcile(key: CrossfadeTransitionKey, snapshot: SecondaryHandoffSnapshot): CrossfadePrimaryReconciliationResult {
            calls += key to snapshot
            events += "reconcile"
            onReconcile()
            if (throws) throw IllegalStateException("boom")
            return result
        }
    }

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private fun snapshot(
        queue: List<Song> = listOf(song(1, 0), song(2, 1), song(3, 2), song(4, 3)),
        index: Int? = 1,
        generation: Long = 5L,
    ) = CrossfadeRuntimeSnapshot(
        queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = index,
        repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
        playerQueueNeedsSync = false, controllerConnected = true,
    )

    private var snap = snapshot()
    private val backend = FakeBackend()
    private val primary = FakePrimary()
    private val reconciler = FakeReconciler()
    private val runtime = CrossfadePreparationRuntime(
        { snap }, { backend }, primaryGainBackend = primary, primaryReconciler = reconciler,
    )
    private var keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L
    private val begin = 10_000L

    private fun fading() {
        runtime.evaluatePreparation(6_000L, null)
        backend.ready()
        val due = runtime.observePrimaryPosition(keyA, startA) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(due, begin))
    }

    private fun pending() {
        fading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, begin + 6_000L))
        assertEquals(CrossfadeState.HandoffPending(keyA, 6_000L), runtime.state)
        events.clear()
    }

    private fun assertCleanedUp() {
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(1, backend.resets)
    }

    // -- Eligibility ------------------------------------------------------------------

    @Test fun nonPendingStatesAreInactiveWithoutEffects() {
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA)) // Idle
        runtime.evaluatePreparation(6_000L, null)
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA)) // Armed
        backend.ready()
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA)) // Ready
        val due = runtime.observePrimaryPosition(keyA, startA) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(due, begin))
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA)) // Fading
        assertEquals(0, backend.snapshotCalls)
        assertTrue(reconciler.calls.isEmpty())
        assertEquals(0, backend.resets)
        assertEquals(listOf(1f), primary.gains) // only the begin write
    }

    @Test fun wrongKeyIsInert() {
        pending()
        val other = CrossfadeTransitionKey(5L, 0, 1)
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(other))
        assertEquals(CrossfadeState.HandoffPending(keyA, 6_000L), runtime.state)
        assertEquals(0, backend.snapshotCalls)
        assertTrue(reconciler.calls.isEmpty())
    }

    @Test fun closedRuntimeIsInactive() {
        pending()
        runtime.close()
        events.clear()
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA))
        assertTrue(events.isEmpty())
    }

    // -- Ownership ------------------------------------------------------------------------

    @Test fun generationLossCancelsBeforeSnapshot() {
        pending()
        snap = snapshot(generation = 6L)
        assertEquals(CrossfadeHandoffExecutionResult.Cancelled(CrossfadeCancelReason.QueueMutation), runtime.executeHandoff(keyA))
        assertCleanedUp()
        assertEquals(0, backend.snapshotCalls)
        assertTrue(reconciler.calls.isEmpty())
    }

    @Test fun manualNavigationLossCancelsBeforeSnapshot() {
        pending()
        snap = snapshot(index = 2)
        assertEquals(CrossfadeHandoffExecutionResult.Cancelled(CrossfadeCancelReason.ManualNavigation), runtime.executeHandoff(keyA))
        assertCleanedUp()
        assertEquals(0, backend.snapshotCalls)
        assertTrue(reconciler.calls.isEmpty())
    }

    // -- Failures ----------------------------------------------------------------------------

    @Test fun unavailableSnapshotFailsThroughHandoffFailed() {
        pending()
        backend.snapshot = null
        assertEquals(
            CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.SecondarySnapshotUnavailable),
            runtime.executeHandoff(keyA),
        )
        assertCleanedUp()
        assertTrue(reconciler.calls.isEmpty())
    }

    @Test fun snapshotIsForwardedUnchangedWithTheExactKey() {
        pending()
        runtime.executeHandoff(keyA)
        assertEquals(listOf(keyA to SecondaryHandoffSnapshot(6_125L, 180_000L)), reconciler.calls)
    }

    @Test fun everyPrimaryRejectionIsPreservedAndFailsClosed() {
        for (reason in CrossfadePrimaryReconciliationRejection.entries) {
            events.clear()
            val b = FakeBackend()
            val p = FakePrimary()
            val r = FakeReconciler().also {
                it.result = CrossfadePrimaryReconciliationResult.Rejected(reason)
            }
            val rt = CrossfadePreparationRuntime({ snap }, { b }, primaryGainBackend = p, primaryReconciler = r)
            rt.evaluatePreparation(6_000L, null)
            b.ready()
            val due = rt.observePrimaryPosition(keyA, startA) as FadeWindowObservation.Due
            assertTrue(rt.executeBeginFade(due, begin))
            rt.executeFadeTick(keyA, begin + 6_000L)
            assertEquals(
                "$reason",
                CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.PrimaryReconciliationRejected(reason)),
                rt.executeHandoff(keyA),
            )
            assertEquals(CrossfadeState.Idle, rt.state)
            assertEquals(1f, p.gains.last(), 0f)
            assertEquals(1, b.resets)
        }
    }

    @Test fun reconcilerExceptionIsContainedAndCleansUp() {
        pending()
        reconciler.throws = true
        assertEquals(
            CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.PrimaryReconciliationException),
            runtime.executeHandoff(keyA),
        )
        assertCleanedUp()
    }

    // -- Success -------------------------------------------------------------------------------

    @Test fun successRestoresPrimaryBeforeAbandoningSecondaryAndEndsIdle() {
        pending()
        assertEquals(CrossfadeHandoffExecutionResult.Succeeded, runtime.executeHandoff(keyA))
        assertEquals(listOf("s:snapshot", "reconcile", "p:1.0", "s:reset"), events)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets) // exactly one secondary reset
        assertEquals(1, events.count { it == "p:1.0" }) // exactly one primary restore
        assertTrue(runtime.rejectedAudibleCommands.isEmpty())
    }

    @Test fun duplicateSongQueueForwardsTheExactPositionalKey() {
        snap = snapshot(queue = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3)), index = 2)
        keyA = CrossfadeTransitionKey(5L, 2, 3)
        pending()
        assertEquals(CrossfadeHandoffExecutionResult.Succeeded, runtime.executeHandoff(keyA))
        assertEquals(keyA, reconciler.calls.single().first)
    }

    // -- Restore failure ---------------------------------------------------------------------------

    @Test fun firstRestoreFailureNeverReportsSuccessAndRetriesViaCleanup() {
        pending()
        var failed = false
        primary.failWhen = { g -> if (g == 1f && !failed) { failed = true; true } else false }
        assertEquals(
            CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.PrimaryGainRestoreFailed),
            runtime.executeHandoff(keyA),
        )
        assertCleanedUp()
        assertEquals(2, events.count { it == "p:1.0" }) // failed attempt + coordinator cleanup retry
    }

    @Test fun persistentRestoreFailureStillClosesIdleAndRetainsOwnershipForClose() {
        pending()
        primary.failWhen = { it == 1f }
        assertEquals(
            CrossfadeHandoffExecutionResult.Failed(CrossfadeHandoffFailure.PrimaryGainRestoreFailed),
            runtime.executeHandoff(keyA),
        )
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets) // secondary still abandoned
        val writes = primary.gains.size
        primary.failWhen = { false }
        runtime.close() // retained ownership: close retries the restore
        assertEquals(writes + 1, primary.gains.size)
        assertEquals(1f, primary.gains.last(), 0f)
    }

    // -- Reentrancy ----------------------------------------------------------------------------------

    @Test fun reentrantCancelDuringReconciliationWins() {
        pending()
        reconciler.onReconcile = { runtime.cancel(CrossfadeCancelReason.Pause) }
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, events.count { it == "p:1.0" }) // only the cancel's restore, no stale success restore
        assertEquals(1, backend.resets)
    }

    @Test fun reentrantCancelDuringPrimaryRestoreWins() {
        pending()
        var armed = true
        primary.onWrite = { g -> if (armed && g == 1f) { armed = false; runtime.cancel(CrossfadeCancelReason.Pause) } }
        assertEquals(CrossfadeHandoffExecutionResult.Inactive, runtime.executeHandoff(keyA))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets) // the cancel's abandon only; no stale success cleanup
    }
}
