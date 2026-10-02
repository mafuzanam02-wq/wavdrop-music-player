package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2C7C: evaluatePreparation is lifecycle-managed (never re-planned) while Fading / HandoffPending. */
class CrossfadeActiveFadeEvaluationTest {

    private inner class FakeBackend : SecondaryPlayerBackend {
        val prepared = mutableListOf<Long>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        var releases = 0
        val gains = mutableListOf<Float>()
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean { gains += gain; return true }
        override fun reset() { resets++ }
        override fun release() { releases++ }
        fun ready() = callbacks!!.onReady(prepared.last(), 180_000L)
    }

    private class FakePrimary : PrimaryGainBackend {
        val gains = mutableListOf<Float>()
        override fun setGain(gain: Float): Boolean { gains += gain; return true }
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
    ) = CrossfadeRuntimeSnapshot(
        queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = index,
        repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = playing, isExternalPlayback = false,
        playerQueueNeedsSync = false, controllerConnected = true,
    )

    private var snap = snapshot()
    private var snapshotCalls = 0
    private val backend = FakeBackend()
    private val primary = FakePrimary()
    private val runtime = CrossfadePreparationRuntime({ snapshotCalls++; snap }, { backend }, primaryGainBackend = primary)
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L
    private val begin = 10_000L

    private fun fading() {
        runtime.evaluatePreparation(6_000L, null)
        backend.ready()
        val due = runtime.observePrimaryPosition(keyA, startA) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(due, begin))
        assertTrue(runtime.state is CrossfadeState.Fading)
    }

    private fun handoffPending() {
        fading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, begin + 6_000L))
    }

    /** Asserts an evaluation neither changed state nor touched either player, and used exactly one snapshot. */
    private fun assertRetained(configured: Long, current: Long?) {
        val state = runtime.state
        val prepared = backend.prepared.size
        val primaryWrites = primary.gains.size
        val secondaryWrites = backend.gains.size
        val calls = snapshotCalls
        runtime.evaluatePreparation(configured, current)
        assertEquals(state, runtime.state)
        assertEquals(prepared, backend.prepared.size)
        assertEquals(primaryWrites, primary.gains.size)
        assertEquals(secondaryWrites, backend.gains.size)
        assertEquals(0, backend.resets)
        assertEquals(0, backend.releases)
        assertEquals(calls + 1, snapshotCalls)
    }

    // -- Fading retention ----------------------------------------------------------

    @Test fun fadingSameConfigIsRetained() {
        fading()
        assertRetained(6_000L, null)
        assertRetained(6_000L, 200_000L)
    }

    @Test fun fadingEnabledDurationChangeIsDeferred() {
        fading()
        assertRetained(3_000L, 200_000L)
        assertRetained(12_000L, 200_000L)
        assertEquals(6_000L, (runtime.state as CrossfadeState.Fading).effectiveDurationMs)
    }

    @Test fun fadingIgnoresCurrentDurationChangesAndUnknownDuration() {
        fading()
        assertRetained(6_000L, null)
        assertRetained(6_000L, 1_000L) // would make a fresh plan unavailable
        assertRetained(6_000L, 10_000_000L)
    }

    // -- Fading cancellation ---------------------------------------------------------

    private fun assertCancelledNoRearm(prepared: Int = 1) {
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primary.gains.last(), 0f)
        assertEquals(1, backend.resets)
        assertEquals(prepared, backend.prepared.size) // no replacement prepared in the same call
    }

    @Test fun explicitOffCancelsWhileFading() {
        fading()
        runtime.evaluatePreparation(0L, 200_000L)
        assertCancelledNoRearm()
    }

    @Test fun negativeConfiguredDurationIsOffWhileFading() {
        fading()
        runtime.evaluatePreparation(-5L, 200_000L)
        assertCancelledNoRearm()
    }

    @Test fun queueGenerationChangeCancelsWithoutRearm() {
        fading()
        snap = snapshot(generation = 6L)
        runtime.evaluatePreparation(6_000L, null)
        assertCancelledNoRearm()
    }

    @Test fun occurrenceChangeCancelsWithoutRearm() {
        fading()
        snap = snapshot(index = 2) // a fresh plan 2 -> 3 would be eligible
        runtime.evaluatePreparation(6_000L, null)
        assertCancelledNoRearm()
    }

    @Test fun pauseCancelsWithoutRearm() {
        fading()
        snap = snapshot(playing = false)
        runtime.evaluatePreparation(6_000L, null)
        assertCancelledNoRearm()
    }

    @Test fun duplicateSongIdsDoNotKeepAChangedOccurrenceOwned() {
        val dup = listOf(song(10, 0), song(20, 1), song(10, 2), song(10, 3), song(10, 4))
        snap = snapshot(queue = dup, index = 2)
        val key = CrossfadeTransitionKey(5L, 2, 3)
        runtime.evaluatePreparation(6_000L, null)
        backend.ready()
        val due = runtime.observePrimaryPosition(key, startA) as FadeWindowObservation.Due
        assertTrue(runtime.executeBeginFade(due, begin))
        assertRetained(6_000L, null) // same live positional key
        snap = snapshot(queue = dup, index = 3) // same song id, different occurrence
        runtime.evaluatePreparation(6_000L, null)
        assertCancelledNoRearm()
    }

    // -- HandoffPending ----------------------------------------------------------------

    @Test fun handoffPendingIsRetainedAndKeepsTerminalGains() {
        handoffPending()
        val terminalOutgoing = primary.gains.last()
        assertRetained(6_000L, null)
        assertRetained(3_000L, 200_000L) // enabled change ignored
        assertEquals(CrossfadeState.HandoffPending(keyA, 6_000L), runtime.state)
        assertEquals(terminalOutgoing, primary.gains.last(), 0f)
    }

    @Test fun handoffPendingOffCancels() {
        handoffPending()
        runtime.evaluatePreparation(0L, 200_000L)
        assertCancelledNoRearm()
    }

    @Test fun handoffPendingOwnershipLossCancelsWithoutRearm() {
        handoffPending()
        snap = snapshot(generation = 6L)
        runtime.evaluatePreparation(6_000L, null)
        assertCancelledNoRearm()
    }

    // -- Pre-audible behaviour unchanged -------------------------------------------------

    @Test fun armedChangedPlanStillReplacesPreparation() {
        runtime.evaluatePreparation(6_000L, null)
        assertTrue(runtime.state is CrossfadeState.Armed)
        runtime.evaluatePreparation(4_000L, null)
        assertEquals(2, backend.prepared.size)
        assertEquals(4_000L, (runtime.state as CrossfadeState.Armed).effectiveDurationMs)
    }

    @Test fun readyChangedPlanStillReplacesPreparation() {
        runtime.evaluatePreparation(6_000L, null)
        backend.ready()
        assertTrue(runtime.state is CrossfadeState.Ready)
        runtime.evaluatePreparation(4_000L, null)
        assertEquals(1, backend.resets) // old preparation abandoned
        assertEquals(2, backend.prepared.size)
        assertEquals(4_000L, (runtime.state as CrossfadeState.Armed).effectiveDurationMs)
        backend.ready()
        assertEquals(4_000L, (runtime.state as CrossfadeState.Ready).effectiveDurationMs)
    }
}
