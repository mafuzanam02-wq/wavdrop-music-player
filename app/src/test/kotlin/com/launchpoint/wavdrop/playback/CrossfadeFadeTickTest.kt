package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2C7B: runtime FadeTick execution with exact coordinator gain pairs. Ticks are called explicitly. */
class CrossfadeFadeTickTest {

    private val events = mutableListOf<String>()

    private inner class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var releases = 0
        val starts = mutableListOf<Float>()
        val gains = mutableListOf<Float>()
        var setGainResult = true
        var setGainThrows = false
        var onSetGain: (Float) -> Unit = {}

        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean { starts += initialGain; events += "s:start"; return true }
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = null
        override fun setGain(gain: Float): Boolean {
            gains += gain
            events += "s:$gain"
            onSetGain(gain)
            if (setGainThrows) throw IllegalStateException("boom")
            return setGainResult
        }
        override fun reset() { resets++; events += "s:reset" }
        override fun release() { releases++; events += "s:release" }
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

    private fun song(id: Long, tag: Long = 0L) = Song(
        id = id, title = "S$id", artist = "Artist", album = "Album",
        albumId = 0L, duration = 200_000L, uri = "content://media/$id/$tag",
        dateAdded = tag, trackNumber = 0, year = 2020,
    )

    private fun snapshot(
        queue: List<Song> = listOf(song(1, 0), song(2, 1), song(3, 2), song(4, 3)),
        index: Int? = 1,
        generation: Long = 5L,
        playing: Boolean = true,
        dirty: Boolean = false,
    ) = CrossfadeRuntimeSnapshot(
        queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = index,
        repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = playing, isExternalPlayback = false,
        playerQueueNeedsSync = dirty, controllerConnected = true,
    )

    private var snap = snapshot()
    private val backend = FakeBackend()
    private val primary = FakePrimary()
    private val runtime = CrossfadePreparationRuntime({ snap }, { backend }, primaryGainBackend = primary)
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L
    private val begin = 10_000L

    private fun ready() {
        runtime.evaluatePreparation(6_000L, null)
        backend.ready()
    }

    /** Begins the fade for keyA at [begin] with [lateness] ms already elapsed. */
    private fun fading(lateness: Long = 0L) {
        ready()
        val due = runtime.observePrimaryPosition(keyA, startA + lateness) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(due, begin))
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    private fun tick(now: Long, key: CrossfadeTransitionKey = keyA) = runtime.executeFadeTick(key, now)

    // -- Eligibility ------------------------------------------------------------

    @Test fun nonFadingStatesAreInactiveWithoutWrites() {
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin)) // Idle
        runtime.evaluatePreparation(6_000L, null)
        assertTrue(runtime.state is CrossfadeState.Armed)
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin))
        backend.ready()
        assertTrue(runtime.state is CrossfadeState.Ready)
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin))
        assertTrue(backend.gains.isEmpty())
        assertTrue(primary.gains.isEmpty())
        assertTrue(runtime.state is CrossfadeState.Ready)
    }

    @Test fun wrongKeyIsInactiveAndDoesNotDisturbTheFade() {
        fading()
        val before = runtime.state
        val primaryBefore = primary.gains.size
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin + 3_000L, CrossfadeTransitionKey(5L, 0, 1)))
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin + 3_000L, keyA.copy(queueGeneration = 4L)))
        assertEquals(before, runtime.state)
        assertTrue(backend.gains.isEmpty())
        assertEquals(primaryBefore, primary.gains.size)
    }

    @Test fun duplicateSongQueueStaysPositional() {
        snap = snapshot(queue = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3)), index = 2)
        val key = CrossfadeTransitionKey(5L, 2, 3)
        ready()
        val due = runtime.observePrimaryPosition(key, startA) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(due, begin))
        // Another occurrence of the same song id cannot tick this fade.
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin + 3_000L, CrossfadeTransitionKey(5L, 0, 2)))
        assertTrue(backend.gains.isEmpty())
        assertEquals(FadeTickExecutionResult.Applied, tick(begin + 3_000L, key))
    }

    @Test fun closedRuntimeIsInactive() {
        fading()
        runtime.close()
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin + 3_000L))
        assertTrue(backend.gains.isEmpty())
    }

    // -- Intermediate / late begin ---------------------------------------------

    @Test fun intermediateTickAppliesExactCoordinatorPairSecondaryFirst() {
        fading()
        events.clear()
        val expected = CrossfadeGainCurve.equalPower(0.5f)
        assertEquals(FadeTickExecutionResult.Applied, tick(begin + 3_000L))
        assertEquals(listOf(expected.incoming), backend.gains)
        assertEquals(expected.outgoing, primary.gains.last(), 0f)
        assertEquals(listOf("s:${expected.incoming}", "p:${expected.outgoing}"), events)
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    @Test fun lateBeginPreservesInitialLatenessAcrossTicks() {
        fading(lateness = 1_000L)
        // 1000 initial + 2000 since begin = 3000 of 6000.
        val expected = CrossfadeGainCurve.equalPower(0.5f)
        assertEquals(FadeTickExecutionResult.Applied, tick(begin + 2_000L))
        assertEquals(listOf(expected.incoming), backend.gains)
        assertEquals(expected.outgoing, primary.gains.last(), 0f)
    }

    // -- Ownership loss ----------------------------------------------------------

    private fun assertOwnershipLoss(newSnap: CrossfadeRuntimeSnapshot, reason: CrossfadeCancelReason) {
        fading()
        snap = newSnap
        assertEquals(FadeTickExecutionResult.Cancelled(reason), tick(begin + 3_000L))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertTrue(backend.gains.isEmpty())
        assertEquals(listOf(1f, 1f), primary.gains) // begin (on-time) then restore; no tick gain
        assertEquals(1, backend.resets)
    }

    @Test fun pauseCancels() = assertOwnershipLoss(snapshot(playing = false), CrossfadeCancelReason.Pause)
    @Test fun generationChangeCancels() = assertOwnershipLoss(snapshot(generation = 6L), CrossfadeCancelReason.QueueMutation)
    @Test fun dirtyQueueCancels() = assertOwnershipLoss(snapshot(dirty = true), CrossfadeCancelReason.QueueBecameDirty)

    // -- Gain failures -----------------------------------------------------------

    @Test fun secondaryGainFailureCancelsAndRestores() {
        fading()
        backend.setGainResult = false
        assertEquals(FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.SecondaryError), tick(begin + 3_000L))
        assertEquals(listOf(1f, 1f), primary.gains) // no tick outgoing; only begin + restore
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun secondaryGainExceptionIsContainedAndCleansUpOnce() {
        fading()
        backend.setGainThrows = true
        assertEquals(FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.SecondaryError), tick(begin + 3_000L))
        assertEquals(listOf(1f, 1f), primary.gains)
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun primaryGainFailureCancelsRestoresAndAbandons() {
        fading()
        primary.failWhen = { it < 1f }
        val expected = CrossfadeGainCurve.equalPower(0.5f)
        assertEquals(FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.PrimaryGainError), tick(begin + 3_000L))
        assertEquals(listOf(1f, expected.outgoing, 1f), primary.gains)
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun primaryGainFailureKeepsOwnershipUntilRestoreSucceeds() {
        fading()
        primary.failWhen = { it < 1f || it == 1f } // tick write and restore both fail
        assertEquals(FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.PrimaryGainError), tick(begin + 3_000L))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        val writes = primary.gains.size
        primary.failWhen = { false }
        runtime.close() // ownership was retained, so close retries the restore
        assertEquals(writes + 1, primary.gains.size)
        assertEquals(1f, primary.gains.last(), 0f)
    }

    // -- Clock regression --------------------------------------------------------

    @Test fun clockRegressionCancelsWithoutTickGains() {
        fading()
        assertEquals(FadeTickExecutionResult.Cancelled(CrossfadeCancelReason.ClockRegression), tick(begin - 1L))
        assertTrue(backend.gains.isEmpty())
        assertEquals(listOf(1f, 1f), primary.gains)
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    // -- Reentrancy ----------------------------------------------------------------

    @Test fun reentrantCancelDuringSecondaryWriteSkipsOldPrimaryGain() {
        fading()
        backend.onSetGain = { runtime.cancel(CrossfadeCancelReason.Pause) }
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin + 3_000L))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(listOf(1f, 1f), primary.gains) // begin + restore; the old tick outgoing never applied
        assertEquals(1, backend.resets)
    }

    @Test fun reentrantCancelDuringPrimaryWriteIsNotResurrected() {
        fading()
        var armed = true
        primary.onWrite = { g -> if (armed && g < 1f) { armed = false; runtime.cancel(CrossfadeCancelReason.Pause) } }
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin + 3_000L))
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertEquals(1f, primary.gains.last(), 0f)
    }

    // -- Terminal ----------------------------------------------------------------

    @Test fun terminalTickAppliesFinalGainsAfterHandoffPendingIsVisible() {
        fading()
        val stateSeen = mutableListOf<CrossfadeState>()
        backend.onSetGain = { stateSeen += runtime.state }
        primary.onWrite = { stateSeen += runtime.state }
        events.clear()
        assertEquals(FadeTickExecutionResult.HandoffPending, tick(begin + 6_000L))
        assertEquals(CrossfadeState.HandoffPending(keyA, 6_000L), runtime.state)
        assertEquals(2, stateSeen.size)
        assertTrue(stateSeen.all { it == CrossfadeState.HandoffPending(keyA, 6_000L) })
        val finalGains = CrossfadeGainCurve.equalPower(1f) // coordinator authority (outgoing is ~6e-17, not literal 0)
        assertEquals(1f, finalGains.incoming, 0f)
        assertEquals(0f, finalGains.outgoing, 1e-6f)
        assertEquals(listOf("s:${finalGains.incoming}", "p:${finalGains.outgoing}"), events)
        assertEquals(0, backend.resets) // still started, not torn down
        assertTrue(runtime.rejectedAudibleCommands.isEmpty()) // RequestHandoff recognised, not rejected
    }

    @Test fun duplicateTerminalTickIsInactive() {
        fading()
        assertEquals(FadeTickExecutionResult.HandoffPending, tick(begin + 6_000L))
        val s = backend.gains.size
        val p = primary.gains.size
        assertEquals(FadeTickExecutionResult.Inactive, tick(begin + 6_500L))
        assertEquals(s, backend.gains.size)
        assertEquals(p, primary.gains.size)
        assertEquals(0, backend.resets)
        assertEquals(CrossfadeState.HandoffPending(keyA, 6_000L), runtime.state)
    }

    @Test fun cancelFromHandoffPendingRestoresPrimaryAndAbandonsSecondary() {
        fading()
        tick(begin + 6_000L)
        runtime.cancel(CrossfadeCancelReason.Pause)
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(1, backend.resets)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun closeFromHandoffPendingRestoresPrimaryBeforeSecondaryRelease() {
        fading()
        tick(begin + 6_000L)
        events.clear()
        runtime.close()
        assertEquals(listOf("p:1.0", "s:reset", "s:release"), events)
        assertEquals(CrossfadeState.Idle, runtime.state)
    }

    @Test fun genericRequestHandoffStillRefusedAndCleansUp() {
        fading()
        tick(begin + 3_000L)
        runtime.applyReduction(CrossfadeReduction(runtime.state, listOf(CrossfadeCommand.RequestHandoff(keyA))), snap)
        assertEquals(listOf<CrossfadeCommand>(CrossfadeCommand.RequestHandoff(keyA)), runtime.rejectedAudibleCommands)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(1, backend.resets)
        assertFalse(runtime.state is CrossfadeState.Fading)
    }
}
