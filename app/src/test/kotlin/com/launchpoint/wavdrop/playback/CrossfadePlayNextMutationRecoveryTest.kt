package com.launchpoint.wavdrop.playback

import androidx.media3.common.MediaItem
import com.launchpoint.wavdrop.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CF-2H3A: an explicit Play Next-family command synchronously cancels any owned crossfade (QueueMutation) before the queue mutation and generation bump; the driver is left alone. */
class CrossfadePlayNextMutationRecoveryTest {

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
        val preparedMediaIds = mutableListOf<String>()
        var callbacks: SecondaryBackendCallbacks? = null
        var resets = 0
        val gains = mutableListOf<Float>()
        override fun prepare(attempt: Long, item: MediaItem, callbacks: SecondaryBackendCallbacks) {
            prepared += attempt
            preparedMediaIds += item.mediaId
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
        mediaItemFactory = { MediaItem.Builder().setMediaId(it.id.toString()).build() },
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

    private fun playNextMutation() = recoverCrossfadeFromPlayNextMutation(runtime)

    @Test fun nullRuntimeIsHarmless() {
        recoverCrossfadeFromPlayNextMutation(null)
    }

    @Test fun idleStaysIdleWithNoEffects() {
        playNextMutation()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(0, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun armedAbandonsSecondaryWithoutPrimaryWrites() {
        toArmed()
        playNextMutation()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun readyAbandonsSecondary() {
        toReady()
        playNextMutation()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1, backend.resets)
        assertTrue(primaryWrites.isEmpty())
        assertEquals(0, reconcileCalls)
    }

    @Test fun fadingRestoresPrimaryBeforeAbandoningSecondary() {
        toFading()
        events.clear()
        playNextMutation()
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
        playNextMutation()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(1f, primaryWrites.last(), 0f)
        assertEquals(listOf("p:1.0", "s:reset"), events)
        assertEquals(0, reconcileCalls)
    }

    @Test fun repeatedPlayNextMutationIsIdempotent() {
        toFading()
        playNextMutation()
        val writes = primaryWrites.size
        playNextMutation()
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertEquals(writes, primaryWrites.size)
        assertEquals(1, backend.resets)
        assertEquals(0, reconcileCalls)
    }

    // Queue states after each family command, with current = index 1 (song 2) and generation 5 -> 6.
    private fun applyPlayNext() { // Play Next X(9): [1,2,9,3,4]
        generation = 6L
        queue = listOf(song(1), song(2), song(9), song(3), song(4))
    }

    private fun applyPlayAllNext() { // Play All Next [X(9), Y(8)]: [1,2,9,8,3,4]
        generation = 6L
        queue = listOf(song(1), song(2), song(9), song(8), song(3), song(4))
    }

    private fun applyMoveToPlayNext() { // move 5th item (id 5) to play next: [1,2,5,3,4]
        generation = 6L
        queue = listOf(song(1), song(2), song(5), song(3), song(4))
    }

    private fun assertFreshTargetIs(expectedSongId: Long) {
        position = 0L
        now += 1_000L
        scheduler.runNext()
        val armed = runtime.state as CrossfadeState.Armed
        assertEquals(CrossfadeTransitionKey(6L, 1, 2), armed.key) // never the old (5, 1, 2)
        assertEquals(expectedSongId.toString(), backend.preparedMediaIds.last())
    }

    @Test fun helperUsesTheQueueMutationReason() {
        assertEquals(CrossfadeCancelReason.QueueMutation, PLAY_NEXT_MUTATION_CANCEL_REASON)
        toArmed()
        val reduction = reduceCrossfade(runtime.state, CrossfadeEvent.Cancel(PLAY_NEXT_MUTATION_CANCEL_REASON))
        assertEquals(CrossfadeCancelReason.QueueMutation, reduction.cancelReason)
    }

    @Test fun generationMismatchFallbackStillReportsQueueMutation() {
        applyPlayNext()
        val snap = CrossfadeRuntimeSnapshot(
            queueGeneration = generation, playbackQueue = queue, currentPlaybackIndex = 1,
            repeatMode = RepeatMode.OFF, shuffleEnabled = false, isPlaying = true, isExternalPlayback = false,
            playerQueueNeedsSync = false, controllerConnected = true,
        )
        assertEquals(CrossfadeCancelReason.QueueMutation, crossfadeOwnershipLossReason(snap, keyA))
    }

    @Test fun singlePlayNextPlansFreshlyTowardTheInsertedOccurrence() {
        toFading()
        val pending = scheduler.activeNow.single()
        playNextMutation()
        applyPlayNext()
        assertTrue(pending.active) // driver not stopped
        assertEquals(CrossfadeState.Idle, runtime.state)
        assertFreshTargetIs(9L)
        assertEquals(0, reconcileCalls)
    }

    @Test fun playAllNextPlansFreshlyTowardTheFirstInsertedOccurrence() {
        toFading()
        playNextMutation()
        applyPlayAllNext()
        assertFreshTargetIs(9L)
    }

    @Test fun moveToPlayNextPlansFreshlyTowardTheMovedOccurrence() {
        toFading()
        playNextMutation()
        applyMoveToPlayNext()
        assertFreshTargetIs(5L)
    }

    @Test fun duplicateSongOccurrencesStayDistinctByIndexAndGeneration() {
        toFading()
        playNextMutation()
        generation = 6L
        queue = listOf(song(1), song(2), song(2), song(3), song(4)) // the same song inserted as the next occurrence
        assertFreshTargetIs(2L)
    }

    @Test fun staleOldKeyCallbacksNeverTickOrHandOffAfterTheMutation() {
        toFading()
        assertEquals(FadeTickExecutionResult.HandoffPending, runtime.executeFadeTick(keyA, now + 6_000L))
        playNextMutation()
        applyPlayNext()
        val writesBefore = primaryWrites.size
        val gainsBefore = backend.gains.size
        runtime.executeFadeTick(keyA, now + 1_000L)
        assertEquals(writesBefore, primaryWrites.size)
        assertEquals(gainsBefore, backend.gains.size)
        assertTrue(runtime.state !is CrossfadeState.Fading && runtime.state !is CrossfadeState.HandoffPending)
        assertEquals(0, reconcileCalls)
    }
}
