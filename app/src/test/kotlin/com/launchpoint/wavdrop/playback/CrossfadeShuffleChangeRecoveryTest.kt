package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2H2: an explicit logical shuffle toggle synchronously cancels any owned crossfade before shuffle planning and the generation bump; the driver is left alone. */
class CrossfadeShuffleChangeRecoveryTest {

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
        val gains = mutableListOf<Float>()
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            this.callbacks = callbacks
        }
        override fun start(initialGain: Float): Boolean = true
        override fun setGain(gain: Float): Boolean { gains += gain; return true }
        override fun handoffSnapshot(): SecondaryHandoffSnapshot? = SecondaryHandoffSnapshot(6_125L, 180_000L)
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
    private val scheduler = FakeScheduler()
    private val backend = FakeBackend()
    private val primaryWrites = mutableListOf<Float>()
    private var reconcileCalls = 0
    private var currentIndex = 1
    private var position = 0L
    private var now = 10_000L

    private val graph = createCrossfadeProductionGraph(
        snapshotProvider = {
            CrossfadeRuntimeSnapshot(
                queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = currentIndex,
                repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
                playerQueueNeedsSync = false, controllerConnected = true,
            )
        },
        backendFactory = { backend },
        primaryGainBackend = PrimaryGainBackend { primaryWrites += it; events += "p:$it"; true },
        reconcilePrimary = { _, _ -> reconcileCalls++; CrossfadePrimaryReconciliationResult.Succeeded },
        scheduler = scheduler,
        clock = { now },
        configuredDurationMsProvider = { 6_000L },
        primaryDurationMs = { 200_000L },
        primaryPositionMs = { position },
    )
    private val runtime get() = graph.runtime
    private val keyA = CrossfadeTransitionKey(5L, 1, 2)
    private val startA = 194_000L

    private fun toArmed() {
        graph.timingDriver.start()
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Armed)
    }

    private fun toReady() { toArmed(); backend.ready(); assertTrue(runtime.state is CrossfadeState.Ready) }

    private fun toFading() {
        toReady()
        position = startA + 1_000L // late begin so the primary is genuinely attenuated
        scheduler.runNext()
        assertTrue(runtime.state is CrossfadeState.Fading)
        assertTrue(primaryWrites.last() < 1f)
    }

    private fun shuffleChanged() = recoverCrossfadeFromShuffleChange(runtime)

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromShuffleChange(null)
    }

    @Test fun idleStaysIdleWithNoEffects() {
        shuffleChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun armedAbandonsSecondaryWithoutPrimaryWrites() {
        toArmed()
        shuffleChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun readyAbandonsSecondary() {
        toReady()
        shuffleChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun fadingRestoresPrimaryBeforeAbandoningSecondary() {
        toFading()
        events.clear()
        shuffleChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    @Test fun handoffPendingCleansUpWithoutHandoff() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        events.clear()
        shuffleChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun repeatedShuffleChangeIsIdempotent() {
        toFading()
        shuffleChanged()
        val writes = primaryWrites.size
        shuffleChanged()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writes, primaryWrites.size)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    // The logical shuffle mutation the explicit command precedes: new generation + new playback order.
    private fun applyShuffleMutation() {
        generation = 6L
        queue = listOf(song(1), song(3), song(4), song(2))
    }

    @Test fun helperUsesTheShuffleChangedReason() {
        assertEquals(CrossfadeCancelReason.ShuffleChanged, SHUFFLE_CHANGE_CANCEL_REASON)
        toArmed()
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.Cancel(SHUFFLE_CHANGE_CANCEL_REASON))
        assertEquals(CrossfadeCancelReason.ShuffleChanged, reduction.cancelReason)
    }

    // Defensive fallback is a different layer: a bypassing generation change still surfaces QueueMutation.
    @Test fun generationMismatchFallbackStillReportsQueueMutation() {
        val snap = CrossfadeRuntimeSnapshot(
            queueGeneration = 6L, playbackQueue = queue, currentPlaybackIndex = 1,
            repeatMode = RepeatMode.OFF, shuffleEnabled = true, isPlaying = true, isExternalPlayback = false,
            playerQueueNeedsSync = false, controllerConnected = true,
        )
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snap, keyA))
    }

    @Test fun driverKeepsRunningAndNextPulsePlansFreshlyWithTheNewGeneration() {
        toFading()
        val pending = scheduler.activeNow.single()
        shuffleChanged()
        applyShuffleMutation()
        assertTrue(pending.active) // driver not stopped
        assertEquals(CrossfadeState.Idle, runtime.state)
        val gainsBefore = backend.gains.size
        val writesBefore = primaryWrites.size
        position = 0L
        now += 1_000L
        scheduler.runNext()
        assertEquals(gainsBefore, backend.gains.size) // no tick of the cancelled fade
        assertEquals(writesBefore, primaryWrites.size)
        assertEquals(0, reconcileCalls)
        val armed = runtime.state as CrossfadeState.Armed
        assertEquals(CrossfadeTransitionKey(6L, 1, 2), armed.key) // never the old (5, 1, 2)
        assertEquals(1, scheduler.activeNow.size)
    }

    @Test fun staleHandoffPendingCallbackNeverHandsOffAfterShuffle() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        shuffleChanged()
        applyShuffleMutation()
        position = 0L
        scheduler.runNext()
        assertTrue(runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(0, reconcileCalls)
    }

    @Test fun staleOldKeyTickIsRefusedAfterShuffle() {
        toFading()
        shuffleChanged()
        applyShuffleMutation()
        val writesBefore = primaryWrites.size
        runtime.executeFadeTick(keyA, now + 1_000L)
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writesBefore, primaryWrites.size)
    }
}
